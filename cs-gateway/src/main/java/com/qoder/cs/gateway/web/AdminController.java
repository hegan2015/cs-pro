package com.qoder.cs.gateway.web;

import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.chat.ChatService;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.seed.SeedService;
import com.qoder.cs.core.stats.StatsService;
import com.qoder.cs.core.subscription.SubscriptionPayService;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.ticket.TicketService;
import com.qoder.cs.facade.dto.*;
import com.qoder.cs.facade.request.KbUpsertRequest;
import com.qoder.cs.kb.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端 API（X-Admin-Token 鉴权）：总览/趋势/会话/工单处置/FAQ/LLM 审计/租户订阅。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final StatsService stats;
    private final ChatService chat;
    private final TicketService tickets;
    private final KbService kb;
    private final LlmCallMapper llmCalls;
    private final SubscriptionService subscriptions;
    private final SubscriptionPayService pay;
    private final SeedService seed;
    private final com.qoder.cs.core.proactive.ProactiveEventMapper proactiveEvents;
    private final com.qoder.cs.core.open.OpenKeyService openKeys;
    private final com.qoder.cs.core.billing.InvoiceService invoices;
    private final com.qoder.cs.core.config.CsProperties csProps;
    private final com.qoder.cs.core.audit.AdminAuditService audit;
    private final com.qoder.cs.core.stats.MetricsService metrics;
    private final com.qoder.cs.core.stats.HealthService health;

    private void audit(String action, String target, String detail) {
        audit.record("admin", action, target, detail);
    }

    @GetMapping("/audit-log")
    public Result<Map<String, Object>> auditLog(@RequestParam(required = false) String action,
                                                @RequestParam(required = false) String actor,
                                                @RequestParam(required = false) Long from,
                                                @RequestParam(required = false) Long to,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return Result.ok(audit.query(action, actor, from, to, page, size));
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(stats.overview());
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public String metrics() {
        return metrics.render();
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(health.detail());
    }

    @GetMapping("/stats/daily")
    public Result<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(stats.daily(days));
    }

    @GetMapping("/sessions")
    public Result<Map<String, Object>> sessions(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) Long from,
                                                @RequestParam(required = false) Long to,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", chat.countSessions(status, from, to));
        out.put("list", chat.querySessions(status, from, to, page, Math.min(size, 50)));
        return Result.ok(out);
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<List<MessageDTO>> sessionMessages(@PathVariable String id) {
        return Result.ok(chat.messages(id));
    }

    // ---- 人工接管 ----

    @PostMapping("/sessions/{id}/takeover")
    public Result<SessionDTO> takeover(@PathVariable String id,
                                       @RequestBody(required = false) Map<String, String> body) {
        SessionDTO s = chat.adminSetTakeover(id, true, body == null ? null : body.get("agent"));
        audit("SESSION_TAKEOVER", id, body == null ? null : body.get("agent"));
        return Result.ok(s);
    }

    @PostMapping("/sessions/{id}/handback")
    public Result<SessionDTO> handback(@PathVariable String id) {
        SessionDTO s = chat.adminSetTakeover(id, false, null);
        audit("SESSION_HANDBACK", id, null);
        return Result.ok(s);
    }

    @PostMapping("/sessions/{id}/messages")
    public Result<MessageDTO> agentSend(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Result.ok(chat.agentReply(id, body.get("content")));
    }

    // ---- 工单处置 ----

    @GetMapping("/tickets")
    public Result<Map<String, Object>> tickets(@RequestParam(required = false) String userId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String source,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", tickets.count(userId, status, category, source, q));
        out.put("list", tickets.query(userId, status, category, source, q, page, Math.min(size, 50)));
        return Result.ok(out);
    }

    @GetMapping("/tickets/{id}")
    public Result<Map<String, Object>> ticketDetail(@PathVariable String id) {
        return Result.ok(tickets.detail(id));
    }

    @PostMapping("/tickets/{id}/assign")
    public Result<TicketDTO> assign(@PathVariable String id, @RequestBody Map<String, String> body) {
        TicketDTO t = tickets.assign(id, body.get("assignee"));
        audit("TICKET_ASSIGN", id, body.get("assignee"));
        return Result.ok(t);
    }

    @PostMapping("/tickets/{id}/resolve")
    public Result<TicketDTO> resolve(@PathVariable String id, @RequestBody Map<String, String> body) {
        TicketDTO t = tickets.resolve(id, body.get("solution"));
        audit("TICKET_RESOLVE", id, null);
        return Result.ok(t);
    }

    @PostMapping("/tickets/{id}/close")
    public Result<TicketDTO> close(@PathVariable String id) {
        TicketDTO t = tickets.close(id);
        audit("TICKET_CLOSE", id, null);
        return Result.ok(t);
    }

    @PostMapping("/tickets/{id}/notes")
    public Result<TicketNoteDTO> note(@PathVariable String id, @RequestBody Map<String, String> body) {
        TicketNoteDTO n = tickets.addNote(id, body.getOrDefault("author", "admin"), body.get("content"));
        audit("TICKET_NOTE", id, null);
        return Result.ok(n);
    }

    // ---- FAQ 管理 ----

    @GetMapping("/kb")
    public Result<List<KbEntryDTO>> kbList() {
        return Result.ok(kb.list());
    }

    @PostMapping("/kb")
    public Result<KbEntryDTO> kbCreate(@RequestBody KbUpsertRequest req) {
        KbEntryDTO e = kb.create(req);
        audit("KB_CREATE", e.getId(), req.getQuestion());
        return Result.ok(e);
    }

    @PutMapping("/kb/{id}")
    public Result<KbEntryDTO> kbUpdate(@PathVariable String id, @RequestBody KbUpsertRequest req) {
        KbEntryDTO e = kb.update(id, req);
        audit("KB_UPDATE", id, req.getQuestion());
        return Result.ok(e);
    }

    @DeleteMapping("/kb/{id}")
    public Result<Void> kbDelete(@PathVariable String id) {
        kb.delete(id);
        audit("KB_DELETE", id, null);
        return Result.ok(null);
    }

    @GetMapping("/kb/{id}/versions")
    public Result<List<KbRevisionDTO>> kbVersions(@PathVariable String id) {
        return Result.ok(kb.listVersions(id));
    }

    @PostMapping("/kb/{id}/rollback/{revisionId}")
    public Result<KbEntryDTO> kbRollback(@PathVariable String id, @PathVariable long revisionId) {
        KbEntryDTO e = kb.rollback(id, revisionId);
        audit("KB_ROLLBACK", id, "revision=" + revisionId);
        return Result.ok(e);
    }

    @GetMapping("/kb/citation-stats")
    public Result<List<Map<String, Object>>> kbCitationStats() {
        return Result.ok(kb.citationStats());
    }

    @PostMapping("/kb/reindex")
    public Result<Map<String, Object>> kbReindex() {
        int n = kb.reindexVectors();
        audit("KB_REINDEX", null, "vectors=" + n);
        return Result.ok(Map.of("indexed", n));
    }

    @GetMapping("/kb/vector-status")
    public Result<Map<String, Object>> kbVectorStatus() {
        return Result.ok(kb.vectorStatus());
    }

    // ---- 坏例队列 ----

    @GetMapping("/badcases")
    public Result<Map<String, Object>> badcases(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return Result.ok(chat.badcases(page, size));
    }

    // ---- 主动服务事件 ----

    @GetMapping("/proactive-events")
    public Result<Map<String, Object>> proactiveEvents(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        int limit = Math.min(size, 100);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", proactiveEvents.count());
        out.put("list", proactiveEvents.page((page - 1) * limit, limit));
        return Result.ok(out);
    }

    // ---- LLM 审计 ----

    @GetMapping("/llm-calls")
    public Result<Map<String, Object>> llmCalls(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        int limit = Math.min(size, 100);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", status == null || status.isBlank() ? llmCalls.countAll() : llmCalls.countByStatus(status));
        out.put("list", llmCalls.query(status, (page - 1) * limit, limit));
        return Result.ok(out);
    }

    // ---- 租户与订阅 ----

    @GetMapping("/tenants")
    public Result<Map<String, Object>> tenants(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return Result.ok(subscriptions.queryTenants(page, size));
    }

    @PostMapping("/tenants")
    public Result<TenantDTO> createTenant(@RequestBody Map<String, String> body) {
        TenantDTO t = subscriptions.createTenant(body.get("code"), body.get("name"));
        audit("TENANT_CREATE", t.getCode(), body.get("name"));
        return Result.ok(t);
    }

    @PostMapping("/tenants/{id}/status")
    public Result<Void> tenantStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        subscriptions.setTenantStatus(id, body.get("status"));
        audit("TENANT_STATUS", id, body.get("status"));
        return Result.ok(null);
    }

    @PostMapping("/codes/generate")
    public Result<List<ActivationCodeDTO>> generateCodes(@RequestBody Map<String, Object> body) {
        int days = body.get("days") == null ? 30 : ((Number) body.get("days")).intValue();
        int count = body.get("count") == null ? 1 : ((Number) body.get("count")).intValue();
        List<ActivationCodeDTO> codes = subscriptions.generateCodes(days, count, (String) body.get("planCode"));
        audit("CODE_GENERATE", null, "days=" + days + " count=" + count + " plan=" + body.get("planCode"));
        return Result.ok(codes);
    }

    @GetMapping("/codes")
    public Result<Map<String, Object>> codes(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(subscriptions.queryCodes(status, page, size));
    }

    @GetMapping("/subscriptions")
    public Result<Map<String, Object>> subscriptions(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return Result.ok(subscriptions.querySubscriptions(page, size));
    }

    @PostMapping("/subscriptions/grant")
    public Result<SubscriptionDTO> grant(@RequestBody Map<String, Object> body) {
        String tenantId = (String) body.get("tenantId");
        int days = body.get("days") == null ? 30 : ((Number) body.get("days")).intValue();
        SubscriptionDTO sub = subscriptions.grant(tenantId, days);
        audit("SUB_GRANT", tenantId, "days=" + days);
        return Result.ok(sub);
    }

    @GetMapping("/sub-orders")
    public Result<Map<String, Object>> subOrders(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return Result.ok(pay.list(page, size));
    }

    @PostMapping("/seed")
    public Result<Void> reseed() {
        seed.seed();
        audit("SEED", null, null);
        return Result.ok(null);
    }

    // ---- 开放 API 凭证 ----

    @PostMapping("/open-keys")
    public Result<OpenKeyDTO> createOpenKey(@RequestBody Map<String, String> body) {
        OpenKeyDTO k = openKeys.generate(body.get("tenantCode"));
        audit("OPENKEY_CREATE", body.get("tenantCode"), k.getApiKey());
        return Result.ok(k);
    }

    @GetMapping("/open-keys")
    public Result<Map<String, Object>> openKeys(@RequestParam(required = false) String tenantCode,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return Result.ok(openKeys.query(tenantCode, page, size));
    }

    @PostMapping("/open-keys/{apiKey}/revoke")
    public Result<Void> revokeOpenKey(@PathVariable String apiKey) {
        openKeys.revoke(apiKey);
        audit("OPENKEY_REVOKE", apiKey, null);
        return Result.ok(null);
    }

    // ---- 套餐目录与月度账单 ----

    @GetMapping("/plans")
    public Result<Map<String, com.qoder.cs.core.config.CsProperties.PlanSpec>> plans() {
        return Result.ok(csProps.getPlans());
    }

    @PostMapping("/invoices/generate")
    public Result<InvoiceDTO> generateInvoice(@RequestBody Map<String, String> body) {
        InvoiceDTO inv = invoices.generate(body.get("tenantCode"), body.get("period"));
        audit("INVOICE_GENERATE", body.get("tenantCode"), body.get("period") + " amount=" + inv.getAmount());
        return Result.ok(inv);
    }

    @GetMapping("/invoices")
    public Result<Map<String, Object>> invoices(@RequestParam(required = false) String tenantCode,
                                                @RequestParam(required = false) String period,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return Result.ok(invoices.query(tenantCode, period, page, size));
    }
}