package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** RBAC 域 DAO：perm_routes / perm_roles / perm_role_routes 三表访问。 */
public final class PermDao {

    private PermDao() {}

    public static void upsertRoute(String code, String path, String method, String kind, String name) {
        Db.exec("INSERT INTO perm_routes (code,path,method,kind,name) VALUES (?,?,?,?,?) " +
             "ON DUPLICATE KEY UPDATE path=VALUES(path), method=VALUES(method), kind=VALUES(kind), name=VALUES(name)",
                code, path, method, kind, name);
    }

    public static List<Map<String, Object>> listRoutes() {
        return Db.queryAll("SELECT code,path,method,kind,name FROM perm_routes ORDER BY kind,code");
    }

    public static List<String> roleRouteCodes(String roleCode) {
        List<Map<String, Object>> rows = Db.queryAll(
                "SELECT route_code FROM perm_role_routes WHERE role_code=? ORDER BY route_code", roleCode);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add((String) r.get("route_code"));
        return out;
    }

    public static void setRoleRoutes(String roleCode, List<String> codes) {
        try (Connection c = Db.conn()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM perm_role_routes WHERE role_code=?")) {
                ps.setString(1, roleCode);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO perm_role_routes (role_code,route_code) VALUES (?,?)")) {
                for (String code : codes) {
                    ps.setString(1, roleCode);
                    ps.setString(2, code);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Map<String, Object>> listRoles() {
        return Db.queryAll("SELECT code,name FROM perm_roles ORDER BY created_at, code");
    }

    public static Map<String, Object> getRole(String code) {
        return Db.queryOne("SELECT code,name FROM perm_roles WHERE code=?", code);
    }

    public static boolean createRole(String code, String name) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO perm_roles (code,name) VALUES (?,?)")) {
            ps.setString(1, code);
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) return false;
            throw new RuntimeException(e);
        }
    }

    public static void updateRoleName(String code, String name) {
        Db.exec("UPDATE perm_roles SET name=? WHERE code=?", name, code);
    }

    public static void deleteRole(String code) {
        Db.exec("DELETE FROM perm_role_routes WHERE role_code=?", code);
        Db.exec("DELETE FROM perm_roles WHERE code=?", code);
    }
}
