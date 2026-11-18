package com.qoder.cs.facade.dto;

import lombok.Data;

/** 管理操作审计行：谁（actor）对什么（target）做了什么（action），留存可查。 */
@Data
public class AdminAuditDTO {
    private Long id;
    private String actor;
    private String action;
    private String target;
    private String detail;
    private Long createdAt;
}