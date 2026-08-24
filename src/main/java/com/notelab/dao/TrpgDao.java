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

    /** 原列表 SQL 投影列序（B/C 拆分阶段2：尾部新增 published，存量接口仅增字段） */
    private static final String[] SCEN_LIST_COLS = {"id", "user_id", "title", "genre", "summary", "created_at", "updated_at", "published"};
    /** 原 SELECT * 列序（表列序；published 为阶段2补列，在表尾） */
    private static final String[] SCEN_ALL_COLS = {"id", "user_id", "title", "genre", "summary", "config_json", "scenario_json", "created_at", "updated_at", "published"};
    /** 原 getTrpgPlay JOIN SQL 列序：p.* + 别名列（scope 为阶段2补列，在表尾、别名列之前） */
    private static final String[] PLAY_ALL_COLS = {"id", "scenario_id", "user_id", "current_node", "state", "ending_title", "steps", "history_json", "created_at", "updated_at", "scope", "scenario_title", "scenario_genre"};
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

    /** B/C 拆分阶段2（C 端）：仅已发布剧本，字段与 B 端列表同风格 */
    public static List<Map<String, Object>> listPublishedTrpgScenarios() {
        return RowUtil.rows(DaoSupport.trpgScenario().selectList(
                Wrappers.lambdaQuery(TrpgScenario.class)
                        .eq(TrpgScenario::getPublished, 1)
                        .orderByDesc(TrpgScenario::getId)), SCEN_LIST_COLS);
    }

    /** B/C 拆分阶段2（B 端发布/下架）：仅更新 published 标记 */
    public static void setTrpgScenarioPublished(long id, int published) {
        DaoSupport.trpgScenario().update(Wrappers.lambdaUpdate(TrpgScenario.class)
                .eq(TrpgScenario::getId, id)
                .set(TrpgScenario::getPublished, published));
    }

    /** 两条 DELETE（对局 + 剧本）原子执行：事务包裹 */
    public static void deleteTrpgScenario(long id, long userId) {
        DaoSupport.tx().executeWithoutResult(status -> {
            DaoSupport.trpgPlaythrough().delete(
                    Wrappers.lambdaQuery(TrpgPlaythrough.class)
                            .eq(TrpgPlaythrough::getScenarioId, id)
                            .eq(TrpgPlaythrough::getUserId, userId));
            DaoSupport.trpgScenario().delete(
                    Wrappers.lambdaQuery(TrpgScenario.class)
                            .eq(TrpgScenario::getId, id)
                            .eq(TrpgScenario::getUserId, userId));
        });
    }

    public static long createTrpgPlay(long scenarioId, long userId, String startNode) {
        // scope 不设值：DB 默认 'b'（B 端既有路径行为不变）
        return createTrpgPlay(scenarioId, userId, startNode, null);
    }

    /** B/C 拆分阶段2：带归属开局（C 端传 "c"；null 走 DB 默认 'b'） */
    public static long createTrpgPlay(long scenarioId, long userId, String startNode, String scope) {
        TrpgPlaythrough p = new TrpgPlaythrough();
        p.setScenarioId(scenarioId);
        p.setUserId(userId);
        p.setCurrentNode(startNode);
        p.setHistoryJson("[]");
        if (scope != null) p.setScope(scope);
        DaoSupport.trpgPlaythrough().insert(p);
        return p.getId() == null ? -1 : p.getId();
    }

    /** 双表 JOIN（Mapper 注解保留原 SQL），经 RowUtil 归一化列序/日期格式 */
    public static Map<String, Object> getTrpgPlay(long id) {
        return RowUtil.norm(DaoSupport.trpgPlaythrough().getPlayJoined(id), PLAY_ALL_COLS);
    }

    public static List<Map<String, Object>> listTrpgPlays(long userId) {
        // B/C 拆分阶段2：B 端列表补 scope='b' 过滤（存量数据默认 'b'，结果集等价）
        return listTrpgPlays("b", userId);
    }

    /** B/C 拆分阶段2：按归属过滤的对局列表（B 端 "b" / C 端 "c"） */
    public static List<Map<String, Object>> listTrpgPlays(String scope, long userId) {
        return RowUtil.norms(DaoSupport.trpgPlaythrough().listPlaysJoined(scope, userId), PLAY_LIST_COLS);
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
     * 启动时把残留的 running 任务标记为中断，由 Bootstrap.run() 在 Db.init 之后调用。
     * （历史上该调用在 TrpgController 的 @PostConstruct 中，早于 Db.init 从未生效；已于 2026-08-24 修复。）
     * DaoSupport.ready() 仅作守护，正常启动路径下恒为就绪。
     */
    public static void abortStaleTrpgGenTasks() {
        if (!DaoSupport.ready()) return;
        DaoSupport.trpgGenTask().abortStale();
    }
}
