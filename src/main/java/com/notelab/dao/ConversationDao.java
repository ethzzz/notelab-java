package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/** 会话域 DAO：conversations + messages 表访问（智能对话）。 */
public final class ConversationDao {

    private ConversationDao() {}

    public static List<Map<String, Object>> listConversations(long userId) {
        return Db.queryAll("SELECT id,title,model,created_at,updated_at FROM conversations WHERE user_id=? ORDER BY updated_at DESC", userId);
    }

    public static long createConversation(long userId, String title, String model) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conversations (user_id,title,model) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, model);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> getConversation(long cid, long userId) {
        return Db.queryOne("SELECT * FROM conversations WHERE id=? AND user_id=?", cid, userId);
    }

    /** 与 db.py 一致：仅删会话行，消息由外键 ON DELETE CASCADE 级联删除。 */
    public static void deleteConversation(long cid, long userId) {
        Db.exec("DELETE FROM conversations WHERE id=? AND user_id=?", cid, userId);
    }

    public static void setConversationTitle(long cid, String title) {
        Db.exec("UPDATE conversations SET title=? WHERE id=?", title, cid);
    }

    public static void setConversationModel(long cid, String model) {
        Db.exec("UPDATE conversations SET model=? WHERE id=?", model, cid);
    }

    public static void touchConversation(long cid) {
        Db.exec("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", cid);
    }

    public static List<Map<String, Object>> listMessages(long cid) {
        return Db.queryAll("SELECT role,content,created_at FROM messages WHERE conversation_id=? ORDER BY id ASC", cid);
    }

    public static void addMessage(long cid, String role, String content) {
        Db.exec("INSERT INTO messages (conversation_id,role,content) VALUES (?,?,?)", cid, role, content);
    }

    public static long countMessages(long cid) {
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) AS n FROM messages WHERE conversation_id=?")) {
            ps.setLong(1, cid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
