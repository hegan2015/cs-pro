package com.qoder.cs.core;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.ticket.TicketService;
import com.qoder.cs.facade.dto.TicketDTO;
import com.qoder.cs.facade.request.TicketCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestApp.class)
class TicketServiceTest {

    @Autowired
    TicketService tickets;

    private TicketCreateRequest req(String requestId) {
        TicketCreateRequest r = new TicketCreateRequest();
        r.setCategory("支付");
        r.setSummary("测试工单");
        r.setRequestId(requestId);
        return r;
    }

    @Test
    void createIsIdempotentByRequestId() {
        String rid = "UT-" + UUID.randomUUID();
        TicketDTO a = tickets.create(req(rid), "U1", "USER");
        TicketDTO b = tickets.create(req(rid), "U1", "USER");
        assertEquals(a.getId(), b.getId());
        assertEquals(1, tickets.query(null, null, null, 1, 200).stream()
                .filter(t -> rid.equals(t.getRequestId())).count());
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOne() throws Exception {
        String rid = "UT-" + UUID.randomUUID();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return tickets.create(req(rid), "U1", "USER").getId();
            }));
        }
        start.countDown();
        String first = futures.get(0).get(10, TimeUnit.SECONDS);
        for (Future<String> f : futures) {
            assertEquals(first, f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, tickets.query(null, null, null, 1, 200).stream()
                .filter(t -> rid.equals(t.getRequestId())).count());
    }

    @Test
    void stateMachineCasFlow() {
        TicketDTO t = tickets.create(req("UT-" + UUID.randomUUID()), "U1", "USER");
        assertEquals("OPEN", t.getStatus());

        assertThrows(BizException.class, () -> tickets.resolve(t.getId(), "直接解决"));
        assertThrows(BizException.class, () -> tickets.close(t.getId()));

        tickets.assign(t.getId(), "客服甲");
        assertEquals("PROCESSING", tickets.get(t.getId()).getStatus());
        assertThrows(BizException.class, () -> tickets.assign(t.getId(), "客服乙"));

        tickets.resolve(t.getId(), "已核实并退款");
        TicketDTO resolved = tickets.get(t.getId());
        assertEquals("RESOLVED", resolved.getStatus());
        assertEquals("已核实并退款", resolved.getSolution());
        assertNotNull(resolved.getResolvedAt());

        tickets.close(t.getId());
        assertEquals("CLOSED", tickets.get(t.getId()).getStatus());
        assertThrows(BizException.class, () -> tickets.close(t.getId()));
    }

    @Test
    void cancelOnlyByOwnerAndBeforeResolved() {
        TicketDTO t = tickets.create(req("UT-" + UUID.randomUUID()), "U1", "USER");
        assertThrows(BizException.class, () -> tickets.cancel(t.getId(), "U2"));
        tickets.cancel(t.getId(), "U1");
        assertEquals("CANCELLED", tickets.get(t.getId()).getStatus());
        assertThrows(BizException.class, () -> tickets.cancel(t.getId(), "U1"));
    }
}