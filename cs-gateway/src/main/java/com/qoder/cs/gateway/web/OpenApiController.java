package com.qoder.cs.gateway.web;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.ticket.TicketService;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.facade.dto.TicketDTO;
import com.qoder.cs.facade.request.OpenTicketCreateRequest;
import com.qoder.cs.facade.request.TicketCreateRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放 API（X-Open-Key 鉴权，租户级）：租户自助查询本租户用量/会话/账单，
 * 数据归属由 OpenKeyAuthFilter 解析的租户强制隔离，无需全局 admin token。
 * v1.3 新增工单收单口：ride-pro 等上游服务端代用户提交工单（requestId 幂等）并回查进度。
 */
@RestController
@RequestMapping("/api/open")
@RequiredArgsConstructor
public class OpenApiController {

    private final SubscriptionService subscriptions;
    private final LlmCallMapper llmCalls;
    private final SessionMapper sessions;
    private final com.qoder.cs.core.billing.InvoiceService invoices;
    private final TicketService tickets;

    private String tenantCode(HttpServletRequest req) {
        return (String) req.getAttribute(OpenKeyAuthFilter.ATTR_TENANT_CODE);
    }

    @GetMapping("/usage")
    public Result<Map<String, Object>> usage(HttpServletRequest req) {
        String code = tenantCode(req);
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Map<String, Object> out = new LinkedHashMap<>(subscriptions.status(code, null));
        out.put("tokensToday", llmCalls.sumTokensTodayByTenant(code, dayStart));
        return Result.ok(out);
    }

    @GetMapping("/sessions")
    public Result<Map<String, Object>> sessions(HttpServletRequest req,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        String code = tenantCode(req);
        int limit = Math.min(size, 50);
        List<SessionDTO> list = sessions.queryByTenant(code, status, (page - 1) * limit, limit);
        list.forEach(s -> s.setAccessToken(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", sessions.countByTenant(code, status));
        out.put("list", list);
        return Result.ok(out);
    }

    @GetMapping("/invoices")
    public Result<List<com.qoder.cs.facade.dto.InvoiceDTO>> invoices(HttpServletRequest req,
                                                                     @RequestParam(required = false) String period) {
        return Result.ok(invoices.list(tenantCode(req), period));
    }

    // ---- 工单收单与回查（ride-pro 工单接入） ----

    /**
     * 上游系统代用户提交工单：requestId 幂等（重复返回同一工单），source 缺省 ride-pro。
     * 返回结构与用户端 POST /api/tickets 一致（TicketDTO：id 即工单号 + status 等）。
     */
    @PostMapping("/tickets")
    public Result<TicketDTO> createTicket(@RequestBody OpenTicketCreateRequest req) {
        if (req.getUserId() == null || req.getUserId().isBlank()) {
            throw BizException.param("userId 必填");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw BizException.param("title 必填");
        }
        String source = req.getSource() == null || req.getSource().isBlank() ? "ride-pro" : req.getSource().trim();
        TicketCreateRequest create = new TicketCreateRequest();
        create.setRequestId(req.getRequestId());
        create.setCategory(req.getCategory());
        create.setSummary(req.getTitle());
        create.setUserName(req.getUserName());
        create.setDescription(req.getDescription());
        create.setOrderId(req.getOrderId());
        create.setAttachments(req.getAttachments());
        return Result.ok(tickets.create(create, req.getUserId().trim(), source));
    }

    /** 上游按用户回查工单进度（userId 必填，可选 status 过滤；分页契约 {total,list}）。 */
    @GetMapping("/tickets")
    public Result<Map<String, Object>> listTickets(@RequestParam String userId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", tickets.count(userId, status, null));
        out.put("list", tickets.query(userId, status, null, page, Math.min(size, 50)));
        return Result.ok(out);
    }

    /** 工单详情 + 处理记录（与用户端 GET /api/tickets/{id} 同构）。 */
    @GetMapping("/tickets/{id}")
    public Result<Map<String, Object>> ticketDetail(@PathVariable String id) {
        return Result.ok(tickets.detail(id));
    }
}