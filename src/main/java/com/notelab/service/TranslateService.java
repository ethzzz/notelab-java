package com.notelab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import com.notelab.dao.TranslateDao;
import com.notelab.infra.QwenClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 每日英语翻译练习 · 业务逻辑层（C/B 两端共用）：
 *  - 大模型判分（非流式 QwenClient.complete，只输出 JSON 对象）
 *  - 大模型批量生成句子（只输出 JSON 数组）
 *  - 中文文本切句（批量导入）
 *  - 当日内容组装（组 + 句子 + 当前用户既有提交回填）
 * 密钥只从 AppConfig 读取，绝不打印/硬编码。
 */
public final class TranslateService {

    private TranslateService() {}

    /** 阶梯元信息：id / 名称 / 说明（前端展示用，键序固定） */
    public static List<Map<String, Object>> tiers() {
        return List.of(tier(1, "简单", "日常短句，主谓宾为主"),
                tier(2, "中等", "含从句、时态与常见搭配"),
                tier(3, "困难", "长句、抽象表达与地道用法"));
    }

    private static Map<String, Object> tier(int id, String name, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", id);
        m.put("name", name);
        m.put("desc", desc);
        return m;
    }

    // ================= 判分 =================

    /** 判分结果（accurate/score/corrected/explanation/errors + model） */
    public static final class Grade {
        public final boolean accurate;
        public final int score;
        public final String corrected;
        public final String explanation;
        public final String errorsJson;
        public final List<Map<String, Object>> errors;
        public final String model;

        Grade(boolean accurate, int score, String corrected, String explanation,
              String errorsJson, List<Map<String, Object>> errors, String model) {
            this.accurate = accurate;
            this.score = score;
            this.corrected = corrected;
            this.explanation = explanation;
            this.errorsJson = errorsJson;
            this.errors = errors;
            this.model = model;
        }
    }

    /** 判分调用失败（上游异常 / JSON 解析失败），由 Controller 转成明确 error 响应，不冒泡 500 */
    public static final class GradeException extends Exception {
        public GradeException(String message) { super(message); }
    }

    private static final String GRADE_SYSTEM = """
            你是一位严谨耐心的英语翻译老师，负责批改中文译英练习。
            你需要判断学生的英文译文是否准确表达了中文原句的意思，并给出评分、修正译文、中文讲解与逐点错误标注。
            评分标准：0-100 分整数，语义完整准确且表达自然得高分；有轻微语法/用词问题扣分较多；意思偏离或严重错误得低分。
            允许多样化的合理译法：参考译文仅供参考，不是唯一标准，只要意思准确、语法正确、表达自然即视为准确。
            译文准确时：accurate 为 true，errors 为空数组，explanation 用中文说明为什么这个译法是好的，score 给高分。
            译文有误时：accurate 为 false，逐条列出错误（类型从 语法/时态/用词/搭配/拼写/其它 中选一个），并给出修正后的完整英文译文。
            严格只输出一个 JSON 对象，不要输出 JSON 以外的任何内容，不要用代码块包裹，键顺序固定为：
            {"accurate": <bool>, "score": <0-100 整数>, "corrected": "<修正后的英文译文>", "explanation": "<中文总体讲解>", "errors": [{"type":"<语法|时态|用词|搭配|拼写|其它>","original":"<原译文中的片段>","suggestion":"<建议改法>","note":"<中文说明>"}]}""";

    /**
     * 调模型判分。zh 中文原句 / en 学生译文 / ref 参考译文（可空）。
     * 任何异常（超时、上游非 200、JSON 解析失败）统一抛 GradeException，交由 Controller 优雅降级。
     */
    public static Grade grade(String zh, String en, String ref) throws GradeException {
        String key = AppConfig.qwenKey();
        if (key.isEmpty() && AppConfig.qwenApiKeys().isEmpty()) throw new GradeException("未配置 QWEN_API_KEY");
        String userPrompt = "中文原句：\n" + zh + "\n\n"
                + "学生的英文译文：\n" + en + "\n\n"
                + ((ref == null || ref.isBlank()) ? "参考译文：（无，请自行判断译法合理性）\n\n" : "参考译文（仅供对照，不是唯一标准）：\n" + ref + "\n\n")
                + "请按系统提示的 JSON 结构输出批改结果：";
        String raw;
        try {
            raw = QwenClient.complete(AppConfig.qwenModel(),
                    List.of(Map.of("role", "system", "content", GRADE_SYSTEM),
                            Map.of("role", "user", "content", userPrompt)),
                    key, 90);
        } catch (Exception e) {
            throw new GradeException("判分失败，请重试");
        }
        JsonNode node = parseJsonObject(raw);
        if (node == null) throw new GradeException("判分失败，请重试");
        try {
            boolean accurate = node.path("accurate").asBoolean(false);
            int score = clampScore(node.path("score").asInt(-1));
            String corrected = text(node.path("corrected"));
            if (corrected.isEmpty()) corrected = en;
            String explanation = text(node.path("explanation"));
            List<Map<String, Object>> errors = parseErrors(node.path("errors"));
            if (accurate) errors = List.of();
            return new Grade(accurate, score, truncate(corrected, 500), explanation,
                    JsonUtil.write(errors), errors, AppConfig.qwenModel());
        } catch (Exception e) {
            throw new GradeException("判分失败，请重试");
        }
    }

    private static int clampScore(int s) {
        if (s < 0) return 0;
        return Math.min(s, 100);
    }

    /** errors 数组归一化：固定键序 type/original/suggestion/note，非对象元素丢弃 */
    private static List<Map<String, Object>> parseErrors(JsonNode errorsNode) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (errorsNode == null || !errorsNode.isArray()) return out;
        for (JsonNode e : errorsNode) {
            if (!e.isObject()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", text(e.path("type")));
            m.put("original", text(e.path("original")));
            m.put("suggestion", text(e.path("suggestion")));
            m.put("note", text(e.path("note")));
            out.add(m);
        }
        return out;
    }

    // ================= 批量生成句子 =================

    /** 生成结果条目 */
    public static final class Generated {
        public final int tier;
        public final String zhText;
        public final String refEn;

        Generated(int tier, String zhText, String refEn) {
            this.tier = tier;
            this.zhText = zhText;
            this.refEn = refEn;
        }
    }

    /** 生成失败（上游异常 / JSON 解析失败），由 Controller 优雅降级 */
    public static final class GenerateException extends Exception {
        public GenerateException(String message) { super(message); }
    }

    private static final String GEN_SYSTEM = """
            你是英语翻译练习题库的出题老师。根据给定的场景与要求，产出中文句子供学生翻译成英文。
            难度分三阶梯：1=简单（日常短句，主谓宾为主，10-20 字）；2=中等（含从句、时态或常见搭配，20-35 字）；3=困难（长句、抽象或地道表达，30-50 字）。
            每句中文 10-50 字，语义完整、贴近场景、可翻译成自然英文，避免专有名词堆砌与无法翻译的网络用语。
            严格只输出一个 JSON 数组，不要输出 JSON 以外的任何内容，不要用代码块包裹，元素结构固定为：
            [{"tier": <1|2|3>, "zh_text": "<中文原句>", "ref_en": "<参考英文译文>"}]""";

    /**
     * LLM 批量生成：counts 为 {1:n, 2:n, 3:n}（值为 0 的阶梯不出题）。
     * 返回按阶梯与出现顺序排好的条目列表；异常统一抛 GenerateException。
     */
    public static List<Generated> generate(String scenario, String prompt, Map<Integer, Integer> counts)
            throws GenerateException {
        String key = AppConfig.qwenKey();
        if (key.isEmpty() && AppConfig.qwenApiKeys().isEmpty()) throw new GenerateException("未配置 QWEN_API_KEY");
        StringBuilder spec = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            spec.append("- tier ").append(e.getKey()).append("：").append(e.getValue()).append(" 句\n");
        }
        if (spec.isEmpty()) throw new GenerateException("请至少设置一个阶梯的数量");
        String userPrompt = "场景：" + (scenario == null || scenario.isBlank() ? "日常生活" : scenario.trim()) + "\n"
                + (prompt == null || prompt.isBlank() ? "" : "额外要求：" + prompt.trim() + "\n")
                + "各阶梯需要生成的句子数量：\n" + spec + "\n"
                + "请按系统提示的 JSON 数组结构输出，数组元素顺序为 tier 从小到大，每个 tier 内按句子从易到难排列。";
        String raw;
        try {
            raw = QwenClient.complete(AppConfig.qwenModel(),
                    List.of(Map.of("role", "system", "content", GEN_SYSTEM),
                            Map.of("role", "user", "content", userPrompt)),
                    key, 120);
        } catch (Exception e) {
            throw new GenerateException("生成失败，请重试");
        }
        JsonNode arr = parseJsonArray(raw);
        if (arr == null) throw new GenerateException("生成失败，请重试");
        List<Generated> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            int tier = n.path("tier").asInt(0);
            if (tier < 1 || tier > 3) continue;
            String zh = text(n.path("zh_text"));
            if (zh.isEmpty()) zh = text(n.path("zh"));
            if (zh.isEmpty()) continue;
            if (zh.length() > 255) zh = zh.substring(0, 255);
            out.add(new Generated(tier, zh, truncate(text(n.path("ref_en")), 500)));
        }
        if (out.isEmpty()) throw new GenerateException("生成失败，请重试");
        return out;
    }

    // ================= 中文切句（批量导入） =================

    /** 中文句末标点（切分点，标点本身不保留在句尾以外） */
    private static final String SENT_END = "。！？；…!?;";

    /** 切分结果：sentences 为可入库句子，skipped 为被过滤条数（空/重复/过短） */
    public static final class SplitResult {
        public final List<String> sentences;
        public final int skipped;

        SplitResult(List<String> sentences, int skipped) {
            this.sentences = sentences;
            this.skipped = skipped;
        }
    }

    /** 单句最短字数（过短过滤，如「好。」这类无练习价值的碎片） */
    public static final int MIN_ZH_LEN = 4;
    /** 中文原句推荐上限（超过仍入库，前端给出提示） */
    public static final int MAX_ZH_LEN = 50;

    /**
     * 按中文句末标点与换行切分成一句一句（句末标点保留在句尾，保持原句完整）：
     * trim、去空、去重（组内已存在的一并由调用方过滤）、过滤过短。
     * 超长（>50 字）不过滤，仅由调用方在响应里提示。
     */
    public static SplitResult splitZhText(String text) {
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int skipped = 0;
        if (text == null) return new SplitResult(out, 0);
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (char ch : line.toCharArray()) {
                cur.append(ch);
                if (SENT_END.indexOf(ch) >= 0) {
                    String sentence = cur.toString().trim();
                    if (accept(sentence, seen)) out.add(sentence);
                    else if (!sentence.isEmpty()) skipped++;
                    cur.setLength(0);
                }
            }
            String tail = cur.toString().trim();
            if (tail.isEmpty()) continue;
            if (accept(tail, seen)) out.add(tail);
            else skipped++;
        }
        return new SplitResult(out, skipped);
    }

    /** 可入库判定：非空、长度达标、未重复（重复计入 skipped） */
    private static boolean accept(String raw, LinkedHashSet<String> seen) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return false;
        if (s.length() < MIN_ZH_LEN) return false;
        return seen.add(s);
    }

    // ================= 当日内容组装 =================

    /**
     * 当日练习内容：组信息 + 阶梯元信息 + 句子（附当前用户既有提交）。
     * 当天无激活组时 group 为 null、sentences 为空数组（C 端显示空态，不报错）。
     */
    public static Map<String, Object> todayPayload(long cUserId, java.time.LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> group = TranslateDao.getActivatedGroup(date);
        if (group == null) {
            body.put("group", null);
            body.put("date", date.toString());
            body.put("tiers", tiers());
            body.put("sentences", List.of());
            return body;
        }
        long gid = ((Number) group.get("id")).longValue();
        Map<String, Object> groupOut = new LinkedHashMap<>();
        groupOut.put("id", group.get("id"));
        groupOut.put("title", group.get("title"));
        groupOut.put("activated_date", group.get("activated_date"));
        body.put("group", groupOut);
        body.put("date", date.toString());
        body.put("tiers", tiers());

        List<Map<String, Object>> sentences = TranslateDao.listSentences(gid);
        List<Long> ids = new ArrayList<>(sentences.size());
        for (Map<String, Object> s : sentences) ids.add(((Number) s.get("id")).longValue());
        Map<Long, Map<String, Object>> subBySentence = new LinkedHashMap<>();
        for (Map<String, Object> sub : TranslateDao.listSubmissionsForSentences(cUserId, date, ids)) {
            Object sid = sub.get("sentence_id");
            if (sid instanceof Number n) subBySentence.put(n.longValue(), sub);
        }
        List<Map<String, Object>> out = new ArrayList<>(sentences.size());
        for (Map<String, Object> s : sentences) {
            long sid = ((Number) s.get("id")).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("id"));
            m.put("tier", s.get("tier"));
            m.put("zh_text", s.get("zh_text"));
            m.put("sort_order", s.get("sort_order"));
            m.put("submission", submissionView(subBySentence.get(sid)));
            out.add(m);
        }
        body.put("sentences", out);
        return body;
    }

    /** 提交记录 → 前端视图（errors_json 解析为数组，解析失败退化为空数组） */
    public static Map<String, Object> submissionView(Map<String, Object> sub) {
        if (sub == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("en_text", sub.get("en_text"));
        Object accurate = sub.get("accurate");
        m.put("accurate", accurate == null ? null : ((Number) accurate).intValue() == 1);
        m.put("score", sub.get("score"));
        m.put("corrected", sub.get("corrected"));
        m.put("explanation", sub.get("explanation"));
        Object errorsJson = sub.get("errors_json");
        m.put("errors", parseErrorsArray(errorsJson == null ? null : String.valueOf(errorsJson)));
        m.put("updated_at", sub.get("updated_at"));
        return m;
    }

    /** errors_json 字符串 → List；非法/空时为 List.of() */
    public static List<Map<String, Object>> parseErrorsArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = JsonUtil.parse(json);
            if (!arr.isArray()) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode e : arr) {
                if (!e.isObject()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", text(e.path("type")));
                m.put("original", text(e.path("original")));
                m.put("suggestion", text(e.path("suggestion")));
                m.put("note", text(e.path("note")));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ================= JSON 容错解析 =================

    /** 从模型原始输出里截取第一个 JSON 对象（容忍 ```json 包裹与前后赘述）；失败返回 null */
    public static JsonNode parseJsonObject(String raw) {
        String s = stripFence(raw);
        int i = s.indexOf('{');
        int j = s.lastIndexOf('}');
        if (i < 0 || j <= i) return null;
        try {
            JsonNode n = JsonUtil.parse(s.substring(i, j + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从模型原始输出里截取 JSON 数组；失败返回 null */
    public static JsonNode parseJsonArray(String raw) {
        String s = stripFence(raw);
        int i = s.indexOf('[');
        int j = s.lastIndexOf(']');
        if (i < 0 || j <= i) return null;
        try {
            JsonNode n = JsonUtil.parse(s.substring(i, j + 1));
            return n.isArray() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 ```json ... ``` 代码块围栏 */
    private static String stripFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
        }
        return s.trim();
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        return n.asText("").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
