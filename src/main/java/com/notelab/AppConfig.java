package com.notelab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置加载：与 Python 版 main.py 的行为对齐。
 * 优先级：进程环境变量 > 本目录 .env > /root/notelab/.env（只读参考，保证密钥/会话密钥一致）> 默认值。
 * Python 版 load_dotenv 语义：已存在的环境变量不覆盖。
 */
public final class AppConfig {

    public static final String SESSION_COOKIE = "notelab_session";
    public static final int SESSION_TTL = 30 * 86400;

    private static final Map<String, String> FILE_ENV = new LinkedHashMap<>();
    private static volatile boolean loaded = false;

    private AppConfig() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        // 本目录 .env 优先于 Python 目录 .env
        loadDotEnv(Paths.get(".env").toAbsolutePath());
        loadDotEnv(Paths.get("/root/notelab/.env"));
        loaded = true;
    }

    private static void loadDotEnv(Path path) {
        if (!Files.exists(path)) return;
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int idx = line.indexOf('=');
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                // strip 成对的引号（与 Python 的 .strip('"').strip("'") 近似）
                v = stripQuotes(stripQuotes(v, '"'), '\'');
                if (!k.isEmpty()) FILE_ENV.putIfAbsent(k, v);
            }
        } catch (IOException ignored) {
        }
    }

    private static String stripQuotes(String v, char q) {
        if (v.length() >= 2 && v.charAt(0) == q && v.charAt(v.length() - 1) == q) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 环境变量 > .env 文件 > 默认值 */
    public static String get(String key, String def) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) return v;
        ensureLoaded();
        v = FILE_ENV.get(key);
        if (v != null) return v;
        return def;
    }

    public static String qwenBaseUrl() {
        return get("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    public static String qwenModel() {
        return get("QWEN_MODEL", "qwen-plus");
    }

    public static String qwenKey() {
        return get("QWEN_API_KEY", "").trim();
    }

    public static String secretKey() {
        return get("SECRET_KEY", "dev-secret-change-me");
    }

    public static String redisHost() {
        return get("REDIS_HOST", "127.0.0.1");
    }

    public static int redisPort() {
        try {
            return Integer.parseInt(get("REDIS_PORT", "6379").trim());
        } catch (NumberFormatException e) {
            return 6379;
        }
    }

    // ---------- MySQL（阶段5：直连 Python 版同一个库，默认值与 db.py 一致） ----------
    public static String mysqlHost() {
        return get("MYSQL_HOST", "127.0.0.1");
    }

    public static int mysqlPort() {
        try {
            return Integer.parseInt(get("MYSQL_PORT", "3306").trim());
        } catch (NumberFormatException e) {
            return 3306;
        }
    }

    public static String mysqlUser() {
        return get("MYSQL_USER", "notelab");
    }

    public static String mysqlPassword() {
        return get("MYSQL_PASSWORD", "");
    }

    public static String mysqlDb() {
        return get("MYSQL_DB", "notelab");
    }

    /** 数据目录（RAG 上传文件），默认 ./data */
    public static Path dataDir() {
        Path p = Paths.get(get("NOTELAB_DATA_DIR", "data")).toAbsolutePath();
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
        }
        return p;
    }
}
