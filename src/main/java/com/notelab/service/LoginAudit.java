package com.notelab.service;

import com.notelab.common.ClientIp;
import com.notelab.dao.LoginAuditDao;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * 登录审计：把**每一次登录尝试**落库（成功与失败都记）。
 *
 * <h3>为什么不用现成的 analytics_events</h3>
 * C 端登录成功时本来就会写 {@code analytics_events}（{@code EventRecorder.recordLoginAndBackfill}），
 * 但那张表**做不了审计**：
 * <ul>
 *   <li>只记成功 —— 失败尝试才是攻击信号，完全不在表里；</li>
 *   <li>IP 只存**加盐哈希**（为隐私设计，故意不可逆）—— 取证时对不上人；</li>
 *   <li>明细 90 天清理（{@code AnalyticsScheduler}）—— 安全事件需要更长的可回溯期；</li>
 *   <li>字段与口径都服务于看板指标，不是为取证设计。</li>
 * </ul>
 * 所以单开 {@code login_audit}（见 {@code DbSchema#loginAuditSchema}），口径为取证而设。
 *
 * <p>⚠️ 它防的是**密码猜测 / 撞库**。**防不住伪造会话 token** 那一类攻击 —— 那种攻击根本不
 * 经过登录接口，本表里不会有任何记录（2026-10-08 修的 SECRET_KEY 默认值漏洞就是那类）。
 * 会话/敏感操作要可追溯，得另建一张「操作审计表」。
 *
 * <p>⚠️ LLM 依赖：无。
 */
public final class LoginAudit {

    private static final Logger log = LoggerFactory.getLogger(LoginAudit.class);

    /**
     * 结果码（落库值）。**只增不改** —— 历史行靠它检索，改字面量等于让旧数据失联。
     *
     * <p>注意 {@link #NO_SUCH_USER} 与 {@link #BAD_PASSWORD} 在**库里有区分**，但**对客户端
     * 一律回同一句 401「用户名或密码错误」** —— 区分只在日志侧，不会泄漏「这个用户名存不存在」。
     */
    public static final String SUCCESS = "success";
    public static final String BAD_PASSWORD = "bad_password";
    public static final String NO_SUCH_USER = "no_such_user";
    public static final String DISABLED = "disabled";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String BAD_REQUEST = "bad_request";

    private static final int MAX_USERNAME = 64;   // 对齐 login_audit.username
    private static final int MAX_UA = 255;        // 对齐 login_audit.ua

    private LoginAudit() {}

    /**
     * 记一条登录尝试。
     *
     * <p>⚠️ **本方法永不抛异常**。审计是**旁路**：它落库失败绝不能把登录本身带崩
     * （数据库抖动时「登录不了」比「审计缺一条」严重得多）。失败只留 WARN，并且日志里
     * 带 app / result，方便事后判断丢了哪一类。
     *
     * @param app      'b' = admin 后台 / 'c' = C 端
     * @param username 用户**输入的原样**（不是查到的账号）—— 攻击者试过的用户名列表本身即情报
     * @param userId   仅登录成功时有值，其余传 null
     * @param result   {@link #SUCCESS} / {@link #BAD_PASSWORD} / …（见本类常量）
     */
    public static void record(String app, HttpServletRequest request, String username,
                              Long userId, String result) {
        try {
            String ip = ClientIp.of(request);   // 已自带 45 字截断（对齐列宽）
            String ua = clip(request == null ? null : request.getHeader("user-agent"), MAX_UA);
            long now = System.currentTimeMillis();
            // 日界与 TranslateScheduler / AnalyticsScheduler 同口径：服务器时区的 LocalDate.now()
            LoginAuditDao.insert(now, LocalDate.now().toString(), app,
                    clip(username == null ? "" : username.trim(), MAX_USERNAME),
                    userId, result, ip, ua);
        } catch (Exception e) {
            log.warn("登录审计落库失败（已忽略，不影响登录）：app={} result={} err={}", app, result, e.toString());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
