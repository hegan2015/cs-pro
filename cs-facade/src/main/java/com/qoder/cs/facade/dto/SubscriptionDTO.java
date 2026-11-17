package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class SubscriptionDTO {
    private String tenantId;
    private String planName;
    private Integer days;
    private String status;
    private Long startAt;
    private Long expireAt;
    private String source;
    private Long updatedAt;
}