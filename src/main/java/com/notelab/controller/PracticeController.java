package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.dao.BlogStatDao;
import com.notelab.dao.PracticeDao;
import com.notelab.dao.PracticeImageDao;
import com.notelab.service.BlogDeployService;
import com.notelab.service.BlogPreviewService;
import com.notelab.service.BlogRepoService;
import com.notelab.service.BlogViewWhitelist;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 练习实践：记录做完每道练习题后的解答（Markdown 正文，可配图），并**一键发布到博客**。
 *
 * <h3>三块存储，别混</h3>
 * <ul>
 *   <li><b>记录</b>：表 {@code practice_record}（见 {@link PracticeDao}）。随条数无限增长，
 *       且删一条不该重写整份配置 —— 所以不进 ui_config。</li>
 *   <li><b>插图</b>：表 {@code practice_image}（见 {@link PracticeImageDao}）。正文里只留短 URL，
 *       不把 base64 塞进 Markdown（textarea 里一个几十万字符的 data URL 会让编辑器直接卡住）。</li>
 *   <li><b>类型字典</b>：{@code ui_config.practice} 的 {@code types}。</li>
 * </ul>
 *
 * <h3>「非内容数据」与「内容数据」分开写</h3>
 * {@code PUT /records/{id}} 的 {@code kind} 参数：{@code meta} 只改元数据（标题/类型/发布字段），
 * {@code content} 只改正文。分开的意义是**防覆盖**：改个标题不该把下面正在写的正文一起提交。
 *
 * <h3>发布链路（2026-10-15 起：接口驱动，不再写博客仓）</h3>
 * 最早的做法是「发布时把解答写成博客仓的 md 文件 → commit + push → 触发 GitHub Actions 构建」。
 * 那条路有两个代价：发布要等 3–5 分钟才生效，而且把「写一篇文章」变成了「一次 git 提交」。
 * 现在改成：**发布 = 一次入库操作**，博客的 solutions 页面在服务端实时拉接口渲染。三步：
 * <ol>
 *   <li>{@code GET /records/{id}/publish-preview} —— 干跑：将发布成什么 slug、归到哪篇原文、
 *       要带几张图、以及全部阻塞项/警告。前端据此渲染二次确认弹窗。</li>
 *   <li>{@code POST /records/{id}/publish} —— 真发：给插图写公开名 + 标记发布态。**秒级生效**。</li>
 *   <li>{@code GET /public/**} —— 匿名只读出口（清单 / 单篇 / 插图），博客侧消费。</li>
 * </ol>
 *
 * <p>⚠️ <b>插图不再导出文件</b>：正文里存的是 {@code /api/practice/images/{id}}，那是 B 端受权限保护的
 * 接口，博客读者是匿名 —— 照搬过去就是一片破图。所以出口处会把它改写成
 * {@code /api/practice/public/images/<公开名>}。公开名只有发布动作才会写，匿名侧因此拉不到任何草稿图。
 *
 * <p>⚠️ 博客仓只剩**只读**用途：拿它的 {@code src/content/posts/} 目录把 module + 篇章号推导成
 * relatedPost（见 {@link #findRelatedPost}）。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    /** ui_config 里的键（只存 types 字典；记录本身在表里） */
    static final String KEY = "practice";

    static final int MAX_TITLE = 200;
    static final int MAX_TYPE = 32;
    /** 正文上限：50 万字符（约 500KB 文本）。写再长的题解也够，同时挡住误贴整份日志 */
    static final int MAX_CONTENT_CHARS = 500_000;
    /** 单张插图上限：与 application.properties 的 multipart 配置一致，这里是服务端最后一道校验 */
    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /** 只收这几种图片类型。不做「任意文件上传」——那等于开了个任意文件托管入口 */
    static final Set<String> IMAGE_MIMES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    // ==================================================================================
    // 博客发布：常量与字典
    // ==================================================================================

    /**
     * 博客的学习路线模块（与 {@code astro-blog-fuwari/src/constants/roadmap-modules.ts} 的模块前缀一致）。
     *
     * <p>⚠️ 与记录的 {@code type} **不是一个维度**：{@code type} 是语言码（js/ts/java），
     * 决定「筛选时归哪一类」；{@code module} 是学习路线模块（algo/typescript/js/react…），
     * 决定「发到博客后归到哪一篇的练习清单下」。同一门语言可以横跨多个模块。
     */
    static final List<Map<String, Object>> BLOG_MODULES = List.of(
            module("algo", "算法与手写题"),
            module("typescript", "TypeScript"),
            module("js", "JavaScript"),
            module("react", "React"),
            module("engineering", "前端工程化"),
            module("browser", "浏览器与性能"),
            module("node", "Node 全栈"),
            module("ai-app", "AI 应用开发"),
            module("java", "Java"),
            module("python", "Python"),
            module("ruoyi", "RuoYi"),
            module("higress", "Higress"),
            module("docker", "Docker"));

    /** 博客 solutions schema 的 difficulty 枚举：**只能是这三个中文词**，写别的值会让博客构建报 schema 错 */
    static final List<String> DIFFICULTIES = List.of("简单", "中等", "困难");

    /**
     * 博客仓的文章目录 —— **只读**。
     *
     * <p>2026-10-15 起发布链路不再往博客仓写任何东西（解答改由公开接口供给、博客侧运行时读取），
     * 这个目录剩下的唯一用途是：把 module + 篇章号推导成 relatedPost（找 {@code <module>-roadmap-<NN>-*}）。
     */
    static final String POSTS_DIR = "src/content/posts";

    /** 正文里插图链接的形状（编辑器上传时写进去的） */
    static final Pattern IMG_RE = Pattern.compile("/api/practice/images/(\\d+)");

    /** 英文短名：小写字母/数字/连字符，不能以连字符开头（它是 URL 的一段） */
    static final Pattern SLUG_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,99}$");
    /** 篇章号：1–2 位数字（博客里对应 algo-roadmap-<NN>-*） */
    static final Pattern CHAPTER_RE = Pattern.compile("^\\d{1,2}$");

    /**
     * 公开图片名的形状：{@code <slug>-<n>.<ext>}。
     *
     * <p>⚠️ 这个正则不只是「格式检查」，它是公开图片接口的**防穿越闸门**：
     * 路径参数一旦能出现 {@code ..} 或 {@code /}，就等于开了一个任意文件读取口。
     */
    static final Pattern IMAGE_NAME_RE =
            Pattern.compile("^[a-z0-9][a-z0-9-]{0,180}\\.(png|jpg|jpeg|webp|gif)$");

    // ==================================================================================
    // 类型字典（懒 seed 初值）
    // ==================================================================================

    /**
     * 内置语言类型。用户在需求里点名的是 js / ts / java / nodejs / html，
     * 这里补成一份「够用的起步集」；要加类型不用改代码（ui_config.practice.types 覆盖）。
     */
    private static final List<Map<String, Object>> BASE_TYPES = List.of(
            type("js", "JavaScript"),
            type("ts", "TypeScript"),
            type("nodejs", "Node.js"),
            type("html", "HTML"),
            type("css", "CSS"),
            type("java", "Java"),
            type("python", "Python"),
            type("go", "Go"),
            type("rust", "Rust"),
            type("c", "C"),
            type("cpp", "C++"),
            type("sql", "SQL"),
            type("lua", "Lua"),
            type("shell", "Shell"),
            type("other", "其他"));

    private static Map<String, Object> type(String value, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        return m;
    }

    private static Map<String, Object> module(String value, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        return m;
    }

    // ==================================================================================
    // 元信息：GET /api/practice/meta
    // ==================================================================================

    /**
     * 页面初始化用：类型下拉、路线模块下拉、难度枚举、插图体积概览。
     *
     * <p>插图体积摊在页面上是刻意的 —— 图片直接进库，没有 {@code ls} 可看，不显示就只能等它撑爆。
     */
    @GetMapping("/meta")
    public ResponseEntity<Map<String, Object>> meta(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("types", typesOf(UiConfigService.getConfig()));
        body.put("modules", BLOG_MODULES);
        body.put("difficulties", DIFFICULTIES);
        try {
            body.put("images", PracticeImageDao.summary());
        } catch (Exception e) {
            body.put("images", Map.of("count", 0L, "bytes", 0L));
        }
        return ResponseEntity.ok(body);
    }

    /** types 出口：库里没有或列表为空 → 回内置（懒 seed，不主动写库） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> typesOf(Map<String, Object> cfg) {
        Object o = cfg.get(KEY);
        if (o instanceof Map) {
            Object ts = ((Map<String, Object>) o).get("types");
            if (ts instanceof List && !((List<?>) ts).isEmpty()) {
                return (List<Map<String, Object>>) ts;
            }
        }
        return new ArrayList<>(BASE_TYPES);
    }

    // ==================================================================================
    // 记录：列表 / 详情
    // ==================================================================================

    /**
     * 列表（**不含正文**）。title 模糊匹配；types 逗号分隔多选（任一命中）。
     *
     * <p>分页参数脏了不报错、按默认值收敛 —— 列表页拿不到数据比拿到第二页更糟。
     */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> records(@RequestParam(required = false) String title,
                                                       @RequestParam(required = false) String types,
                                                       @RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String size,
                                                       HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int p = Math.max(1, intOf(page, 1));
        int n = Math.max(1, Math.min(PracticeDao.MAX_SIZE, intOf(size, PracticeDao.DEFAULT_SIZE)));
        List<String> ts = splitTypes(types);
        try {
            long total = PracticeDao.countRecords(title, ts);
            List<Map<String, Object>> items = PracticeDao.listRecords(title, ts, (p - 1) * n, n);
            attachViews(items);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            body.put("total", total);
            body.put("page", p);
            body.put("size", n);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
    }

    /** 单条（**含正文**）：编辑页/查看页打开时取 */
    @GetMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> record(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> r;
        try {
            r = PracticeDao.getRecord(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        attachViews(List.of(r));
        return ResponseEntity.ok(r);
    }

    /**
     * 给列表/详情补上**博客浏览数**（{@code views}）。
     *
     * <p>数据是读者浏览器匿名上报的（{@code /api/blog/public/view}），按对外 slug 存在
     * {@code blog_article_stat}，与文章用的是同一张表、同一套「同访客同一篇只算一次」口径。
     *
     * <p>三条刻意的小口径：
     * <ul>
     *   <li><b>只给已发布的补</b>：没有 {@code published_slug} 就没有对外地址，谈不上被读了多少。
     *       下线会清掉 slug，所以下线后这一列会消失 —— 重新发布到同一 slug 时，
     *       历史计数会自动接上（统计按 slug 存，不认记录 id）。</li>
     *   <li><b>没有记录补 0</b>：这里与博客前端刻意相反。博客上「0」会被读者误读成「数据坏了」，
     *       后台要的恰恰是「这篇确实还没人看」这个信息。</li>
     *   <li><b>查失败不抛</b>：浏览数是展示性指标，数据库抖一下不该让整个列表 500。</li>
     * </ul>
     */
    private static void attachViews(List<Map<String, Object>> items) {
        List<String> slugs = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String s = str(it.get("published_slug"));
            if (!s.isEmpty()) slugs.add(s);
        }
        if (slugs.isEmpty()) return;
        try {
            Map<String, Long> views = BlogStatDao.viewsOf(slugs);
            for (Map<String, Object> it : items) {
                String s = str(it.get("published_slug"));
                if (!s.isEmpty()) it.put("views", views.getOrDefault(s, 0L));
            }
        } catch (Exception ignored) {
            // 见方法头第三条：宁可这一列不显示，也不要让列表挂掉
        }
    }

    // ==================================================================================
    // 记录：增 / 改 / 删
    // ==================================================================================

    /** 新建记录（一级页面的「创建记录」弹窗）。正文留空，进二级页面再写。 */
    @PostMapping("/records")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体必须是 JSON 对象"));
        }
        PracticeDao.Fields f = fieldsOf(node);
        String bad = validateBase(f);
        if (bad != null) return ResponseEntity.status(400).body(Map.of("error", bad));

        try {
            long id = PracticeDao.insertRecord(f, "", uid(user), uname(user));
            return ResponseEntity.ok(Map.of("ok", true, "id", id));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "创建失败：" + e));
        }
    }

    /**
     * 改一条记录。body：{@code {"kind":"meta", …}} 或 {@code {"kind":"content","content":…}}。
     *
     * <p>⚠️ 刻意**不用**「提供了哪个字段就改哪个」的 PATCH 语义：那样「只想改标题」的请求一旦漏传
     * content，正文就被清空，而且从请求体上看不出这是失误还是本意。kind 把意图写在明面上。
     */
    @PutMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (PracticeDao.getRecord(id) == null) {
            return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        }
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体必须是 JSON 对象"));
        }
        String kind = JsonSanitizer.str(node, "kind", 16).trim();
        try {
            if ("meta".equals(kind)) {
                PracticeDao.Fields f = fieldsOf(node);
                String bad = validateBase(f);
                if (bad != null) return ResponseEntity.status(400).body(Map.of("error", bad));
                PracticeDao.updateFields(id, f, uid(user), uname(user));
            } else if ("content".equals(kind)) {
                // ⚠️ 这里**不能用 JsonSanitizer.str**：它按字符上限截断，而正文上限 50 万字符，
                //    被静默截断比直接报错更难查。先取原值、再显式判超限。
                JsonNode c = node.get("content");
                String content = c == null || c.isNull() ? "" : c.asText("");
                if (content.length() > MAX_CONTENT_CHARS) {
                    return ResponseEntity.status(400).body(Map.of("error",
                            "内容过大（>" + (MAX_CONTENT_CHARS / 1000) + "K 字符），精简后再保存"));
                }
                PracticeDao.updateContent(id, content, uid(user), uname(user));
            } else {
                return ResponseEntity.status(400).body(Map.of("error", "kind 必须是 meta 或 content"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int n;
        try {
            n = PracticeDao.deleteRecord(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "删除失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true, "deleted", n));
    }

    // ==================================================================================
    // 插图：上传 / 直出
    // ==================================================================================

    /**
     * 上传一张插图，返回可直接写进 Markdown 的 URL。
     *
     * <p>⚠️ 服务端**必须自己校验** mime 与体积：前端那套压缩/类型判断只是体验层，绕过它只要一行命令。
     * 白名单之外一律拒 —— 否则这里就变成了任意文件上传入口。
     */
    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file,
                                                           HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "没有收到文件"));
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "图片过大（>" + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB），压缩后再传"));
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase();
        // 有些客户端给的是 application/octet-stream，此时按文件名后缀兜一次
        if (!IMAGE_MIMES.contains(mime)) {
            String byName = mimeByName(file.getOriginalFilename());
            if (byName == null) {
                return ResponseEntity.status(400).body(Map.of("error", "只支持 PNG / JPEG / WebP / GIF 图片"));
            }
            mime = byName;
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "读取文件失败：" + e));
        }
        if (data.length == 0) return ResponseEntity.status(400).body(Map.of("error", "文件内容为空"));
        try {
            long id = PracticeImageDao.insertImage(mime, data.length, data, uid(user));
            return ResponseEntity.ok(Map.of("ok", true, "id", id, "url", imageUrl(id)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
    }

    /**
     * 直出图片字节。
     *
     * <p>⚠️ 图片**不可变**（id 唯一、内容写入后不再改），所以给浏览器长缓存 + immutable：
     * 编辑页反复刷新时不必重复拉同一张图。权限仍由受限前缀兜着（非超管 403），
     * 缓存头里用 {@code private} 是刻意的 —— 别让中间代理缓存到别人手里。
     */
    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return ResponseEntity.status(401).build();
        Map<String, Object> r;
        try {
            r = PracticeImageDao.getImage(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
        if (r == null) return ResponseEntity.status(404).build();
        byte[] data = (byte[]) r.get("data");
        String mime = String.valueOf(r.get("mime"));
        MediaType mt;
        try {
            mt = MediaType.parseMediaType(mime);
        } catch (Exception e) {
            mt = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mt)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(data);
    }

    // ==================================================================================
    // 公开只读（匿名）：博客侧运行时拉取
    // ==================================================================================
    //
    // 2026-10-15 起，练习解答不再由「发布时写进博客仓的 md 文件 + 重新构建」供给，
    // 而是走这三个匿名接口 —— 博客的 solutions 页面在服务端实时拉取、实时渲染。
    // 于是「B 端点发布」到「读者看到」之间不再有 git 提交、不再有 CI 构建。
    //
    // ⚠️ 公开的边界是**已发布**：三个接口都只认 published_slug / public_name，
    //    草稿（未发布的记录、没打过公开名的图）永远不出现。

    /**
     * 已发布的解答清单（匿名可读，**不含正文**）。
     *
     * <p>为什么可以公开：发布这个动作本身就是在决定「让别人看到」，所以这里不泄露任何东西。
     * 反过来说，判据必须严格是「发布过」—— 见 {@link PracticeDao#listPublished()}。
     */
    @GetMapping("/public/solutions")
    public ResponseEntity<Map<String, Object>> publicSolutions() {
        List<Map<String, Object>> recs;
        try {
            recs = PracticeDao.listPublished();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
        // relatedPost 由 module + 篇章号推导，而推导要扫一遍博客仓的文章目录 ——
        // 同 (module, chapter) 的条目共用一次结果，避免每条记录都扫一遍盘。
        Map<String, String> relCache = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : recs) {
            String key = str(r.get("module")) + "/" + str(r.get("chapter"));
            String rel = relCache.computeIfAbsent(key,
                    k -> findRelatedPost(str(r.get("module")), str(r.get("chapter"))));
            items.add(publicListItem(r, rel));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }

    /**
     * 单篇解答（匿名可读，**含正文**）。
     *
     * <p>正文里的插图链接在这里被改写成公开地址（{@link #rewriteImagesForPublic}）——
     * 库里存的仍是原始正文，改写只发生在出口。
     */
    @GetMapping("/public/solutions/{slug}")
    public ResponseEntity<Map<String, Object>> publicSolution(@PathVariable String slug) {
        Map<String, Object> rec;
        try {
            rec = PracticeDao.getByPublishedSlug(slug);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
        if (rec == null) return ResponseEntity.status(404).body(Map.of("error", "解答不存在"));

        String module = str(rec.get("module"));
        String chapter = str(rec.get("chapter"));
        Map<String, Object> body = publicListItem(rec, findRelatedPost(module, chapter));
        body.put("content", rewriteImagesForPublic(str(rec.get("content"))));

        // 上一篇 / 下一篇：同模块同篇章内按题号相邻（详情页底部的两张大卡片用）
        List<Map<String, Object>> siblings = PracticeDao.listPublishedChapter(module, chapter);
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (slug.equals(str(siblings.get(i).get("published_slug")))) { idx = i; break; }
        }
        String rel = str(body.get("relatedPost"));
        body.put("prev", idx > 0 ? publicListItem(siblings.get(idx - 1), rel) : null);
        body.put("next", idx >= 0 && idx < siblings.size() - 1 ? publicListItem(siblings.get(idx + 1), rel) : null);
        return ResponseEntity.ok(body);
    }

    /**
     * 插图直出（匿名可读）。
     *
     * <p>⚠️ 只按 {@code public_name} 查，**不接受 id**：id 是自增的，顺着枚举就能把未发布草稿里的图
     * 整批拉走。public_name 只有发布动作才会写。
     *
     * <p>缓存策略与 B 端的 {@code /images/{id}} 相反：这边用 {@code public}（图片不可变，
     * 且本来就是公开内容，让 CDN/代理一起缓存是最划算的）。
     */
    @GetMapping("/public/images/{name}")
    public ResponseEntity<byte[]> publicImage(@PathVariable String name) {
        if (name == null || !IMAGE_NAME_RE.matcher(name).matches()) {
            return ResponseEntity.status(404).build();
        }
        Map<String, Object> r;
        try {
            r = PracticeImageDao.getByPublicName(name);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
        if (r == null) return ResponseEntity.status(404).build();
        byte[] data = (byte[]) r.get("data");
        MediaType mt;
        try {
            mt = MediaType.parseMediaType(String.valueOf(r.get("mime")));
        } catch (Exception e) {
            mt = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mt)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(data);
    }

    /** 对外投影：字段名与语义固定下来（博客侧按这套字段渲染，改这里等于改契约） */
    private static Map<String, Object> publicListItem(Map<String, Object> rec, String relatedPost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", str(rec.get("published_slug")));
        m.put("title", str(rec.get("title")));
        m.put("module", str(rec.get("module")));
        m.put("chapter", str(rec.get("chapter")));
        m.put("order", num(rec.get("order_no")));
        m.put("difficulty", str(rec.get("difficulty")));
        m.put("tags", splitCsv(str(rec.get("tags"))));
        m.put("description", str(rec.get("description")));
        m.put("relatedPost", relatedPost == null ? "" : relatedPost);
        // 只给日期：对外不需要精确到秒，也少暴露一点编辑节奏
        m.put("published", dateOf(rec.get("published_at")));
        m.put("updated", dateOf(rec.get("updated_at")));
        return m;
    }

    /** "2026-10-15 12:00:00.0" → "2026-10-15"（取不到就原样返回，不抛） */
    private static String dateOf(Object o) {
        String s = str(o);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    // ==================================================================================
    // 发布到博客：预检
    // ==================================================================================

    /**
     * 发布预检（干跑）。不写任何文件，只回答「点下发布会发生什么」。
     *
     * <p>前端用它渲染二次确认弹窗 —— 弹窗里必须能看到**文件名**（因为它就是博客的 URL）、
     * 会归到哪篇文章下、以及要带走几张图。看不见这些就只是「再点一次确定」，不叫二次确认。
     */
    @GetMapping("/records/{id}/publish-preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> rec = PracticeDao.getRecord(id);
        if (rec == null) return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        Plan plan;
        try {
            plan = buildPlan(rec);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "预检失败：" + e));
        }
        Map<String, Object> body = new LinkedHashMap<>(plan.toBody());
        body.put("ok", true);
        body.put("ready", plan.blockers.isEmpty());
        // 记录里已有的发布历史 —— 让用户知道「这是第一次发」还是「这是在覆盖上一版」
        String oldSlug = str(rec.get("published_slug"));
        body.put("published", oldSlug.isEmpty() ? null : Map.of(
                "slug", oldSlug,
                "commit", str(rec.get("published_commit")),
                "at", str(rec.get("published_at"))));
        body.put("willUpdate", !oldSlug.isEmpty() && oldSlug.equals(plan.slug));
        body.put("willRename", !oldSlug.isEmpty() && !oldSlug.equals(plan.slug));
        body.put("oldSlug", oldSlug);
        return ResponseEntity.ok(body);
    }

    /**
     * 博客预览（解答）：把**当前记录的发布计划**交给博客自己的页面渲染。
     *
     * <p>与「文章起草」那条预览完全同源（同一个 {@code BlogPreviewService}）：服务端把内容放进短时存储，
     * 返回可嵌 iframe 的地址；博客侧路由用**自己的** markdown 管线出页面 —— 所以是「预览＝线上观感」，
     * 而不是后台用另一套库画个近似图。解答与文章用**两条**预览路由（`/blog/solutions/preview/<token>/`
     * 与 `/blog/preview/<token>/`），因为两者的页面布局本来就不同（解答有难度徽章与「返回原题」）。
     *
     * <p>⚠️ 预览**不因为 blocker 拒绝**：blockers 是「发布信息还没配齐」，而预览恰恰要能看半成品效果。
     * ⚠️ 正文里引用的插图走 B 端受登录保护的 {@code /api/practice/images/{id}} —— 预览 iframe 与后台
     *    同源，浏览器会带上会话，所以**未发布的图也能显示**（公开解答页走的是另一条匿名出口）。
     */
    @PostMapping("/records/{id}/blog-preview")
    public ResponseEntity<Map<String, Object>> blogPreview(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> rec = PracticeDao.getRecord(id);
        if (rec == null) return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        Plan plan;
        try {
            plan = buildPlan(rec);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "预检失败：" + e));
        }
        Map<String, Object> out = BlogPreviewService.putSolution(
                AuthUtil.userId(user),
                str(rec.get("title")),
                str(rec.get("description")),
                splitCsv(str(rec.get("tags"))),
                plan.difficulty(),
                plan.relatedPost(),
                plan.body());
        out.put("slug", plan.slug());
        out.put("relatedPost", plan.relatedPost());
        out.put("blockers", plan.blockers());
        return ResponseEntity.ok(out);
    }

    // ==================================================================================
    // 发布到博客：真发
    // ==================================================================================

    /**
     * 发布一条记录（= 让它对博客公开）。
     *
     * <p>2026-10-15 起这里**不再碰博客仓**：只做两件事 —— 给用到的插图打上公开名、
     * 把记录标成已发布。博客的解答页改成运行时读 {@code /api/practice/public/**}，
     * 所以发布是**即时生效**的（没有 commit、不等构建、也不会再有 scp 卡住那种故障面）。
     *
     * <p>顺序刻意是「先校验（不写任何东西）→ 再落库」：校验不过时一行数据都不会被改，
     * 用户看到的只有 400 与原因。
     */
    @PostMapping("/records/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> rec = PracticeDao.getRecord(id);
        if (rec == null) return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));

        Plan plan;
        try {
            plan = buildPlan(rec);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "预检失败：" + e));
        }
        if (!plan.blockers.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "还差一些发布信息：" + String.join("；", plan.blockers),
                    "blockers", plan.blockers));
        }

        String oldSlug = str(rec.get("published_slug"));
        boolean update = !oldSlug.isEmpty() && oldSlug.equals(plan.slug);

        try {
            // 给插图打公开名 —— 公开接口只认这个名字，没打过的图匿名永远拉不到
            int imageCount = 0;
            for (Map<String, Object> img : plan.images) {
                long imgId = ((Number) img.get("id")).longValue();
                PracticeImageDao.setPublicName(imgId, (String) img.get("publicName"));
                imageCount++;
            }
            // 发布态落在记录上；改过 slug 时旧 slug 被直接覆盖（旧链接失效，但对外只有这一个地址）
            PracticeDao.markPublished(id, plan.slug, "");
            // 刚发的这篇立刻可被计数（否则要等白名单 5 分钟缓存过期，前几个读者会白读）
            BlogViewWhitelist.invalidateSolutions();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("slug", plan.slug);
            out.put("url", blogUrlOf(plan.slug));
            out.put("images", imageCount);
            out.put("relatedPost", plan.relatedPost);
            out.put("warnings", plan.warnings);
            out.put("updated", update);
            out.put("note", "已发布。博客页面直接读数据库，刷新即见（不需要重新构建）；站内搜索索引与 sitemap 已排队重建。");
            out.put("search", queueReindex(user, blogUrlOf(plan.slug)));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "发布失败：" + e.getMessage()));
        }
    }

    /**
     * 下线一篇已发布的解答（= 让博客上的读者看不到它）。
     *
     * <p>为什么要单独一个动作：发布之前是**单向**的 —— 发错了、或者这篇还不该公开，只能去数据库改。
     * 下线的语义是「撤下，但记录还在」：后台照样能看到、能编辑，重新发布就回到同一个地址。
     *
     * <p>⚠️ 只清发布态，不删记录也不删插图（理由见 {@code PracticeDao.markUnpublished}）。
     * 所以下线的效果是**公开接口查不到** → 博客的解答页 404，而不是把内容删掉。
     */
    @PostMapping("/records/{id}/unpublish")
    public ResponseEntity<Map<String, Object>> unpublish(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> rec = PracticeDao.getRecord(id);
        if (rec == null) return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        String slug = str(rec.get("published_slug"));
        if (slug.isEmpty()) {
            // 已经下线了也当成功处理会更"顺"，但那样用户分不清「我点了没生效」还是「本来就没发」，
            // 所以这里明确告诉他当前状态。
            return ResponseEntity.status(400).body(Map.of("error", "这条还没有发布，无需下线"));
        }
        PracticeDao.markUnpublished(id);
        // 下线后这个 slug 不该再被计数（页面已 404，但统计接口本来还会认它）
        BlogViewWhitelist.invalidateSolutions();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("slug", slug);
        out.put("note", "已下线。" + blogUrlOf(slug) + " 现在返回 404；重新发布即可恢复，地址不变。");
        // 搜索索引也要把它去掉：重建读的是**当前已发布清单**，所以下线之后跑一次就自动不含它
        out.put("search", queueReindex(AuthUtil.user(request), ""));
        return ResponseEntity.ok(out);
    }

    /**
     * 衍生索引重建入队（best-effort）：站内搜索（pagefind）+ sitemap。
     *
     * <p>为什么发布/下线解答要单独跑一次：解答页是**运行时渲染**（prerender=false），构建产物
     * `dist/client` 里没有它们 —— pagefind 只索引构建产物（题解搜不到），`@astrojs/sitemap`
     * 只枚举预渲染路由（单篇题解进不了 sitemap）。不补这一步，题解既搜不到、也难被发现。
     * 页面本身仍是即时生效的（读者刷新就见），这一步只解决「能不能被搜到/被爬到」。
     *
     * <p>⚠️ 失败**不能**让「已经发布成功」变成 500：内容已经在库里、页面上已经能看了，
     * 索引没建成只是搜不到，属于可重试的次要后果（状态见 /blog-deploy 的历史）。
     */
    private static Map<String, Object> queueReindex(Map<String, Object> user, String verifyPath) {
        try {
            return BlogDeployService.triggerReindex(AuthUtil.userId(user), String.valueOf(user.get("username")), verifyPath);
        } catch (Exception e) {
            return Map.of("ok", false, "reason", "索引重建入队失败：" + e.getMessage());
        }
    }

    // ==================================================================================
    // 发布：计划构建（预检与发布共用，保证「看到的」与「发出的」是同一份）
    // ==================================================================================

    /**
     * 一次发布的完整计划。
     *
     * <p>预检与真发**共用**同一个构建函数：分开写两份必然漂移，最后变成「预检说没问题、
     * 发布却报错」或更糟的「预检说会生成 A、实际写出来是 B」。
     */
    private record Plan(String slug, String module, String chapter, int orderNo, String difficulty,
                        String relatedPost, String body,
                        List<String> blockers, List<String> warnings,
                        List<Map<String, Object>> images) {

        Map<String, Object> toBody() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", slug);
            m.put("url", blogUrlOf(slug));
            m.put("module", module);
            m.put("chapter", chapter);
            m.put("order", orderNo);
            m.put("difficulty", difficulty);
            m.put("relatedPost", relatedPost);
            m.put("relatedPostFound", !relatedPost.isEmpty());
            m.put("images", images);
            m.put("blockers", blockers);
            m.put("warnings", warnings);
            return m;
        }
    }

    private Plan buildPlan(Map<String, Object> rec) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String title = str(rec.get("title")).trim();
        String module = str(rec.get("module")).trim();
        String chapter = str(rec.get("chapter")).trim();
        int orderNo = (int) num(rec.get("order_no"));
        String slugName = str(rec.get("slug_name")).trim();
        String difficulty = str(rec.get("difficulty")).trim();
        String description = str(rec.get("description")).trim();
        String body = str(rec.get("content"));

        if (title.isEmpty()) blockers.add("标题不能为空");
        if (!moduleCodes().contains(module)) blockers.add("路线模块未选（或不是已知模块）");
        if (!CHAPTER_RE.matcher(chapter).matches()) blockers.add("篇章号要是 1–2 位数字，如 01");
        if (orderNo < 1 || orderNo > 999) blockers.add("题号要在 1–999 之间");
        if (!SLUG_NAME_RE.matcher(slugName).matches()) {
            blockers.add("英文短名只能用小写字母/数字/连字符（如 pivot-index），且不能以连字符开头");
        }
        if (!DIFFICULTIES.contains(difficulty)) blockers.add("难度只能是 简单 / 中等 / 困难");
        if (body.isBlank()) warnings.add("正文为空，发出去的会是一个只有标题的页面");

        // 文件名 = URL。规则见博客 docs/solutions-convention.md：
        // <模块前缀>-<篇章号>-ex<题号>-<英文短名>.md
        String ch2 = pad2(chapter);
        String slug = module + "-" + ch2 + "-ex" + pad2(orderNo) + "-" + slugName;

        // relatedPost 不用用户填：文件名里的篇章号正好对应 <module>-roadmap-<NN>-*，
        // 填两份信息必然会不一致（改了一处忘了另一处），所以这里直接推导。
        String relatedPost = "";
        if (!module.isEmpty() && CHAPTER_RE.matcher(chapter).matches()) {
            relatedPost = findRelatedPost(module, ch2);
            if (relatedPost.isEmpty()) {
                warnings.add("博客里没找到 " + module + "-roadmap-" + ch2 + "-* 这篇路线文章，"
                        + "聚合页会把它归到「独立分组」，单题页也没有「返回原题」");
            }
        }

        // 插图清单（同一张图在正文里出现多次只导出一次）
        List<Map<String, Object>> images = new ArrayList<>();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        Matcher m = IMG_RE.matcher(body);
        while (m.find()) {
            try { ids.add(Long.parseLong(m.group(1))); } catch (NumberFormatException ignored) { }
        }
        int n = 0;
        for (Long imgId : ids) {
            Map<String, Object> row;
            try {
                row = PracticeImageDao.getImage(imgId);
            } catch (Exception e) {
                row = null;
            }
            if (row == null) {
                warnings.add("插图 #" + imgId + " 已经不存在了，正文里那条链接会保持原样（博客上看不到这张图）");
                continue;
            }
            n++;
            String ext = extOf(str(row.get("mime")));
            // 公开名 = 对外文件名。它既是 URL 的一段，也是「这张图允许匿名访问」的凭据
            String publicName = slug + "-" + n + "." + ext;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", imgId);
            one.put("publicName", publicName);
            one.put("url", publicImageUrl(publicName));
            images.add(one);
        }

        return new Plan(slug, module, chapter, orderNo, difficulty, relatedPost, body,
                blockers, warnings, images);
    }

    /**
     * 公开侧正文改写：把 {@code /api/practice/images/{id}} 换成 {@code /api/practice/public/images/<公开名>}。
     *
     * <p>为什么在**读取时**改写、而不是发布时写回库：库里那份要保持「原始正文」（编辑器里看到的
     * 就是它），改写只是对外呈现的一层投影。写回库会让「换一次图片出口」变成一次全表重写。
     *
     * <p>⚠️ 没有公开名（未发布过的图）保持原样：那种链接对匿名读者是 401，比换成一个能被枚举猜到的
     * 地址更诚实，也堵住了「顺着 id 把草稿里的图拉走」这条路。
     */
    private static String rewriteImagesForPublic(String body) {
        Matcher m = IMG_RE.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = PracticeImageDao.publicNameOf(Long.parseLong(m.group(1)));
            String url = name.isEmpty() ? m.group(0) : publicImageUrl(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(url));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 在博客仓的文章目录里找该模块该篇章的路线文章，返回 slug（去掉 .md / .mdx）；找不到返回空串。
     *
     * <p>⚠️ 只**读**这个 clone（发布链路已不再往里写）—— 它现在的角色是「线路文章清单」的权威副本，
     * 用来把 module + 篇章号推导成 relatedPost。
     *
     * <p>⚠️ 两种后缀都要认：博客的路线文章 2026-10-20 起统一是 {@code .mdx}（为了能嵌组件），
     * 只认 {@code .md} 会让所有解答的 relatedPost 都是空串 —— 聚合页全落到「独立分组」，
     * 且不报错，只有肉眼看页面才会发现。
     */
    private static String findRelatedPost(String module, String chapter2) {
        String prefix = module + "-roadmap-" + chapter2 + "-";
        for (String name : BlogRepoService.listNames(POSTS_DIR)) {
            if (!name.startsWith(prefix)) continue;
            if (name.endsWith(".md")) return name.substring(0, name.length() - 3);
            if (name.endsWith(".mdx")) return name.substring(0, name.length() - 4);
        }
        return "";
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    /** 从 JSON 解析「非内容数据」（新建与修改共用）。长度按列宽裁，但不做强枚举校验 ——
     *  强校验留给发布预检：允许先建一条草稿，等发布时再补齐。 */
    private static PracticeDao.Fields fieldsOf(JsonNode node) {
        String difficulty = JsonSanitizer.str(node, "difficulty", 8).trim();
        if (difficulty.isEmpty()) difficulty = "中等";
        return new PracticeDao.Fields(
                JsonSanitizer.str(node, "title", MAX_TITLE).trim(),
                JsonSanitizer.str(node, "type", MAX_TYPE).trim(),
                JsonSanitizer.str(node, "module", 32).trim(),
                JsonSanitizer.str(node, "chapter", 8).trim(),
                orderOf(node),
                JsonSanitizer.str(node, "slug_name", 120).trim(),
                difficulty,
                tagsOf(node),
                JsonSanitizer.str(node, "description", 500).trim());
    }

    /** 只有标题和类型是硬性的；发布字段可以后补（发布预检会逐个拦） */
    private static String validateBase(PracticeDao.Fields f) {
        if (f.title().isEmpty()) return "标题不能为空";
        if (f.type().isEmpty()) return "类型不能为空";
        if (f.description().length() > 500) return "描述过长";
        return null;
    }

    /**
     * 题号：数字或数字字符串都认。
     *
     * <p>⚠️ 刻意**不用** {@link JsonSanitizer#clampInt} —— 它只认 {@code isNumber()}，
     * 前端一时传成 {@code "5"}（字符串）就会被静默当成 0，而 0 的表现是
     * 「发布时说题号要在 1–999 之间」，从现象完全看不出是类型问题。
     */
    private static int orderOf(JsonNode node) {
        JsonNode n = node.get("order_no");
        if (n == null || n.isNull()) return 0;
        int v;
        if (n.isNumber()) {
            v = n.asInt();
        } else {
            try {
                v = Integer.parseInt(n.asText("").trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return Math.max(0, Math.min(999, v));
    }

    /** tags：接受数组或逗号分隔字符串；去重保序，总长按列宽截断 */
    private static String tagsOf(JsonNode node) {
        JsonNode t = node.get("tags");
        if (t == null || t.isNull()) return "";
        List<String> parts = new ArrayList<>();
        if (t.isArray()) {
            for (JsonNode x : t) {
                String s = x.asText("").trim();
                if (!s.isEmpty()) parts.add(s.length() > 32 ? s.substring(0, 32) : s);
            }
        } else {
            for (String s : t.asText("").split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) parts.add(v.length() > 32 ? v.substring(0, 32) : v);
            }
        }
        String joined = String.join(",", new LinkedHashSet<>(parts));
        return joined.length() > 255 ? joined.substring(0, 255) : joined;
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String x : s.split(",")) {
            String v = x.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static Set<String> moduleCodes() {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> m : BLOG_MODULES) out.add(String.valueOf(m.get("value")));
        return out;
    }

    /**
     * 插图的公开访问路径（写进正文里的就是它）。
     *
     * <p>2026-10-15 起图片由 Java 直出，不再导出到博客的 public/ —— 所以这里的前缀是
     * {@code /api/practice/public/images/}，与博客同源（都是 haolo.cloud），页面里直接引用即可。
     */
    private static String publicImageUrl(String publicName) {
        return "/api/practice/public/images/" + publicName;
    }

    /** 解答在博客上的访问路径（文件名即路径段） */
    private static String blogUrlOf(String slug) {
        return "/blog/solutions/" + slug + "/";
    }

    /** 图片 mime → 文件扩展名（公开名要带扩展名，浏览器/nginx 才认） */
    private static String extOf(String mime) {
        return switch (mime == null ? "" : mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private static String pad2(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() >= 2 ? t : ("0" + t);
    }

    private static String pad2(int n) {
        return n < 10 ? ("0" + n) : String.valueOf(n);
    }

    private static JsonNode parse(String raw) {
        try {
            if (raw == null || raw.isBlank()) return null;
            return JsonUtil.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件名后缀 → mime（仅在客户端没给对 Content-Type 时兜底） */
    private static String mimeByName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return null;
    }

    /** "js,ts,js" → [js, ts]（去空去重，顺序保留） */
    private static List<String> splitTypes(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) {
            for (String x : s.split(",")) {
                String v = x.trim();
                if (!v.isEmpty()) out.add(v.length() > MAX_TYPE ? v.substring(0, MAX_TYPE) : v);
            }
        }
        return new ArrayList<>(out);
    }

    private static int intOf(String s, int dft) {
        if (s == null || s.isBlank()) return dft;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static Long uid(Map<String, Object> user) {
        Object o = user.get("id");
        return o instanceof Number n ? n.longValue() : null;
    }

    private static String uname(Map<String, Object> user) {
        return String.valueOf(user.getOrDefault("username", ""));
    }

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 图片的对外路径（编辑器插入 Markdown 用，与 {@link #image} 的映射一致） */
    private static String imageUrl(long id) {
        return "/api/practice/images/" + id;
    }
}
