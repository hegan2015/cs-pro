package com.qoder.cs.core.chat;

import com.qoder.cs.facade.dto.SessionDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SessionMapper {

    @Select("SELECT NEXT VALUE FOR seq_session")
    long nextId();

    @Insert("INSERT INTO cs_session(id, user_role, user_id, user_name, tenant_code, status, created_at) " +
            "VALUES(#{id}, #{userRole}, #{userId}, #{userName}, #{tenantCode}, 'ACTIVE', #{createdAt})")
    int insert(SessionDTO session);

    @Select("SELECT * FROM cs_session WHERE id = #{id}")
    SessionDTO selectById(@Param("id") String id);

    @Update("UPDATE cs_session SET status = 'ENDED', ended_at = #{now}, intent = #{intent} WHERE id = #{id} AND status = 'ACTIVE'")
    int end(@Param("id") String id, @Param("now") long now, @Param("intent") String intent);

    @Update("UPDATE cs_session SET intent = #{intent} WHERE id = #{id}")
    int updateIntent(@Param("id") String id, @Param("intent") String intent);

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
}