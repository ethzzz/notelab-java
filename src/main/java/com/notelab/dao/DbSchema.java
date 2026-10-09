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
        analyticsSchema();
        loginAuditSchema();
    }

    /**
     * 全站数据闭环（PRD-P0）单表：analytics_events。
     *
     * <p>只增不改：CREATE TABLE IF NOT EXISTS，重启幂等。
     *
     * <p>⚠️ **冗余 day 列不是偷懒，是索引必需品**：所有看板查询都按天聚合，写了冗余列才能
     * {@code WHERE day=? } 命中 {@code idx_day_event}；若图省事写 {@code DATE(ts)=?}，函数套在列上
     * 会让 ts 索引直接失效（4GB 机器上就是全表扫）。
     *
     * <p>⚠️ {@code session_id}/{@code anon_id} 用 CHAR(16) 且 NOT NULL：客户端事件一定带；
     * 服务端旁路事件（登录/发布等）没有会话上下文时写空串 ""，**不是 NULL** ——
     * 这样 DAU 的 {@code COALESCE(user_id, anon_id)} 不会把服务端事件误当游客，
     * 且这些事件本来就带 user_id。
     */
    private static void analyticsSchema() {
        Db.exec("""
            CREATE TABLE IF NOT EXISTS analytics_events (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                ts          DATETIME(3)  NOT NULL,
                day         DATE         NOT NULL,
                app         VARCHAR(8)   NOT NULL,
                event       VARCHAR(64)  NOT NULL,
                session_id  CHAR(16)     NOT NULL,
                anon_id     CHAR(16)     NOT NULL,
                user_id     BIGINT       NULL,
                path        VARCHAR(255) NULL,
                props       VARCHAR(2000) NULL,
                ip_hash     CHAR(16)     NULL,
                ua          VARCHAR(255) NULL,
                KEY idx_day_event (day, event),
                KEY idx_anon_day (anon_id, day),
                KEY idx_user (user_id, day),
                KEY idx_session (session_id, ts)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    /**
     * 登录审计单表：login_audit。
     *
     * <p>与 {@code analytics_events}（见 {@link #analyticsSchema()}）**刻意不复用同一张表**，
     * 尽管两者都有登录记录 —— 它们服务的目标恰好相反：
     * <ul>
     *   <li>{@code analytics_events} 是**业务指标**：只记成功登录（DAU/留存用），IP 只存**加盐哈希**
     *       （隐私优先、故意不可逆），明细保留 90 天；</li>
     *   <li>{@code login_audit} 是**安全取证**：**全部结果都记**（失败尝试才是攻击信号），IP 存**明文**
     *       （取证要对得上人），保留 365 天。</li>
     * </ul>
     * 把这两套口径塞进一张表，必然要牺牲其中一边。宁可两张表，各自把一边做对。
     *
     * <p>⚠️ {@code day} 是**索引必需**的冗余列：页面按天筛，写 {@code WHERE day=?} 才命中
     * {@code idx_day_result}；若图省事写 {@code DATE(ts)=?}，函数套在列上会让 ts 索引失效。
     *
     * <p>⚠️ {@code username} 存的是**用户输入的原样**，不是查到的账号 —— 攻击者尝试过的用户名列表
     * 本身就是情报（能区分「定向打某个账号」与「广撒网撞库」）。所以账号不存在时也要落这一行。
     *
     * <p>⚠️ 本表**不存密码、不存 token**。任何情况下都不要加。
     */
    private static void loginAuditSchema() {
        Db.exec("""
            CREATE TABLE IF NOT EXISTS login_audit (
                id        BIGINT AUTO_INCREMENT PRIMARY KEY,
                ts        DATETIME(3)   NOT NULL,
                day       DATE          NOT NULL,
                app       VARCHAR(4)    NOT NULL,
                username  VARCHAR(64)   NOT NULL,
                user_id   BIGINT        NULL,
                result    VARCHAR(24)   NOT NULL,
                ip        VARCHAR(45)   NOT NULL,
                ua        VARCHAR(255)  NULL,
                KEY idx_day_result (day, result),
                KEY idx_ip_ts (ip, ts),
                KEY idx_username_ts (username, ts)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
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
                super_only TINYINT NOT NULL DEFAULT 0,
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
        // external = 外部账号（给不注册账号的外部人用）：账号与密码一律由超管建/重置，默认只给「仪表盘」。
        // 语义细节见 PermService.ROLE_EXTERNAL。
        Db.exec("INSERT IGNORE INTO perm_roles (code,name) VALUES ('super_admin','超级管理员'),('user','普通用户'),('external','外部账号')");
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
        // C 端用户组的路由持有（与 B 端 perm_role_routes 同构，但**独立一张表**）。
        // 为什么不分端共用 perm_role_routes：B 端角色（perm_roles）与 C 端用户组（c_user_groups）
        // 是两套编码空间，共用一张持有表后「给 B 端角色勾了 C 端接口」这种错配在数据库层面无法区分，
        // 只能靠应用逻辑拦 —— 与其靠约定，不如让错配根本写不进去。
        Db.exec("""
            CREATE TABLE IF NOT EXISTS c_group_routes (
                group_code VARCHAR(50) NOT NULL,
                route_code VARCHAR(120) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (group_code, route_code)
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
        // ---- B 端协作画布（只增不改）：只存元数据，画布内容由协作服务(notelab-b/collab)的 SQLite 持有。
        //      room_id 为 16 位 hex 随机串，前端用它拼协作 ws 地址与页面参数；不复用、不顺序分配。
        //      engine 决定这个画布由哪套引擎渲染（tldraw | excalidraw）：两套实现的文档格式不通用，
        //      所以引擎是**画布级**属性、建好即固定（要换引擎就新建一个画布）。
        Db.exec("""
            CREATE TABLE IF NOT EXISTS canvas_doc (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                room_id CHAR(16) NOT NULL,
                title VARCHAR(200) NOT NULL,
                engine VARCHAR(16) NOT NULL DEFAULT 'tldraw',
                created_by BIGINT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_canvas_room (room_id),
                KEY idx_canvas_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // engine 是后加的列：老库靠这一句补上（MySQL 没有 ADD COLUMN IF NOT EXISTS，先查再 ALTER，重启幂等）
        if (!hasColumn("canvas_doc", "engine")) {
            Db.exec("ALTER TABLE canvas_doc ADD COLUMN engine VARCHAR(16) NOT NULL DEFAULT 'tldraw'");
        }
        // ---- C 端游戏存档（只增不改）：按 user_id + game_code 唯一，data_json 存各游戏自有结构 ----
        Db.exec("""
            CREATE TABLE IF NOT EXISTS c_game_save (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                game_code VARCHAR(32) NOT NULL,
                data_json MEDIUMTEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_user_game (user_id, game_code),
                KEY idx_game (game_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // ---- B/C 拆分阶段2：TRPG 数据归属补列（只增不改；MySQL 无 ADD COLUMN IF NOT EXISTS，先查 information_schema 再 ALTER，重启幂等） ----
        if (!hasColumn("trpg_playthroughs", "scope")) {
            Db.exec("ALTER TABLE trpg_playthroughs ADD COLUMN scope CHAR(1) NOT NULL DEFAULT 'b'");
        }
        // ---- 权限：受限接口（仅超管）从散落常量变成数据（2026-10-09）----
        // 背景：RESTRICTED_PREFIXES 此前只被 syncUserApiPerms 用来「不自动发码」，
        // PermGuard 不校验它、setRoleRoutes 也不拦它 —— 于是超管在「分配路由」里给自建角色
        // 勾上 api:/api/c-admin/users，那个角色真的能读写 C 端用户管理（那几个 Controller 自带零校验）。
        // 落成列之后：启动回填 → 树里显示锁标记且禁勾 → setRoleRoutes 拒绝授予 → denyReason 兜底拦截。
        if (!hasColumn("perm_routes", "super_only")) {
            Db.exec("ALTER TABLE perm_routes ADD COLUMN super_only TINYINT NOT NULL DEFAULT 0");
        }
        // ---- B/C 权限分流（2026-10-09）：路由归属哪一端 ----
        // 背景：perm_routes 是 B/C 混装的（启动时把所有 SpringMVC 路由都 upsert 进来，
        // 包括 /api/c/auth、/api/c/game 这些 C 端接口），于是 B 端「角色组管理 → 分配路由」里
        // 也能勾到 C 端接口 —— 但 C 端是另一套身份体系（c_users），勾了也约束不到任何人，
        // 只是把分配树弄脏、让管理员分不清哪些是自己该管的。
        // side='b'（默认，存量行全是 B 端）/ 'c'（/api/c/** 与 C 端页面，见 model/CRoutes）。
        // ⚠️ 存量 /api/c/** 行的 side 会在下次启动时被 upsert 纠正为 'c'，不需要手工刷数据。
        if (!hasColumn("perm_routes", "side")) {
            Db.exec("ALTER TABLE perm_routes ADD COLUMN side CHAR(1) NOT NULL DEFAULT 'b'");
        }
        if (!hasIndex("trpg_playthroughs", "idx_trpg_p_scope_user")) {
            Db.exec("ALTER TABLE trpg_playthroughs ADD KEY idx_trpg_p_scope_user (scope, user_id)");
        }
        if (!hasColumn("trpg_scenarios", "published")) {
            Db.exec("ALTER TABLE trpg_scenarios ADD COLUMN published TINYINT NOT NULL DEFAULT 0");
        }
        // ---- 架构守护台 ArchGuard：跨仓依赖扫描快照（只增不改，供 M3 可视化页面画趋势） ----
        Db.exec("""
            CREATE TABLE IF NOT EXISTS arch_scan_runs (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                node_count INT NOT NULL DEFAULT 0,
                edge_count INT NOT NULL DEFAULT 0,
                cycle_count INT NOT NULL DEFAULT 0,
                violation_count INT NOT NULL DEFAULT 0,
                error_count INT NOT NULL DEFAULT 0,
                warn_count INT NOT NULL DEFAULT 0,
                build_ms INT NOT NULL DEFAULT 0,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                KEY idx_arch_run_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS arch_scan_cycles (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id BIGINT NOT NULL,
                member_seq INT NOT NULL,
                member VARCHAR(255) NOT NULL,
                KEY idx_arch_cycle_run (run_id, member)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        Db.exec("""
            CREATE TABLE IF NOT EXISTS arch_scan_violations (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id BIGINT NOT NULL,
                rule_id VARCHAR(64) NOT NULL,
                level VARCHAR(16) NOT NULL,
                from_module VARCHAR(255) NOT NULL,
                to_module VARCHAR(255) NOT NULL,
                KEY idx_arch_vio_run (run_id),
                KEY idx_arch_vio_rule (rule_id, level)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
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
