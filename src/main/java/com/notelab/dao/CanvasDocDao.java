package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.CanvasDoc;

import java.util.List;
import java.util.Map;

/**
 * 画布域 DAO（B 端协作画布）：canvas_doc 的访问。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 DocumentDao / InviteCodeDao。
 */
public final class CanvasDocDao {

    private CanvasDocDao() {}

    /** 全列序（画布元数据没有大字段，列表与详情共用） */
    private static final String[] COLS = {"id", "room_id", "title", "created_by", "created_at", "updated_at"};

    /** 新建画布，返回自增 id */
    public static long create(String roomId, String title, Long createdBy) {
        CanvasDoc d = new CanvasDoc();
        d.setRoomId(roomId);
        d.setTitle(title);
        d.setCreatedBy(createdBy);
        DaoSupport.canvasDoc().insert(d);
        return d.getId() == null ? 0L : d.getId();
    }

    public static Map<String, Object> getByRoom(String roomId) {
        CanvasDoc d = DaoSupport.canvasDoc().selectOne(
                Wrappers.lambdaQuery(CanvasDoc.class).eq(CanvasDoc::getRoomId, roomId));
        return RowUtil.row(d, COLS);
    }

    public static boolean existsByRoom(String roomId) {
        Long n = DaoSupport.canvasDoc().selectCount(
                Wrappers.lambdaQuery(CanvasDoc.class).eq(CanvasDoc::getRoomId, roomId));
        return n != null && n > 0;
    }

    /** 重命名（updated_at 由 MySQL ON UPDATE 自动维护） */
    public static void rename(String roomId, String title) {
        DaoSupport.canvasDoc().update(null, Wrappers.lambdaUpdate(CanvasDoc.class)
                .eq(CanvasDoc::getRoomId, roomId)
                .set(CanvasDoc::getTitle, title));
    }

    public static int delete(String roomId) {
        return DaoSupport.canvasDoc().delete(
                Wrappers.lambdaQuery(CanvasDoc.class).eq(CanvasDoc::getRoomId, roomId));
    }

    /** 列表：可选标题关键字；按 updated_at 倒序（最近编辑的在前） */
    public static List<Map<String, Object>> list(String q, int limit) {
        QueryWrapper<CanvasDoc> w = new QueryWrapper<>();
        if (q != null && !q.isBlank()) w.like("title", q.trim());
        w.orderByDesc("updated_at");
        w.last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return RowUtil.rows(DaoSupport.canvasDoc().selectList(w), COLS);
    }
}
