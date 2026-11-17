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

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(stats.overview());
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

    // ---- 工单处置 ----

    @GetMapping("/tickets")
    public Result<Map<String, Object>> tickets(@RequestParam(required = false) String userId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", tickets.count(userId, status, category));
        out.put("list", tickets.query(userId, status, category, page, Math.min(size, 50)));
        return Result.ok(out);
    }

    @GetMapping("/tickets/{id}")
    public Result<Map<String, Object>> ticketDetail(@PathVariable String id) {
        return Result.ok(tickets.detail(id));
    }

    @PostMapping("/tickets/{id}/assign")
    public Result<TicketDTO> assign(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Result.ok(tickets.assign(id, body.get("assignee")));
    }

    @PostMapping("/tickets/{id}/resolve")
    public Result<TicketDTO> resolve(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Result.ok(tickets.resolve(id, body.get("solution")));
    }

    @PostMapping("/tickets/{id}/close")
    public Result<TicketDTO> close(@PathVariable String id) {
        return Result.ok(tickets.close(id));
    }

    @PostMapping("/tickets/{id}/notes")
    public Result<TicketNoteDTO> note(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Result.ok(tickets.addNote(id, body.getOrDefault("author", "admin"), body.get("content")));
    }

    // ---- FAQ 管理 ----

    @GetMapping("/kb")
    public Result<List<KbEntryDTO>> kbList() {
        return Result.ok(kb.list());
    }

    @PostMapping("/kb")
    public Result<KbEntryDTO> kbCreate(@RequestBody KbUpsertRequest req) {
        return Result.ok(kb.create(req));
    }

    @PutMapping("/kb/{id}")
    public Result<KbEntryDTO> kbUpdate(@PathVariable String id, @RequestBody KbUpsertRequest req) {
        return Result.ok(kb.update(id, req));
    }

    @DeleteMapping("/kb/{id}")
    public Result<Void> kbDelete(@PathVariable String id) {
        kb.delete(id);
        return Result.ok(null);
    }

    // ---- LLM 审计 ----

    @GetMapping("/llm-calls")
    public Result<List<LlmCallDTO>> llmCalls(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(llmCalls.query(status, (page - 1) * Math.min(size, 100), Math.min(size, 100)));
    }

    // ---- 租户与订阅 ----

    @GetMapping("/tenants")
    public Result<List<TenantDTO>> tenants() {
        return Result.ok(subscriptions.listTenants());
    }

    @PostMapping("/tenants")
    public Result<TenantDTO> createTenant(@RequestBody Map<String, String> body) {
        return Result.ok(subscriptions.createTenant(body.get("code"), body.get("name")));
    }

    @PostMapping("/tenants/{id}/status")
    public Result<Void> tenantStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        subscriptions.setTenantStatus(id, body.get("status"));
        return Result.ok(null);
    }

    @PostMapping("/codes/generate")
    public Result<List<ActivationCodeDTO>> generateCodes(@RequestBody Map<String, Object> body) {
        int days = body.get("days") == null ? 30 : ((Number) body.get("days")).intValue();
        int count = body.get("count") == null ? 1 : ((Number) body.get("count")).intValue();
        return Result.ok(subscriptions.generateCodes(days, count));
    }

    @GetMapping("/codes")
    public Result<List<ActivationCodeDTO>> codes(@RequestParam(required = false) String status) {
        return Result.ok(subscriptions.listCodes(status));
    }

    @GetMapping("/subscriptions")
    public Result<List<Map<String, Object>>> subscriptions() {
        return Result.ok(subscriptions.allSubscriptions());
    }

    @PostMapping("/subscriptions/grant")
    public Result<SubscriptionDTO> grant(@RequestBody Map<String, Object> body) {
        String tenantId = (String) body.get("tenantId");
        int days = body.get("days") == null ? 30 : ((Number) body.get("days")).intValue();
        return Result.ok(subscriptions.grant(tenantId, days));
    }

    @GetMapping("/sub-orders")
    public Result<List<SubOrderDTO>> subOrders() {
        return Result.ok(pay.list());
    }

    @PostMapping("/seed")
    public Result<Void> reseed() {
        seed.seed();
        return Result.ok(null);
    }
}