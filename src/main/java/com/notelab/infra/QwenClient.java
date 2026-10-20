package com.notelab.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.service.LlmGateway;

import java.util.List;
import java.util.Map;

/**
 * 旧的大模型门面 —— 现在只是一个**薄壳**：把调用转发给 {@link LlmGateway}，一个 key 都不再碰。
 *
 * <p>为什么先留壳：11 个调用点里 5 处用 {@code catch (QwenClient.ModelHttpException e)} 做错误文案，
 * 一次性改签名会让它们静默改变行为。所以分两步：
 * <ol>
 *   <li><b>本提交</b>：壳转发到 gateway（平台解析 / allowlist / 限额 / 记账 / 轮换全部走新链路），
 *       入参里的 {@code key} 已经**被忽略**；</li>
 *   <li>下一步：把调用点改成直接调 gateway、删掉 {@code String key = AppConfig.llmKey()} 与这些 catch，
 *       然后删掉本类。</li>
 * </ol>
 *
 * <p>{@link ModelHttpException} 现在继承 {@link LlmException}：5 处旧的 catch 继续有效，
 * 而错误类型已经统一成新的（{@code messageFull()/messageShort()} 文案逐字不变）。
 *
 * @deprecated 用 {@link LlmGateway#chat(String, List)} / {@link LlmGateway#stream(String, List, boolean, LlmTransport.Handler)}
 */
@Deprecated
public final class QwenClient {

    private QwenClient() {}

    /** @deprecated 用 {@link LlmException} */
    @Deprecated
    public static final class ModelHttpException extends LlmException {
        public ModelHttpException(int status, String body) {
            super(status, body);
        }
    }

    /** @deprecated 用 {@link LlmGateway#chatModel(String, List, int)} */
    @Deprecated
    public static String complete(String model, List<Map<String, String>> messages, String key, int timeoutSec)
            throws Exception {
        try {
            return LlmGateway.chatModel(model, messages, timeoutSec);
        } catch (LlmException e) {
            throw new ModelHttpException(e.status, e.body);
        } catch (LlmGateway.ConfigException e) {
            throw new ModelHttpException(400, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /** @deprecated 用 {@link LlmTransport.Handler} */
    @Deprecated
    public interface StreamHandler {
        void onDelta(String delta);
    }

    /** @deprecated 用 {@link LlmGateway#streamModel(String, List, boolean, LlmTransport.Handler)} */
    @Deprecated
    public static String streamChat(String model, List<Map<String, String>> messages, String key,
                                    int timeoutSec, StreamHandler handler) throws Exception {
        return streamChat(model, messages, key, timeoutSec, false, handler);
    }

    /** @deprecated 同上（disableThinking 由 transport 按协议自行表达） */
    @Deprecated
    public static String streamChat(String model, List<Map<String, String>> messages, String key,
                                    int timeoutSec, boolean disableThinking, StreamHandler handler) throws Exception {
        try {
            return LlmGateway.streamModel(model, messages, disableThinking, handler::onDelta);
        } catch (LlmException e) {
            throw new ModelHttpException(e.status, e.body);
        } catch (LlmGateway.ConfigException e) {
            throw new ModelHttpException(400, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /**
     * 拉某个平台的真实模型清单（诊断用：后台"从平台拉取候选"按钮）。
     *
     * @deprecated 诊断属于 provider 层：用 {@link OpenAiCompatTransport#fetchModels(String)}
     */
    @Deprecated
    public static JsonNode fetchModels(String provider) throws Exception {
        return OpenAiCompatTransport.INSTANCE.fetchModels(provider);
    }
}
