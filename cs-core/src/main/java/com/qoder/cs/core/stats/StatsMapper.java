package com.qoder.cs.core.stats;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface StatsMapper {

    @Insert("MERGE INTO stats_daily(day, metric, value) KEY(day, metric) VALUES(#{day}, #{metric}, #{value})")
    int upsertDaily(@Param("day") String day, @Param("metric") String metric, @Param("value") long value);

    @Select("SELECT day, metric, value FROM stats_daily WHERE day >= #{fromDay} AND day <= #{toDay} ORDER BY day")
    List<Map<String, Object>> range(@Param("fromDay") String fromDay, @Param("toDay") String toDay);

    @Select("SELECT COALESCE(AVG(latency_ms), 0) FROM cs_message WHERE role = 'BOT' AND latency_ms IS NOT NULL")
    double avgBotLatency();
}