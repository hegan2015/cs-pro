package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class SubOrderDTO {
    private String id;
    private String tenantId;
    private Long amount;
    private Integer days;
    private String requestId;
    private String ppOrderId;
    private String ppPaymentId;
    private String status;
    private Long createdAt;
    private Long paidAt;
}