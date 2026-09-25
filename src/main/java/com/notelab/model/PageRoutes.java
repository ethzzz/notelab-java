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
            new String[]{"/spire-editor", "爬塔尖塔工坊"},
            new String[]{"/user/accounts", "账户管理"},
            new String[]{"/user/roles", "角色组管理"},
            new String[]{"/user/invites", "邀请码"},
            new String[]{"/c-users", "C端用户管理"},
            new String[]{"/ui", "界面配置"},
            new String[]{"/perm", "权限管理"}
    );
}
