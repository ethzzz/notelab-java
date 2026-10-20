package com.notelab.controller;

import com.notelab.service.LlmGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户可选的模型清单（聊天页与竞技场的下拉）。
 *
 * <p>2026-10-20 起**不再去 provider 拉**：可选清单来自 {@link com.notelab.infra.ModelRegistry} 的 catalog
 * （后台可维护）。原因有两条：
 * <ol>
 *   <li>provider 的 {@code /models} 只说明"这个 key 理论上能访问什么"，不等于"我们要开放什么"；
 *       DeepSeek 那边只列了两个 id，探测结果不一定完整；</li>
 *   <li>原来这里直连 provider 且用的是单值 key —— 曾因此稳定 401（对话却正常），
 *       表现为"模型能用，但下拉永远是空的"。</li>
 * </ol>
 *
 * <p>响应形状保持兼容：{@code models} 仍是厂商模型名的字符串数组；新增 {@code details}
 * 给将来"显示是哪家的模型"用（{@code [{id, provider, label}]}）。
 */
@RestController
@RequestMapping("/api")
public class ModelsController {

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();

        List<String> ids = LlmGateway.models();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("models", ids);
        body.put("details", LlmGateway.modelDetails());
        body.put("default", LlmGateway.defaultModelId());
        if (ids.isEmpty()) {
            body.put("note", "没有可选的模型（请到「系统管理 → 模型与平台」配置 catalog）");
        }
        return ResponseEntity.ok(body);
    }
}
