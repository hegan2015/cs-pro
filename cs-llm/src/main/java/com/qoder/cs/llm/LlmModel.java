package com.qoder.cs.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** OpenAI 兼容协议的最小契约集。 */
public final class LlmModel {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
        private List<ToolCall> toolCalls;
        private String toolCallId;
        /** OpenAI 多模态 content parts（text/image_url）；非 null 时优先于 content 字符串 */
        private List<Map<String, Object>> contentParts;

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, null, null, null);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, null, null, null);
        }

        /** 用户多模态消息：文本 + 图片（data URL 或公网 URL） */
        public static ChatMessage userMultimodal(String text, List<String> imageUrls) {
            List<Map<String, Object>> parts = new java.util.ArrayList<>();
            if (text != null && !text.isBlank()) {
                parts.add(Map.of("type", "text", "text", text));
            }
            for (String url : imageUrls) {
                parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
            }
            return new ChatMessage("user", null, null, null, parts);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content, null, null, null);
        }

        public static ChatMessage assistantToolCalls(List<ToolCall> calls) {
            return new ChatMessage("assistant", null, calls, null, null);
        }

        public static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId, null);
        }

        public boolean hasImages() {
            return contentParts != null && contentParts.stream()
                    .anyMatch(p -> "image_url".equals(p.get("type")));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String name;
        private String argumentsJson;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolSpec {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }

    @Data
    public static class ChatResult {
        private String content;
        private List<ToolCall> toolCalls;
        private long promptTokens;
        private long completionTokens;
        /** 实际应答端点的模型（路由链可能切到备用） */
        private String model;
        /** stop / length / tool_calls 等；length 表示达到 max_tokens 被截断 */
        private String finishReason;

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    private LlmModel() {
    }
}