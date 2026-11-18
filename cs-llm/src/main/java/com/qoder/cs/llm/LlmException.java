package com.qoder.cs.llm;

/**
 * LLM 调用失败（超时/网络/HTTP 错误/熔断/已出流后中断）。调用方据此降级 FAQ。
 * httpStatus/streamed/circuit 标记供重试分类与审计状态判断。
 */
public class LlmException extends RuntimeException {

    private int httpStatus;
    private boolean streamed;
    private boolean circuit;
    /** 流式中断时已生成的文本，供调用方保留部分内容而非整体丢弃 */
    private String partialContent;

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmException withHttpStatus(int status) {
        this.httpStatus = status;
        return this;
    }

    public LlmException markStreamed() {
        this.streamed = true;
        return this;
    }

    public LlmException markCircuit() {
        this.circuit = true;
        return this;
    }

    public LlmException withPartialContent(String partialContent) {
        this.partialContent = partialContent;
        return this;
    }

    public String getPartialContent() {
        return partialContent;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isStreamed() {
        return streamed;
    }

    public boolean isCircuit() {
        return circuit;
    }
}
