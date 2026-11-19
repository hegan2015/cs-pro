package com.qoder.cs.facade.request;

import lombok.Data;

/**
 * 开放收单口契约（POST /api/open/tickets，X-Open-Key 租户鉴权）：
 * ride-pro 等上游系统服务端代用户提交工单。requestId 幂等（重复返回同一工单）；
 * source 缺省 "ride-pro"；title 落 summary，description 落长文本描述。
 */
@Data
public class OpenTicketCreateRequest {
    /** 幂等键（必填，上游确定性生成，重复提交返回同一工单） */
    private String requestId;
    /** 上游用户 ID（必填） */
    private String userId;
    /** 用户昵称（可选） */
    private String userName;
    /** 来源系统标识，缺省 "ride-pro" */
    private String source;
    /** 分类：计价/支付/退款/提现/账号/投诉/其他，缺省 其他 */
    private String category;
    /** 关联订单号（可选，ride-pro 订单 ID） */
    private String orderId;
    /** 工单标题（必填，落 summary） */
    private String title;
    /** 问题详细描述（可选） */
    private String description;
    /** 图片附件 URL 逗号列表（/uploads/ 相对路径，≤3 张，可选） */
    private String attachments;
}