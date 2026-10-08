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
        verifySecurityBaseline();
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

    /**
     * 启动安全基线自检 —— 在一切初始化**之前**跑：配置不安全就别启动。
     *
     * <p>为什么是硬阻断而不是只打日志：2026-10-08 发现线上一直在用源码里公开的默认密钥
     * {@link AppConfig#INSECURE_DEFAULT_SECRET}，而它**只被打印成日志**（`secret_key=(默认值)`），
     * 服务照常启动、接口照常 200 —— 于是这个洞静静躺了很久。实测用默认值签出的 token
     * 能直接拿到 `/api/perm/users` 的 **200**（超管专属接口），即任何人拿到源码就能接管后台。
     *
     * <p>安全底线的失败必须是**可见的**：宁可起不来（pm2 立刻报警并重试），也不要
     * 「看起来一切正常」地裸奔。本地/CI 确需跳过时显式设 {@code ALLOW_INSECURE_SECRET=1}。
     */
    private void verifySecurityBaseline() {
        if (!AppConfig.secretKeyIsInsecure()) {
            if (AppConfig.analyticsSaltIsInsecure()) {
                log.warn("⚠️ ANALYTICS_IP_SALT 仍是默认值 —— IP 哈希可被反推，建议换成随机值");
            }
            return;
        }
        if ("1".equals(AppConfig.get("ALLOW_INSECURE_SECRET", ""))) {
            log.warn("""
                    ⚠️⚠️ 正在使用**源码里公开的**默认会话密钥（ALLOW_INSECURE_SECRET=1 显式放行）。
                    任何拿到源码的人都能伪造任意用户（含 super_admin）的会话 —— 仅限本地/CI，生产禁止。""");
            return;
        }
        throw new IllegalStateException("""
                拒绝启动：SECRET_KEY 未配置，会话密钥仍是源码里公开的默认值。
                用该默认值签出的 token 能直接通过超管接口，必须先配置随机密钥：
                    echo "SECRET_KEY=$(python3 -c 'import secrets;print(secrets.token_hex(32))')" >> /root/Notelab/notelab-java/.env
                    chmod 600 /root/Notelab/notelab-java/.env
                    pm2 restart notelab-java
                （换密钥会使所有 B/C 端会话失效，用户需重新登录。）
                本地/CI 确认无外网暴露时，可显式设置 ALLOW_INSECURE_SECRET=1 跳过本检查。""");
    }
}
