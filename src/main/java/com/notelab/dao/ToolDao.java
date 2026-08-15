package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/** AI 工具库域 DAO：tools 表 CRUD。 */
public final class ToolDao {

    private ToolDao() {}

    public static List<Map<String, Object>> listTools() {
        return Db.queryAll("SELECT id,name,icon,category,type,description,endpoint,config,enabled,created_at,updated_at FROM tools ORDER BY id");
    }

    public static Map<String, Object> getTool(long id) {
        return Db.queryOne("SELECT * FROM tools WHERE id=?", id);
    }

    public static long createTool(String name, String icon, String category, String type,
                                  String description, String endpoint, String config, boolean enabled) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, icon);
            ps.setString(3, category);
            ps.setString(4, type);
            ps.setString(5, description);
            ps.setString(6, endpoint);
            ps.setString(7, config);
            ps.setInt(8, enabled ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void updateTool(long id, String name, String icon, String category, String type,
                                  String description, String endpoint, String config, boolean enabled) {
        Db.exec("UPDATE tools SET name=?,icon=?,category=?,type=?,description=?,endpoint=?,config=?,enabled=? WHERE id=?",
                name, icon, category, type, description, endpoint, config, enabled ? 1 : 0, id);
    }

    public static void setToolEnabled(long id, boolean enabled) {
        Db.exec("UPDATE tools SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
    }

    public static void deleteTool(long id) {
        Db.exec("DELETE FROM tools WHERE id=?", id);
    }
}
