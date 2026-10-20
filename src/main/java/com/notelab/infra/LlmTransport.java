package com.notelab.infra;

import java.util.List;
import java.util.Map;

/**
 * 协议无关的传输层。
 *
 * <p>实现按 {@link LlmProviders.Provider#protocol()} 分派：
 * <ul>
 *   <li>{@code openai} → {@link OpenAiCompatTransport}（DeepSeek / OpenAI / Qwen 都走它）</li>
 *   <li>{@code anthropic} → 将来的 Claude 实现（鉴权头、system 位置、SSE 事件名、usage 字段名都不同，
 *       差异只允许出现在实现里，不能泄漏到 {@link com.notelab.service.LlmGateway} 以上）</li>
 * </ul>
 *
 * <p>入参是**平台名**而不是 baseUrl/key：key 由实现自己从 {@link KeyRing} 取并按需轮换，
 * 调用方（以及 gateway）永远碰不到 key。
 */
public interface LlmTransport {

    /** 流式增量回调（替代旧的 {@code QwenClient.StreamHandler}） */
    @FunctionalInterface
    interface Handler {
        void onDelta(String delta);
    }

    /** 非流式；返回正文。失败抛 {@link LlmException} */
    String complete(String provider, String model, List<Map<String, String>> messages, int timeoutSec)
            throws Exception;

    /**
     * 流式。返回 {@code null} 表示正常读完；返回非 null 是上游错误描述
     * （与旧 {@code streamChat} 的约定一致：调用方把它转成 SSE 的 error 事件）。
     */
    String stream(String provider, String model, List<Map<String, String>> messages,
                  int timeoutSec, boolean disableThinking, Handler handler) throws Exception;
}
