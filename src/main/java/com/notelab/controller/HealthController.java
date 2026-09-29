package com.notelab.controller;

import com.notelab.infra.LlmHealth;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health/llm —— 大模型可用性探测。
 *
 * <p>存在的理由：key 失效时用户只会看到「判分失败，请重试」，完全不知道发生了什么。
 * 前端拿这个接口决定要不要显示「AI 批改暂不可用，已切换本地对照」的提示。
 *
 * <p>无需登录（状态本身不敏感），但探测结果走 {@link LlmHealth} 的 5 分钟缓存，
 * 不会被当成打上游的放大通道。{@code ?force=1} 强制重新探测。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping("/llm")
    public ResponseEntity<Map<String, Object>> llm(@RequestParam(required = false) String force) {
        if ("1".equals(force)) LlmHealth.probe();
        Map<String, Object> body = new LinkedHashMap<>(LlmHealth.snapshot());
        body.put("force", "1".equals(force));
        return ResponseEntity.ok(body);
    }
}
