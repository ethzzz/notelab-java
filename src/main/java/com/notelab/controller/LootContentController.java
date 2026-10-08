package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.service.EventRecorder;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 摸金行动（Loot Raid）内容工坊：GET|POST /api/loot-content。
 *
 * <p>稀有度 / 物品 / 容器 / 掉落表 / 地图 / 全局参数 六切片存 ui_config JSON 的 "loot" 键，不新建表。
 * 结构与写法照抄 {@link SpireContentController}（爬塔已验证过的范式）：
 * <ul>
 *   <li><b>手工白名单</b>：save() 里逐键 put，没登记的键会被**静默丢弃**（爬塔踩过的坑）；</li>
 *   <li><b>懒 seed</b>：库中某切片为空则回内置默认（BASE_*），保证 B 端页面首次打开就有东西可编辑；</li>
 *   <li><b>整包覆盖写</b>：POST 提交全量六切片；</li>
 *   <li><b>发布快照</b>：publish 把 lootOf() 的结果快照进 ui_config.loot_published，C 端只读快照。</li>
 * </ul>
 *
 * <p>净化只做<b>结构与体积</b>校验，不做玩法裁决（平衡/EV 归 C 端引擎与 B 端模拟器）。
 * 一条不合法就丢该条，不让整包 400。
 *
 * <p>⚠️ <b>稀有度是动态的</b>（2026-10-06）：档位由 {@code loot.rarities} 数组定义，
 * 数组顺序 = 由低到高。物品的 rarity、容器的 rarityWeights / pity.minRarity 都引用档位 key，
 * 因此 <b>必须先净化 rarities 拿到 order，再净化其余切片</b> —— 顺序反了会把后台新增的档位
 * 判成"非法稀有度"而整条丢掉（表现为"配的东西全没了但没报错"）。
 *
 * <p>⚠️ 三端同构：本文件的默认包、净化口径必须与
 * {@code notelab-c/lib/loot-content.ts}、{@code notelab-b/.../loot-editor/_shared/model.ts} 一致。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/loot-content")
public class LootContentController {

    /** 色板 key 白名单（与 B/C 端 PALETTE 一致；未登记的色一律回落 slate） */
    private static final Set<String> PALETTE_KEYS = Set.of(
            "slate", "blue", "cyan", "emerald", "amber", "purple", "rose", "red");

    /** 形状 id 白名单（与 B/C 端 SHAPES 一致；未登记的一律回落 1x1，绝不留非法值） */
    private static final Set<String> SHAPE_IDS = Set.of("1x1", "1x2", "1x3", "2x2", "L", "J", "T", "S");

    private static final int MAX_LOOT_CHARS = 1_000_000;
    private static final int MAX_ITEMS = 500;
    private static final int MAX_CONTAINERS = 200;
    private static final int MAX_TABLES = 200;
    private static final int MAX_MAPS = 50;
    private static final int MAX_POOL_PER_TABLE = 200;
    private static final int MAX_RARITIES = 12;

    // ==================================================================================
    // 内置默认内容（只读镜像 / 懒 seed 初值）
    // ⚠️ C 端 lib/loot-content.ts 的 DEFAULT_LOOT 必须与此**逐字段一致**。
    // ==================================================================================

    private static Map<String, Object> rarity(String key, String label, String color, int unitValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("label", label); m.put("color", color); m.put("unitValue", unitValue);
        return m;
    }

    /**
     * 内置五档。unitValue = <b>每格基准价</b>：物品面值 ≈ 该档每格价 × 占格数，
     * 于是「同等稀有度下占格越多越值钱」是配置出来的，不是代码写死的。
     */
    private static final List<Map<String, Object>> BASE_RARITIES = List.of(
            rarity("common", "普通", "slate", 65),
            rarity("uncommon", "精良", "blue", 280),
            rarity("rare", "稀有", "purple", 830),
            rarity("epic", "史诗", "amber", 2250),
            rarity("legendary", "传说", "red", 7250));

    private static final List<String> BASE_ORDER = List.of("common", "uncommon", "rare", "epic", "legendary");

    private static Map<String, Object> item(String id, String name, String rarity, int baseValue,
            Integer recycleValue, int stack, String emoji, String shape, String image,
            List<String> tags, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("rarity", rarity); m.put("baseValue", baseValue);
        m.put("recycleValue", recycleValue); m.put("stack", stack); m.put("emoji", emoji);
        m.put("shape", shape); m.put("image", image);
        m.put("tags", tags); m.put("desc", desc);
        return m;
    }

    private static Map<String, Object> weights(double c, double u, double r, double e, double l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("common", c); m.put("uncommon", u); m.put("rare", r); m.put("epic", e); m.put("legendary", l);
        return m;
    }

    private static Map<String, Object> pity(int afterRuns, String minRarity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("afterRuns", afterRuns); m.put("minRarity", minRarity);
        return m;
    }

    private static Map<String, Object> container(String id, String name, int colsMin, int colsMax,
            int rowsMin, int rowsMax, double fillRate, int slotMs,
            Map<String, Object> rarityWeights, int riskCost, Map<String, Object> p, String tableId,
            String emoji, String image) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name);
        m.put("colsMin", colsMin); m.put("colsMax", colsMax);
        m.put("rowsMin", rowsMin); m.put("rowsMax", rowsMax);
        m.put("fillRate", fillRate); m.put("slotMs", slotMs);
        m.put("rarityWeights", rarityWeights); m.put("riskCost", riskCost);
        m.put("pity", p); m.put("tableId", tableId); m.put("emoji", emoji); m.put("image", image);
        return m;
    }

    private static Map<String, Object> poolItem(String itemId, int weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", itemId); m.put("weight", weight);
        return m;
    }

    private static Map<String, Object> table(String id, String name, List<Map<String, Object>> pool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("pool", pool);
        return m;
    }

    private static Map<String, Object> entry(int coins, int minExtracts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coins", coins); m.put("items", new ArrayList<>()); m.put("minExtracts", minExtracts);
        m.put("groups", new ArrayList<>());
        return m;
    }

    private static Map<String, Object> ctnCount(String containerId, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("containerId", containerId); m.put("count", count);
        return m;
    }

    private static Map<String, Object> mapDef(String id, String name, int timeLimitSec, int riskLimit,
            double valueMult, double tierBoost, Map<String, Object> e,
            List<Map<String, Object>> containers, int extractPoints) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("timeLimitSec", timeLimitSec); m.put("riskLimit", riskLimit);
        m.put("valueMult", valueMult); m.put("tierBoost", tierBoost); m.put("entry", e);
        m.put("containers", containers); m.put("extractPoints", extractPoints);
        return m;
    }

    /**
     * 24 件物品：面值 = 该档每格基准价 × 占格数。
     * ⚠️ 唯一的故意例外是「王冠宝石」：1×1 却是传说档 —— 保留"小而极贵"的幻想。
     */
    private static final List<Map<String, Object>> BASE_ITEMS = List.of(
            item("it-001", "旧手表", "common", 60, null, 1, "⌚", "1x1", "/loot/it-001.png", List.of("junk"), ""),
            item("it-002", "生锈扳手", "common", 110, null, 1, "🔧", "1x2", "/loot/it-002.png", List.of("junk"), ""),
            item("it-003", "罐头食品", "common", 45, null, 3, "🥫", "1x1", "/loot/it-003.png", List.of("supply"), ""),
            item("it-004", "铜线卷", "common", 140, null, 2, "🔌", "1x2", "/loot/it-004.png", List.of("mat"), ""),
            item("it-005", "军用水壶", "common", 50, null, 1, "🍶", "1x1", "/loot/it-005.png", List.of("supply"), ""),
            item("it-006", "破旧地图", "common", 85, null, 1, "🗺️", "1x1", "/loot/it-006.png", List.of("info"), ""),
            item("it-007", "打火机", "common", 65, null, 1, "🔥", "1x1", "/loot/it-007.png", List.of("supply"), ""),
            item("it-008", "零件盒", "common", 380, null, 2, "🧰", "2x2", "/loot/it-008.png", List.of("mat"), ""),
            item("it-009", "急救包", "uncommon", 440, null, 2, "🩹", "1x2", "/loot/it-009.png", List.of("med"), ""),
            item("it-010", "便携电台", "uncommon", 520, null, 1, "📻", "1x2", "/loot/it-010.png", List.of("tech"), ""),
            item("it-011", "军用望远镜", "uncommon", 900, null, 1, "🔭", "1x3", "/loot/it-011.png", List.of("optics"), ""),
            item("it-012", "精钢匕首", "uncommon", 360, null, 1, "🗡️", "1x2", "/loot/it-012.png", List.of("weapon"), ""),
            item("it-013", "防毒面具", "uncommon", 1360, null, 1, "😷", "2x2", "/loot/it-013.png", List.of("gear"), ""),
            item("it-014", "加密硬盘", "uncommon", 380, null, 1, "💽", "1x1", "/loot/it-014.png", List.of("tech", "info"), ""),
            item("it-015", "夜视仪", "rare", 1700, null, 1, "🕶️", "1x2", "/loot/it-015.png", List.of("optics", "gear"), ""),
            item("it-016", "金条", "rare", 2000, null, 5, "🧱", "1x2", "/loot/it-016.png", List.of("treasure"), ""),
            item("it-017", "稀有电路板", "rare", 1240, null, 3, "🔲", "1x2", "/loot/it-017.png", List.of("tech", "mat"), ""),
            item("it-018", "古董怀表", "rare", 700, null, 1, "🕰️", "1x1", "/loot/it-018.png", List.of("treasure"), ""),
            item("it-019", "军用手枪", "rare", 3450, null, 1, "🔫", "1x3", "/loot/it-019.png", List.of("weapon"), ""),
            item("it-020", "黄金雕像", "epic", 8800, null, 1, "🗿", "2x2", "/loot/it-020.png", List.of("treasure"), ""),
            item("it-021", "实验样本", "epic", 3600, null, 1, "🧪", "1x2", "/loot/it-021.png", List.of("tech"), ""),
            item("it-022", "稀有芯片组", "epic", 2700, null, 2, "💠", "1x1", "/loot/it-022.png", List.of("tech"), ""),
            item("it-023", "黑箱核心", "legendary", 24000, null, 1, "⬛", "2x2", "/loot/it-023.png", List.of("artifact"), ""),
            item("it-024", "王冠宝石", "legendary", 8500, null, 1, "👑", "1x1", "/loot/it-024.png", List.of("treasure"), ""));

    /**
     * 6 种容器。网格尺寸给的是<b>区间</b>，开局按 seed 掷 —— 这就是"物资箱几×几是随机的"。
     * fillRate &lt; 1 才会出现空格，别设成 1（那样永远是满的，"摸空"这条线就没了）。
     */
    private static final List<Map<String, Object>> BASE_CONTAINERS = List.of(
            container("ct-crate", "木箱", 2, 3, 2, 2, 0.8, 600, weights(55, 28, 12, 4.5, 0.5), 1, null, "lt-crate", "📦", "/loot/ct-crate.png"),
            container("ct-tool", "工具柜", 3, 3, 2, 2, 0.75, 1000, weights(45, 33, 15, 6, 1), 2, null, "lt-tool", "🔧", "/loot/ct-tool.png"),
            container("ct-ammo", "弹药箱", 2, 2, 2, 3, 0.8, 800, weights(50, 30, 14, 5, 1), 2, null, "lt-ammo", "🧨", "/loot/ct-ammo.png"),
            container("ct-med", "医疗柜", 2, 3, 2, 2, 0.75, 1200, weights(48, 32, 14, 5, 1), 2, null, "lt-med", "🩺", "/loot/ct-med.png"),
            container("ct-safe", "保险柜", 2, 2, 2, 2, 0.9, 3000, weights(20, 30, 30, 15, 5), 3, pity(12, "epic"), "lt-safe", "🔐", "/loot/ct-safe.png"),
            container("ct-cage", "储物笼", 3, 4, 2, 3, 0.7, 500, weights(70, 20, 8, 1.5, 0.5), 1, null, "lt-cage", "🗄️", "/loot/ct-cage.png"));

    /** 6 张掉落表；池子每档至少 1 件候选（否则该档轮盘抽空 → 引擎降档） */
    private static final List<Map<String, Object>> BASE_TABLES = List.of(
            table("lt-crate", "木箱掉落", List.of(
                    poolItem("it-001", 22), poolItem("it-002", 20), poolItem("it-003", 24), poolItem("it-004", 16),
                    poolItem("it-009", 10), poolItem("it-012", 12),
                    poolItem("it-017", 6), poolItem("it-021", 2), poolItem("it-023", 1))),
            table("lt-tool", "工具柜掉落", List.of(
                    poolItem("it-002", 20), poolItem("it-004", 22), poolItem("it-008", 18),
                    poolItem("it-010", 14), poolItem("it-012", 10), poolItem("it-014", 8),
                    poolItem("it-017", 8), poolItem("it-022", 2), poolItem("it-023", 1))),
            table("lt-ammo", "弹药箱掉落", List.of(
                    poolItem("it-002", 30), poolItem("it-008", 26),
                    poolItem("it-011", 16), poolItem("it-012", 14),
                    poolItem("it-019", 10), poolItem("it-021", 2), poolItem("it-024", 1))),
            table("lt-med", "医疗柜掉落", List.of(
                    poolItem("it-003", 28), poolItem("it-005", 26),
                    poolItem("it-009", 24), poolItem("it-013", 18),
                    poolItem("it-015", 8), poolItem("it-021", 2), poolItem("it-024", 1))),
            table("lt-safe", "保险柜掉落", List.of(
                    poolItem("it-007", 20), poolItem("it-014", 24),
                    poolItem("it-015", 20), poolItem("it-016", 22), poolItem("it-018", 18),
                    poolItem("it-020", 12), poolItem("it-021", 10), poolItem("it-022", 6),
                    poolItem("it-023", 3), poolItem("it-024", 2))),
            table("lt-cage", "储物笼掉落", List.of(
                    poolItem("it-001", 22), poolItem("it-003", 24), poolItem("it-005", 20),
                    poolItem("it-006", 18), poolItem("it-007", 22),
                    poolItem("it-009", 10), poolItem("it-017", 4), poolItem("it-021", 1), poolItem("it-023", 1))));

    /**
     * 2 张图。
     * ⚠️ 2026-10-06 形状化后两个数值重调：
     *   ① riskLimit：容器变网格后一格一格摸、风险按占格数累加，旧上限会开局就爆（20→22、30→40）；
     *   ② valueMult：背包从「8 件」变成「15 格」，能带走的东西多了约 2.4 倍，
     *      价值倍率必须同比下调（0.35→0.18、0.50→0.23），否则 EV 会飙到 4.2×（远超 [1.5, 3.5]）。
     */
    private static final List<Map<String, Object>> BASE_MAPS = List.of(
            mapDef("depot", "仓库区", 300, 22, 0.18, 0, entry(200, 0),
                    List.of(ctnCount("ct-crate", 4), ctnCount("ct-safe", 1)), 2),
            mapDef("port", "港口集装箱", 240, 40, 0.23, 0.4, entry(400, 3),
                    List.of(ctnCount("ct-crate", 3), ctnCount("ct-tool", 3), ctnCount("ct-ammo", 2),
                            ctnCount("ct-med", 2), ctnCount("ct-safe", 2), ctnCount("ct-cage", 2)), 3));

    /** 全局参数（懒 seed 默认值）。背包 = 5×3 网格（15 格），旧版 backpackCap(8 件) 已废弃 */
    private static Map<String, Object> baseBalance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recycleRate", 0.6);
        m.put("extractRate", 0.55);
        m.put("backpackCols", 5);
        m.put("backpackRows", 3);
        m.put("initialCoins", 500);
        m.put("rescueCoins", 200);
        m.put("rescueCooldownSec", 86400);
        m.put("extractHoldMs", 5000);
        m.put("riskPerSlot", 1);
        // ⚠️ 与设计目标区间 [1.5, 3.5] 自洽：warn = 区间上限（超了才提示），reject = 10× 门槛（崩到这个
        //    量级才拒绝保存）。旧值 1.15/3.0 会让健康图常驻告警，且手改 valueMult 一点就被拒。
        m.put("evWarnRatio", 3.5);
        m.put("evRejectRatio", 10.0);
        return m;
    }

    // ==================================================================================
    // 接口
    // ==================================================================================

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> body = lootOf(UiConfigService.getConfig());
        // 只读常量：供 B 端展示"内置默认"，不落库（publish 快照不含这些键）
        body.put("baseBalance", baseBalance());
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

        // ⚠️ 顺序不可换：rarities 先净化出 order，物品/容器才能按它校验稀有度 key。
        //    反过来会把后台新增的档位判成非法 → 整条丢掉，且**不报错**。
        List<Map<String, Object>> rarities = sanitizeRarities(node.get("rarities"));
        List<String> order = orderOf(rarities);

        // ⚠️ 手工白名单：这里没 put 的键会被静默丢弃（B 端存了刷新就没了且不报错）
        Map<String, Object> loot = new LinkedHashMap<>();
        loot.put("rarities", rarities);
        loot.put("items", sanitizeItems(node.get("items"), order));
        loot.put("containers", sanitizeContainers(node.get("containers"), order));
        loot.put("tables", sanitizeTables(node.get("tables")));
        loot.put("maps", sanitizeMaps(node.get("maps")));
        loot.put("balance", sanitizeBalance(node.get("balance")));

        String lootJson = JsonUtil.write(loot);
        if (lootJson.length() > MAX_LOOT_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "自定义内容过大（>" + (MAX_LOOT_CHARS / 1000) + "KB），删几件物品/容器再保存"));
        }
        try {
            UiConfigService.update("loot", loot);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 已发布快照是否存在的轻量查询（B 端用于提示"改了但没发布"） */
    @GetMapping("/published")
    public ResponseEntity<Map<String, Object>> published(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Object snap = UiConfigService.getConfig().get("loot_published");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("published", snap instanceof Map);
        return ResponseEntity.ok(out);
    }

    /** 发布：把当前编辑内容整体快照写入顶层键 loot_published（合并写，保留其它键） */
    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> snapshot = lootOf(UiConfigService.getConfig());
        try {
            UiConfigService.update("loot_published", snapshot);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        // 服务端旁路埋点（PRD-P0 §4.2）：与 spire_publish 同口径，后台发了没人玩 = 白干
        EventRecorder.record("b", "loot_publish", AuthUtil.userId(user), request, JsonSanitizer.snapshotStat(snapshot));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 下架：删除 loot_published 键（C 端回落内置默认包） */
    @PostMapping("/unpublish")
    public ResponseEntity<Map<String, Object>> unpublish(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        try {
            UiConfigService.remove("loot_published");
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** loot 出口（GET 与 publish 快照共用）。库中某切片为空 → 回内置默认（懒 seed） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> lootOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rarities", new ArrayList<>(BASE_RARITIES));
        out.put("items", new ArrayList<>(BASE_ITEMS));
        out.put("containers", new ArrayList<>(BASE_CONTAINERS));
        out.put("tables", new ArrayList<>(BASE_TABLES));
        out.put("maps", new ArrayList<>(BASE_MAPS));
        out.put("balance", baseBalance());
        Object o = cfg.get("loot");
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            Object rs = m.get("rarities");
            if (rs instanceof List && !((List<?>) rs).isEmpty()) out.put("rarities", rs);
            Object it = m.get("items");
            if (it instanceof List && !((List<?>) it).isEmpty()) out.put("items", it);
            Object ct = m.get("containers");
            if (ct instanceof List && !((List<?>) ct).isEmpty()) out.put("containers", ct);
            Object tb = m.get("tables");
            if (tb instanceof List && !((List<?>) tb).isEmpty()) out.put("tables", tb);
            Object mp = m.get("maps");
            if (mp instanceof List && !((List<?>) mp).isEmpty()) out.put("maps", mp);
            Object ba = m.get("balance");
            if (ba instanceof Map) out.put("balance", mergeBalance((Map<String, Object>) ba));
        }
        return out;
    }

    /** 档位 key 列表（数组顺序 = 由低到高） */
    private static List<String> orderOf(List<Map<String, Object>> rarities) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rarities) {
            Object k = r.get("key");
            if (k instanceof String && !((String) k).isEmpty()) out.add((String) k);
        }
        return out.isEmpty() ? new ArrayList<>(BASE_ORDER) : out;
    }

    // ==================================================================================
    // 净化（只做结构与体积校验；不合法丢该条）
    // ==================================================================================

    /** 稀有度档位：key 非空且唯一；color 不在色板里回落 slate；unitValue ≥ 0 */
    private static List<Map<String, Object>> sanitizeRarities(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode r : node) {
                if (out.size() >= MAX_RARITIES) break;
                if (r == null || !r.isObject()) continue;
                String key = JsonSanitizer.str(r, "key");
                if (key.isEmpty() || seen.contains(key)) continue;
                seen.add(key);
                String label = JsonSanitizer.str(r, "label");
                String color = JsonSanitizer.str(r, "color").toLowerCase(Locale.ROOT);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", key);
                m.put("label", label.isEmpty() ? key : label);
                m.put("color", PALETTE_KEYS.contains(color) ? color : "slate");
                m.put("unitValue", JsonSanitizer.clampInt(r.get("unitValue"), 0, 9_999_999, 0));
                out.add(m);
            }
        }
        return out.isEmpty() ? new ArrayList<>(BASE_RARITIES) : out;
    }

    private static List<Map<String, Object>> sanitizeItems(JsonNode node, List<String> order) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        Set<String> orderSet = new LinkedHashSet<>(order);
        for (JsonNode it : node) {
            if (out.size() >= MAX_ITEMS) break;
            if (it == null || !it.isObject()) continue;
            String id = JsonSanitizer.str(it, "id");
            String name = JsonSanitizer.str(it, "name");
            String rarity = JsonSanitizer.str(it, "rarity");
            // ⚠️ 稀有度必须属于当前 order：档位被删掉后属于它的物品会被整条丢弃（B 端删档前会提示影响面）
            if (id.isEmpty() || name.isEmpty() || !orderSet.contains(rarity) || seen.contains(id)) continue;
            seen.add(id);
            String shape = JsonSanitizer.str(it, "shape");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("rarity", rarity);
            m.put("baseValue", JsonSanitizer.clampInt(it.get("baseValue"), 1, 9_999_999, 50));
            JsonNode rv = it.get("recycleValue");
            m.put("recycleValue", (rv != null && rv.isNumber()) ? JsonSanitizer.clampInt(rv, 0, 9_999_999, 0) : null);
            m.put("stack", JsonSanitizer.clampInt(it.get("stack"), 1, 99, 1));
            m.put("emoji", it.has("emoji") && it.get("emoji").isTextual() ? it.get("emoji").asText() : "📦");
            // 形状必须落在白名单里：写错的一律回落 1×1，绝不留非法值（放置算法会炸）
            m.put("shape", SHAPE_IDS.contains(shape) ? shape : "1x1");
            m.put("image", it.has("image") && it.get("image").isTextual() ? it.get("image").asText().trim() : "");
            List<String> tags = new ArrayList<>();
            JsonNode tg = it.get("tags");
            if (tg != null && tg.isArray()) {
                for (JsonNode t : tg) if (t != null && t.isTextual() && !t.asText().trim().isEmpty()) tags.add(t.asText().trim());
            }
            m.put("tags", tags);
            m.put("desc", it.has("desc") && it.get("desc").isTextual() ? it.get("desc").asText() : "");
            out.add(m);
        }
        return out;
    }

    /** 旧配置（只有标量 slots、没有网格字段）的迁移 —— 不迁移容器会被整条丢掉 */
    private static int[] gridFromSlots(int slots) {
        if (slots <= 1) return new int[]{1, 1, 1, 1};
        if (slots == 2) return new int[]{2, 2, 1, 1};
        if (slots <= 4) return new int[]{2, 2, 2, 2};
        return new int[]{3, 3, 2, 2};
    }

    private static List<Map<String, Object>> sanitizeContainers(JsonNode node, List<String> order) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode c : node) {
            if (out.size() >= MAX_CONTAINERS) break;
            if (c == null || !c.isObject()) continue;
            String id = JsonSanitizer.str(c, "id");
            String name = JsonSanitizer.str(c, "name");
            String tableId = JsonSanitizer.str(c, "tableId");
            if (id.isEmpty() || name.isEmpty() || tableId.isEmpty() || seen.contains(id)) continue;
            Map<String, Object> rw = sanitizeRarityWeights(c.get("rarityWeights"), order);
            if (rw == null) continue;   // 各档不全或总和为 0 → 丢该条
            seen.add(id);
            // 旧 slots 迁移：库里已发布的那份就是这种形态
            int[] g = gridFromSlots(JsonSanitizer.clampInt(c.get("slots"), 1, 64, -1) < 0 ? 1 : JsonSanitizer.clampInt(c.get("slots"), 1, 64, 1));
            boolean hasGrid = c.has("colsMin") || c.has("colsMax") || c.has("rowsMin") || c.has("rowsMax");
            int defMin = hasGrid ? 1 : g[0];
            int colsMin = JsonSanitizer.clampInt(c.get("colsMin"), 1, 8, hasGrid ? 2 : g[0]);
            int colsMax = JsonSanitizer.clampInt(c.get("colsMax"), colsMin, 8, hasGrid ? 2 : g[1]);
            int rowsMin = JsonSanitizer.clampInt(c.get("rowsMin"), 1, 8, hasGrid ? 2 : g[2]);
            int rowsMax = JsonSanitizer.clampInt(c.get("rowsMax"), rowsMin, 8, hasGrid ? 2 : g[3]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("colsMin", colsMin);
            m.put("colsMax", colsMax);
            m.put("rowsMin", rowsMin);
            m.put("rowsMax", rowsMax);
            m.put("fillRate", JsonSanitizer.clampDbl(c.get("fillRate"), 0, 1, 0.75));
            m.put("slotMs", JsonSanitizer.clampInt(c.get("slotMs"), 100, 10_000, 800));
            m.put("rarityWeights", rw);
            m.put("riskCost", JsonSanitizer.clampInt(c.get("riskCost"), 0, 10, 1));
            JsonNode p = c.get("pity");
            Map<String, Object> pm = null;
            if (p != null && p.isObject()) {
                String minRarity = JsonSanitizer.str(p, "minRarity");
                if (order.contains(minRarity)) {
                    pm = new LinkedHashMap<>();
                    pm.put("afterRuns", JsonSanitizer.clampInt(p.get("afterRuns"), 2, 50, 12));
                    pm.put("minRarity", minRarity);
                }
            }
            m.put("pity", pm);
            m.put("tableId", tableId);
            m.put("emoji", c.has("emoji") && c.get("emoji").isTextual() ? c.get("emoji").asText() : "📦");
            m.put("image", c.has("image") && c.get("image").isTextual() ? c.get("image").asText().trim() : "");
            out.add(m);
        }
        return out;
    }

    /** 各档权重：order 里每一档都必须是 ≥ 0 的有限数、总和 > 0；否则返回 null（丢该条） */
    private static Map<String, Object> sanitizeRarityWeights(JsonNode node, List<String> order) {
        if (node == null || !node.isObject()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        double sum = 0;
        for (String r : order) {
            if (!node.has(r) || !node.get(r).isNumber()) return null;
            double v = Math.max(0, Math.min(999, node.get(r).asDouble()));
            out.put(r, v);
            sum += v;
        }
        return sum > 0 ? out : null;
    }

    private static List<Map<String, Object>> sanitizeTables(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode t : node) {
            if (out.size() >= MAX_TABLES) break;
            if (t == null || !t.isObject()) continue;
            String id = JsonSanitizer.str(t, "id");
            if (id.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            List<Map<String, Object>> pool = new ArrayList<>();
            JsonNode pn = t.get("pool");
            if (pn != null && pn.isArray()) {
                for (JsonNode p : pn) {
                    if (pool.size() >= MAX_POOL_PER_TABLE) break;
                    if (p == null || !p.isObject()) continue;
                    String itemId = JsonSanitizer.str(p, "itemId");
                    if (itemId.isEmpty()) continue;
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("itemId", itemId);
                    pm.put("weight", JsonSanitizer.clampInt(p.get("weight"), 0, 999, 1));
                    pool.add(pm);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", JsonSanitizer.str(t, "name").isEmpty() ? id : JsonSanitizer.str(t, "name"));
            m.put("pool", pool);
            out.add(m);
        }
        return out;
    }

    /** 地图净化：entry / containers 逐层净化；id 缺或重复丢弃该条 */
    private static List<Map<String, Object>> sanitizeMaps(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode mp : node) {
            if (out.size() >= MAX_MAPS) break;
            if (mp == null || !mp.isObject()) continue;
            String id = JsonSanitizer.str(mp, "id");
            if (id.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", JsonSanitizer.str(mp, "name").isEmpty() ? id : JsonSanitizer.str(mp, "name"));
            m.put("timeLimitSec", JsonSanitizer.clampInt(mp.get("timeLimitSec"), 30, 3600, 300));
            m.put("riskLimit", JsonSanitizer.clampInt(mp.get("riskLimit"), 1, 999, 20));
            m.put("valueMult", JsonSanitizer.clampDbl(mp.get("valueMult"), 0.01, 100, 0.18));
            m.put("tierBoost", JsonSanitizer.clampDbl(mp.get("tierBoost"), 0, 10, 0));
            m.put("entry", sanitizeEntry(mp.get("entry")));
            List<Map<String, Object>> ctns = new ArrayList<>();
            JsonNode cn = mp.get("containers");
            if (cn != null && cn.isArray()) {
                for (JsonNode c : cn) {
                    if (c == null || !c.isObject()) continue;
                    String cid = JsonSanitizer.str(c, "containerId");
                    if (cid.isEmpty()) continue;
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("containerId", cid);
                    cm.put("count", JsonSanitizer.clampInt(c.get("count"), 0, 99, 1));
                    ctns.add(cm);
                }
            }
            m.put("containers", ctns);
            m.put("extractPoints", JsonSanitizer.clampInt(mp.get("extractPoints"), 1, 9, 2));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> sanitizeEntry(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coins", 0);
        out.put("items", new ArrayList<>());
        out.put("minExtracts", 0);
        out.put("groups", new ArrayList<>());
        if (node == null || !node.isObject()) return out;
        out.put("coins", JsonSanitizer.clampInt(node.get("coins"), 0, 9_999_999, 0));
        out.put("minExtracts", JsonSanitizer.clampInt(node.get("minExtracts"), 0, 9999, 0));
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode in = node.get("items");
        if (in != null && in.isArray()) {
            for (JsonNode it : in) {
                if (it == null || !it.isObject()) continue;
                String itemId = JsonSanitizer.str(it, "itemId");
                if (itemId.isEmpty()) continue;
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("itemId", itemId);
                im.put("qty", JsonSanitizer.clampInt(it.get("qty"), 1, 99, 1));
                items.add(im);
            }
        }
        out.put("items", items);
        List<String> groups = new ArrayList<>();
        JsonNode gn = node.get("groups");
        if (gn != null && gn.isArray()) {
            for (JsonNode g : gn) if (g != null && g.isTextual() && !g.asText().trim().isEmpty()) groups.add(g.asText().trim());
        }
        out.put("groups", groups);
        return out;
    }

    /** 全局参数净化：缺字段用内置默认补齐，保证 C 端无脑消费不会拿到 null */
    private static Map<String, Object> sanitizeBalance(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return baseBalance();
        out.put("recycleRate", JsonSanitizer.clampDbl(node.get("recycleRate"), 0, 1, 0.6));
        out.put("extractRate", JsonSanitizer.clampDbl(node.get("extractRate"), 0, 1, 0.55));
        out.put("backpackCols", JsonSanitizer.clampInt(node.get("backpackCols"), 1, 8, 5));
        out.put("backpackRows", JsonSanitizer.clampInt(node.get("backpackRows"), 1, 8, 3));
        out.put("initialCoins", JsonSanitizer.clampInt(node.get("initialCoins"), 0, 9_999_999, 500));
        out.put("rescueCoins", JsonSanitizer.clampInt(node.get("rescueCoins"), 0, 9_999_999, 200));
        out.put("rescueCooldownSec", JsonSanitizer.clampInt(node.get("rescueCooldownSec"), 0, 30 * 86400, 86400));
        out.put("extractHoldMs", JsonSanitizer.clampInt(node.get("extractHoldMs"), 500, 60_000, 5000));
        out.put("riskPerSlot", JsonSanitizer.clampInt(node.get("riskPerSlot"), 0, 10, 1));
        out.put("evWarnRatio", JsonSanitizer.clampDbl(node.get("evWarnRatio"), 1, 100, 3.5));
        out.put("evRejectRatio", JsonSanitizer.clampDbl(node.get("evRejectRatio"), 1, 100, 10.0));
        return out;
    }

    /** 库中有 balance map 时用库值覆盖内置（缺字段保留内置），再走净化口径 */
    private static Map<String, Object> mergeBalance(Map<String, Object> lib) {
        Map<String, Object> merged = new LinkedHashMap<>(baseBalance());
        for (String k : merged.keySet()) {
            if (lib.containsKey(k) && lib.get(k) != null) merged.put(k, lib.get(k));
        }
        return sanitizeBalance(JsonUtil.MAPPER.valueToTree(merged));
    }
}
