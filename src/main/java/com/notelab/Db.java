package com.notelab;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据层：与 Python 版 db.py 的表结构/行为一致，但使用独立的 SQLite 文件
 * （data/notelab-java.db），不触碰 Python 服务的数据。
 * 单连接 + synchronized：SQLite 写入串行化，读多写少场景足够。
 */
public final class Db {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Connection conn;

    private Db() {}

    public static synchronized void init() {
        try {
            Path dbFile = AppConfig.dataDir().resolve("notelab-java.db");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
                for (String s : SCHEMA) st.execute(s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 初始化失败: " + e.getMessage(), e);
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                email TEXT UNIQUE,
                created_at TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                title TEXT NOT NULL DEFAULT '新对话',
                model TEXT NOT NULL DEFAULT 'qwen3.8-max',
                created_at TEXT,
                updated_at TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS english_conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                title TEXT NOT NULL DEFAULT '新对话',
                scenario TEXT NOT NULL DEFAULT 'free',
                created_at TEXT,
                updated_at TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS english_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL REFERENCES english_conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                correction TEXT,
                error_note TEXT,
                created_at TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS ui_config (
                id INTEGER PRIMARY KEY,
                config TEXT NOT NULL,
                updated_at TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_conv_user ON conversations(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id)",
            "CREATE INDEX IF NOT EXISTS idx_en_conv_user ON english_conversations(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_en_msg_conv ON english_messages(conversation_id)",
    };

    // ---------- users ----------
    public static synchronized long createUser(String username, String passwordHash, String email) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username,password_hash,email,created_at) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, email);
            ps.setString(4, now());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new UniqueViolation(e);
        }
    }

    /** 模拟 Python pymysql.err.IntegrityError（唯一键冲突） */
    public static final class UniqueViolation extends RuntimeException {
        public UniqueViolation(SQLException cause) { super(cause); }
    }

    public static Map<String, Object> getUserByUsername(String username) {
        return queryOne("SELECT * FROM users WHERE username=?", username);
    }

    public static Map<String, Object> getUserByEmail(String email) {
        return queryOne("SELECT * FROM users WHERE email=?", email);
    }

    public static Map<String, Object> getUserById(long id) {
        return queryOne("SELECT * FROM users WHERE id=?", id);
    }

    // ---------- conversations ----------
    public static List<Map<String, Object>> listConversations(long userId) {
        return queryAll("SELECT id,title,model,created_at,updated_at FROM conversations WHERE user_id=? ORDER BY updated_at DESC", userId);
    }

    public static synchronized long createConversation(long userId, String title, String model) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO conversations (user_id,title,model,created_at,updated_at) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, model);
            ps.setString(4, now());
            ps.setString(5, now());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> getConversation(long cid, long userId) {
        return queryOne("SELECT * FROM conversations WHERE id=? AND user_id=?", cid, userId);
    }

    public static synchronized void deleteConversation(long cid, long userId) {
        exec("DELETE FROM conversations WHERE id=? AND user_id=?", cid, userId);
        exec("DELETE FROM messages WHERE conversation_id=?", cid); // SQLite 级联兜底
    }

    public static synchronized void setConversationTitle(long cid, String title) {
        exec("UPDATE conversations SET title=? WHERE id=?", title, cid);
    }

    public static synchronized void setConversationModel(long cid, String model) {
        exec("UPDATE conversations SET model=? WHERE id=?", model, cid);
    }

    public static synchronized void touchConversation(long cid) {
        exec("UPDATE conversations SET updated_at=? WHERE id=?", now(), cid);
    }

    // ---------- messages ----------
    public static List<Map<String, Object>> listMessages(long cid) {
        return queryAll("SELECT role,content,created_at FROM messages WHERE conversation_id=? ORDER BY id ASC", cid);
    }

    public static synchronized void addMessage(long cid, String role, String content) {
        exec("INSERT INTO messages (conversation_id,role,content,created_at) VALUES (?,?,?,?)", cid, role, content, now());
    }

    public static synchronized long countMessages(long cid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) AS n FROM messages WHERE conversation_id=?")) {
            ps.setLong(1, cid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ---------- English Learning ----------
    public static List<Map<String, Object>> enListConversations(long userId) {
        return queryAll("SELECT id,title,scenario,created_at,updated_at FROM english_conversations WHERE user_id=? ORDER BY updated_at DESC", userId);
    }

    public static synchronized long enCreateConversation(long userId, String title, String scenario) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO english_conversations (user_id,title,scenario,created_at,updated_at) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, scenario);
            ps.setString(4, now());
            ps.setString(5, now());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> enGetConversation(long cid, long userId) {
        return queryOne("SELECT * FROM english_conversations WHERE id=? AND user_id=?", cid, userId);
    }

    public static synchronized void enDeleteConversation(long cid, long userId) {
        exec("DELETE FROM english_conversations WHERE id=? AND user_id=?", cid, userId);
        exec("DELETE FROM english_messages WHERE conversation_id=?", cid);
    }

    public static synchronized void enTouch(long cid) {
        exec("UPDATE english_conversations SET updated_at=? WHERE id=?", now(), cid);
    }

    public static List<Map<String, Object>> enListMessages(long cid) {
        return queryAll("SELECT role,content,correction,error_note,created_at FROM english_messages WHERE conversation_id=? ORDER BY id ASC", cid);
    }

    public static synchronized void enAddMessage(long cid, String role, String content, String correction, String errorNote) {
        exec("INSERT INTO english_messages (conversation_id,role,content,correction,error_note,created_at) VALUES (?,?,?,?,?,?)",
                cid, role, content, correction, errorNote, now());
    }

    // ---------- UI 配置 ----------
    public static synchronized String getUiConfigJson() {
        Map<String, Object> row = queryOne("SELECT config FROM ui_config WHERE id=1");
        return row == null ? null : (String) row.get("config");
    }

    public static synchronized void saveUiConfig(String configJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ui_config (id,config,updated_at) VALUES (1,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET config=excluded.config, updated_at=excluded.updated_at")) {
            ps.setString(1, configJson);
            ps.setString(2, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- 内部工具 ----------
    private static synchronized void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> queryOne(String sql, Object... args) {
        synchronized (Db.class) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return row(rs);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static List<Map<String, Object>> queryAll(String sql, Object... args) {
        synchronized (Db.class) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) out.add(row(rs));
                    return out;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setNull(i + 1, java.sql.Types.NULL);
            else if (a instanceof Long l) ps.setLong(i + 1, l);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else ps.setString(i + 1, a.toString());
        }
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        int n = rs.getMetaData().getColumnCount();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
        }
        return m;
    }
}
