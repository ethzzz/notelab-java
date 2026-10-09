package com.notelab.model;

import java.util.List;

/**
 * C 端页面路由表常量：path, 名称。与 B 端 {@link PageRoutes} **并列而独立**。
 *
 * <p>为什么单独一份：C 端（notelab-c，游戏中心 / 个人主页 / 效率工具）与 B 端后台是两套页面体系，
 * 访问者也不是同一批人（C 端是 {@code c_users}）。塞进 {@link PageRoutes} 会让
 * 「菜单树 ↔ 页面路由」的一致性校验（MenuTree.leafPaths）因为有对不上的 C 端路径而一直告警，
 * 也会让 B 端角色组的分配树里出现自己管不到的页面。
 *
 * <p>登记口径：只登记**值得被权限管住**的页面。以下刻意不登记：
 * <ul>
 *   <li>{@code /login}、{@code /register} —— 未登录也要能进，纳入权限等于把注册口堵死。</li>
 *   <li>{@code /play/trpg/play} 这类二级子页 —— 归在父页 {@code /play/trpg} 名下，
 *       守卫按前缀判即可，逐条登记只会让清单膨胀。</li>
 * </ul>
 *
 * <p>⚠️ 与 B 端不同，C 端**没有 MenuTree 式的菜单常量**：C 端导航是前端自己写的（主页 tab / 游戏中心卡片），
 * 所以这里不存在「菜单 ↔ 路由」双登记问题，改这个文件时只需确认路径在 notelab-c 的 {@code app/} 下真实存在。
 */
public final class CRoutes {

    private CRoutes() {}

    public static final List<String[]> C_PAGE_ROUTES = List.of(
            new String[]{"/", "C端首页"},
            new String[]{"/posts", "笔记"},
            new String[]{"/tools", "效率工具"},
            new String[]{"/games", "游戏中心"},
            // /play/* 各游戏：全屏游玩页，逐条登记以便按用户组开放（如内测阶段只给某组开摸金）
            new String[]{"/play/dungeon", "游戏·地牢"},
            new String[]{"/play/loot", "游戏·摸金"},
            new String[]{"/play/spire", "游戏·爬塔"},
            new String[]{"/play/thunder", "游戏·雷霆"},
            new String[]{"/play/trpg", "游戏·剧本"},
            new String[]{"/play/vs", "游戏·幸存者"},
            // /utils/* 效率工具（2026-10-03 从 /games/utils 迁出，旧路径由 next.config 308 兜住）
            new String[]{"/utils/translate", "工具·每日翻译"},
            new String[]{"/utils/flashcards", "工具·速查卡"},
            new String[]{"/utils/excerpts", "工具·知识摘录"},
            new String[]{"/utils/snippets", "工具·模板片段"},
            new String[]{"/utils/habits", "工具·习惯打卡"}
    );
}
