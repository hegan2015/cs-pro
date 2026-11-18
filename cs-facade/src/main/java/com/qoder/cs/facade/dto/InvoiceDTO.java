package com.qoder.cs.facade.dto;

import lombok.Data;

/** 月度账单：按租户聚合 OK 状态 LLM 调用 token，按套餐千 token 单价计费（金额单位分）。 */
@Data
public class InvoiceDTO {
    private String id;
    private String tenantId;
    /** 账期 YYYY-MM */
    private String period;
    private Long promptTokens;
    private Long completTokens;
    private Long calls;
    private Long amount;
    private String planCode;
    private Long createdAt;
}