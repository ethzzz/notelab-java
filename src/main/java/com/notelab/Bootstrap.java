package com.notelab;

import javax.sql.DataSource;

import com.notelab.common.AppConfig;
import com.notelab.dao.Db;
import com.notelab.dao.PermDao;
import com.notelab.dao.TrpgDao;
import com.notelab.service.PermService;
import com.notelab.service.RagService;
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

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(String... args) {
        Db.init(dataSource);
        // TRPG 生成任务清残：必须在 Db.init 之后执行（原在 TrpgController @PostConstruct 中早于建库，从未生效）
        TrpgDao.abortStaleTrpgGenTasks();
        PermService.registerAllRoutes(handlerMapping);
        RagService.init();
        log.info("NoteLab-Java 启动完成：port=8001, mysql={}:{}/{}, user={}, base_url={}, model={}, key={}, secret_key={}, data_dir={}, perm_routes={}",
                AppConfig.mysqlHost(), AppConfig.mysqlPort(), AppConfig.mysqlDb(), AppConfig.mysqlUser(),
                AppConfig.qwenBaseUrl(), AppConfig.qwenModel(),
                AppConfig.qwenKey().isEmpty() ? "(未配置)" : "(已配置)",
                AppConfig.secretKey().equals("dev-secret-change-me") ? "(默认值)" : "(已配置)",
                AppConfig.dataDir(), PermDao.listRoutes().size());
    }
}
