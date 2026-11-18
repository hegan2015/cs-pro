package com.qoder.cs.core.ticket;

import com.qoder.cs.facade.dto.RatingDTO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RatingMapper {

    @Insert("INSERT INTO cs_rating(target_type, target_id, score, comment, created_at) " +
            "VALUES(#{targetType}, #{targetId}, #{score}, #{comment}, #{createdAt})")
    int insert(RatingDTO rating);

    @Select("SELECT * FROM cs_rating WHERE target_type = #{targetType} AND target_id = #{targetId}")
    RatingDTO select(@Param("targetType") String targetType, @Param("targetId") String targetId);

    @Select("SELECT COUNT(*) FROM cs_rating WHERE target_type = #{targetType}")
    long countByType(@Param("targetType") String targetType);

    @Select("SELECT COALESCE(AVG(score), 0) FROM cs_rating WHERE target_type = #{targetType}")
    double avgByType(@Param("targetType") String targetType);

    @Select("SELECT COUNT(*) FROM cs_rating WHERE target_type = #{targetType} AND score <= 2")
    long lowCountByType(@Param("targetType") String targetType);

    @Select("SELECT COUNT(*) FROM cs_rating r JOIN cs_message m ON r.target_id = m.id " +
            "WHERE r.target_type = 'MESSAGE' AND m.session_id = #{sessionId} AND r.score <= 2")
    long lowMessageCountBySession(@Param("sessionId") String sessionId);
}