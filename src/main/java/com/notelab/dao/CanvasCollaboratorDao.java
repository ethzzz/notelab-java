package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.CanvasCollaborator;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布协作者域 DAO：canvas_collaborator 的访问（画布级 ACL）。
 *
 * <p>⚠️ 本表**只存被邀请的协作者**：创建者由 {@code canvas_doc.created_by} 隐含、超管全权，
 * 两者都不落表。所以「查某人有没有权限」必须同时看 canvas_doc 与本表，别只看这里。
 */
public final class CanvasCollaboratorDao {

    private CanvasCollaboratorDao() {}

    /** 投影列序（列表接口的 JSON 键序，前端逐字段读） */
    private static final String[] COLS = {"room_id", "user_id", "permission", "invited_by", "created_at"};

    /** 邀请 / 改权限（同 (room_id,user_id) 覆盖权限） */
    public static void upsert(String roomId, long userId, String permission, Long invitedBy) {
        CanvasCollaborator existing = DaoSupport.canvasCollaborator().selectOne(
                Wrappers.lambdaQuery(CanvasCollaborator.class)
                        .eq(CanvasCollaborator::getRoomId, roomId)
                        .eq(CanvasCollaborator::getUserId, userId));
        if (existing != null) {
            DaoSupport.canvasCollaborator().update(null, Wrappers.lambdaUpdate(CanvasCollaborator.class)
                    .eq(CanvasCollaborator::getId, existing.getId())
                    .set(CanvasCollaborator::getPermission, permission));
            return;
        }
        CanvasCollaborator c = new CanvasCollaborator();
        c.setRoomId(roomId);
        c.setUserId(userId);
        c.setPermission(permission);
        c.setInvitedBy(invitedBy);
        try {
            DaoSupport.canvasCollaborator().insert(c);
        } catch (DuplicateKeyException e) {
            // 并发同时邀请同一人：唯一键兜住，退化为「改权限」
            DaoSupport.canvasCollaborator().update(null, Wrappers.lambdaUpdate(CanvasCollaborator.class)
                    .eq(CanvasCollaborator::getRoomId, roomId)
                    .eq(CanvasCollaborator::getUserId, userId)
                    .set(CanvasCollaborator::getPermission, permission));
        }
    }

    /** 某一画布的协作者列表（含权限） */
    public static List<Map<String, Object>> listByRoom(String roomId) {
        return RowUtil.rows(DaoSupport.canvasCollaborator().selectList(
                Wrappers.lambdaQuery(CanvasCollaborator.class)
                        .eq(CanvasCollaborator::getRoomId, roomId)
                        .orderByAsc(CanvasCollaborator::getId)), COLS);
    }

    /**
     * 一次取多个房间的协作者，返回 roomId → 协作者行列表。
     *
     * <p>为什么批量：列表页要显示每行的协作者，逐房间查就是 N+1（画布多起来直接拖垮列表）。
     * 一次 {@code IN (...)} 取回再在内存分组。
     */
    public static Map<String, List<Map<String, Object>>> groupByRooms(List<String> roomIds) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (roomIds == null || roomIds.isEmpty()) return out;
        for (Map<String, Object> r : RowUtil.rows(DaoSupport.canvasCollaborator().selectList(
                Wrappers.lambdaQuery(CanvasCollaborator.class)
                        .in(CanvasCollaborator::getRoomId, roomIds)
                        .orderByAsc(CanvasCollaborator::getId)), COLS)) {
            out.computeIfAbsent(String.valueOf(r.get("room_id")), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    /** 该用户在某画布上的协作者权限（view/edit）；不是协作者返回 null */
    public static String permissionOf(String roomId, long userId) {
        CanvasCollaborator c = DaoSupport.canvasCollaborator().selectOne(
                Wrappers.lambdaQuery(CanvasCollaborator.class)
                        .eq(CanvasCollaborator::getRoomId, roomId)
                        .eq(CanvasCollaborator::getUserId, userId)
                        .select(CanvasCollaborator::getPermission));
        return c == null ? null : c.getPermission();
    }

    /** 移除协作者。返回删除行数（0 = 本来就不是协作者） */
    public static int remove(String roomId, long userId) {
        return DaoSupport.canvasCollaborator().delete(Wrappers.lambdaQuery(CanvasCollaborator.class)
                .eq(CanvasCollaborator::getRoomId, roomId)
                .eq(CanvasCollaborator::getUserId, userId));
    }

    /** 该画布全部协作者的 user_id（列表页算「我参与了多少」用，顺带做权限判定） */
    public static Set<Long> userIdsOf(String roomId) {
        Set<Long> out = new LinkedHashSet<>();
        for (CanvasCollaborator c : DaoSupport.canvasCollaborator().selectList(
                Wrappers.lambdaQuery(CanvasCollaborator.class)
                        .eq(CanvasCollaborator::getRoomId, roomId)
                        .select(CanvasCollaborator::getUserId))) {
            if (c.getUserId() != null) out.add(c.getUserId());
        }
        return out;
    }

    /** 删除画布时清掉它的授权行（否则残留孤行） */
    public static int deleteByRoom(String roomId) {
        return DaoSupport.canvasCollaborator().delete(
                Wrappers.lambdaQuery(CanvasCollaborator.class).eq(CanvasCollaborator::getRoomId, roomId));
    }

    /**
     * 全部孤立的授权行（room_id 已不在 canvas_doc 里）。
     *
     * <p>给画布对账用：画布删除是跨表两步，中途失败会留下没有画布的授权行。
     * ⚠️ 用 NOT IN 子查询而不是 JOIN DELETE —— 本表与 canvas_doc 在同一个库，子查询最直白。
     */
    public static List<String> orphanRoomIds() {
        QueryWrapper<CanvasCollaborator> w = new QueryWrapper<>();
        w.select("DISTINCT room_id");
        w.notInSql("room_id", "SELECT room_id FROM canvas_doc");
        List<String> out = new ArrayList<>();
        for (CanvasCollaborator c : DaoSupport.canvasCollaborator().selectList(w)) {
            if (c.getRoomId() != null) out.add(c.getRoomId());
        }
        return out;
    }
}
