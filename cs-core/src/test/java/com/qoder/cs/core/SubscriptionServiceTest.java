package com.qoder.cs.core;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.subscription.TenantMapper;
import com.qoder.cs.facade.dto.ActivationCodeDTO;
import com.qoder.cs.facade.dto.SubscriptionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestApp.class)
class SubscriptionServiceTest {

    private static final long DAY_MS = 86_400_000L;

    @Autowired
    SubscriptionService subscriptions;
    @Autowired
    TenantMapper tenantMapper;

    private TenantDTO freshTenant() {
        return subscriptions.createTenant("ut-" + UUID.randomUUID().toString().substring(0, 8), "单测租户");
    }

    @Test
    void concurrentRedeemHasExactlyOneWinner() throws Exception {
        TenantDTO t = freshTenant();
        ActivationCodeDTO code = subscriptions.generateCodes(30, 1).get(0);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    subscriptions.redeem(t.getCode(), code.getCode());
                    wins.incrementAndGet();
                } catch (BizException e) {
                    assertEquals("A0409", e.getCode());
                    conflicts.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, wins.get());
        assertEquals(n - 1, conflicts.get());
        assertTrue(subscriptions.isSubscribed(t.getId()));
    }

    @Test
    void renewalStacksDays() {
        TenantDTO t = freshTenant();
        SubscriptionDTO first = subscriptions.extend(t.getId(), 30, "ADMIN");
        long expire1 = first.getExpireAt();
        SubscriptionDTO second = subscriptions.extend(t.getId(), 30, "CODE");
        assertTrue(second.getExpireAt() >= expire1 + 30 * DAY_MS - 5000);
        assertTrue(second.getExpireAt() <= expire1 + 30 * DAY_MS + 5000);
    }

    @Test
    void expiredSubscriptionIsNotSubscribed() {
        TenantDTO t = freshTenant();
        subscriptions.extend(t.getId(), 30, "ADMIN");
        assertTrue(subscriptions.isSubscribed(t.getId()));
        SubscriptionDTO sub = tenantMapper.selectSubscription(t.getId());
        sub.setExpireAt(System.currentTimeMillis() - 1000);
        tenantMapper.upsertSubscription(sub);
        assertFalse(subscriptions.isSubscribed(t.getId()));
        subscriptions.extend(t.getId(), 30, "CODE");
        assertTrue(subscriptions.isSubscribed(t.getId()));
    }

    @Test
    void freeQuotaNeverOverspendsUnderConcurrency() throws Exception {
        String userId = "UT-USER-" + UUID.randomUUID();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (subscriptions.tryConsumeFree(userId)) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(5, granted.get());
        assertEquals(5, subscriptions.freeUsed(userId));
        assertFalse(subscriptions.tryConsumeFree(userId));
    }

    @Test
    void frozenTenantRejected() {
        TenantDTO t = freshTenant();
        subscriptions.setTenantStatus(t.getId(), "FROZEN");
        BizException e = assertThrows(BizException.class, () -> subscriptions.requireTenant(t.getCode()));
        assertEquals("A0409", e.getCode());
    }

    @Test
    void usedCodeCannotRedeemAgain() {
        TenantDTO t1 = freshTenant();
        TenantDTO t2 = freshTenant();
        ActivationCodeDTO code = subscriptions.generateCodes(7, 1).get(0);
        subscriptions.redeem(t1.getCode(), code.getCode());
        BizException e = assertThrows(BizException.class, () -> subscriptions.redeem(t2.getCode(), code.getCode()));
        assertEquals("A0409", e.getCode());
        assertFalse(subscriptions.isSubscribed(t2.getId()));
    }
}