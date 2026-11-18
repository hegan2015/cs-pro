package com.qoder.cs.kb;

import com.qoder.cs.facade.dto.KbRevisionDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KbRevisionMapper {

    @Insert("INSERT INTO cs_kb_revision(kb_id, question, answer, category, keywords, saved_by, saved_at) " +
            "VALUES(#{kbId}, #{question}, #{answer}, #{category}, #{keywords}, #{savedBy}, #{savedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KbRevisionDTO revision);

    @Select("SELECT * FROM cs_kb_revision WHERE kb_id = #{kbId} ORDER BY saved_at DESC, id DESC")
    List<KbRevisionDTO> selectByKb(@Param("kbId") String kbId);

    @Select("SELECT * FROM cs_kb_revision WHERE id = #{id}")
    KbRevisionDTO selectById(@Param("id") long id);
}