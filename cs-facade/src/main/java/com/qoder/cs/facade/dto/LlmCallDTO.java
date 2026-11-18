package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class LlmCallDTO {
    private String id;
    private String sessionId;
    private String model;
    private String promptVersion;
    private Long promptTokens;
    private Long completionTokens;
    private Long latencyMs;
    private String status;
    private String error;
    private Long createdAt;
}