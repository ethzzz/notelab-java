package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摸金 loot 净化规则的边界测试（审查 §3.4）。
 *
 * <p>只打边界：类型不符 / 缺字段 / 上下界夹紧 / 上限截断 / 白名单回落 / 空值与重复丢弃。
 * 不断言数值平衡（那由 B 端 EV 估算与 C 端引擎负责）。
 */
class LootContentControllerTest {

    private static JsonNode json(String s) throws Exception {
        return JsonUtil.parse(s);
    }

    /** 稀有度顺序由「档位数组」决定，这里给三档供各方法使用 */
    private static final List<String> ORDER = List.of("common", "uncommon", "rare");

    /** 覆盖 order 全档的权重对象（sanitizeRarityWeights 要求每档都在且 ≥0） */
    private static final String FULL_WEIGHTS = "{\"common\":10,\"uncommon\":5,\"rare\":1}";

    // ==================== 稀有度 ====================

    @Nested
    @DisplayName("sanitizeRarities — 稀有度档位")
    class Rarities {

        @Test
        @DisplayName("非数组 / 空数组都回落内置档位（不是空表）")
        void fallsBackToBuiltin() throws Exception {
            assertEquals(LootContentController.BASE_RARITIES, LootContentController.sanitizeRarities(null));
            assertEquals(LootContentController.BASE_RARITIES, LootContentController.sanitizeRarities(json("{}")));
            assertEquals(LootContentController.BASE_RARITIES, LootContentController.sanitizeRarities(json("[]")));
        }

        @Test
        @DisplayName("key 为空 / 重复 → 丢弃该档（重复只留第一条）")
        void dropsBlankAndDuplicateKeys() throws Exception {
            List<Map<String, Object>> r = LootContentController.sanitizeRarities(json(
                    "[{\"key\":\"\"},{\"key\":\"a\",\"label\":\"第一\"},{\"key\":\"a\",\"label\":\"第二\"},"
                            + "{\"key\":\"  \"},{\"key\":\"b\"}]"));
            assertEquals(2, r.size());
            assertEquals("第一", r.get(0).get("label"));
        }

        @Test
        @DisplayName("label 为空回落 key；color 不在色板回落 slate；合法 color 原样保留")
        void labelAndColorFallback() throws Exception {
            String legal = LootContentController.PALETTE_KEYS.iterator().next();
            List<Map<String, Object>> r = LootContentController.sanitizeRarities(json(
                    "[{\"key\":\"a\"},{\"key\":\"b\",\"color\":\"NOT_A_COLOR\"},{\"key\":\"c\",\"color\":\"" + legal + "\"}]"));
            assertEquals("a", r.get(0).get("label"));       // label 缺 → 用 key
            assertEquals("slate", r.get(1).get("color"));   // 非法色 → slate
            assertEquals(legal, r.get(2).get("color"));     // 合法色保留
        }

        @Test
        @DisplayName("color 大小写不敏感（先 toLowerCase 再查色板）")
        void colorIsCaseInsensitive() throws Exception {
            String legal = LootContentController.PALETTE_KEYS.iterator().next().toUpperCase();
            assertEquals(LootContentController.PALETTE_KEYS.iterator().next(),
                    LootContentController.sanitizeRarities(json("[{\"key\":\"a\",\"color\":\"" + legal + "\"}]"))
                            .get(0).get("color"));
        }

        @Test
        @DisplayName("unitValue 夹到 [0, 9999999]，非数字回 0")
        void unitValueClamped() throws Exception {
            List<Map<String, Object>> r = LootContentController.sanitizeRarities(json(
                    "[{\"key\":\"a\",\"unitValue\":-5},{\"key\":\"b\",\"unitValue\":99999999},{\"key\":\"c\",\"unitValue\":\"x\"}]"));
            assertEquals(0, r.get(0).get("unitValue"));
            assertEquals(9_999_999, r.get(1).get("unitValue"));
            assertEquals(0, r.get(2).get("unitValue"));
        }

        @Test
        @DisplayName("档位数被 MAX_RARITIES 截断")
        void capsCount() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < LootContentController.MAX_RARITIES + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"key\":\"k").append(i).append("\"}");
            }
            sb.append(']');
            assertEquals(LootContentController.MAX_RARITIES,
                    LootContentController.sanitizeRarities(json(sb.toString())).size());
        }
    }

    // ==================== 物品 ====================

    @Nested
    @DisplayName("sanitizeItems — 物品")
    class Items {

        @Test
        @DisplayName("非数组 → 空表")
        void nonArrayYieldsEmpty() throws Exception {
            assertTrue(LootContentController.sanitizeItems(null, ORDER).isEmpty());
            assertTrue(LootContentController.sanitizeItems(json("{}"), ORDER).isEmpty());
        }

        @Test
        @DisplayName("缺 id / name、id 重复、rarity 不在 order → 丢弃")
        void dropsInvalidRows() throws Exception {
            assertTrue(LootContentController.sanitizeItems(json("[{\"name\":\"n\",\"rarity\":\"common\"}]"), ORDER).isEmpty());
            assertTrue(LootContentController.sanitizeItems(json("[{\"id\":\"i\",\"rarity\":\"common\"}]"), ORDER).isEmpty());
            // rarity 不在当前 order（档位被删掉）→ 整条丢弃
            assertTrue(LootContentController.sanitizeItems(json("[{\"id\":\"i\",\"name\":\"n\",\"rarity\":\"gone\"}]"), ORDER).isEmpty());

            List<Map<String, Object>> r = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"i\",\"name\":\"第一\",\"rarity\":\"common\"},{\"id\":\"i\",\"name\":\"第二\",\"rarity\":\"common\"}]"), ORDER);
            assertEquals(1, r.size());
            assertEquals("第一", r.get(0).get("name"));
        }

        @Test
        @DisplayName("baseValue 夹 [1,9999999]（默认 50）；stack 夹 [1,99]（默认 1）")
        void numericClamps() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"i\",\"name\":\"n\",\"rarity\":\"common\",\"baseValue\":0,\"stack\":999}]"), ORDER).get(0);
            assertEquals(1, r.get("baseValue"));
            assertEquals(99, r.get("stack"));

            Map<String, Object> d = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"i\",\"name\":\"n\",\"rarity\":\"common\"}]"), ORDER).get(0);
            assertEquals(50, d.get("baseValue"));
            assertEquals(1, d.get("stack"));
        }

        @Test
        @DisplayName("recycleValue：非数字 → null（不是 0）；数字则夹到 [0,9999999]")
        void recycleValueKeepsNull() throws Exception {
            List<Map<String, Object>> r = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"a\",\"name\":\"n\",\"rarity\":\"common\",\"recycleValue\":\"x\"},"
                            + "{\"id\":\"b\",\"name\":\"n\",\"rarity\":\"common\",\"recycleValue\":-9}]"), ORDER);
            assertNull(r.get(0).get("recycleValue"));
            assertEquals(0, r.get(1).get("recycleValue"));
        }

        @Test
        @DisplayName("shape 不在白名单一律回落 1x1（绝不留非法值）")
        void shapeFallsBackTo1x1() throws Exception {
            List<Map<String, Object>> r = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"a\",\"name\":\"n\",\"rarity\":\"common\",\"shape\":\"BOGUS\"},"
                            + "{\"id\":\"b\",\"name\":\"n\",\"rarity\":\"common\",\"shape\":\"2x2\"}]"), ORDER);
            assertEquals("1x1", r.get(0).get("shape"));
            assertEquals("2x2", r.get(1).get("shape"));
        }

        @Test
        @DisplayName("tags 非数组 → 空表；元素 trim 后丢空串与非文本")
        void tagsSanitized() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"i\",\"name\":\"n\",\"rarity\":\"common\",\"tags\":[\" a \",\"\",7,null,\"b\"]}]"), ORDER).get(0);
            assertEquals(List.of("a", "b"), r.get("tags"));

            Map<String, Object> r2 = LootContentController.sanitizeItems(json(
                    "[{\"id\":\"i\",\"name\":\"n\",\"rarity\":\"common\",\"tags\":\"nope\"}]"), ORDER).get(0);
            assertEquals(List.of(), r2.get("tags"));
        }

        @Test
        @DisplayName("数量被 MAX_ITEMS 截断")
        void capsCount() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < LootContentController.MAX_ITEMS + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"i").append(i).append("\",\"name\":\"n\",\"rarity\":\"common\"}");
            }
            sb.append(']');
            assertEquals(LootContentController.MAX_ITEMS,
                    LootContentController.sanitizeItems(json(sb.toString()), ORDER).size());
        }
    }

    // ==================== 旧 slots 迁移 ====================

    @Nested
    @DisplayName("gridFromSlots — 旧配置迁移")
    class GridMigration {

        @Test
        @DisplayName("slots → {colsMin, colsMax, rowsMin, rowsMax} 的分档")
        void mapsSlotsToGrid() {
            assertArrayEquals(new int[]{1, 1, 1, 1}, LootContentController.gridFromSlots(0));
            assertArrayEquals(new int[]{1, 1, 1, 1}, LootContentController.gridFromSlots(1));
            assertArrayEquals(new int[]{2, 2, 1, 1}, LootContentController.gridFromSlots(2));
            assertArrayEquals(new int[]{2, 2, 2, 2}, LootContentController.gridFromSlots(3));
            assertArrayEquals(new int[]{2, 2, 2, 2}, LootContentController.gridFromSlots(4));
            assertArrayEquals(new int[]{3, 3, 2, 2}, LootContentController.gridFromSlots(5));
            assertArrayEquals(new int[]{3, 3, 2, 2}, LootContentController.gridFromSlots(64));
        }
    }

    // ==================== 容器 ====================

    @Nested
    @DisplayName("sanitizeContainers — 容器")
    class Containers {

        private String ctn(String extra) {
            return "[{\"id\":\"c1\",\"name\":\"箱\",\"tableId\":\"t1\",\"rarityWeights\":" + FULL_WEIGHTS + extra + "}]";
        }

        @Test
        @DisplayName("缺 id / name / tableId → 丢弃")
        void requiresIdentity() throws Exception {
            assertTrue(LootContentController.sanitizeContainers(json(
                    "[{\"name\":\"n\",\"tableId\":\"t\",\"rarityWeights\":" + FULL_WEIGHTS + "}]"), ORDER).isEmpty());
            assertTrue(LootContentController.sanitizeContainers(json(
                    "[{\"id\":\"c\",\"tableId\":\"t\",\"rarityWeights\":" + FULL_WEIGHTS + "}]"), ORDER).isEmpty());
            assertTrue(LootContentController.sanitizeContainers(json(
                    "[{\"id\":\"c\",\"name\":\"n\",\"rarityWeights\":" + FULL_WEIGHTS + "}]"), ORDER).isEmpty());
        }

        @Test
        @DisplayName("rarityWeights 缺档 / 全 0 → 整条容器丢弃")
        void dropsWhenWeightsInvalid() throws Exception {
            assertTrue(LootContentController.sanitizeContainers(json(
                    "[{\"id\":\"c\",\"name\":\"n\",\"tableId\":\"t\",\"rarityWeights\":{\"common\":1}}]"), ORDER).isEmpty());
            assertTrue(LootContentController.sanitizeContainers(json(
                    "[{\"id\":\"c\",\"name\":\"n\",\"tableId\":\"t\","
                            + "\"rarityWeights\":{\"common\":0,\"uncommon\":0,\"rare\":0}}]"), ORDER).isEmpty());
        }

        @Test
        @DisplayName("旧 slots 配置（无网格字段）被迁移成网格，而不是丢弃")
        void migratesLegacySlots() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeContainers(json(ctn(",\"slots\":2")), ORDER).get(0);
            assertEquals(2, r.get("colsMin"));
            assertEquals(2, r.get("colsMax"));
            assertEquals(1, r.get("rowsMin"));
            assertEquals(1, r.get("rowsMax"));
        }

        @Test
        @DisplayName("没有任何网格线索时回落 1×1")
        void defaultsToSingleCell() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeContainers(json(ctn("")), ORDER).get(0);
            assertEquals(1, r.get("colsMin"));
            assertEquals(1, r.get("rowsMax"));
        }

        @Test
        @DisplayName("colsMax 小于 colsMin 时被抬到 colsMin；fillRate 夹 [0,1]")
        void clampRelations() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeContainers(json(
                    "[{\"id\":\"c\",\"name\":\"n\",\"tableId\":\"t\",\"rarityWeights\":" + FULL_WEIGHTS
                            + ",\"colsMin\":6,\"colsMax\":2,\"fillRate\":9}]"), ORDER).get(0);
            assertEquals(6, r.get("colsMin"));
            assertEquals(6, r.get("colsMax"));   // 2 < colsMin → 被抬到 colsMin
            assertEquals(1.0, r.get("fillRate"));
        }

        @Test
        @DisplayName("pity.minRarity 不在 order → pity 为 null（不产生半个 pity）")
        void pityDroppedWhenRarityUnknown() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeContainers(json(
                    ctn(",\"pity\":{\"minRarity\":\"gone\",\"afterRuns\":3}")), ORDER).get(0);
            assertNull(r.get("pity"));

            Map<String, Object> ok = LootContentController.sanitizeContainers(json(
                    ctn(",\"pity\":{\"minRarity\":\"rare\",\"afterRuns\":3}")), ORDER).get(0);
            Map<?, ?> pity = (Map<?, ?>) ok.get("pity");
            assertEquals("rare", pity.get("minRarity"));
            assertEquals(3, pity.get("afterRuns"));
        }

        @Test
        @DisplayName("数量被 MAX_CONTAINERS 截断")
        void capsCount() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < LootContentController.MAX_CONTAINERS + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"c").append(i).append("\",\"name\":\"n\",\"tableId\":\"t\",\"rarityWeights\":")
                        .append(FULL_WEIGHTS).append('}');
            }
            sb.append(']');
            assertEquals(LootContentController.MAX_CONTAINERS,
                    LootContentController.sanitizeContainers(json(sb.toString()), ORDER).size());
        }
    }

    // ==================== 权重 ====================

    @Nested
    @DisplayName("sanitizeRarityWeights — 各档权重")
    class RarityWeights {

        @Test
        @DisplayName("非对象 / 缺档 / 档位非数字 → null")
        void invalidYieldsNull() throws Exception {
            assertNull(LootContentController.sanitizeRarityWeights(null, ORDER));
            assertNull(LootContentController.sanitizeRarityWeights(json("[]"), ORDER));
            assertNull(LootContentController.sanitizeRarityWeights(json("{\"common\":1}"), ORDER));                    // 缺档
            assertNull(LootContentController.sanitizeRarityWeights(json("{\"common\":1,\"uncommon\":\"x\",\"rare\":1}"), ORDER));
        }

        @Test
        @DisplayName("总和为 0 → null（该容器/表无意义）")
        void zeroSumYieldsNull() throws Exception {
            assertNull(LootContentController.sanitizeRarityWeights(json("{\"common\":0,\"uncommon\":0,\"rare\":0}"), ORDER));
        }

        @Test
        @DisplayName("单档夹到 [0,999]；正常值原样保留且键序 = order")
        void clampsEachTier() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeRarityWeights(
                    json("{\"common\":-3,\"uncommon\":5000,\"rare\":2}"), ORDER);
            assertNotNull(r);
            assertEquals(List.of("common", "uncommon", "rare"), List.copyOf(r.keySet()));
            assertEquals(0.0, r.get("common"));
            assertEquals(999.0, r.get("uncommon"));
            assertEquals(2.0, r.get("rare"));
        }
    }

    // ==================== 掉落表 ====================

    @Nested
    @DisplayName("sanitizeTables — 掉落表")
    class Tables {

        @Test
        @DisplayName("非数组 → 空表；缺 id 或重复 id 丢弃")
        void requiresUniqueId() throws Exception {
            assertTrue(LootContentController.sanitizeTables(null).isEmpty());
            assertTrue(LootContentController.sanitizeTables(json("{}")).isEmpty());
            List<Map<String, Object>> r = LootContentController.sanitizeTables(json(
                    "[{\"id\":\"t\"},{\"id\":\"t\",\"name\":\"第二\"},{\"name\":\"无id\"}]"));
            assertEquals(1, r.size());
            assertEquals("t", r.get(0).get("id"));
        }

        @Test
        @DisplayName("name 为空回落 id；pool 元素缺 itemId 跳过、weight 夹 [0,999]")
        void poolSanitized() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeTables(json(
                    "[{\"id\":\"t\",\"pool\":[{\"itemId\":\"\",\"weight\":5},{\"itemId\":\"i1\",\"weight\":5000},"
                            + "{\"itemId\":\"i2\"},77]}]")).get(0);
            assertEquals("t", r.get("name"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pool = (List<Map<String, Object>>) r.get("pool");
            assertEquals(2, pool.size());                 // 空 itemId 与非对象被跳过
            assertEquals(999, pool.get(0).get("weight"));
            assertEquals(1, pool.get(1).get("weight"));   // 缺 weight → 默认 1
        }

        @Test
        @DisplayName("数量被 MAX_TABLES 截断；单表 pool 被 MAX_POOL_PER_TABLE 截断")
        void capsCounts() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < LootContentController.MAX_TABLES + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"t").append(i).append("\"}");
            }
            sb.append(']');
            assertEquals(LootContentController.MAX_TABLES,
                    LootContentController.sanitizeTables(json(sb.toString())).size());

            StringBuilder pool = new StringBuilder("[");
            for (int i = 0; i < LootContentController.MAX_POOL_PER_TABLE + 5; i++) {
                if (i > 0) pool.append(',');
                pool.append("{\"itemId\":\"i").append(i).append("\"}");
            }
            pool.append(']');
            Map<String, Object> one = LootContentController.sanitizeTables(
                    json("[{\"id\":\"t\",\"pool\":" + pool + "}]")).get(0);
            assertEquals(LootContentController.MAX_POOL_PER_TABLE, ((List<?>) one.get("pool")).size());
        }
    }

    // ==================== 地图 ====================

    @Nested
    @DisplayName("sanitizeMaps — 地图")
    class Maps {

        @Test
        @DisplayName("非数组 → 空表；缺 id 或重复 id 丢弃")
        void requiresUniqueId() throws Exception {
            assertTrue(LootContentController.sanitizeMaps(null).isEmpty());
            assertTrue(LootContentController.sanitizeMaps(json("{}")).isEmpty());
            assertEquals(1, LootContentController.sanitizeMaps(json(
                    "[{\"id\":\"m\"},{\"id\":\"m\"},{\"name\":\"无id\"}]")).size());
        }

        @Test
        @DisplayName("name 为空（含纯空白）回落 id")
        void nameFallsBackToId() throws Exception {
            assertEquals("m1", LootContentController.sanitizeMaps(
                    json("[{\"id\":\"m1\",\"name\":\"   \"}]")).get(0).get("name"));
        }

        @Test
        @DisplayName("数值各自夹紧：timeLimitSec[30,3600] riskLimit[1,999] extractPoints[1,9]")
        void clampsNumbers() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeMaps(json(
                    "[{\"id\":\"m\",\"timeLimitSec\":1,\"riskLimit\":9999,\"extractPoints\":99}]")).get(0);
            assertEquals(30, r.get("timeLimitSec"));
            assertEquals(999, r.get("riskLimit"));
            assertEquals(9, r.get("extractPoints"));
        }

        @Test
        @DisplayName("valueMult[0.01,100] / tierBoost[0,10] 越界夹紧，缺省各回默认")
        void clampsDoubles() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeMaps(json(
                    "[{\"id\":\"m\",\"valueMult\":0.0001,\"tierBoost\":999}]")).get(0);
            assertEquals(0.01, r.get("valueMult"));
            assertEquals(10.0, r.get("tierBoost"));
        }

        @Test
        @DisplayName("containers 里缺 containerId 的元素被跳过、count 夹 [0,99]；entry 总是有完整骨架")
        void nestedSanitized() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeMaps(json(
                    "[{\"id\":\"m\",\"containers\":[{\"containerId\":\"\"},{\"containerId\":\"c\",\"count\":999},42]}]")).get(0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cs = (List<Map<String, Object>>) r.get("containers");
            assertEquals(1, cs.size());
            assertEquals(99, cs.get(0).get("count"));
            Map<?, ?> entry = (Map<?, ?>) r.get("entry");
            for (String k : List.of("coins", "items", "minExtracts", "groups")) {
                assertNotNull(entry.get(k), "entry." + k + " 应当存在");
            }
        }

        @Test
        @DisplayName("数量被 MAX_MAPS 截断")
        void capsCount() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < LootContentController.MAX_MAPS + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"m").append(i).append("\"}");
            }
            sb.append(']');
            assertEquals(LootContentController.MAX_MAPS, LootContentController.sanitizeMaps(json(sb.toString())).size());
        }
    }

    // ==================== 入场奖励 ====================

    @Nested
    @DisplayName("sanitizeEntry — 入场奖励")
    class Entry {

        @Test
        @DisplayName("非对象 → 完整空骨架（4 个键都在，而不是 null 或空 map）")
        void nonObjectYieldsSkeleton() {
            Map<String, Object> r = LootContentController.sanitizeEntry(null);
            assertEquals(4, r.size());
            assertEquals(0, r.get("coins"));
            assertEquals(List.of(), r.get("items"));
            assertEquals(0, r.get("minExtracts"));
            assertEquals(List.of(), r.get("groups"));
        }

        @Test
        @DisplayName("coins / minExtracts 夹到 [0, 9999999] / [0, 9999]")
        void clampsNumbers() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeEntry(
                    json("{\"coins\":-1,\"minExtracts\":99999}"));
            assertEquals(0, r.get("coins"));
            assertEquals(9999, r.get("minExtracts"));
        }

        @Test
        @DisplayName("items 缺 itemId 跳过、qty 夹 [1,99]；groups 丢空串与非文本并 trim")
        void nestedSanitized() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeEntry(json(
                    "{\"items\":[{\"itemId\":\"\"},{\"itemId\":\"i\",\"qty\":999}],\"groups\":[\" g \",\"\",7,null]}"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
            assertEquals(1, items.size());
            assertEquals(99, items.get(0).get("qty"));
            assertEquals(List.of("g"), r.get("groups"));
        }
    }

    // ==================== 平衡 ====================

    @Nested
    @DisplayName("sanitizeBalance — 全局参数")
    class Balance {

        @Test
        @DisplayName("非对象回落内置默认")
        void nonObjectFallsBackToBuiltin() {
            assertEquals(LootContentController.baseBalance(), LootContentController.sanitizeBalance(null));
        }

        @Test
        @DisplayName("背包网格夹到 [1,8]；比例类夹到 [0,1]")
        void clamps() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeBalance(
                    json("{\"backpackCols\":99,\"backpackRows\":0,\"recycleRate\":5,\"extractRate\":-1}"));
            assertEquals(8, r.get("backpackCols"));
            assertEquals(1, r.get("backpackRows"));
            assertEquals(1.0, r.get("recycleRate"));
            assertEquals(0.0, r.get("extractRate"));
        }

        @Test
        @DisplayName("EV 阈值三端同值：默认 evWarnRatio=3.5 / evRejectRatio=10（缺字段时）")
        void evThresholdsHaveDefaults() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeBalance(json("{}"));
            assertEquals(3.5, r.get("evWarnRatio"));
            assertEquals(10.0, r.get("evRejectRatio"));
        }

        @Test
        @DisplayName("键集合完整（C 端不会拿到 null）")
        void alwaysComplete() throws Exception {
            Map<String, Object> r = LootContentController.sanitizeBalance(json("{}"));
            for (String k : List.of("recycleRate", "extractRate", "backpackCols", "backpackRows",
                    "initialCoins", "rescueCoins", "rescueCooldownSec", "extractHoldMs",
                    "riskPerSlot", "evWarnRatio", "evRejectRatio")) {
                assertNotNull(r.get(k), k + " 不应为 null");
            }
        }
    }
}
