package com.notelab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 启动初始化：连库建表 → RBAC 路由自动注册（含未来新增路由）→ RAG 目录。 */
@Component
public class Bootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Override
    public void run(String... args) {
        Db.init();
        PermService.registerAllRoutes(handlerMapping);
        RagService.init();
        log.info("NoteLab-Java 启动完成：port=8001, mysql={}:{}/{}, user={}, base_url={}, model={}, key={}, secret_key={}, data_dir={}, perm_routes={}",
                AppConfig.mysqlHost(), AppConfig.mysqlPort(), AppConfig.mysqlDb(), AppConfig.mysqlUser(),
                AppConfig.qwenBaseUrl(), AppConfig.qwenModel(),
                AppConfig.qwenKey().isEmpty() ? "(未配置)" : "(已配置)",
                AppConfig.secretKey().equals("dev-secret-change-me") ? "(默认值)" : "(已配置)",
                AppConfig.dataDir(), Db.listRoutes().size());
    }
}
