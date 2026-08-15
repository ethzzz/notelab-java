package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/** 用户域 DAO：users 表的账户/角色相关访问。 */
public final class UserDao {

    private UserDao() {}

    public static long createUser(String username, String passwordHash, String email) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (username,password_hash,email) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) throw new Db.UniqueViolation(e);
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> getUserByUsername(String username) {
        return Db.queryOne("SELECT * FROM users WHERE username=?", username);
    }

    public static Map<String, Object> getUserByEmail(String email) {
        return Db.queryOne("SELECT * FROM users WHERE email=?", email);
    }

    public static Map<String, Object> getUserById(long id) {
        return Db.queryOne("SELECT * FROM users WHERE id=?", id);
    }

    public static List<Map<String, Object>> listUsersForPerm() {
        return Db.queryAll("SELECT id,username,email,role,created_at FROM users ORDER BY id");
    }

    public static void setUserRole(long uid, String role) {
        Db.exec("UPDATE users SET role=? WHERE id=?", role, uid);
    }

    public static void setUserPassword(long uid, String passwordHash) {
        Db.exec("UPDATE users SET password_hash=? WHERE id=?", passwordHash, uid);
    }

    public static long countSuperAdmins() {
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) AS n FROM users WHERE role='super_admin'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 首次自举：若尚无超级管理员，把最早注册的用户提升为超级管理员 */
    public static void promoteFirstUserToAdmin() {
        Db.exec("UPDATE users SET role='super_admin' WHERE id=(SELECT id FROM (SELECT MIN(id) AS id FROM users) t)");
    }

    public static long createUserWithRole(String username, String passwordHash, String email, String role) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (username,password_hash,email,role) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, email);
            ps.setString(4, role);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) throw new Db.UniqueViolation(e);
            throw new RuntimeException(e);
        }
    }

    public static long countUsersByRole(String role) {
        Map<String, Object> r = Db.queryOne("SELECT COUNT(*) AS n FROM users WHERE role=?", role);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    public static void migrateUsersToRole(String fromRole, String toRole) {
        Db.exec("UPDATE users SET role=? WHERE role=?", toRole, fromRole);
    }

    public static void updateUserInfo(long id, String username, String email) {
        Db.exec("UPDATE users SET username=?, email=? WHERE id=?", username, email, id);
    }

    public static void deleteUser(long id) {
        Db.exec("DELETE FROM users WHERE id=?", id);
    }
}
