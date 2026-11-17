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
    private Long createdAt;
    private Long endedAt;
}