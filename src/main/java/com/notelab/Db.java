package com.notelab;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据层：直连 Python 版同一个 MySQL 库（notelab），两服务共享数据。
 * 表结构与行为和 Python 版 db.py 完全一致（建表语句逐条照抄 db.py）。
 * 连接池：HikariCP，最大 10 连接（与 Python 侧 pymysql 短连接并发读写兼容）。
 * 配置来自 MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
 * （进程环境变量 → ./.env → /root/notelab/.env，见 AppConfig）。
 */
public final class Db {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile HikariDataSource ds;

    private Db() {}

    public static synchronized void init() {
        if (ds != null) return;
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("notelab-mysql");
        cfg.setJdbcUrl("jdbc:mysql://" + AppConfig.mysqlHost() + ":" + AppConfig.mysqlPort() + "/"
                + AppConfig.mysqlDb() + "?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true");
        cfg.setUsername(AppConfig.mysqlUser());
        cfg.setPassword(AppConfig.mysqlPassword());
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5000);
        ds = new HikariDataSource(cfg);
        try {
            for (String s : SCHEMA) exec(s);
            for (String s : ENGLISH_SCHEMA) exec(s);
            migrateSchema();
        } catch (RuntimeException e) {
            throw new IllegalStateException("MySQL 初始化失败: " + e.getMessage(), e);
        }
    }

    /** 对应 db.py migrate_schema：users.email 补列 + ui_config 表（只增不改）。 */
    private static void migrateSchema() {
        Map<String, Object> col = queryOne("SHOW COLUMNS FROM users LIKE 'email'");
        if (col == null) {
            exec("ALTER TABLE users ADD COLUMN email VARCHAR(100) NULL UNIQUE");
        }
        exec("""
            CREATE TABLE IF NOT EXISTS ui_config (
                id INT PRIMARY KEY,
                config MEDIUMTEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    // 建表语句与 /root/notelab/db.py 的 SCHEMA / ENGLISH_SCHEMA 逐条一致（仅 CREATE TABLE IF NOT EXISTS）。
    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(50) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                email VARCHAR(100) NULL UNIQUE,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT NOT NULL,
                title VARCHAR(200) NOT NULL DEFAULT '新对话',
                model VARCHAR(100) NOT NULL DEFAULT 'qwen3.8-max',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_user (user_id),
                CONSTRAINT fk_conv_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INT AUTO_INCREMENT PRIMARY KEY,
                conversation_id INT NOT NULL,
                role VARCHAR(20) NOT NULL,
                content MEDIUMTEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_conv (conversation_id),
                CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
    };

    private static final String[] ENGLISH_SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS english_conversations (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT NOT NULL,
                title VARCHAR(200) NOT NULL DEFAULT '新对话',
                scenario VARCHAR(50) NOT NULL DEFAULT 'free',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_en_user (user_id),
                CONSTRAINT fk_en_conv_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS english_messages (
                id INT AUTO_INCREMENT PRIMARY KEY,
                conversation_id INT NOT NULL,
                role VARCHAR(20) NOT NULL,
                content MEDIUMTEXT NOT NULL,
                correction MEDIUMTEXT NULL,
                error_note VARCHAR(500) NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_en_conv (conversation_id),
                CONSTRAINT fk_en_msg_conv FOREIGN KEY (conversation_id) REFERENCES english_conversations(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
    };

    /** 模拟 Python pymysql.err.IntegrityError（唯一键冲突） */
    public static final class UniqueViolation extends RuntimeException {
        public UniqueViolation(SQLException cause) { super(cause); }
    }

    // ---------- users ----------
    public static long createUser(String username, String passwordHash, String email) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
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
            if ("23000".equals(e.getSQLState())) throw new UniqueViolation(e);
            throw new RuntimeException(e);
        }
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

    public static long createConversation(long userId, String title, String model) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
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
        return queryOne("SELECT * FROM conversations WHERE id=? AND user_id=?", cid, userId);
    }

    /** 与 db.py 一致：仅删会话行，消息由外键 ON DELETE CASCADE 级联删除。 */
    public static void deleteConversation(long cid, long userId) {
        exec("DELETE FROM conversations WHERE id=? AND user_id=?", cid, userId);
    }

    public static void setConversationTitle(long cid, String title) {
        exec("UPDATE conversations SET title=? WHERE id=?", title, cid);
    }

    public static void setConversationModel(long cid, String model) {
        exec("UPDATE conversations SET model=? WHERE id=?", model, cid);
    }

    public static void touchConversation(long cid) {
        exec("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", cid);
    }

    // ---------- messages ----------
    public static List<Map<String, Object>> listMessages(long cid) {
        return queryAll("SELECT role,content,created_at FROM messages WHERE conversation_id=? ORDER BY id ASC", cid);
    }

    public static void addMessage(long cid, String role, String content) {
        exec("INSERT INTO messages (conversation_id,role,content) VALUES (?,?,?)", cid, role, content);
    }

    public static long countMessages(long cid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) AS n FROM messages WHERE conversation_id=?")) {
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

    public static long enCreateConversation(long userId, String title, String scenario) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
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
        return queryOne("SELECT * FROM english_conversations WHERE id=? AND user_id=?", cid, userId);
    }

    /** 与 db.py 一致：仅删会话行，消息由外键级联删除。 */
    public static void enDeleteConversation(long cid, long userId) {
        exec("DELETE FROM english_conversations WHERE id=? AND user_id=?", cid, userId);
    }

    public static void enSetTitle(long cid, String title) {
        exec("UPDATE english_conversations SET title=? WHERE id=?", title, cid);
    }

    public static void enTouch(long cid) {
        exec("UPDATE english_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", cid);
    }

    public static List<Map<String, Object>> enListMessages(long cid) {
        return queryAll("SELECT role,content,correction,error_note,created_at FROM english_messages WHERE conversation_id=? ORDER BY id ASC", cid);
    }

    public static void enAddMessage(long cid, String role, String content, String correction, String errorNote) {
        exec("INSERT INTO english_messages (conversation_id,role,content,correction,error_note) VALUES (?,?,?,?,?)",
                cid, role, content, correction, errorNote);
    }

    // ---------- UI 配置 ----------
    public static String getUiConfigJson() {
        Map<String, Object> row = queryOne("SELECT config FROM ui_config WHERE id=1");
        return row == null ? null : (String) row.get("config");
    }

    public static void saveUiConfig(String configJson) {
        exec("INSERT INTO ui_config (id,config) VALUES (1,?) ON DUPLICATE KEY UPDATE config=?",
                configJson, configJson);
    }

    // ---------- 内部工具 ----------
    private static Connection conn() throws SQLException {
        HikariDataSource d = ds;
        if (d == null) throw new SQLException("Db.init() 尚未调用");
        return d.getConnection();
    }

    private static void exec(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> queryOne(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return row(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Map<String, Object>> queryAll(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
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
            Object v = rs.getObject(i);
            // 对齐 Python 的 str(datetime)：'yyyy-MM-dd HH:mm:ss'
            if (v instanceof LocalDateTime ldt) v = ldt.format(TS);
            else if (v instanceof Timestamp t) v = t.toLocalDateTime().format(TS);
            m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), v);
        }
        return m;
    }
}
