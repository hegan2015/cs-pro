package com.qoder.cs.core.subscription;

import com.qoder.cs.facade.dto.SubscriptionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TenantMapper {

    @Select("SELECT NEXT VALUE FOR seq_tenant")
    long nextId();

    @Select("SELECT * FROM cs_tenant ORDER BY id")
    List<TenantDTO> selectAll();

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

    @Update("MERGE INTO cs_subscription(tenant_id, plan_name, days, status, start_at, expire_at, source, updated_at) " +
            "KEY(tenant_id) VALUES(#{tenantId}, #{planName}, #{days}, #{status}, #{startAt}, #{expireAt}, #{source}, #{updatedAt})")
    int upsertSubscription(SubscriptionDTO sub);

    @Update("UPDATE cs_subscription SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expire_at <= #{now}")
    int markExpired(@Param("now") long now);

    // ---- free usage（CAS 原子自增，并发不超发）----

    @Select("SELECT count FROM cs_free_usage WHERE user_id = #{userId} AND day = #{day}")
    Integer freeCount(@Param("userId") String userId, @Param("day") String day);

    @Update("UPDATE cs_free_usage SET count = count + 1 WHERE user_id = #{userId} AND day = #{day} AND count < #{quota}")
    int incrementFree(@Param("userId") String userId, @Param("day") String day, @Param("quota") int quota);

    @Insert("INSERT INTO cs_free_usage(user_id, day, count) VALUES(#{userId}, #{day}, 1)")
    int insertFree(@Param("userId") String userId, @Param("day") String day);
}