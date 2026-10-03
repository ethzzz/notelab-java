package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目实践 · 项目开发总结：GET|POST /api/dev-notes。
 *
 * <p>存放「做项目时踩到的技术难点」的结构化记录：所属项目 → 条目（现象 / 原因 / 解决方案 / 代码）。
 * 整包存 ui_config JSON 的 "dev_notes" 键，不新建表；结构与写法对齐 {@link LootContentController}：
 * <ul>
 *   <li><b>手工白名单</b>：save() 里逐字段 put，没登记的字段被静默丢弃（爬塔踩过的坑）；</li>
 *   <li><b>懒 seed</b>：库中无 dev_notes 或项目列表为空 → 回内置记录（BASE_PROJECTS），
 *       保证页面首次打开就有内容，用户可以在此基础上增删改；</li>
 *   <li><b>整包覆盖写</b>：POST 提交全量项目数组。</li>
 * </ul>
 *
 * <p>没有发布快照：这不是 C 端消费的内容，只是后台的知识库，编辑即生效。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/dev-notes")
public class DevNoteController {

    /** 条目性质：阻断 / 缺陷 / 坑 / 备忘（展示用，不参与逻辑） */
    private static final Set<String> SEVERITIES = Set.of("blocker", "bug", "pitfall", "note");

    private static final int MAX_DEVNOTES_CHARS = 1_000_000;
    private static final int MAX_PROJECTS = 20;
    private static final int MAX_ENTRIES_PER_PROJECT = 200;
    private static final int MAX_TAGS = 20;

    // ==================================================================================
    // 内置记录（懒 seed 初值）—— 摸金行动 W1 开发过程中真实踩到的技术难点。
    // 与 docs/PRACTICE/loot-开发实践.md 同源；新增条目优先写这里（或直接在后台页面里加）。
    // ==================================================================================

    private static Map<String, Object> devEntry(String id, String title, String stage, String severity,
            String symptom, String cause, String solution, String code, List<String> tags, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("title", title); m.put("stage", stage); m.put("severity", severity);
        m.put("symptom", symptom); m.put("cause", cause); m.put("solution", solution);
        m.put("code", code); m.put("tags", tags); m.put("date", date);
        return m;
    }

    private static Map<String, Object> devProject(String code, String name, String desc,
            List<Map<String, Object>> entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("name", name); m.put("desc", desc); m.put("entries", entries);
        return m;
    }

    private static final List<Map<String, Object>> BASE_PROJECTS = List.of(
            devProject("loot", "摸金行动（Loot Raid）",
                    "搜刮撤离类 H5 游戏：进图门槛 → 容器搜刮 → 风险累积 → 撤离结算。B 端配内容、C 端只读消费，LLM 零依赖。",
                    List.of(
                            devEntry("loot-w1-01",
                                    "antd Select 的 onChange 参数类型含 undefined，直接传给要 string 的函数报 TS2322",
                                    "W1 · B 端配置页", "blocker",
                                    "B 端 5 个配置页 build 全挂：Type '(value: string | undefined) => void' is not assignable to … 报 TS2322。\n"
                                            + "本地 dev 不一定复现（热更宽松），是服务器 npm run build 才炸出来的。",
                                    "antd v5 的 Select.onChange 单选取值签名是 (value: ValueType, option) => void，"
                                            + "ValueType 在单选模式下是 string | undefined（allowClear / 值为空时会给 undefined）。"
                                            + "直接把 v 塞进 { ...draft, rarity: v } 就要求 v 是 string → 类型不兼容。",
                                    "所有 Select 的 onChange 加 typeof 守卫（或 ?? \"\" 兜底），别用非空断言骗编译器——"
                                            + "运行时真会拿到 undefined。输入型控件（InputNumber）同理用 v ?? 默认值。",
                                    "// ❌ 报 TS2322\n"
                                            + "<Select value={draft.rarity}\n"
                                            + "  onChange={(r) => setDraft({ ...draft, rarity: r })} />\n\n"
                                            + "// ✅ 加 typeof 守卫\n"
                                            + "<Select value={draft.rarity}\n"
                                            + "  onChange={(r) => { if (typeof r === \"string\") setDraft({ ...draft, rarity: r }) }} />",
                                    List.of("typescript", "antd", "构建", "B端"), "2026-10-03"),
                            devEntry("loot-w1-02",
                                    "内容管线手工白名单：没登记的键会被静默丢弃（保存成功但刷新就没了）",
                                    "W1 · Java 内容管线", "pitfall",
                                    "B 端保存返回 200，刷新后新加的字段/切片消失，且**不报任何错**。"
                                            + "排查时容易怀疑是前端没提交或数据库没写。",
                                    "save() 里是手工逐键 out.put(\"items\", …)、out.put(\"containers\", …) 的白名单写法，"
                                            + "JSON 里没被显式 put 的键直接不进落库对象。白名单是为了防脏键，代价是「新增切片必须记得同步改」。",
                                    "新增一个切片要同时改**两处**：save() 的 put 白名单，和 xxxOf() 的读取分支。"
                                            + "漏改任何一处都表现为「存了读不回来」。验收时用「POST 塞一个 evil:1，读回看 keys 里有没有 evil」来证明白名单生效。",
                                    "// save() 白名单（漏写就丢键）\n"
                                            + "Map<String, Object> loot = new LinkedHashMap<>();\n"
                                            + "loot.put(\"items\", sanitizeItems(node.get(\"items\")));\n"
                                            + "loot.put(\"containers\", sanitizeContainers(node.get(\"containers\")));\n\n"
                                            + "// xxxOf() 读取分支（也要同步）\n"
                                            + "Object it = m.get(\"items\");\n"
                                            + "if (it instanceof List && !((List<?>) it).isEmpty()) out.put(\"items\", it);",
                                    List.of("java", "内容管线", "白名单", "静默失败"), "2026-10-03"),
                            devEntry("loot-w1-03",
                                    "用「懒 seed」让后台首次打开就有内容可编辑",
                                    "W1 · Java 内容管线", "note",
                                    "B 端配置页第一次打开是空表，维护者不知道要填什么、格式是什么，只能去翻代码找示例。",
                                    "内容全在后台配置（不像代码里有常量），库里没数据时没有任何参照物。",
                                    "xxxOf() 里某切片为空就回内置默认常量（BASE_*），**不主动写库**——只有用户点「保存」才落库。"
                                            + "这样默认值改了不需要迁移脚本，用户没动过的切片永远跟随代码里的最新默认。"
                                            + "对应地，C 端也要有一份**逐字段一致**的内置默认包，接口挂掉时回落。",
                                    "// 库中某切片为空 → 回内置默认（懒 seed）\n"
                                            + "out.put(\"items\", new ArrayList<>(BASE_ITEMS));\n"
                                            + "Object it = cfg.get(\"loot\").get(\"items\");\n"
                                            + "if (it instanceof List && !((List<?>) it).isEmpty()) out.put(\"items\", it);",
                                    List.of("java", "懒seed", "默认值"), "2026-10-03"),
                            devEntry("loot-w1-04",
                                    "C 端读「发布快照」而不是编辑中的内容，否则半成品会直接上线",
                                    "W1 · 发布链路", "pitfall",
                                    "如果 C 端直接读后台正在编辑的 loot 键，B 端改到一半点保存，线上就变了——"
                                            + "玩家可能抽到没配完的掉落表（池子空档 → 槽位空手）。",
                                    "编辑态与线上态共用一份数据，没有隔离。",
                                    "publish 时把 lootOf() 的结果整体快照进 ui_config.loot_published，C 端**只读快照**；"
                                            + "unpublish 就删掉这个键让 C 端回落内置默认。B 端顶部用「已发布 / 未发布 / 有未保存改动」三个标记"
                                            + "把这三种状态显式区分开（发布前先 commit，避免发布旧版）。",
                                    "// publish：编辑内容 → 快照（C 端只读这个键）\n"
                                            + "cfg.put(\"loot_published\", lootOf(cfg));\n\n"
                                            + "// 匿名 C 端接口读快照\n"
                                            + "@GetMapping(\"/api/c/loot/content\")\n"
                                            + "public ResponseEntity<Map<String,Object>> c() { … get(\"loot_published\") … }",
                                    List.of("发布", "快照", "隔离", "内容管线"), "2026-10-03"),
                            devEntry("loot-w1-05",
                                    "掉落表池子必须每档至少 1 件候选，否则该档轮盘抽空手",
                                    "W1 · 数值设计", "pitfall",
                                    "保险柜连着开出空槽，表现为「高级容器反而什么都摸不到」。日志里看不到报错，因为空手是合法返回值。",
                                    "抽取流程是「按容器权重抽稀有度档 → 在该档候选里按权重抽物品」。"
                                            + "如果掉落表池子里某个档位一件候选都没有，抽到该档时 rollItem 只能返回 null → 该槽位空手。"
                                            + "档位越高越容易空（池子里传说档往往只有 1-2 件，删一件就空了）。",
                                    "B 端保存时对每张表做「五档候选覆盖检查」并**给警告而不是拒绝保存**（配到一半是正常中间态）；"
                                            + "C 端引擎把 null 当合法结果处理（槽位显示空手），不能抛异常。"
                                            + "验收时用「每档至少 1 件」的断言体检整份内容。",
                                    "// 引擎侧：抽不到就空手，不要抛\n"
                                            + "const item = rollItem(seed, table.pool, rarity)\n"
                                            + "if (!item) { /* 该槽空手，UI 显示「空」 */ }\n\n"
                                            + "// B 端体检：五档是否都有候选\n"
                                            + "const missing = RARITIES.filter(r =>\n"
                                            + "  !table.pool.some(p => byId.get(p.itemId)?.rarity === r))",
                                    List.of("数值", "掉落表", "边界", "轮盘"), "2026-10-03"),
                            devEntry("loot-w1-06",
                                    "未使用的 import 会被 TS 拦下，改名/重构后要顺手摘掉",
                                    "W1 · B 端重构", "bug",
                                    "build 报 TS6133 / noUnusedLocals：'sanitizeWeights' is declared but its value is never read。"
                                            + "代码逻辑完全正常，纯粹是残留导入把构建搞挂。",
                                    "重构时把某段逻辑内联或移走后，import 行还留在文件顶部。",
                                    "构建报错后先看是不是 unused import（最快的一类修复）；"
                                            + "用 grep -rn 该标识符确认真的没别处引用，再删导入行，而不是随手注释掉。",
                                    "// 删之前先确认没有别处引用\ngrep -rn \"displayValue\" src/ --include=*.tsx",
                                    List.of("typescript", "构建", "重构"), "2026-10-03"),
                            devEntry("loot-w1-07",
                                    "本机没有 JDK，Java 侧改动只能靠服务器 mvn 编译验证",
                                    "W1 · 环境约束", "note",
                                    "想在本地先编译一遍 Java 再提交，结果本机没有 java/mvn，无法预检。",
                                    "开发机只装了 Node 与 MySQL，没有 JDK17/Maven（~/.m2/repository 只有残渣）。",
                                    "Java 改动提交后立刻在服务器跑 mvn package 验证，**别带着未编译的改动继续往下写 B/C 端**——"
                                            + "Java 是内容管线的源头，它不通过后面全白做。判据用 $? 而不是 tail 日志（日志里 success 也可能是旧构建）。",
                                    "# 服务器编译预检（本机无 JDK）\n"
                                            + "cd /root/Notelab/notelab-java && mvn -B -DskipTests package > log 2>&1; echo $?",
                                    List.of("环境", "java", "预检"), "2026-10-03"),
                            devEntry("loot-w1-08",
                                    "C 端 notelab-c 没有 basePath，游戏路由就是 /play/loot，别按旧注释加 /games 前缀",
                                    "W1 · C 端路由", "pitfall",
                                    "照旧文档/旧注释给素材与接口路径加 /games 前缀，结果 404（或指向错误的路由）。",
                                    "notelab-c 早期曾用 basePath=\"/games\"，后来去掉了；但仓内仍有历史注释与文档写着带前缀的路径。"
                                            + "同时 nginx 里 /games 与 / 都代理到同一个 3010 端口，所以两条路都能通，问题更隐蔽。",
                                    "新增 C 端路由前先 grep next.config.ts 的 basePath 与服务器 nginx 的 location；"
                                            + "素材 URL 统一走一个 helper（如 spireAssetUrl），不要到处手写前缀拼字符串。",
                                    "# 确认 basePath 与 nginx 路由归属\ngrep -n \"basePath\" next.config.ts\nssh myapp \"nginx -T | grep -nE '^\\s*location'\"",
                                    List.of("nextjs", "路由", "nginx", "环境"), "2026-10-03")
                    ))
    );

    // ==================================================================================
    // 接口
    // ==================================================================================

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(devNotesOf(UiConfigService.getConfig()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        if (!node.isObject()) return ResponseEntity.status(400).body(Map.of("error", "body 必须是对象"));

        // ⚠️ 手工白名单：这里没 put 的键会被静默丢弃（存了刷新就没了且不报错）
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("projects", sanitizeProjects(node.get("projects")));

        String json = JsonUtil.write(doc);
        if (json.length() > MAX_DEVNOTES_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "内容过大（>" + (MAX_DEVNOTES_CHARS / 1000) + "KB），精简几条记录再保存"));
        }
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.put("dev_notes", doc);
        try {
            UiConfigService.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** dev_notes 出口：库中没有或项目列表为空 → 回内置记录（懒 seed） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> devNotesOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", new ArrayList<>(BASE_PROJECTS));
        Object o = cfg.get("dev_notes");
        if (o instanceof Map) {
            Object ps = ((Map<String, Object>) o).get("projects");
            if (ps instanceof List && !((List<?>) ps).isEmpty()) out.put("projects", ps);
        }
        return out;
    }

    // ==================================================================================
    // 净化（只做结构与体积校验；不合法丢该条，不让整包 400）
    // ==================================================================================

    private static List<Map<String, Object>> sanitizeProjects(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode p : node) {
            if (out.size() >= MAX_PROJECTS) break;
            if (p == null || !p.isObject()) continue;
            String code = str(p, "code", 64);
            String name = str(p, "name", 100);
            if (code.isEmpty() || name.isEmpty() || seen.contains(code)) continue;
            seen.add(code);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            m.put("desc", str(p, "desc", 500));
            m.put("entries", sanitizeEntries(p.get("entries")));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> sanitizeEntries(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode e : node) {
            if (out.size() >= MAX_ENTRIES_PER_PROJECT) break;
            if (e == null || !e.isObject()) continue;
            String id = str(e, "id", 64);
            String title = str(e, "title", 200);
            if (id.isEmpty() || title.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            String severity = str(e, "severity", 16);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("stage", str(e, "stage", 100));
            m.put("severity", SEVERITIES.contains(severity) ? severity : "pitfall");
            m.put("symptom", str(e, "symptom", 4000));
            m.put("cause", str(e, "cause", 4000));
            m.put("solution", str(e, "solution", 8000));
            m.put("code", str(e, "code", 20000));
            m.put("tags", strList(e.get("tags"), MAX_TAGS, 32));
            m.put("date", str(e, "date", 20));
            out.add(m);
        }
        return out;
    }

    private static List<String> strList(JsonNode node, int max, int itemMax) {
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

    private static String str(JsonNode n, String field, int max) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || !v.isTextual()) return "";
        String s = v.asText().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
