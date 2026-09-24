package com.notelab.dao;

import java.util.Map;

/**
 * 建表层：表结构与行为和 Python 版 db.py 完全一致（建表语句逐条照抄 db.py）。
 * 只增不改：新表/补列一律 CREATE TABLE IF NOT EXISTS / ALTER 前探测。
 * 由 Db.init() 在连接池就绪后委托调用。
 */
final class DbSchema {

    private DbSchema() {}

    static void initSchema() {
        for (String s : SCHEMA) Db.exec(s);
        for (String s : ENGLISH_SCHEMA) Db.exec(s);
        migrateSchema();
        translateSchema();
    }

    /**
     * 每日英语翻译练习三张新表（en_tr_groups / en_tr_sentences / en_tr_submissions）。
     * 纯只增：CREATE TABLE IF NOT EXISTS，重启幂等，不改/删任何现有表。
     * submissions 的 uk_user_sentence_date 唯一键是「同人同日同句只留一条」去重覆盖的基础。
     */
    private static void translateSchema() {
        Db.exec("""
            CREATE TABLE IF NOT EXISTS en_tr_groups (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                title VARCHAR(120) NOT NULL,
                status VARCHAR(20) NOT NULL DEFAULT 'draft',
                activated_date DATE NULL,
                source VARCHAR(20) NOT NULL DEFAULT 'manual',
                scenario VARCHAR(60) DEFAULT '',
                note VARCHAR(255) DEFAULT '',
                created_by BIGINT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                KEY idx_status (status),
                KEY idx_actdate (activated_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS en_tr_sentences (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                group_id BIGINT NOT NULL,
                tier TINYINT NOT NULL,
                sort_order INT NOT NULL DEFAULT 0,
                zh_text VARCHAR(255) NOT NULL,
                ref_en VARCHAR(500) NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                KEY idx_group (group_id, tier, sort_order),
                CONSTRAINT fk_entr_s_group FOREIGN KEY (group_id) REFERENCES en_tr_groups(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS en_tr_submissions (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                c_user_id INT NOT NULL,
                sentence_id BIGINT NOT NULL,
                group_id BIGINT NOT NULL,
                submit_date DATE NOT NULL,
                en_text TEXT NOT NULL,
                accurate TINYINT NULL,
                score INT NULL,
                corrected VARCHAR(500) NULL,
                explanation TEXT NULL,
                errors_json TEXT NULL,
                model VARCHAR(60) DEFAULT '',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_user_sentence_date (c_user_id, sentence_id, submit_date),
                KEY idx_user_date (c_user_id, submit_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    /** 对应 db.py migrate_schema：users.email 补列 + ui_config 表（只增不改）。 */
    private static void migrateSchema() {
        Map<String, Object> col = Db.queryOne("SHOW COLUMNS FROM users LIKE 'email'");
        if (col == null) {
            Db.exec("ALTER TABLE users ADD COLUMN email VARCHAR(100) NULL UNIQUE");
        }
        Db.exec("""
            CREATE TABLE IF NOT EXISTS ui_config (
                id INT PRIMARY KEY,
                config MEDIUMTEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // ---- RBAC: users.role 补列 + 权限路由/角色/角色-路由 三张新表（只增不改） ----
        Map<String, Object> roleCol = Db.queryOne("SHOW COLUMNS FROM users LIKE 'role'");
        if (roleCol == null) {
            Db.exec("ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'user'");
        }
        Db.exec("""
            CREATE TABLE IF NOT EXISTS perm_routes (
                code VARCHAR(120) PRIMARY KEY,
                path VARCHAR(200) NOT NULL,
                method VARCHAR(30) NOT NULL DEFAULT '',
                kind VARCHAR(20) NOT NULL DEFAULT 'api',
                name VARCHAR(120) NOT NULL DEFAULT '',
                builtin TINYINT NOT NULL DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS perm_roles (
                code VARCHAR(50) PRIMARY KEY,
                name VARCHAR(50) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS perm_role_routes (
                role_code VARCHAR(50) NOT NULL,
                route_code VARCHAR(120) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (role_code, route_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
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
        Db.exec("""
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
        Db.exec("""
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
        Db.exec("INSERT IGNORE INTO perm_roles (code,name) VALUES ('super_admin','超级管理员'),('user','普通用户')");
        // ---- AI 工具库：tools 表（只增不改） ----
        Db.exec("""
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
        // ---- B/C 拆分阶段1：C 端用户体系（c_users / c_user_groups，只增不改） ----
        Db.exec("""
            CREATE TABLE IF NOT EXISTS c_users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(50) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                nickname VARCHAR(50) NOT NULL DEFAULT '',
                group_code VARCHAR(50) NOT NULL DEFAULT 'default',
                status VARCHAR(20) NOT NULL DEFAULT 'active',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS c_user_groups (
                code VARCHAR(50) PRIMARY KEY,
                name VARCHAR(50) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("INSERT IGNORE INTO c_user_groups (code,name) VALUES ('default','默认组')");
        // 爬塔角色授权（spire.charAccess）：VIP 组种子——INSERT IGNORE 幂等，已存在则不动（不改现有组名）
        Db.exec("INSERT IGNORE INTO c_user_groups (code,name) VALUES ('vip','VIP用户')");
        // ---- C 端注册邀请码（只增不改）：max_uses 为可注册次数上限，超出不可再用 ----
        Db.exec("""
            CREATE TABLE IF NOT EXISTS invite_codes (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                code VARCHAR(32) NOT NULL UNIQUE,
                max_uses INT NOT NULL DEFAULT 1,
                used_count INT NOT NULL DEFAULT 0,
                revoked TINYINT NOT NULL DEFAULT 0,
                remark VARCHAR(255) NOT NULL DEFAULT '',
                created_by BIGINT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // ---- B 端文档编辑（只增不改）：富文本正文存 content_html，导入 .docx 解析后可编辑/导出 ----
        Db.exec("""
            CREATE TABLE IF NOT EXISTS documents (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                title VARCHAR(200) NOT NULL,
                content_html LONGTEXT NOT NULL,
                size_bytes BIGINT NOT NULL DEFAULT 0,
                created_by BIGINT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // ---- B 端笔记（只增不改）：Markdown 正文存 content_md，Notion 式笔记列表/编辑/查看 ----
        Db.exec("""
            CREATE TABLE IF NOT EXISTS notes (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                title VARCHAR(200) NOT NULL,
                content_md LONGTEXT NOT NULL,
                size_bytes BIGINT NOT NULL DEFAULT 0,
                created_by BIGINT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // ---- B/C 拆分阶段2：TRPG 数据归属补列（只增不改；MySQL 无 ADD COLUMN IF NOT EXISTS，先查 information_schema 再 ALTER，重启幂等） ----
        if (!hasColumn("trpg_playthroughs", "scope")) {
            Db.exec("ALTER TABLE trpg_playthroughs ADD COLUMN scope CHAR(1) NOT NULL DEFAULT 'b'");
        }
        if (!hasIndex("trpg_playthroughs", "idx_trpg_p_scope_user")) {
            Db.exec("ALTER TABLE trpg_playthroughs ADD KEY idx_trpg_p_scope_user (scope, user_id)");
        }
        if (!hasColumn("trpg_scenarios", "published")) {
            Db.exec("ALTER TABLE trpg_scenarios ADD COLUMN published TINYINT NOT NULL DEFAULT 0");
        }
    }

    /** 列存在守护（information_schema）：存在返回 true */
    private static boolean hasColumn(String table, String column) {
        return Db.queryOne("SELECT 1 AS x FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", table, column) != null;
    }

    /** 索引存在守护（information_schema）：存在返回 true */
    private static boolean hasIndex(String table, String index) {
        return Db.queryOne("SELECT 1 AS x FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? AND index_name=?", table, index) != null;
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

    /** 首次建表后种子示例工具（仅表为空时写入，幂等） */
    private static void seedToolsIfEmpty() {
        Map<String, Object> row = Db.queryOne("SELECT COUNT(*) AS n FROM tools");
        long n = row == null ? 0 : ((Number) row.get("n")).longValue();
        if (n > 0) return;
        Db.exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "语音合成 TTS", "🔊", "语音", "api",
                "微软 edge-tts 神经音色朗读，NoteLab 英语页 /api/tts 已实际接入；可扩更多音色与语种",
                "/api/tts", "{\"provider\":\"edge-tts\",\"voice\":\"en-US-AriaNeural\"}", 1);
        Db.exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "图像生成", "🎨", "生成", "api",
                "文本生成图像（DashScope 文生图服务示例条目，接入需在配置中补充 API 密钥引用）",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
                "{\"model\":\"wanx2.1-t2i-turbo\",\"size\":\"1024*1024\"}", 0);
        Db.exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "联网搜索", "🌐", "搜索", "api",
                "为对话提供实时联网检索能力（示例条目，可对接博查/Bing 等搜索 API）",
                "", "{\"provider\":\"bocha\",\"top_k\":5}", 0);
        Db.exec("INSERT INTO tools (name,icon,category,type,description,endpoint,config,enabled) VALUES (?,?,?,?,?,?,?,?)",
                "MCP 文件服务", "🔌", "MCP", "mcp",
                "Model Context Protocol 工具示例：本地文件系统读写（stdio 方式），供支持 MCP 的客户端挂载",
                "stdio://filesystem", "{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/root/notelab-java/data\"]}", 0);
    }
}
