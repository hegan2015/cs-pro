package com.qoder.cs.facade.dto;

import lombok.Data;

/** 开放 API 租户级凭证：X-Open-Key 头携带 apiKey，替代全局 admin token 的租户自助通道。 */
@Data
public class OpenKeyDTO {
    private String apiKey;
    private String tenantId;
    private String secret;
    private String status;
    private Long createdAt;
}