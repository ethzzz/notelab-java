package com.notelab.scheduler;

import com.notelab.dao.AnalyticsDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 埋点明细清理（PRD-P0 §8）。
 *
 * <p>为什么要清：{@code analytics_events} 是**明细表**，日活 100 时年增约 146 万行；
 * 服务器只有 4GB 内存、31GB 磁盘，明细留 90 天足够看趋势，再往前没有查询价值。
 *
 * <p>三条约束：
 * <ol>
 *   <li>cron 定在 <b>4:30</b>，避开 3:00 的 mysqldump（否则清理和备份抢 I/O）；</li>
 *   <li>只删**明细**，将来做了日预聚合表（PRD §9）那张要留 1 年，别一起删；</li>
 *   <li>必须打印删除行数 —— 静默删数据是最难排查的那类事故。</li>
 * </ol>
 *
 * <p>⚠️ LLM 依赖：无。
 */
@Component
public class AnalyticsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsScheduler.class);

    /** 明细保留期（天）。与 PRD §3.2 / A6 的口径一致 */
    private static final int KEEP_DAYS = 90;

    /** 每天 4:30（Spring cron 六段：秒 分 时 日 月 周），避开 3:00 的 DB 备份 */
    @Scheduled(cron = "0 30 4 * * ?")
    public void cleanOld() {
        try {
            String cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString();
            int deleted = AnalyticsDao.cleanBefore(cutoff);
            log.info("埋点清理：删除 {} 之前的明细共 {} 行（保留期 {} 天）", cutoff, deleted, KEEP_DAYS);
        } catch (Exception e) {
            // 清理失败不影响任何业务，但必须留痕
            log.warn("埋点清理任务失败（下次仍会重试）：{}", e.toString());
        }
    }
}
