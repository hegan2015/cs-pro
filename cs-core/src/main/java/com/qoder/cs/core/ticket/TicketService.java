package com.qoder.cs.core.ticket;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.facade.dto.TicketDTO;
import com.qoder.cs.facade.dto.TicketNoteDTO;
import com.qoder.cs.facade.request.TicketCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单：requestId 幂等创建（唯一约束兜底）、CAS 状态机
 * OPEN→PROCESSING→RESOLVED→CLOSED，OPEN/PROCESSING 可 CANCELLED；
 * RESOLVED 满 7 天服务端自动关闭。工单对所有用户免费（含未订阅）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final Set<String> CATEGORIES = Set.of("计价", "支付", "退款", "提现", "账号", "投诉", "其他");
    private static final long AUTO_CLOSE_MS = 7 * 86_400_000L;

    private final TicketMapper ticketMapper;
    private final com.qoder.cs.common.id.IdGenerator ids;
    private final TicketNoteMapper noteMapper;
    private final SessionMapper sessionMapper;

    public TicketDTO create(TicketCreateRequest req, String userId, String source) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw BizException.param("requestId 必填（幂等键）");
        }
        if (req.getSummary() == null || req.getSummary().isBlank()) {
            throw BizException.param("summary 必填");
        }
        String category = req.getCategory() == null ? "其他" : req.getCategory();
        if (!CATEGORIES.contains(category)) {
            throw BizException.param("category 仅 " + CATEGORIES);
        }
        String priority = req.getPriority() == null ? "NORMAL" : req.getPriority();
        if (!"NORMAL".equals(priority) && !"URGENT".equals(priority)) {
            throw BizException.param("priority 仅 NORMAL/URGENT");
        }
        TicketDTO existing = ticketMapper.selectByRequestId(req.getRequestId());
        if (existing != null) {
            return existing;
        }
        if (req.getSessionId() != null && !req.getSessionId().isBlank()) {
            SessionDTO s = sessionMapper.selectById(req.getSessionId());
            if (s == null || !userId.equals(s.getUserId())) {
                throw BizException.notFound("会话不存在或不属于当前用户");
            }
        }
        TicketDTO t = new TicketDTO();
        t.setId("T" + ids.next("seq_ticket"));
        t.setSessionId(req.getSessionId());
        t.setUserId(userId);
        t.setUserName(req.getUserName());
        t.setCategory(category);
        t.setPriority(priority);
        t.setSummary(req.getSummary().trim());
        t.setDescription(req.getDescription());
        t.setOrderId(req.getOrderId());
        t.setAttachments(com.qoder.cs.core.biz.UploadService.validateList(req.getAttachments()));
        t.setRequestId(req.getRequestId());
        t.setSource(source);
        t.setCreatedAt(System.currentTimeMillis());
        try {
            ticketMapper.insert(t);
        } catch (DuplicateKeyException e) {
            return ticketMapper.selectByRequestId(req.getRequestId());
        }
        log.info("工单创建 id={} user={} category={} source={}", t.getId(), userId, category, source);
        return ticketMapper.selectById(t.getId());
    }

    public TicketDTO get(String id) {
        TicketDTO t = ticketMapper.selectById(id);
        if (t == null) {
            throw BizException.notFound("工单不存在: " + id);
        }
        return t;
    }

    public Map<String, Object> detail(String id) {
        TicketDTO t = get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", t);
        out.put("notes", noteMapper.selectByTicket(id));
        if (t.getSessionId() != null) {
            out.put("session", sessionMapper.selectById(t.getSessionId()));
        }
        return out;
    }

    public List<TicketDTO> query(String userId, String status, String category, int page, int size) {
        return query(userId, status, category, null, null, page, size);
    }

    public long count(String userId, String status, String category) {
        return count(userId, status, category, null, null);
    }

    /** 扩展筛选：source 来源系统 + q 关键字（摘要/工单号/订单号/用户模糊匹配），空白视为不筛。 */
    public List<TicketDTO> query(String userId, String status, String category, String source, String q,
                                 int page, int size) {
        return ticketMapper.query(blankToNull(userId), blankToNull(status), blankToNull(category),
                blankToNull(source), blankToNull(q), (page - 1) * size, size);
    }

    public long count(String userId, String status, String category, String source, String q) {
        return ticketMapper.count(blankToNull(userId), blankToNull(status), blankToNull(category),
                blankToNull(source), blankToNull(q));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public TicketDTO assign(String id, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            throw BizException.param("assignee 必填");
        }
        cas(id, "OPEN", "PROCESSING", assignee.trim(), null);
        return get(id);
    }

    public TicketDTO resolve(String id, String solution) {
        if (solution == null || solution.isBlank()) {
            throw BizException.param("solution 必填");
        }
        cas(id, "PROCESSING", "RESOLVED", null, solution.trim());
        return get(id);
    }

    public TicketDTO close(String id) {
        cas(id, "RESOLVED", "CLOSED", null, null);
        return get(id);
    }

    public TicketDTO cancel(String id, String userId) {
        TicketDTO t = get(id);
        if (!t.getUserId().equals(userId)) {
            throw BizException.notFound("工单不存在或不属于当前用户");
        }
        if ("OPEN".equals(t.getStatus())) {
            cas(id, "OPEN", "CANCELLED", null, null);
        } else if ("PROCESSING".equals(t.getStatus())) {
            cas(id, "PROCESSING", "CANCELLED", null, null);
        } else {
            throw BizException.conflict("当前状态不可取消: " + t.getStatus());
        }
        return get(id);
    }

    public TicketNoteDTO addNote(String ticketId, String author, String content) {
        get(ticketId);
        if (author == null || author.isBlank() || content == null || content.isBlank()) {
            throw BizException.param("author/content 必填");
        }
        TicketNoteDTO n = new TicketNoteDTO();
        n.setId("N" + ids.next("seq_note"));
        n.setTicketId(ticketId);
        n.setAuthor(author.trim());
        n.setContent(content.trim());
        n.setCreatedAt(System.currentTimeMillis());
        noteMapper.insert(n);
        return n;
    }

    @Scheduled(fixedDelay = 60_000)
    public void autoClose() {
        List<TicketDTO> stale = ticketMapper.resolvedBefore(System.currentTimeMillis() - AUTO_CLOSE_MS);
        for (TicketDTO t : stale) {
            if (ticketMapper.casStatus(t.getId(), "RESOLVED", "CLOSED", null, null, System.currentTimeMillis()) > 0) {
                log.info("工单自动关闭 id={}", t.getId());
            }
        }
    }

    private void cas(String id, String from, String to, String assignee, String solution) {
        get(id);
        if (ticketMapper.casStatus(id, from, to, assignee, solution, System.currentTimeMillis()) == 0) {
            throw BizException.conflict("状态流转失败：需 " + from + " → " + to + "（当前状态不符或已并发变更）");
        }
    }
}