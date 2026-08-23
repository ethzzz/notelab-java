package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.TrpgGenTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** trpg_gen_tasks 表 Mapper。 */
public interface TrpgGenTaskMapper extends BaseMapper<TrpgGenTask> {

    /** 完成态更新（原 SQL 原样：state='done' + error 置空串） */
    @Update("UPDATE trpg_gen_tasks SET state='done', scenario_id=#{scenarioId}, error='' WHERE id=#{id}")
    int finishTask(@Param("id") long id, @Param("scenarioId") long scenarioId);

    /** 失败态更新（error 由调用方截断后传入） */
    @Update("UPDATE trpg_gen_tasks SET state='error', error=#{error} WHERE id=#{id}")
    int failTask(@Param("id") long id, @Param("error") String error);

    /** 启动时把残留 running 任务标记为中断 */
    @Update("UPDATE trpg_gen_tasks SET state='error', error='服务重启，生成任务中断，请重新生成' WHERE state='running'")
    int abortStale();
}
