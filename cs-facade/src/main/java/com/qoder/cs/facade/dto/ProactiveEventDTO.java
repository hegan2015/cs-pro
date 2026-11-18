package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class ProactiveEventDTO {
    private String eventKey;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String payload;
    private String status;
    private Long createdAt;
}