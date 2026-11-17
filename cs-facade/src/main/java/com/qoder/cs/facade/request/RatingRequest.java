package com.qoder.cs.facade.request;

import lombok.Data;

@Data
public class RatingRequest {
    private String targetType;
    private String targetId;
    private Integer score;
    private String comment;
}