package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/** TRPG 跑团域 DAO：trpg_scenarios / trpg_playthroughs / trpg_gen_tasks 三表访问。 */
public final class TrpgDao {

    private TrpgDao() {}

    public static long createTrpgScenario(long userId, String title, String genre, String summary, String configJson, String scenarioJson) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO trpg_scenarios (user_id,title,genre,summary,config_json,scenario_json) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId); ps.setString(2, title); ps.setString(3, genre); ps.setString(4, summary);
            ps.setString(5, configJson); ps.setString(6, scenarioJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static List<Map<String, Object>> listTrpgScenarios(long userId) {
        return Db.queryAll("SELECT id,user_id,title,genre,summary,created_at,updated_at FROM trpg_scenarios WHERE user_id=? ORDER BY id DESC", userId);
    }

    public static Map<String, Object> getTrpgScenario(long id) {
        return Db.queryOne("SELECT * FROM trpg_scenarios WHERE id=?", id);
    }

    public static void deleteTrpgScenario(long id, long userId) {
        Db.exec("DELETE FROM trpg_playthroughs WHERE scenario_id=? AND user_id=?", id, userId);
        Db.exec("DELETE FROM trpg_scenarios WHERE id=? AND user_id=?", id, userId);
    }

    public static long createTrpgPlay(long scenarioId, long userId, String startNode) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO trpg_playthroughs (scenario_id,user_id,current_node,history_json) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scenarioId); ps.setLong(2, userId); ps.setString(3, startNode); ps.setString(4, "[]");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static Map<String, Object> getTrpgPlay(long id) {
        return Db.queryOne("SELECT p.*, s.title AS scenario_title, s.genre AS scenario_genre FROM trpg_playthroughs p JOIN trpg_scenarios s ON s.id=p.scenario_id WHERE p.id=?", id);
    }

    public static List<Map<String, Object>> listTrpgPlays(long userId) {
        return Db.queryAll("SELECT p.id,p.scenario_id,p.current_node,p.state,p.ending_title,p.steps,p.updated_at,s.title AS scenario_title,s.genre AS scenario_genre FROM trpg_playthroughs p JOIN trpg_scenarios s ON s.id=p.scenario_id WHERE p.user_id=? ORDER BY p.updated_at DESC LIMIT 50", userId);
    }

    public static void updateTrpgPlay(long id, String currentNode, String state, String endingTitle, int steps, String historyJson) {
        Db.exec("UPDATE trpg_playthroughs SET current_node=?,state=?,ending_title=?,steps=?,history_json=? WHERE id=?",
                currentNode, state, endingTitle, steps, historyJson, id);
    }

    public static void deleteTrpgPlay(long id, long userId) {
        Db.exec("DELETE FROM trpg_playthroughs WHERE id=? AND user_id=?", id, userId);
    }

    // ---------- TRPG 生成任务（异步化：规避长请求被代理层超时断开） ----------
    public static long createTrpgGenTask(long userId, String configJson) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO trpg_gen_tasks (user_id,config_json) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId); ps.setString(2, configJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static Map<String, Object> getTrpgGenTask(long id) {
        return Db.queryOne("SELECT * FROM trpg_gen_tasks WHERE id=?", id);
    }

    public static void finishTrpgGenTask(long id, long scenarioId) {
        Db.exec("UPDATE trpg_gen_tasks SET state='done', scenario_id=?, error='' WHERE id=?", scenarioId, id);
    }

    public static void failTrpgGenTask(long id, String error) {
        String e = error == null ? "" : (error.length() > 480 ? error.substring(0, 480) : error);
        Db.exec("UPDATE trpg_gen_tasks SET state='error', error=? WHERE id=?", e, id);
    }

    /** 启动时把残留的 running 任务标记为中断（进程重启即丢失，提示用户重新生成） */
    public static void abortStaleTrpgGenTasks() {
        Db.exec("UPDATE trpg_gen_tasks SET state='error', error='服务重启，生成任务中断，请重新生成' WHERE state='running'");
    }
}
