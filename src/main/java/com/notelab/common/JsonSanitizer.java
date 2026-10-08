package com.notelab.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON 载荷净化助手 —— B 端内容配置的**唯一**取值口径。
 *
 * <p>存在的理由（2026-10-08 审查 §1.2 + §2.4）：这些助手原先以「每个 controller 各抄一份
 * {@code private static}」的形式散落在 Spire / Loot / Blog / CTrack / DevNote 五个控制器里，
 * **同名、同签名，却有 4 种不同语义**（是否 trim、判据是 {@code isTextual} 还是 {@code isNull}、
 * 是否截断），而且差异是**静默**的 —— 同一份 JSON 载荷走不同净化路径得到不同结果，
 * 编译通过、接口返回 200，只是数据悄悄不一致。收敛到这一份实现后，「净化口径」只有一处定义。
 *
 * <p>统一口径：{@link #str} 一律**要求 isTextual**（数字 / 对象 / 数组 / null → {@code ""}）
 * 并 **trim**；缺字段或类型不符绝不返回 {@code null}（避免 null 污染 JSON）。
 * 与个别原实现的差异已按 bug 修，逐条记在对应方法上。
 *
 * <p>调用方一律用类名限定（{@code JsonSanitizer.str(...)}）而非 {@code import static} ——
 * 本仓此前没有任何静态导入，且限定名让「这个值从哪来的口径」在调用点就可见。
 */
public final class JsonSanitizer {

    private JsonSanitizer() {}

    /**
     * 取文本字段：要求 {@code isTextual}，缺字段 / 类型不符一律 {@code ""}，结果 trim。绝不返回 null。
     *
     * <p>⚠️ 口径统一说明（合并了此前 4 种实现，差异按 bug 修）：
     * <ul>
     *   <li><b>Spire</b> 原版调 {@code asText()} <b>不 trim</b> —— 带首尾空格的敌人 id / name
     *       会被原样存库，且 moves 的 {@code kind} 匹配不上 {@code MOVE_KINDS} 白名单被**静默丢弃**
     *       （"atk " ≠ "atk"）。trim 后按用户意图落库。</li>
     *   <li><b>CTrack</b> 原版判据是 {@code isNull}（用 {@code asText("")} 取值）——
     *       数字 {@code 123} 会被转成字符串 {@code "123"}，在其余实现里则是 {@code ""}。
     *       统一为 {@code isTextual}：埋点字段本就该是字符串，数字是脏数据，
     *       落空串比静默接受更诚实（该文件 props 校验本就把数字当非法值跳过）。</li>
     * </ul>
     */
    public static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText().trim();
    }

    /** {@link #str} + 长度上限（超出直接截断）。DevNote 的 date / title 等短字段用。 */
    public static String str(JsonNode n, String field, int max) {
        return truncate(str(n, field), max);
    }

    /**
     * 文本数组：逐项 trim、丢弃空串与非文本项，最多 {@code max} 项、每项最多 {@code itemMax} 字符。
     * 非数组 / null → 空列表（不返回 null）。
     */
    public static List<String> strList(JsonNode node, int max, int itemMax) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode t : node) {
            if (out.size() >= max) break;
            if (t == null || !t.isTextual()) continue;
            String s = t.asText().trim();
            if (s.isEmpty()) continue;
            out.add(s.length() > itemMax ? s.substring(0, itemMax) : s);
        }
        return out;
    }

    /**
     * 截断到 {@code max} 字符；null → {@code ""}。**不 trim** —— 需要 trim 的先走 {@link #str}
     * 或自己 trim（保留这个区别是因为 TranslateService / GradeEngine 的截断原本就不 trim）。
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 取整数并夹到 [{@code lo}, {@code hi}]；非法（非数字）/ 缺失 → {@code dft}。 */
    public static int clampInt(JsonNode n, int lo, int hi, int dft) {
        if (n == null || !n.isNumber()) return dft;
        return Math.max(lo, Math.min(hi, n.asInt()));
    }

    /** 取小数并夹到 [{@code lo}, {@code hi}]；非法（非数字）/ 缺失 → {@code dft}。 */
    public static double clampDbl(JsonNode n, double lo, double hi, double dft) {
        if (n == null || !n.isNumber()) return dft;
        return Math.max(lo, Math.min(hi, n.asDouble()));
    }

    /**
     * 发布快照的规模统计（埋点 props）：{@code slices} = 非空的顶层切片数
     * （Collection / Map 且非空），{@code chars} = 序列化字符数。
     */
    public static Map<String, Object> snapshotStat(Map<String, Object> snap) {
        int slices = 0;
        for (Object v : snap.values()) {
            if (v instanceof Collection<?> c && !c.isEmpty()) slices++;
            else if (v instanceof Map<?, ?> m && !m.isEmpty()) slices++;
        }
        return Map.of("slices", slices, "chars", JsonUtil.write(snap).length());
    }
}
