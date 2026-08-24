package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.TrpgPlaythrough;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** trpg_playthroughs 表 Mapper。 */
public interface TrpgPlaythroughMapper extends BaseMapper<TrpgPlaythrough> {

    /** 双表 JOIN（p.* + 剧本别名列），原 SQL 原样保留；map key 为列别名（小写） */
    @Select("SELECT p.*, s.title AS scenario_title, s.genre AS scenario_genre "
            + "FROM trpg_playthroughs p JOIN trpg_scenarios s ON s.id=p.scenario_id WHERE p.id=#{id}")
    Map<String, Object> getPlayJoined(@Param("id") long id);

    /**
     * 双表 JOIN 对局列表（投影列与原 SQL 完全一致）。
     * B/C 拆分阶段2：补 scope 过滤（B 端传 'b'，存量数据默认 'b'，结果集等价；C 端传 'c'）。
     */
    @Select("SELECT p.id,p.scenario_id,p.current_node,p.state,p.ending_title,p.steps,p.updated_at,"
            + "s.title AS scenario_title,s.genre AS scenario_genre "
            + "FROM trpg_playthroughs p JOIN trpg_scenarios s ON s.id=p.scenario_id "
            + "WHERE p.scope=#{scope} AND p.user_id=#{userId} ORDER BY p.updated_at DESC LIMIT 50")
    List<Map<String, Object>> listPlaysJoined(@Param("scope") String scope, @Param("userId") long userId);
}
