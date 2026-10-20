package com.notelab.controller;

import com.notelab.service.LlmGateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notelab.common.AppConfig;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.dao.BlogDeployDao;
import com.notelab.dao.BlogStatDao;
import com.notelab.infra.QwenClient;
import com.notelab.infra.QwenKeys;
import com.notelab.service.BlogArticleService;
import com.notelab.service.BlogDeployService;
import com.notelab.service.BlogPreviewService;
import com.notelab.service.BlogRepoService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 博客管理（B 端）：文章起草 / 文章记录。
 *
 * <p><b>本类只做 HTTP 这一层</b> —— 鉴权、解析载荷、拼响应、记改动日志。
 * 文章的机制（frontmatter、slug 规则、去重、校验、读-改-写）全部在 {@link BlogArticleService}，
 * 那里是唯一真相源；原因见那个类的注释（预检与真写必须共用一份实现）。
 *
 * <p>接口一览（都要求 B 端登录；{@code PermGuard} 另把 {@code /api/blog} 限定为仅超管）：
 * <ul>
 *   <li>{@code POST /generate}      —— 给提示词，调 QwenClient 起草一篇完整 Markdown（不落盘）</li>
 *   <li>{@code POST /plan}          —— **干跑**：会写成什么（文件名=URL、会覆盖谁、缺什么）</li>
 *   <li>{@code POST /content}       —— 新建并写入博客仓（结构化字段，或 AI 草稿的 raw_markdown）</li>
 *   <li>{@code PUT /articles/{slug}}—— 更新既有文章（可改名）</li>
 *   <li>{@code POST /articles/{slug}/draft} —— 上线 / 下线（只动 frontmatter 的 draft 那一行）</li>
 *   <li>{@code DELETE /articles/{slug}}     —— 删除（**仅允许草稿**）</li>
 *   <li>{@code GET /list}           —— 全部 slug（起草页的「最近文章」）</li>
 *   <li>{@code GET /articles}       —— 清单 + 浏览数 + 最后一次后台改动（文章记录页）</li>
 *   <li>{@code GET /articles/{slug}}—— 单篇详情：元信息 + 正文 + **原文全文** + 改动记录</li>
 *   <li>{@code GET /options}        —— 分类 / 标签候选（取自仓内现状，别再手输）</li>
 *   <li>{@code POST /deploy}        —— 手动触发/重试一次部署（构建 + 发布到线上）</li>
 *   <li>{@code GET /deploy/latest}、{@code /deploy/{id}}、{@code GET /deploy} —— 部署状态 / 历史</li>
 * </ul>
 *
 * <p>**会影响线上的**写入动作（发布 / 更新已发布文章 / 上下线）成功后会**自动排一次部署**
 * （{@link BlogDeployService}，`BLOG_AUTO_DEPLOY=0` 可关）：博客是静态站，commit 推上去了但没构建，
 * 读者什么都看不到。草稿的保存与草稿的删除**不构建** —— 读者本来就看不到它们。
 * ⚠️ 构建进行中（约 1 分钟）写入会被 409 挡住：构建要读整个工作区，与提交并发会互相踩。
 *
 * <p>仓库写入本身（clone / pull / commit / push）在 {@link BlogRepoService}。
 * push 到 main 会触发博客的 GitHub Actions（实测它的上传步骤要 11–25 分钟）；
 * ⚠️ admin 侧发布**不依赖**它 —— 发布后由后台触发服务器本地构建（约 80 秒），见任务简报 P2。
 */
@RestController
@RequestMapping("/api/blog")
public class BlogContentController {

    private static final Logger log = LoggerFactory.getLogger(BlogContentController.class);

    // ==================================================================================
    // AI 起草（本类里唯一还留着的业务：它只跟 Qwen 有关，与文章机制无关）
    // ==================================================================================

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) String raw,
                                                        HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node = parseJson(raw);
        if (node == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));

        String prompt = JsonSanitizer.str(node, "prompt");
        if (prompt.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "prompt 不能为空"));
        String category = JsonSanitizer.str(node, "category");
        String tags = JsonSanitizer.str(node, "tags");
        String persona = JsonSanitizer.str(node, "persona");
        if (persona.isEmpty()) persona = "资深前端工程师，工作十年，一直保持做笔记的习惯";
        int length = JsonSanitizer.clampInt(node.get("length"), 800, 6000, 2500);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", buildSystemPrompt(category, tags, persona, length)),
                Map.of("role", "user", "content", prompt));
        String markdown;
        try {
            markdown = LlmGateway.chatModel(AppConfig.qwenModel(), messages, 120);
        } catch (Exception e) {
            log.warn("博客 AI 起草失败", e);
            return ResponseEntity.status(502).body(Map.of("error", "AI 生成失败：" + e.getMessage()));
        }
        if (markdown == null || markdown.isBlank()) {
            return ResponseEntity.status(502).body(Map.of("error", "AI 返回为空"));
        }
        // 去掉模型可能夹带的 ```markdown 围栏，保证落盘即合法 .md
        return ResponseEntity.ok(Map.of("ok", true, "markdown", stripFence(markdown)));
    }

    // ==================================================================================
    // 计划（干跑）
    // ==================================================================================

    /**
     * 干跑：返回「会写成什么」，一行都不写。
     *
     * <p>阻塞项（blockers）在这里是**正常结果**而不是错误：前端拿它做二次确认
     * （文件名=URL、会覆盖谁、缺什么），所以照样 200 —— 真正写入的接口才用 400 拒绝。
     */
    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node = parseJson(raw);
        if (node == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        BlogArticleService.Plan plan = BlogArticleService.plan(node);
        Map<String, Object> body = new LinkedHashMap<>(plan.toBody());
        body.put("ok", true);
        body.put("ready", plan.ready());
        return ResponseEntity.ok(body);
    }

    // ==================================================================================
    // 写入：新建 / 更新 / 上下线 / 删除
    // ==================================================================================

    /** 新建一篇文章（结构化字段，或 AI 草稿的 {@code raw_markdown}）。 */
    @PostMapping("/content")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node = parseJson(raw);
        if (node == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        return write(request, node, false);
    }

    /**
     * 更新一篇既有文章（可改名）。
     *
     * <p>⚠️ slug 从**路径**取（它决定改的是哪一篇），body 里的 {@code slug} 一律被它覆盖 ——
     * 一条请求里出现两个「要改谁」的答案时以路径为准，免得前端漏传就改错文件。
     */
    @PutMapping("/articles/{slug}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String slug,
                                                      @RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode parsed = parseJson(raw);
        if (parsed == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        ObjectNode node = (ObjectNode) parsed;
        node.put("mode", "update");
        node.put("slug", slug);
        return write(request, node, true);
    }

    /**
     * 上线 / 下线：{@code {"draft":true}} 转草稿（下线），{@code false} 发布。
     * 只改 frontmatter 的 draft 那一行，正文与其它字段一个字节都不动。
     */
    @PostMapping("/articles/{slug}/draft")
    public ResponseEntity<Map<String, Object>> setDraft(@PathVariable String slug,
                                                        @RequestBody(required = false) String raw,
                                                        HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node = parseJson(raw);
        if (node == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        if (!node.has("draft")) return ResponseEntity.status(400).body(Map.of("error", "缺少 draft 字段"));
        boolean draft = node.get("draft").asBoolean();
        if (BlogDeployDao.countActiveDeploys() > 0) return busyDeploy();
        try {
            String title = titleOf(slug);
            Map<String, Object> r = BlogArticleService.setDraft(slug, draft);
            logAction(request, slug, draft ? "unpublish" : "publish", title,
                    draft ? "经「文章起草」转为草稿（下线）" : "经「文章起草」发布上线");
            maybeDeployInto(request, r, draft ? "" : "/blog/posts/" + slug + "/", "publish");
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return fail(e, "切换上下线失败");
        }
    }

    /** 删除（仅限草稿；已发布的先下线）。 */
    @DeleteMapping("/articles/{slug}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String slug, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (BlogDeployDao.countActiveDeploys() > 0) return busyDeploy();
        try {
            String title = titleOf(slug);
            Map<String, Object> r = BlogArticleService.delete(slug);
            logAction(request, slug, "delete", title, "经「文章起草」删除（原为草稿）");
            // 删除的只可能是草稿（非草稿走不到这里），草稿删掉不影响线上 → 不构建
            r.put("deploy", Map.of("ok", true, "skipped", true, "reason", "删的是草稿，线上本来就没有它"));
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return fail(e, "删除失败");
        }
    }

    // ==================================================================================
    // 部署（构建 + 发布到线上）
    // ==================================================================================

    /**
     * 手动触发一次部署（也用于「重试」）。
     *
     * <p>body 可选：{@code {"slug":"<文章>"}} 表示「部署完探活这一篇」；不传就是整站部署。
     * ⚠️ 只有**已发布**的文章才把文章 URL 当探活目标 —— 草稿本来就不该可访问，
     * 拿它当判据必然假失败。
     */
    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        JsonNode node = (raw == null || raw.isBlank()) ? null : parseJson(raw);
        String slug = node == null ? "" : JsonSanitizer.str(node, "slug");
        String verifyPath = "";
        if (!slug.isEmpty()) {
            BlogArticleService.Article a = BlogArticleService.read(slug);
            if (a != null && !a.draft()) verifyPath = "/blog/posts/" + slug + "/";
        }
        Map<String, Object> r = BlogDeployService.trigger(AuthUtil.userId(me),
                String.valueOf(me.get("username")), "manual", verifyPath);
        if (!Boolean.TRUE.equals(r.get("ok"))) return ResponseEntity.status(429).body(r);
        if (!slug.isEmpty()) logAction(request, slug, "deploy", titleOf(slug), "手动触发部署并探活");
        return ResponseEntity.ok(r);
    }

    /** 某次部署的状态（前端轮询用） */
    @GetMapping("/deploy/{id}")
    public ResponseEntity<Map<String, Object>> deployStatus(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> row = BlogDeployDao.get(id);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "没有这次部署记录"));
        return ResponseEntity.ok(row);
    }

    /** 最近一次部署 + 当前配置（部署状态页首屏用它，省一次请求） */
    @GetMapping("/deploy/latest")
    public ResponseEntity<Map<String, Object>> deployLatest(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(deploySummary());
    }

    /** 部署历史（最近 20 条）+ 同一份摘要 */
    @GetMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deployHistory(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> out = deploySummary();
        out.put("items", BlogDeployDao.recent(20));
        return ResponseEntity.ok(out);
    }

    /** 部署相关的共同摘要：最近一次 + 活跃数 + 仓里 HEAD + 配置 */
    private static Map<String, Object> deploySummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("latest", BlogDeployDao.latest());
        out.put("active", BlogDeployDao.countActive());
        out.put("activeDeploys", BlogDeployDao.countActiveDeploys());
        out.put("headCommit", BlogRepoService.headCommit());
        out.put("script", BlogDeployService.deployScript());
        out.put("reindexScript", BlogDeployService.reindexScript());
        out.put("autoDeploy", BlogDeployService.autoEnabled());
        return out;
    }

    // ==================================================================================
    // 预览（交给博客自己的 SSR 路由渲染，保证与线上文章页一致）
    // ==================================================================================

    /**
     * 存一份「正在编辑的草稿」，返回可直接嵌 iframe 的预览地址。
     *
     * <p>为什么要多这一步：预览必须与线上一致，而线上正文是**博客的构建期管线**渲染的
     * （expressive-code / KaTeX / 提示块）。后台再怎么配都是另一套库。所以正文交给博客侧渲染，
     * 本接口只负责把这份还没落盘的草稿放进短时存储里（见 {@link BlogPreviewService}）。
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody(required = false) String raw,
                                                       HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        JsonNode node = parseJson(raw);
        if (node == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        return ResponseEntity.ok(BlogPreviewService.put(AuthUtil.userId(me), node));
    }

    /**
     * 取一份预览给博客渲染 —— **匿名可读**。
     *
     * <p>⚠️ 这不是疏忽：博客的 SSR 进程没有 B 端会话，它只能匿名来取；所以授权手段只剩
     * 「拿着不可猜的 token」这一条（128 位随机 + 30 分钟 TTL + 只读，见 {@code PermGuard.PUBLIC_PREFIXES}）。
     * 过期或不存在一律 404，让博客页面显示「预览已过期」而不是一片空白。</p>
     */
    @GetMapping("/preview/{token}")
    public ResponseEntity<Map<String, Object>> previewPayload(@PathVariable String token) {
        BlogPreviewService.Entry e = BlogPreviewService.get(token);
        if (e == null) return ResponseEntity.status(404).body(Map.of("error", "预览不存在或已过期"));
        return ResponseEntity.ok(BlogPreviewService.payload(e));
    }

    // ==================================================================================
    // 读：清单 / 详情 / 候选
    // ==================================================================================

    /** 全部 slug（按发布时间倒序）。起草页的「最近文章」用它。 */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        List<Map<String, Object>> items = new ArrayList<>();
        for (BlogArticleService.Article a : BlogArticleService.list()) {
            items.add(Map.of("slug", a.slug()));
        }
        return ResponseEntity.ok(Map.of("ok", true, "items", items));
    }

    /**
     * 文章清单 + 统计：标题 / 分类 / 发布日 / 草稿态 + 浏览数 + 最后一次后台改动。
     *
     * <p>数据来自两处：**文章本身**（博客仓的 md）与**统计**（blog_article_stat 的浏览数、
     * blog_article_log 的改动记录）。在 Java 侧拼而不是让前端 join：前端要按浏览数排序/筛选，
     * 拿两份数据自己拼会很难受。
     */
    @GetMapping("/articles")
    public ResponseEntity<Map<String, Object>> articles(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        List<BlogArticleService.Article> arts = BlogArticleService.list();
        List<String> slugs = arts.stream().map(BlogArticleService.Article::slug).toList();
        // 浏览数与最后改动都是**一次查完**再在内存里拼：逐篇查 = 202 次往返
        Map<String, Long> views = BlogStatDao.viewsOf(slugs);
        Map<String, Map<String, Object>> lastLogs = BlogStatDao.lastLogs();

        List<Map<String, Object>> items = new ArrayList<>();
        for (BlogArticleService.Article a : arts) {
            Map<String, Object> one = new LinkedHashMap<>(a.toMeta());
            one.put("views", views.getOrDefault(a.slug(), 0L));
            one.put("lastChange", lastLogs.get(a.slug()));
            items.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", items.size());
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    /**
     * 单篇详情：元信息 + 正文 + **原文全文** + 浏览数 + 后台改动记录。
     *
     * <p>为什么要回原文全文（{@code markdown}）：编辑页要能把整篇（含 frontmatter）原样装载；
     * 只给结构化字段的话，{@code image} / {@code lang} 这类本类不管理的键在页面上就看不见，
     * 用户改一次保存就可能把它们弄丢。
     */
    @GetMapping("/articles/{slug}")
    public ResponseEntity<Map<String, Object>> article(@PathVariable String slug, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        BlogArticleService.Article a = BlogArticleService.read(slug);
        if (a == null) return ResponseEntity.status(404).body(Map.of("error", "没有这篇文章"));
        Map<String, Object> out = new LinkedHashMap<>(a.toMeta());
        out.put("body", a.body());
        String raw = BlogArticleService.rawOf(slug);
        out.put("markdown", raw == null ? "" : raw);
        out.put("views", BlogStatDao.viewsOf(slug));
        out.put("logs", BlogStatDao.logsOf(slug, 50));
        return ResponseEntity.ok(out);
    }

    /** 分类 / 标签候选（取自仓内现有文章），外加总数与草稿数。 */
    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> options(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(BlogArticleService.options());
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    /** 解析请求体。{} 或非法 JSON → {@code null}（调用方回 400）。 */
    private static JsonNode parseJson(String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            return JsonUtil.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 共用的写入路径：先出计划（有 blocker 就 400，一行都不写），再落盘，最后记改动日志。
     *
     * @param update true = 更新既有文章（决定日志文案与动作名）
     */
    private static ResponseEntity<Map<String, Object>> write(HttpServletRequest request, JsonNode node,
                                                             boolean update) {
        if (BlogDeployDao.countActiveDeploys() > 0) return busyDeploy();
        try {
            BlogArticleService.Plan plan = BlogArticleService.plan(node);
            if (!plan.ready()) {
                Map<String, Object> body = new LinkedHashMap<>(plan.toBody());
                body.put("ok", false);
                return ResponseEntity.status(400).body(body);
            }
            Map<String, Object> r = BlogArticleService.apply(plan);
            StringBuilder detail = new StringBuilder(update ? "经「文章起草」更新" : "经「文章起草」写入");
            detail.append(plan.draft() ? "（草稿）" : "（已发布）");
            if (plan.renamed()) {
                detail.append("；slug 由 ").append(plan.oldSlug()).append(" 改为 ").append(plan.slug());
            }
            logAction(request, plan.slug(), update ? "update" : "create", plan.title(), detail.toString());
            if (plan.draft()) {
                // 草稿不上线：构建一次要 1 分多钟、还会占住机器，没必要为「读者看不到的变化」付这个代价
                r.put("deploy", Map.of("ok", true, "skipped", true, "reason", "草稿不上线，无需构建（发布时会自动构建）"));
            } else {
                maybeDeployInto(request, r, "/blog/posts/" + plan.slug() + "/", "publish");
            }
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return fail(e, update ? "更新文章失败" : "写入文章失败");
        }
    }

    /**
     * 正在部署时**拒绝写入**（409 + 明确文案）。
     *
     * <p>为什么要拒：部署脚本会 `git pull` 并读整个工作区，而 java 的写入在同一份 clone 上
     * commit/push —— 两者并发会互相踩。2026-10-16 实测到一次：构建报
     * {@code fatal: Cannot fast-forward to multiple branches} 退出 128，部署白失败，
     * 而那次写入本身是成功的（「一半成功」最难排查）。
     *
     * <p>为什么是快速失败而不是挂在那等锁：请求挂在锁上要等一分钟，前端只能转圈；
     * 409 能把「为什么现在不能改」说清楚（P3 的编辑器据此提示）。
     *
     * <p>⚠️ 判据是 {@code countActiveDeploys()} 而不是「有任何任务在跑」：搜索索引重建
     * （解答发布后触发）只读产物、几秒钟结束，不该把编辑挡住。
     */
    private static ResponseEntity<Map<String, Object>> busyDeploy() {
        return ResponseEntity.status(409).body(Map.of(
                "error", "正在部署（约 1 分钟），等它结束再改文章",
                "deploying", true));
    }
    /**
     * 发布类动作（写入 / 上下线 / 删除）之后的**自动部署**：把一次构建排进队列。
     *
     * <p>三个刻意的取舍：
     * <ul>
     *   <li><b>入队失败绝不影响写入的结果</b>：文章已经提交推送成功了，那才是这次请求的主要结果。
     *       部署结果作为 {@code deploy} 字段附在响应里，前端据此提示「已发布，但部署在排队 / 被拒」。</li>
     *   <li><b>被拒不是异常</b>：上一场构建还没跑完时（构建约 1–2 分钟）是**正常**情况，
     *       返回 {@code {ok:false, reason}}，让前端说清「为什么没开始」而不是让用户以为坏了。</li>
     *   <li><b>开关在配置里</b>：{@code BLOG_AUTO_DEPLOY=0} 关掉自动部署（改配置不用改代码）。</li>
     * </ul>
     */
    private static void maybeDeployInto(HttpServletRequest request, Map<String, Object> resp,
                                        String verifyPath, String kind) {
        if (!BlogDeployService.autoEnabled()) {
            resp.put("deploy", Map.of("ok", false, "reason", "自动部署已关闭（BLOG_AUTO_DEPLOY=0）"));
            return;
        }
        Map<String, Object> me = AuthUtil.user(request);
        try {
            resp.put("deploy", BlogDeployService.trigger(
                    me == null ? null : AuthUtil.userId(me),
                    me == null ? "" : String.valueOf(me.get("username")), kind, verifyPath));
        } catch (Exception e) {
            log.warn("自动部署入队失败：{}", e.toString());
            resp.put("deploy", Map.of("ok", false, "reason", "自动部署入队异常：" + e.getMessage()));
        }
    }

    /**
     * 记一条后台改动（admin「文章记录」页展示）。
     *
     * <p>⚠️ 只记**经过本接口**的动作：博客仓的 md 同样可以直接编辑、提交（日常就是这么维护的），
     * 那些改动不会出现在这张表里。口径是「后台记录」，不是「仓库全历史」。
     */
    private static void logAction(HttpServletRequest request, String slug, String action,
                                  String title, String detail) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return;
        try {
            BlogStatDao.log(slug, action, title, detail, AuthUtil.userId(me), String.valueOf(me.get("username")));
        } catch (Exception e) {
            // 日志写失败不该让一次已经成功的发布返回错误 —— 但必须留痕
            log.warn("写博客改动日志失败 slug={} action={}：{}", slug, action, e.toString());
        }
    }

    /** 日志里要一个标题；读不到就回空串 */
    private static String titleOf(String slug) {
        BlogArticleService.Article a = BlogArticleService.read(slug);
        return a == null ? "" : a.title();
    }

    private static ResponseEntity<Map<String, Object>> fail(Exception e, String what) {
        if (e instanceof NoSuchElementException) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
        if (e instanceof IllegalStateException) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        if (e instanceof IllegalArgumentException) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
        log.error(what, e);
        return ResponseEntity.status(500).body(Map.of("error", what + "：" + e.getMessage()));
    }

    private static String buildSystemPrompt(String category, String tags, String persona, int length) {
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
    private static String stripFence(String md) {
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
}
