package com.notelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NoteLab · AI 功能试验后台（Java / Spring Boot 重写版）
 * 与 Python FastAPI 版（/root/notelab，:8000）API 契约完全兼容，并行运行于 :8001。
 */
@SpringBootApplication
public class NoteLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(NoteLabApplication.class, args);
    }
}
