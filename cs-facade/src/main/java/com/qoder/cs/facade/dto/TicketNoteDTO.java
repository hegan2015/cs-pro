package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class TicketNoteDTO {
    private String id;
    private String ticketId;
    private String author;
    private String content;
    private Long createdAt;
}