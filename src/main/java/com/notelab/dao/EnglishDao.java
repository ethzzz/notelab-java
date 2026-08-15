package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/** 英语学习域 DAO：english_conversations + english_messages 表访问。 */
public final class EnglishDao {

    private EnglishDao() {}

    public static List<Map<String, Object>> enListConversations(long userId) {
        return Db.queryAll("SELECT id,title,scenario,created_at,updated_at FROM english_conversations WHERE user_id=? ORDER BY updated_at DESC", userId);
    }

    public static long enCreateConversation(long userId, String title, String scenario) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO english_conversations (user_id,title,scenario) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, scenario);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> enGetConversation(long cid, long userId) {
        return Db.queryOne("SELECT * FROM english_conversations WHERE id=? AND user_id=?", cid, userId);
    }

    /** 与 db.py 一致：仅删会话行，消息由外键级联删除。 */
    public static void enDeleteConversation(long cid, long userId) {
        Db.exec("DELETE FROM english_conversations WHERE id=? AND user_id=?", cid, userId);
    }

    public static void enSetTitle(long cid, String title) {
        Db.exec("UPDATE english_conversations SET title=? WHERE id=?", title, cid);
    }

    public static void enTouch(long cid) {
        Db.exec("UPDATE english_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", cid);
    }

    public static List<Map<String, Object>> enListMessages(long cid) {
        return Db.queryAll("SELECT role,content,correction,error_note,created_at FROM english_messages WHERE conversation_id=? ORDER BY id ASC", cid);
    }

    public static void enAddMessage(long cid, String role, String content, String correction, String errorNote) {
        Db.exec("INSERT INTO english_messages (conversation_id,role,content,correction,error_note) VALUES (?,?,?,?,?)",
                cid, role, content, correction, errorNote);
    }
}
