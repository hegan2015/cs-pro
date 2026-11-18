package com.qoder.cs.core.audit;

import com.qoder.cs.facade.dto.AdminAuditDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AdminAuditMapper {

    @Insert("INSERT INTO cs_admin_audit(actor, action, target, detail, created_at) " +
            "VALUES(#{actor}, #{action}, #{target}, #{detail}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminAuditDTO audit);

    @Select("<script>SELECT * FROM cs_admin_audit WHERE 1=1 " +
            "<if test='action != null'>AND action = #{action}</if> " +
            "<if test='actor != null'>AND actor = #{actor}</if> " +
            "<if test='from != null'>AND created_at &gt;= #{from}</if> " +
            "<if test='to != null'>AND created_at &lt;= #{to}</if> " +
            "ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AdminAuditDTO> query(@Param("action") String action, @Param("actor") String actor,
                              @Param("from") Long from, @Param("to") Long to,
                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM cs_admin_audit WHERE 1=1 " +
            "<if test='action != null'>AND action = #{action}</if> " +
            "<if test='actor != null'>AND actor = #{actor}</if> " +
            "<if test='from != null'>AND created_at &gt;= #{from}</if> " +
            "<if test='to != null'>AND created_at &lt;= #{to}</if></script>")
    long count(@Param("action") String action, @Param("actor") String actor,
               @Param("from") Long from, @Param("to") Long to);
}