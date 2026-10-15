package com.notelab.controller;

import com.notelab.common.ClientIp;
import com.notelab.dao.BlogStatDao;
import com.notelab.service.BlogRepoService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 博客文章浏览数：{@code /api/blog/public/**}，**匿名可读可写**。
 *
 * <p>为什么是公开端点：博客是静态站，读者绝大多数没有 NoteLab 账号，甚至不知道有这个后台。
 * 计数这件事必须由**读者自己的浏览器**上报（服务端渲染时根本没有"读者"这个概念）。
 * 所以这两个接口进 {@link com.notelab.common.PermGuard#PUBLIC_PREFIXES}，
 * 安全性由本类自己保证 —— 见下面三条护栏。
 *
 * <h3>无会话写入口的三条护栏</h3>
 * <ol>
 *   <li><b>slug 白名单</b>：只接受博客仓里真实存在的文章（读目录 + 5 分钟缓存）。
 *       只校验格式的话，任何人都能拿随机串往统计表里灌行。</li>
 *   <li><b>visitorId 形状校验</b>：前端 localStorage 里的随机串，限制字符集与长度，
 *       挡住把任意文本（甚至 SQL/HTML 片段）当 id 存进来。</li>
 *   <li><b>按 IP 节流</b>：正常读者一分钟看不了几篇；没有这道闸，一个循环脚本就能把数字刷上天。</li>
 * </ol>
 *
 * <p>⚠️ 浏览数是**展示性指标**，不是计费/审计数据：口径是「同一浏览器同一篇只算一次」，
 * 换浏览器、清存储、无痕模式都会算成新访客。宁可偏多，不去做指纹识别那套。
 *
 * <p>LLM 依赖：无。
 */
@RestController
@RequestMapping("/api/blog/public")
public class BlogStatController {

    private static final Logger log = LoggerFactory.getLogger(BlogStatController.class);

    /** visitorId 的形状：前端 crypto.randomUUID() 或同类随机串 */
    private static final Pattern VISITOR_RE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    /** 一次批量查询最多问多少篇（列表页也就几十篇，200 是留余量挡超长 URL） */
    private static final int MAX_SLUGS = 200;

    /** 同一 IP 每分钟最多的上报次数。正常读者远到不了；只挡脚本刷 */
    private static final int MAX_POSTS_PER_MIN = 120;

    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    // ---------------- slug 白名单（懒加载 + 过期） ----------------

    private static final long CACHE_MS = 5 * 60_000L;
    private static volatile Set<String> slugCache = Set.of();
    private static volatile long slugCacheAt = 0;

    /**
     * 博客仓 posts 目录里的 slug 集合。
     *
     * <p>⚠️ 读失败时**沿用上一次的缓存**而不是清空：清空等于「所有上报都被判非法」，
     * 博客仓暂时读不到（比如 git 操作占着目录）就整站计数停摆，那是把一个小故障放大成停服。
     */
    private static Set<String> knownSlugs() {
        long now = System.currentTimeMillis();
        if (now - slugCacheAt <= CACHE_MS) return slugCache;
        try {
            Set<String> s = new HashSet<>();
            for (String n : BlogRepoService.listNames("src/content/posts")) {
                if (n.endsWith(".md")) s.add(n.substring(0, n.length() - 3));
            }
            if (!s.isEmpty()) {
                slugCache = Set.copyOf(s);
                slugCacheAt = now;
            }
        } catch (Exception e) {
            log.warn("读取博客文章清单失败，沿用旧缓存（{} 篇）：{}", slugCache.size(), e.toString());
            slugCacheAt = now - CACHE_MS + 30_000L;   // 30 秒后重试，别每个请求都去打文件系统
        }
        return slugCache;
    }

    // ---------------- 上报 ----------------

    /**
     * 记一次浏览。返回**计数后**的浏览数，前端直接拿它更新数字（省掉一次往返）。
     *
     * <p>去重与计数在 {@link BlogStatDao#recordView}：同访客同篇只 +1。
     */
    @PostMapping("/view")
    public ResponseEntity<Map<String, Object>> view(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        String slug = body == null || body.get("slug") == null ? "" : String.valueOf(body.get("slug")).trim();
        String visitor = body == null || body.get("visitorId") == null
                ? "" : String.valueOf(body.get("visitorId")).trim();

        if (!knownSlugs().contains(slug)) {
            return ResponseEntity.status(404).body(Map.of("error", "没有这篇文章"));
        }
        if (!VISITOR_RE.matcher(visitor).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "visitorId 格式不对"));
        }
        if (throttled(ClientIp.of(request))) {
            // 429 而不是静默成功：调用方（博客页脚脚本）会看到并且不更新数字，
            // 比「假装记上了」诚实；正常读者永远碰不到这条。
            return ResponseEntity.status(429).body(Map.of("error", "太频繁"));
        }
        try {
            long views = BlogStatDao.recordView(slug, visitor);
            return ResponseEntity.ok(Map.of("views", views));
        } catch (Exception e) {
            log.warn("记录浏览失败 slug={}：{}", slug, e.toString());
            return ResponseEntity.status(500).body(Map.of("error", "记录失败"));
        }
    }

    /**
     * 批量读浏览数（列表页一次问几十篇）。
     * 没有统计的文章会缺席（而不是补 0）—— 前端据此把「还没人看过」和「读不到数据」区分开。
     */
    @GetMapping("/views")
    public ResponseEntity<Map<String, Object>> views(@RequestParam(required = false) String slugs) {
        List<String> want = new ArrayList<>();
        if (slugs != null && !slugs.isBlank()) {
            for (String one : slugs.split(",")) {
                String s = one.trim();
                if (!s.isEmpty()) want.add(s);
                if (want.size() >= MAX_SLUGS) break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("views", BlogStatDao.viewsOf(want));
        } catch (Exception e) {
            log.warn("查浏览数失败：{}", e.toString());
            out.put("views", Map.of());
        }
        return ResponseEntity.ok(out);
    }

    // ---------------- 节流 ----------------

    /** 固定窗口计数（1 分钟）。条目数上万就整体清空 —— 这个表只是防刷，丢了顶多多放几个请求进来。 */
    private boolean throttled(String ip) {
        if (hits.size() > 10_000) hits.clear();
        long now = System.currentTimeMillis();
        long[] w = hits.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] > 60_000) {
                w[0] = now;
                w[1] = 0;
            }
            w[1]++;
            return w[1] > MAX_POSTS_PER_MIN;
        }
    }
}
