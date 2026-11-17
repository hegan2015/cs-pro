package com.qoder.cs.kb;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Bm25IndexTest {

    private Bm25Index indexWithFaqs() {
        Bm25Index index = new Bm25Index();
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("K1", Bm25Index.docText("打车怎么计价？", "计价,价格,费用,起步价",
                "计价 = 起步价 + 里程费 + 时长费，再乘以高峰系数。"));
        docs.put("K2", Bm25Index.docText("退款多久到账？", "退款,到账,时效",
                "退款审核通过后实时退回钱包余额。"));
        docs.put("K3", Bm25Index.docText("提现失败了怎么办？", "提现,失败",
                "提现失败金额会退回钱包可用余额。"));
        index.rebuild(docs);
        return index;
    }

    @Test
    void chineseQueryRanksMatchingDocFirst() {
        List<Bm25Index.Scored> hits = indexWithFaqs().search("打车计价规则是什么", 3);
        assertFalse(hits.isEmpty());
        assertEquals("K1", hits.get(0).id());
    }

    @Test
    void refundQueryHitsRefundDoc() {
        List<Bm25Index.Scored> hits = indexWithFaqs().search("退款什么时候到账", 3);
        assertEquals("K2", hits.get(0).id());
    }

    @Test
    void unrelatedQueryReturnsEmpty() {
        assertTrue(indexWithFaqs().search("量子力学薛定谔", 3).isEmpty());
    }

    @Test
    void emptyIndexReturnsEmpty() {
        Bm25Index index = new Bm25Index();
        assertTrue(index.search("计价", 3).isEmpty());
    }

    @Test
    void rebuildReplacesAtomically() {
        Bm25Index index = indexWithFaqs();
        index.rebuild(Map.of("K9", Bm25Index.docText("如何订阅智能客服？", "订阅,激活码", "输入激活码立即开通。")));
        List<Bm25Index.Scored> hits = index.search("怎么订阅", 3);
        assertEquals("K9", hits.get(0).id());
        assertTrue(index.search("打车计价", 3).stream().noneMatch(s -> s.id().equals("K1")));
    }

    @Test
    void tokenizeMixesAsciiAndCjkBigrams() {
        Map<String, Integer> tf = Bm25Index.tokenize("订单O12退款");
        assertEquals(1, tf.get("o12"));
        assertEquals(1, tf.get("退款"));
        assertEquals(1, tf.get("退"));
        assertEquals(1, tf.get("款"));
    }

    @Test
    void limitRespected() {
        assertEquals(2, indexWithFaqs().search("钱包余额退回", 2).size());
    }
}