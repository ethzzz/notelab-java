package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import com.notelab.infra.QwenClient;
import com.notelab.infra.QwenKeys;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    private static String blogRepoUrl() {
        return AppConfig.get("BLOG_REPO_URL", "git@github.com:ethzzz/blog.git");
    }

    private static String blogLocal() {
        return AppConfig.get("BLOG_REPO_LOCAL", "/root/blog-src");
    }

    private static Path postsDir() {
        return Paths.get(blogLocal(), "src/content/posts");
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
        String prompt = str(node, "prompt");
        if (prompt.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "prompt 不能为空"));
        String category = str(node, "category");
        String tags = str(node, "tags");
        String persona = str(node, "persona");
        if (persona.isEmpty()) persona = "资深前端工程师，工作十年，一直保持做笔记的习惯";
        int length = clampInt(node.get("length"), 800, 6000, 2500);

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
        Path dir = postsDir();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                        .limit(200)
                        .forEach(p -> items.add(Map.of(
                                "slug", p.getFileName().toString().replaceAll("\\.md$", ""))));
            } catch (Exception ignore) {
                // 列不出也不阻塞
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "items", items));
    }

    // ===================== 实现 =====================

    private Map<String, Object> writeArticle(JsonNode node) throws Exception {
        String rawMd = str(node, "raw_markdown");
        String title, category, description, tagsJoined;
        boolean draft;
        String body;

        if (!rawMd.isEmpty()) {
            // AI 确认发布：草稿已带 frontmatter，解析后覆盖 draft 为用户选择
            Frontmatter fm = parseFrontmatter(rawMd);
            title = fm.title.isEmpty() ? str(node, "title") : fm.title;
            category = fm.category.isEmpty() ? str(node, "category") : fm.category;
            description = fm.description.isEmpty() ? str(node, "description") : fm.description;
            tagsJoined = fm.tags.isEmpty() ? str(node, "tags") : fm.tags;
            draft = node.has("draft") ? node.get("draft").asBoolean() : fm.draft;
            body = fm.body;
        } else {
            title = str(node, "title");
            category = str(node, "category");
            description = str(node, "description");
            tagsJoined = str(node, "tags");
            draft = node.has("draft") ? node.get("draft").asBoolean() : true;
            body = str(node, "content_md");
        }
        if (title.isEmpty()) throw new IllegalArgumentException("title 不能为空");
        if (body.isEmpty()) throw new IllegalArgumentException("正文不能为空");

        String slug = slugify(str(node, "slug"));
        if (slug.isEmpty()) slug = slugify(title);
        if (slug.isEmpty()) slug = "post";

        ensureClone();

        Path file = postsDir().resolve(slug + ".md");
        int i = 1;
        while (Files.exists(file)) {
            file = postsDir().resolve(slug + "-" + i + ".md");
            i++;
        }

        String fmText = buildFrontmatter(title, category, description, tagsJoined, draft);
        Files.writeString(file, fmText + body + "\n", StandardCharsets.UTF_8);

        String commit = gitCommitAndPush(file.getFileName().toString(), title);
        return Map.of(
                "ok", true,
                "slug", file.getFileName().toString().replaceAll("\\.md$", ""),
                "path", "src/content/posts/" + file.getFileName(),
                "commit", commit,
                "draft", draft);
    }

    /** 确保本地 clone 存在并与远端同步（ff-only，避免非快进 push 被拒） */
    private void ensureClone() throws Exception {
        Path local = Paths.get(blogLocal());
        if (!Files.isDirectory(local.resolve(".git"))) {
            run(List.of("git", "clone", blogRepoUrl(), blogLocal()));
        } else {
            run(List.of("git", "-C", blogLocal(), "pull", "--ff-only"));
        }
        Files.createDirectories(postsDir());
    }

    /** 提交并推送；无变更返回 "no-change" */
    private String gitCommitAndPush(String fileName, String title) throws Exception {
        run(List.of("git", "-C", blogLocal(), "add", "src/content/posts/" + fileName));
        String msg = "blog: add " + (title.length() > 40 ? title.substring(0, 40) : title);
        int code = run(List.of("git", "-C", blogLocal(), "commit", "-m", msg), false);
        if (code != 0) return "no-change";
        run(List.of("git", "-C", blogLocal(), "push"));
        try {
            return runCapture(List.of("git", "-C", blogLocal(), "rev-parse", "--short", "HEAD")).trim();
        } catch (Exception e) {
            return "pushed";
        }
    }

    // ===================== 文本/工具 =====================

    private String buildFrontmatter(String title, String category, String tagsJoined,
                                    String description, boolean draft) {
        List<String> tags = java.util.Arrays.stream(tagsJoined.split(","))
                .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yamlQuote(title)).append("\n");
        sb.append("published: ").append(OffsetDateTime.now().format(FMTER)).append("\n");
        sb.append("description: ").append(yamlQuote(description)).append("\n");
        if (tags.isEmpty()) {
            sb.append("tags: []\n");
        } else {
            sb.append("tags: [")
                    .append(tags.stream().map(this::yamlQuote).collect(Collectors.joining(", ")))
                    .append("]\n");
        }
        sb.append("category: ").append(yamlQuote(category.isEmpty() ? "未分类" : category)).append("\n");
        sb.append("draft: ").append(draft ? "true" : "false").append("\n");
        sb.append("---\n");
        return sb.toString();
    }

    /** 解析 --- 包裹的 frontmatter；解析不出则整段当作正文 */
    private Frontmatter parseFrontmatter(String md) {
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

    /** 单引号包裹标量；内部单引号翻倍（YAML 安全）；去换行 */
    private String yamlQuote(String s) {
        String t = s == null ? "" : s.replace("\r", "").replace("\n", " ").trim();
        return "'" + t.replace("'", "''") + "'";
    }

    private String unquote(String v) {
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

    private static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText().trim();
    }

    private static int clampInt(JsonNode n, int lo, int hi, int dft) {
        if (n == null || !n.isNumber()) return dft;
        return Math.max(lo, Math.min(hi, n.asInt()));
    }

    // ===================== 进程执行 =====================

    private int run(List<String> cmd) throws Exception {
        return run(cmd, true);
    }

    private int run(List<String> cmd, boolean fail) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = br.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        if (code != 0 && fail) {
            throw new RuntimeException("命令失败(" + code + "): " + String.join(" ", cmd) + "\n" + out);
        }
        return code;
    }

    private String runCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = br.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("命令失败(" + code + "): " + String.join(" ", cmd) + "\n" + out);
        }
        return out;
    }

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
