package com.qoder.cs.kb;

import com.qoder.cs.facade.dto.KbEntryDTO;
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
    private final Bm25Index index = new Bm25Index();

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
        log.info("KB 索引重建完成 entries={}", docs.size());
    }

    public List<Hit> search(String query, int limit) {
        List<Hit> hits = new ArrayList<>();
        for (Bm25Index.Scored s : index.search(query, limit)) {
            KbEntryDTO e = kbMapper.selectById(s.id());
            if (e != null && Boolean.TRUE.equals(e.getEnabled())) {
                hits.add(new Hit(e, s.score()));
            }
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
        e.setId("K" + kbMapper.nextId());
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
        e.setId("K" + kbMapper.nextId());
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
        KbEntryDTO upd = toEntry(req);
        upd.setId(id);
        upd.setUpdatedAt(System.currentTimeMillis());
        kbMapper.update(upd);
        rebuildIndex();
        return kbMapper.selectById(id);
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