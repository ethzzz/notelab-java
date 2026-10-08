package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爬塔 spire 净化规则的边界测试（审查 §3.4）。
 *
 * <p>为什么测这些方法：它们是全部业务里**唯一**「纯函数」的部分 —— 输入 {@code JsonNode}、
 * 输出 Map/List、无 IO、无 Spring 依赖，因此不需要上下文就能覆盖。此前它们是
 * {@code private static}，够不着（见审查 §2.2「静态状态无法 mock，不是不打测试是打不了」），
 * 现已放开为包级可见。
 *
 * <p>覆盖口径：**只打边界** —— 类型不符 / 缺字段 / 上下界夹紧 / 上限截断 / 空白与超长丢弃。
 * 不断言业务语意（那是 C 端引擎的事）。
 */
class SpireContentControllerTest {

    private static JsonNode json(String s) throws Exception {
        return JsonUtil.parse(s);
    }

    @Nested
    @DisplayName("sanitizeCharAccess — {用户组码: [角色 id]}")
    class CharAccess {

        @Test
        @DisplayName("null / 数组 / 字符串 → 空表")
        void nonObjectYieldsEmpty() throws Exception {
            assertTrue(SpireContentController.sanitizeCharAccess(null).isEmpty());
            assertTrue(SpireContentController.sanitizeCharAccess(json("[]")).isEmpty());
            assertTrue(SpireContentController.sanitizeCharAccess(json("\"x\"")).isEmpty());
        }

        @Test
        @DisplayName("键与值都 trim，元素去重且丢弃空串与非文本")
        void trimsAndDedups() throws Exception {
            Map<String, List<String>> r = SpireContentController.sanitizeCharAccess(
                    json("{\" vip \": [\" blade \", \"blade\", \"\", 7, null, \"mage\"]}"));
            assertEquals(List.of("blade", "mage"), r.get("vip"));
        }

        @Test
        @DisplayName("空白键被丢弃；值不是数组时该键对应空表（而非缺键）")
        void blankKeyDropped_valueNotArrayBecomesEmptyList() throws Exception {
            Map<String, List<String>> r = SpireContentController.sanitizeCharAccess(
                    json("{\"   \": [\"a\"], \"k\": \"notArray\"}"));
            assertEquals(1, r.size());
            assertEquals(List.of(), r.get("k"));
        }
    }

    @Nested
    @DisplayName("sanitizeAssets — {槽位: 当前使用的素材路径}")
    class Assets {

        @Test
        @DisplayName("空值 / 非文本 / 超长值一律**丢弃而不是存空串**")
        void dropsInvalidInsteadOfStoringBlank() throws Exception {
            String huge = "x".repeat(SpireContentController.MAX_ASSET_PATH + 1);
            Map<String, String> r = SpireContentController.sanitizeAssets(json(
                    "{\"ok\":\"  /games/a.png  \",\"empty\":\"  \",\"num\":42,\"huge\":\"" + huge + "\"}"));
            assertEquals("/games/a.png", r.get("ok"));
            assertFalse(r.containsKey("empty"));
            assertFalse(r.containsKey("num"));
            assertFalse(r.containsKey("huge"));
        }

        @Test
        @DisplayName("键超长（>120）丢弃；边界值 120 保留")
        void dropsOverlongKey() throws Exception {
            String ok120 = "k".repeat(120);
            String bad121 = "k".repeat(121);
            Map<String, String> r = SpireContentController.sanitizeAssets(
                    json("{\"" + ok120 + "\":\"/a.png\", \"" + bad121 + "\":\"/b.png\"}"));
            assertTrue(r.containsKey(ok120));
            assertFalse(r.containsKey(bad121));
        }
    }

    @Nested
    @DisplayName("sanitizeAssetPool — {槽位: [候选素材...]}")
    class AssetPool {

        @Test
        @DisplayName("单槽位条目数被 MAX_POOL_PER_SLOT 截断")
        void capsPerSlot() throws Exception {
            int n = SpireContentController.MAX_POOL_PER_SLOT + 10;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append("\"/p").append(i).append(".png\"");
            }
            sb.append(']');
            Map<String, List<String>> r = SpireContentController.sanitizeAssetPool(json("{\"s\":" + sb + "}"));
            assertEquals(SpireContentController.MAX_POOL_PER_SLOT, r.get("s").size());
        }

        @Test
        @DisplayName("去重保序；全被过滤掉的槽位不产生键")
        void dedupsKeepsOrder_dropsEmptySlot() throws Exception {
            Map<String, List<String>> r = SpireContentController.sanitizeAssetPool(
                    json("{\"a\":[\"/x.png\",\"/x.png\",\"/y.png\",\"\"],\"b\":[42],\"c\":\"notArray\"}"));
            assertEquals(List.of("/x.png", "/y.png"), r.get("a"));
            assertFalse(r.containsKey("b"));
            assertFalse(r.containsKey("c"));
        }
    }

    @Nested
    @DisplayName("sanitizeAct — 单幕（形状校验，不合法整幕作废）")
    class Act {

        @Test
        @DisplayName("null / 非对象 / nodes 缺失 / nodes 空 → null")
        void requiresNodes() throws Exception {
            assertNull(SpireContentController.sanitizeAct(null));
            assertNull(SpireContentController.sanitizeAct(json("[]")));
            assertNull(SpireContentController.sanitizeAct(json("{}")));
            assertNull(SpireContentController.sanitizeAct(json("{\"nodes\":[]}")));
        }

        @Test
        @DisplayName("节点缺 id / row / col / type / next 任一 → 整幕 null；id 为空串同样 null")
        void nodeShapeIsStrict() throws Exception {
            String good = "{\"nodes\":[{\"id\":\"n1\",\"row\":1,\"col\":2,\"type\":\"enemy\",\"next\":[]}]}";
            assertNotNull(SpireContentController.sanitizeAct(json(good)));

            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"row\":1,\"col\":2,\"type\":\"enemy\",\"next\":[]}]}")));        // 缺 id
            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"n1\",\"col\":2,\"type\":\"enemy\",\"next\":[]}]}")));     // 缺 row
            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"n1\",\"row\":1,\"type\":\"enemy\",\"next\":[]}]}")));     // 缺 col
            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"n1\",\"row\":1,\"col\":2,\"next\":[]}]}")));              // 缺 type
            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"n1\",\"row\":1,\"col\":2,\"type\":\"enemy\"}]}")));       // 缺 next
            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"\",\"row\":1,\"col\":2,\"type\":\"e\",\"next\":[]}]}")));  // id 空串
        }

        /**
         * ⚠️ 实测差异（写测试时发现，非本次改动引入）：这里的 id 判据是
         * {@code id.asText().isEmpty()}，**不 trim** —— 所以**纯空白 id（"  "）是被接受的**，
         * 会连同空白一起存进库。
         *
         * <p>而 {@code sanitizeEnemies} / {@code sanitizeMaps} 的 id 走 {@code JsonSanitizer.str}
         * （trim 后判断）→ 空白会被丢弃。**同一类字段两种口径**，与审查 §1.2
         * 「同名判据不同」属同一类问题。
         *
         * <p>如实固化现状（而不是断言理想行为），免得后来者以为这里有 trim；
         * 口径统一留给后续（不在「只放开可见性」的本轮范围内）。
         */
        @Test
        @DisplayName("⚠️ 已固化差异：纯空白 id 当前被接受（与 sanitizeEnemies 的 trim 口径不一致）")
        void blankIdIsCurrentlyAccepted() throws Exception {
            assertNotNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"  \",\"row\":1,\"col\":2,\"type\":\"e\",\"next\":[]}]}")));
        }

        @Test
        @DisplayName("row 是字符串（不是数字）→ 整幕 null")
        void rowMustBeNumber() throws Exception {
            assertNull(SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"n1\",\"row\":\"1\",\"col\":2,\"type\":\"e\",\"next\":[]}]}")));
        }

        @Test
        @DisplayName("act / layers 缺省：act=1、layers=节点数")
        void defaultsActAndLayers() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeAct(json(
                    "{\"nodes\":[{\"id\":\"n1\",\"row\":1,\"col\":2,\"type\":\"e\",\"next\":[]}]}"));
            assertEquals(1, r.get("act"));
            assertEquals(1, r.get("layers"));
        }
    }

    @Nested
    @DisplayName("sanitizeMaps — 地图方案 {defaultId, packs[]}")
    class Maps {

        @Test
        @DisplayName("非对象 → 空骨架（defaultId 空串 + 空 packs），而不是 null")
        void nonObjectYieldsEmptySkeleton() {
            Map<String, Object> r = SpireContentController.sanitizeMaps(null);
            assertEquals("", r.get("defaultId"));
            assertEquals(List.of(), r.get("packs"));
        }

        @Test
        @DisplayName("没有可用幕的方案被整体丢弃（acts 空 / acts 里全被 sanizeAct 否掉）")
        void dropsPacksWithoutUsableActs() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeMaps(json(
                    "{\"defaultId\":\"p1\",\"packs\":["
                            + "{\"id\":\"p1\",\"acts\":[]},"
                            + "{\"id\":\"p2\",\"acts\":[{\"nodes\":[]}]}]}"));
            assertEquals(0, ((List<?>) r.get("packs")).size());
            assertEquals("p1", r.get("defaultId"));
        }

        @Test
        @DisplayName("方案数量被 MAX_PACKS 截断")
        void capsPackCount() throws Exception {
            StringBuilder sb = new StringBuilder("{\"packs\":[");
            for (int i = 0; i < SpireContentController.MAX_PACKS + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"p").append(i)
                        .append("\",\"acts\":[{\"nodes\":[{\"id\":\"n\",\"row\":0,\"col\":0,\"type\":\"e\",\"next\":[]}]}]}");
            }
            sb.append("]}");
            assertEquals(SpireContentController.MAX_PACKS,
                    ((List<?>) SpireContentController.sanitizeMaps(json(sb.toString())).get("packs")).size());
        }
    }

    @Nested
    @DisplayName("sanitizeBalance — 平衡/难度")
    class Balance {

        @Test
        @DisplayName("非对象回落内置默认（不是空表）")
        void nonObjectFallsBackToBuiltin() {
            assertEquals(SpireContentController.baseBalance(), SpireContentController.sanitizeBalance(null));
        }

        @Test
        @DisplayName("越界值被夹紧而不是回落默认")
        void clampsInsteadOfFallingBack() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeBalance(
                    json("{\"totalActs\":99,\"mapRows\":0,\"actScaleStep\":99.5}"));
            assertEquals(SpireContentController.MAX_ACTS, r.get("totalActs"));  // 99 → 8
            assertEquals(1, r.get("mapRows"));                                  // 0 → 1
            assertEquals(5.0, r.get("actScaleStep"));                           // 99.5 → 5.0
        }

        @Test
        @DisplayName("actBossIds 不足 totalActs 时用**末位元素**循环补齐（不是交替），数量恰为 totalActs")
        void padsBossIdsFromLastElement() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeBalance(
                    json("{\"totalActs\":5,\"actBossIds\":[\"a\",\"b\"]}"));
            assertEquals(List.of("a", "b", "b", "b", "b"), r.get("actBossIds"));
        }

        @Test
        @DisplayName("actBossIds 非数组 → 回内置默认；数量同样补齐到 totalActs")
        void bossIdsFallbackWhenNotArray() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeBalance(
                    json("{\"totalActs\":5,\"actBossIds\":\"nope\"}"));
            assertEquals(5, ((List<?>) r.get("actBossIds")).size());
        }

        @Test
        @DisplayName("actBossIds 里的非文本 / 空白元素被丢弃")
        void bossIdsDropsNonTextual() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeBalance(
                    json("{\"totalActs\":2,\"actBossIds\":[\"a\", 7, \"  \"]}"));
            assertEquals(List.of("a", "a"), r.get("actBossIds"));
        }
    }

    @Nested
    @DisplayName("sanitizeMapRules — 地图生成规则")
    class MapRules {

        @Test
        @DisplayName("非对象回落内置默认")
        void nonObjectFallsBackToBuiltin() {
            assertEquals(SpireContentController.baseMapRules(), SpireContentController.sanitizeMapRules(null));
        }

        @Test
        @DisplayName("layers/acts/earlySafeLayers 各自夹到 [lo,hi]")
        void clampsToRange() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeMapRules(
                    json("{\"layers\":999,\"acts\":0,\"earlySafeLayers\":99}"));
            assertEquals(40, r.get("layers"));            // [4,40]
            assertEquals(1, r.get("acts"));               // [1,8]
            assertEquals(8, r.get("earlySafeLayers"));    // [0,8]
        }

        @Test
        @DisplayName("pathCount 只取数组前两项，每项独立回默认 4 / 6")
        void pathCountTakesFirstTwo() throws Exception {
            assertEquals(List.of(4, 6), SpireContentController.sanitizeMapRules(json("{}")).get("pathCount"));
            assertEquals(List.of(7, 6), SpireContentController.sanitizeMapRules(json("{\"pathCount\":[7]}")).get("pathCount"));
            assertEquals(List.of(7, 2), SpireContentController.sanitizeMapRules(json("{\"pathCount\":[7,2,9]}")).get("pathCount"));
        }

        @Test
        @DisplayName("嵌套的 weights / minLayer / revealPool 缺字段各自回默认，且键齐全")
        void nestedMapsAlwaysComplete() throws Exception {
            Map<String, Object> r = SpireContentController.sanitizeMapRules(json("{}"));
            for (String k : List.of("weights", "minLayer", "revealPool")) {
                Map<?, ?> m = (Map<?, ?>) r.get(k);
                assertNotNull(m, k + " 应当存在");
                assertFalse(m.isEmpty(), k + " 不应为空");
            }
        }

        @Test
        @DisplayName("weights 越界夹到 [0,999]")
        void weightsClamped() throws Exception {
            Map<?, ?> w = (Map<?, ?>) SpireContentController.sanitizeMapRules(
                    json("{\"weights\":{\"enemy\":5000,\"elite\":-3}}")).get("weights");
            assertEquals(999, w.get("enemy"));
            assertEquals(0, w.get("elite"));
        }
    }

    @Nested
    @DisplayName("sanitizeEnemies — 敌人")
    class Enemies {

        @Test
        @DisplayName("null / 非数组 → 空表")
        void nonArrayYieldsEmpty() throws Exception {
            assertTrue(SpireContentController.sanitizeEnemies(null).isEmpty());
            assertTrue(SpireContentController.sanitizeEnemies(json("{}")).isEmpty());
        }

        @Test
        @DisplayName("缺 id 或 name 的条目丢弃")
        void requiresIdAndName() throws Exception {
            assertTrue(SpireContentController.sanitizeEnemies(json(
                    "[{\"id\":\"e1\",\"moves\":[{\"name\":\"x\",\"kind\":\"atk\"}]}]")).isEmpty());
            assertTrue(SpireContentController.sanitizeEnemies(json(
                    "[{\"name\":\"n\",\"moves\":[{\"name\":\"x\",\"kind\":\"atk\"}]}]")).isEmpty());
        }

        @Test
        @DisplayName("kind 不在白名单的 move 丢弃；若招式为空则整条敌人不保留")
        void moveKindIsWhitelisted() throws Exception {
            assertEquals(0, SpireContentController.sanitizeEnemies(json(
                    "[{\"id\":\"e1\",\"name\":\"n\",\"moves\":[{\"name\":\"x\",\"kind\":\"bogus\"}]}]")).size());

            List<Map<String, Object>> ok = SpireContentController.sanitizeEnemies(json(
                    "[{\"id\":\"e1\",\"name\":\"n\",\"moves\":[{\"name\":\"x\",\"kind\":\"atk\"},{\"name\":\"y\",\"kind\":\"bogus\"}]}]"));
            assertEquals(1, ok.size());
            assertEquals(1, ((List<?>) ok.get(0).get("moves")).size());
        }

        @Test
        @DisplayName("hp 夹到 [1,999]；elite/boss 只在显式 true 时出现")
        void hpClamped_flagsOnlyWhenTrue() throws Exception {
            List<Map<String, Object>> r = SpireContentController.sanitizeEnemies(json(
                    "[{\"id\":\"e1\",\"name\":\"n\",\"hp\":99999,\"elite\":false,\"boss\":true,"
                            + "\"moves\":[{\"name\":\"x\",\"kind\":\"atk\"}]}]"));
            assertEquals(999, r.get(0).get("hp"));
            assertFalse(r.get(0).containsKey("elite"));   // false → 不出现
            assertEquals(true, r.get(0).get("boss"));
        }

        @Test
        @DisplayName("move 的 amt 夹 [0,99]、hits 夹 [1,9]；debuffKind 只接受 weak/vuln")
        void moveFieldsClamped_debuffKindWhitelist() throws Exception {
            List<Map<String, Object>> r = SpireContentController.sanitizeEnemies(json(
                    "[{\"id\":\"e1\",\"name\":\"n\",\"moves\":["
                            + "{\"name\":\"x\",\"kind\":\"debuff\",\"amt\":-5,\"hits\":99,\"debuffKind\":\"weak\"},"
                            + "{\"name\":\"y\",\"kind\":\"debuff\",\"debuffKind\":\"poison\"}]}]"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mv = (List<Map<String, Object>>) r.get(0).get("moves");
            assertEquals(0, mv.get(0).get("amt"));
            assertEquals(9, mv.get(0).get("hits"));
            assertEquals("weak", mv.get(0).get("debuffKind"));
            assertFalse(mv.get(1).containsKey("debuffKind"));   // poison 不在白名单 → 不出现
        }
    }
}
