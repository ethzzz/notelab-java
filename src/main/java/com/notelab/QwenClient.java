package com.notelab;

import com.fasterxml.jackson.databind.JsonNode;

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
 * 阿里云 token-plan 网关（OpenAI 兼容协议）调用封装。
 * 超时/错误语义与 Python 版 httpx 调用一致。
 * 多 Key 轮换：某把 key 配额耗尽（429 insufficient_quota）时自动切换下一把（见 QwenKeys）。
 */
public final class QwenClient {

    /** 上游非 200 响应（消息格式与 Python 版一致，分两种风格） */
    public static final class ModelHttpException extends RuntimeException {
        public final int status;
        public final String body;

        public ModelHttpException(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        /** chat/toolbox/rag/extract 风格：模型返回 HTTP {status}：{body[:300]}（全角冒号） */
        public String messageFull() {
            return "模型返回 HTTP " + status + "：" + body.substring(0, Math.min(300, body.length()));
        }

        /** arena / english 风格：HTTP {status}: {body[:200]}（半角冒号） */
        public String messageShort() {
            return "HTTP " + status + ": " + body.substring(0, Math.min(200, body.length()));
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private QwenClient() {}

    private static HttpRequest.Builder base(String url, String key, int timeoutSec) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json");
    }

    private static String chatUrl() {
        return AppConfig.qwenBaseUrl() + "/chat/completions";
    }

    /** GET /models（30s 超时），返回解析后的 JSON；非 2xx 抛异常（与 Python 行为一致：异常 → 降级） */
    public static JsonNode fetchModels(String key) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(AppConfig.qwenBaseUrl() + "/models"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return JsonUtil.parse(resp.body());
    }

    /** 非流式 chat completion，返回 content；非 200 抛 ModelHttpException，超时抛 HttpTimeoutException。
     *  入参 key 仅作首选：配额耗尽时自动轮换到下一把候选 key。 */
    public static String complete(String model, List<Map<String, String>> messages, String key, int timeoutSec)
            throws Exception {
        ModelHttpException last = null;
        for (String k : QwenKeys.candidates()) {
            try {
                String r = doComplete(model, messages, k, timeoutSec);
                QwenKeys.markWorking(k);
                return r;
            } catch (ModelHttpException e) {
                if (QwenKeys.isQuotaExhausted(e.status, e.body)) {
                    QwenKeys.markExhausted(k);
                    last = e;
                    continue;
                }
                throw e;
            }
        }
        throw last != null ? last : new ModelHttpException(429, "no usable qwen api key");
    }

    private static String doComplete(String model, List<Map<String, String>> messages, String key, int timeoutSec)
            throws Exception {
        Map<String, Object> payload = Map.of("model", model, "messages", messages, "stream", false);
        HttpRequest req = base(chatUrl(), key, timeoutSec)
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.write(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new ModelHttpException(resp.statusCode(), resp.body());
        }
        JsonNode json = JsonUtil.parse(resp.body());
        return json.at("/choices/0/message/content").asText("");
    }

    /** 流式结果回调 */
    public interface StreamHandler {
        void onDelta(String delta);
    }

    /**
     * 流式 chat completion。
     * 返回 null 表示正常读完（或正常结束）；返回字符串表示上游非 200 的错误描述（对应 Python 的 error 事件）。
     * 超时抛 java.net.http.HttpTimeoutException，其他异常抛 IOException 等，由调用方映射为 error 事件。
     */
    public static String streamChat(String model, List<Map<String, String>> messages, String key,
                                    int timeoutSec, StreamHandler handler) throws Exception {
        return streamChat(model, messages, key, timeoutSec, false, handler);
    }

    /** 流式 chat completion（disableThinking=true 时关闭 qwen3 思考链，结构化长输出场景显著提速）。
     *  入参 key 仅作首选：仅当尚未向下游吐出任何内容时，配额耗尽才轮换重试（避免重复输出）。 */
    public static String streamChat(String model, List<Map<String, String>> messages, String key,
                                    int timeoutSec, boolean disableThinking, StreamHandler handler) throws Exception {
        int[] emitted = {0};
        StreamHandler wrapped = d -> { emitted[0]++; handler.onDelta(d); };
        String lastErr = null;
        for (String k : QwenKeys.candidates()) {
            emitted[0] = 0;
            String err = doStreamChat(model, messages, k, timeoutSec, disableThinking, wrapped);
            if (err == null) {
                QwenKeys.markWorking(k);
                return null;
            }
            if (emitted[0] == 0) {
                int status = statusOf(err);
                String body = bodyOf(err);
                if (QwenKeys.isQuotaExhausted(status, body)) {
                    QwenKeys.markExhausted(k);
                    lastErr = err;
                    continue;
                }
            }
            return err;
        }
        return lastErr;
    }

    /** 从 doStreamChat 的错误串（"模型返回 HTTP {status}：{body}"，全角冒号）提取状态码 */
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

    /** 从 doStreamChat 的错误串提取响应体部分 */
    private static String bodyOf(String err) {
        int i = err.indexOf('\uff1a');
        return i >= 0 ? err.substring(i + 1) : "";
    }

    private static String doStreamChat(String model, List<Map<String, String>> messages, String key,
                                       int timeoutSec, boolean disableThinking, StreamHandler handler) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", true);
        if (disableThinking) payload.put("enable_thinking", false);
        HttpRequest req = base(chatUrl(), key, timeoutSec)
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.write(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String body;
            try (var is = resp.body()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                body = "";
            }
            return "模型返回 HTTP " + resp.statusCode() + "：" + body.substring(0, Math.min(300, body.length()));
        }
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
                } catch (Exception ignored) {
                    // 与 Python 一致：解析失败的行直接跳过
                }
            }
        }
        return null;
    }
}
