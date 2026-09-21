package com.notelab.scheduler;

import com.notelab.dao.TranslateDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每日英语翻译练习 · 0 点激活定时任务。
 * 每天 0 点取 status='queued' 中 created_at 最早的一个组 → status='used' + activated_date=当天。
 * 幂等：当天已有 used 组则跳过（重复触发/手动补跑都不会激活第二个组）；
 * 激活用 CAS（仅当该组仍为 queued 时更新）防并发重复。
 * pm2 fork 单实例，@Scheduled 不会多实例重复触发。
 */
@Component
public class TranslateScheduler {

    private static final Logger log = LoggerFactory.getLogger(TranslateScheduler.class);

    /** 每天 0 点整（Spring cron 六段：秒 分 时 日 月 周） */
    @Scheduled(cron = "0 0 0 * * ?")
    public void dailyActivate() {
        Map<String, Object> r = activateToday(LocalDate.now());
        log.info("每日翻译练习激活任务：{}", r);
    }

    /**
     * 激活「指定日期」的队列首组（B 端手动补跑同一逻辑）。
     * 返回 {activated:bool, group_id, title, date, reason}，供日志与管理接口回显。
     */
    public static Map<String, Object> activateToday(LocalDate date) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        try {
            if (TranslateDao.hasActivatedOn(date)) {
                out.put("activated", false);
                out.put("group_id", null);
                out.put("title", null);
                out.put("reason", "当天已有激活组，跳过");
                return out;
            }
            Map<String, Object> head = TranslateDao.firstQueuedGroup();
            if (head == null) {
                out.put("activated", false);
                out.put("group_id", null);
                out.put("title", null);
                out.put("reason", "队列为空（无 queued 组），跳过");
                return out;
            }
            long gid = ((Number) head.get("id")).longValue();
            String title = String.valueOf(head.get("title"));
            if (TranslateDao.activateGroup(gid, date) != 1) {
                out.put("activated", false);
                out.put("group_id", gid);
                out.put("title", title);
                out.put("reason", "该组状态已变化（非 queued），跳过");
                return out;
            }
            out.put("activated", true);
            out.put("group_id", gid);
            out.put("title", title);
            out.put("reason", "");
            out.put("queued_left", TranslateDao.countQueued());
            return out;
        } catch (Exception e) {
            // 定时任务异常不得影响服务存活：记录并返回失败原因
            log.error("每日翻译练习激活任务异常", e);
            out.put("activated", false);
            out.put("group_id", null);
            out.put("title", null);
            out.put("reason", "激活异常：" + e.getMessage());
            return out;
        }
    }
}
