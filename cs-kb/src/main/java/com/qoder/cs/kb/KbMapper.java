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

    @Select("SELECT NEXT VALUE FOR seq_kb")
    long nextId();

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

    @Select("SELECT * FROM cs_kb WHERE enabled = TRUE ORDER BY hit_count DESC, id LIMIT #{limit}")
    List<KbEntryDTO> topHit(@Param("limit") int limit);
}