package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class RatingDTO {
    private String targetType;
    private String targetId;
    private Integer score;
    private String comment;
    private Long createdAt;
}