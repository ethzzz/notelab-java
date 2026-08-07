package com.notelab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** 启动初始化：连接 Python 版同一个 MySQL 库并确保建表（对应 Python 的 db.init_db / init_english_db / migrate_schema，仅 CREATE TABLE IF NOT EXISTS）。 */
@Component
public class Bootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    @Override
    public void run(String... args) {
        Db.init();
        RagService.init();
        log.info("NoteLab-Java 启动完成：port=8001, mysql={}:{}/{}, user={}, base_url={}, model={}, key={}, secret_key={}, data_dir={}",
                AppConfig.mysqlHost(), AppConfig.mysqlPort(), AppConfig.mysqlDb(), AppConfig.mysqlUser(),
                AppConfig.qwenBaseUrl(), AppConfig.qwenModel(),
                AppConfig.qwenKey().isEmpty() ? "(未配置)" : "(已配置)",
                AppConfig.secretKey().equals("dev-secret-change-me") ? "(默认值)" : "(已配置)",
                AppConfig.dataDir());
    }
}
