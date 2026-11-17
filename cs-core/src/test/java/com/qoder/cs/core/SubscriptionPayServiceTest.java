package com.qoder.cs.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.core.biz.PaymentProClient;
import com.qoder.cs.core.subscription.SubscriptionPayService;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.subscription.TenantMapper;
import com.qoder.cs.facade.dto.SubOrderDTO;
import com.qoder.cs.facade.dto.SubscriptionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** pp 通道以桩替代：验证下单→回查终态→同事务开通，且重放不重复加天数。 */
@SpringBootTest(classes = TestApp.class)
class SubscriptionPayServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        PaymentProClient stubPp() {
            return new PaymentProClient("", "", "", "") {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public JsonNode callRead(String method, Map<String, Object> params) {
                    if ("trade.query".equals(method)) {
                        return JSON.createObjectNode()
                                .set("payment", JSON.createObjectNode().put("status", "SUCCESS"));
                    }
                    return JSON.createObjectNode();
                }

                @Override
                public JsonNode callSubscriptionPay(String method, Map<String, Object> params) {
                    if ("trade.create".equals(method)) {
                        return JSON.createObjectNode().put("id", "O-STUB-" + UUID.randomUUID());
                    }
                    return JSON.createObjectNode()
                            .set("payment", JSON.createObjectNode().put("id", "P-STUB-1").put("status", "SUCCESS"));
                }
            };
        }
    }

    @Autowired
    SubscriptionPayService pay;
    @Autowired
    SubscriptionService subscriptions;
    @Autowired
    TenantMapper tenantMapper;

    @Test
    void payOrderSettlesOnceAndActivatesIdempotently() {
        TenantDTO t = subscriptions.createTenant("ut-pay-" + UUID.randomUUID().toString().substring(0, 8), "支付单测租户");
        SubOrderDTO order = pay.createOrder(t.getCode(), "A-STUB");
        assertEquals("PENDING", order.getStatus());
        assertNotNull(order.getPpOrderId());

        SubOrderDTO settled = pay.get(order.getId());
        assertEquals("ACTIVATED", settled.getStatus());
        assertTrue(subscriptions.isSubscribed(t.getId()));
        SubscriptionDTO sub = tenantMapper.selectSubscription(t.getId());
        long expireAfterFirst = sub.getExpireAt();
        assertEquals("PAY", sub.getSource());

        SubOrderDTO replay = pay.get(order.getId());
        assertEquals("ACTIVATED", replay.getStatus());
        assertEquals(expireAfterFirst, tenantMapper.selectSubscription(t.getId()).getExpireAt());
    }

    @Test
    void payRequiresPayerAccount() {
        TenantDTO t = subscriptions.createTenant("ut-pay-" + UUID.randomUUID().toString().substring(0, 8), "支付单测租户2");
        assertThrows(com.qoder.cs.common.exception.BizException.class, () -> pay.createOrder(t.getCode(), null));
    }
}