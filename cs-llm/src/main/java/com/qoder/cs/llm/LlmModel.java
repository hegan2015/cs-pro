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

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, null, null);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, null, null);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content, null, null);
        }

        public static ChatMessage assistantToolCalls(List<ToolCall> calls) {
            return new ChatMessage("assistant", null, calls, null);
        }

        public static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId);
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

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    private LlmModel() {
    }
}