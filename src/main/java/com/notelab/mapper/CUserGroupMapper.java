package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.CUserGroup;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** c_user_groups 表 Mapper（C 端用户组） */
public interface CUserGroupMapper extends BaseMapper<CUserGroup> {

    /** 用户组列表 + 成员数（LEFT JOIN 统计，空组为 0） */
    @Select("""
            SELECT g.code, g.name, g.created_at, COUNT(u.id) AS member_count
            FROM c_user_groups g
            LEFT JOIN c_users u ON u.group_code = g.code
            GROUP BY g.code, g.name, g.created_at
            ORDER BY g.code""")
    List<Map<String, Object>> selectGroupsWithCount();
}
