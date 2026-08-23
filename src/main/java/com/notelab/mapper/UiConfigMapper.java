package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.UiConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** ui_config 表 Mapper。 */
public interface UiConfigMapper extends BaseMapper<UiConfig> {

    /** 单行 upsert（原 SQL 原样保留） */
    @Insert("INSERT INTO ui_config (id,config) VALUES (1,#{config}) ON DUPLICATE KEY UPDATE config=#{config}")
    int upsertConfig(@Param("config") String config);
}
