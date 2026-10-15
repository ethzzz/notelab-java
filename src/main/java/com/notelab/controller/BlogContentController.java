package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.AppConfig;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.common.YamlText;
import com.notelab.dao.BlogStatDao;
import com.notelab.infra.QwenClient;
import com.notelab.infra.QwenKeys;
import com.notelab.service.BlogRepoService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 博客管理：B 端把文章写入「十年前端笔记」博客仓库（GitHub: ethzzz/blog）。
 *
 * <p>两个动作：
 * <ul>
 *   <li>POST /api/blog/generate —— 给提示词，调 notelab-java 现有的 QwenClient（Aliyun MaaS 网关）起草一篇
 *       完整 Markdown（含 YAML frontmatter），返回草稿供前端审阅。</li>
 *   <li>POST /api/blog/content —— 把文章写进博客仓库的 src/content/posts/：手动模式传结构化字段，
 *       AI 模式传 raw_markdown（frontmatter 已在草稿里），后端统一组装/规范化 frontmatter 后提交。</li>
 *   <li>GET  /api/blog/list   —— 列出本地 clone 已有的文章 slug（前端做最近文章清单用）。</li>
 * </ul>
 *
 * <p>仓库写入：本地维护一份博客源码 clone（BLOG_REPO_LOCAL，默认 /root/blog-src），先 pull --ff-only 同步，
 * 写文件后 git add/commit/push 回 GitHub。所有写操作都要求 B 端登录（AuthUtil）。
 *
 * <p>frontmatter 全部用单引号包裹标量（含 @ 开头的标签如 @types 也安全），避免 YAML 解析失败整站构建挂掉。
 */
@RestController
@RequestMapping("/api/blog")
public class BlogContentController {

    private static final Logger log = LoggerFactory.getLogger(BlogContentController.class);
    private static final DateTimeFormatter FMTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** 文章目录（仓内相对路径）。clone / pull / commit / push 统一走 {@link BlogRepoService}，别在本类里重写一套 */
    private static final String POSTS_DIR = "src/content/posts";

    private static Path postsDir() {
        return BlogRepoService.resolve(POSTS_DIR);
    }

    // ===================== AI 起草 =====================

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) String raw,
                                                        HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        String prompt = JsonSanitizer.str(node, "prompt");
        if (prompt.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "prompt 不能为空"));
        String category = JsonSanitizer.str(node, "category");
        String tags = JsonSanitizer.str(node, "tags");
        String persona = JsonSanitizer.str(node, "persona");
        if (persona.isEmpty()) persona = "资深前端工程师，工作十年，一直保持做笔记的习惯";
        int length = JsonSanitizer.clampInt(node.get("length"), 800, 6000, 2500);

        String system = buildSystemPrompt(category, tags, persona, length);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt));
        String key = QwenKeys.candidates().isEmpty() ? "" : QwenKeys.candidates().get(0);
        String markdown;
        try {
            markdown = QwenClient.complete(AppConfig.qwenModel(), messages, key, 120);
        } catch (Exception e) {
            log.warn("博客 AI 起草失败", e);
            return ResponseEntity.status(502).body(Map.of("error", "AI 生成失败：" + e.getMessage()));
        }
        if (markdown == null || markdown.isBlank()) {
            return ResponseEntity.status(502).body(Map.of("error", "AI 返回为空"));
        }
        // 去掉模型可能夹带的 ```markdown 围栏，保证落库即合法 .md
        markdown = stripFence(markdown);
        return ResponseEntity.ok(Map.of("ok", true, "markdown", markdown));
    }

    // ===================== 写入博客仓库 =====================

    @PostMapping("/content")
    public ResponseEntity<Map<String, Object>> content(@RequestBody(required = false) String raw,
                                                       HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        try {
            Map<String, Object> r = writeArticle(node);
            // 记一条后台改动（admin「文章记录」页展示）。
            // ⚠️ 只记**经过这个接口**的写入：博客仓的 md 同样可以直接编辑、提交（日常就是这么维护的），
            //    那些改动不会出现在这张表里。口径是「后台记录」，不是「仓库全历史」——
            //    要全覆盖得改读 git log，那是另一个取舍（见 BlogStatDao 的注释）。
            Map<String, Object> me = AuthUtil.user(request);
            if (me != null) {
                BlogStatDao.log(String.valueOf(r.get("slug")), "create",
                        String.valueOf(r.getOrDefault("title", "")),
                        Boolean.TRUE.equals(r.get("draft"))
                                ? "经「文章起草」写入（草稿）" : "经「文章起草」写入（已发布）",
                        AuthUtil.userId(me), String.valueOf(me.get("username")));
            }
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("写入博客失败", e);
            return ResponseEntity.status(500).body(Map.of("error", "写入博客失败：" + e.getMessage()));
        }
    }

    // ===================== 列表 =====================

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String name : BlogRepoService.listNames(POSTS_DIR)) {
            if (name.endsWith(".md")) items.add(Map.of("slug", name.replaceAll("\\.md$", "")));
            if (items.size() >= 200) break;
        }
        return ResponseEntity.ok(Map.of("ok", true, "items", items));
    }

    // ===================== 文章记录（admin「文章记录」页） =====================

    /**
     * 博客文章清单 + 统计：每篇的标题 / 分类 / 发布日 / 草稿态 + 浏览数 + 最后一次后台改动。
     *
     * <p>数据来自两处：**文章本身**（博客仓的 md，见 {@link #scanArticles()}）与
     * **统计**（blog_article_stat 的浏览数、blog_article_log 的改动记录）。
     * 之所以在 Java 侧拼而不是让前端分别取：前端要按浏览数排序/筛选，拿两份数据自己 join 会很难受。
     */
    @GetMapping("/articles")
    public ResponseEntity<Map<String, Object>> articles(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        List<Map<String, Object>> arts = scanArticles();
        List<String> slugs = arts.stream().map(a -> String.valueOf(a.get("slug"))).toList();
        // 浏览数与最后改动都是**一次查完**再在内存里拼：逐篇查 = 202 次往返
        Map<String, Long> views = BlogStatDao.viewsOf(slugs);
        Map<String, Map<String, Object>> lastLogs = BlogStatDao.lastLogs();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> a : arts) {
            String slug = String.valueOf(a.get("slug"));
            Map<String, Object> one = new LinkedHashMap<>(a);
            one.put("views", views.getOrDefault(slug, 0L));
            one.put("lastChange", lastLogs.get(slug));
            items.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", items.size());
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    /** 单篇详情：文章元信息 + 浏览数 + 后台改动记录 */
    @GetMapping("/articles/{slug}")
    public ResponseEntity<Map<String, Object>> article(@PathVariable String slug, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> found = null;
        for (Map<String, Object> a : scanArticles()) {
            if (slug.equals(a.get("slug"))) { found = a; break; }
        }
        if (found == null) return ResponseEntity.status(404).body(Map.of("error", "没有这篇文章"));
        Map<String, Object> out = new LinkedHashMap<>(found);
        out.put("views", BlogStatDao.viewsOf(slug));
        out.put("logs", BlogStatDao.logsOf(slug, 50));
        return ResponseEntity.ok(out);
    }

    // ===================== 实现 =====================

    /** 文章清单缓存：202 篇 md 全读一遍是毫秒级，但没必要每个请求都做 */
    private static final long ART_CACHE_MS = 5 * 60_000L;
    private static volatile List<Map<String, Object>> artCache = List.of();
    private static volatile long artCacheAt = 0;

    /**
     * 扫博客仓的 posts 目录，解析每篇的 slug 与 frontmatter。
     *
     * <p>⚠️ 缓存 5 分钟：这是**统计视图**，不是内容编辑器 —— 刚在仓库里改完标题要等一会儿才反映出来。
     * 读失败时沿用旧缓存（清空等于列表突然变空，比陈旧更难排查）。
     */
    private static List<Map<String, Object>> scanArticles() {
        long now = System.currentTimeMillis();
        if (now - artCacheAt <= ART_CACHE_MS && !artCache.isEmpty()) return artCache;

        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : BlogRepoService.listNames(POSTS_DIR)) {
            if (!name.endsWith(".md")) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            String slug = name.substring(0, name.length() - 3);
            one.put("slug", slug);
            one.put("title", "");
            one.put("category", "");
            one.put("published", "");
            one.put("draft", false);
            try {
                String text = Files.readString(
                        BlogRepoService.resolve(POSTS_DIR + "/" + name), StandardCharsets.UTF_8);
                Frontmatter fm = parseFrontmatter(text);
                one.put("title", fm.title);
                one.put("category", fm.category);
                one.put("draft", fm.draft);
                // published 不在 Frontmatter 的字段里（写作时用不到），列表页要显示，单独抠一行
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("^published:\\s*(.+)$",
                                java.util.regex.Pattern.MULTILINE).matcher(text);
                if (m.find()) one.put("published", m.group(1).trim().replaceAll("^['\"]|['\"]$", ""));
            } catch (Exception e) {
                log.warn("读取文章 {} 的 frontmatter 失败：{}", name, e.toString());
            }
            out.add(one);
        }
        // 默认按发布日期倒序（最近写的在前），日期相同按 slug —— 顺序稳定，翻页不会乱跳
        out.sort((a, b) -> {
            int c = String.valueOf(b.get("published")).compareTo(String.valueOf(a.get("published")));
            return c != 0 ? c : String.valueOf(a.get("slug")).compareTo(String.valueOf(b.get("slug")));
        });
        if (!out.isEmpty()) {
            artCache = List.copyOf(out);
            artCacheAt = now;
        }
        return artCache.isEmpty() ? out : artCache;
    }

    private Map<String, Object> writeArticle(JsonNode node) throws Exception {
        String rawMd = JsonSanitizer.str(node, "raw_markdown");
        String title, category, description, tagsJoined;
        boolean draft;
        String body;

        if (!rawMd.isEmpty()) {
            // AI 确认发布：草稿已带 frontmatter，解析后覆盖 draft 为用户选择
            Frontmatter fm = parseFrontmatter(rawMd);
            title = fm.title.isEmpty() ? JsonSanitizer.str(node, "title") : fm.title;
            category = fm.category.isEmpty() ? JsonSanitizer.str(node, "category") : fm.category;
            description = fm.description.isEmpty() ? JsonSanitizer.str(node, "description") : fm.description;
            tagsJoined = fm.tags.isEmpty() ? JsonSanitizer.str(node, "tags") : fm.tags;
            draft = node.has("draft") ? node.get("draft").asBoolean() : fm.draft;
            body = fm.body;
        } else {
            title = JsonSanitizer.str(node, "title");
            category = JsonSanitizer.str(node, "category");
            description = JsonSanitizer.str(node, "description");
            tagsJoined = JsonSanitizer.str(node, "tags");
            draft = node.has("draft") ? node.get("draft").asBoolean() : true;
            body = JsonSanitizer.str(node, "content_md");
        }
        if (title.isEmpty()) throw new IllegalArgumentException("title 不能为空");
        if (body.isEmpty()) throw new IllegalArgumentException("正文不能为空");

        String slug = slugify(JsonSanitizer.str(node, "slug"));
        if (slug.isEmpty()) slug = slugify(title);
        if (slug.isEmpty()) slug = "post";

        BlogRepoService.ensure();

        String fileName = slug + ".md";
        int i = 1;
        while (BlogRepoService.exists(POSTS_DIR + "/" + fileName)) {
            fileName = slug + "-" + i + ".md";
            i++;
        }

        String fmText = buildFrontmatter(title, category, description, tagsJoined, draft);
        String rel = POSTS_DIR + "/" + fileName;
        BlogRepoService.writeText(rel, fmText + body + "\n");

        String commit = BlogRepoService.commitPush(
                "blog: add " + (title.length() > 40 ? title.substring(0, 40) : title), List.of(rel));
        return Map.of(
                "ok", true,
                "slug", fileName.replaceAll("\\.md$", ""),
                "title", title,
                "path", rel,
                "commit", commit,
                "draft", draft);
    }

    // clone / pull / commit / push 已收敛到 {@link BlogRepoService}（练习实践发布共用同一套）。
    // 之前这里各写一份，两处对「clone 放哪、push 被拒怎么办」的处理并不一致 —— 这类差异只在生产暴露。

    // ===================== 文本/工具 =====================

    private String buildFrontmatter(String title, String category, String tagsJoined,
                                    String description, boolean draft) {
        List<String> tags = java.util.Arrays.stream(tagsJoined.split(","))
                .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(YamlText.quote(title)).append("\n");
        sb.append("published: ").append(OffsetDateTime.now().format(FMTER)).append("\n");
        sb.append("description: ").append(YamlText.quote(description)).append("\n");
        if (tags.isEmpty()) {
            sb.append("tags: []\n");
        } else {
            sb.append("tags: [")
                    .append(tags.stream().map(YamlText::quote).collect(Collectors.joining(", ")))
                    .append("]\n");
        }
        sb.append("category: ").append(YamlText.quote(category.isEmpty() ? "未分类" : category)).append("\n");
        sb.append("draft: ").append(draft ? "true" : "false").append("\n");
        sb.append("---\n");
        return sb.toString();
    }

    /** 解析 --- 包裹的 frontmatter；解析不出则整段当作正文 */
    private static Frontmatter parseFrontmatter(String md) {
        Frontmatter fm = new Frontmatter();
        if (md.startsWith("---")) {
            int end = md.indexOf("\n---", 3);
            if (end > 0) {
                String head = md.substring(3, end).strip();
                fm.body = md.substring(end + 4);
                for (String line : head.split("\n")) {
                    int c = line.indexOf(':');
                    if (c < 0) continue;
                    String k = line.substring(0, c).trim();
                    String v = line.substring(c + 1).trim();
                    switch (k) {
                        case "title" -> fm.title = unquote(v);
                        case "description" -> fm.description = unquote(v);
                        case "category" -> fm.category = unquote(v);
                        case "draft" -> fm.draft = v.equalsIgnoreCase("true") || v.equals("1");
                        case "tags" -> fm.tags = v.replaceAll("[\\[\\]'\"]", "").trim();
                        default -> { /* 忽略未知字段 */ }
                    }
                }
                return fm;
            }
        }
        fm.body = md;
        return fm;
    }

    // 单引号 YAML 标量已收敛到 common/YamlText.quote（练习实践发布共用同一口径）

    private static String unquote(String v) {
        if (v == null) return "";
        return v.strip().replaceAll("^['\"]|['\"]$", "");
    }

    /** 中文/字母/数字保留，其余变 -；折叠多 -、去首尾 - */
    private String slugify(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.trim().toLowerCase().toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || (ch >= '\u4e00' && ch <= '\u9fa5') || ch == '-') {
                sb.append(ch);
            } else if (ch == ' ' || ch == '_') {
                sb.append('-');
            } else {
                sb.append('-');
            }
        }
        return sb.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }

    private String buildSystemPrompt(String category, String tags, String persona, int length) {
        return "你是一位" + persona + "。请按以下要求写一篇技术博客文章，直接输出完整的 Markdown（含 YAML frontmatter）。\n"
                + "要求：\n"
                + "- 分类(category)：[" + (category.isEmpty() ? "未分类" : category) + "]\n"
                + "- 标签(tags)：[" + (tags.isEmpty() ? "技术笔记" : tags) + "]\n"
                + "- 篇幅：约 " + length + " 字\n"
                + "- 风格：第一人称、像工作笔记，结合真实踩坑经历与解决方案，适当给出可运行代码示例（用 ``` 围栏）\n"
                + "- frontmatter 字段必须包含：title、published（用占位 ISO 时间即可）、description、tags(数组)、category、draft(false)\n"
                + "- 只输出 Markdown 正文（含 --- 包裹的 frontmatter），不要任何额外解释或 ``` 围栏。";
    }

    /** 去掉模型可能夹带的 ```markdown ... ``` 围栏 */
    private String stripFence(String md) {
        String t = md.strip();
        if (t.startsWith("```")) {
            int firstNL = t.indexOf('\n');
            if (firstNL > 0) {
                int last = t.lastIndexOf("```");
                if (last > firstNL) t = t.substring(firstNL + 1, last).strip();
            }
        }
        return t;
    }

    // ===================== 进程执行 =====================
    // 已移除：run / runCapture 收敛到 BlogRepoService.run（那里补了超时与非交互 SSH 环境变量）

    /** frontmatter 解析结果 */
    private static final class Frontmatter {
        String title = "";
        String category = "";
        String description = "";
        String tags = "";
        boolean draft = false;
        String body = "";
    }
}
