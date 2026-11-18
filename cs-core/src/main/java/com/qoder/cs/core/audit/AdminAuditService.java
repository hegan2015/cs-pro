package com.qoder.cs.core.audit;

import com.qoder.cs.facade.dto.AdminAuditDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理审计留存：变更类操作落审计行。写入失败仅告警不阻断主流程（审计是旁路合规，非业务关键路径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditMapper audits;

    public void record(String actor, String action, String target, String detail) {
        try {
            AdminAuditDTO a = new AdminAuditDTO();
            a.setActor(actor == null || actor.isBlank() ? "admin" : actor);
            a.setAction(action);
            a.setTarget(target);
            a.setDetail(detail == null ? null : (detail.length() > 1024 ? detail.substring(0, 1024) : detail));
            a.setCreatedAt(System.currentTimeMillis());
            audits.insert(a);
        } catch (Exception e) {
            log.warn("审计写入失败 action={} target={}: {}", action, target, e.getMessage());
        }
    }

    public Map<String, Object> query(String action, String actor, Long from, Long to, int page, int size) {
        int limit = Math.min(size, 100);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", audits.count(action, actor, from, to));
        out.put("list", audits.query(action, actor, from, to, (page - 1) * limit, limit));
        return out;
    }
}