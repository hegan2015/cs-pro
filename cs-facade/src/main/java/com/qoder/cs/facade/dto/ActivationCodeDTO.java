package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class ActivationCodeDTO {
    private String code;
    private Integer days;
    private String status;
    private String usedByTenant;
    private Long usedAt;
    private Long createdAt;
}