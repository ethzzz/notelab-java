package com.notelab.service;

import com.notelab.dao.CommunityDao;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把博客文章导入为社区「文章讨论区」种子帖（PRD-P9 M1 冷启动 / M2 后台按钮化）。
 *
 * <h3>为什么读 RSS 而不是扫 md frontmatter</h3>
 * 前端 {@code BlogTab} 的讨论入口用 {@code postKey(link)} 当 key，而 {@code postKey} 就是
 * {@code new URL(link).pathname}（如 {@code /blog/posts/xxx/}）。**RSS 是唯一与前端同源的真相**。
 * 自己扫 md 拼 {@code /blog/posts/<文件名>/}，一旦某篇文章用了自定义 slug 就会**静默失配**：
 * 用户点「讨论」找不到种子帖，转而去建一条重复主题，全程不报错。
 *
 * <h3>为什么用 StAX 而不是 DOM</h3>
 * 线上 {@code rss.xml} 有 3.2MB（含全文 {@code content:encoded}）。DOM 会把它整棵树读进内存，
 * 在这台 2核/3.9G、swap 已吃 2.1G 的机器上不值得。StAX 流式只保留当前 item 的 title/link，
 * 内存占用与文章数量无关。
 *
 * <h3>幂等</h3>
 * 先删「没有任何回复的系统种子帖」，再用已存在 key 集合跳过其余 ——
 * 有回复的种子帖必须保留，否则重新导入会把真实讨论一起抹掉。
 */
public final class CommunitySeedService {

    /** 博客静态产物的 RSS 路径（nginx 直出的构建产物） */
    private static final String RSS_PATH = "/var/www/blog/rss.xml";
    /** 种子帖落在哪个板块（综合讨论） */
    private static final String BOARD_SLUG = "general";
    /** 与 cm_posts.title VARCHAR(120) 对齐 */
    private static final int TITLE_MAX = 120;

    private CommunitySeedService() {}

    /**
     * 执行导入。
     *
     * @return {@code {ok,total,inserted,deleted,skipped,file,message}}，失败时 ok=false 且不抛异常
     *         —— 后台按钮要能把原因显示出来，而不是一个 500
     */
    public static Map<String, Object> importFromRss() {
        Map<String, Object> out = new LinkedHashMap<>();
        File f = new File(RSS_PATH);
        if (!f.isFile() || !f.canRead()) {
            out.put("ok", false);
            out.put("message", "读不到 RSS：" + RSS_PATH + "（博客尚未构建或权限不足）");
            return out;
        }

        Map<String, Object> board = CommunityDao.getBoardBySlug(BOARD_SLUG);
        if (board == null) {
            out.put("ok", false);
            out.put("message", "缺少 " + BOARD_SLUG + " 板块，无法导入");
            return out;
        }
        long boardId = ((Number) board.get("id")).longValue();

        List<String[]> items;
        try {
            items = parse(f);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "解析 RSS 失败：" + e.getMessage());
            return out;
        }

        // 顺序：先取已有 key→标题，再清理「文章已不在 RSS 里」的空种子，最后补插/同步标题。
        // ⚠️ 不能写成「先删全部空种子再重插」—— 那会重建所有行、帖子 id 全变，
        //    用户收藏的 /community/p/<id> 立刻失效（见 CommunityDao.deleteEmptySeedPostsExcept 注释）。
        Map<String, String> existing = CommunityDao.seedTitles();
        Set<String> rssKeys = new HashSet<>();
        for (String[] it : items) rssKeys.add(it[1]);
        int deleted = CommunityDao.deleteEmptySeedPostsExcept(rssKeys);

        int inserted = 0, skipped = 0, renamed = 0;
        for (String[] it : items) {
            String title = it[0];
            String key = it[1];
            String cur = existing.get(key);
            if (cur != null) {
                // 文章改了标题：同步种子帖标题，否则讨论区还顶着旧标题
                if (!cur.equals(title)) {
                    CommunityDao.updateSeedTitle(key, title);
                    renamed++;
                } else {
                    skipped++;
                }
                continue;
            }
            CommunityDao.insertSeedPost(boardId, title, key);
            existing.put(key, title);   // 同一份 RSS 里理论上不重复，但重复插入会变成两个讨论区
            inserted++;
        }

        out.put("ok", true);
        out.put("total", items.size());
        out.put("inserted", inserted);
        out.put("renamed", renamed);
        out.put("deleted", deleted);
        out.put("skipped", skipped);
        out.put("file", RSS_PATH);
        out.put("message", "导入完成：新增 " + inserted + "，同步标题 " + renamed
                + "，已存在 " + skipped + "，清理失效种子 " + deleted);
        return out;
    }

    /** 解析 RSS → [title, pathname] 列表（按 code point 截断标题） */
    private static List<String[]> parse(File f) throws Exception {
        XMLInputFactory xif = XMLInputFactory.newInstance();
        // 关掉外部实体：RSS 是本地文件，但这是解析任何 XML 的默认纪律（XXE）
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (InputStream in = new FileInputStream(f)) {
            XMLStreamReader r = xif.createXMLStreamReader(in, "UTF-8");
            boolean inItem = false;
            String title = null;
            String link = null;
            while (r.hasNext()) {
                int e = r.next();
                if (e == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if ("item".equals(n)) {
                        inItem = true;
                        title = null;
                        link = null;
                    } else if (inItem && "title".equals(n)) {
                        title = r.getElementText();
                    } else if (inItem && "link".equals(n)) {
                        link = r.getElementText();
                    }
                } else if (e == XMLStreamConstants.END_ELEMENT && "item".equals(r.getLocalName())) {
                    inItem = false;
                    if (title != null && link != null) {
                        String path = pathOf(link);
                        if (path != null && !path.isEmpty() && seen.add(path)) {
                            out.add(new String[]{clamp(title.trim()), path});
                        }
                    }
                }
            }
            r.close();
        }
        return out;
    }

    /** 取 pathname（与前端 {@code new URL(link).pathname} 等价）；非法 URL 返回 null */
    private static String pathOf(String link) {
        try {
            return new URI(link.trim()).getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 code point 截断（emoji 不会被切成半个） */
    private static String clamp(String s) {
        if (s == null) return "";
        int n = s.codePointCount(0, s.length());
        if (n <= TITLE_MAX) return s;
        return s.substring(0, s.offsetByCodePoints(0, TITLE_MAX));
    }
}
