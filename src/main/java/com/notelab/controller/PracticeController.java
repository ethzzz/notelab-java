package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.common.YamlText;
import com.notelab.dao.PracticeDao;
import com.notelab.dao.PracticeImageDao;
import com.notelab.service.BlogRepoService;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
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
 * <h3>发布链路（2026-10-14 打通）</h3>
 * 练习实践 → 博客的 {@code src/content/solutions/}（**不是 posts**）。这个集合在博客侧已经存在
 * （列表页 / 详情页 / 导航入口 / schema 都齐），B 端只差一个写入入口。三步：
 * <ol>
 *   <li>{@code GET /records/{id}/publish-preview} —— 干跑一遍，把「将生成什么」摊给用户看：
 *       文件名（= URL）、frontmatter、relatedPost 推导结果、要带走几张图、以及所有阻塞项/警告。
 *       前端据此渲染二次确认弹窗。</li>
 *   <li>{@code POST /records/{id}/publish} —— 真写：导出插图 → 改写正文链接 → 写 md →
 *       commit + push（push 到 main 会触发博客的 GitHub Actions 构建部署）。</li>
 *   <li>回写发布态（{@code published_slug}/{@code published_at}/{@code published_commit}），
 *       让「这条发过没、发的是哪一版」在列表上看得见，也避免重复发布变成重复文件。</li>
 * </ol>
 *
 * <p>⚠️ <b>插图必须导出</b>：正文里存的是 {@code /api/practice/images/{id}}，那是 B 端受权限保护的接口，
 * 博客访问者是匿名 —— 照搬链接过去就是一片破图。所以要把字节写进博客仓的 {@code public/}
 * 并把链接改写成 {@code /blog/images/solutions/...}（{@code /blog} 前缀是博客的全站内链约定，漏了 404）。
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
            module("higress", "Higress"));

    /** 博客 solutions schema 的 difficulty 枚举：**只能是这三个中文词**，写别的值会让博客构建报 schema 错 */
    static final List<String> DIFFICULTIES = List.of("简单", "中等", "困难");

    static final String SOLUTIONS_DIR = "src/content/solutions";
    static final String POSTS_DIR = "src/content/posts";
    /** 插图导出位置（博客 public 根下）。访问路径是 base + 这个路径，见 {@link #imageUrlOf} */
    static final String SOLUTION_IMAGES_DIR = "public/images/solutions";

    /** 正文里插图链接的形状（编辑器上传时写进去的） */
    static final Pattern IMG_RE = Pattern.compile("/api/practice/images/(\\d+)");

    /** 英文短名：小写字母/数字/连字符，不能以连字符开头（它是 URL 的一段） */
    static final Pattern SLUG_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,99}$");
    /** 篇章号：1–2 位数字（博客里对应 algo-roadmap-<NN>-*） */
    static final Pattern CHAPTER_RE = Pattern.compile("^\\d{1,2}$");

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
        return ResponseEntity.ok(r);
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

    // ==================================================================================
    // 发布到博客：真发
    // ==================================================================================

    /**
     * 发布一条记录到博客的 {@code src/content/solutions/}。
     *
     * <p>顺序刻意是「先校验（不写任何东西）→ 再写文件 → 最后才 commit/push」：
     * 校验不过时博客仓一个字节都不会被碰，用户看到的只有 400 与原因。
     *
     * <p>⚠️ push 的对象是博客的 main 分支，会触发 GitHub Actions 构建部署（约 3–5 分钟生效）。
     * 本接口返回成功**不等于**线上已可见，响应里带 note 说明这一点。
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

        List<String> paths = new ArrayList<>();
        int imageCount = 0;
        // id → 导出去的新 URL。⚠️ 不能用「前 N 张」来推断哪些导出了：中间某张被删会让后面全部错位，
        // 结果是正文里引用了一个根本没写出去的图片路径。
        Map<Long, String> exportedMap = new LinkedHashMap<>();
        try {
            BlogRepoService.ensure();

            // 1) 导出插图：把库里的字节写进博客 public/，正文里的链接改指向它
            for (Map<String, Object> img : plan.images) {
                long imgId = ((Number) img.get("id")).longValue();
                Map<String, Object> row = PracticeImageDao.getImage(imgId);
                if (row == null) continue;   // 预检到写入之间被删了：跳过，正文链接保持原样（已是 warning）
                BlogRepoService.writeBinary((String) img.get("target"), (byte[]) row.get("data"));
                paths.add((String) img.get("target"));
                exportedMap.put(imgId, (String) img.get("url"));
                imageCount++;
            }

            // 2) 改写正文里的插图链接（只改本轮真的导出成功的那些）
            String body = rewriteImages(plan.body, exportedMap);

            // 3) 写解答文件（含 frontmatter）
            String rel = SOLUTIONS_DIR + "/" + plan.slug + ".md";
            BlogRepoService.writeText(rel, plan.frontmatter() + body + "\n");
            paths.add(rel);

            // 4) 改过名字（module/篇章号/题号/短名变了）→ 清掉上一版的旧文件，
            //    否则博客的聚合页会同时列出新旧两篇，而旧的永远不会再被更新。
            if (!oldSlug.isEmpty() && !oldSlug.equals(plan.slug)) {
                String oldRel = SOLUTIONS_DIR + "/" + oldSlug + ".md";
                if (BlogRepoService.exists(oldRel)) {
                    BlogRepoService.deleteIfExists(oldRel);
                    paths.add(oldRel);
                }
            }

            // 5) 提交并推送 —— 只 add 本次涉及的文件（博客仓可能有别人未提交的进行中改动）
            String commit = BlogRepoService.commitPush(
                    "solutions: " + (update ? "update " : "add ") + plan.slug, paths);

            // 6) 回写发布态
            PracticeDao.markPublished(id, plan.slug, commit);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("slug", plan.slug);
            out.put("url", blogUrlOf(plan.slug));
            out.put("path", rel);
            out.put("commit", commit);
            out.put("images", imageCount);
            out.put("relatedPost", plan.relatedPost);
            out.put("warnings", plan.warnings);
            out.put("updated", update);
            out.put("note", "已推送。博客由 GitHub Actions 构建部署，约 3–5 分钟后线上可见。");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "发布失败：" + e.getMessage(),
                    "hint", "文件可能已写到博客仓但未推送，重试一次即可（内容相同会走 no-change）。"));
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
                        String relatedPost, String body, String frontmatter,
                        List<String> blockers, List<String> warnings,
                        List<Map<String, Object>> images) {

        Map<String, Object> toBody() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", slug);
            m.put("target", SOLUTIONS_DIR + "/" + slug + ".md");
            m.put("url", blogUrlOf(slug));
            m.put("module", module);
            m.put("chapter", chapter);
            m.put("order", orderNo);
            m.put("difficulty", difficulty);
            m.put("relatedPost", relatedPost);
            m.put("relatedPostFound", !relatedPost.isEmpty());
            m.put("frontmatter", frontmatter);
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
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", imgId);
            one.put("target", SOLUTION_IMAGES_DIR + "/" + slug + "-" + n + "." + ext);
            one.put("url", imageUrlOf(slug, n, ext));
            images.add(one);
        }

        String fm = buildSolutionFrontmatter(title, description, module, relatedPost,
                orderNo, difficulty, str(rec.get("tags")));
        return new Plan(slug, module, chapter, orderNo, difficulty, relatedPost, body, fm,
                blockers, warnings, images);
    }

    /**
     * 生成 solutions 的 frontmatter。
     *
     * <p>字段与顺序对齐博客 {@code src/content/config.ts} 的 solutions schema；
     * {@code published} 用**发布当天**而不是记录的创建日 —— 它表达的是「哪天公开的」。
     */
    private static String buildSolutionFrontmatter(String title, String description, String module,
                                                   String relatedPost, int orderNo, String difficulty,
                                                   String tagsJoined) {
        List<String> tags = splitCsv(tagsJoined);
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(YamlText.quote(title)).append("\n");
        sb.append("published: ").append(LocalDate.now()).append("\n");
        // description 留空会让 SEO/卡片回退成 title，所以这里补上 title 而不是留空
        sb.append("description: ").append(YamlText.quote(description.isEmpty() ? title : description)).append("\n");
        sb.append("module: ").append(YamlText.quote(module)).append("\n");
        sb.append("relatedPost: ").append(YamlText.quote(relatedPost)).append("\n");
        sb.append("order: ").append(orderNo).append("\n");
        sb.append("difficulty: ").append(YamlText.quote(difficulty)).append("\n");
        if (tags.isEmpty()) {
            sb.append("tags: []\n");
        } else {
            sb.append("tags: [")
                    .append(tags.stream().map(YamlText::quote).collect(java.util.stream.Collectors.joining(", ")))
                    .append("]\n");
        }
        sb.append("draft: false\n");
        sb.append("lang: 'zh-CN'\n");
        sb.append("---\n");
        return sb.toString();
    }

    /**
     * 把正文里的 {@code /api/practice/images/{id}} 换成博客上的静态地址。
     *
     * <p>⚠️ 只替换**确实导出成功**的那些（map 里没有的保持原样）：预检到写入之间图片可能被删，
     * 那种情况保留原链接比换成一个指向 404 的新链接更诚实。
     */
    private static String rewriteImages(String body, Map<Long, String> exported) {
        Matcher m = IMG_RE.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = exported.get(Long.parseLong(m.group(1)));
            m.appendReplacement(sb, Matcher.quoteReplacement(url == null ? m.group(0) : url));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 在博客仓的文章目录里找该模块该篇章的路线文章，返回 slug（去 .md）；找不到返回空串 */
    private static String findRelatedPost(String module, String chapter2) {
        String prefix = module + "-roadmap-" + chapter2 + "-";
        for (String name : BlogRepoService.listNames(POSTS_DIR)) {
            if (name.startsWith(prefix) && name.endsWith(".md")) {
                return name.substring(0, name.length() - 3);
            }
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

    /** 插图在博客上的访问路径：站点 base 是 /blog，public/ 下的文件按同名路径对外 */
    private static String imageUrlOf(String slug, int n, String ext) {
        return "/blog/images/solutions/" + slug + "-" + n + "." + ext;
    }

    /** 解答在博客上的访问路径（文件名即路径段） */
    private static String blogUrlOf(String slug) {
        return "/blog/solutions/" + slug + "/";
    }

    /** 图片 mime → 文件扩展名（博客 public 下要带扩展名，浏览器才认） */
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
