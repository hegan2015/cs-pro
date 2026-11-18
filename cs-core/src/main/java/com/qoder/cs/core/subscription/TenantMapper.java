package com.qoder.cs.core.subscription;

import com.qoder.cs.facade.dto.SubscriptionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TenantMapper {


    @Select("SELECT * FROM cs_tenant ORDER BY id")
    List<TenantDTO> selectAll();

    @Select("SELECT COUNT(*) FROM cs_tenant")
    long countAll();

    @Select("SELECT * FROM cs_tenant ORDER BY id LIMIT #{size} OFFSET #{offset}")
    List<TenantDTO> page(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT * FROM cs_tenant WHERE id = #{id}")
    TenantDTO selectById(@Param("id") String id);

    @Select("SELECT * FROM cs_tenant WHERE code = #{code}")
    TenantDTO selectByCode(@Param("code") String code);

    @Insert("INSERT INTO cs_tenant(id, code, name, status, created_at) VALUES(#{id}, #{code}, #{name}, 'ACTIVE', #{createdAt})")
    int insert(TenantDTO tenant);

    @Update("UPDATE cs_tenant SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    // ---- subscription（一租户一行，MERGE upsert）----

    @Select("SELECT * FROM cs_subscription WHERE tenant_id = #{tenantId}")
    SubscriptionDTO selectSubscription(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM cs_subscription")
    List<SubscriptionDTO> selectAllSubscriptions();

    @Update("INSERT INTO cs_subscription(tenant_id, plan_name, plan_code, days, status, start_at, expire_at, source, updated_at) " +
            "VALUES(#{tenantId}, #{planName}, #{planCode}, #{days}, #{status}, #{startAt}, #{expireAt}, #{source}, #{updatedAt}) " +
            "ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name), plan_code = VALUES(plan_code), days = VALUES(days), status = VALUES(status), " +
            "start_at = VALUES(start_at), expire_at = VALUES(expire_at), source = VALUES(source), updated_at = VALUES(updated_at)")
    int upsertSubscription(SubscriptionDTO sub);

    @Update("UPDATE cs_subscription SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expire_at <= #{now}")
    int markExpired(@Param("now") long now);

    // ---- free usage（CAS 原子自增，并发不超发）----

    @Select("SELECT used_count FROM cs_free_usage WHERE user_id = #{userId} AND use_day = #{day}")
    Integer freeCount(@Param("userId") String userId, @Param("day") String day);

    @Update("UPDATE cs_free_usage SET used_count = used_count + 1 WHERE user_id = #{userId} AND use_day = #{day} AND used_count < #{quota}")
    int incrementFree(@Param("userId") String userId, @Param("day") String day, @Param("quota") int quota);

    @Insert("INSERT INTO cs_free_usage(user_id, use_day, used_count) VALUES(#{userId}, #{day}, 1)")
    int insertFree(@Param("userId") String userId, @Param("day") String day);
}