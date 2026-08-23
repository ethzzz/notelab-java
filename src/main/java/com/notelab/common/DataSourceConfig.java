package com.notelab.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 唯一连接池：HikariCP（poolName=notelab-mysql），Spring 托管 Bean。
 * jdbcUrl 参数串与池参数（max=10 / minIdle=1 / connectionTimeout=5000）与原 Db.init() 手工池完全一致；
 * 配置来源不变：MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB（见 AppConfig 三级回退）。
 * MyBatis-Plus、事务管理器与 DbSchema（经 Db）全部复用此池——与 Python 侧共享 MySQL，禁止出现第二个池。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("notelab-mysql");
        cfg.setJdbcUrl("jdbc:mysql://" + AppConfig.mysqlHost() + ":" + AppConfig.mysqlPort() + "/"
                + AppConfig.mysqlDb() + "?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true");
        cfg.setUsername(AppConfig.mysqlUser());
        cfg.setPassword(AppConfig.mysqlPassword());
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5000);
        return new HikariDataSource(cfg);
    }
}
