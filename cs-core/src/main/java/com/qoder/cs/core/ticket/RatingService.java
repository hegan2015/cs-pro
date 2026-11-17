package com.qoder.cs.core.ticket;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.chat.MessageMapper;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.facade.dto.RatingDTO;
import com.qoder.cs.facade.request.RatingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 满意度评分：SESSION/MESSAGE/TICKET 三类目标，一目标一评（主键约束）。 */
@Service
@RequiredArgsConstructor
public class RatingService {

    private static final Set<String> TYPES = Set.of("SESSION", "MESSAGE", "TICKET");

    private final RatingMapper ratingMapper;
    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final TicketMapper ticketMapper;

    public RatingDTO rate(RatingRequest req) {
        if (req.getTargetType() == null || !TYPES.contains(req.getTargetType())) {
            throw BizException.param("targetType 仅 SESSION/MESSAGE/TICKET");
        }
        if (req.getTargetId() == null || req.getTargetId().isBlank()) {
            throw BizException.param("targetId 必填");
        }
        if (req.getScore() == null || req.getScore() < 1 || req.getScore() > 5) {
            throw BizException.param("score 需 1-5");
        }
        requireTarget(req.getTargetType(), req.getTargetId());
        if (ratingMapper.select(req.getTargetType(), req.getTargetId()) != null) {
            throw BizException.conflict("该对象已评分，不可重复评分");
        }
        RatingDTO r = new RatingDTO();
        r.setTargetType(req.getTargetType());
        r.setTargetId(req.getTargetId());
        r.setScore(req.getScore());
        r.setComment(req.getComment());
        r.setCreatedAt(System.currentTimeMillis());
        try {
            ratingMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("该对象已评分，不可重复评分");
        }
        return r;
    }

    private void requireTarget(String type, String id) {
        boolean exists = switch (type) {
            case "SESSION" -> sessionMapper.selectById(id) != null;
            case "TICKET" -> ticketMapper.selectById(id) != null;
            default -> messageMapper.selectById(id) != null;
        };
        if (!exists) {
            throw BizException.notFound("评分对象不存在: " + id);
        }
    }
}