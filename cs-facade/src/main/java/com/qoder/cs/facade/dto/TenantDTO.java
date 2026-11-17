package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class TenantDTO {
    private String id;
    private String code;
    private String name;
    private String status;
    private Long createdAt;
}