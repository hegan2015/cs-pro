package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class KbRevisionDTO {
    private Long id;
    private String kbId;
    private String question;
    private String answer;
    private String category;
    private String keywords;
    private String savedBy;
    private Long savedAt;
}