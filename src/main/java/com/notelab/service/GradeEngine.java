package com.notelab.service;

import com.notelab.common.JsonUtil;
import com.notelab.infra.LlmHealth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 翻译练习判分引擎：**双模式 + 自动降级**。
 *
 * <p>优先级：
 * <ol>
 *   <li>{@code llm} —— 大模型可用时，原样走 {@link TranslateService#grade}（逻辑一行未改）</li>
 *   <li>{@code local} —— 大模型不可用，但有参考译文：本地确定性比对（词级编辑距离）</li>
 *   <li>{@code manual} —— 大模型不可用且无参考译文：不判分，交还用户自评</li>
 * </ol>
 *
 * <p>为什么这样设计：2026-09-29 实测全站 key 全部 401，翻译练习彻底不可用。但库里**已经存了
 * {@code ref_en}（参考译文）与 {@code corrected}（批改后的正确译文）** —— 复习与自测场景下
 * 并不需要再问模型一句"这对不对"，对着标准答案做确定性比对就够了（即 Anki 的 type-in answer 模式）。
 *
 * <p>三种模式输出**同一套字段**（accurate/score/corrected/explanation/errors），调用方无需分支。
 * 额外返回 {@code mode}，供前端**诚实标注**当前是哪种判分 —— 绝不能把本地比对伪装成 AI 批改。
 */
public final class GradeEngine {

    /** 判分结果（accurate/score 在 manual 模式下为 null，表示"未判分"） */
    public static final class Grade {
        public final String mode;
        public final Boolean accurate;
        public final Integer score;
        public final String corrected;
        public final String explanation;
        public final List<Map<String, Object>> errors;
        public final String errorsJson;
        public final String model;

        Grade(String mode, Boolean accurate, Integer score, String corrected, String explanation,
              List<Map<String, Object>> errors, String errorsJson, String model) {
            this.mode = mode;
            this.accurate = accurate;
            this.score = score;
            this.corrected = corrected;
            this.explanation = explanation;
            this.errors = errors;
            this.errorsJson = errorsJson;
            this.model = model;
        }
    }

    private GradeEngine() {}

    /** 判分入口：永不抛异常（大模型失败自动降级），调用方可直接使用 */
    public static Grade grade(String zh, String en, String ref) {
        if (LlmHealth.available()) {
            try {
                TranslateService.Grade g = TranslateService.grade(zh, en, ref);
                return new Grade("llm", g.accurate, g.score, g.corrected, g.explanation,
                        g.errors, g.errorsJson, g.model);
            } catch (Exception e) {
                // 探测说可用但实际失败：立刻标记为不可用，缓存期内不再打上游（同时也是 401 降噪）
                LlmHealth.markDown("判分调用失败：" + e.getMessage());
            }
        }
        return localGrade(en, ref);
    }

    // ================= 本地对照判分 =================

    private static Grade localGrade(String en, String ref) {
        if (ref == null || ref.isBlank()) {
            return new Grade("manual", null, null, en,
                    "该句暂无参考译文，且大模型批改当前不可用，无法自动判分。请对照中文原句自行核对。",
                    List.of(), "[]", "manual");
        }
        double ratio = similarity(en, ref);
        boolean accurate;
        int score;
        if (ratio >= 0.999) {
            accurate = true;
            score = 100;
        } else if (ratio >= 0.90) {
            // 同义改写：认可，但扣一点分
            accurate = true;
            score = 88 + (int) Math.round((ratio - 0.90) / 0.10 * 11);
        } else if (ratio >= 0.70) {
            accurate = false;
            score = 60 + (int) Math.round((ratio - 0.70) / 0.20 * 24);
        } else {
            accurate = false;
            score = 30 + (int) Math.round(ratio / 0.70 * 29);
        }
        score = Math.max(0, Math.min(100, score));

        List<Map<String, Object>> errors = accurate ? List.of() : List.of(error(ratio, en, ref));
        String note = "本地对照判分（AI 批改暂不可用）：与参考译文相似度 " + pct(ratio) + "%。" + diffNote(en, ref);
        return new Grade("local", accurate, score, accurate ? en : ref, note, errors,
                JsonUtil.write(errors), "local-diff");
    }

    private static Map<String, Object> error(double ratio, String en, String ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", ratio >= 0.70 ? "用词" : "其它");
        m.put("original", truncate(en, 200));
        m.put("suggestion", truncate(ref, 200));
        m.put("note", "本地对照：相似度 " + pct(ratio) + "%，仅供参考；AI 批改恢复后会给出逐点讲解");
        return m;
    }

    // ---------------- 文本处理 ----------------

    /** 词序列相似度 = 1 - 编辑距离 / 较长序列长度，落到 [0,1] */
    static double similarity(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() && tb.isEmpty()) return 1.0;
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        int dist = editDistance(ta, tb);
        double r = 1.0 - (double) dist / Math.max(ta.size(), tb.size());
        return Math.max(0.0, Math.min(1.0, r));
    }

    /** 归一化 + 分词：小写、弯引号拉直、非字母数字撇号一律视为分隔符 */
    static List<String> tokens(String s) {
        if (s == null) return List.of();
        String n = s.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'');
        n = n.replaceAll("[^a-z0-9'\\s]", " ").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String w : n.split(" ")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** 词级编辑距离（Levenshtein，滚动数组） */
    static int editDistance(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(cur, 0, prev, 0, m + 1);
        }
        return prev[m];
    }

    /** 词频多重集差异：告诉用户"缺了什么、多了什么"，比单纯一个分数有用 */
    static String diffNote(String en, String ref) {
        Map<String, Integer> ca = counts(tokens(en));
        Map<String, Integer> cb = counts(tokens(ref));
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cb.entrySet()) {
            for (int i = ca.getOrDefault(e.getKey(), 0); i < e.getValue(); i++) missing.add(e.getKey());
        }
        for (Map.Entry<String, Integer> e : ca.entrySet()) {
            for (int i = cb.getOrDefault(e.getKey(), 0); i < e.getValue(); i++) extra.add(e.getKey());
        }
        StringBuilder sb = new StringBuilder();
        if (!missing.isEmpty()) sb.append("未用上：").append(String.join("、", limit(missing, 8))).append("。");
        if (!extra.isEmpty()) sb.append("多出：").append(String.join("、", limit(extra, 8))).append("。");
        return sb.toString();
    }

    private static Map<String, Integer> counts(List<String> ws) {
        Map<String, Integer> m = new TreeMap<>();
        for (String w : ws) m.merge(w, 1, Integer::sum);
        return m;
    }

    private static List<String> limit(List<String> l, int n) {
        return l.size() <= n ? l : new ArrayList<>(l.subList(0, n));
    }

    private static String pct(double r) {
        return Math.round(r * 100) + "%";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
