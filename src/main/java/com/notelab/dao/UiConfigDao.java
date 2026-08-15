package com.notelab.dao;

import java.util.Map;

/** UI 配置域 DAO：ui_config 表单行读写。 */
public final class UiConfigDao {

    private UiConfigDao() {}

    public static String getUiConfigJson() {
        Map<String, Object> row = Db.queryOne("SELECT config FROM ui_config WHERE id=1");
        return row == null ? null : (String) row.get("config");
    }

    public static void saveUiConfig(String configJson) {
        Db.exec("INSERT INTO ui_config (id,config) VALUES (1,?) ON DUPLICATE KEY UPDATE config=?",
                configJson, configJson);
    }
}
