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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开发记录 dev_notes 净化规则的边界测试（审查 §3.4）。
 *
 * <p>与 {@link SpireContentControllerTest} 同口径：只打边界（截断 / 白名单回落 / 空值丢弃 / 去重），
 * 不断言业务语意。
 */
class DevNoteControllerTest {

    private static JsonNode json(String s) throws Exception {
        return JsonUtil.parse(s);
    }

    @Nested
    @DisplayName("sanitizeProjects — 项目")
    class Projects {

        @Test
        @DisplayName("null / 非数组 / 数组里非对象元素 → 空表或跳过")
        void requiresArray() throws Exception {
            assertTrue(DevNoteController.sanitizeProjects(null).isEmpty());
            assertTrue(DevNoteController.sanitizeProjects(json("{}")).isEmpty());
            assertTrue(DevNoteController.sanitizeProjects(json("[1,\"x\",null]")).isEmpty());
        }

        @Test
        @DisplayName("code / name 任一为空 → 丢弃该条")
        void requiresCodeAndName() throws Exception {
            assertTrue(DevNoteController.sanitizeProjects(json("[{\"name\":\"n\"}]")).isEmpty());
            assertTrue(DevNoteController.sanitizeProjects(json("[{\"code\":\"c\"}]")).isEmpty());
            assertTrue(DevNoteController.sanitizeProjects(json("[{\"code\":\"  \",\"name\":\"n\"}]")).isEmpty());
        }

        @Test
        @DisplayName("code 重复 → 只保留第一条")
        void dedupsByCode() throws Exception {
            List<Map<String, Object>> r = DevNoteController.sanitizeProjects(json(
                    "[{\"code\":\"a\",\"name\":\"第一\"},{\"code\":\"a\",\"name\":\"第二\"}]"));
            assertEquals(1, r.size());
            assertEquals("第一", r.get(0).get("name"));
        }

        @Test
        @DisplayName("超长字段被截断：code≤64 / name≤100 / desc≤500")
        void truncatesOverlongFields() throws Exception {
            Map<String, Object> r = DevNoteController.sanitizeProjects(json(
                    "[{\"code\":\"" + "c".repeat(100) + "\",\"name\":\"" + "n".repeat(150) + "\","
                            + "\"desc\":\"" + "d".repeat(600) + "\"}]")).get(0);
            assertEquals(64, ((String) r.get("code")).length());
            assertEquals(100, ((String) r.get("name")).length());
            assertEquals(500, ((String) r.get("desc")).length());
        }

        @Test
        @DisplayName("数量被 MAX_PROJECTS 截断")
        void capsCount() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < DevNoteController.MAX_PROJECTS + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"code\":\"c").append(i).append("\",\"name\":\"n").append(i).append("\"}");
            }
            sb.append(']');
            assertEquals(DevNoteController.MAX_PROJECTS,
                    DevNoteController.sanitizeProjects(json(sb.toString())).size());
        }

        @Test
        @DisplayName("entries 非数组 → 空列表（而不是 null / 缺键）")
        void entriesNonArrayBecomesEmptyList() throws Exception {
            Map<String, Object> r = DevNoteController.sanitizeProjects(
                    json("[{\"code\":\"c\",\"name\":\"n\",\"entries\":\"nope\"}]")).get(0);
            assertEquals(List.of(), r.get("entries"));
        }
    }

    @Nested
    @DisplayName("sanitizeEntries — 记录条目")
    class Entries {

        @Test
        @DisplayName("id / title 任一为空 → 丢弃；id 重复只留第一条")
        void requiresIdAndTitle() throws Exception {
            assertTrue(DevNoteController.sanitizeEntries(json("[{\"title\":\"t\"}]")).isEmpty());
            assertTrue(DevNoteController.sanitizeEntries(json("[{\"id\":\"i\"}]")).isEmpty());
            List<Map<String, Object>> r = DevNoteController.sanitizeEntries(json(
                    "[{\"id\":\"i\",\"title\":\"第一\"},{\"id\":\"i\",\"title\":\"第二\"}]"));
            assertEquals(1, r.size());
            assertEquals("第一", r.get(0).get("title"));
        }

        @Test
        @DisplayName("severity 不在白名单 → 回落 pitfall；合法值原样保留")
        void severityWhitelistFallback() throws Exception {
            List<Map<String, Object>> r = DevNoteController.sanitizeEntries(json(
                    "[{\"id\":\"a\",\"title\":\"t\",\"severity\":\"bogus\"},"
                            + "{\"id\":\"b\",\"title\":\"t\",\"severity\":\"blocker\"}]"));
            assertEquals("pitfall", r.get(0).get("severity"));
            assertEquals("blocker", r.get(1).get("severity"));
        }

        @Test
        @DisplayName("tags 走 strList：trim、丢空串与非文本、每条 ≤32 字、最多 MAX_TAGS 条")
        void tagsSanitized() throws Exception {
            StringBuilder tags = new StringBuilder("[");
            for (int i = 0; i < DevNoteController.MAX_TAGS + 5; i++) {
                if (i > 0) tags.append(',');
                tags.append("\" tag").append(i).append(" \"");
            }
            tags.append(",\"\", 7, \"").append("x".repeat(50)).append("\"]");

            Map<String, Object> r = DevNoteController.sanitizeEntries(json(
                    "[{\"id\":\"i\",\"title\":\"t\",\"tags\":" + tags + "}]")).get(0);
            @SuppressWarnings("unchecked")
            List<String> got = (List<String>) r.get("tags");
            assertEquals(DevNoteController.MAX_TAGS, got.size());       // 数字与空串被丢，总数被截断
            assertEquals("tag0", got.get(0));                            // trim 生效
            assertFalse(got.contains(""));
        }

        @Test
        @DisplayName("超长字段被截断：title≤200 / solution≤8000 / code≤20000")
        void truncatesOverlongFields() throws Exception {
            Map<String, Object> r = DevNoteController.sanitizeEntries(json(
                    "[{\"id\":\"i\",\"title\":\"" + "t".repeat(300) + "\","
                            + "\"solution\":\"" + "s".repeat(9000) + "\","
                            + "\"code\":\"" + "c".repeat(25000) + "\"}]")).get(0);
            assertEquals(200, ((String) r.get("title")).length());
            assertEquals(8000, ((String) r.get("solution")).length());
            assertEquals(20000, ((String) r.get("code")).length());
        }

        @Test
        @DisplayName("数量被 MAX_ENTRIES_PER_PROJECT 截断")
        void capsCount() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < DevNoteController.MAX_ENTRIES_PER_PROJECT + 5; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"i").append(i).append("\",\"title\":\"t\"}");
            }
            sb.append(']');
            assertEquals(DevNoteController.MAX_ENTRIES_PER_PROJECT,
                    DevNoteController.sanitizeEntries(json(sb.toString())).size());
        }

        @Test
        @DisplayName("非数组 → 空表")
        void nonArrayYieldsEmpty() throws Exception {
            assertTrue(DevNoteController.sanitizeEntries(null).isEmpty());
            assertTrue(DevNoteController.sanitizeEntries(json("{}")).isEmpty());
        }

        @Test
        @DisplayName("项目 → 条目：整体走通，且 entries 里能拿到净化后的条目")
        void projectsWireThroughEntries() throws Exception {
            List<Map<String, Object>> r = DevNoteController.sanitizeProjects(json(
                    "[{\"code\":\"c\",\"name\":\"n\",\"entries\":["
                            + "{\"id\":\"i\",\"title\":\"T\",\"severity\":\"bug\",\"tags\":[\"a\"]}]}]"));
            assertEquals(1, r.size());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) r.get(0).get("entries");
            assertEquals(1, entries.size());
            assertEquals("bug", entries.get(0).get("severity"));
            assertEquals(List.of("a"), entries.get(0).get("tags"));
        }
    }
}
