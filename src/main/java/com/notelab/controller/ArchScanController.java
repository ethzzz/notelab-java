package com.notelab.controller;

import com.notelab.dao.ArchScanDao;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架构守护台 ArchGuard：扫描快照查询接口（B 端超管）。
 * 只读，写入由 ops/archguard 的扫描器直连 MySQL 落库（与 daily-iteration 同套 /root/.my.cnf 凭据）。
 *
 * /api/arch 不在 PermGuard.RESTRICTED_PREFIXES 内，super_admin 直接放行；
 * 其余角色路由由 PermService.registerAllRoutes 在启动时自动登记。
 */
@RestController
@RequestMapping("/api/arch")
public class ArchScanController {

    private static final String[] DELTA_KEYS = {"node_count", "edge_count", "cycle_count",
            "violation_count", "error_count", "warn_count"};

    /** GET /api/arch/summary 最近一次扫描 + 与上一次的环比 delta + 该次的环/违规明细 */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();

        List<Map<String, Object>> two = ArchScanDao.lastTwo();
        Map<String, Object> body = new LinkedHashMap<>();
        if (two.isEmpty()) {
            body.put("has_data", false);
            return ResponseEntity.ok(body);
        }
        Map<String, Object> latest = two.get(two.size() - 1);
        Map<String, Object> prev = two.size() >= 2 ? two.get(0) : null;

        body.put("has_data", true);
        body.put("latest", latest);
        if (prev != null) {
            Map<String, Object> delta = new LinkedHashMap<>();
            for (String k : DELTA_KEYS) delta.put(k, num(latest.get(k)) - num(prev.get(k)));
            body.put("delta", delta);
            body.put("prev", prev);
        }
        long runId = ((Number) latest.get("id")).longValue();
        body.put("cycles", ArchScanDao.cyclesOf(runId));
        body.put("violations", ArchScanDao.violationsOf(runId));
        return ResponseEntity.ok(body);
    }

    /** GET /api/arch/trend?limit=30 趋势序列（时间升序，最近 limit 次扫描） */
    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> trend(@RequestParam(defaultValue = "30") int limit,
                                                     HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();

        int n = Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> runs = ArchScanDao.recentRuns(n);
        Collections.reverse(runs); // 表内 id 倒序 → 页面从左到右的时间升序

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", runs.size());
        body.put("runs", runs);
        return ResponseEntity.ok(body);
    }

    /** GET /api/arch/runs/{id} 单次扫描明细：汇总 + 各环成员 + 规则违规 */
    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> runDetail(@PathVariable("id") long id,
                                                         HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();

        Map<String, Object> run = ArchScanDao.runById(id);
        if (run == null) return ResponseEntity.status(404).body(Map.of("error", "扫描记录不存在"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", run);
        body.put("cycles", ArchScanDao.cyclesOf(id));
        body.put("violations", ArchScanDao.violationsOf(id));
        return ResponseEntity.ok(body);
    }

    private static int num(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }
}
