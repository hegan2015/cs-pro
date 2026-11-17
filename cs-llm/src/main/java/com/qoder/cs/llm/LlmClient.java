package com.qoder.cs.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.llm.LlmModel.ChatMessage;
import com.qoder.cs.llm.LlmModel.ChatResult;
import com.qoder.cs.llm.LlmModel.ToolCall;
import com.qoder.cs.llm.LlmModel.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容 Chat Completions 客户端（JDK HttpClient，零第三方 SDK）。
 * 支持 tools / stream / 失败重试一次 / 连续失败熔断。
 */
@Slf4j
@Component
public class LlmClient {

    private final LlmProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0;

    public LlmClient(LlmProperties props) {
        this.props = props;
    }

    public boolean available() {
        return props.configured() && System.currentTimeMillis() >= circuitOpenUntil;
    }

    public String model() {
        return props.getModel();
    }

    /**
     * @param onToken 非 null 时走流式（最终回答轮）；null 走非流式（工具调用轮）。
     */
    public ChatResult chat(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        if (!props.configured()) {
            throw new LlmException("LLM 未配置 apiKey");
        }
        if (System.currentTimeMillis() < circuitOpenUntil) {
            throw new LlmException("LLM 熔断中，暂走知识库降级");
        }
        try {
            ChatResult r = doChat(messages, tools, onToken);
            consecutiveFailures.set(0);
            return r;
        } catch (LlmException e) {
            recordFailure();
            throw e;
        } catch (Exception e) {
            // 网络/超时类失败重试一次（对话生成无资金副作用）
            try {
                ChatResult r = doChat(messages, tools, onToken);
                consecutiveFailures.set(0);
                return r;
            } catch (Exception retry) {
                recordFailure();
                throw new LlmException("LLM 调用失败: " + retry.getMessage(), retry);
            }
        }
    }

    private void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= props.getCircuitFailureThreshold()) {
            circuitOpenUntil = System.currentTimeMillis() + props.getCircuitOpenMs();
            log.warn("LLM 连续失败 {} 次，熔断 {}ms", n, props.getCircuitOpenMs());
        }
    }

    private ChatResult doChat(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken)
            throws Exception {
        boolean stream = onToken != null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", serializeMessages(messages));
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> ts = new ArrayList<>();
            for (ToolSpec t : tools) {
                ts.add(Map.of("type", "function", "function",
                        Map.of("name", t.getName(), "description", t.getDescription(),
                                "parameters", t.getParameters())));
            }
            body.put("tools", ts);
        }
        if (stream) {
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        ChatResult result = new ChatResult();
        if (!stream) {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("LLM HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            JsonNode root = json.readTree(resp.body());
            JsonNode msg = root.path("choices").path(0).path("message");
            result.setContent(msg.path("content").asText(null));
            result.setToolCalls(parseToolCalls(msg.path("tool_calls")));
            fillUsage(result, root.path("usage"));
        } else {
            HttpResponse<Stream<String>> resp = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() / 100 != 2) {
                String err = resp.body().reduce("", (a, b) -> a + b);
                throw new LlmException("LLM HTTP " + resp.statusCode() + ": " + truncate(err));
            }
            StringBuilder content = new StringBuilder();
            Map<Integer, ToolCall> partialCalls = new TreeMap<>();
            try (Stream<String> lines = resp.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode chunk = json.readTree(data);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    String piece = delta.path("content").asText(null);
                    if (piece != null && !piece.isEmpty()) {
                        content.append(piece);
                        onToken.accept(piece);
                    }
                    mergeStreamToolCalls(delta.path("tool_calls"), partialCalls);
                    fillUsage(result, chunk.path("usage"));
                }
            }
            result.setContent(content.length() == 0 ? null : content.toString());
            result.setToolCalls(partialCalls.isEmpty() ? null : new ArrayList<>(partialCalls.values()));
        }
        return result;
    }

    private List<Map<String, Object>> serializeMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.getRole());
            if (m.getContent() != null) {
                mm.put("content", m.getContent());
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (ToolCall tc : m.getToolCalls()) {
                    tcs.add(Map.of("id", tc.getId(), "type", "function",
                            "function", Map.of("name", tc.getName(),
                                    "arguments", tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson())));
                }
                mm.put("tool_calls", tcs);
            }
            if (m.getToolCallId() != null) {
                mm.put("tool_call_id", m.getToolCallId());
            }
            out.add(mm);
        }
        return out;
    }

    private List<ToolCall> parseToolCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : node) {
            calls.add(new ToolCall(tc.path("id").asText(),
                    tc.path("function").path("name").asText(),
                    tc.path("function").path("arguments").asText("{}")));
        }
        return calls;
    }

    private void mergeStreamToolCalls(JsonNode node, Map<Integer, ToolCall> partial) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode tc : node) {
            int idx = tc.path("index").asInt(0);
            ToolCall cur = partial.computeIfAbsent(idx, k -> new ToolCall("", "", ""));
            if (tc.hasNonNull("id")) {
                cur.setId(tc.get("id").asText());
            }
            JsonNode fn = tc.path("function");
            if (fn.hasNonNull("name")) {
                cur.setName(cur.getName() + fn.get("name").asText());
            }
            if (fn.hasNonNull("arguments")) {
                cur.setArgumentsJson(cur.getArgumentsJson() + fn.get("arguments").asText());
            }
        }
    }

    private static void fillUsage(ChatResult result, JsonNode usage) {
        if (usage != null && usage.isObject()) {
            result.setPromptTokens(usage.path("prompt_tokens").asLong(0));
            result.setCompletionTokens(usage.path("completion_tokens").asLong(0));
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}