package com.notelab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** 启动初始化：建库建表（对应 Python 的 db.init_db / init_english_db / migrate_schema）。 */
@Component
public class Bootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    @Override
    public void run(String... args) {
        Db.init();
        RagService.init();
        log.info("NoteLab-Java 启动完成：port=8001, base_url={}, model={}, key={}, secret_key={}, data_dir={}",
                AppConfig.qwenBaseUrl(), AppConfig.qwenModel(),
                AppConfig.qwenKey().isEmpty() ? "(未配置)" : "(已配置)",
                AppConfig.secretKey().equals("dev-secret-change-me") ? "(默认值)" : "(已配置)",
                AppConfig.dataDir());
    }
}
