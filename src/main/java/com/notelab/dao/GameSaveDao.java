package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.CGameSave;

import java.util.List;
import java.util.Map;

/**
 * C 端游戏存档 DAO：按 (user_id, game_code) 读写各游戏自有结构的 JSON 存档。
 * 静态门面，风格对齐 CUserDao。
 */
public final class GameSaveDao {

    private GameSaveDao() {}

    private static final String[] COLS = {"id", "user_id", "game_code", "data_json", "updated_at"};

    /** 取某用户某游戏的存档（无则返回 null） */
    public static Map<String, Object> get(long userId, String gameCode) {
        CGameSave s = DaoSupport.cGameSave().selectOne(
                Wrappers.lambdaQuery(CGameSave.class)
                        .eq(CGameSave::getUserId, userId)
                        .eq(CGameSave::getGameCode, gameCode));
        return RowUtil.row(s, COLS);
    }

    /**
     * 列出某用户全部存档的**摘要**（不含 data_json，避免把各游戏的大 JSON 一起拉回来），
     * 按最近更新倒序。供 C 端个人中心的「云端存档」卡片使用（PRD-P5）。
     *
     * <p>⚠️ 只反映**用通用 KV 存档的游戏**（见 GameSaveController）。TRPG 走 trpg_playthroughs、
     * 地牢走 /api/c/dungeon/save、爬塔与雷霆无服务端记录，都不会出现在这里 —— 调用方不要
     * 把它当成「全部游玩记录」展示。
     */
    public static List<Map<String, Object>> listByUser(long userId) {
        List<CGameSave> rows = DaoSupport.cGameSave().selectList(
                Wrappers.lambdaQuery(CGameSave.class)
                        .eq(CGameSave::getUserId, userId)
                        .orderByDesc(CGameSave::getUpdatedAt));
        return RowUtil.rows(rows, "game_code", "updated_at");
    }

    /** 写入（不存在则插入，存在则更新 data_json） */
    public static void upsert(long userId, String gameCode, String dataJson) {
        CGameSave existing = DaoSupport.cGameSave().selectOne(
                Wrappers.lambdaQuery(CGameSave.class)
                        .eq(CGameSave::getUserId, userId)
                        .eq(CGameSave::getGameCode, gameCode));
        if (existing == null) {
            CGameSave s = new CGameSave();
            s.setUserId(userId);
            s.setGameCode(gameCode);
            s.setDataJson(dataJson);
            DaoSupport.cGameSave().insert(s);
        } else {
            existing.setDataJson(dataJson);
            DaoSupport.cGameSave().updateById(existing);
        }
    }
}
