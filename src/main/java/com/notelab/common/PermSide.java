package com.notelab.common;

/**
 * 路由归属端（B 端后台 / C 端站点）的**唯一判定入口**。
 *
 * <p>为什么抽出来：判定规则（「{@code /api/c/**} 是 C 端」）会被四处用到 ——
 * 启动注册时写 {@code perm_routes.side}、B 端角色组下发时过滤、C 端用户组下发时过滤、
 * 以及两端的授予接口各自拒绝对方的码。散落成四处 {@code startsWith} 迟早会有一处漏改，
 * 于是「B 端角色勾到了 C 端接口」这类错配又悄悄回来。
 *
 * <p>⚠️ 与 {@link PermGuard#C_ENDPOINT_PREFIX} 的关系：前缀常量在 PermGuard（门禁那边也要用），
 * 这里只负责把它翻译成分端的 side 值，两者必须一致 —— 所以这里直接引用它而不是另抄一份字符串。
 */
public final class PermSide {

    /** B 端（admin 后台） */
    public static final String B = "b";
    /** C 端（游戏中心 / 个人主页，身份体系为 c_users） */
    public static final String C = "c";

    private PermSide() {}

    /**
     * 接口路径 → 端。
     *
     * <p>⚠️ 判据是 {@code /api/c/} 前缀**外加** {@code /api/c} 自身（类级 {@code @RequestMapping("/api/c")}
     * 若将来挂了方法级空路径，登记出来的就是 {@code /api/c}），但**绝不能**用 {@code startsWith("/api/c")} ——
     * 那会把 {@code /api/c-admin/**}（B 端管理员管理 C 端用户，名字像 C 端实为 B 端）整段误判成 C 端。
     */
    public static String ofApi(String apiPath) {
        if (apiPath == null || apiPath.isEmpty()) return B;
        String p = apiPath.trim();
        return p.equals("/api/c") || p.startsWith(PermGuard.C_ENDPOINT_PREFIX) ? C : B;
    }

    /** 非 B 即 C：空值/脏值一律按 B 处理（存量行默认值就是 'b'） */
    public static String normalize(String side) {
        return C.equals(side) ? C : B;
    }
}
