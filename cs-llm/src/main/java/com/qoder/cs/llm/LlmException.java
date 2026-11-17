package com.qoder.cs.llm;

/** LLM 调用失败（超时/网络/协议错误/熔断）。调用方据此降级 FAQ。 */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}