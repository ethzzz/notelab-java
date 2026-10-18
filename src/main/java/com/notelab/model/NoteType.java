package com.notelab.model;

import java.util.List;

/**
 * 笔记表的**文档类型**（notes.doc_type）。
 *
 * <p>为什么要有它：笔记与 PRD 是同一套东西 —— 同表、同字段、同 Markdown 编辑器、同导出端点，
 * 唯一的差别是「在哪个页面里看」。用一列 {@code doc_type} 表达，比复制一张表 / 复制一套 Controller
 * 省得多：类型只是**归类维度**，不是第二套数据模型。
 *
 * <p>⚠️ 它是**归类**不是**安全边界**：能调 {@code /api/notes} 的账号换个 type 参数照样能读到另一种
 * （B 端接口权限是模块级、不按文档类型细分）。页面级权限（{@code page:/documents/prd}）才是
 * 「谁能进哪个页面」的边界。想按类型隔离数据得先有按类型的接口授权，目前没有这个需求。
 *
 * <p>取值是**白名单**：未知类型一律拒绝，否则一个拼错的 type 会静默创建出一批谁也看不见的孤儿文档。
 */
public final class NoteType {

    /** 普通笔记（默认；老数据全部归入此类型） */
    public static final String NOTE = "note";
    /** 产品需求文档 */
    public static final String PRD = "prd";

    /** 全部合法类型（顺序即接口文档里的列举顺序） */
    public static final List<String> ALL = List.of(NOTE, PRD);

    private NoteType() {}

    /** 归一化：null/空白 → {@link #NOTE}；非法值 → null（调用方据此回 400） */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return NOTE;
        String s = raw.trim().toLowerCase();
        return ALL.contains(s) ? s : null;
    }

    /** 中文名（导出标题、日志用） */
    public static String label(String type) {
        return PRD.equals(type) ? "PRD" : "笔记";
    }
}
