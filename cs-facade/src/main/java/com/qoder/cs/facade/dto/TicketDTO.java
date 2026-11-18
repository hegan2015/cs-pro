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
    /** 图片附件 URL 逗号列表（/uploads/ 相对路径） */
    private String attachments;
    private Long createdAt;
    private Long resolvedAt;
    private Long closedAt;
}