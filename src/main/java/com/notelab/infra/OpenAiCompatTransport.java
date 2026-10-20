package com.notelab.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.service.LlmUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的传输实现（DeepSeek / OpenAI / Qwen 都是这一套）。
 *
 * <p>逻辑从旧 {@code QwenClient} 逐字搬过来，改动只有三处：
 * ① 平台不再是全局单值，而是入参 {@code provider}（baseUrl 与 key 都从 {@link LlmProviders} 取）；
 * ② key 轮换从全局 {@code QwenKeys} 换成按平台的 {@link KeyRing}；
 * ③ 错误类型从 {@code QwenClient.ModelHttpException} 换成 {@link LlmException}。
 *
 * <p>⚠️ 这个类**不做限额检查**（那是 {@code LlmGateway} 的职责）：传输层只管"发出去、收回来、记账"。
 * 记账仍在这里，因为只有这里拿得到响应里的 {@code usage}。
 */
public final class OpenAiCompatTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatTransport.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** 单例：无状态（轮换状态在 KeyRing 里），不必每次 new */
    public static final OpenAiCompatTransport INSTANCE = new OpenAiCompatTransport();

    private OpenAiCompatTransport() {}

    // ==================================================================================
    // 非流式
    // ==================================================================================

    @Override
    public String complete(String provider, String model, List<Map<String, String>> messages, int timeoutSec)
            throws Exception {
        List<String> keys = KeyRing.candidates(provider);
        if (keys.isEmpty()) throw new IllegalStateException("平台 " + provider + " 没有配置 API key");

        LlmException last = null;
        for (String key : keys) {
            String url = chatUrl(provider);
            Map<String, Object> payload = Map.of("model", model, "messages", messages, "stream", false);
            HttpResponse<String> resp = HTTP.send(request(url, key, timeoutSec).POST(
                            HttpRequest.BodyPublishers.ofString(JsonUtil.write(payload), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (resp.statusCode() != 200) {
                // 失败的调用也记一行（tokens 为 0）：页面上的"失败数"才有来源
                LlmUsageService.record(model, key, false, resp.statusCode(), 0, 0, 0, false);
                LlmException e = new LlmException(resp.statusCode(), resp.body());
                String reason = KeyRing.unusableReason(resp.statusCode(), resp.body());
                if (reason != null) {
                    KeyRing.markUnusable(provider, key, reason);
                    last = e;
                    continue;
                }
                throw e;
            }

            JsonNode json = JsonUtil.parse(resp.body());
            JsonNode u = json.at("/usage");
            LlmUsageService.record(model, key, true, 200,
                    u.path("prompt_tokens").asInt(0),
                    u.path("completion_tokens").asInt(0),
                    u.path("total_tokens").asInt(0), false);
            KeyRing.markWorking(provider, key);
            return json.at("/choices/0/message/content").asText("");
        }
        if (last != null) {
            log.warn("[{}] 非流式调用失败：{} 把候选 key 全不可用，最后 HTTP {}（model={}）",
                    provider, keys.size(), last.status, model);
            throw last;
        }
        throw new LlmException(429, "no usable api key");
    }

    // ==================================================================================
    // 流式
    // ==================================================================================

    @Override
    public String stream(String provider, String model, List<Map<String, String>> messages,
                         int timeoutSec, boolean disableThinking, Handler handler) throws Exception {
        List<String> keys = KeyRing.candidates(provider);
        if (keys.isEmpty()) throw new IllegalStateException("平台 " + provider + " 没有配置 API key");

        int[] emitted = {0};
        Handler wrapped = d -> {
            emitted[0]++;
            handler.onDelta(d);
        };
        String lastErr = null;
        for (String key : keys) {
            emitted[0] = 0;
            String err = doStream(provider, model, messages, timeoutSec, disableThinking, key, wrapped);
            if (err == null) {
                KeyRing.markWorking(provider, key);
                return null;
            }
            // 已经把内容吐给前端了就不能再换 key 重试（会重复输出），与旧实现一致
            if (emitted[0] == 0) {
                int status = statusOf(err);
                String reason = KeyRing.unusableReason(status, bodyOf(err));
                if (reason != null) {
                    KeyRing.markUnusable(provider, key, reason);
                    lastErr = err;
                    continue;
                }
            }
            return err;
        }
        if (lastErr != null) {
            log.warn("[{}] 流式调用失败：{} 把候选 key 全不可用，最后错误：{}", provider, keys.size(), lastErr);
        }
        return lastErr;
    }

    private String doStream(String provider, String model, List<Map<String, String>> messages, int timeoutSec,
                            boolean disableThinking, String key, Handler handler) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", true);
        // 让 provider 在最后一个 SSE 分片里带上 usage；不带的话流式调用（聊天/TRPG）的用量就完全拿不到
        payload.put("stream_options", Map.of("include_usage", true));
        if (disableThinking) payload.put("enable_thinking", false);

        HttpResponse<java.io.InputStream> resp = HTTP.send(
                request(chatUrl(provider), key, timeoutSec).POST(
                        HttpRequest.BodyPublishers.ofString(JsonUtil.write(payload), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (resp.statusCode() != 200) {
            String body;
            try (var is = resp.body()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                body = "";
            }
            LlmUsageService.record(model, key, false, resp.statusCode(), 0, 0, 0, true);
            return "模型返回 HTTP " + resp.statusCode() + "：" + body.substring(0, Math.min(300, body.length()));
        }

        int[] usage = {0, 0, 0};
        try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                try {
                    JsonNode node = JsonUtil.parse(data);
                    JsonNode delta = node.at("/choices/0/delta/content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String d = delta.asText();
                        if (!d.isEmpty()) handler.onDelta(d);
                    }
                    JsonNode u = node.at("/usage");
                    if (!u.isMissingNode() && !u.isNull()) {
                        usage[0] = u.path("prompt_tokens").asInt(0);
                        usage[1] = u.path("completion_tokens").asInt(0);
                        usage[2] = u.path("total_tokens").asInt(usage[0] + usage[1]);
                    }
                } catch (Exception ignored) {
                    // 与 Python 版一致：解析失败的行直接跳过
                }
            }
        }
        LlmUsageService.record(model, key, true, 200, usage[0], usage[1], usage[2], true);
        return null;
    }

    // ==================================================================================
    // 工具
    // ==================================================================================

    private static HttpRequest.Builder request(String url, String key, int timeoutSec) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json");
    }

    private static String chatUrl(String provider) {
        LlmProviders.Provider p = LlmProviders.get(provider);
        String base = p == null ? "" : p.baseUrl();
        return base.replaceAll("/+$", "") + "/chat/completions";
    }

    /** 从 "模型返回 HTTP {status}：{body}" 里取状态码（全角冒号） */
    private static int statusOf(String err) {
        try {
            int s = err.indexOf("HTTP ") + 5;
            int e = err.indexOf('\uff1a', s);
            if (s < 5 || e < 0) return 0;
            return Integer.parseInt(err.substring(s, e).trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String bodyOf(String err) {
        int i = err.indexOf('\uff1a');
        return i >= 0 ? err.substring(i + 1) : "";
    }
}
