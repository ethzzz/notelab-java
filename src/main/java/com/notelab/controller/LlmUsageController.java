package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.service.LlmUsageService;
import com.notelab.service.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 大模型用量与限额（B 端「系统管理 → 模型用量」）。
 *
 * <p>整个 {@code /api/admin} 前缀在 {@code PermGuard.RESTRICTED_PREFIXES} 里 → **仅超管**；
 * 这里再显式校验一次 super_admin，与其它运维接口保持一致的双保险（见 notelab-java/AGENTS.md）。
 *
 * <p>接口只有两个：读汇总、整份写限额。**没有"删除记录"** —— 账本是事实，不该被抹掉；
 * 要重新开始算账，宁可加时间范围参数。
 */
@RestController
@RequestMapping("/api/admin/llm-usage")
public class LlmUsageController {

    /** 汇总：今天 / 限额 / 剩余 / 最近 N 天 / 按模型 / 按 key / provider 余额 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(value = "days", required = false, defaultValue = "14") int days,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> deny = guard(request);
        if (deny != null) return deny;
        return ResponseEntity.ok(LlmUsageService.summary(days));
    }

    /**
     * 写限额：{@code {enabled, dailyTokens, dailyRequests}}，**整份替换**。
     * 值为 0 表示该维度不限制（避免"误设 0 = 全禁"这种惊吓）。
     */
    @PutMapping("/limits")
    public ResponseEntity<Map<String, Object>> saveLimits(@RequestBody JsonNode body,
                                                          HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> deny = guard(request);
        if (deny != null) return deny;
        Boolean enabled = body.has("enabled") ? body.get("enabled").asBoolean() : null;
        Long tokens = longOrNull(body.get("dailyTokens"));
        Long requests = longOrNull(body.get("dailyRequests"));
        return ResponseEntity.ok(Map.of("ok", true,
                "limits", LlmUsageService.saveLimits(enabled, tokens, requests)));
    }

    /** 未登录 → 401；非超管 → 403；通过 → null */
    private static ResponseEntity<Map<String, Object>> guard(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) {
            return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));
        }
        return null;
    }

    /** 前端可能传数字也可能传字符串（表单），两种都收 */
    private static Long longOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.asLong();
        try {
            String s = n.asText("").trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }
}
