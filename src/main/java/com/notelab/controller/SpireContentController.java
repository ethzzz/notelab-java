package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.UiConfigDao;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 爬塔尖塔内容工坊：GET|POST /api/spire-content。
 * 自定义卡/角色/技能模板/敌人/地图规则存 ui_config JSON 的 "spire" 键
 * （{cards:[], characters:[], skills:[], charAccess:{}, assets:{}, assetPool:{}, maps:{}, enemies:[], balance:{}, mapRules:{}}），不新建表；
 * 前端引擎在加载时做净化与注册，这里只做结构与体积校验。
 *
 * 角色授权（spire 第 4 键 charAccess）：{组码: [可选角色 id...]}，C 端角色选择页按登录用户所属
 * c_user_groups.code 做前置筛选；缺失或该组无键时 fail-open（不筛选）。GET 额外回带 baseCharacters
 * 常量（镜像 C 端引擎 BASE_CHARACTERS），供 B 端授权界面展示内置角色。
 */
@RestController
@RequestMapping("/api/spire-content")
public class SpireContentController {

    /**
     * 内置基础角色清单（只读镜像）：内容 = notelab-c/lib/spire-engine.ts 的 BASE_CHARACTERS
     * （id / name / icon）。B 端「角色授权」界面要展示"全量可选角色池 = 基础角色 + 工坊自定义角色"，
     * 但 B 端仓库没有引擎代码，故由后端提供这份常量。
     * ⚠️ 新增/改名内置角色时必须同步此处，否则 B 端授权界面看不到该角色（无法勾进白名单）。
     */
    /** 构造一个基础角色（字段与 notelab-c/lib/spire-engine.ts 的 CharacterDef 完全对齐） */
    private static Map<String, Object> baseChar(String id, String name, String icon, int maxHp, String desc,
            List<String> startDeck, List<Map<String, Object>> passives, Map<String, Object> skill) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("icon", icon); m.put("maxHp", maxHp);
        m.put("desc", desc); m.put("startDeck", startDeck); m.put("passives", passives); m.put("skill", skill);
        return m;
    }
    private static Map<String, Object> basePassive(String kind, String name, String icon, String desc, int value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind); m.put("name", name); m.put("icon", icon); m.put("desc", desc); m.put("value", value);
        return m;
    }
    private static Map<String, Object> baseSkill(String kind, String name, String icon, String desc,
            int cooldown, int value, String cardId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind); m.put("name", name); m.put("icon", icon); m.put("desc", desc);
        m.put("cooldown", cooldown); m.put("value", value);
        if (cardId != null) m.put("cardId", cardId);
        return m;
    }

    /**
     * 内置基础角色（**完整定义**，只读镜像）：内容 = notelab-c/lib/spire-engine.ts 的 BASE_CHARACTERS
     * （id / name / icon / maxHp / desc / startDeck / passives / skill）。
     * 用途：①「角色授权」界面展示可选角色池；② spireOf 懒 seed——库中无角色数据时填充，
     * 让后台「角色制作」页总能看见并编辑这 4 个内置角色（C 端同 id 覆盖代码兜底）。
     * ⚠️ 新增/改名/调数值的内置角色必须同步 notelab-c/lib/spire-engine.ts 的 BASE_CHARACTERS，否则两端不一致。
     */
    private static final List<Map<String, Object>> BASE_CHARACTERS = List.of(
            baseChar("blade", "刃影", "🥷", 80, "磨砺锋刃的刺客，攻伐凌厉",
                    List.of("strike", "strike", "strike", "strike", "strike", "defend", "defend", "defend", "defend", "bash"),
                    List.of(basePassive("atk-bonus", "淬锋", "🗡️", "攻击卡伤害 +1（全局生效）", 1)),
                    baseSkill("generate-card", "影分身", "🌑", "凭空生成一张 0 费【影袭】加入手牌", 3, 1, "shadowstrike")),
            baseChar("guard", "铁壁守卫", "🛡️", 90, "岿然不动的前卫，铜墙铁壁",
                    List.of("strike", "strike", "strike", "strike", "defend", "defend", "defend", "defend", "defend", "bash", "shrug"),
                    List.of(basePassive("block-on-turn-start", "甲铸", "⚒️", "每回合开始时获得 2 点格挡", 2)),
                    baseSkill("gain-block", "钢铁壁垒", "🏰", "立即获得 10 点格挡", 3, 10, null)),
            baseChar("mage", "秘法编织者", "🔮", 72, "编织奥术的智者，牌流不竭",
                    List.of("strike", "strike", "strike", "strike", "defend", "defend", "defend", "defend", "bash", "flex"),
                    List.of(basePassive("energy-on-play-count", "奥术涌动", "✨", "每打出 3 张牌获得 1 点能量", 3)),
                    baseSkill("draw-cards", "灵感迸发", "📖", "立即抽 2 张牌", 3, 2, null)),
            baseChar("wuzhuge", "武诸葛", "🪶", 76, "鞠躬尽瘁的谋主，运筹帷幄，牌随势动",
                    List.of("strike", "strike", "strike", "strike", "strike", "defend", "defend", "defend", "bash", "anger"),
                    List.of(
                            basePassive("start-hand-7", "尽瘁", "🕯️", "开局摸至 7 张手牌；每回合准备阶段回复生命（= 卡组中攻击卡数量，至少 1），并观看牌堆顶 7 张牌任意安排到顶/底", 7),
                            basePassive("echo-on-play", "情势", "🀄", "打出卡片时，若手牌中还有同类型（攻击/防御/增益/特殊）的牌，可三选一：效果×同类数量 / 抽同类数量张牌 / 回复同类数量点生命", 0)),
                    baseSkill("echo-copy", "锦囊复刻", "📜", "每轮对战限一次：选择手牌中一张牌生成其原始复制；复制牌打出后会在回合结束时回到手中，未打出则留在手牌", 0, 1, null))
    );

    /**
     * 内置基础敌人（**完整定义**，只读镜像）：内容 = notelab-c/lib/spire-engine.ts 的 ENEMIES
     * （id / name / icon / hp / elite / boss / moves）。用途同 BASE_CHARACTERS：
     * ①「敌人制作」界面展示可选敌人池；② spireOf 懒 seed——库中无敌人数据时填充，
     * 让后台「敌人制作」页总能看见并编辑这 10 个内置敌人（C 端同 id 覆盖代码兜底）。
     * ⚠️ 新增/改名/调数值的内置敌人必须同步 notelab-c/lib/spire-engine.ts 的 ENEMIES，否则两端不一致。
     */
    private static Map<String, Object> baseMove(String name, String kind, int amt, int hits, String icon, String debuffKind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("kind", kind); m.put("amt", amt); m.put("hits", hits); m.put("icon", icon);
        if (debuffKind != null) m.put("debuffKind", debuffKind);
        return m;
    }
    private static Map<String, Object> baseEnemy(String id, String name, String icon, int hp,
            Boolean elite, Boolean boss, List<Map<String, Object>> moves) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("icon", icon); m.put("hp", hp); m.put("moves", moves);
        if (Boolean.TRUE.equals(elite)) m.put("elite", true);
        if (Boolean.TRUE.equals(boss)) m.put("boss", true);
        return m;
    }

    private static final List<Map<String, Object>> BASE_ENEMIES = List.of(
            baseEnemy("cultist", "邪教徒", "👤", 30, false, false, List.of(
                    baseMove("嚎叫", "buff", 2, 1, "📣", null),
                    baseMove("黑暗打击", "atk", 6, 1, "🗡️", null),
                    baseMove("黑暗打击", "atk", 6, 1, "🗡️", null))),
            baseEnemy("worm", "颚虫", "🐛", 34, false, false, List.of(
                    baseMove("撕咬", "atk", 11, 1, "👄", null),
                    baseMove("鞭打", "atk", 7, 1, "💥", null),
                    baseMove("盘蜷", "block", 6, 1, "🛡️", null))),
            baseEnemy("louse", "虱子", "🪲", 24, false, false, List.of(
                    baseMove("叮咬", "atk", 6, 1, "🦷", null),
                    baseMove("吐丝", "debuff", 2, 1, "🕸️", "weak"),
                    baseMove("叮咬", "atk", 6, 1, "🦷", null))),
            baseEnemy("slime", "酸液史莱姆", "🦠", 36, false, false, List.of(
                    baseMove("撞击", "atk", 9, 1, "💢", null),
                    baseMove("重压", "atk", 12, 1, "🫠", null),
                    baseMove("分泌", "buff", 2, 1, "🫧", null))),
            baseEnemy("fungi", "真菌兽", "🍄", 28, false, false, List.of(
                    baseMove("生长", "buff", 3, 1, "🌱", null),
                    baseMove("啃咬", "atk", 7, 1, "🦷", null),
                    baseMove("孢子喷吐", "debuff", 1, 1, "☁️", "vuln"))),
            baseEnemy("nob", "哥布林头目", "👹", 62, true, false, List.of(
                    baseMove("怒吼", "buff", 2, 1, "📣", null),
                    baseMove("冲撞", "atk", 13, 1, "🐂", null),
                    baseMove("碎颅击", "atk", 9, 1, "💀", null))),
            baseEnemy("sentry", "石像哨卫", "🗿", 58, true, false, List.of(
                    baseMove("石化凝视", "debuff", 1, 1, "👁️", "vuln"),
                    baseMove("重拳", "atk", 12, 1, "🪨", null),
                    baseMove("岩甲", "block", 9, 1, "🛡️", null))),
            baseEnemy("king", "史莱姆之王", "👑", 130, false, true, List.of(
                    baseMove("王者重击", "atk", 16, 1, "👑", null),
                    baseMove("泰山压顶", "atk", 11, 1, "🌊", null),
                    baseMove("沸腾", "buff", 3, 1, "🫧", null),
                    baseMove("腐蚀喷吐", "debuff", 2, 1, "☠️", "weak"))),
            baseEnemy("jadeGolem", "青玉魔像", "💠", 165, false, true, List.of(
                    baseMove("碎岩重拳", "atk", 18, 1, "🪨", null),
                    baseMove("晶簇崩落", "atk", 9, 2, "💠", null),
                    baseMove("青玉壁障", "block", 14, 1, "🛡️", null),
                    baseMove("共鸣", "buff", 3, 1, "🔮", null))),
            baseEnemy("spireLord", "尖塔之主", "🔺", 200, false, true, List.of(
                    baseMove("终焉裁决", "atk", 22, 1, "⚔️", null),
                    baseMove("万钧坠击", "atk", 10, 3, "🌩️", null),
                    baseMove("邪能灌注", "buff", 4, 1, "🔺", null),
                    baseMove("王座威压", "debuff", 2, 1, "🌀", "weak"),
                    baseMove("绝望凝视", "debuff", 2, 1, "👁️", "vuln")))
    );

    /**
     * 内置平衡/难度参数（**只读镜像**）：内容 = notelab-c/lib/spire-engine.ts 的
     * MAP_ROWS / TOTAL_ACTS / ACT_BOSS_IDS / actScale 步进。用途：让「难度配置」页总有
     * 可用默认值，且 C 端在无自定义 balance 时回落这些内置常量。
     * ⚠️ 调平衡改此处须同步 C 端 spire-engine.ts 的对应常量，否则两端不一致。
     */
    private static final int BASE_TOTAL_ACTS = 3;
    private static final int BASE_MAP_ROWS = 16;
    private static final List<String> BASE_ACT_BOSS_IDS = List.of("king", "jadeGolem", "spireLord");
    private static final double BASE_ACT_SCALE_STEP = 0.3;

    private static Map<String, Object> baseBalance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalActs", BASE_TOTAL_ACTS);
        m.put("mapRows", BASE_MAP_ROWS);
        m.put("actBossIds", new ArrayList<>(BASE_ACT_BOSS_IDS));
        m.put("actScaleStep", BASE_ACT_SCALE_STEP);
        return m;
    }

    /**
     * 内置地图生成规则（**只读镜像**）：内容 = notelab-b/src/lib/spire-mapgen.ts 的 DEFAULT_PARAMS
     * （= notelab-c/public/spire/map-gen.config.json 的现值 + 项目扩展 event 权重/最早层与 earlySafeLayers）。
     * 用途：让「地图生成」页总有可用默认规则，且 C 端在无自定义 mapRules 时回落这些内置常量。
     * ⚠️ 调地图规则改此处须同步 notelab-b/src/lib/spire-mapgen.ts 的 DEFAULT_PARAMS 与
     *     notelab-c/lib/spire-engine.ts 的 BASE_MAP_RULES，否则两端不一致。
     */
    private static final Map<String, Object> BASE_MAP_RULES = baseMapRules();
    private static Map<String, Object> baseMapRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layers", 16);
        m.put("acts", 3);
        m.put("maxColumns", 4);
        m.put("pathCount", List.of(4, 6));
        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("enemy", 45); weights.put("elite", 15); weights.put("shop", 12);
        weights.put("rest", 10); weights.put("random", 18); weights.put("event", 12);
        m.put("weights", weights);
        Map<String, Object> minLayer = new LinkedHashMap<>();
        minLayer.put("enemy", 0); minLayer.put("elite", 3); minLayer.put("shop", 2);
        minLayer.put("rest", 2); minLayer.put("random", 0); minLayer.put("event", 2);
        m.put("minLayer", minLayer);
        Map<String, Object> revealPool = new LinkedHashMap<>();
        revealPool.put("normal", 45); revealPool.put("elite", 15); revealPool.put("shop", 12); revealPool.put("rest", 10);
        m.put("revealPool", revealPool);
        m.put("earlySafeLayers", 2);
        return m;
    }

    /**
     * spire JSON 体积上限（字符）。原来的 200KB 是按「只有卡/角色」估的；
     * 地图方案是自包含的整图节点表（一套 3 幕约 12KB），把上限提到 1MB 才够存几套。
     * 库里是 mediumtext（16MB），1MB 距上限很远；再大就该走对象存储而不是配置表。
     */
    private static final int MAX_SPIRE_CHARS = 1_000_000;
    /** 地图方案套数上限 */
    private static final int MAX_PACKS = 20;
    /** 单套方案幕数上限（本项目 3 幕，留点余量） */
    private static final int MAX_ACTS = 8;
    /** 单幕节点数上限（16 层 × 最多 ~6 列 ≈ 100，留足余量） */
    private static final int MAX_NODES = 4000;
    /** 素材路径长度上限（只是个 URL/相对路径，防呆） */
    private static final int MAX_ASSET_PATH = 500;
    /** 单个槽位的资源池条目上限（同类素材够用即可，防呆） */
    private static final int MAX_POOL_PER_SLOT = 50;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> body = spireOf(UiConfigService.getConfig());
        // 只读常量，供 B 端授权界面展示内置角色；不落库（publish 快照走 spireOf，不含该键）
        body.put("baseCharacters", BASE_CHARACTERS);
        // 只读常量，供 B 端「敌人制作」页打"内置/自定义"标签；不落库
        body.put("baseEnemies", BASE_ENEMIES);
        // 只读常量，供 B 端「难度配置」页展示内置默认值；不落库
        body.put("baseBalance", baseBalance());
        // 只读常量，供 B 端「地图生成」页展示内置默认规则；不落库
        body.put("baseMapRules", baseMapRules());
        return ResponseEntity.ok(body);
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
        Map<String, Object> spire = new LinkedHashMap<>();
        spire.put("cards", node.get("cards") != null && node.get("cards").isArray()
                ? JsonUtil.MAPPER.convertValue(node.get("cards"), List.class) : List.of());
        spire.put("characters", node.get("characters") != null && node.get("characters").isArray()
                ? JsonUtil.MAPPER.convertValue(node.get("characters"), List.class) : List.of());
        spire.put("skills", node.get("skills") != null && node.get("skills").isArray()
                ? JsonUtil.MAPPER.convertValue(node.get("skills"), List.class) : List.of());
        // 角色授权白名单：{组码: [角色 id...]}，类型净化后与三键一起写库
        spire.put("charAccess", sanitizeCharAccess(node.get("charAccess")));
        // 素材资源槽位：{槽位 key: C 端素材路径}；未配置的槽位 C 端回落内置默认
        spire.put("assets", sanitizeAssets(node.get("assets")));
        // 素材资源池：{槽位 key: [素材路径...]} —— 同一槽位可登记多个候选，
        // assets 里的那一个才是**当前使用**的；池子为空时 C 端不受影响（仍按 assets 走）。
        spire.put("assetPool", sanitizeAssetPool(node.get("assetPool")));
        // 地图方案：{defaultId, packs:[{id,name,params,acts:[{act,layers,nodes}]}]}；无方案时 C 端本地生成
        spire.put("maps", sanitizeMaps(node.get("maps")));
        // 敌人/Boss：{id,name,icon,hp,elite?,boss?,moves:[...]}；库中无则用内置 10 懒 seed
        spire.put("enemies", sanitizeEnemies(node.get("enemies")));
        // 平衡/难度：{totalActs,mapRows,actBossIds[],actScaleStep}；库中无则用内置默认值
        spire.put("balance", sanitizeBalance(node.get("balance")));
        // 地图生成规则：{layers,acts,maxColumns,pathCount,weights,minLayer,revealPool,earlySafeLayers}；库中无则用内置默认值
        spire.put("mapRules", sanitizeMapRules(node.get("mapRules")));
        String spireJson = JsonUtil.write(spire);
        if (spireJson.length() > MAX_SPIRE_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "自定义内容过大（>" + (MAX_SPIRE_CHARS / 1000) + "KB，多半是地图方案存太多，删几套再保存）"));
        }
        // 合并进现有 ui_config（保留 background/menus 等其它键）
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.put("spire", spire);
        try {
            UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * B/C 拆分阶段2：发布——把当前 spire 工坊内容整体快照写入顶层键 spire_published
     * （合并写，保留 background/menus/spire 等其它键，写法同 save）。
     */
    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.put("spire_published", spireOf(cfg));
        try {
            UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** B/C 拆分阶段2：下架——删除 spire_published 键（合并写，保留其它键） */
    @PostMapping("/unpublish")
    public ResponseEntity<Map<String, Object>> unpublish(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.remove("spire_published");
        try {
            UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** spire 出口（GET 与 publish 快照共用）：cards / characters / skills / charAccess / assets / assetPool / maps / enemies / balance / mapRules */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> spireOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", List.of());
        // 角色默认懒 seed 内置 4（库中无角色数据时也保证后台可编辑内置角色）
        out.put("characters", new ArrayList<>(BASE_CHARACTERS));
        out.put("skills", List.of());
        out.put("charAccess", new LinkedHashMap<String, List<String>>());
        out.put("assets", new LinkedHashMap<String, String>());
        out.put("assetPool", new LinkedHashMap<String, List<String>>());
        out.put("maps", Map.of("packs", List.of()));
        // 敌人默认懒 seed 内置 10（库中无敌人数据时也保证后台可编辑内置敌人）
        out.put("enemies", new ArrayList<>(BASE_ENEMIES));
        // 平衡/难度默认内置（库中无 balance 时用内置常量）
        out.put("balance", baseBalance());
        // 地图生成规则默认内置（库中无 mapRules 时用内置常量）
        out.put("mapRules", baseMapRules());
        Object o = cfg.get("spire");
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put("cards", m.getOrDefault("cards", List.of()));
            // 角色：库中有非空列表则用库的，否则继续用内置 4（懒 seed）
            Object ch = m.get("characters");
            out.put("characters", (ch instanceof List && !((List<?>) ch).isEmpty())
                    ? ch : new ArrayList<>(BASE_CHARACTERS));
            out.put("skills", m.getOrDefault("skills", List.of()));
            Object ca = m.get("charAccess");
            if (ca instanceof Map) out.put("charAccess", ca);
            Object as = m.get("assets");
            if (as instanceof Map) out.put("assets", as);
            Object ap = m.get("assetPool");
            if (ap instanceof Map) out.put("assetPool", ap);
            Object mp = m.get("maps");
            if (mp instanceof Map) out.put("maps", mp);
            // 敌人：库中有非空列表则用库的，否则继续用内置 10（懒 seed）
            Object en = m.get("enemies");
            out.put("enemies", (en instanceof List && !((List<?>) en).isEmpty())
                    ? en : new ArrayList<>(BASE_ENEMIES));
            // 平衡/难度：库中有 map 则用库的（缺字段以内置补齐），否则继续用内置
            Object ba = m.get("balance");
            if (ba instanceof Map) out.put("balance", mergeBalance((Map<String, Object>) ba));
            // 地图生成规则：库中有 map 则用库的（缺字段以内置补齐），否则继续用内置
            Object mr = m.get("mapRules");
            if (mr instanceof Map) out.put("mapRules", mergeMapRules((Map<String, Object>) mr));
        }
        return out;
    }

    /**
     * charAccess 类型净化：非对象→{}；值非数组→[]；数组元素只保留非空字符串（trim 去重）。
     * 键为 C 端用户组码（c_user_groups.code），值是该组可选择的角色 id 白名单。
     */
    private static Map<String, List<String>> sanitizeCharAccess(JsonNode node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            String code = e.getKey() == null ? "" : e.getKey().trim();
            if (code.isEmpty()) return;
            List<String> ids = new java.util.ArrayList<>();
            JsonNode v = e.getValue();
            if (v != null && v.isArray()) {
                for (JsonNode it : v) {
                    if (it == null || !it.isTextual()) continue;
                    String id = it.asText().trim();
                    if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                }
            }
            out.put(code, ids);
        });
        return out;
    }

    /**
     * 素材槽位净化：只保留 {非空字符串键: 非空字符串值}，值 trim、超长的丢弃。
     * 空值直接**丢掉而不是存空串** —— 语义上「未配置 = 回落内置默认」，
     * 存空串会让文档里堆一堆无意义键，也让 B 端的「文档指纹」在保存前后不一致。
     *
     * <p>值形如 `/games/spire/art/icon-normal.png`：**带 C 端 basePath 前缀**，
     * 由 B 端从素材清单接口拿到的 url 原样写入，后端不校验其可解析性（C 端会做 fail-open）。
     */
    private static Map<String, String> sanitizeAssets(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty() || k.length() > 120) return;
            JsonNode v = e.getValue();
            if (v == null || !v.isTextual()) return;
            String val = v.asText().trim();
            if (val.isEmpty() || val.length() > MAX_ASSET_PATH) return;
            out.put(k, val);
        });
        return out;
    }

    /**
     * 素材资源池净化：{槽位 key: [素材路径...]}。
     *
     * <p>与 assets 的分工：**池子 = 该槽位登记了哪些候选**，assets = 当前使用哪一个。
     * 同一类型（如精英怪）可以登记多张图，运营在后台切换即可，不用重新找素材路径。
     *
     * <p>净化规则：非对象→{}；值非数组→跳过；数组元素只保留合法路径字符串（trim、去重、保序），
     * 单槽位条目上限 MAX_POOL_PER_SLOT。键的规则与 assets 一致（槽位 key，见 B 端 ASSET_SLOTS）。
     */
    private static Map<String, List<String>> sanitizeAssetPool(JsonNode node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty() || k.length() > 120) return;
            JsonNode v = e.getValue();
            if (v == null || !v.isArray()) return;
            List<String> paths = new java.util.ArrayList<>();
            for (JsonNode it : v) {
                if (paths.size() >= MAX_POOL_PER_SLOT) break;
                if (it == null || !it.isTextual()) continue;
                String p = it.asText().trim();
                if (p.isEmpty() || p.length() > MAX_ASSET_PATH) continue;
                if (!paths.contains(p)) paths.add(p);
            }
            if (!paths.isEmpty()) out.put(k, paths);
        });
        return out;
    }

    /**
     * 地图方案净化：结构不对的**幕**丢弃，没有可用幕的**方案**丢弃。
     *
     * <p>刻意做得比 C 端轻：地图语义（不交叉 / 无死路 / 唯一 BOSS …）的裁决权在生成器与 C 端加载器，
     * 后端只保证「形状合法 + 体积可控」，避免后端变成第二套地图规则真相源。
     */
    private static Map<String, Object> sanitizeMaps(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> packs = new java.util.ArrayList<>();
        if (node == null || !node.isObject()) {
            out.put("defaultId", "");
            out.put("packs", packs);
            return out;
        }
        JsonNode arr = node.get("packs");
        if (arr != null && arr.isArray()) {
            for (JsonNode p : arr) {
                if (packs.size() >= MAX_PACKS) break;
                if (p == null || !p.isObject()) continue;
                Map<String, Object> pack = new LinkedHashMap<>();
                pack.put("id", str(p, "id"));
                pack.put("name", str(p, "name"));
                pack.put("createdAt", str(p, "createdAt"));
                JsonNode params = p.get("params");
                if (params != null && params.isObject()) {
                    pack.put("params", JsonUtil.MAPPER.convertValue(params, Map.class));
                }
                List<Object> acts = new java.util.ArrayList<>();
                JsonNode actsNode = p.get("acts");
                if (actsNode != null && actsNode.isArray()) {
                    for (JsonNode a : actsNode) {
                        if (acts.size() >= MAX_ACTS) break;
                        Map<String, Object> act = sanitizeAct(a);
                        if (act != null) acts.add(act);
                    }
                }
                if (acts.isEmpty()) continue;   // 没有一幕可用 → 整个方案丢掉
                pack.put("acts", acts);
                packs.add(pack);
            }
        }
        out.put("defaultId", str(node, "defaultId"));
        out.put("packs", packs);
        return out;
    }

    /** 单幕净化：nodes 必须是数组且每个元素形状合法（id/row/col/type/next）；不合法返回 null */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeAct(JsonNode a) {
        if (a == null || !a.isObject()) return null;
        JsonNode nodes = a.get("nodes");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty() || nodes.size() > MAX_NODES) return null;
        for (JsonNode n : nodes) {
            if (n == null || !n.isObject()) return null;
            JsonNode id = n.get("id"), row = n.get("row"), col = n.get("col"), type = n.get("type"), next = n.get("next");
            if (id == null || !id.isTextual() || id.asText().isEmpty()) return null;
            if (row == null || !row.isNumber() || col == null || !col.isNumber()) return null;
            if (type == null || !type.isTextual()) return null;
            if (next == null || !next.isArray()) return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("act", a.get("act") != null && a.get("act").isNumber() ? a.get("act").asInt() : 1);
        out.put("layers", a.get("layers") != null && a.get("layers").isNumber() ? a.get("layers").asInt() : nodes.size());
        out.put("nodes", JsonUtil.MAPPER.convertValue(nodes, List.class));
        return out;
    }

    /** 取字符串字段，缺省为空串（避免 null 污染 JSON） */
    private static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }

    /**
     * 平衡/难度净化：totalActs 1-8、mapRows 1-400、actScaleStep 0-5（小数），actBossIds 为
     * 非空字符串数组（幕 BOSS 的敌人 id，C 端缺失时回退终幕 BOSS）。非法值回落内置默认，
     * 缺字段用 baseBalance 补齐，保证 C 端无脑消费也不会拿到 null。
     */
    private static Map<String, Object> sanitizeBalance(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return baseBalance();
        out.put("totalActs", clampInt(node.get("totalActs"), 1, MAX_ACTS, BASE_TOTAL_ACTS));
        out.put("mapRows", clampInt(node.get("mapRows"), 1, MAX_NODES, BASE_MAP_ROWS));
        out.put("actScaleStep", clampDbl(node.get("actScaleStep"), 0.0, 5.0, BASE_ACT_SCALE_STEP));
        List<String> ids = new java.util.ArrayList<>();
        JsonNode ab = node.get("actBossIds");
        if (ab != null && ab.isArray()) {
            for (JsonNode it : ab) {
                if (it == null || !it.isTextual()) continue;
                String id = it.asText().trim();
                if (!id.isEmpty() && ids.size() < MAX_ACTS && !ids.contains(id)) ids.add(id);
            }
        }
        // 至少填到 totalActs 个：不足的用已收集的循环补齐（保证每幕都有 BOSS id，C 端不会越界）
        int need = clampInt(node.get("totalActs"), 1, MAX_ACTS, BASE_TOTAL_ACTS);
        if (ids.isEmpty()) ids.addAll(BASE_ACT_BOSS_IDS);
        while (ids.size() < need) ids.add(ids.get((ids.size() - 1) % ids.size()));
        out.put("actBossIds", new ArrayList<>(ids.subList(0, need)));
        return out;
    }

    /** 库中有 balance map 时，用库值覆盖内置默认（缺字段保留内置），再走净化口径保证形状合法 */
    private static Map<String, Object> mergeBalance(Map<String, Object> lib) {
        Map<String, Object> merged = new LinkedHashMap<>(baseBalance());
        for (String k : new String[] { "totalActs", "mapRows", "actScaleStep", "actBossIds" }) {
            if (lib.containsKey(k) && lib.get(k) != null) merged.put(k, lib.get(k));
        }
        return sanitizeBalance(JsonUtil.MAPPER.valueToTree(merged));
    }

    /**
     * 地图生成规则净化：layers 4-40、acts 1-8、maxColumns 2-8、pathCount 1-8、weights 0-999、
     * minLayer 0-40、revealPool 0-999、earlySafeLayers 0-8。缺字段/越界回落内置默认，保证 C 端
     * 无脑消费也不会拿到 null（fail-open）。口径与 B 端 spire-mapgen.ts 的 sanitizeParams 完全一致。
     */
    private static Map<String, Object> sanitizeMapRules(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return baseMapRules();
        out.put("layers", clampInt(node.get("layers"), 4, 40, 16));
        out.put("acts", clampInt(node.get("acts"), 1, 8, 3));
        out.put("maxColumns", clampInt(node.get("maxColumns"), 2, 8, 4));
        JsonNode pc = node.get("pathCount");
        out.put("pathCount", List.of(
                clampInt(pc != null && pc.isArray() && pc.size() > 0 ? pc.get(0) : null, 1, 8, 4),
                clampInt(pc != null && pc.isArray() && pc.size() > 1 ? pc.get(1) : null, 1, 8, 6)));
        Map<String, Object> weights = new LinkedHashMap<>();
        JsonNode w = node.get("weights");
        weights.put("enemy", clampInt(w != null && w.has("enemy") ? w.get("enemy") : null, 0, 999, 45));
        weights.put("elite", clampInt(w != null && w.has("elite") ? w.get("elite") : null, 0, 999, 15));
        weights.put("shop", clampInt(w != null && w.has("shop") ? w.get("shop") : null, 0, 999, 12));
        weights.put("rest", clampInt(w != null && w.has("rest") ? w.get("rest") : null, 0, 999, 10));
        weights.put("random", clampInt(w != null && w.has("random") ? w.get("random") : null, 0, 999, 18));
        weights.put("event", clampInt(w != null && w.has("event") ? w.get("event") : null, 0, 999, 12));
        out.put("weights", weights);
        Map<String, Object> minLayer = new LinkedHashMap<>();
        JsonNode ml = node.get("minLayer");
        minLayer.put("enemy", clampInt(ml != null && ml.has("enemy") ? ml.get("enemy") : null, 0, 40, 0));
        minLayer.put("elite", clampInt(ml != null && ml.has("elite") ? ml.get("elite") : null, 0, 40, 3));
        minLayer.put("shop", clampInt(ml != null && ml.has("shop") ? ml.get("shop") : null, 0, 40, 2));
        minLayer.put("rest", clampInt(ml != null && ml.has("rest") ? ml.get("rest") : null, 0, 40, 2));
        minLayer.put("random", clampInt(ml != null && ml.has("random") ? ml.get("random") : null, 0, 40, 0));
        minLayer.put("event", clampInt(ml != null && ml.has("event") ? ml.get("event") : null, 0, 40, 2));
        out.put("minLayer", minLayer);
        Map<String, Object> revealPool = new LinkedHashMap<>();
        JsonNode rp = node.get("revealPool");
        revealPool.put("normal", clampInt(rp != null && rp.has("normal") ? rp.get("normal") : null, 0, 999, 45));
        revealPool.put("elite", clampInt(rp != null && rp.has("elite") ? rp.get("elite") : null, 0, 999, 15));
        revealPool.put("shop", clampInt(rp != null && rp.has("shop") ? rp.get("shop") : null, 0, 999, 12));
        revealPool.put("rest", clampInt(rp != null && rp.has("rest") ? rp.get("rest") : null, 0, 999, 10));
        out.put("revealPool", revealPool);
        out.put("earlySafeLayers", clampInt(node.get("earlySafeLayers"), 0, 8, 2));
        return out;
    }

    /** 库中有 mapRules map 时，用库值覆盖内置默认（缺字段保留内置），再走净化口径保证形状合法 */
    private static Map<String, Object> mergeMapRules(Map<String, Object> lib) {
        Map<String, Object> merged = new LinkedHashMap<>(baseMapRules());
        for (String k : new String[] { "layers", "acts", "maxColumns", "pathCount", "weights", "minLayer", "revealPool", "earlySafeLayers" }) {
            if (lib.containsKey(k) && lib.get(k) != null) merged.put(k, lib.get(k));
        }
        return sanitizeMapRules(JsonUtil.MAPPER.valueToTree(merged));
    }

    /** 取 Double 并夹到 [lo,hi]，非法/缺失返回 dft */
    private static double clampDbl(JsonNode n, double lo, double hi, double dft) {
        if (n == null || !n.isNumber()) return dft;
        double v = n.asDouble();
        return Math.max(lo, Math.min(hi, v));
    }

    /** 取整数并夹到 [lo,hi]，非法/缺失返回 dft（敌人血量、move 数值用） */
    private static int clampInt(JsonNode n, int lo, int hi, int dft) {
        if (n == null || !n.isNumber()) return dft;
        return Math.max(lo, Math.min(hi, n.asInt()));
    }

    /** 意图类型白名单（与 C 端 Move.kind 对齐） */
    private static final java.util.Set<String> MOVE_KINDS =
            java.util.Set.of("atk", "block", "buff", "debuff");

    /**
     * 敌人净化：每条必须 id / name 非空、至少 1 个合法 move，否则丢弃该条（结构与体积校验，
     * 不做战斗平衡裁决——平衡在 C 端引擎与 `actScale` 倍率）。move 必须 name/kind 合法，
     * kind 非白名单丢弃该 move；debuffKind 只接受 weak/vuln。
     */
    private static List<Map<String, Object>> sanitizeEnemies(JsonNode node) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode e : node) {
            if (e == null || !e.isObject()) continue;
            String id = str(e, "id");
            String name = str(e, "name");
            if (id.isEmpty() || name.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("icon", e.has("icon") && e.get("icon").isTextual() ? e.get("icon").asText() : "👾");
            m.put("hp", clampInt(e.get("hp"), 1, 999, 30));
            JsonNode el = e.get("elite");
            if (el != null && el.isBoolean() && el.asBoolean()) m.put("elite", true);
            JsonNode bo = e.get("boss");
            if (bo != null && bo.isBoolean() && bo.asBoolean()) m.put("boss", true);
            List<Map<String, Object>> moves = new java.util.ArrayList<>();
            JsonNode mv = e.get("moves");
            if (mv != null && mv.isArray()) {
                for (JsonNode mm : mv) {
                    if (mm == null || !mm.isObject()) continue;
                    String kind = str(mm, "kind");
                    if (!MOVE_KINDS.contains(kind)) continue;
                    Map<String, Object> mv2 = new LinkedHashMap<>();
                    mv2.put("name", str(mm, "name"));
                    mv2.put("kind", kind);
                    mv2.put("amt", clampInt(mm.get("amt"), 0, 99, 1));
                    mv2.put("hits", clampInt(mm.get("hits"), 1, 9, 1));
                    mv2.put("icon", mm.has("icon") && mm.get("icon").isTextual() ? mm.get("icon").asText() : "❓");
                    String dk = str(mm, "debuffKind");
                    if (dk.equals("weak") || dk.equals("vuln")) mv2.put("debuffKind", dk);
                    moves.add(mv2);
                }
            }
            if (moves.isEmpty()) continue; // 无可用招式的敌人无意义，丢弃
            m.put("moves", moves);
            out.add(m);
        }
        return out;
    }
}
