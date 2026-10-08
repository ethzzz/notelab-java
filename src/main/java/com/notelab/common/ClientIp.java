package com.notelab.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析（**全仓唯一实现**）。
 *
 * <p>⚠️ 单开一个类只为拦住一件事：这段逻辑极易写错，而写错的代价是**登录限流被绕过**。
 * 仓里此前有**两份**实现（{@code AuthUtil.clientIp} 与 {@code EventRecorder.clientIp}），
 * 两份都写着「取 {@code X-Forwarded-For} 的**第一项**」—— 那是绝大多数示例代码的写法，也是错的。
 *
 * <h3>为什么不能取第一项</h3>
 * nginx 侧写的是 {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;}
 * —— {@code $proxy_add_x_forwarded_for} 是**追加**语义：客户端**自带**的那个头会被原样保留在**最前**，
 * nginx 再把自己看到的 {@code $remote_addr} 接在后面。于是请求头实际是：
 * <pre>X-Forwarded-For: &lt;客户端自己填的，内容任意&gt;, &lt;真实对端 IP&gt;</pre>
 * 「取第一项」= 取到攻击者想要什么就是什么的值。
 *
 * <p><b>实测（2026-10-08，公网 /api/c/auth/login，用户名不存在）</b>：
 * 同一个伪造 XFF 连打 12 次 → {@code 401×10, 429, 429}（限流正常生效）；
 * 每次换一个伪造 XFF 打 12 次 → {@code 401 × 12}，**零 429**（限流被完全绕过，等于无限次猜密码）。
 *
 * <h3>可信度顺序（顺序不可调换）</h3>
 * <ol>
 *   <li>{@code X-Real-IP} —— nginx 用 {@code proxy_set_header}（**覆盖**语义）写入 {@code $remote_addr}，
 *       客户端自带的同名头会被顶掉。**唯一可信且客户端无法伪造的一项。**</li>
 *   <li>{@code X-Forwarded-For} 的**最后一项** —— 即 nginx 追加的真实对端地址。
 *       <br>⚠️ 将来若在前面加 CDN，这一项会变成 CDN 的 IP，届时要改成「信任固定跳数、从右往左数」。</li>
 *   <li>{@code getRemoteAddr()} —— 未经代理直连时的对端地址。</li>
 * </ol>
 *
 * <p>⚠️ 前提是请求**确实经过 nginx**（线上 :80 / :443 都是）。直接打 :8001 时这三项全都由调用方控制，
 * 所以后端端口**不能**对公网暴露（当前只监听本机 / 内网）。
 */
public final class ClientIp {

    /** 长度上限：IPv6 含 IPv4 映射最长 45（{@code xxxx:...:xxxx}），超出的一律截断，防脏数据灌进列/限流键 */
    private static final int MAX_LEN = 45;

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        if (request == null) return "unknown";

        // ① 唯一可信源：nginx 覆盖写入，客户端伪造无效
        String real = request.getHeader("x-real-ip");
        if (real != null && !real.isBlank()) return clip(real.trim());

        // ② nginx 追加的真实对端在**最后**（绝不能取第一项）
        String fwd = request.getHeader("x-forwarded-for");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) return clip(last);
        }

        // ③ 无代理直连
        String addr = request.getRemoteAddr();
        return addr != null && !addr.isEmpty() ? clip(addr) : "unknown";
    }

    private static String clip(String s) {
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }
}
