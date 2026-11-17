package com.qoder.cs.core.support;

import org.apache.ibatis.annotations.*;

@Mapper
public interface IdempotentMapper {

    @Insert("INSERT INTO idempotent_record(request_id, biz_type, result_json, created_at) " +
            "VALUES(#{requestId}, #{bizType}, #{resultJson}, #{createdAt})")
    int insert(@Param("requestId") String requestId, @Param("bizType") String bizType,
               @Param("resultJson") String resultJson, @Param("createdAt") long createdAt);

    @Select("SELECT result_json FROM idempotent_record WHERE request_id = #{requestId}")
    String selectResult(@Param("requestId") String requestId);

    @Update("UPDATE idempotent_record SET result_json = #{resultJson} WHERE request_id = #{requestId} AND result_json IS NULL")
    int fillResult(@Param("requestId") String requestId, @Param("resultJson") String resultJson);
}