package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.Document;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 文档域 DAO（B 端文档编辑）：documents 的访问。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 InviteCodeDao。
 */
public final class DocumentDao {

    private DocumentDao() {}

    /** 全列序（详情含正文） */
    private static final String[] ALL_COLS = {"id", "title", "content_html", "size_bytes", "created_by", "created_at", "updated_at"};
    /** 列表列序（不含正文，避免大字段拖慢列表） */
    private static final String[] LIST_COLS = {"id", "title", "size_bytes", "created_by", "created_at", "updated_at"};

    /** 新建文档，返回自增 id */
    public static long create(String title, String contentHtml, Long createdBy) {
        Document d = new Document();
        d.setTitle(title);
        d.setContentHtml(contentHtml);
        d.setSizeBytes((long) contentHtml.getBytes(StandardCharsets.UTF_8).length);
        d.setCreatedBy(createdBy);
        DaoSupport.document().insert(d);
        return d.getId();
    }

    public static Map<String, Object> getById(long id) {
        return RowUtil.row(DaoSupport.document().selectById(id), ALL_COLS);
    }

    public static boolean exists(long id) {
        Long n = DaoSupport.document().selectCount(Wrappers.lambdaQuery(Document.class).eq(Document::getId, id));
        return n != null && n > 0;
    }

    /** 更新标题与正文（正文大小同步刷新；updated_at 由 MySQL ON UPDATE 自动维护） */
    public static void update(long id, String title, String contentHtml) {
        DaoSupport.document().update(null, Wrappers.lambdaUpdate(Document.class)
                .eq(Document::getId, id)
                .set(Document::getTitle, title)
                .set(Document::getContentHtml, contentHtml)
                .set(Document::getSizeBytes, (long) contentHtml.getBytes(StandardCharsets.UTF_8).length));
    }

    public static void delete(long id) {
        DaoSupport.document().deleteById(id);
    }

    /** 分页 + 可选标题关键字；按 id 倒序（新文档在前），不取正文字段 */
    public static List<Map<String, Object>> listPaged(String q, int limit, long offset) {
        QueryWrapper<Document> w = new QueryWrapper<>();
        appendFilter(w, q);
        w.select(Document.class, info -> !"content_html".equals(info.getColumn()));
        w.orderByDesc("id");
        Page<Document> page = new Page<>(offset / limit + 1, limit, false);
        return RowUtil.rows(DaoSupport.document().selectPage(page, w).getRecords(), LIST_COLS);
    }

    public static long countFiltered(String q) {
        QueryWrapper<Document> w = new QueryWrapper<>();
        appendFilter(w, q);
        Long n = DaoSupport.document().selectCount(w);
        return n == null ? 0 : n;
    }

    private static void appendFilter(QueryWrapper<Document> w, String q) {
        if (q != null && !q.isBlank()) {
            w.like("title", q.trim());
        }
    }
}
