package com.qoder.cs.facade.request;

import lombok.Data;

@Data
public class TicketCreateRequest {
    private String sessionId;
    private String category;
    private String summary;
    private String orderId;
    private String priority;
    private String requestId;
}