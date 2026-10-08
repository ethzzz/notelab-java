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
    private static final String[] COLS = {"id", "room_id", "title", "engine", "created_by", "created_at", "updated_at"};

    /** 新建画布，返回自增 id */
    public static long create(String roomId, String title, String engine, Long createdBy) {
        CanvasDoc d = new CanvasDoc();
        d.setRoomId(roomId);
        d.setTitle(title);
        d.setEngine(engine);
        d.setCreatedBy(createdBy);
        DaoSupport.canvasDoc().insert(d);
        return d.getId() == null ? 0L : d.getId();
    }

    public static Map<String, Object> getByRoom(String roomId) {
        CanvasDoc d = DaoSupport.canvasDoc().selectOne(
                Wrappers.lambdaQuery(CanvasDoc.class).eq(CanvasDoc::getRoomId, roomId));
        return RowUtil.row(d, COLS);
    }

    /**
     * 该画布的创建者 id；画布不存在返回 null。
     *
     * <p>⚠️ 「画布不存在」与「创建者为空」都返回 null —— 调用方（权限判定）应当把两者
     * **一视同仁地当作无权访问**，这样就不会通过响应差异泄漏「某个 roomId 是否存在」。
     *
     * <p>本方法取代了原来的 {@code existsByRoom}：归属判定顺带回答了「存不存在」，
     * 不需要再单独查一次。
     */
    public static Long ownerId(String roomId) {
        CanvasDoc d = DaoSupport.canvasDoc().selectOne(
                Wrappers.lambdaQuery(CanvasDoc.class)
                        .select(CanvasDoc::getCreatedBy)
                        .eq(CanvasDoc::getRoomId, roomId));
        return d == null ? null : d.getCreatedBy();
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

    /**
     * 列表：可选标题关键字；按 updated_at 倒序（最近编辑的在前）。
     *
     * <p>⚠️ {@code createdBy} 为 {@code null} 表示**不过滤**（超管看全部）。
     * 调用方必须**显式**传 null 才会不过滤 —— 不要把「拿不到当前用户 id」也传成 null，
     * 那会静默退化成「所有人都看到全部画布」（fail-open）。
     */
    public static List<Map<String, Object>> list(String q, Long createdBy, int limit) {
        QueryWrapper<CanvasDoc> w = new QueryWrapper<>();
        if (q != null && !q.isBlank()) w.like("title", q.trim());
        if (createdBy != null) w.eq("created_by", createdBy);
        w.orderByDesc("updated_at");
        w.last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return RowUtil.rows(DaoSupport.canvasDoc().selectList(w), COLS);
    }
}
