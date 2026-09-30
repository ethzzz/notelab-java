package com.notelab.dao;

import com.notelab.common.RowUtil;
import com.notelab.model.entity.ArchScanCycle;
import com.notelab.model.entity.ArchScanRun;
import com.notelab.model.entity.ArchScanViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 架构守护台 ArchGuard DAO：arch_scan_runs / arch_scan_cycles / arch_scan_violations 三张快照表的读写。
 * 静态门面，风格对齐 UiConfigDao / GameSaveDao。
 */
public final class ArchScanDao {

    private ArchScanDao() {}

    private static final String[] RUN_COLS = {"id", "node_count", "edge_count", "cycle_count",
            "violation_count", "error_count", "warn_count", "build_ms", "created_at"};
    private static final String[] CYCLE_COLS = {"id", "run_id", "member_seq", "member"};
    private static final String[] VIO_COLS = {"id", "run_id", "rule_id", "level", "from_module", "to_module"};

    /** 一次完整扫描落库：run 汇总 + 各环成员 + 规则违规，整体一个事务。返回新 run id。 */
    public static long save(ArchScanRun run, List<List<String>> cycles, List<ArchScanViolation> violations) {
        return DaoSupport.tx().execute(status -> {
            Db.exec("INSERT INTO arch_scan_runs (node_count,edge_count,cycle_count,violation_count,error_count,warn_count,build_ms) VALUES (?,?,?,?,?,?,?)",
                    nz(run.getNodeCount()), nz(run.getEdgeCount()), nz(run.getCycleCount()), nz(run.getViolationCount()),
                    nz(run.getErrorCount()), nz(run.getWarnCount()), nz(run.getBuildMs()));
            Map<String, Object> last = Db.queryOne("SELECT LAST_INSERT_ID() AS id");
            long runId = last == null ? 0L : ((Number) last.get("id")).longValue();
            for (List<String> members : cycles) {
                if (members == null) continue;
                for (int seq = 0; seq < members.size(); seq++) {
                    Db.exec("INSERT INTO arch_scan_cycles (run_id,member_seq,member) VALUES (?,?,?)", runId, seq, members.get(seq));
                }
            }
            for (ArchScanViolation v : violations) {
                Db.exec("INSERT INTO arch_scan_violations (run_id,rule_id,level,from_module,to_module) VALUES (?,?,?,?,?)",
                        runId, v.getRuleId(), v.getLevel(), v.getFromModule(), v.getToModule());
            }
            return runId;
        });
    }

    /** 最近若干次扫描（id 倒序，供趋势图直接用） */
    public static List<Map<String, Object>> recentRuns(int limit) {
        List<Map<String, Object>> rows = Db.queryAll("SELECT * FROM arch_scan_runs ORDER BY id DESC LIMIT ?", limit);
        return RowUtil.norms(rows, RUN_COLS);
    }

    /** 单次扫描汇总 */
    public static Map<String, Object> runById(long id) {
        return RowUtil.norm(Db.queryOne("SELECT * FROM arch_scan_runs WHERE id=?", id), RUN_COLS);
    }

    /** 某次扫描命中的各循环依赖环（按环内顺序输出） */
    public static List<Map<String, Object>> cyclesOf(long runId) {
        List<Map<String, Object>> rows = Db.queryAll("SELECT * FROM arch_scan_cycles WHERE run_id=? ORDER BY member_seq", runId);
        return RowUtil.norms(rows, CYCLE_COLS);
    }

    /** 某次扫描命中的规则违规 */
    public static List<Map<String, Object>> violationsOf(long runId) {
        List<Map<String, Object>> rows = Db.queryAll("SELECT * FROM arch_scan_violations WHERE run_id=? ORDER BY id", runId);
        return RowUtil.norms(rows, VIO_COLS);
    }

    /** 最近两次扫描（时间升序，用于环比 delta；不足两次时返回已有一次的升序列表） */
    public static List<Map<String, Object>> lastTwo() {
        List<Map<String, Object>> rows = Db.queryAll("SELECT * FROM arch_scan_runs ORDER BY id DESC LIMIT 2");
        List<Map<String, Object>> asc = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) asc.add(RowUtil.norm(rows.get(i), RUN_COLS));
        return asc;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
