package com.notelab.controller;

import com.notelab.infra.LlmHealth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GET /api/health/llm —— 大模型可用性探测。
 *
 * <p>存在的理由：key 失效时用户只会看到「判分失败，请重试」，完全不知道发生了什么。
 * 前端拿这个接口决定要不要显示「AI 批改暂不可用，已切换本地对照」的提示。
 *
 * <p><b>匿名可用，但只给结论、不给实现细节</b>（2026-10-21 加固）：
 * 匿名响应只保留 {@code available / mode / checked_at}。原来还透出 {@code key_count}
 * 与 {@code model}（如 {@code deepseek-flash}）—— 等于把「用哪家模型、配了几把 key」
 * 告诉任何人，连改配置这件事都会被外面观察到。登录用户才拿完整快照（含 {@code reason}）。
 *
 * <p>⚠️ <b>{@code ?force=1} 只对登录用户生效</b>：它会绕过 5 分钟缓存直接打上游。
 * 匿名能触发就等于开了一个「无限调用外部大模型 API」的入口 —— 配额与费用都会被烧掉。
 * 匿名传 force 不报错、只是**安静地不生效**（回落读缓存），避免泄露「这个参数存在且有作用」。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /** 匿名可见字段：只够判断「现在能不能用 AI」，不含任何配置信息 */
    private static final Set<String> PUBLIC_FIELDS = Set.of("available", "mode", "checked_at");

    @GetMapping("/llm")
    public ResponseEntity<Map<String, Object>> llm(@RequestParam(required = false) String force,
                                                   HttpServletRequest request) {
        boolean loggedIn = AuthUtil.user(request) != null;
        boolean doProbe = "1".equals(force) && loggedIn;
        if (doProbe) LlmHealth.probe();

        Map<String, Object> body = new LinkedHashMap<>(LlmHealth.snapshot());
        if (!loggedIn) body.keySet().retainAll(PUBLIC_FIELDS);
        body.put("force", doProbe);
        return ResponseEntity.ok(body);
    }
}
