package com.notelab.service;

import com.notelab.infra.LlmException;
import com.notelab.infra.LlmProviders;
import com.notelab.infra.LlmTransport;
import com.notelab.infra.ModelRegistry;
import com.notelab.infra.OpenAiCompatTransport;

import java.util.List;
import java.util.Map;

/**
 * 大模型调用的**唯一门面**。调用方只需要知道"用哪种模型"：
 *
 * <pre>
 * LlmGateway.chat("chat", messages)                    // 代码里的意图（别名）
 * LlmGateway.chatModel("deepseek-v4-pro", messages)    // 用户在后台选的厂商模型名
 * LlmGateway.stream("chat", messages, delta -&gt; ...)    // 流式
 * LlmGateway.models()                                  // 可选清单（厂商模型名）
 * </pre>
 *
 * <p>它负责四件事，调用方一律不关心：
 * ① **别名/模型名解析** → 哪个平台、哪个厂商模型（{@link ModelRegistry}）；
 * ② **allowlist 校验** —— 未登记的模型名在本地就被拒，不再转发给 provider 换一个 400
 *    （2026-10-20 的教训：老会话存着 {@code qwen3.8-max}，换平台后一发送就报错）；
 * ③ **日限额**（{@link LlmUsageService#assertWithinDaily()}）与协议分派；
 * ④ **错误归一**：配置类问题直接给中文原因，上游错误给 {@link LlmException}。
 *
 * <p>⚠️ key 在整个链路里只出现在 {@code KeyRing}/{@code OpenAiCompatTransport} 内部，
 * 门面与调用方都拿不到。
 */
public final class LlmGateway {

    /**
     * 配置类问题（别名不存在、模型未登记、平台没 key、协议没实现）。
     *
     * <p>**继承 {@link LlmException}**（状态码 400）而不是裸 RuntimeException：各控制器都先
     * {@code catch (LlmException)} 给 502 + 可读文案，配置错误若走 RuntimeException 分支，
     * 会被误报成"响应超时（504）"。
     */
    public static final class ConfigException extends LlmException {
        public ConfigException(String msg) {
            super(400, msg);
        }

        @Override
        public String toString() {
            return getMessage() == null ? "" : getMessage();
        }

        /** 配置类错误没有上游响应体，用自身消息当 body，这样 messageFull() 也能给出原因 */
        @Override
        public String messageFull() {
            String m = getMessage() == null ? "" : getMessage();
            return body.isEmpty() ? m : body;
        }
    }

    private LlmGateway() {}

    // ==================================================================================
    // 非流式
    // ==================================================================================

    public static String chat(String alias, List<Map<String, String>> messages) {
        return chat(alias, messages, 120);
    }

    /** 按别名调用（代码里的默认路径） */
    public static String chat(String alias, List<Map<String, String>> messages, int timeoutSec) {
        ModelRegistry.Target t = resolveAliasOrThrow(alias);
        return complete(t, messages, timeoutSec);
    }

    /** 按厂商模型名调用（用户在后台/竞技场选择的路径） */
    public static String chatModel(String modelId, List<Map<String, String>> messages, int timeoutSec) {
        ModelRegistry.Target t = resolveModelOrThrow(modelId);
        return complete(t, messages, timeoutSec);
    }

    // ==================================================================================
    // 流式
    // ==================================================================================

    public static String stream(String alias, List<Map<String, String>> messages, LlmTransport.Handler handler) {
        return stream(alias, messages, 180, false, handler);
    }

    public static String stream(String alias, List<Map<String, String>> messages, boolean disableThinking,
                                LlmTransport.Handler handler) {
        return stream(alias, messages, 180, disableThinking, handler);
    }

    public static String stream(String alias, List<Map<String, String>> messages, int timeoutSec,
                                LlmTransport.Handler handler) {
        return stream(alias, messages, timeoutSec, false, handler);
    }

    public static String stream(String alias, List<Map<String, String>> messages, int timeoutSec,
                                boolean disableThinking, LlmTransport.Handler handler) {
        ModelRegistry.Target t = resolveAliasOrThrow(alias);
        return streamTarget(t, messages, timeoutSec, disableThinking, handler);
    }

    public static String streamModel(String modelId, List<Map<String, String>> messages, LlmTransport.Handler handler) {
        return streamModel(modelId, messages, 180, false, handler);
    }

    public static String streamModel(String modelId, List<Map<String, String>> messages, boolean disableThinking,
                                     LlmTransport.Handler handler) {
        return streamModel(modelId, messages, 180, disableThinking, handler);
    }

    public static String streamModel(String modelId, List<Map<String, String>> messages, int timeoutSec,
                                     LlmTransport.Handler handler) {
        return streamModel(modelId, messages, timeoutSec, false, handler);
    }

    public static String streamModel(String modelId, List<Map<String, String>> messages, int timeoutSec,
                                     boolean disableThinking, LlmTransport.Handler handler) {
        ModelRegistry.Target t = resolveModelOrThrow(modelId);
        return streamTarget(t, messages, timeoutSec, disableThinking, handler);
    }

    // ==================================================================================
    // 可选清单（给 /api/models 与后台下拉）
    // ==================================================================================

    /** 用户可选的厂商模型名（保持注册表顺序） */
    public static List<String> models() {
        return ModelRegistry.catalogIds();
    }

    /** 同上，带 provider/label 细节（前端将来想显示"哪家的模型"时用） */
    public static List<Map<String, Object>> modelDetails() {
        return ModelRegistry.catalogDetails();
    }

    /** 默认模型名（别名 chat 指向的那个），给前端做初始选中值 */
    public static String defaultModelId() {
        ModelRegistry.Target t = ModelRegistry.resolveAlias(ModelRegistry.defaultAlias());
        return t == null ? "" : t.modelId();
    }

    /**
     * 是否"能用"：默认别名能解析到平台、该平台启用且有 key。
     *
     * <p>替代调用点里那些 {@code AppConfig.llmKey().isEmpty()} 判断 —— 调用方只该问"能不能用"，
     * 不该知道 key 存在哪、有几把。
     */
    public static boolean available() {
        ModelRegistry.Target t = ModelRegistry.resolveAlias(ModelRegistry.defaultAlias());
        if (t == null) return false;
        LlmProviders.Provider p = LlmProviders.get(t.provider());
        return p != null && p.enabled() && p.hasKey();
    }

    /** 配置了 key 且启用的平台数量（只给数字，不含 key 内容） */
    public static int providerCount() {
        int n = 0;
        for (LlmProviders.Provider p : LlmProviders.all().values()) {
            if (p.enabled() && p.hasKey()) n++;
        }
        return n;
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    private static ModelRegistry.Target resolveAliasOrThrow(String alias) {
        ModelRegistry.Target t = ModelRegistry.resolveAlias(alias);
        if (t == null) {
            throw new ConfigException("模型别名未配置：" + alias + "（请到「系统管理 → 模型与平台」配置）");
        }
        return t;
    }

    private static ModelRegistry.Target resolveModelOrThrow(String modelId) {
        ModelRegistry.Target t = ModelRegistry.resolveModelId(modelId);
        if (t == null) {
            // 本地拒绝，不转发给 provider：这条就是"老会话存旧模型名 → 400"的根治点
            throw new ConfigException("模型不在可用清单里：" + modelId + "（请在后台的模型清单里添加，或改用可选模型）");
        }
        return t;
    }

    private static String complete(ModelRegistry.Target t, List<Map<String, String>> messages, int timeoutSec) {
        LlmUsageService.assertWithinDaily();
        LlmTransport transport = transportFor(t.provider());
        try {
            return transport.complete(t.provider(), t.modelId(), messages, timeoutSec);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用异常：" + e.getMessage(), e);
        }
    }

    private static String streamTarget(ModelRegistry.Target t, List<Map<String, String>> messages,
                                       int timeoutSec, boolean disableThinking, LlmTransport.Handler handler) {
        LlmUsageService.assertWithinDaily();
        LlmTransport transport = transportFor(t.provider());
        try {
            return transport.stream(t.provider(), t.modelId(), messages, timeoutSec, disableThinking, handler);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用异常：" + e.getMessage(), e);
        }
    }

    /** 协议 → 实现。加 Claude 时只在这里加一个 case（以及对应的 transport 类） */
    private static LlmTransport transportFor(String provider) {
        LlmProviders.Provider p = LlmProviders.get(provider);
        if (p == null) throw new ConfigException("平台未配置：" + provider + "（检查 LLM_PROVIDERS 与 LLM_<平台>_BASE_URL）");
        if (!p.enabled()) throw new ConfigException("平台已禁用：" + provider);
        if (!p.hasKey()) throw new ConfigException("平台 " + provider + " 没有配置 API key");
        String protocol = p.protocol() == null ? "" : p.protocol().trim().toLowerCase();
        return switch (protocol) {
            case "", "openai" -> OpenAiCompatTransport.INSTANCE;
            case "anthropic" -> throw new ConfigException(
                    "平台 " + provider + " 配的是 anthropic 协议，但该协议的实现还没写（见 ops/LLM-ARCHITECTURE.md §6）");
            default -> throw new ConfigException("未知协议：" + p.protocol() + "（平台 " + provider + "）");
        };
    }
}
