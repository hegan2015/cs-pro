package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class SessionDTO {
    private String id;
    private String userRole;
    private String userId;
    private String userName;
    private String tenantCode;
    private String status;
    private String intent;
    /** 人工接管标记（v1.1.0）：true 时消息排队由客服回复，不走 LLM */
    private Boolean takeover;
    /** 会话访问凭证（v1.2.0）：仅建会话时返回一次，读消息/发消息需携带；legacy 会话为 null 时放行 */
    private String accessToken;
    private Long createdAt;
    private Long endedAt;
}