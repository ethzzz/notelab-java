package com.notelab.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring Scheduling（此前全仓未启用）：
 * 供 scheduler/TranslateScheduler 的每日 0 点激活任务使用。
 * pm2 以 fork 单实例运行本服务，@Scheduled 不会多实例重复触发。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
