package com.notelab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 草稿**预览**的短时存储 —— 让后台能把「正在编辑、还没写进博客仓的文章」交给博客自己的渲染管线去呈现。
 *
 * <h3>为什么要有它（而不是在后台用 marked 渲染）</h3>
 * 用户 2026-10-16 明确要求「预览界面要和线上博客文章页一致」。线上的正文渲染是一条**构建期管线**
 * （expressive-code 代码高亮 / KaTeX / admonition 提示块 / 标题锚点 / 阅读时长），后台再怎么配
 * 也只是另一套库、另一种观感。所以预览的正文由**博客侧**渲染：博客仓有
 * {@code src/utils/runtime-markdown.ts}（把同一套插件在运行时原样再跑一遍，练习解答页已经在用），
 * 新路由 {@code /blog/preview/<token>} 复用它。本类解决的是最后一环：**那份还没落盘的草稿放哪**。
 *
 * <h3>三条设计约束</h3>
 * <ol>
 *   <li><b>只在内存里、只在单实例内</b>：pm2 是 fork 单实例，进程重启丢预览可以接受
 *       （用户刷新一次编辑器就会重新 POST 一份）。放库反而要建表、要清理、要担心多实例看不到。</li>
 *   <li><b>token 必须不可猜</b>：预览的是**未发布内容**，而博客的 SSR 进程是**匿名**去取的
 *       （它没有 B 端会话）。所以授权手段只剩「拿着 token 才能看」这一条 ——
 *       128 位随机 + 30 分钟 TTL + 只读。⚠️ 谁拿到 URL 谁就能看，token 只出现在后台界面里。</li>
 *   <li><b>有界</b>：每次 POST 一个 token，编辑器反复刷新会堆很多。超过 {@link #MAX_ENTRIES}
 *       先清过期、再清最旧的，绝不无限涨。</li>
 * </ol>
 */
public final class BlogPreviewService {

    private static final Logger log = LoggerFactory.getLogger(BlogPreviewService.class);

    /** 预览存活时间：够一个编辑回合（看、改、再看），又不至于把草稿长期挂在一个可猜风险的地址上 */
    private static final long TTL_MS = 30 * 60_000L;

    /** 条数上限：超出先清过期的，再按创建时间清最旧 */
    private static final int MAX_ENTRIES = 200;

    private static final SecureRandom RND = new SecureRandom();
    private static final Map<String, Entry> STORE = new ConcurrentHashMap<>();

    private BlogPreviewService() {}

    /**
     * 一份待预览的内容。
     *
     * @param kind        {@code post}（博客文章）或 {@code solution}（练习解答）—— 博客侧按它选布局，
     *                    也对得上「token 不能跨类型乱用」（拿文章 token 去解答预览路由会 404）
     * @param difficulty  仅解答用（简单/中等/困难），文章留空
     * @param relatedPost 仅解答用：关联的路线文章 slug（解答页据此显示「返回原题」）
     * @param body        正文 Markdown（**不含** frontmatter —— frontmatter 的字段已经拆成 title/tags/... 了）
     */
    public record Entry(long ownerUid, long createdAt, String kind, String slug, String title, String description,
                        List<String> tags, String category, boolean draft, String published,
                        String difficulty, String relatedPost, String body) {}

    /**
     * 存一份草稿并返回 token（博客文章那条路）。
     *
     * <p>**刻意不复用** {@code BlogArticleService.plan()}：那是「要写进仓库」的计划，
     * 会因为「标题为空 / slug 撞名 / 正文为空」直接给 blocker —— 而预览恰恰要能看**半成品**
     * （标题还没写完就想看看排版），也不该因为 slug 还没定就不给预览。
     */
    public static Map<String, Object> put(long ownerUid, JsonNode node) {
        String raw = JsonSanitizer.str(node, "raw_markdown");
        String body = JsonSanitizer.str(node, "content_md");
        String title = JsonSanitizer.str(node, "title");
        String description = JsonSanitizer.str(node, "description");
        String category = JsonSanitizer.str(node, "category");
        String published = JsonSanitizer.str(node, "published");
        String slug = JsonSanitizer.str(node, "slug");
        List<String> tags = tagsOf(node);

        // AI 起草那条路传的是整篇 Markdown（含 frontmatter）：拆出来，字段以显式传的为准
        if (!raw.isEmpty()) {
            String fmTitle = frontmatterValue(raw, "title");
            if (title.isEmpty()) title = fmTitle;
            if (description.isEmpty()) description = frontmatterValue(raw, "description");
            if (category.isEmpty()) category = frontmatterValue(raw, "category");
            if (published.isEmpty()) published = frontmatterValue(raw, "published");
            if (tags.isEmpty()) tags = tagsOf(frontmatterValue(raw, "tags"));
            if (body.isEmpty()) body = stripFrontmatter(raw);
        }

        Entry e = new Entry(ownerUid, System.currentTimeMillis(), "post", slug,
                title.isEmpty() ? "（未填标题）" : title,
                description, tags, category,
                !node.has("draft") || node.get("draft").asBoolean(),
                published, "", "", body);
        return store(e, "/blog/preview/");
    }

    /**
     * 存一份**练习解答**的预览（博客侧路由 {@code /blog/solutions/preview/<token>/}）。
     *
     * <p>为什么由服务端来拼这份数据：解答要展示的字段不是记录里的裸字段，而是**发布计划的产物**
     * （{@code PracticeController.buildPlan} 推出来的 difficulty / relatedPost / 正文）。
     * 让浏览器自己拼一份，就会出现「预览里是一个样、真发布是另一个样」——
     * 与「预检与真发共用同一个计划」是同一条理由。
     *
     * <p>⚠️ 调用方刻意**不因为 blocker 就拒绝预览**：blockers 是「发布信息还没配齐」，
     * 而预览恰恰要能看半成品效果。
     */
    public static Map<String, Object> putSolution(long ownerUid, String title, String description,
                                                  List<String> tags, String difficulty, String relatedPost,
                                                  String body) {
        Entry e = new Entry(ownerUid, System.currentTimeMillis(), "solution", "", 
                title.isEmpty() ? "（未填标题）" : title,
                description == null ? "" : description, tags == null ? List.of() : tags, "",
                true, "", difficulty == null ? "" : difficulty, relatedPost == null ? "" : relatedPost,
                body == null ? "" : body);
        return store(e, "/blog/solutions/preview/");
    }

    /** 落库 + 返回前端要的地址（两条路共用；URL 前缀按类型不同） */
    private static Map<String, Object> store(Entry e, String urlPrefix) {
        sweep();
        String token = newToken();
        STORE.put(token, e);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("url", urlPrefix + token + "/");
        out.put("expiresInSec", TTL_MS / 1000);
        out.put("chars", e.body().length());
        return out;
    }

    /** 取一份预览（过期即删）。找不到返回 {@code null}。 */
    public static Entry get(String token) {
        if (token == null || token.isEmpty()) return null;
        Entry e = STORE.get(token);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.createdAt() > TTL_MS) {
            STORE.remove(token);
            return null;
        }
        return e;
    }

    /** 给博客 SSR 路由渲染用的载荷（**匿名可读**，靠 token 授权） */
    public static Map<String, Object> payload(Entry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // kind 决定博客用哪套布局渲染；也是「token 不能跨类型用」的依据（另一条路由会 404）
        out.put("kind", e.kind());
        out.put("title", e.title());
        out.put("description", e.description());
        out.put("tags", e.tags());
        out.put("category", e.category());
        out.put("draft", e.draft());
        out.put("published", e.published());
        out.put("difficulty", e.difficulty());
        out.put("relatedPost", e.relatedPost());
        out.put("body", e.body());
        // slug 只用于页面内的展示性信息（例如「将来会发布到 /blog/posts/<slug>/」），没有也不影响渲染
        out.put("slug", e.slug());
        return out;
    }

    /** 当前存了几份（诊断/日志用） */
    public static int size() {
        return STORE.size();
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    private static String newToken() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 先清过期；仍超上限就按创建时间从旧到新清到上限以内 */
    private static void sweep() {
        long now = System.currentTimeMillis();
        STORE.entrySet().removeIf(en -> now - en.getValue().createdAt() > TTL_MS);
        if (STORE.size() < MAX_ENTRIES) return;
        List<Map.Entry<String, Entry>> es = new ArrayList<>(STORE.entrySet());
        es.sort((a, b) -> Long.compare(a.getValue().createdAt(), b.getValue().createdAt()));
        int drop = STORE.size() - MAX_ENTRIES + 1;
        for (int i = 0; i < drop && i < es.size(); i++) {
            STORE.remove(es.get(i).getKey());
        }
        log.info("预览存储已达上限，清理了 {} 份最旧的（当前 {}）", drop, STORE.size());
    }

    /** tags 既接受数组也接受逗号串（与写入侧同口径，前端不必为预览单独改一次传参） */
    private static List<String> tagsOf(JsonNode node) {
        JsonNode t = node == null ? null : node.get("tags");
        if (t != null && t.isArray()) return JsonSanitizer.strList(t, 12, 40);
        return tagsOf(JsonSanitizer.str(node, "tags"));
    }

    private static List<String> tagsOf(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null) return out;
        for (String s : joined.replaceAll("[\\[\\]]", "").split(",")) {
            String v = s.trim().replaceAll("^['\"]|['\"]$", "");
            if (!v.isEmpty() && out.size() < 12) out.add(v);
        }
        return out;
    }

    /** 从整篇 Markdown 里取一个 frontmatter 标量（取不到回空串） */
    private static String frontmatterValue(String md, String key) {
        if (md == null || !md.startsWith("---")) return "";
        int end = md.indexOf("\n---", 3);
        if (end < 0) return "";
        for (String line : md.substring(3, end).split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            if (line.substring(0, c).trim().equals(key)) {
                return line.substring(c + 1).trim().replaceAll("^['\"]|['\"]$", "");
            }
        }
        return "";
    }

    /** 去掉 frontmatter，只留正文 */
    private static String stripFrontmatter(String md) {
        if (md == null || !md.startsWith("---")) return md == null ? "" : md;
        int end = md.indexOf("\n---", 3);
        return end < 0 ? md : md.substring(end + 4).strip();
    }
}
