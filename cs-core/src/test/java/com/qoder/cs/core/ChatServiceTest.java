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
        SessionDTO s = newSession("UT-CHAT-" + UUID.randomUUID());
        MessageDTO bot = chat.sendMessage(s.getId(), "打车怎么计价？");
        assertEquals("BOT", bot.getRole());
        assertTrue(bot.getContent().contains("起步价"));
        assertNotNull(bot.getKbRefs());
        assertEquals("计价", chat.getSession(s.getId()).getIntent());
    }

    @Test
    void freeQuotaExhaustionReturnsConflict() {
        SessionDTO s = newSession("UT-CHAT-" + UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            chat.sendMessage(s.getId(), "退款多久到账？");
        }
        BizException e = assertThrows(BizException.class, () -> chat.sendMessage(s.getId(), "退款多久到账？"));
        assertEquals("A0409", e.getCode());
    }

    @Test
    void subscribedUnlimitedWithLlmFallback() {
        String userId = "UT-CHAT-" + UUID.randomUUID();
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
        SessionDTO s = newSession("UT-CHAT-" + UUID.randomUUID());
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
        req.setUserId("UT-M-" + UUID.randomUUID());
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
}