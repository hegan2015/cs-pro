package com.qoder.cs.core.proactive;

import com.qoder.cs.facade.dto.ProactiveEventDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ProactiveEventMapper {

    /** event_key 主键即幂等锁：多实例并发轮询时插入失败方跳过推送 */
    @Insert("INSERT INTO cs_proactive_event(event_key, tenant_id, user_id, session_id, payload, status, created_at) " +
            "VALUES(#{eventKey}, #{tenantId}, #{userId}, #{sessionId}, #{payload}, #{status}, #{createdAt})")
    int insert(ProactiveEventDTO event);

    @Update("UPDATE cs_proactive_event SET session_id = #{sessionId} WHERE event_key = #{eventKey}")
    int updateSession(@Param("eventKey") String eventKey, @Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM cs_proactive_event")
    long count();

    @Select("SELECT * FROM cs_proactive_event ORDER BY created_at DESC, event_key LIMIT #{limit} OFFSET #{offset}")
    List<ProactiveEventDTO> page(@Param("offset") int offset, @Param("limit") int limit);
}