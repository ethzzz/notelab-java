package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.Note;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 笔记表 DAO（B 端笔记）：notes 的访问层。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 DocumentDao。
 */
public final class NoteDao {

    private NoteDao() {}

    /** 全列序（详情含正文） */
    private static final String[] ALL_COLS = {"id", "title", "content_md", "size_bytes", "created_by", "created_at", "updated_at"};
    /** 列表列序（不含正文，避免大字段拖慢列表） */
    private static final String[] LIST_COLS = {"id", "title", "size_bytes", "created_by", "created_at", "updated_at"};

    /** 新建笔记，返回自增 id */
    public static long create(String title, String contentMd, Long createdBy) {
        Note n = new Note();
        n.setTitle(title);
        n.setContentMd(contentMd);
        n.setSizeBytes((long) contentMd.getBytes(StandardCharsets.UTF_8).length);
        n.setCreatedBy(createdBy);
        DaoSupport.note().insert(n);
        return n.getId();
    }

    public static Map<String, Object> getById(long id) {
        return RowUtil.row(DaoSupport.note().selectById(id), ALL_COLS);
    }

    public static boolean exists(long id) {
        Long n = DaoSupport.note().selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(Note.class).eq(Note::getId, id));
        return n != null && n > 0;
    }

    /** 更新标题与正文（正文大小同步刷新；updated_at 由 MySQL ON UPDATE 自动维护） */
    public static void update(long id, String title, String contentMd) {
        DaoSupport.note().update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(Note.class)
                .eq(Note::getId, id)
                .set(Note::getTitle, title)
                .set(Note::getContentMd, contentMd)
                .set(Note::getSizeBytes, (long) contentMd.getBytes(StandardCharsets.UTF_8).length));
    }

    public static void delete(long id) {
        DaoSupport.note().deleteById(id);
    }

    /** 分页 + 可选标题关键字；按 id 倒序（新笔记在前），不取正文字段 */
    public static List<Map<String, Object>> listPaged(String q, int limit, long offset) {
        QueryWrapper<Note> w = new QueryWrapper<>();
        appendFilter(w, q);
        w.select(Note.class, info -> !"content_md".equals(info.getColumn()));
        w.orderByDesc("id");
        Page<Note> page = new Page<>(offset / limit + 1, limit, false);
        return RowUtil.rows(DaoSupport.note().selectPage(page, w).getRecords(), LIST_COLS);
    }

    public static long countFiltered(String q) {
        QueryWrapper<Note> w = new QueryWrapper<>();
        appendFilter(w, q);
        Long n = DaoSupport.note().selectCount(w);
        return n == null ? 0 : n;
    }

    private static void appendFilter(QueryWrapper<Note> w, String q) {
        if (q != null && !q.isBlank()) {
            w.like("title", q.trim());
        }
    }
}
