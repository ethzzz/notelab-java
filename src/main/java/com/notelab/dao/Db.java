package com.notelab.dao;

import com.notelab.common.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据层基座：直连 Python 版同一个 MySQL 库（notelab），两服务共享数据。
 * 连接池：HikariCP，最大 10 连接（与 Python 侧 pymysql 短连接并发读写兼容）。
 * 配置来自 MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
 * （进程环境变量 → ./.env → /root/notelab/.env，见 AppConfig）。
 * 建表 DDL 见 DbSchema；各领域 SQL 见 UserDao / ConversationDao / EnglishDao /
 * UiConfigDao / PermDao / ToolDao / TrpgDao（均复用本类的 conn/exec/queryOne/queryAll）。
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
            DbSchema.initSchema();
        } catch (RuntimeException e) {
            throw new IllegalStateException("MySQL 初始化失败: " + e.getMessage(), e);
        }
    }

    /** 模拟 Python pymysql.err.IntegrityError（唯一键冲突） */
    public static final class UniqueViolation extends RuntimeException {
        public UniqueViolation(SQLException cause) { super(cause); }
    }

    // ---------- 内部工具（dao 包内共享） ----------
    static Connection conn() throws SQLException {
        HikariDataSource d = ds;
        if (d == null) throw new SQLException("Db.init() 尚未调用");
        return d.getConnection();
    }

    static void exec(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, Object> queryOne(String sql, Object... args) {
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

    static List<Map<String, Object>> queryAll(String sql, Object... args) {
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
