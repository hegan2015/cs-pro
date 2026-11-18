package com.qoder.cs.kb;

import com.qoder.cs.facade.dto.KbEntryDTO;
import com.qoder.cs.facade.dto.KbRevisionDTO;
import com.qoder.cs.facade.request.KbUpsertRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private final KbMapper kbMapper;
    private final KbRevisionMapper revisionMapper;
    private final com.qoder.cs.common.id.IdGenerator ids;
    private final Bm25Index index = new Bm25Index();

    /** TopK 结果短 TTL 缓存：高频重复问题免重复检索；KB 任何变更（rebuildIndex）即失效 */
    @org.springframework.beans.factory.annotation.Value("${cs.kb.cache-ttl-ms:30000}")
    private long cacheTtlMs;
    private final Map<String, CachedHits> searchCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CACHE_MAX = 512;

    private record CachedHits(List<Hit> hits, long at) {
    }

    public record Hit(KbEntryDTO entry, double score) {
    }

    @PostConstruct
    public void init() {
        rebuildIndex();
    }

    public synchronized void rebuildIndex() {
        Map<String, String> docs = new LinkedHashMap<>();
        for (KbEntryDTO e : kbMapper.selectEnabled()) {
            docs.put(e.getId(), Bm25Index.docText(e.getQuestion(), e.getKeywords(), e.getAnswer()));
        }
        index.rebuild(docs);
        searchCache.clear();
        log.info("KB 索引重建完成 entries={}", docs.size());
    }

    public List<Hit> search(String query, int limit) {
        String key = query + "|" + limit;
        long now = System.currentTimeMillis();
        CachedHits cached = searchCache.get(key);
        if (cached != null && now - cached.at() < cacheTtlMs) {
            return cached.hits();
        }
        List<Hit> hits = new ArrayList<>();
        for (Bm25Index.Scored s : index.search(query, limit)) {
            KbEntryDTO e = kbMapper.selectById(s.id());
            if (e != null && Boolean.TRUE.equals(e.getEnabled())) {
                hits.add(new Hit(e, s.score()));
            }
        }
        if (cacheTtlMs > 0) {
            if (searchCache.size() >= CACHE_MAX) {
                searchCache.clear();
            }
            searchCache.put(key, new CachedHits(hits, now));
        }
        return hits;
    }

    public void recordHit(String id) {
        kbMapper.incrementHit(id);
    }

    public List<KbEntryDTO> list() {
        return kbMapper.selectAll();
    }

    public List<KbEntryDTO> hot(int limit) {
        return kbMapper.topHit(limit);
    }

    public KbEntryDTO get(String id) {
        return kbMapper.selectById(id);
    }

    public synchronized KbEntryDTO create(KbUpsertRequest req) {
        validate(req);
        if (kbMapper.selectByQuestion(req.getQuestion()) != null) {
            throw com.qoder.cs.common.exception.BizException.conflict("FAQ 问题已存在: " + req.getQuestion());
        }
        KbEntryDTO e = toEntry(req);
        e.setId("K" + ids.next("seq_kb"));
        e.setUpdatedAt(System.currentTimeMillis());
        kbMapper.insert(e);
        rebuildIndex();
        return e;
    }

    /** 播种用：按 question 去重只增不删。 */
    public synchronized boolean seedIfAbsent(String question, String answer, String category, String keywords) {
        if (kbMapper.selectByQuestion(question) != null) {
            return false;
        }
        KbEntryDTO e = new KbEntryDTO();
        e.setId("K" + ids.next("seq_kb"));
        e.setQuestion(question);
        e.setAnswer(answer);
        e.setCategory(category);
        e.setKeywords(keywords);
        e.setEnabled(true);
        e.setUpdatedAt(System.currentTimeMillis());
        kbMapper.insert(e);
        rebuildIndex();
        return true;
    }

    public synchronized KbEntryDTO update(String id, KbUpsertRequest req) {
        validate(req);
        KbEntryDTO e = kbMapper.selectById(id);
        if (e == null) {
            throw com.qoder.cs.common.exception.BizException.notFound("FAQ 不存在: " + id);
        }
        snapshot(e, "admin"); // 改写前旧版快照，支持回滚
        KbEntryDTO upd = toEntry(req);
        upd.setId(id);
        upd.setUpdatedAt(System.currentTimeMillis());
        kbMapper.update(upd);
        rebuildIndex();
        return kbMapper.selectById(id);
    }

    // ---- KB 版本化与回滚 ----

    private void snapshot(KbEntryDTO e, String savedBy) {
        KbRevisionDTO rev = new KbRevisionDTO();
        rev.setKbId(e.getId());
        rev.setQuestion(e.getQuestion());
        rev.setAnswer(e.getAnswer());
        rev.setCategory(e.getCategory());
        rev.setKeywords(e.getKeywords());
        rev.setSavedBy(savedBy);
        rev.setSavedAt(System.currentTimeMillis());
        revisionMapper.insert(rev);
    }

    public List<KbRevisionDTO> listVersions(String kbId) {
        if (kbMapper.selectById(kbId) == null) {
            throw com.qoder.cs.common.exception.BizException.notFound("FAQ 不存在: " + kbId);
        }
        return revisionMapper.selectByKb(kbId);
    }

    /** 回滚到指定历史版本：当前内容先存为新版本（回滚本身也可再回滚）。 */
    public synchronized KbEntryDTO rollback(String kbId, long revisionId) {
        KbEntryDTO cur = kbMapper.selectById(kbId);
        if (cur == null) {
            throw com.qoder.cs.common.exception.BizException.notFound("FAQ 不存在: " + kbId);
        }
        KbRevisionDTO rev = revisionMapper.selectById(revisionId);
        if (rev == null || !kbId.equals(rev.getKbId())) {
            throw com.qoder.cs.common.exception.BizException.notFound("版本不存在或不属于该 FAQ: " + revisionId);
        }
        snapshot(cur, "rollback");
        cur.setQuestion(rev.getQuestion());
        cur.setAnswer(rev.getAnswer());
        cur.setCategory(rev.getCategory());
        cur.setKeywords(rev.getKeywords());
        cur.setUpdatedAt(System.currentTimeMillis());
        kbMapper.update(cur);
        rebuildIndex();
        log.info("KB 回滚 id={} revisionId={}", kbId, revisionId);
        return kbMapper.selectById(kbId);
    }

    // ---- 引用与解决率 ----

    /** BOT 回答引用了哪些条目就计哪些（去重） */
    public void recordCited(List<Hit> hits) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (seen.add(h.entry().getId())) {
                kbMapper.incrementCited(h.entry().getId());
            }
        }
    }

    /**
     * 引用解决率（近似口径）：引用满 7 天观察期、会话后续 7 天无工单且会话无差评 = 已解决。
     * 每次读取时重算并回写 cited_resolved_count，管理端展示即最新。
     */
    public List<Map<String, Object>> citationStats() {
        long cutoff = System.currentTimeMillis() - 7L * 86_400_000L;
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbEntryDTO e : kbMapper.selectAll()) {
            long matured = kbMapper.countCitedMatured(e.getId(), cutoff);
            long resolved = kbMapper.countCitedResolved(e.getId(), cutoff);
            kbMapper.updateResolvedCount(e.getId(), resolved);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("question", e.getQuestion());
            row.put("cited", e.getCitedCount() == null ? 0 : e.getCitedCount());
            row.put("matured", matured);
            row.put("resolved", resolved);
            row.put("rate", matured == 0 ? null : Math.round(resolved * 1000.0 / matured) / 10.0);
            out.add(row);
        }
        return out;
    }

    public synchronized void delete(String id) {
        if (kbMapper.delete(id) == 0) {
            throw com.qoder.cs.common.exception.BizException.notFound("FAQ 不存在: " + id);
        }
        rebuildIndex();
    }

    private static void validate(KbUpsertRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw com.qoder.cs.common.exception.BizException.param("question 必填");
        }
        if (req.getAnswer() == null || req.getAnswer().isBlank()) {
            throw com.qoder.cs.common.exception.BizException.param("answer 必填");
        }
    }

    private static KbEntryDTO toEntry(KbUpsertRequest req) {
        KbEntryDTO e = new KbEntryDTO();
        e.setQuestion(req.getQuestion().trim());
        e.setAnswer(req.getAnswer().trim());
        e.setCategory(req.getCategory() == null || req.getCategory().isBlank() ? "其他" : req.getCategory().trim());
        e.setKeywords(req.getKeywords());
        e.setEnabled(req.getEnabled() == null || req.getEnabled());
        return e;
    }
}