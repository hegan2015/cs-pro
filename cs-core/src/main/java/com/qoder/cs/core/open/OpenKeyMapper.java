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

    @Select("SELECT * FROM cs_open_key WHERE tenant_id = #{tenantId} ORDER BY created_at DESC")
    List<OpenKeyDTO> listByTenant(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM cs_open_key ORDER BY created_at DESC")
    List<OpenKeyDTO> listAll();

    @Update("UPDATE cs_open_key SET status = 'REVOKED' WHERE api_key = #{apiKey} AND status = 'ACTIVE'")
    int revoke(@Param("apiKey") String apiKey);
}