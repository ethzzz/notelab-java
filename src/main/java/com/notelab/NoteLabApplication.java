package com.notelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * NoteLab · AI 功能试验后台（Java / Spring Boot 重写版）
 * 与 Python FastAPI 版（/root/notelab，:8000）API 契约完全兼容，并行运行于 :8001。
 */
// 排除 DataSource 自动装配：连接池由 Db.java 用 HikariCP 手动管理（直连 Python 版 MySQL）
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class NoteLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(NoteLabApplication.class, args);
    }
}
