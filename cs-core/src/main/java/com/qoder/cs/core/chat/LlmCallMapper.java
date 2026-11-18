package com.qoder.cs.core.chat;

import com.qoder.cs.facade.dto.LlmCallDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface LlmCallMapper {


    @Insert("INSERT INTO cs_llm_call(id, session_id, model, prompt_version, prompt_tokens, completion_tokens, latency_ms, status, error, created_at) " +
            "VALUES(#{id}, #{sessionId}, #{model}, #{promptVersion}, #{promptTokens}, #{completionTokens}, #{latencyMs}, #{status}, #{error}, #{createdAt})")
    int insert(LlmCallDTO call);

    @Select("<script>SELECT * FROM cs_llm_call " +
            "<if test='status != null'>WHERE status = #{status}</if> " +
            "ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<LlmCallDTO> query(@Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM cs_llm_call WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM cs_llm_call")
    long countAll();

    @Select("SELECT COUNT(*) FROM cs_llm_call WHERE session_id = #{sessionId} AND status IN ('FALLBACK','BUDGET','ABORTED')")
    long countDegradedBySession(@Param("sessionId") String sessionId);

    @Select("SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM cs_llm_call")
    long sumTokens();

    @Select("SELECT COALESCE(SUM(c.prompt_tokens + c.completion_tokens), 0) FROM cs_llm_call c " +
            "JOIN cs_session s ON c.session_id = s.id " +
            "WHERE s.tenant_code = #{tenantCode} AND c.status = 'OK' AND c.created_at >= #{dayStart}")
    long sumTokensTodayByTenant(@Param("tenantCode") String tenantCode, @Param("dayStart") long dayStart);

    /** 账期聚合：仅 OK 调用计入账单（降级/熔断不收费）。返回 promptTokens/completTokens/calls。 */
    @Select("SELECT COALESCE(SUM(c.prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(c.completion_tokens), 0) AS completTokens, COUNT(*) AS calls " +
            "FROM cs_llm_call c JOIN cs_session s ON c.session_id = s.id " +
            "WHERE s.tenant_code = #{tenantCode} AND c.status = 'OK' " +
            "AND c.created_at >= #{from} AND c.created_at < #{to}")
    com.qoder.cs.facade.dto.InvoiceDTO aggregateByTenant(@Param("tenantCode") String tenantCode,
                                                         @Param("from") long from, @Param("to") long to);
}