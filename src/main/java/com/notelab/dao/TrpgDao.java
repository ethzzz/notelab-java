package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.TrpgGenTask;
import com.notelab.model.entity.TrpgPlaythrough;
import com.notelab.model.entity.TrpgScenario;

import java.util.List;
import java.util.Map;

/** TRPG 跑团域 DAO：trpg_scenarios / trpg_playthroughs / trpg_gen_tasks 三表访问（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class TrpgDao {

    private TrpgDao() {}

    /** 原列表 SQL 投影列序 */
    private static final String[] SCEN_LIST_COLS = {"id", "user_id", "title", "genre", "summary", "created_at", "updated_at"};
    /** 原 SELECT * 列序（表列序） */
    private static final String[] SCEN_ALL_COLS = {"id", "user_id", "title", "genre", "summary", "config_json", "scenario_json", "created_at", "updated_at"};
    /** 原 getTrpgPlay JOIN SQL 列序：p.* + 别名列 */
    private static final String[] PLAY_ALL_COLS = {"id", "scenario_id", "user_id", "current_node", "state", "ending_title", "steps", "history_json", "created_at", "updated_at", "scenario_title", "scenario_genre"};
    /** 原 listTrpgPlays JOIN SQL 投影列序 */
    private static final String[] PLAY_LIST_COLS = {"id", "scenario_id", "current_node", "state", "ending_title", "steps", "updated_at", "scenario_title", "scenario_genre"};
    /** 原 SELECT * 列序（表列序） */
    private static final String[] TASK_ALL_COLS = {"id", "user_id", "state", "config_json", "scenario_id", "error", "created_at", "updated_at"};

    public static long createTrpgScenario(long userId, String title, String genre, String summary, String configJson, String scenarioJson) {
        TrpgScenario s = new TrpgScenario();
        s.setUserId(userId);
        s.setTitle(title);
        s.setGenre(genre);
        s.setSummary(summary);
        s.setConfigJson(configJson);
        s.setScenarioJson(scenarioJson);
        DaoSupport.trpgScenario().insert(s);
        return s.getId() == null ? -1 : s.getId();
    }

    public static List<Map<String, Object>> listTrpgScenarios(long userId) {
        return RowUtil.rows(DaoSupport.trpgScenario().selectList(
                Wrappers.lambdaQuery(TrpgScenario.class)
                        .eq(TrpgScenario::getUserId, userId)
                        .orderByDesc(TrpgScenario::getId)), SCEN_LIST_COLS);
    }

    public static Map<String, Object> getTrpgScenario(long id) {
        return RowUtil.row(DaoSupport.trpgScenario().selectById(id), SCEN_ALL_COLS);
    }

    public static void deleteTrpgScenario(long id, long userId) {
        DaoSupport.trpgPlaythrough().delete(
                Wrappers.lambdaQuery(TrpgPlaythrough.class)
                        .eq(TrpgPlaythrough::getScenarioId, id)
                        .eq(TrpgPlaythrough::getUserId, userId));
        DaoSupport.trpgScenario().delete(
                Wrappers.lambdaQuery(TrpgScenario.class)
                        .eq(TrpgScenario::getId, id)
                        .eq(TrpgScenario::getUserId, userId));
    }

    public static long createTrpgPlay(long scenarioId, long userId, String startNode) {
        TrpgPlaythrough p = new TrpgPlaythrough();
        p.setScenarioId(scenarioId);
        p.setUserId(userId);
        p.setCurrentNode(startNode);
        p.setHistoryJson("[]");
        DaoSupport.trpgPlaythrough().insert(p);
        return p.getId() == null ? -1 : p.getId();
    }

    /** 双表 JOIN（Mapper 注解保留原 SQL），经 RowUtil 归一化列序/日期格式 */
    public static Map<String, Object> getTrpgPlay(long id) {
        return RowUtil.norm(DaoSupport.trpgPlaythrough().getPlayJoined(id), PLAY_ALL_COLS);
    }

    public static List<Map<String, Object>> listTrpgPlays(long userId) {
        return RowUtil.norms(DaoSupport.trpgPlaythrough().listPlaysJoined(userId), PLAY_LIST_COLS);
    }

    public static void updateTrpgPlay(long id, String currentNode, String state, String endingTitle, int steps, String historyJson) {
        // 与原 UPDATE 一致：全部列显式覆盖（含 null/空串）
        DaoSupport.trpgPlaythrough().update(Wrappers.lambdaUpdate(TrpgPlaythrough.class)
                .eq(TrpgPlaythrough::getId, id)
                .set(TrpgPlaythrough::getCurrentNode, currentNode)
                .set(TrpgPlaythrough::getState, state)
                .set(TrpgPlaythrough::getEndingTitle, endingTitle)
                .set(TrpgPlaythrough::getSteps, steps)
                .set(TrpgPlaythrough::getHistoryJson, historyJson));
    }

    public static void deleteTrpgPlay(long id, long userId) {
        DaoSupport.trpgPlaythrough().delete(
                Wrappers.lambdaQuery(TrpgPlaythrough.class)
                        .eq(TrpgPlaythrough::getId, id)
                        .eq(TrpgPlaythrough::getUserId, userId));
    }

    // ---------- TRPG 生成任务（异步化：规避长请求被代理层超时断开） ----------
    public static long createTrpgGenTask(long userId, String configJson) {
        TrpgGenTask t = new TrpgGenTask();
        t.setUserId(userId);
        t.setConfigJson(configJson);
        DaoSupport.trpgGenTask().insert(t);
        return t.getId() == null ? -1 : t.getId();
    }

    public static Map<String, Object> getTrpgGenTask(long id) {
        return RowUtil.row(DaoSupport.trpgGenTask().selectById(id), TASK_ALL_COLS);
    }

    /** 完成态（原 SQL 由 Mapper 注解保留：state='done' + error 置空串） */
    public static void finishTrpgGenTask(long id, long scenarioId) {
        DaoSupport.trpgGenTask().finishTask(id, scenarioId);
    }

    public static void failTrpgGenTask(long id, String error) {
        String e = error == null ? "" : (error.length() > 480 ? error.substring(0, 480) : error);
        DaoSupport.trpgGenTask().failTask(id, e);
    }

    /**
     * 启动时把残留的 running 任务标记为中断。
     * 注意：迁移前该调用发生在 TrpgController 的 @PostConstruct（早于 Db.init），实际总是静默跳过；
     * 这里以 DaoSupport.ready() 保持同样语义——上下文未就绪时安全跳过。
     */
    public static void abortStaleTrpgGenTasks() {
        if (!DaoSupport.ready()) return;
        DaoSupport.trpgGenTask().abortStale();
    }
}
