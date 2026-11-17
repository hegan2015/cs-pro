package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class KbEntryDTO {
    private String id;
    private String question;
    private String answer;
    private String category;
    private String keywords;
    private Boolean enabled;
    private Long hitCount;
    private Long updatedAt;
}