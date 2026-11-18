package com.qoder.cs.gateway.web;

import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.facade.dto.SessionDTO;
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
 */
@RestController
@RequestMapping("/api/open")
@RequiredArgsConstructor
public class OpenApiController {

    private final SubscriptionService subscriptions;
    private final LlmCallMapper llmCalls;
    private final SessionMapper sessions;
    private final com.qoder.cs.core.billing.InvoiceService invoices;

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
}