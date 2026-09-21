package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.EnTrGroup;
import com.notelab.model.entity.EnTrSentence;
import com.notelab.model.entity.EnTrSubmission;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 每日英语翻译练习域 DAO：en_tr_groups / en_tr_sentences / en_tr_submissions 三表访问。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 EnglishDao / TrpgDao。
 */
public final class TranslateDao {

    private TranslateDao() {}

    /** en_tr_groups 全列序（表列序） */
    private static final String[] GROUP_ALL_COLS = {"id", "title", "status", "activated_date", "source",
            "scenario", "note", "created_by", "created_at", "updated_at"};
    /** 组列表投影列序（含 sentence_count 子查询列） */
    private static final String[] GROUP_LIST_COLS = {"id", "title", "status", "activated_date", "source",
            "scenario", "note", "created_by", "created_at", "updated_at", "sentence_count"};
    /** en_tr_sentences 全列序（表列序） */
    private static final String[] SENTENCE_ALL_COLS = {"id", "group_id", "tier", "sort_order", "zh_text", "ref_en", "created_at"};
    /** en_tr_submissions 全列序（表列序） */
    private static final String[] SUB_ALL_COLS = {"id", "c_user_id", "sentence_id", "group_id", "submit_date",
            "en_text", "accurate", "score", "corrected", "explanation", "errors_json", "model", "created_at", "updated_at"};
    /** 提交 JOIN 句子/组 的投影列序 */
    private static final String[] SUB_JOIN_COLS = {"id", "sentence_id", "group_id", "submit_date", "en_text",
            "accurate", "score", "corrected", "explanation", "errors_json", "model", "created_at", "updated_at",
            "tier", "sort_order", "zh_text", "ref_en", "group_title"};

    // ================= en_tr_groups =================

    /** 新建句子组（status 默认 draft），返回自增 id */
    public static long createGroup(String title, String source, String scenario, String note, Long createdBy) {
        EnTrGroup g = new EnTrGroup();
        g.setTitle(title);
        g.setStatus("draft");
        g.setSource(source == null || source.isBlank() ? "manual" : source);
        g.setScenario(scenario == null ? "" : scenario);
        g.setNote(note == null ? "" : note);
        g.setCreatedBy(createdBy);
        DaoSupport.enTrGroup().insert(g);
        return g.getId() == null ? -1 : g.getId();
    }

    /** 组列表（含句子数），按 id 倒序 */
    public static List<Map<String, Object>> listGroups() {
        return RowUtil.norms(DaoSupport.enTrGroup().selectGroupsWithCount(), GROUP_LIST_COLS);
    }

    public static Map<String, Object> getGroup(long id) {
        return RowUtil.row(DaoSupport.enTrGroup().selectById(id), GROUP_ALL_COLS);
    }

    /** 元信息/状态部分更新：null 表示不修改（activated_date 传 null 表示不改；需清空时由调用方走 clearActivatedDate） */
    public static void updateGroupFields(long id, String title, String status, String scenario, String note, LocalDate activatedDate) {
        DaoSupport.enTrGroup().update(Wrappers.lambdaUpdate(EnTrGroup.class)
                .eq(EnTrGroup::getId, id)
                .set(title != null, EnTrGroup::getTitle, title)
                .set(status != null, EnTrGroup::getStatus, status)
                .set(scenario != null, EnTrGroup::getScenario, scenario)
                .set(note != null, EnTrGroup::getNote, note)
                .set(activatedDate != null, EnTrGroup::getActivatedDate, activatedDate));
    }

    /** 来源改写：manual → import / llm（首次批量填充时定型） */
    public static void setGroupSource(long id, String source) {
        DaoSupport.enTrGroup().update(Wrappers.lambdaUpdate(EnTrGroup.class)
                .eq(EnTrGroup::getId, id)
                .set(EnTrGroup::getSource, source));
    }

    /** activated_date 显式置空（B 端撤销强制发布 / 退回草稿） */
    public static void clearActivatedDate(long id) {
        DaoSupport.enTrGroup().update(Wrappers.lambdaUpdate(EnTrGroup.class)
                .eq(EnTrGroup::getId, id)
                .set(EnTrGroup::getActivatedDate, null));
    }

    /** 删组：句子由外键 ON DELETE CASCADE 级联删；提交记录保留（句子 id 仍可追溯，JOIN 为 null） */
    public static void deleteGroup(long id) {
        DaoSupport.enTrGroup().deleteById(id);
    }

    /** 当天已激活（used + activated_date=指定日期）的组：无则 null */
    public static Map<String, Object> getActivatedGroup(LocalDate date) {
        List<EnTrGroup> rows = DaoSupport.enTrGroup().selectList(
                Wrappers.lambdaQuery(EnTrGroup.class)
                        .eq(EnTrGroup::getStatus, "used")
                        .eq(EnTrGroup::getActivatedDate, date)
                        .orderByDesc(EnTrGroup::getId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : RowUtil.row(rows.get(0), GROUP_ALL_COLS);
    }

    /** 是否已有某天激活的组（定时任务幂等守护） */
    public static boolean hasActivatedOn(LocalDate date) {
        Long n = DaoSupport.enTrGroup().selectCount(
                Wrappers.lambdaQuery(EnTrGroup.class)
                        .eq(EnTrGroup::getStatus, "used")
                        .eq(EnTrGroup::getActivatedDate, date));
        return n != null && n > 0;
    }

    /** 入队队列的队首（status='queued' 中 created_at 最早、同刻 id 最小）：无则 null */
    public static Map<String, Object> firstQueuedGroup() {
        List<EnTrGroup> rows = DaoSupport.enTrGroup().selectList(
                Wrappers.lambdaQuery(EnTrGroup.class)
                        .eq(EnTrGroup::getStatus, "queued")
                        .orderByAsc(EnTrGroup::getCreatedAt)
                        .orderByAsc(EnTrGroup::getId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : RowUtil.row(rows.get(0), GROUP_ALL_COLS);
    }

    /** queued 组数量（B 端概览/日志用） */
    public static long countQueued() {
        Long n = DaoSupport.enTrGroup().selectCount(
                Wrappers.lambdaQuery(EnTrGroup.class).eq(EnTrGroup::getStatus, "queued"));
        return n == null ? 0 : n;
    }

    /**
     * 激活队首组（CAS 语义）：仅当该组仍为 queued 时置 used + activated_date，返回受影响行数。
     * 并发/重复触发下只会有一个调用成功（affected=1），其余为 0。
     */
    public static int activateGroup(long id, LocalDate date) {
        return DaoSupport.enTrGroup().update(Wrappers.lambdaUpdate(EnTrGroup.class)
                .eq(EnTrGroup::getId, id)
                .eq(EnTrGroup::getStatus, "queued")
                .set(EnTrGroup::getStatus, "used")
                .set(EnTrGroup::getActivatedDate, date));
    }

    // ================= en_tr_sentences =================

    /** 新增句子，返回自增 id；sort_order 为 null 时自动排到该阶梯末尾 */
    public static long createSentence(long groupId, int tier, String zhText, String refEn, Integer sortOrder) {
        EnTrSentence s = new EnTrSentence();
        s.setGroupId(groupId);
        s.setTier(tier);
        s.setZhText(zhText);
        s.setRefEn(refEn);
        s.setSortOrder(sortOrder != null ? sortOrder : nextSortOrder(groupId, tier));
        DaoSupport.enTrSentence().insert(s);
        return s.getId() == null ? -1 : s.getId();
    }

    /** 某阶梯下一个排序号（现有最大 + 1，空阶梯从 0 开始） */
    public static int nextSortOrder(long groupId, int tier) {
        List<EnTrSentence> rows = DaoSupport.enTrSentence().selectList(
                Wrappers.lambdaQuery(EnTrSentence.class)
                        .eq(EnTrSentence::getGroupId, groupId)
                        .eq(EnTrSentence::getTier, tier)
                        .orderByDesc(EnTrSentence::getSortOrder)
                        .last("LIMIT 1"));
        if (rows.isEmpty() || rows.get(0).getSortOrder() == null) return 0;
        return rows.get(0).getSortOrder() + 1;
    }

    /** 组内句子（按 tier, sort_order, id 排序） */
    public static List<Map<String, Object>> listSentences(long groupId) {
        return RowUtil.rows(DaoSupport.enTrSentence().selectList(
                Wrappers.lambdaQuery(EnTrSentence.class)
                        .eq(EnTrSentence::getGroupId, groupId)
                        .orderByAsc(EnTrSentence::getTier)
                        .orderByAsc(EnTrSentence::getSortOrder)
                        .orderByAsc(EnTrSentence::getId)), SENTENCE_ALL_COLS);
    }

    public static Map<String, Object> getSentence(long id) {
        return RowUtil.row(DaoSupport.enTrSentence().selectById(id), SENTENCE_ALL_COLS);
    }

    /** 组内是否已存在同样的中文原句（导入去重用） */
    public static boolean sentenceExists(long groupId, String zhText) {
        Long n = DaoSupport.enTrSentence().selectCount(
                Wrappers.lambdaQuery(EnTrSentence.class)
                        .eq(EnTrSentence::getGroupId, groupId)
                        .eq(EnTrSentence::getZhText, zhText));
        return n != null && n > 0;
    }

    /** 部分字段更新：null 表示不修改 */
    public static void updateSentenceFields(long id, String zhText, String refEn, Integer tier, Integer sortOrder) {
        DaoSupport.enTrSentence().update(Wrappers.lambdaUpdate(EnTrSentence.class)
                .eq(EnTrSentence::getId, id)
                .set(zhText != null, EnTrSentence::getZhText, zhText)
                .set(refEn != null, EnTrSentence::getRefEn, refEn)
                .set(tier != null, EnTrSentence::getTier, tier)
                .set(sortOrder != null, EnTrSentence::getSortOrder, sortOrder));
    }

    public static void deleteSentence(long id) {
        DaoSupport.enTrSentence().deleteById(id);
    }

    public static long countSentences(long groupId) {
        Long n = DaoSupport.enTrSentence().selectCount(
                Wrappers.lambdaQuery(EnTrSentence.class).eq(EnTrSentence::getGroupId, groupId));
        return n == null ? 0 : n;
    }

    // ================= en_tr_submissions =================

    /** upsert：同 (c_user_id, sentence_id, submit_date) 覆盖旧判分结果（去重覆盖核心） */
    public static void upsertSubmission(long cUserId, long sentenceId, long groupId, LocalDate submitDate,
                                        String enText, Integer accurate, Integer score, String corrected,
                                        String explanation, String errorsJson, String model) {
        DaoSupport.enTrSubmission().upsert(cUserId, sentenceId, groupId, submitDate, enText, accurate, score,
                corrected, explanation, errorsJson, model);
    }

    /** 某用户对指定句子集合的提交（C 端 /today 回填）；sentenceIds 为空时返回空列表 */
    public static List<Map<String, Object>> listSubmissionsForSentences(long cUserId, LocalDate date, List<Long> sentenceIds) {
        if (sentenceIds == null || sentenceIds.isEmpty()) return List.of();
        QueryWrapper<EnTrSubmission> w = new QueryWrapper<>();
        w.eq("c_user_id", cUserId).eq("submit_date", date).in("sentence_id", sentenceIds);
        return RowUtil.rows(DaoSupport.enTrSubmission().selectList(w), SUB_ALL_COLS);
    }

    /** 某用户某天全部提交（JOIN 句子/组，历史查询） */
    public static List<Map<String, Object>> listSubmissionsByDate(long cUserId, LocalDate date) {
        return RowUtil.norms(DaoSupport.enTrSubmission().selectByUserDate(cUserId, date), SUB_JOIN_COLS);
    }
}
