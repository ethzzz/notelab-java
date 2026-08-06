package com.notelab;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** SSE 帧写出：与 Python 的 sse(obj) = "data: {json}\n\n" 完全一致。 */
public final class SseUtil {

    private SseUtil() {}

    public static String frame(Map<String, Object> obj) {
        return "data: " + JsonUtil.write(obj) + "\n\n";
    }

    public static void prepare(HttpServletResponse resp) {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");
    }

    public static void send(OutputStream out, Map<String, Object> obj) throws IOException {
        out.write(frame(obj).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
