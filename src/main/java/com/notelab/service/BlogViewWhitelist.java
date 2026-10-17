package com.notelab.service;

import com.notelab.dao.PracticeDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 博客浏览数上报的 **slug 白名单**：博客仓的路线文章（posts）∪ 已发布的练习解答（solutions）。
 *
 * <p>为什么需要白名单：{@code /api/blog/public/view} 是匿名可写入口，只校验格式的话，
 * 任何人都能拿随机串往统计表里灌行（见 {@link com.notelab.common.PermGuard#PUBLIC_PREFIXES}
 * 那一段的安全边界）。所以「这个 slug 在博客上真的存在」必须由服务端判定。
 *
 * <p>两边的来源不同、更新频率也不同：
 * <ul>
 *   <li><b>posts</b>：博客仓 {@code src/content/posts/*.md} 的文件名。只有构建/发布文章才会变，
 *       所以扫目录 + 5 分钟缓存足够。</li>
 *   <li><b>solutions</b>：MySQL 的 {@code practice_record.published_slug}（{@code <> ''} 即已发布，
 *       与公开接口同一个判据）。发布/下线是**秒级**动作，等 5 分钟缓存过期就等于
 *       「刚发的题解前 5 分钟没人读得进统计」→ 所以发布/下线后要主动 {@link #invalidateSolutions()}。</li>
 * </ul>
 *
 * <p>⚠️ 读失败时**沿用上一次的缓存**而不是清空：清空等于「所有上报都判非法」，
 * 博客仓暂时读不到、或数据库抖一下，就整站计数停摆 —— 那是把一个小故障放大成停服。
 * 失败后把下次重试时间提前到 30 秒，避免每个请求都去打一次文件系统 / DB。
 *
 * <p>LLM 依赖：无。
 */
public final class BlogViewWhitelist {

    private static final Logger log = LoggerFactory.getLogger(BlogViewWhitelist.class);

    private static final long CACHE_MS = 5 * 60_000L;
    /** 读失败后的重试间隔：比正常 TTL 短，好让它尽快自愈 */
    private static final long RETRY_MS = 30_000L;

    private static volatile Set<String> postSlugs = Set.of();
    private static volatile long postSlugsAt = 0;

    private static volatile Set<String> solutionSlugs = Set.of();
    private static volatile long solutionSlugsAt = 0;

    private BlogViewWhitelist() {}

    /** 当前允许计数的全部 slug */
    public static Set<String> known() {
        Set<String> all = new HashSet<>(posts());
        all.addAll(solutions());
        return all;
    }

    /**
     * 发布/下线解答后调用：丢掉解答那份缓存，下一次上报立刻按新状态判定。
     *
     * <p>刻意**不**同时清 posts 缓存：那两份互不相关，一起清只是白白多扫一次目录。
     */
    public static void invalidateSolutions() {
        solutionSlugsAt = 0;
    }

    // ---------------- posts：博客仓里的 md 文件名 ----------------

    private static Set<String> posts() {
        long now = System.currentTimeMillis();
        if (now - postSlugsAt <= CACHE_MS) return postSlugs;
        try {
            Set<String> s = new HashSet<>();
            for (String n : BlogRepoService.listNames("src/content/posts")) {
                if (n.endsWith(".md")) s.add(n.substring(0, n.length() - 3));
            }
            if (!s.isEmpty()) {
                postSlugs = Set.copyOf(s);
                postSlugsAt = now;
            }
        } catch (Exception e) {
            log.warn("读取博客文章清单失败，沿用旧缓存（{} 篇）：{}", postSlugs.size(), e.toString());
            postSlugsAt = now - CACHE_MS + RETRY_MS;
        }
        return postSlugs;
    }

    // ---------------- solutions：库里已发布的对外 slug ----------------

    private static Set<String> solutions() {
        long now = System.currentTimeMillis();
        if (now - solutionSlugsAt <= CACHE_MS) return solutionSlugs;
        try {
            List<String> list = PracticeDao.publishedSlugs();
            solutionSlugs = Set.copyOf(list);
            solutionSlugsAt = now;
        } catch (Exception e) {
            log.warn("读取已发布解答清单失败，沿用旧缓存（{} 篇）：{}", solutionSlugs.size(), e.toString());
            solutionSlugsAt = now - CACHE_MS + RETRY_MS;
        }
        return solutionSlugs;
    }
}
