package com.qoder.cs.core.chat;

import com.qoder.cs.facade.dto.LlmCallDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface LlmCallMapper {

    @Select("SELECT NEXT VALUE FOR seq_llm_call")
    long nextId();

    @Insert("INSERT INTO cs_llm_call(id, session_id, model, prompt_tokens, completion_tokens, latency_ms, status, error, created_at) " +
            "VALUES(#{id}, #{sessionId}, #{model}, #{promptTokens}, #{completionTokens}, #{latencyMs}, #{status}, #{error}, #{createdAt})")
    int insert(LlmCallDTO call);

    @Select("<script>SELECT * FROM cs_llm_call " +
            "<if test='status != null'>WHERE status = #{status}</if> " +
            "ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<LlmCallDTO> query(@Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM cs_llm_call WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM cs_llm_call")
    long sumTokens();
}