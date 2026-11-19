package com.qoder.cs.facade.request;

import lombok.Data;

@Data
public class TicketCreateRequest {
    private String sessionId;
    private String category;
    private String summary;
    /** 用户昵称（可选，开放收单口携带） */
    private String userName;
    /** 问题详细描述（可选，开放收单口携带） */
    private String description;
    private String orderId;
    private String priority;
    private String requestId;
    private String attachments;
}