package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.EnTrGroup;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** en_tr_groups 表 Mapper（每日英语翻译练习 · 句子组）。 */
public interface EnTrGroupMapper extends BaseMapper<EnTrGroup> {

    /** 组列表 + 句子数（子查询统计，避免额外一次全表扫描） */
    @Select("""
            SELECT g.id, g.title, g.status, g.activated_date, g.source, g.scenario, g.note,
                   g.created_by, g.created_at, g.updated_at,
                   (SELECT COUNT(*) FROM en_tr_sentences s WHERE s.group_id = g.id) AS sentence_count
            FROM en_tr_groups g
            ORDER BY g.id DESC""")
    List<Map<String, Object>> selectGroupsWithCount();
}
