package com.qoder.cs.core.billing;

import com.qoder.cs.facade.dto.InvoiceDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InvoiceMapper {

    @Update("INSERT INTO cs_invoice(id, tenant_id, period, prompt_tokens, complet_tokens, calls, amount, plan_code, created_at) " +
            "VALUES(#{id}, #{tenantId}, #{period}, #{promptTokens}, #{completTokens}, #{calls}, #{amount}, #{planCode}, #{createdAt}) " +
            "ON DUPLICATE KEY UPDATE prompt_tokens = VALUES(prompt_tokens), complet_tokens = VALUES(complet_tokens), " +
            "calls = VALUES(calls), amount = VALUES(amount), plan_code = VALUES(plan_code), created_at = VALUES(created_at)")
    int upsert(InvoiceDTO invoice);

    @Select("SELECT * FROM cs_invoice WHERE tenant_id = #{tenantId} AND period = #{period}")
    InvoiceDTO selectByTenantPeriod(@Param("tenantId") String tenantId, @Param("period") String period);

    @Select("SELECT * FROM cs_invoice WHERE tenant_id = #{tenantId} ORDER BY period DESC")
    List<InvoiceDTO> listByTenant(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM cs_invoice WHERE period = #{period} ORDER BY tenant_id")
    List<InvoiceDTO> listByPeriod(@Param("period") String period);

    @Select("SELECT * FROM cs_invoice ORDER BY period DESC, tenant_id")
    List<InvoiceDTO> listAll();

    @Select("<script>SELECT COUNT(*) FROM cs_invoice " +
            "<where><if test='tenantId != null'>tenant_id = #{tenantId}</if> " +
            "<if test='period != null'>AND period = #{period}</if></where></script>")
    long count(@Param("tenantId") String tenantId, @Param("period") String period);

    @Select("<script>SELECT * FROM cs_invoice " +
            "<where><if test='tenantId != null'>tenant_id = #{tenantId}</if> " +
            "<if test='period != null'>AND period = #{period}</if></where> " +
            "ORDER BY period DESC, tenant_id LIMIT #{size} OFFSET #{offset}</script>")
    List<InvoiceDTO> page(@Param("tenantId") String tenantId, @Param("period") String period,
                          @Param("offset") int offset, @Param("size") int size);
}