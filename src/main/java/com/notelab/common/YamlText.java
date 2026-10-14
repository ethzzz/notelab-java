package com.notelab.common;

/**
 * YAML 标量输出（写 frontmatter 用）。
 *
 * <p>统一用**单引号**：YAML 里单引号串只有一种转义（{@code '} 写两遍），不像双引号还有
 * 反斜杠、换行、u-escape 等一串需要逐一处理的规则 ——
 * 值里出现 {@code @types}、{@code C:\path}、{@code "引号"} 时都不必再想怎么逃。
 *
 * <p>⚠️ 换行必须折成空格：frontmatter 是「一行一个字段」，值里带真换行会让 YAML 解析整体失败，
 * 而失败发生在博客 CI 的构建期 —— 现象是「push 成功了但站点一直没更新」，排查起来很绕。
 */
public final class YamlText {

    private YamlText() {}

    /** 包成单引号 YAML 标量（内部的 {@code '} 翻倍） */
    public static String quote(String s) {
        String t = s == null ? "" : s.replace("\r", "").replace("\n", " ").trim();
        return "'" + t.replace("'", "''") + "'";
    }
}
