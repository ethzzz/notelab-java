package com.notelab.dao;

import com.notelab.model.entity.UiConfig;

/** UI 配置域 DAO：ui_config 表单行读写（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class UiConfigDao {

    private UiConfigDao() {}

    public static String getUiConfigJson() {
        UiConfig c = DaoSupport.uiConfig().selectById(1);
        return c == null ? null : c.getConfig();
    }

    /** 单行 upsert（ON DUPLICATE KEY UPDATE，原 SQL 由 Mapper 注解原样保留） */
    public static void saveUiConfig(String configJson) {
        DaoSupport.uiConfig().upsertConfig(configJson);
    }
}
