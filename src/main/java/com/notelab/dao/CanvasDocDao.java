package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.CanvasDoc;

import java.util.LinkedHashMap;
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
     * 列表：可选标题关键字 + 可选引擎；按 updated_at 倒序（最近编辑的在前）。
     *
     * <p>⚠️ **不再按 created_by 收窄**（2026-10-10 方案 C）：画布列表对全部 B 端账户可见，
     * 「能不能打开/改/删」由 {@code canvas_collaborator} 逐块决定，不由列表决定。
     * 因此本方法没有 owner 参数 —— 少一个参数就少一处「忘了传就静默看全部」的 fail-open 风险。
     */
    public static List<Map<String, Object>> list(String q, String engine, int limit) {
        QueryWrapper<CanvasDoc> w = new QueryWrapper<>();
        if (q != null && !q.isBlank()) w.like("title", q.trim());
        // engine 由调用方先过白名单再传进来；这里不做兜底，避免「写错的引擎静默变成不过滤」
        if (engine != null && !engine.isBlank()) w.eq("engine", engine);
        w.orderByDesc("updated_at");
        w.last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return RowUtil.rows(DaoSupport.canvasDoc().selectList(w), COLS);
    }

    /**
     * 全部画布元数据，键为 roomId —— **不设上限、不按 created_by 收窄**，只给「元数据 ↔ 内容」对账用。
     *
     * <p>⚠️ 刻意不用 {@link #list}：那个有 200 条上限，超了会把正常画布误报成「内容侧孤儿」，
     * 而对账的结论可能被用来删数据，不能有这种假阳性。本方法没有任何权限收窄 ——
     * <b>调用方必须先确认当前用户是超管</b>。
     */
    public static Map<String, Map<String, Object>> metaByRoom() {
        String[] cols = {"room_id", "title", "engine", "created_at", "updated_at"};
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : RowUtil.rows(DaoSupport.canvasDoc().selectList(
                Wrappers.lambdaQuery(CanvasDoc.class).select(
                        CanvasDoc::getRoomId, CanvasDoc::getTitle, CanvasDoc::getEngine,
                        CanvasDoc::getCreatedAt, CanvasDoc::getUpdatedAt)), cols)) {
            out.put(String.valueOf(r.get("room_id")), r);
        }
        return out;
    }
}
