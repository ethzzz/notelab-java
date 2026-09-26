package com.notelab.model;

import java.util.List;

/**
 * 前端页面路由表常量（数据结构层，菜单驱动）：path, 名称。
 * image/audio 为"敬请期待"占位页，同样纳入权限体系。
 */
public final class PageRoutes {

    private PageRoutes() {}

    public static final List<String[]> PAGE_ROUTES = List.of(
            new String[]{"/", "仪表盘"},
            new String[]{"/chat", "智能对话"},
            new String[]{"/arena", "模型竞技场"},
            new String[]{"/toolbox", "文本工具箱"},
            new String[]{"/rag", "文档问答 RAG"},
            new String[]{"/english", "英语学习"},
            new String[]{"/translate", "翻译句子库"},
            new String[]{"/image", "文生图"},
            new String[]{"/audio", "语音转文字"},
            new String[]{"/extract", "结构化抽取"},
            new String[]{"/tools", "AI工具库"},
            new String[]{"/lowcode", "低代码平台"},
            // B/C 拆分 P6：/trpg/play、/vs、/spire 游玩页已移至 C 端，从 B 端页面路由表移除
            new String[]{"/trpg/gen", "生成剧本"},
            new String[]{"/docs", "文档编辑"},
            new String[]{"/notes", "笔记"},
            // 爬塔工坊由单页 4 个 Tab 拆成 6 个子页（原 /spire-editor 现为 307 重定向，见 notelab-b/next.config.ts）。
            // ⚠️ 这里与 MenuTree.gc_spire 必须一一对应：只加菜单不加路由 → 叶子被 RBAC 过滤，谁都看不到；
            //    只加路由不加菜单 → 没有入口。历史 role 里的 page:/spire-editor 行会自然失效（不在本表 → 不再开通任何路径），
            //    非超管角色需要在「角色组管理」里重新勾选下面这 6 条。
            new String[]{"/spire-editor/cards", "爬塔·卡片制作"},
            new String[]{"/spire-editor/chars", "爬塔·角色制作"},
            new String[]{"/spire-editor/skills", "爬塔·技能制作"},
            new String[]{"/spire-editor/assets", "爬塔·素材资源"},
            new String[]{"/spire-editor/map", "爬塔·地图生成"},
            new String[]{"/spire-editor/access", "爬塔·角色授权"},
            new String[]{"/user/accounts", "账户管理"},
            new String[]{"/user/roles", "角色组管理"},
            new String[]{"/user/invites", "邀请码"},
            new String[]{"/c-users", "C端用户管理"},
            new String[]{"/ui", "界面配置"},
            new String[]{"/perm", "权限管理"}
    );
}
