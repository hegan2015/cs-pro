package com.qoder.cs.core.chat;

import com.qoder.cs.facade.dto.SessionDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SessionMapper {


    @Insert("INSERT INTO cs_session(id, user_role, user_id, user_name, tenant_code, status, access_token, created_at) " +
            "VALUES(#{id}, #{userRole}, #{userId}, #{userName}, #{tenantCode}, 'ACTIVE', #{accessToken}, #{createdAt})")
    int insert(SessionDTO session);

    @Select("SELECT * FROM cs_session WHERE id = #{id}")
    SessionDTO selectById(@Param("id") String id);

    @Select("SELECT * FROM cs_session WHERE user_id = #{userId} AND tenant_code = #{tenantCode} " +
            "AND status = 'ACTIVE' ORDER BY created_at DESC, id DESC LIMIT 1")
    SessionDTO latestActiveByUser(@Param("userId") String userId, @Param("tenantCode") String tenantCode);

    @Update("UPDATE cs_session SET status = 'ENDED', ended_at = #{now}, intent = #{intent} WHERE id = #{id} AND status = 'ACTIVE'")
    int end(@Param("id") String id, @Param("now") long now, @Param("intent") String intent);

    @Update("UPDATE cs_session SET intent = #{intent} WHERE id = #{id}")
    int updateIntent(@Param("id") String id, @Param("intent") String intent);

    @Update("UPDATE cs_session SET takeover = #{on} WHERE id = #{id}")
    int setTakeover(@Param("id") String id, @Param("on") boolean on);

    @Select("<script>SELECT * FROM cs_session WHERE 1=1 " +
            "<if test='status != null'>AND status = #{status}</if> " +
            "<if test='from != null'>AND created_at &gt;= #{from}</if> " +
            "<if test='to != null'>AND created_at &lt;= #{to}</if> " +
            "ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<SessionDTO> query(@Param("status") String status, @Param("from") Long from,
                           @Param("to") Long to, @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM cs_session WHERE 1=1 " +
            "<if test='status != null'>AND status = #{status}</if> " +
            "<if test='from != null'>AND created_at &gt;= #{from}</if> " +
            "<if test='to != null'>AND created_at &lt;= #{to}</if></script>")
    long count(@Param("status") String status, @Param("from") Long from, @Param("to") Long to);

    @Select("<script>SELECT * FROM cs_session WHERE tenant_code = #{tenantCode} " +
            "<if test='status != null'>AND status = #{status}</if> " +
            "ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<SessionDTO> queryByTenant(@Param("tenantCode") String tenantCode, @Param("status") String status,
                                   @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM cs_session WHERE tenant_code = #{tenantCode} " +
            "<if test='status != null'>AND status = #{status}</if></script>")
    long countByTenant(@Param("tenantCode") String tenantCode, @Param("status") String status);

    /** 坏例候选：会话差评 / 机器人消息差评 / LLM 降级（FALLBACK/BUDGET/ABORTED） */
    String BADCASE_WHERE =
            "EXISTS (SELECT 1 FROM cs_rating r WHERE r.target_type = 'SESSION' AND r.target_id = s.id AND r.score <= 2) " +
            "OR EXISTS (SELECT 1 FROM cs_rating r JOIN cs_message m ON r.target_id = m.id " +
            "  WHERE r.target_type = 'MESSAGE' AND m.session_id = s.id AND r.score <= 2) " +
            "OR EXISTS (SELECT 1 FROM cs_llm_call c WHERE c.session_id = s.id " +
            "  AND c.status IN ('FALLBACK','BUDGET','ABORTED'))";

    @Select("SELECT COUNT(DISTINCT s.id) FROM cs_session s WHERE " + BADCASE_WHERE)
    long badcasesCount();

    @Select("SELECT DISTINCT s.* FROM cs_session s WHERE " + BADCASE_WHERE +
            " ORDER BY s.created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<SessionDTO> badcases(@Param("offset") int offset, @Param("limit") int limit);
}