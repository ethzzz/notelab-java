package com.notelab.common;

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
    /** B/C 拆分阶段1：C 端独立会话 Cookie 名（与 B 端 notelab_session 隔离） */
    public static final String SESSION_COOKIE_C = "notelab_c_session";

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

    /**
     * 候选 key 列表（QwenKeys 轮换用）：QWEN_API_KEYS 逗号分隔；未配置时退化为单把 QWEN_API_KEY。
     * 建议写在 /root/notelab-java/.env（仅 Java 生效，不影响 Python 版共用的 QWEN_API_KEY）。
     */
    public static java.util.List<String> qwenApiKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : get("QWEN_API_KEYS", "").split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) keys.add(t);
        }
        if (keys.isEmpty() && !qwenKey().isEmpty()) keys.add(qwenKey());
        return keys;
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

    // ---------- 爬塔素材清单（B 端「素材资源」页的候选池） ----------
    /**
     * C 端爬塔素材所在目录。B 端浏览器**读不到 C 端仓库**，所以素材候选清单由后端直接扫盘下发；
     * 后端与 C 端同机部署，默认指向 C 端仓的 public/spire（即线上 /spire 的真实来源）。
     * 目录不存在时接口返回 available=false，不抛错（本地开发/未部署 C 端时不该把 B 端页面打挂）。
     */
    public static String spireAssetRoot() {
        return get("SPIRE_ASSET_ROOT", "/root/notelab-c/public/spire");
    }

    /**
     * 素材 URL 前缀（拼在 /spire 之前）。⚠️ 默认已从 /games 改为空：
     * C 端并入个人主页后**不再有 basePath**（nginx / → 3010），public 挂在根路径，
     * /games 只是站内路由 —— 下发 /games/spire/... 会让 B 端预览缩略图与 C 端 <img> 全部 404。
     * 历史已存的 /games 前缀路径由 C 端 spireAssetUrl 归一化剥掉，所以新旧值并存无害。
     * 若日后 C 端重新挂 basePath，用环境变量 SPIRE_ASSET_URL_PREFIX 改回即可。
     */
    public static String spireAssetUrlPrefix() {
        return get("SPIRE_ASSET_URL_PREFIX", "");
    }

    // ---------- 摸金行动物品图片（B 端「物品配置」页的图片下拉候选） ----------
    /**
     * C 端摸金物品图片目录（public/loot 下的文件即线上 /loot/... 的来源）。
     * 与爬塔素材同理：B 端浏览器读不到 C 端仓库，只能让同机的后端扫盘下发。
     * 目录不存在时接口返回 available=false + files=[]，B 端静默退回 emoji，不报错。
     */
    public static String lootAssetRoot() {
        // ⚠️ 大小写敏感：服务器上的仓目录是 /root/Notelab/notelab-c（大写 N），写小写会
        //    直接判成"目录不存在"，B 端图片下拉永远空 —— 用 LOOT_ASSET_ROOT 覆盖即可。
        return get("LOOT_ASSET_ROOT", "/root/Notelab/notelab-c/public/loot");
    }

    /** 摸金图片 URL 前缀（C 端无 basePath，默认空 → /loot/xxx.png） */
    public static String lootAssetUrlPrefix() {
        return get("LOOT_ASSET_URL_PREFIX", "");
    }
}
