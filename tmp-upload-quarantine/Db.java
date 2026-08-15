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
        // ---- RBAC: users.role 补列 + 权限路由/角色/角色-路由 三张新表（只增不改） ----
        Map<String, Object> roleCol = queryOne("SHOW COLUMNS FROM users LIKE 'role'");
        if (roleCol == null) {
            exec("ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'user'");
        }
        exec("""
            CREATE TABLE IF NOT EXISTS perm_routes (
                code VARCHAR(120) PRIMARY KEY,
                path VARCHAR(200) NOT NULL,
                method VARCHAR(30) NOT NULL DEFAULT '',
                kind VARCHAR(20) NOT NULL DEFAULT 'api',
                name VARCHAR(120) NOT NULL DEFAULT '',
                builtin TINYINT NOT NULL DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        exec("""
            CREATE TABLE IF NOT EXISTS perm_roles (
                code VARCHAR(50) PRIMARY KEY,
                name VARCHAR(50) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        exec("""
            CREATE TABLE IF NOT EXISTS perm_role_routes (
                role_code VARCHAR(50) NOT NULL,
                route_code VARCHAR(120) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (role_code, route_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        exec("""
            CREATE TABLE IF NOT EXISTS trpg_scenarios (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                title VARCHAR(120) NOT NULL,
                genre VARCHAR(60) DEFAULT '',
                summary VARCHAR(500) DEFAULT '',
                config_json MEDIUMTEXT,
                scenario_json MEDIUMTEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                KEY idx_trpg_s_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        exec("""
            CREATE TABLE IF NOT EXISTS trpg_gen_tasks (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                state VARCHAR(20) NOT NULL DEFAULT 'running',
                config_json MEDIUMTEXT,
                scenario_id BIGINT NULL,
                error VARCHAR(500) DEFAULT '',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                KEY idx_trpg_t_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        exec("""
            CREATE TABLE IF NOT EXISTS trpg_playthroughs (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                scenario_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                current_node VARCHAR(80) NOT NULL,
                state VARCHAR(20) DEFAULT 'playing',
                ending_title VARCHAR(160) DEFAULT '',
                steps INT DEFAULT 0,
                history_json MEDIUMTEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                KEY idx_trpg_p_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        exec("INSERT IGNORE INTO perm_roles (code,name) VALUES ('super_admin','超级管理员'),('user','普通用户')");
        // ---- AI 工具库：tools 表（只增不改） ----
        exec("""
            CREATE TABLE IF NOT EXISTS tools (
                id INT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                icon VARCHAR(20) NOT NULL DEFAULT '🔧',
                category VARCHAR(50) NOT NULL DEFAULT '自定义',
                type VARCHAR(30) NOT NULL DEFAULT 'api',
                description TEXT,
                endpoint VARCHAR(500) NOT NULL DEFAULT '',
                config MEDIUMTEXT,
                enabled TINYINT NOT NULL DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        seedToolsIfEmpty();
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



    // ---------- RBAC：权限路由 / 角色 / 账户角色 ----------
    public static void upsertRoute(String code, String path, String method, String kind, String name) {
        exec("INSERT INTO perm_routes (code,path,method,kind,name) VALUES (?,?,?,?,?) " +
             "ON DUPLICATE KEY UPDATE path=VALUES(path), method=VALUES(method), kind=VALUES(kind), name=VALUES(name)",
                code, path, method, kind, name);
    }

    public static List<Map<String, Object>> listRoutes() {
        return queryAll("SELECT code,path,method,kind,name FROM perm_routes ORDER BY kind,code");
    }

    public static List<String> roleRouteCodes(String roleCode) {
        List<Map<String, Object>> rows = queryAll(
                "SELECT route_code FROM perm_role_routes WHERE role_code=? ORDER BY route_code", roleCode);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add((String) r.get("route_code"));
        return out;
    }

    public static void setRoleRoutes(String roleCode, List<String> codes) {
        try (Connection c = conn()) {
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

    public static List<Map<String, Object>> listUsersForPerm() {
        return queryAll("SELECT id,username,email,role,created_at FROM users ORDER BY id");
    }

    public static void setUserRole(long uid, String role) {
        exec("UPDATE users SET role=? WHERE id=?", role, uid);
    }

    public static void setUserPassword(long uid, String passwordHash) {
        exec("UPDATE users SET password_hash=? WHERE id=?", passwordHash, uid);
    }

    public static long countSuperAdmins() {
        try (Connection c = conn();
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
        exec("UPDATE users SET role='super_admin' WHERE id=(SELECT id FROM (SELECT MIN(id) AS id FROM users) t)");
    }

    public static long createUserWithRole(String username, String passwordHash, String email, String role) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
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
            if ("23000".equals(e.getSQLState())) throw new UniqueViolation(e);
            throw new RuntimeException(e);
        }
    }



    // ---------- AI 工具库 ----------
    /** 首次建表后种子示例工具（仅表为空时写入，幂等） */
    private static void seedToolsIfEmpty() {
        Map<String, Object> row = queryOne("SELECT COUNT(*) AS n FROM tools");
        long n = row == null ? 0 : ((Number) row.get("n")).longValue();
        if (n > 0) return;
        exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "语音合成 TTS", "🔊", "语音", "api",
                "微软 edge-tts 神经音色朗读，NoteLab 英语页 /api/tts 已实际接入；可扩更多音色与语种",
                "/api/tts", "{\"provider\":\"edge-tts\",\"voice\":\"en-US-AriaNeural\"}", 1);
        exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "图像生成", "🎨", "生成", "api",
                "文本生成图像（DashScope 文生图服务示例条目，接入需在配置中补充 API 密钥引用）",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
                "{\"model\":\"wanx2.1-t2i-turbo\",\"size\":\"1024*1024\"}", 0);
        exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "联网搜索", "🌐", "搜索", "api",
                "为对话提供实时联网检索能力（示例条目，可对接博查/Bing 等搜索 API）",
                "", "{\"provider\":\"bocha\",\"top_k\":5}", 0);
        exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "MCP 文件服务", "🔌", "MCP", "mcp",
                "Model Context Protocol 工具示例：本地文件系统读写（stdio 方式），供支持 MCP 的客户端挂载",
                "stdio://filesystem", "{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/root/notelab-java/data\"]}", 0);
    }

    public static List<Map<String, Object>> listTools() {
        return queryAll("SELECT id,name,icon,category,type,description,endpoint,config,enabled,created_at,updated_at FROM tools ORDER BY id");
    }

    public static Map<String, Object> getTool(long id) {
        return queryOne("SELECT * FROM tools WHERE id=?", id);
    }

    public static long createTool(String name, String icon, String category, String type,
                                  String description, String endpoint, String config, boolean enabled) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
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
        exec("UPDATE tools SET name=?,icon=?,category=?,type=?,description=?,endpoint=?,config=?,enabled=? WHERE id=?",
                name, icon, category, type, description, endpoint, config, enabled ? 1 : 0, id);
    }

    public static void setToolEnabled(long id, boolean enabled) {
        exec("UPDATE tools SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
    }

    public static void deleteTool(long id) {
        exec("DELETE FROM tools WHERE id=?", id);
    }


    // ---------- TRPG 跑团 ----------
    public static long createTrpgScenario(long userId, String title, String genre, String summary, String configJson, String scenarioJson) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO trpg_scenarios (user_id,title,genre,summary,config_json,scenario_json) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId); ps.setString(2, title); ps.setString(3, genre); ps.setString(4, summary);
            ps.setString(5, configJson); ps.setString(6, scenarioJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static List<Map<String, Object>> listTrpgScenarios(long userId) {
        return queryAll("SELECT id,user_id,title,genre,summary,created_at,updated_at FROM trpg_scenarios WHERE user_id=? ORDER BY id DESC", userId);
    }

    public static Map<String, Object> getTrpgScenario(long id) {
        return queryOne("SELECT * FROM trpg_scenarios WHERE id=?", id);
    }

    public static void deleteTrpgScenario(long id, long userId) {
        exec("DELETE FROM trpg_playthroughs WHERE scenario_id=? AND user_id=?", id, userId);
        exec("DELETE FROM trpg_scenarios WHERE id=? AND user_id=?", id, userId);
    }

    public static long createTrpgPlay(long scenarioId, long userId, String startNode) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO trpg_playthroughs (scenario_id,user_id,current_node,history_json) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scenarioId); ps.setLong(2, userId); ps.setString(3, startNode); ps.setString(4, "[]");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static Map<String, Object> getTrpgPlay(long id) {
        return queryOne("SELECT p.*, s.title AS scenario_title, s.genre AS scenario_genre FROM trpg_playthroughs p JOIN trpg_scenarios s ON s.id=p.scenario_id WHERE p.id=?", id);
    }

    public static List<Map<String, Object>> listTrpgPlays(long userId) {
        return queryAll("SELECT p.id,p.scenario_id,p.current_node,p.state,p.ending_title,p.steps,p.updated_at,s.title AS scenario_title,s.genre AS scenario_genre FROM trpg_playthroughs p JOIN trpg_scenarios s ON s.id=p.scenario_id WHERE p.user_id=? ORDER BY p.updated_at DESC LIMIT 50", userId);
    }

    public static void updateTrpgPlay(long id, String currentNode, String state, String endingTitle, int steps, String historyJson) {
        exec("UPDATE trpg_playthroughs SET current_node=?,state=?,ending_title=?,steps=?,history_json=? WHERE id=?",
                currentNode, state, endingTitle, steps, historyJson, id);
    }

    public static void deleteTrpgPlay(long id, long userId) {
        exec("DELETE FROM trpg_playthroughs WHERE id=? AND user_id=?", id, userId);
    }

    // ---------- TRPG 生成任务（异步化：规避长请求被代理层超时断开） ----------
    public static long createTrpgGenTask(long userId, String configJson) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO trpg_gen_tasks (user_id,config_json) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId); ps.setString(2, configJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static Map<String, Object> getTrpgGenTask(long id) {
        return queryOne("SELECT * FROM trpg_gen_tasks WHERE id=?", id);
    }

    public static void finishTrpgGenTask(long id, long scenarioId) {
        exec("UPDATE trpg_gen_tasks SET state='done', scenario_id=?, error='' WHERE id=?", scenarioId, id);
    }

    public static void failTrpgGenTask(long id, String error) {
        String e = error == null ? "" : (error.length() > 480 ? error.substring(0, 480) : error);
        exec("UPDATE trpg_gen_tasks SET state='error', error=? WHERE id=?", e, id);
    }

    /** 启动时把残留的 running 任务标记为中断（进程重启即丢失，提示用户重新生成） */
    public static void abortStaleTrpgGenTasks() {
        exec("UPDATE trpg_gen_tasks SET state='error', error='服务重启，生成任务中断，请重新生成' WHERE state='running'");
    }

    // ---------- 用户管理：动态角色组 ----------
    public static List<Map<String, Object>> listRoles() {
        return queryAll("SELECT code,name FROM perm_roles ORDER BY created_at, code");
    }

    public static Map<String, Object> getRole(String code) {
        return queryOne("SELECT code,name FROM perm_roles WHERE code=?", code);
    }

    public static boolean createRole(String code, String name) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
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
        exec("UPDATE perm_roles SET name=? WHERE code=?", name, code);
    }

    public static void deleteRole(String code) {
        exec("DELETE FROM perm_role_routes WHERE role_code=?", code);
        exec("DELETE FROM perm_roles WHERE code=?", code);
    }

    public static long countUsersByRole(String role) {
        Map<String, Object> r = queryOne("SELECT COUNT(*) AS n FROM users WHERE role=?", role);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    public static void migrateUsersToRole(String fromRole, String toRole) {
        exec("UPDATE users SET role=? WHERE role=?", toRole, fromRole);
    }

    // ---------- 用户管理：账户编辑 ----------
    public static void updateUserInfo(long id, String username, String email) {
        exec("UPDATE users SET username=?, email=? WHERE id=?", username, email, id);
    }

    public static void deleteUser(long id) {
        exec("DELETE FROM users WHERE id=?", id);
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
