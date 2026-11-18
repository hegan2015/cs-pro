package com.qoder.cs.core;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.audit.AdminAuditService;
import com.qoder.cs.core.billing.InvoiceService;
import com.qoder.cs.core.chat.ChatService;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.core.open.OpenKeyService;
import com.qoder.cs.core.stats.MetricsService;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.facade.dto.*;
import com.qoder.cs.facade.request.SessionCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** v1.2.0 商用缺口（F 批）：开放凭证 / 会话凭证 / 套餐账单 / 审计 / metrics。 */
@SpringBootTest(classes = TestApp.class)
class CommercialFeaturesTest {

    @Autowired ChatService chat;
    @Autowired SubscriptionService subscriptions;
    @Autowired OpenKeyService openKeys;
    @Autowired InvoiceService invoices;
    @Autowired AdminAuditService audit;
    @Autowired MetricsService metrics;
    @Autowired LlmCallMapper llmCalls;
    @Autowired SessionMapper sessions;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private TenantDTO newTenant() {
        return subscriptions.createTenant("ut-f-" + uniq(), "F批单测租户");
    }

    private SessionDTO newSession(String tenantCode, String userId) {
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("PASSENGER");
        req.setUserId(userId);
        req.setTenantCode(tenantCode);
        return chat.createSession(req);
    }

    // ---- F1 开放凭证 ----

    @Test
    void openKeyGenerateResolveRevoke() {
        TenantDTO t = newTenant();
        OpenKeyDTO k = openKeys.generate(t.getCode());
        assertTrue(k.getApiKey().startsWith("ok-"));
        assertEquals(t.getId(), openKeys.resolve(k.getApiKey()).getId());
        openKeys.revoke(k.getApiKey());
        BizException e = assertThrows(BizException.class, () -> openKeys.resolve(k.getApiKey()));
        assertEquals("A0401", e.getCode());
        assertEquals("A0401", assertThrows(BizException.class, () -> openKeys.resolve("ok-nonexistent")).getCode());
        assertEquals("A0401", assertThrows(BizException.class, () -> openKeys.resolve(null)).getCode());
    }

    @Test
    void openKeyFrozenTenantForbidden() {
        TenantDTO t = newTenant();
        OpenKeyDTO k = openKeys.generate(t.getCode());
        subscriptions.setTenantStatus(t.getId(), "FROZEN");
        BizException e = assertThrows(BizException.class, () -> openKeys.resolve(k.getApiKey()));
        assertEquals("A0403", e.getCode());
    }

    // ---- F2 会话凭证 ----

    @Test
    void sessionTokenIssuedOnceAndEnforced() {
        TenantDTO t = newTenant();
        SessionDTO s = newSession(t.getCode(), "U-" + uniq());
        assertNotNull(s.getAccessToken());
        assertNull(chat.getSession(s.getId()).getAccessToken()); // 按 id 查询不回显
        chat.requireAccess(s.getId(), s.getAccessToken()); // 正确凭证放行
        assertEquals("A0403", assertThrows(BizException.class,
                () -> chat.requireAccess(s.getId(), "wrong-token")).getCode());
        assertEquals("A0403", assertThrows(BizException.class,
                () -> chat.requireAccess(s.getId(), null)).getCode());
    }

    @Test
    void legacySessionWithoutTokenAllowed() {
        TenantDTO t = newTenant();
        SessionDTO s = new SessionDTO();
        s.setId("S-LEGACY-" + uniq());
        s.setUserRole("PASSENGER");
        s.setUserId("U-" + uniq());
        s.setTenantCode(t.getCode());
        s.setAccessToken(null); // 升级前 legacy 会话
        s.setCreatedAt(System.currentTimeMillis());
        sessions.insert(s);
        chat.requireAccess(s.getId(), null); // 无凭证 legacy 放行
        chat.requireAccess(s.getId(), "anything");
    }

    // ---- F3 套餐账单 ----

    @Test
    void invoiceAggregatesOkCallsAndBillsByPlan() {
        TenantDTO t = newTenant();
        subscriptions.grant(t.getId(), 30); // standard，千 token 单价 10 分
        SessionDTO s = newSession(t.getCode(), "U-" + uniq());
        String period = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        long now = System.currentTimeMillis();
        llmCalls.insert(call("L-A-" + uniq(), s.getId(), 1000, 0, "OK", now));
        llmCalls.insert(call("L-B-" + uniq(), s.getId(), 0, 500, "OK", now));
        llmCalls.insert(call("L-C-" + uniq(), s.getId(), 9999, 9999, "FALLBACK", now)); // 降级不计费

        InvoiceDTO inv = invoices.generate(t.getCode(), period);
        assertEquals(1500L, inv.getPromptTokens() + inv.getCompletTokens());
        assertEquals(2L, inv.getCalls());
        assertEquals(15L, inv.getAmount()); // ceil(1500*10/1000)=15 分
        assertEquals("standard", inv.getPlanCode());

        // 幂等：重复出账覆盖更新，不产生重复行
        InvoiceDTO again = invoices.generate(t.getCode(), period);
        assertEquals(inv.getId(), again.getId());
        List<InvoiceDTO> list = invoices.list(t.getCode(), period);
        assertEquals(1, list.size());
    }

    @Test
    void invoiceRejectsBadPeriod() {
        TenantDTO t = newTenant();
        assertEquals("A0400", assertThrows(BizException.class,
                () -> invoices.generate(t.getCode(), "2026-13")).getCode());
        assertEquals("A0400", assertThrows(BizException.class,
                () -> invoices.generate(t.getCode(), "bad")).getCode());
    }

    private LlmCallDTO call(String id, String sessionId, long prompt, long complet, String status, long now) {
        LlmCallDTO c = new LlmCallDTO();
        c.setId(id);
        c.setSessionId(sessionId);
        c.setModel("test");
        c.setPromptTokens(prompt);
        c.setCompletionTokens(complet);
        c.setStatus(status);
        c.setCreatedAt(now);
        return c;
    }

    // ---- F4 审计 ----

    @Test
    void auditRecordAndQuery() {
        String marker = "UT-AUDIT-" + uniq();
        audit.record("tester", "KB_UPDATE", marker, "detail");
        Map<String, Object> res = audit.query("KB_UPDATE", "tester", null, null, 1, 50);
        @SuppressWarnings("unchecked")
        List<AdminAuditDTO> list = (List<AdminAuditDTO>) res.get("list");
        assertTrue(list.stream().anyMatch(a -> marker.equals(a.getTarget())));
    }

    // ---- F5 metrics ----

    @Test
    void metricsRenderPrometheusText() {
        String text = metrics.render();
        assertTrue(text.contains("# TYPE cs_sessions_total gauge"));
        assertTrue(text.contains("cs_messages_total"));
        assertTrue(text.contains("cs_llm_calls{status=\"OK\"}"));
        assertTrue(text.contains("cs_sse_connections_active"));
        assertTrue(text.contains("cs_proactive_enabled"));
    }
}