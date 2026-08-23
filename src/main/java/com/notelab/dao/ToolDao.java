package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.Tool;

import java.util.List;
import java.util.Map;

/** AI 工具库域 DAO：tools 表 CRUD（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class ToolDao {

    private ToolDao() {}

    /** 原 listTools 与 getTool（SELECT *）的投影列序一致：表列序 */
    private static final String[] COLS = {"id", "name", "icon", "category", "type", "description",
            "endpoint", "config", "enabled", "created_at", "updated_at"};

    public static List<Map<String, Object>> listTools() {
        return RowUtil.rows(DaoSupport.tool().selectList(
                Wrappers.lambdaQuery(Tool.class).orderByAsc(Tool::getId)), COLS);
    }

    public static Map<String, Object> getTool(long id) {
        return RowUtil.row(DaoSupport.tool().selectById(id), COLS);
    }

    public static long createTool(String name, String icon, String category, String type,
                                  String description, String endpoint, String config, boolean enabled) {
        Tool t = new Tool();
        t.setName(name);
        t.setIcon(icon);
        t.setCategory(category);
        t.setType(type);
        t.setDescription(description);
        t.setEndpoint(endpoint);
        t.setConfig(config);
        t.setEnabled(enabled ? 1 : 0);
        DaoSupport.tool().insert(t);
        return t.getId() == null ? -1 : t.getId();
    }

    public static void updateTool(long id, String name, String icon, String category, String type,
                                  String description, String endpoint, String config, boolean enabled) {
        // 与原 UPDATE 一致：全部列显式覆盖（含 null），不用 MP 默认的跳过 null 策略
        DaoSupport.tool().update(Wrappers.lambdaUpdate(Tool.class)
                .eq(Tool::getId, id)
                .set(Tool::getName, name)
                .set(Tool::getIcon, icon)
                .set(Tool::getCategory, category)
                .set(Tool::getType, type)
                .set(Tool::getDescription, description)
                .set(Tool::getEndpoint, endpoint)
                .set(Tool::getConfig, config)
                .set(Tool::getEnabled, enabled ? 1 : 0));
    }

    public static void setToolEnabled(long id, boolean enabled) {
        DaoSupport.tool().update(Wrappers.lambdaUpdate(Tool.class)
                .eq(Tool::getId, id)
                .set(Tool::getEnabled, enabled ? 1 : 0));
    }

    public static void deleteTool(long id) {
        DaoSupport.tool().deleteById(id);
    }
}
