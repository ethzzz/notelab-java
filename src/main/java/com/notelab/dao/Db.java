package com.notelab.dao;

import javax.sql.DataSource;

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
 * 连接池：Spring 托管的唯一 HikariCP Bean（common/DataSourceConfig，poolName=notelab-mysql，
 * max=10 / minIdle=1 / connectionTimeout=5000），本类经 init(DataSource) 持有，不再自建池。
 * 配置来自 MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
 * （进程环境变量 → ./.env → /root/Notelab/notelab-java/.env，见 AppConfig）。
 *
 * ⚠️ 本类**不是**「仅供建表」—— 除建表（DbSchema，42 处调用）外，以下两个业务域
 * 仍走裸 JDBC，向 MyBatis-Plus 的迁移没有做完：
 *   - dao/AnalyticsDao   18 处（analytics_events 写入与按天聚合）
 *   - dao/ArchScanDao     9 处（arch_scan_* 快照落库）
 * 其余业务域已迁到 MyBatis-Plus（mapper/ + 各静态 DAO 门面）。
 * 删改本类 conn/exec/execCount/queryOne/queryAll 之前，先按上面清单核对调用点 ——
 * 2026-10-08 的审查就是为了防止「照注释删方法」导致的编译失败。
 */
public final class Db {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile DataSource ds;

    private Db() {}

    /** 持有 Spring 托管的唯一连接池并执行建表（由 Bootstrap 在启动时调用） */
    public static synchronized void init(DataSource dataSource) {
        if (ds != null) return;
        ds = dataSource;
        try {
            DbSchema.initSchema();
        } catch (RuntimeException e) {
            throw new IllegalStateException("MySQL 初始化失败: " + e.getMessage(), e);
        }
    }

    /** 模拟 Python pymysql.err.IntegrityError（唯一键冲突） */
    public static final class UniqueViolation extends RuntimeException {
        public UniqueViolation(SQLException cause) { super(cause); }
        /** MyBatis-Plus 路径：Spring 把 SQLState 23000 翻译成 DuplicateKeyException 等 RuntimeException */
        public UniqueViolation(RuntimeException cause) { super(cause); }
    }

    // ---------- 内部工具（dao 包内共享） ----------
    static Connection conn() throws SQLException {
        DataSource d = ds;
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

    /** 同 exec，但返回受影响行数（埋点身份回填/清理定时需要打印 deleted 行数，别静默）。 */
    static int execCount(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
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
