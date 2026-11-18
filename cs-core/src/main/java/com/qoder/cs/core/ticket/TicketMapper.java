package com.qoder.cs.core.ticket;

import com.qoder.cs.facade.dto.TicketDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TicketMapper {


    @Insert("INSERT INTO cs_ticket(id, session_id, user_id, category, priority, summary, order_id, status, assignee, request_id, source, attachments, created_at) " +
            "VALUES(#{id}, #{sessionId}, #{userId}, #{category}, #{priority}, #{summary}, #{orderId}, 'OPEN', #{assignee}, #{requestId}, #{source}, #{attachments}, #{createdAt})")
    int insert(TicketDTO ticket);

    @Select("SELECT * FROM cs_ticket WHERE id = #{id}")
    TicketDTO selectById(@Param("id") String id);

    @Select("SELECT * FROM cs_ticket WHERE request_id = #{requestId}")
    TicketDTO selectByRequestId(@Param("requestId") String requestId);

    @Select("<script>SELECT * FROM cs_ticket WHERE 1=1 " +
            "<if test='userId != null'>AND user_id = #{userId}</if> " +
            "<if test='status != null'>AND status = #{status}</if> " +
            "<if test='category != null'>AND category = #{category}</if> " +
            "ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TicketDTO> query(@Param("userId") String userId, @Param("status") String status,
                          @Param("category") String category, @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM cs_ticket WHERE 1=1 " +
            "<if test='userId != null'>AND user_id = #{userId}</if> " +
            "<if test='status != null'>AND status = #{status}</if> " +
            "<if test='category != null'>AND category = #{category}</if></script>")
    long count(@Param("userId") String userId, @Param("status") String status, @Param("category") String category);

    @Update("UPDATE cs_ticket SET status = #{to}, assignee = COALESCE(#{assignee}, assignee), " +
            "resolved_at = CASE WHEN #{to} = 'RESOLVED' THEN #{now} ELSE resolved_at END, " +
            "closed_at = CASE WHEN #{to} = 'CLOSED' THEN #{now} ELSE closed_at END, " +
            "solution = COALESCE(#{solution}, solution) " +
            "WHERE id = #{id} AND status = #{from}")
    int casStatus(@Param("id") String id, @Param("from") String from, @Param("to") String to,
                  @Param("assignee") String assignee, @Param("solution") String solution, @Param("now") long now);

    @Select("SELECT * FROM cs_ticket WHERE status = 'RESOLVED' AND resolved_at < #{before}")
    List<TicketDTO> resolvedBefore(@Param("before") long before);

    @Select("SELECT COUNT(*) FROM cs_ticket WHERE created_at >= #{from} AND created_at < #{to}")
    long countBetween(@Param("from") long from, @Param("to") long to);

    @Select("SELECT COUNT(*) FROM cs_ticket WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}