package com.notelab.dao;

import java.util.List;
import java.util.Map;

/**
 * 博客部署任务的状态读写（表 {@code blog_deploy_job}，建表处见 {@link DbSchema}）。
 *
 * <p>⚠️ 本类刻意落在 {@code com.notelab.dao}：{@link Db} 的 exec/queryAll 是**包级私有**，
 * 只有同包类能直接发 SQL（与 {@link BlogStatDao} / {@link AnalyticsDao} 同规矩）。
 *
 * <p>状态机就四个值、只往前走一次：{@code queued → running → (success | failed)}。
 * 「只往前走一次」是刻意的 —— 没有重试队列，失败就是失败，重试＝再建一行（历史反而更清楚：
 * 哪次成功、哪次失败、失败在什么输出上，按 id 排就是一串）。
 */
public final class BlogDeployDao {

    private BlogDeployDao() {}

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";

    /** 建一行 queued 任务，返回自增 id */
    public static long create(Long userId, String userName, String kind, String commitHash, String verifyPath) {
        return Db.execInsert("INSERT INTO blog_deploy_job "
                        + "(trigger_by, trigger_by_name, trigger_kind, commit_hash, verify_path, status) "
                        + "VALUES (?,?,?,?,?,?)",
                userId, nz(userName), nz(kind), nz(commitHash), nz(verifyPath), QUEUED);
    }

    public static void markRunning(long id) {
        Db.exec("UPDATE blog_deploy_job SET status=?, started_at=NOW() WHERE id=?", RUNNING, id);
    }

    /** 收尾。{@code probeCode} 可以为 null（脚本就失败时不再探活） */
    public static void finish(long id, String status, Integer exitCode, Integer probeCode,
                             Long durationMs, String logTail) {
        Db.exec("UPDATE blog_deploy_job SET status=?, exit_code=?, probe_code=?, duration_ms=?, "
                        + "log_tail=?, finished_at=NOW() WHERE id=?",
                status, exitCode, probeCode, durationMs, nz(logTail), id);
    }

    public static Map<String, Object> get(long id) {
        return Db.queryOne("SELECT * FROM blog_deploy_job WHERE id=?", id);
    }

    public static Map<String, Object> latest() {
        return Db.queryOne("SELECT * FROM blog_deploy_job ORDER BY id DESC LIMIT 1");
    }

    /** 最近 N 次（状态页的历史列表） */
    public static List<Map<String, Object>> recent(int limit) {
        return Db.queryAll("SELECT * FROM blog_deploy_job ORDER BY id DESC LIMIT ?", limit);
    }

    /** 还在跑或排队中的任务数 —— 用来挡住并发部署（两条构建同时写 /var/www/blog 会互相覆盖） */
    public static int countActive() {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM blog_deploy_job WHERE status IN (?,?)", QUEUED, RUNNING);
        return r == null || !(r.get("n") instanceof Number n) ? 0 : n.intValue();
    }

    /**
     * 启动清残：JVM 重启会让 {@code running} 的行**永远**停在那里（收尾代码随进程一起没了）。
     *
     * <p>照 {@link TrpgDao#abortStaleTrpgGenTasks()} 的先例来（那边也是重启后清 TRPG 生成任务）：
     * 不清的话 {@link #countActive()} 恒 &gt; 0 → 之后**任何**部署都会被「已有部署在跑」挡掉，
     * 而且页面上一行永远转圈的 running 比报错更让人困惑。
     *
     * @return 被标记为中断的行数
     */
    public static int abortStaleJobs() {
        return Db.execCount("UPDATE blog_deploy_job SET status=?, finished_at=NOW(), "
                        + "log_tail=CONCAT(COALESCE(log_tail,''), ?) WHERE status IN (?,?)",
                FAILED, "\n[中断] 服务重启，这次部署的收尾没跑到（构建可能已部分完成，建议重跑一次）",
                QUEUED, RUNNING);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
