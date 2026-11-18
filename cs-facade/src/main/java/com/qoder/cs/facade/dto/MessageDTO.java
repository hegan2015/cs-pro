package com.qoder.cs.facade.dto;

import lombok.Data;

@Data
public class MessageDTO {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private String toolName;
    private String toolResult;
    private String kbRefs;
    private Long latencyMs;
    private Long tokens;
    private Long createdAt;
    /** create_ticket 工具产出的工单草稿 JSON（仅 BOT 消息，前端渲染确认卡片，不落工单表） */
    private String ticketDraft;
    /** 本轮工具调用轨迹文本（v1.1.0 多轮记忆：历史重建时注入 LLM） */
    private String toolTrace;
    /** 图片附件 URL 逗号列表（仅 USER 消息，/uploads/ 相对路径） */
    private String attachments;
}