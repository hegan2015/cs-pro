package com.qoder.cs.facade.request;

import lombok.Data;

@Data
public class SessionCreateRequest {
    private String userRole;
    private String userId;
    private String userName;
    private String tenantCode;
}