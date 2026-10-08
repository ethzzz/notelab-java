package com.notelab.scheduler;

import com.notelab.dao.LoginAuditDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 登录审计明细清理（{@code login_audit}）。
 *
 * <p>为什么保留期比埋点长得多（365 天 vs {@code AnalyticsScheduler} 的 90 天）：
 * 埋点是**指标**，看趋势用，越老的明细越没查询价值；审计是**取证**，要能回答
 * 「去年这时候有没有人在试我的后台」「这个 IP 是不是长期在扫」—— 90 天会把跨年度的
 * 慢速扫描切掉。而流量小得多（只有登录，日活 100 也就几百行/天），留一年也不占地方。
 *
 * <p>三条约束（与 {@code AnalyticsScheduler} 同构）：
 * <ol>
 *   <li>cron 定在 <b>4:45</b>，紧跟在 4:30 的埋点清理之后 —— 两个清理任务串行，不抢同一时段 I/O；</li>
 *   <li>只删自己的表，别顺手删别的域；</li>
 *   <li>必须打印删除行数 —— 静默删数据是最难排查的那类事故。</li>
 * </ol>
 *
 * <p>⚠️ LLM 依赖：无。
 */
@Component
public class LoginAuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditScheduler.class);

    /** 保留期（天）。改这个值只影响将来的清理，已删的不可能找回 —— 调小前想清楚。 */
    private static final int KEEP_DAYS = 365;

    /** 每天 4:45（Spring cron 六段：秒 分 时 日 月 周），在 4:30 的埋点清理之后 */
    @Scheduled(cron = "0 45 4 * * ?")
    public void cleanOld() {
        try {
            String cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString();
            int deleted = LoginAuditDao.cleanBefore(cutoff);
            log.info("登录审计清理：删除 {} 之前的记录共 {} 行（保留期 {} 天）", cutoff, deleted, KEEP_DAYS);
        } catch (Exception e) {
            // 清理失败不影响任何业务，但必须留痕
            log.warn("登录审计清理任务失败（下次仍会重试）：{}", e.toString());
        }
    }
}
