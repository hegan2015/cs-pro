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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容 Chat Completions 客户端（JDK HttpClient，零第三方 SDK）。
 * 支持 tools / stream / 按错误类型退避重试 / 按端点（baseUrl+model）独立熔断 + half-open 探测。
 */
@Slf4j
@Component
public class LlmClient {

    private final LlmProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 熔断按端点分键：某供应商抖动不拖垮其他供应商/模型 */
    private final Map<String, Breaker> breakers = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Breaker {
        final java.util.concurrent.atomic.AtomicInteger fails =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile long openUntil;
    }

    public LlmClient(LlmProperties props) {
        this.props = props;
    }

    /** 供应商端点：主→备路由链的最小单元，熔断按端点分键 */
    private record Endpoint(String baseUrl, String model, String apiKey) {
        String key() {
            return baseUrl.replaceAll("/+$", "") + "|" + model;
        }
    }

    private Endpoint primary() {
        return new Endpoint(props.getBaseUrl(), props.getModel(), props.getApiKey());
    }

    private Endpoint backup() {
        if (props.getBaseUrlBackup() == null || props.getBaseUrlBackup().isBlank()) {
            return null;
        }
        String model = props.getModelBackup() == null || props.getModelBackup().isBlank()
                ? props.getModel() : props.getModelBackup();
        String key = props.getApiKeyBackup() == null || props.getApiKeyBackup().isBlank()
                ? props.getApiKey() : props.getApiKeyBackup();
        return new Endpoint(props.getBaseUrlBackup(), model, key);
    }

    private boolean endpointOpen(Endpoint ep) {
        Breaker br = breakers.get(ep.key());
        return br != null && System.currentTimeMillis() < br.openUntil;
    }

    public boolean available() {
        if (!props.configured()) {
            return false;
        }
        if (!endpointOpen(primary())) {
            return true;
        }
        Endpoint b = backup();
        return b != null && !endpointOpen(b);
    }

    public String model() {
        return props.getModel();
    }

    public boolean configured() {
        return props.configured();
    }

    /** 各端点熔断状态快照（metrics 用）：fails=连续失败计数，open=是否熔断中。 */
    public Map<String, Map<String, Object>> breakerStates() {
        long now = System.currentTimeMillis();
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        breakers.forEach((k, br) -> out.put(k, Map.of(
                "fails", br.fails.get(), "open", now < br.openUntil, "openUntil", br.openUntil)));
        return out;
    }

    /**
     * @param onToken 非 null 时走流式（最终回答轮）；null 走非流式（工具调用轮）。
     */
    public ChatResult chat(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        return chat(messages, tools, onToken, props.getMaxTokens());
    }

    /** 路由链：主端点（含重试/熔断）失败且未出流时切备用端点；均已失败则抛出由调用方降级 FAQ。 */
    public ChatResult chat(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken,
                           int maxTokens) {
        if (!props.configured()) {
            throw new LlmException("LLM 未配置 apiKey");
        }
        LlmException last;
        try {
            return attempt(primary(), messages, tools, onToken, maxTokens);
        } catch (LlmException e) {
            last = e;
            Endpoint b = backup();
            // 已推送流式片段时不切备用，避免用户看到重复内容；部分内容随异常保留
            if (b != null && !e.isStreamed()) {
                log.warn("LLM 主端点 {} 失败（{}），路由备用端点 {}", primary().key(), e.getMessage(), b.key());
                try {
                    return attempt(b, messages, tools, onToken, maxTokens);
                } catch (LlmException e2) {
                    last = e2;
                }
            }
        }
        throw last;
    }

    private ChatResult attempt(Endpoint ep, List<ChatMessage> messages, List<ToolSpec> tools,
                               Consumer<String> onToken, int maxTokens) {
        Breaker br = breakers.computeIfAbsent(ep.key(), k -> new Breaker());
        if (System.currentTimeMillis() < br.openUntil) {
            // 熔断打开：直接拒绝（half-open 由 openUntil 到期后的下一次真实调用承担）
            throw new LlmException("LLM 熔断中，暂走知识库降级").markCircuit();
        }
        java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<String> sink = onToken == null ? null : piece -> {
            emitted.set(true);
            onToken.accept(piece);
        };
        int maxAttempts = Math.max(1, props.getRetryMax() + 1);
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResult r = doChat(ep, messages, tools, sink, maxTokens);
                br.fails.set(0);
                return r;
            } catch (Exception e) {
                LlmException le = e instanceof LlmException x ? x
                        : new LlmException("LLM 调用失败: " + e.getMessage(), e);
                if (emitted.get()) {
                    le.markStreamed(); // 已推送流式片段，禁止重试以免内容重复
                }
                last = le;
                if (attempt < maxAttempts && retryable(le)) {
                    long backoff = props.getRetryBackoffMs() * (1L << (attempt - 1));
                    log.warn("LLM 端点 {} 第 {} 次失败（{}），{}ms 后重试",
                            ep.key(), attempt, le.getMessage(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        recordFailure(br, ep);
        throw last;
    }

    /** 429/5xx/网络与超时类可退避重试；4xx 参数与鉴权错误、已出流的流式中断不重试。 */
    private static boolean retryable(LlmException e) {
        if (e.isStreamed()) {
            return false;
        }
        int status = e.getHttpStatus();
        if (status > 0) {
            return status == 429 || status >= 500;
        }
        return e.getCause() != null; // IOException/TimeoutException 等传输层异常
    }

    private void recordFailure(Breaker br, Endpoint ep) {
        int n = br.fails.incrementAndGet();
        if (n >= props.getCircuitFailureThreshold()) {
            br.openUntil = System.currentTimeMillis() + props.getCircuitOpenMs();
            log.warn("LLM 端点 {} 连续失败 {} 次，熔断 {}ms", ep.key(), n, props.getCircuitOpenMs());
        }
    }

    private ChatResult doChat(Endpoint ep, List<ChatMessage> messages, List<ToolSpec> tools,
                              Consumer<String> onToken, int maxTokens) throws Exception {
        boolean stream = onToken != null;
        // 含图片的消息路由到视觉模型（同端点覆盖 model；未配置则沿用端点模型）
        String model = ep.model();
        if (props.getVisionModel() != null && !props.getVisionModel().isBlank()
                && messages.stream().anyMatch(ChatMessage::hasImages)) {
            model = props.getVisionModel();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", serializeMessages(messages));
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", maxTokens);
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
                .uri(URI.create(ep.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ep.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        ChatResult result = new ChatResult();
        result.setModel(model);
        if (!stream) {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("LLM HTTP " + resp.statusCode() + ": " + truncate(resp.body()))
                        .withHttpStatus(resp.statusCode());
            }
            JsonNode root = json.readTree(resp.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");
            result.setContent(msg.path("content").asText(null));
            result.setToolCalls(parseToolCalls(msg.path("tool_calls")));
            result.setFinishReason(choice.path("finish_reason").asText(null));
            fillUsage(result, root.path("usage"));
        } else {
            HttpResponse<Stream<String>> resp = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() / 100 != 2) {
                String err = resp.body().reduce("", (a, b) -> a + b);
                throw new LlmException("LLM HTTP " + resp.statusCode() + ": " + truncate(err))
                        .withHttpStatus(resp.statusCode());
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
                    JsonNode choice = chunk.path("choices").path(0);
                    JsonNode delta = choice.path("delta");
                    String piece = delta.path("content").asText(null);
                    if (piece != null && !piece.isEmpty()) {
                        content.append(piece);
                        onToken.accept(piece);
                    }
                    if (choice.hasNonNull("finish_reason")) {
                        result.setFinishReason(choice.get("finish_reason").asText());
                    }
                    mergeStreamToolCalls(delta.path("tool_calls"), partialCalls);
                    fillUsage(result, chunk.path("usage"));
                }
            } catch (Exception e) {
                // 断流：保留已生成文本随异常上抛，调用方据此落库标注而非整体丢弃
                throw new LlmException("LLM 流式中断: " + e.getMessage(), e)
                        .markStreamed().withPartialContent(content.toString());
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
            if (m.getContentParts() != null && !m.getContentParts().isEmpty()) {
                mm.put("content", m.getContentParts()); // OpenAI 多模态 parts 数组
            } else if (m.getContent() != null) {
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