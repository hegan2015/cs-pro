package com.qoder.cs.kb;

import com.qoder.cs.facade.dto.KbEntryDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KbMapper {

    @Select("SELECT * FROM cs_kb ORDER BY id")
    List<KbEntryDTO> selectAll();

    @Select("SELECT * FROM cs_kb WHERE enabled = TRUE")
    List<KbEntryDTO> selectEnabled();

    @Select("SELECT * FROM cs_kb WHERE id = #{id}")
    KbEntryDTO selectById(@Param("id") String id);

    @Select("SELECT * FROM cs_kb WHERE question = #{question}")
    KbEntryDTO selectByQuestion(@Param("question") String question);


    @Insert("INSERT INTO cs_kb(id, question, answer, category, keywords, enabled, hit_count, updated_at) " +
            "VALUES(#{id}, #{question}, #{answer}, #{category}, #{keywords}, #{enabled}, 0, #{updatedAt})")
    int insert(KbEntryDTO entry);

    @Update("UPDATE cs_kb SET question=#{question}, answer=#{answer}, category=#{category}, " +
            "keywords=#{keywords}, enabled=#{enabled}, updated_at=#{updatedAt} WHERE id=#{id}")
    int update(KbEntryDTO entry);

    @Delete("DELETE FROM cs_kb WHERE id = #{id}")
    int delete(@Param("id") String id);

    @Update("UPDATE cs_kb SET hit_count = hit_count + 1 WHERE id = #{id}")
    int incrementHit(@Param("id") String id);

    @Update("UPDATE cs_kb SET cited_count = cited_count + 1 WHERE id = #{id}")
    int incrementCited(@Param("id") String id);

    @Update("UPDATE cs_kb SET cited_resolved_count = #{count} WHERE id = #{id}")
    int updateResolvedCount(@Param("id") String id, @Param("count") long count);

    /** 满 7 天观察期的引用次数（kb_refs 逗号列表按边界匹配，避免 K1 误配 K10） */
    @Select("SELECT COUNT(*) FROM cs_message m WHERE m.role = 'BOT' AND m.kb_refs IS NOT NULL " +
            "AND CONCAT(',', m.kb_refs, ',') LIKE CONCAT('%,', #{id}, ',%') AND m.created_at <= #{cutoff}")
    long countCitedMatured(@Param("id") String id, @Param("cutoff") long cutoff);

    /** 已解决近似口径：满 7 天且会话后续 7 天无工单、会话无差评 */
    @Select("SELECT COUNT(*) FROM cs_message m JOIN cs_session s ON m.session_id = s.id " +
            "WHERE m.role = 'BOT' AND CONCAT(',', m.kb_refs, ',') LIKE CONCAT('%,', #{id}, ',%') " +
            "AND m.created_at <= #{cutoff} " +
            "AND NOT EXISTS (SELECT 1 FROM cs_ticket t WHERE t.session_id = s.id " +
            "  AND t.created_at >= m.created_at AND t.created_at <= m.created_at + 604800000) " +
            "AND NOT EXISTS (SELECT 1 FROM cs_rating r WHERE r.target_type = 'SESSION' " +
            "  AND r.target_id = s.id AND r.score <= 2)")
    long countCitedResolved(@Param("id") String id, @Param("cutoff") long cutoff);

    @Select("SELECT * FROM cs_kb WHERE enabled = TRUE ORDER BY hit_count DESC, id LIMIT #{limit}")
    List<KbEntryDTO> topHit(@Param("limit") int limit);
}