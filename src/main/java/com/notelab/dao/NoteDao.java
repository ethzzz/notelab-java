package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notelab.common.RowUtil;
import com.notelab.model.NoteType;
import com.notelab.model.entity.Note;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 笔记表 DAO（B 端笔记 / PRD）：notes 的访问层。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 DocumentDao。
 *
 * <p>⚠️ 每个读方法都带 {@code docType}：笔记与 PRD 同表，按类型分页/计数/导出**必须**带过滤，
 * 漏一处就会让「PRD 管理」里冒出笔记（反之亦然）。这里刻意不提供「不带类型」的全表读方法，
 * 让漏传变成编译错误，而不是静默混起来。
 */
public final class NoteDao {

    private NoteDao() {}

    /** 全列序（详情含正文） */
    private static final String[] ALL_COLS = {"id", "doc_type", "title", "content_md", "size_bytes", "created_by", "created_at", "updated_at"};
    /** 列表列序（不含正文，避免大字段拖慢列表） */
    private static final String[] LIST_COLS = {"id", "doc_type", "title", "size_bytes", "created_by", "created_at", "updated_at"};

    /** 新建文档，返回自增 id（docType 必须是 {@link NoteType#normalize} 归一后的值） */
    public static long create(String docType, String title, String contentMd, Long createdBy) {
        Note n = new Note();
        n.setDocType(docType);
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

    /** 按 id + 类型取（类型不符返回 null —— 调用方转 404，避免跨类型误改） */
    public static Map<String, Object> getByIdInType(long id, String docType) {
        Note n = DaoSupport.note().selectOne(Wrappers.lambdaQuery(Note.class)
                .eq(Note::getId, id)
                .eq(Note::getDocType, docType));
        return RowUtil.row(n, ALL_COLS);
    }

    /** 该 id 是否存在于指定类型下 */
    public static boolean existsInType(long id, String docType) {
        Long n = DaoSupport.note().selectCount(Wrappers.lambdaQuery(Note.class)
                .eq(Note::getId, id)
                .eq(Note::getDocType, docType));
        return n != null && n > 0;
    }

    /** 更新标题与正文（正文大小同步刷新；updated_at 由 MySQL ON UPDATE 自动维护） */
    public static void update(long id, String title, String contentMd) {
        DaoSupport.note().update(null, Wrappers.lambdaUpdate(Note.class)
                .eq(Note::getId, id)
                .set(Note::getTitle, title)
                .set(Note::getContentMd, contentMd)
                .set(Note::getSizeBytes, (long) contentMd.getBytes(StandardCharsets.UTF_8).length));
    }

    public static void delete(long id) {
        DaoSupport.note().deleteById(id);
    }

    /** 分页 + 可选标题关键字；按 id 倒序（新的在前），不取正文字段 */
    public static List<Map<String, Object>> listPaged(String docType, String q, int limit, long offset) {
        QueryWrapper<Note> w = new QueryWrapper<>();
        w.eq("doc_type", docType);
        appendFilter(w, q);
        w.select(Note.class, info -> !"content_md".equals(info.getColumn()));
        w.orderByDesc("id");
        Page<Note> page = new Page<>(offset / limit + 1, limit, false);
        return RowUtil.rows(DaoSupport.note().selectPage(page, w).getRecords(), LIST_COLS);
    }

    /** 某类型的全量列表（含正文），按 id 倒序；供「导出全部」使用 */
    public static List<Map<String, Object>> listAll(String docType, String q) {
        QueryWrapper<Note> w = new QueryWrapper<>();
        w.eq("doc_type", docType);
        appendFilter(w, q);
        w.orderByDesc("id");
        return RowUtil.rows(DaoSupport.note().selectList(w), ALL_COLS);
    }

    public static long countFiltered(String docType, String q) {
        QueryWrapper<Note> w = new QueryWrapper<>();
        w.eq("doc_type", docType);
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
