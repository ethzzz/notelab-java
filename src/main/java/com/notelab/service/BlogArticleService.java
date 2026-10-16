package com.notelab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.YamlText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 博客**文章**（{@code src/content/posts/*.md}）的读、写、校验与计划 —— 唯一收敛点。
 *
 * <p>为什么要从 controller 里抽出来：原来这些事（frontmatter 解析/拼装、slug 规则、去重、
 * 建文件）全塞在 {@code BlogContentController} 一个类里，于是「预检会写成什么」与「真正写下去什么」
 * 根本没法共用一份实现 —— 而练习实践（{@code PracticeController.buildPlan}）已经吃过这个亏，
 * 那里的结论写得很清楚：<b>预检与真发必须共用同一个构建函数，分开写两份必然漂移</b>。
 * 这里照同一条规矩办：{@link #plan} 产出计划，{@link #plan} 的输出既给干跑接口看，也是写入的唯一输入。
 *
 * <h3>四条必须守住的约定</h3>
 * <ol>
 *   <li><b>frontmatter 一律单引号</b>（{@link YamlText#quote}）：{@code @types}、{@code C:\path} 这类值
 *       用双引号或裸写都可能让 YAML 解析失败，而失败发生在**博客 CI 的构建期** ——
 *       现象是「push 成功了但站点一直没更新」，排查极绕。</li>
 *   <li><b>更新走「行内改写」而不是重建 frontmatter</b>（{@link #patchHead}）：重建会把
 *       {@code image} / {@code lang} / {@code updated} 这些本类不管理的键**静默抹掉**。
 *       同时 {@code published} 默认**保持不变** —— 改一次正文就让文章跳回时间线顶部是不对的。</li>
 *   <li><b>读-改-写要自己串行化</b>：{@link BlogRepoService} 的锁只保护单条 git 命令，
 *       保护不了「读文件 → 改 → 写回」这一整段，两个并发编辑会互相覆盖。</li>
 *   <li><b>写入后必须 {@link #invalidate()} 清缓存</b>：元信息缓存 5 分钟，
 *       不清的话后台刚改完、列表里还是旧的，看起来像「点了没生效」。</li>
 * </ol>
 *
 * <p>⚠️ 与练习解答（{@code solutions}）不是一回事：解答已经改成「数据库为真相 + 博客运行时读接口」，
 * 本类还是「仓库里的 md 为真相 + 构建期产出」—— 因为 posts 要撑首页分页/归档/标签/RSS/pagefind 全文搜索，
 * 那些都是构建期产物。发布因此不是秒级，而是「等一次构建」（后台本地构建实测 80 秒）。
 */
public final class BlogArticleService {

    private static final Logger log = LoggerFactory.getLogger(BlogArticleService.class);

    /** 文章目录（仓内相对路径）。clone / pull / commit / push 统一走 {@link BlogRepoService} */
    public static final String POSTS_DIR = "src/content/posts";

    /** frontmatter 里的发布时间形状：{@code 2026-10-16T20:30:00+08:00} */
    private static final DateTimeFormatter FMTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** slug 允许：小写字母 / 数字 / 中文 / 连字符。中文放行是因为仓里既有 slug 就那么写的 */
    private static final Pattern SLUG_RE = Pattern.compile("[a-z0-9\\u4e00-\\u9fa5-]{1,80}");

    /** {@code published} 手工指定时的形状（日期或 ISO 时间），其余一律拒绝而不是猜 */
    private static final Pattern DATE_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ].*)?");

    private static final int MAX_TITLE = 200;
    private static final int MAX_DESC = 500;
    /** 单篇正文上限：博客是静态站，一篇塞几百 KB 会把整站构建拖慢；超了**报错**而不是静默截断 */
    private static final int MAX_BODY = 200_000;
    private static final int MAX_TAGS = 12;
    private static final int MAX_TAG_LEN = 40;

    /**
     * 列表接口的硬上限。
     * ⚠️ 原来写的是 200，而仓里**已经 202 篇** —— 那是个静默截断（「最近文章」永远少两篇，
     * 而且随着发文只会越来越不准）。这里抬到 5000，仍然留一个上限防止目录被塞爆时打挂接口。
     */
    private static final int LIST_LIMIT = 5000;

    /** 元信息缓存：202 个 md 全读一遍是毫秒级，但没必要每个请求都做 */
    private static final long CACHE_MS = 5 * 60_000L;
    private static volatile List<Article> cache = List.of();
    private static volatile long cacheAt = 0L;

    /** 读-改-写串行化（见类注释第 3 条）。所有会碰文件内容的写操作都要进这把锁 */
    private static final Object WRITE_LOCK = new Object();

    private BlogArticleService() {}

    // ==================================================================================
    // 数据结构
    // ==================================================================================

    /**
     * 一篇文章。
     *
     * @param body 正文（不含 frontmatter）。列表缓存里这个字段是空串（省内存，列表用不到正文）
     */
    public record Article(String slug, String title, String description, List<String> tags,
                          String category, boolean draft, String published, String body) {

        /** 展示用：把元信息摊平成 Map（字段名与老接口保持一致，前端不用改） */
        public Map<String, Object> toMeta() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", slug);
            m.put("title", title);
            m.put("description", description);
            m.put("tags", tags);
            m.put("category", category);
            m.put("published", published);
            m.put("draft", draft);
            return m;
        }
    }

    /** frontmatter 的解析结果（内部用） */
    private record Parsed(String title, String description, List<String> tags, String category,
                          boolean draft, String published, String body) {}

    /**
     * 一次写入计划 —— 干跑（{@code POST /api/blog/plan}）与真写（{@code POST /api/blog/content}、
     * {@code PUT /api/blog/articles/{slug}}）**共用**这一个产物，所以「预检看到的」就是「写下去的」。
     *
     * @param mode        {@code create} / {@code update}
     * @param slug        目标 slug（= 文件名，= URL 的一段）
     * @param oldSlug     更新前的 slug；新建时为空串
     * @param oldRel      更新前的仓内相对路径；改名或草稿态回写时要删/覆盖
     * @param rel         目标的仓内相对路径
     * @param rawOriginal 更新时读到的原始文件全文（用来保留本类不管理的 frontmatter 键）；新建时为空串
     */
    public record Plan(String mode, String slug, String oldSlug, String oldRel, String rel,
                       String title, String description, List<String> tags, String category,
                       boolean draft, String published, String body, String rawOriginal,
                       List<String> blockers, List<String> warnings) {

        public boolean ready() { return blockers.isEmpty(); }

        public boolean renamed() { return !oldSlug.isEmpty() && !oldSlug.equals(slug); }

        /** 最终写进文件的内容 = frontmatter + 正文 + 结尾换行 */
        public String fileText() {
            String head = rawOriginal.isBlank() ? null : patchHead(rawOriginal, managedFields());
            if (head == null) head = canonicalFrontmatter();
            return head + "\n" + body + "\n";
        }

        /**
         * 本类管理的 frontmatter 键（值含冒号后的前导空格，与仓内既有文件风格一致）。
         *
         * <p>{@code published} 也在里面，但值是按「更新时默认沿用原值」算好的
         * （见 {@code plan()} 里那段注释）—— 所以行内改写它只是把同一行重写一遍，
         * 不会在没要求改日期时把文章顶上时间线。
         */
        private Map<String, String> managedFields() {
            Map<String, String> want = new LinkedHashMap<>();
            want.put("title", " " + YamlText.quote(title));
            want.put("published", " " + published);
            want.put("description", " " + YamlText.quote(description));
            want.put("tags", " " + tagsInline(tags));
            want.put("category", " " + YamlText.quote(category.isEmpty() ? "未分类" : category));
            want.put("draft", " " + draft);
            return want;
        }

        /** 新建时的规范 frontmatter（键顺序与仓内既有文章一致） */
        private String canonicalFrontmatter() {
            StringBuilder sb = new StringBuilder("---\n");
            sb.append("title: ").append(YamlText.quote(title)).append('\n');
            sb.append("published: ").append(published).append('\n');
            sb.append("description: ").append(YamlText.quote(description)).append('\n');
            sb.append("tags: ").append(tagsInline(tags)).append('\n');
            sb.append("category: ").append(YamlText.quote(category.isEmpty() ? "未分类" : category)).append('\n');
            sb.append("draft: ").append(draft).append('\n');
            sb.append("---");
            return sb.toString();
        }

        public String commitMessage() {
            if ("update".equals(mode)) {
                return renamed() ? "blog: rename " + oldSlug + " -> " + slug : "blog: update " + slug;
            }
            return "blog: add " + (title.length() > 40 ? title.substring(0, 40) : title);
        }

        /** 干跑接口的响应体（前端二次确认要看到「文件名 = URL」和会写什么） */
        public Map<String, Object> toBody() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode);
            m.put("slug", slug);
            m.put("url", "/blog/posts/" + slug + "/");
            m.put("path", rel);
            m.put("oldSlug", oldSlug);
            m.put("renamed", renamed());
            m.put("title", title);
            m.put("description", description);
            m.put("tags", tags);
            m.put("category", category.isEmpty() ? "未分类" : category);
            m.put("draft", draft);
            m.put("published", published);
            m.put("chars", body.length());
            m.put("commitMessage", commitMessage());
            m.put("blockers", blockers);
            m.put("warnings", warnings);
            return m;
        }
    }

    // ==================================================================================
    // 读
    // ==================================================================================

    /**
     * 文章清单（元信息，按发布日期倒序）。缓存 {@link #CACHE_MS}。
     *
     * <p>⚠️ 读目录失败时沿用旧缓存：清空等于列表突然变空，比「陈旧」更难排查。
     */
    public static List<Article> list() {
        long now = System.currentTimeMillis();
        if (now - cacheAt <= CACHE_MS && !cache.isEmpty()) return cache;
        List<Article> scanned = scan();
        if (!scanned.isEmpty()) {
            cache = List.copyOf(scanned);
            cacheAt = now;
        }
        return cache.isEmpty() ? scanned : cache;
    }

    /** 清元信息缓存 —— 任何写操作之后都要调（见类注释第 4 条） */
    public static void invalidate() {
        cacheAt = 0L;
    }

    /** 单篇（含正文）。不存在返回 {@code null}。 */
    public static Article read(String slug) {
        String rel = relOf(slug);
        if (!BlogRepoService.exists(rel)) return null;
        try {
            String raw = Files.readString(BlogRepoService.resolve(rel), StandardCharsets.UTF_8);
            Parsed p = parse(raw);
            return new Article(slug, p.title(), p.description(), p.tags(), p.category(),
                    p.draft(), p.published(), p.body());
        } catch (Exception e) {
            log.warn("读取文章 {} 失败：{}", slug, e.toString());
            return null;
        }
    }

    /** 单篇的**原始文件全文**（含 frontmatter 原样）。不存在返回 {@code null}。 */
    public static String rawOf(String slug) {
        String rel = relOf(slug);
        if (!BlogRepoService.exists(rel)) return null;
        try {
            return Files.readString(BlogRepoService.resolve(rel), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取文章原文 {} 失败：{}", slug, e.toString());
            return null;
        }
    }

    /**
     * 分类 / 标签候选 —— 取自**仓内现有文章**，而不是另立一份白名单常量。
     *
     * <p>为什么必须有这个接口：分类与标签原来靠手输，而博客的分类是 frontmatter 里的自由字符串
     * （{@code category: notelab} 与 {@code notelab 笔记} 是两个分类）。散着写的结果是归档页上
     * 同一类内容裂成好几组；候选值来自现状，至少能让人「照着已有的选」。
     */
    public static Map<String, Object> options() {
        Map<String, Integer> cats = new TreeMap<>();
        Map<String, Integer> tags = new TreeMap<>();
        int drafts = 0;
        for (Article a : list()) {
            if (a.draft()) drafts++;
            String c = a.category().isBlank() ? "未分类" : a.category();
            cats.merge(c, 1, Integer::sum);
            for (String t : a.tags()) tags.merge(t, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", list().size());
        out.put("drafts", drafts);
        out.put("categories", ranked(cats));
        out.put("tags", ranked(tags));
        return out;
    }

    /** 计数倒序 → 名称升序，最多 40 条（候选是给人挑的，不是全量导出） */
    private static List<Map<String, Object>> ranked(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        es.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> e.getValue()).reversed()
                .thenComparing(e -> e.getKey()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : es) {
            if (out.size() >= 40) break;
            out.add(Map.of("name", e.getKey(), "count", e.getValue()));
        }
        return out;
    }

    // ==================================================================================
    // 计划（干跑与真写共用）
    // ==================================================================================

    /**
     * 把请求体变成一份写入计划。**不碰任何文件**（除了读现状），所以可以放心当干跑用。
     *
     * <p>入参形状：
     * <ul>
     *   <li>{@code mode}：{@code create}（默认）/ {@code update}。update 必须给 {@code slug}（现有文章的 slug），
     *       改名再给 {@code newSlug}。</li>
     *   <li>结构化字段：{@code title} / {@code slug} / {@code category} / {@code tags}（数组或逗号串）/
     *       {@code description} / {@code content_md} / {@code draft} / {@code published}（可选）</li>
     *   <li>或 {@code raw_markdown}：AI 起草返回的整篇 Markdown（含 frontmatter），解析后覆盖上面的字段</li>
     * </ul>
     *
     * <p>⚠️ controller 给 {@code PUT /articles/{slug}} 用时会先把路径里的 slug 写进 body 再调这里
     * —— 这样「干跑」与「真写」判定的是同一份输入，不会出现两套 slug 规则。
     */
    public static Plan plan(JsonNode node) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String mode = JsonSanitizer.str(node, "mode");
        if (!"update".equals(mode)) mode = "create";

        String slugIn = slugify(JsonSanitizer.str(node, "slug"));
        String newSlugIn = slugify(JsonSanitizer.str(node, "newSlug"));

        // AI 起草的那份草稿里 frontmatter 已经写好了：先解析出来，再让显式字段覆盖它
        Parsed fromRaw = null;
        String rawMd = JsonSanitizer.str(node, "raw_markdown");
        if (!rawMd.isEmpty()) fromRaw = parse(rawMd);

        String title = pick(JsonSanitizer.str(node, "title"), fromRaw == null ? "" : fromRaw.title());
        String description = pick(JsonSanitizer.str(node, "description"), fromRaw == null ? "" : fromRaw.description());
        String category = pick(JsonSanitizer.str(node, "category"), fromRaw == null ? "" : fromRaw.category());
        List<String> tags = tagsOf(node, fromRaw);
        String body = pickBody(node, fromRaw);

        title = JsonSanitizer.truncate(title, MAX_TITLE);
        description = JsonSanitizer.truncate(description, MAX_DESC);

        if (title.isEmpty()) blockers.add("标题不能为空");
        if (body.isBlank()) blockers.add("正文不能为空");
        if (body.length() > MAX_BODY) {
            blockers.add("正文过长（" + body.length() + " 字符，上限 " + MAX_BODY + "）");
        }
        if (tags.size() > MAX_TAGS) {
            warnings.add("标签超过 " + MAX_TAGS + " 个，只保留前 " + MAX_TAGS + " 个");
            tags = new ArrayList<>(tags.subList(0, MAX_TAGS));
        }
        if (description.isEmpty()) warnings.add("描述为空：列表页与分享卡片上会没有摘要");
        if (category.isEmpty()) warnings.add("分类为空：会写成「未分类」，进不了按分类策展的聚合页");

        boolean draft = node.has("draft") ? node.get("draft").asBoolean()
                : (fromRaw != null && fromRaw.draft());
        if (!node.has("draft") && fromRaw == null) draft = true;   // 默认先存草稿（沿用原行为）

        // ---------- slug 与文件路径 ----------
        String oldSlug = "";
        String oldRel = "";
        String rawOriginal = "";
        String slug;
        if ("update".equals(mode)) {
            oldSlug = slugIn;
            if (oldSlug.isEmpty()) {
                blockers.add("更新模式必须给 slug（要改的是哪一篇）");
                slug = "";
            } else if (!BlogRepoService.exists(relOf(oldSlug))) {
                blockers.add("文章不存在：" + oldSlug);
                slug = oldSlug;
            } else {
                slug = newSlugIn.isEmpty() ? oldSlug : newSlugIn;
                oldRel = relOf(oldSlug);
                String raw = rawOf(oldSlug);
                rawOriginal = raw == null ? "" : raw;
                if (!slug.equals(oldSlug) && BlogRepoService.exists(relOf(slug))) {
                    blockers.add("slug 已被占用：" + slug + "（换一个，或先改那一篇）");
                }
            }
        } else {
            String derived = slugIn.isEmpty() ? slugify(title) : slugIn;
            if (derived.isEmpty()) derived = "post";
            if (!SLUG_RE.matcher(derived).matches()) {
                blockers.add("slug 只能用小写字母/数字/中文/连字符：" + derived);
            }
            slug = derived;
            if (BlogRepoService.exists(relOf(slug))) {
                if (!slugIn.isEmpty()) {
                    // 人是明确指定了 slug 的：静默改成 foo-1 会让人以为发布成功、其实 URL 不是他要的
                    blockers.add("slug 已存在：" + slug + "（想改这一篇请用编辑，想新发请换个 slug）");
                } else {
                    int i = 1;
                    while (BlogRepoService.exists(relOf(slug + "-" + i))) i++;
                    warnings.add("同名文件已存在，自动改用 " + slug + "-" + i);
                    slug = slug + "-" + i;
                }
            }
        }

        // ---------- published ----------
        // 更新时默认**保持原值**（改一次正文就让文章跳回时间线顶部是不对的）；
        // 想改日期必须显式传 published。
        String published = JsonSanitizer.str(node, "published");
        if (published.isEmpty()) {
            if (!rawOriginal.isEmpty()) {
                published = parse(rawOriginal).published();
            } else if (fromRaw != null && !fromRaw.published().isEmpty()) {
                published = fromRaw.published();
            }
        }
        if (published.isEmpty() || !DATE_RE.matcher(published).matches()) {
            if (!published.isEmpty()) warnings.add("published 格式不认识，已改用当前时间：" + published);
            published = OffsetDateTime.now().format(FMTER);
        }

        return new Plan(mode, slug, oldSlug, oldRel, slug.isEmpty() ? "" : relOf(slug),
                title, description, tags, category, draft, published, body.trim(), rawOriginal,
                List.copyOf(blockers), List.copyOf(warnings));
    }

    // ==================================================================================
    // 写
    // ==================================================================================

    /**
     * 落盘 + 提交推送。计划里还有 blocker 时**直接拒绝**（一行都不写）。
     *
     * <p>改名时先删旧文件再写新文件，两个路径一起 add —— 否则旧文件会留成孤儿（博客上就是同一篇两个 URL）。
     */
    public static Map<String, Object> apply(Plan plan) throws Exception {
        if (!plan.ready()) throw new IllegalArgumentException(String.join("；", plan.blockers()));
        synchronized (WRITE_LOCK) {
            BlogRepoService.ensure();
            List<String> paths = new ArrayList<>();
            if (plan.renamed()) {
                BlogRepoService.deleteIfExists(plan.oldRel());
                paths.add(plan.oldRel());
            }
            BlogRepoService.writeText(plan.rel(), plan.fileText());
            paths.add(plan.rel());
            String commit = BlogRepoService.commitPush(plan.commitMessage(), paths);
            invalidate();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("mode", plan.mode());
            out.put("slug", plan.slug());
            out.put("title", plan.title());
            out.put("path", plan.rel());
            out.put("commit", commit);
            out.put("draft", plan.draft());
            out.put("renamed", plan.renamed());
            out.put("oldSlug", plan.oldSlug());
            return out;
        }
    }

    /**
     * 只改草稿态（上线 / 下线），其余字段与正文**原样保留**。
     *
     * <p>为什么单独一个动作而不是走「更新」：更新的入参是整篇内容，而上下线只想动一个布尔值 ——
     * 走更新就得把正文原样传回去，中间任何一次复制失真都会改坏文章。行内改写只碰 draft 那一行。
     */
    public static Map<String, Object> setDraft(String slug, boolean draft) throws Exception {
        String rel = relOf(slug);
        synchronized (WRITE_LOCK) {
            String raw = rawOf(slug);
            if (raw == null) throw new NoSuchElementException("文章不存在：" + slug);
            String patched = patchHead(raw, Map.of("draft", " " + draft));
            if (patched == null) {
                throw new IllegalStateException("这篇文章没有 frontmatter，无法只改草稿态：" + slug);
            }
            String tail = raw.substring(raw.indexOf("\n---", 3) + 4);
            BlogRepoService.ensure();
            BlogRepoService.writeText(rel, patched + tail);
            String commit = BlogRepoService.commitPush(
                    (draft ? "blog: unpublish " : "blog: publish ") + slug, List.of(rel));
            invalidate();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("slug", slug);
            out.put("draft", draft);
            out.put("commit", commit);
            out.put("url", "/blog/posts/" + slug + "/");
            return out;
        }
    }

    /**
     * 删除一篇文章（点进 URL 会 404）。
     *
     * <p>⚠️ **只允许删草稿**：已发布文章可能已被收录/外链，一次误点就断链。想下线走
     * {@link #setDraft}(slug, true) —— 内容还在，地址不变，重新发布即恢复。
     */
    public static Map<String, Object> delete(String slug) throws Exception {
        String rel = relOf(slug);
        synchronized (WRITE_LOCK) {
            Article a = read(slug);
            if (a == null) throw new NoSuchElementException("文章不存在：" + slug);
            if (!a.draft()) {
                throw new IllegalStateException("这篇还在发布中，先下线（转草稿）再删：" + slug);
            }
            BlogRepoService.ensure();
            BlogRepoService.deleteIfExists(rel);
            String commit = BlogRepoService.commitPush("blog: delete " + slug, List.of(rel));
            invalidate();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("slug", slug);
            out.put("commit", commit);
            out.put("note", "已从博客仓删除；线上要等下一次构建生效");
            return out;
        }
    }

    // ==================================================================================
    // 内部：路径 / 解析 / 改写
    // ==================================================================================

    public static String relOf(String slug) {
        return POSTS_DIR + "/" + slug + ".md";
    }

    private static List<Article> scan() {
        List<Article> out = new ArrayList<>();
        for (String name : BlogRepoService.listNames(POSTS_DIR)) {
            if (!name.endsWith(".md")) continue;
            if (out.size() >= LIST_LIMIT) break;
            String slug = name.substring(0, name.length() - 3);
            try {
                String raw = Files.readString(BlogRepoService.resolve(POSTS_DIR + "/" + name),
                        StandardCharsets.UTF_8);
                Parsed p = parse(raw);
                out.add(new Article(slug, p.title(), p.description(), p.tags(), p.category(),
                        p.draft(), p.published(), ""));
            } catch (Exception e) {
                log.warn("读取文章 {} 的 frontmatter 失败：{}", name, e.toString());
                out.add(new Article(slug, "", "", List.of(), "", false, "", ""));
            }
        }
        // 发布日期倒序；同一天按 slug —— 顺序稳定，翻页不会乱跳
        out.sort(Comparator.comparing(Article::published).reversed()
                .thenComparing(Article::slug));
        return out;
    }

    /** 解析 {@code ---} 包裹的 frontmatter；没有或解析不出则整段当正文 */
    private static Parsed parse(String raw) {
        if (raw == null) return new Parsed("", "", List.of(), "", false, "", "");
        if (!raw.startsWith("---")) return new Parsed("", "", List.of(), "", false, "", raw.strip());
        int end = raw.indexOf("\n---", 3);
        if (end < 0) return new Parsed("", "", List.of(), "", false, "", raw.strip());
        String head = raw.substring(3, end);
        String body = raw.substring(end + 4).strip();
        String title = "";
        String description = "";
        String category = "";
        String published = "";
        boolean draft = false;
        List<String> tags = List.of();
        for (String line : head.split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim();
            String v = line.substring(c + 1).trim();
            switch (k) {
                case "title" -> title = unquote(v);
                case "description" -> description = unquote(v);
                case "category" -> category = unquote(v);
                case "published" -> published = unquote(v);
                case "draft" -> draft = v.equalsIgnoreCase("true") || v.equals("1");
                case "tags" -> tags = splitTags(v);
                default -> { /* 本类不管理的键（image / lang / updated …）在改写时原样保留 */ }
            }
        }
        return new Parsed(title, description, tags, category, draft, published, body);
    }

    /**
     * 行内改写 frontmatter：**只动 {@code want} 里给的键**，其余行（含未知键、含 published）原样保留；
     * 缺失的键追加在末尾。
     *
     * @return 新的 head（含 {@code ---} 两个定界行）；文件没有 frontmatter 时返回 {@code null}，
     *         由调用方决定是回退到重建还是报错
     */
    private static String patchHead(String raw, Map<String, String> want) {
        int end = raw.startsWith("---") ? raw.indexOf("\n---", 3) : -1;
        if (end < 0) return null;
        List<String> lines = new ArrayList<>();
        for (String l : raw.substring(3, end).split("\n", -1)) {
            if (!l.isBlank()) lines.add(l);
        }
        Set<String> written = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            int c = line.indexOf(':');
            String k = c < 0 ? "" : line.substring(0, c).trim();
            if (want.containsKey(k)) {
                out.add(k + ":" + want.get(k));
                written.add(k);
            } else {
                out.add(line);
            }
        }
        for (Map.Entry<String, String> e : want.entrySet()) {
            if (!written.contains(e.getKey())) out.add(e.getKey() + ":" + e.getValue());
        }
        return "---\n" + String.join("\n", out) + "\n---";
    }

    /** 中文/字母/数字保留，其余变 -；折叠多 -、去首尾 -（与之前的实现同口径，别改 —— 它决定 URL） */
    private static String slugify(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.trim().toLowerCase().toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || (ch >= '\u4e00' && ch <= '\u9fa5') || ch == '-') {
                sb.append(ch);
            } else {
                sb.append('-');
            }
        }
        return sb.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }

    private static String unquote(String v) {
        if (v == null) return "";
        return v.strip().replaceAll("^['\"]|['\"]$", "");
    }

    /** {@code [a, 'b', c]} → 列表（去引号、丢空项、单条限长） */
    private static List<String> splitTags(String v) {
        List<String> out = new ArrayList<>();
        for (String t : v.replaceAll("[\\[\\]]", "").split(",")) {
            String s = unquote(t.trim());
            if (s.isEmpty()) continue;
            out.add(JsonSanitizer.truncate(s, MAX_TAG_LEN));
            if (out.size() >= MAX_TAGS) break;
        }
        return out;
    }

    /** 标签数组 → {@code ['a', 'b']}。每条都过 {@link YamlText#quote}：{@code @types} 这类必须带引号 */
    private static String tagsInline(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "[]";
        List<String> out = new ArrayList<>();
        for (String t : tags) out.add(YamlText.quote(t));
        return "[" + String.join(", ", out) + "]";
    }

    /** tags 既接受数组也接受逗号串（老前端传的是逗号串，别为了统一把调用方改一遍） */
    private static List<String> tagsOf(JsonNode node, Parsed fromRaw) {
        JsonNode t = node == null ? null : node.get("tags");
        if (t != null && t.isArray()) {
            List<String> out = new ArrayList<>();
            for (String s : JsonSanitizer.strList(t, MAX_TAGS, MAX_TAG_LEN)) out.add(s);
            return out;
        }
        String s = JsonSanitizer.str(node, "tags");
        if (!s.isEmpty()) return splitTags(s);
        return fromRaw == null ? List.of() : fromRaw.tags();
    }

    private static String pickBody(JsonNode node, Parsed fromRaw) {
        String body = JsonSanitizer.str(node, "content_md");
        if (!body.isEmpty()) return body;
        return fromRaw == null ? "" : fromRaw.body();
    }

    private static String pick(String explicit, String fallback) {
        return explicit == null || explicit.isEmpty() ? (fallback == null ? "" : fallback) : explicit;
    }

}
