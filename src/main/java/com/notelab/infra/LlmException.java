package com.notelab.infra;

/**
 * 大模型调用失败（协议无关）。
 *
 * <p>替代旧的 {@code QwenClient.ModelHttpException}：调用方不再关心是哪家平台报的错，
 * 只关心「状态码 + 上游原文」——两个 message 变体保留下来，是因为现有各控制器对
 * 「详细」与「精简」的偏好不同（Arena/English 用精简、Chat/RAG/Extract 用详细），
 * 换掉会让前端看到的消息文案跟着变。
 */
public class LlmException extends RuntimeException {

    public final int status;
    public final String body;

    public LlmException(int status, String body) {
        super("模型返回 HTTP " + status);
        this.status = status;
        this.body = body == null ? "" : body;
    }

    /** chat / toolbox / rag / extract 风格：模型返回 HTTP {status}：{body[:300]}（全角冒号，与旧版逐字一致） */
    public String messageFull() {
        return "模型返回 HTTP " + status + "：" + body.substring(0, Math.min(300, body.length()));
    }

    /** arena / english 风格：HTTP {status}: {body[:200]}（半角冒号） */
    public String messageShort() {
        return "HTTP " + status + ": " + body.substring(0, Math.min(200, body.length()));
    }

    /** 用户可见文案：只给状态，不甩整段上游 JSON */
    @Override
    public String toString() {
        return "模型调用失败（HTTP " + status + "）";
    }
}
