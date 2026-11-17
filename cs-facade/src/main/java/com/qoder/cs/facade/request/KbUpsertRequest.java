package com.qoder.cs.facade.request;

import lombok.Data;

@Data
public class KbUpsertRequest {
    private String question;
    private String answer;
    private String category;
    private String keywords;
    private Boolean enabled;
}