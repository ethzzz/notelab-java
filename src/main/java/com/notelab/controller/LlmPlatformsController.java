package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.infra.LlmProviders;
import com.notelab.infra.ModelRegistry;
import com.notelab.infra.OpenAiCompatTransport;
import com.notelab.service.LlmGateway;
import com.notelab.service.PermService;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「系统管理 → 模型与平台」：看平台、探测候选模型、改别名与可选清单。
 *
 * <h3>为什么这里没有"写 key"的接口</h3>
 * key 只在服务器环境变量里（chmod 600），后台**只读**：显示条数与掩码尾部。一旦提供
 * "把 key 读出来"或"把 key 写进库"的接口，后台本身就是泄露源 —— 见 ops/LLM-ARCHITECTURE.md §7。
 *
 * <h3>能改什么 / 不能改什么</h3>
 * <ul>
 *   <li><b>能改</b>：别名映射（chat → 哪个平台哪个模型）、默认别名、每个平台的可选模型清单（catalog）。</li>
 *   <li><b>不能改</b>：平台的 baseUrl / protocol / key —— 那些在环境变量里，改它要动服务器配置并重启。
 *       页面把它们**只读展示**，避免"页面上改了半天其实没生效"。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/llm-platforms")
public class LlmPlatformsController {

    /** 当前形态：平台（含掩码）+ 别名 + catalog + 可选清单 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> deny = guard(request);
        if (deny != null) return deny;
        return ResponseEntity.ok(snapshot());
    }

    /**
     * 保存别名与可选清单：{@code {aliases:{chat:{provider,id}}, defaultAlias, catalog:{provider:[ids]}}}。
     *
     * <p>校验放在这里而不是页面：别名指向不存在的平台、id 为空、默认别名不存在 —— 都要当场拒绝，
     * 否则保存完所有人都调不了模型。
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody JsonNode body, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> deny = guard(request);
        if (deny != null) return deny;

        Map<String, Object> aliases = new LinkedHashMap<>();
        JsonNode a = body.get("aliases");
        if (a != null && a.isObject()) {
            var it = a.fields();
            while (it.hasNext()) {
                var e = it.next();
                String provider = e.getValue().path("provider").asText("").trim();
                String id = e.getValue().path("id").asText("").trim();
                if (provider.isEmpty() || id.isEmpty()) {
                    return bad("别名 " + e.getKey() + " 的平台或模型名为空");
                }
                if (LlmProviders.get(provider) == null) {
                    return bad("别名 " + e.getKey() + " 指向未配置的平台：" + provider
                            + "（平台在环境变量 LLM_PROVIDERS / LLM_<平台>_BASE_URL 里配置）");
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("provider", provider);
                t.put("id", id);
                aliases.put(e.getKey(), t);
            }
        }
        if (aliases.isEmpty()) return bad("至少要保留一个别名（例如 chat）");

        String defaultAlias = body.path("defaultAlias").asText("").trim();
        if (defaultAlias.isEmpty() || !aliases.containsKey(defaultAlias)) {
            defaultAlias = String.valueOf(aliases.keySet().iterator().next());
        }

        Map<String, Object> catalog = new LinkedHashMap<>();
        JsonNode c = body.get("catalog");
        if (c != null && c.isObject()) {
            var it = c.fields();
            while (it.hasNext()) {
                var e = it.next();
                List<String> ids = new ArrayList<>();
                if (e.getValue().isArray()) {
                    for (JsonNode n : e.getValue()) {
                        String id = n.asText("").trim();
                        if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                    }
                }
                if (!ids.isEmpty()) catalog.put(e.getKey(), ids);
            }
        }
        if (catalog.isEmpty()) {
            // 没给 catalog 就用别名里出现的目标兜底，避免"保存后一个可选模型都没有"
            for (Object v : aliases.values()) {
                if (v instanceof Map<?, ?> m) {
                    String p = String.valueOf(m.get("provider"));
                    String id = String.valueOf(m.get("id"));
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) catalog.computeIfAbsent(p, k -> new ArrayList<String>());
                    if (!ids.contains(id)) ids.add(id);
                }
            }
        }

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("aliases", aliases);
        cfg.put("defaultAlias", defaultAlias);
        cfg.put("catalog", catalog);
        UiConfigService.update("llm_models", cfg);
        ModelRegistry.invalidate();   // 立刻生效，不用重启
        return ResponseEntity.ok(snapshot());
    }

    /**
     * 探测某平台真实可用的模型（{@code GET /models}）—— 只作**候选参考**：
     * 开放哪些模型由 catalog 决定，不自动采用探测结果（DeepSeek 只列两个 id，不一定完整）。
     */
    @PostMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestBody JsonNode body, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> deny = guard(request);
        if (deny != null) return deny;
        String provider = body.path("provider").asText("").trim();
        LlmProviders.Provider p = LlmProviders.get(provider);
        if (p == null) return bad("平台未配置：" + provider);
        try {
            JsonNode data = OpenAiCompatTransport.INSTANCE.fetchModels(provider);
            List<String> ids = new ArrayList<>();
            JsonNode arr = data.get("data");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    String id = n.path("id").asText("");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            return ResponseEntity.ok(Map.of("ok", true, "provider", provider, "models", ids,
                    "note", "仅候选参考：要开放哪个模型请在下方「可选模型清单」里加"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "provider", provider,
                    "reason", e.getClass().getSimpleName() + "：" + e.getMessage()));
        }
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    private static Map<String, Object> snapshot() {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (LlmProviders.Provider p : LlmProviders.all().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("label", p.label());
            m.put("protocol", p.protocol());
            m.put("baseUrl", p.baseUrl());
            m.put("enabled", p.enabled());
            m.put("keyCount", p.apiKeys().size());
            m.put("maskedKey", p.maskedKey());   // 只有掩码，没有明文
            providers.add(m);
        }
        Map<String, Object> aliases = new LinkedHashMap<>();
        ModelRegistry.aliases().forEach((k, v) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("provider", v.provider());
            t.put("id", v.modelId());
            aliases.put(k, t);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providers);
        out.put("aliases", aliases);
        out.put("defaultAlias", ModelRegistry.defaultAlias());
        out.put("catalog", ModelRegistry.catalog());
        out.put("models", LlmGateway.models());
        out.put("defaultModel", LlmGateway.defaultModelId());
        return out;
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static ResponseEntity<Map<String, Object>> guard(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) {
            return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));
        }
        return null;
    }
}
