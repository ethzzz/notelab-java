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

    /**
     * 服务器工作副本根目录的默认值。⚠️ 大小写敏感：是 {@code /root/Notelab}（大写 N），
     * <b>不是</b> {@code /root/notelab} —— 后者在这个服务器上根本不存在。
     *
     * <p>为什么值得单独抽成一个常量：扫盘（爬塔素材 / 摸金图片）与运维看板都要拼绝对路径，
     * 各写一遍就会出现"一处大写一处小写"，而且失败形态是<em>静默</em>的 ——
     * 目录不存在时接口只是返回空列表 / warn，不报错，看起来像"没素材"而不是"路径写错了"。
     * 统一走 {@link #repoRoot()} 后只剩这一处可能写错。
     */
    public static final String DEFAULT_REPO_ROOT = "/root/Notelab";

    /**
     * 源码里公开的占位会话密钥。**绝不可用于生产**。
     *
     * <p>为什么把它抽成具名常量：2026-10-08 发现线上确实一度在用它（`.env` 里没有 SECRET_KEY），
     * 而用它是**静默**的 —— 服务照常启动、接口照常 200，只是任何人拿这个公开字符串就能
     * 伪造任意用户（含 super_admin uid=18）的会话。抽成常量后 {@link #secretKeyIsInsecure()}
     * 与 `Bootstrap` 的启动自检都引用它，避免"检测逻辑里再抄一份字面量"。
     */
    public static final String INSECURE_DEFAULT_SECRET = "dev-secret-change-me";

    /** 同上，用于分析库 IP 哈希的盐（危害小得多：只影响 IP 匿名性）。 */
    public static final String INSECURE_DEFAULT_ANALYTICS_SALT = "notelab-analytics-salt";

    private static final Map<String, String> FILE_ENV = new LinkedHashMap<>();
    private static volatile boolean loaded = false;

    private AppConfig() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        // 本目录 .env 优先于 Python 目录 .env
        loadDotEnv(Paths.get(".env").toAbsolutePath());
        // ⚠️ 这里必须用常量而非 repoRoot()：repoRoot() 走 get() → ensureLoaded()，会无限递归爆栈。
        // ⚠️ 大小写：服务器上是 /root/Notelab（大写 N），写小写会让这一档直接失效（文件不存在 → 静默跳过）。
        loadDotEnv(Paths.get(DEFAULT_REPO_ROOT + "/.env"));
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

    // ==================================================================================
    // 大模型配置（2026-10-20 从「阿里云 MaaS」切到 DeepSeek 时统一了命名）
    //
    // 变量名：LLM_*（2026-10-20 起不再兼容旧的 QWEN_*，那条兼容层正是"两套真相"的来源）。
    //   现网 .env 用 LLM_BASE_URL / LLM_MODEL / LLM_API_KEYS（与 ai-lab 的 LLM_* 对齐）。
    // ⚠️ 只改 .env 而代码还读旧名 = 服务会回落到「dashscope + qwen-plus + 默认 key」，
    //   2026-10-20 就这么踩过一次（改名的同一次部署必须带上这版代码）。
    // ==================================================================================

    /** 打哪家网关（OpenAI 兼容协议）。 */
    public static String llmBaseUrl() {
        return get("LLM_BASE_URL", "https://api.deepseek.com/v1");
    }

    /** 默认模型。 */
    public static String llmModel() {
        return get("LLM_MODEL", "deepseek-flash");
    }

    /**
     * 首选 key —— **单值场景**（拉模型列表 {@code QwenClient.fetchModels}、健康探活）用它。
     *
     * <p>与 {@link #llmApiKeys()} **同一口径**：先 LLM_API_KEYS、再 QWEN_API_KEYS，取第一把；
     * 都为空才退到单值 LLM_API_KEY / QWEN_API_KEY。
     *
     * <p>2026-10-20 踩过的坑：这里原来只读 {@code QWEN_API_KEY}。而 {@code QWEN_API_KEY} 还留在
     * <b>/etc/environment</b>（历史遗留的阿里云死 key），进程环境变量优先级高于 `.env` ——
     * 于是「对话」用 `.env` 里的新 key 正常、`/api/models` 却拿死 key 报 401，
     * 表现为「模型能用但模型列表永远是空的」。
     */
    public static String llmKey() {
        for (String csv : new String[]{get("LLM_API_KEYS", ""), get("LLM_DEEPSEEK_API_KEYS", "")}) {
            for (String k : csv.split(",")) {
                String t = k.trim();
                if (!t.isEmpty()) return t;
            }
        }
        return get("LLM_API_KEY", "").trim();
    }

    /**
     * 候选 key 列表（QwenKeys 轮换用）：LLM_API_KEYS / QWEN_API_KEYS 逗号分隔；
     * 都为空时退化为单值 LLM_API_KEY / QWEN_API_KEY。
     *
     * <p>⚠️ 这里**直接读单值变量**而不是调 {@link #llmKey()}：后者会先看逗号列表，
     * 两个方法互相调用就成环了（而且会让"退化"分支永远不成立）。
     */
    public static java.util.List<String> llmApiKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String csv : new String[]{get("LLM_API_KEYS", ""), get("LLM_DEEPSEEK_API_KEYS", "")}) {
            for (String k : csv.split(",")) {
                String t = k.trim();
                if (!t.isEmpty() && !keys.contains(t)) keys.add(t);
            }
        }
        if (keys.isEmpty()) {
            String single = get("LLM_API_KEY", "").trim();
            if (!single.isEmpty()) keys.add(single);
        }
        return keys;
    }

    /** 按顺序取第一个非空的配置值，全空则用默认值 */
    private static String firstOf(String k1, String k2, String def) {
        String v = get(k1, "");
        if (!v.trim().isEmpty()) return v;
        v = get(k2, "");
        return v.trim().isEmpty() ? def : v;
    }

    /**
     * 会话签名密钥（HMAC-SHA256）—— B 端 {@code notelab_session} 与 C 端 {@code notelab_c_session} 共用。
     *
     * <p>⚠️ <b>生产必须在 .env 里显式配置</b>。未配置时回落 {@link #INSECURE_DEFAULT_SECRET}
     * （源码里公开的字符串）→ {@link #secretKeyIsInsecure()} 为真 → `Bootstrap` **拒绝启动**。
     *
     * <p>⚠️ 改这个值 = 所有 B/C 端会话立即失效（用户需重新登录）；旁路服务（ai-lab 等）若
     * 缓存了会话也要一并清理。
     */
    public static String secretKey() {
        return get("SECRET_KEY", INSECURE_DEFAULT_SECRET);
    }

    /** 会话密钥是否仍是公开占位值（或空）—— 生产环境为真即视为致命配置错误。 */
    public static boolean secretKeyIsInsecure() {
        String v = secretKey();
        return v.isEmpty() || INSECURE_DEFAULT_SECRET.equals(v);
    }

    /** 分析库 IP 盐：IP 只存 {@code HmacSHA256(ip + 盐)} 的前 16 位，盐不入库。 */
    public static String analyticsIpSalt() {
        return get("ANALYTICS_IP_SALT", INSECURE_DEFAULT_ANALYTICS_SALT);
    }

    /** IP 盐是否仍是公开占位值 —— 只告警不阻断（影响力仅限 IP 匿名性，不影响认证）。 */
    public static boolean analyticsSaltIsInsecure() {
        return INSECURE_DEFAULT_ANALYTICS_SALT.equals(analyticsIpSalt());
    }

    /**
     * 是否给会话 Cookie 加 {@code Secure}（只允许 HTTPS 携带）。
     *
     * <p>线上全站 HTTPS（nginx:80 → 301，且已配 HSTS），所以生产应开 —— 见 .env 的
     * {@code COOKIE_SECURE=1}。做成配置项而非写死：本地走 HTTP 时开了会**登录不上**
     * （浏览器不会在明文请求里带上 Secure Cookie），这种取舍不该由代码替人决定。
     */
    public static boolean cookieSecure() {
        return "1".equals(get("COOKIE_SECURE", "").trim());
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

    // ---------- 服务器路径基准 ----------
    /**
     * 服务器工作副本根目录（其下平铺 notelab-java / notelab-b / notelab-c 等仓）。
     * 本地开发或不在这台机器上时用环境变量 {@code REPO_ROOT} 覆盖。
     */
    public static String repoRoot() {
        return get("REPO_ROOT", DEFAULT_REPO_ROOT);
    }

    /** C 端仓库目录 —— B 端浏览器读不到它，素材/图片候选只能靠后端扫盘下发。 */
    public static String cRepoDir() {
        return repoRoot() + "/notelab-c";
    }

    /** B 端仓库目录 —— 课程 JSON（src/content/learning）在里面，见 {@link #learningContentDir()}。 */
    public static String bRepoDir() {
        return repoRoot() + "/notelab-b";
    }

    // ---------- 编程学习：文件型课程 ----------
    /**
     * 课程 JSON 目录，约定<strong>一门语言一个文件</strong>（{@code src/content/learning/<code>.json}）。
     *
     * <p>为什么放在 B 端仓而不是 {@link com.notelab.model.LearningSeed}：课程正文是 145KB 级别的
     * 长 Markdown，写进 Java 字符串常量既没法 diff 也没法 review；放仓里随 git 走，改完部署 B 端即可生效。
     * 后端与 B 端同机部署，所以直接扫盘读（与 C 端素材清单同一套做法）。
     *
     * <p>目录不存在时跳过，不抛错 —— 本地开发没有 B 端仓也不该把接口打挂。
     */
    public static String learningContentDir() {
        return get("LEARNING_CONTENT_DIR", bRepoDir() + "/src/content/learning");
    }

    // ---------- 爬塔素材清单（B 端「素材资源」页的候选池） ----------
    /**
     * C 端爬塔素材所在目录。B 端浏览器**读不到 C 端仓库**，所以素材候选清单由后端直接扫盘下发；
     * 后端与 C 端同机部署，默认指向 C 端仓的 public/spire（即线上 /spire 的真实来源）。
     * 目录不存在时接口返回 available=false，不抛错（本地开发/未部署 C 端时不该把 B 端页面打挂）。
     */
    public static String spireAssetRoot() {
        return get("SPIRE_ASSET_ROOT", cRepoDir() + "/public/spire");
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
        return get("LOOT_ASSET_ROOT", cRepoDir() + "/public/loot");
    }

    /** 摸金图片 URL 前缀（C 端无 basePath，默认空 → /loot/xxx.png） */
    public static String lootAssetUrlPrefix() {
        return get("LOOT_ASSET_URL_PREFIX", "");
    }
}
