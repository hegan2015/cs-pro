package com.qoder.cs.core.open;

import com.qoder.cs.facade.dto.OpenKeyDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OpenKeyMapper {

    @Insert("INSERT INTO cs_open_key(api_key, tenant_id, secret, status, created_at) " +
            "VALUES(#{apiKey}, #{tenantId}, #{secret}, 'ACTIVE', #{createdAt})")
    int insert(OpenKeyDTO key);

    @Select("SELECT * FROM cs_open_key WHERE api_key = #{apiKey}")
    OpenKeyDTO selectByKey(@Param("apiKey") String apiKey);

    @Select("<script>SELECT COUNT(*) FROM cs_open_key " +
            "<if test='tenantId != null'>WHERE tenant_id = #{tenantId}</if></script>")
    long count(@Param("tenantId") String tenantId);

    @Select("<script>SELECT * FROM cs_open_key " +
            "<if test='tenantId != null'>WHERE tenant_id = #{tenantId}</if> " +
            "ORDER BY created_at DESC, api_key LIMIT #{size} OFFSET #{offset}</script>")
    List<OpenKeyDTO> page(@Param("tenantId") String tenantId, @Param("offset") int offset, @Param("size") int size);

    @Update("UPDATE cs_open_key SET status = 'REVOKED' WHERE api_key = #{apiKey} AND status = 'ACTIVE'")
    int revoke(@Param("apiKey") String apiKey);
}