package com.qoder.cs.core.subscription;

import com.qoder.cs.facade.dto.SubOrderDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SubOrderMapper {


    @Insert("INSERT INTO cs_sub_order(id, tenant_id, amount, days, request_id, status, created_at) " +
            "VALUES(#{id}, #{tenantId}, #{amount}, #{days}, #{requestId}, #{status}, #{createdAt})")
    int insert(SubOrderDTO order);

    @Select("SELECT * FROM cs_sub_order WHERE id = #{id}")
    SubOrderDTO selectById(@Param("id") String id);

    @Select("SELECT * FROM cs_sub_order ORDER BY created_at DESC")
    List<SubOrderDTO> selectAll();

    @Select("SELECT COUNT(*) FROM cs_sub_order")
    long countAll();

    @Select("SELECT * FROM cs_sub_order ORDER BY created_at DESC, id LIMIT #{size} OFFSET #{offset}")
    List<SubOrderDTO> page(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT * FROM cs_sub_order WHERE status = 'PENDING' AND pp_order_id IS NOT NULL")
    List<SubOrderDTO> selectPending();

    @Update("UPDATE cs_sub_order SET pp_order_id = #{ppOrderId}, pp_payment_id = #{ppPaymentId} WHERE id = #{id}")
    int updatePpIds(@Param("id") String id, @Param("ppOrderId") String ppOrderId, @Param("ppPaymentId") String ppPaymentId);

    @Update("UPDATE cs_sub_order SET status = #{to}, paid_at = #{paidAt} WHERE id = #{id} AND status = #{from}")
    int casStatus(@Param("id") String id, @Param("from") String from, @Param("to") String to, @Param("paidAt") Long paidAt);
}