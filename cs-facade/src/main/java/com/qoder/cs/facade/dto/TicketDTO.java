package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class TicketDTO {
    private String id;
    private String sessionId;
    private String userId;
    private String category;
    private String priority;
    private String summary;
    private String orderId;
    private String status;
    private String assignee;
    private String requestId;
    private String source;
    private String solution;
    private Long createdAt;
    private Long resolvedAt;
    private Long closedAt;
}