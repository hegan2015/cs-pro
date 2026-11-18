package com.qoder.cs.core.subscription;

import com.qoder.cs.facade.dto.ActivationCodeDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ActivationCodeMapper {

    @Insert("INSERT INTO cs_activation_code(code, days, plan_code, status, created_at) " +
            "VALUES(#{code}, #{days}, #{planCode}, 'UNUSED', #{createdAt})")
    int insert(ActivationCodeDTO code);

    @Select("SELECT * FROM cs_activation_code WHERE code = #{code}")
    ActivationCodeDTO selectByCode(@Param("code") String code);

    @Select("<script>SELECT * FROM cs_activation_code " +
            "<if test='status != null'>WHERE status = #{status}</if> ORDER BY created_at DESC, code</script>")
    List<ActivationCodeDTO> list(@Param("status") String status);

    @Select("<script>SELECT COUNT(*) FROM cs_activation_code " +
            "<if test='status != null'>WHERE status = #{status}</if></script>")
    long count(@Param("status") String status);

    @Select("<script>SELECT * FROM cs_activation_code " +
            "<if test='status != null'>WHERE status = #{status}</if> " +
            "ORDER BY created_at DESC, code LIMIT #{size} OFFSET #{offset}</script>")
    List<ActivationCodeDTO> page(@Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    /** 一码一用：CAS 仅 UNUSED 可翻转，并发兑换仅一胜。 */
    @Update("UPDATE cs_activation_code SET status = 'USED', used_by_tenant = #{tenantId}, used_at = #{now} " +
            "WHERE code = #{code} AND status = 'UNUSED'")
    int redeem(@Param("code") String code, @Param("tenantId") String tenantId, @Param("now") long now);
}