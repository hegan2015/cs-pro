package com.qoder.cs.core;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.chat.ChatService;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.facade.dto.MessageDTO;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import com.qoder.cs.facade.request.SessionCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 无 LLM Key 环境：未订阅 FAQ 限次、订阅后 FAQ 降级不限次、会话生命周期。 */
@SpringBootTest(classes = TestApp.class)
class ChatServiceTest {

    @Autowired
    ChatService chat;
    @Autowired
    SubscriptionService subscriptions;

    private SessionDTO newSession(String userId) {
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("PASSENGER");
        req.setUserId(userId);
        req.setUserName("单测用户");
        return chat.createSession(req);
    }

    @Test
    void unsubscribedGetsFaqDirectAnswer() {
        SessionDTO s = newSession("UT-CHAT-" + UUID.randomUUID().toString().substring(0, 8));
        MessageDTO bot = chat.sendMessage(s.getId(), "打车怎么计价？");
        assertEquals("BOT", bot.getRole());
        assertTrue(bot.getContent().contains("起步价"));
        assertNotNull(bot.getKbRefs());
        assertEquals("计价", chat.getSession(s.getId()).getIntent());
    }

    @Test
    void freeQuotaExhaustionReturnsConflict() {
        SessionDTO s = newSession("UT-CHAT-" + UUID.randomUUID().toString().substring(0, 8));
        for (int i = 0; i < 5; i++) {
            chat.sendMessage(s.getId(), "退款多久到账？");
        }
        BizException e = assertThrows(BizException.class, () -> chat.sendMessage(s.getId(), "退款多久到账？"));
        assertEquals("A0409", e.getCode());
    }

    @Test
    void subscribedUnlimitedWithLlmFallback() {
        String userId = "UT-CHAT-" + UUID.randomUUID().toString().substring(0, 8);
        TenantDTO tenant = subscriptions.createTenant(
                "ut-chat-" + UUID.randomUUID().toString().substring(0, 8), "对话单测租户");
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("PASSENGER");
        req.setUserId(userId);
        req.setTenantCode(tenant.getCode());
        SessionDTO s = chat.createSession(req);
        subscriptions.grant(tenant.getId(), 30);
        for (int i = 0; i < 7; i++) {
            MessageDTO bot = chat.sendMessage(s.getId(), "打车怎么计价？");
            assertNotNull(bot.getContent());
        }
        MessageDTO last = chat.sendMessage(s.getId(), "提现失败了怎么办？");
        assertTrue(last.getContent().contains("智能助手暂不可用") || last.getContent().contains("提现"));
    }

    @Test
    void endedSessionRejectsMessages() {
        SessionDTO s = newSession("UT-CHAT-" + UUID.randomUUID().toString().substring(0, 8));
        chat.endSession(s.getId());
        assertEquals("ENDED", chat.getSession(s.getId()).getStatus());
        BizException e = assertThrows(BizException.class, () -> chat.sendMessage(s.getId(), "你好"));
        assertEquals("A0409", e.getCode());
        assertThrows(BizException.class, () -> chat.endSession(s.getId()));
    }

    @Test
    void merchantDefaultsToPaymentProTenant() {
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("MERCHANT");
        req.setUserId("UT-M-" + UUID.randomUUID().toString().substring(0, 8));
        SessionDTO s = chat.createSession(req);
        assertEquals("payment-pro", s.getTenantCode());
    }

    @Test
    void unknownTenantRejected() {
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("PASSENGER");
        req.setUserId("U1");
        req.setTenantCode("no-such-tenant");
        BizException e = assertThrows(BizException.class, () -> chat.createSession(req));
        assertEquals("A0404", e.getCode());
    }

    @Test
    void intentClassification() {
        assertEquals("计价", ChatService.classifyIntent("这个订单费用怎么算"));
        assertEquals("退款", ChatService.classifyIntent("我要退钱"));
        assertEquals("提现", ChatService.classifyIntent("提现没到账"));
        assertEquals("投诉", ChatService.classifyIntent("司机态度太差"));
        assertEquals("其他", ChatService.classifyIntent("你好"));
    }

    @Test
    void intentDerivedFromToolTrace() {
        assertEquals("订单", ChatService.deriveIntent(java.util.List.of("list_ride_orders"), "你好"));
        assertEquals("提现", ChatService.deriveIntent(java.util.List.of("query_withdraws"), "你好"));
        assertEquals("支付", ChatService.deriveIntent(java.util.List.of("query_wallet"), "你好"));
        assertEquals("计价", ChatService.deriveIntent(java.util.List.of("pricing_rules"), "你好"));
        // 首个可映射工具优先；仅工单/无工具回退关键词
        assertEquals("订单", ChatService.deriveIntent(
                java.util.List.of("query_ride_order", "query_wallet"), "你好"));
        assertEquals("退款", ChatService.deriveIntent(java.util.List.of("create_ticket"), "我要退款"));
        assertEquals("其他", ChatService.deriveIntent(java.util.List.of(), "你好"));
    }

    @Test
    void tenantConcurrencyQuotaReturns429() {
        TenantDTO tenant = subscriptions.createTenant(
                "ut-quota-" + UUID.randomUUID().toString().substring(0, 8), "并发配额单测租户");
        subscriptions.grant(tenant.getId(), 30);
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("PASSENGER");
        req.setUserId("UT-QUOTA-" + UUID.randomUUID().toString().substring(0, 8));
        req.setTenantCode(tenant.getCode());
        SessionDTO s = chat.createSession(req);
        // 测试配置并发=1：占满后第二条消息立即 429，释放后恢复
        assertTrue(chat.acquireTenantSlot(tenant.getCode()));
        BizException e = assertThrows(BizException.class, () -> chat.sendMessage(s.getId(), "你好"));
        assertEquals("A0429", e.getCode());
        chat.releaseTenantSlot(tenant.getCode());
        assertNotNull(chat.sendMessage(s.getId(), "你好"));
    }

    @Test
    void ragQueryRewriteAndRollingSummary() {
        assertEquals("那退款呢", ChatService.rewriteQuery("那退款呢", null));
        assertEquals("那退款呢 打车怎么计价", ChatService.rewriteQuery("那退款呢", "打车怎么计价"));
        assertEquals("你好", ChatService.rewriteQuery("你好", "你好"));

        java.util.List<com.qoder.cs.facade.dto.MessageDTO> older = new java.util.ArrayList<>();
        for (int i = 3; i >= 1; i--) { // 新→旧
            com.qoder.cs.facade.dto.MessageDTO u = new com.qoder.cs.facade.dto.MessageDTO();
            u.setRole("USER");
            u.setContent("问题" + i + "。补充说明" + i);
            com.qoder.cs.facade.dto.MessageDTO b = new com.qoder.cs.facade.dto.MessageDTO();
            b.setRole("BOT");
            b.setContent("回答" + i + "。详细解释" + i);
            older.add(b);
            older.add(u);
        }
        String summary = ChatService.summarizeOlder(older);
        assertTrue(summary.startsWith("【更早对话摘要"));
        assertTrue(summary.contains("用户: 问题1"));
        assertTrue(summary.contains("客服: 回答3"));
        assertFalse(summary.contains("补充说明"));
        assertTrue(summary.length() <= 700);
        assertEquals("", ChatService.summarizeOlder(java.util.List.of()));
    }

    @Test
    void kbSearchCacheInvalidatedOnChange() {
        var first = kb.search("打车怎么计价", 3);
        var second = kb.search("打车怎么计价", 3);
        assertSame(first, second); // TTL 内命中缓存（同一实例）
        var entry = kb.list().get(0);
        var req = new com.qoder.cs.facade.request.KbUpsertRequest();
        req.setQuestion(entry.getQuestion());
        req.setAnswer(entry.getAnswer());
        req.setCategory(entry.getCategory());
        req.setKeywords(entry.getKeywords());
        kb.update(entry.getId(), req); // 变更触发 rebuildIndex → 缓存失效
        var third = kb.search("打车怎么计价", 3);
        assertNotSame(first, third);
    }

    @Autowired
    com.qoder.cs.kb.KbService kb;
    @Autowired
    com.qoder.cs.core.biz.UploadService uploads;
    @Autowired
    com.qoder.cs.core.ticket.TicketService tickets;
    @Autowired
    com.qoder.cs.core.proactive.ProactiveService proactive;

    private static final byte[] TINY_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    void imageAttachmentUploadAndMessagePersistence() {
        String url = uploads.save("shot.png", TINY_PNG);
        assertTrue(url.matches("/uploads/[0-9a-f-]{36}\\.png"));
        assertNotNull(uploads.dataUrl(url));
        assertTrue(uploads.dataUrl(url).startsWith("data:image/png;base64,"));

        SessionDTO s = newSession("UT-IMG-" + UUID.randomUUID().toString().substring(0, 8));
        chat.sendMessage(s.getId(), "这张截图里的报错是什么意思？", url);
        var userMsg = chat.messages(s.getId()).stream()
                .filter(m -> "USER".equals(m.getRole())).findFirst().orElseThrow();
        assertEquals(url, userMsg.getAttachments());

        // 非法附件地址与超量拒绝
        assertThrows(BizException.class, () -> chat.sendMessage(s.getId(), "hi", "https://evil.com/x.png"));
        assertThrows(BizException.class, () -> chat.sendMessage(s.getId(), "hi", url + "," + url + "," + url + "," + url));
        assertThrows(BizException.class, () -> uploads.save("shell.jsp", TINY_PNG));
    }

    @Test
    void ticketCarriesImageAttachments() {
        String url = uploads.save("ticket.png", TINY_PNG);
        var req = new com.qoder.cs.facade.request.TicketCreateRequest();
        req.setRequestId("UT-TICKET-IMG-" + UUID.randomUUID());
        req.setCategory("支付");
        req.setSummary("截图中的支付失败");
        req.setAttachments(url);
        var t = tickets.create(req, "UT-IMG-U1", "USER");
        assertEquals(url, t.getAttachments());
    }

    @Test
    void multimodalPartsStructure() {
        var m = com.qoder.cs.llm.LlmModel.ChatMessage.userMultimodal("看图", java.util.List.of("data:image/png;base64,xx"));
        assertTrue(m.hasImages());
        assertEquals(2, m.getContentParts().size());
        assertEquals("text", m.getContentParts().get(0).get("type"));
        assertEquals("image_url", m.getContentParts().get(1).get("type"));
        assertFalse(com.qoder.cs.llm.LlmModel.ChatMessage.user("纯文本").hasImages());
    }

    @Test
    void proactiveNotifyIsIdempotentAndPushesOnce() {
        String user = "UT-PRO-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "ut-expired:" + UUID.randomUUID();
        assertTrue(proactive.notify(key, "ride-pro", user, "PASSENGER", "【主动服务】单测提醒", "O-UT"));
        assertFalse(proactive.notify(key, "ride-pro", user, "PASSENGER", "【主动服务】单测提醒", "O-UT"));
        var session = chat.querySessions("ACTIVE", null, null, 1, 50).stream()
                .filter(x -> user.equals(x.getUserId())).findFirst().orElseThrow();
        long notes = chat.messages(session.getId()).stream()
                .filter(m -> m.getContent().contains("单测提醒")).count();
        assertEquals(1, notes);
    }

    @Test
    void kbVersioningAndRollback() {
        var req = new com.qoder.cs.facade.request.KbUpsertRequest();
        req.setQuestion("单测版本化问题-" + UUID.randomUUID().toString().substring(0, 8));
        req.setAnswer("原始答案");
        req.setCategory("其他");
        var entry = kb.create(req);
        assertTrue(kb.listVersions(entry.getId()).isEmpty());

        req.setAnswer("修改后的答案");
        kb.update(entry.getId(), req);
        var versions = kb.listVersions(entry.getId());
        assertEquals(1, versions.size());
        assertEquals("原始答案", versions.get(0).getAnswer());

        var restored = kb.rollback(entry.getId(), versions.get(0).getId());
        assertEquals("原始答案", restored.getAnswer());
        // 回滚前当前版也存为快照，回滚本身可再回滚
        var after = kb.listVersions(entry.getId());
        assertEquals(2, after.size());
        assertTrue(after.stream().anyMatch(v -> "修改后的答案".equals(v.getAnswer())));
    }

    @Test
    void faqDirectAnswerCountsCitation() {
        var req = new com.qoder.cs.facade.request.KbUpsertRequest();
        req.setQuestion("单测引用计数问题鸬鹚-" + UUID.randomUUID().toString().substring(0, 8));
        req.setAnswer("单测引用答案：鸬鹚捕鱼说明。");
        req.setCategory("其他");
        req.setKeywords("鸬鹚");
        var entry = kb.create(req);
        SessionDTO s = newSession("UT-CITE-" + UUID.randomUUID().toString().substring(0, 8));
        MessageDTO bot = chat.sendMessage(s.getId(), entry.getQuestion());
        assertTrue(bot.getKbRefs().contains(entry.getId()));
        assertTrue(kb.get(entry.getId()).getCitedCount() >= 1);
        var stat = kb.citationStats().stream()
                .filter(r -> entry.getId().equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals(0L, stat.get("matured")); // 未满 7 天观察期
        assertNull(stat.get("rate"));
    }

    @Test
    void badcaseQueueIncludesDegradedSession() {
        TenantDTO tenant = subscriptions.createTenant(
                "ut-bad-" + UUID.randomUUID().toString().substring(0, 8), "坏例单测租户");
        subscriptions.grant(tenant.getId(), 30);
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserRole("PASSENGER");
        req.setUserId("UT-BAD-" + UUID.randomUUID().toString().substring(0, 8));
        req.setTenantCode(tenant.getCode());
        SessionDTO s = chat.createSession(req);
        chat.sendMessage(s.getId(), "打车怎么计价？"); // 无 LLM Key → FALLBACK 降级
        var item = chat.badcases(50).stream()
                .filter(b -> s.getId().equals(((SessionDTO) b.get("session")).getId()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        java.util.List<String> reasons = (java.util.List<String>) item.get("reasons");
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("智能助手降级")));
        assertEquals("打车怎么计价？", item.get("lastUser"));
    }

    @Test
    void piiMasking() {
        assertEquals("请联系 138****5678 处理", ChatService.maskPii("请联系 13812345678 处理"));
        assertEquals("身份证 110***********1234", ChatService.maskPii("身份证 110101199001011234"));
        assertEquals("卡号 6222********1234", ChatService.maskPii("卡号 6222021234561234"));
        // 金额/短数字/订单号不受影响
        assertEquals("12.50 元，订单 O20261118001", ChatService.maskPii("12.50 元，订单 O20261118001"));
        assertNull(ChatService.maskPii(null));
    }
}