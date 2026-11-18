package com.qoder.cs.kb.vector;

import com.qoder.cs.facade.dto.KbEntryDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RRF 融合排序与向量索引工具方法（纯静态，不依赖容器）。 */
class VectorIndexTest {

    @Test
    void rrfFuseBoostsEntryPresentInBothLists() {
        List<String> fused = VectorIndex.rrfFuse(List.of("A", "B", "C"), List.of("B", "D"), 60, 4);
        assertEquals("B", fused.get(0)); // 双榜命中融合分最高
        assertEquals(4, fused.size());
        assertTrue(fused.containsAll(List.of("A", "B", "C", "D")));
    }

    @Test
    void rrfFuseRespectsLimitAndSingleListOrder() {
        List<String> fused = VectorIndex.rrfFuse(List.of("A", "B", "C"), List.of(), 60, 2);
        assertEquals(List.of("A", "B"), fused);
    }

    @Test
    void rrfFuseEmptyInputs() {
        assertTrue(VectorIndex.rrfFuse(List.of(), List.of(), 60, 5).isEmpty());
    }

    @Test
    void pointIdIsStableUuid() {
        assertEquals(VectorIndex.pointId("K31"), VectorIndex.pointId("K31"));
        assertNotEquals(VectorIndex.pointId("K31"), VectorIndex.pointId("K32"));
        assertEquals(36, VectorIndex.pointId("K31").length());
    }

    @Test
    void docTextTruncatesLongAnswer() {
        KbEntryDTO e = new KbEntryDTO();
        e.setQuestion("如何退款");
        e.setKeywords("退款,退钱");
        e.setAnswer("x".repeat(1000));
        String text = VectorIndex.docText(e);
        assertTrue(text.startsWith("如何退款\n退款,退钱\n"));
        assertEquals("如何退款\n退款,退钱\n".length() + 400, text.length());
    }
}