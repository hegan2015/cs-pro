package com.qoder.cs.core.chat;

import com.qoder.cs.facade.dto.MessageDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MessageMapper {


    @Insert("INSERT INTO cs_message(id, session_id, role, content, tool_name, tool_result, kb_refs, ticket_draft, tool_trace, attachments, latency_ms, tokens, created_at) " +
            "VALUES(#{id}, #{sessionId}, #{role}, #{content}, #{toolName}, #{toolResult}, #{kbRefs}, #{ticketDraft}, #{toolTrace}, #{attachments}, #{latencyMs}, #{tokens}, #{createdAt})")
    int insert(MessageDTO message);

    @Select("SELECT * FROM cs_message WHERE id = #{id}")
    MessageDTO selectById(@Param("id") String id);

    @Select("SELECT * FROM cs_message WHERE session_id = #{sessionId} ORDER BY created_at, id")
    List<MessageDTO> selectBySession(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM cs_message WHERE session_id = #{sessionId} AND role IN ('USER','BOT') ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<MessageDTO> recentDialog(@Param("sessionId") String sessionId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM cs_message WHERE session_id = #{sessionId} AND role = 'USER' AND created_at >= #{dayStart}")
    int countUserToday(@Param("sessionId") String sessionId, @Param("dayStart") long dayStart);

    @Select("SELECT COUNT(*) FROM cs_message")
    long countAll();

    @Select("SELECT COUNT(*) FROM cs_message WHERE created_at >= #{from} AND created_at < #{to}")
    long countBetween(@Param("from") long from, @Param("to") long to);
}