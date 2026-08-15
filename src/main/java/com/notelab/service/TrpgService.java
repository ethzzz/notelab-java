package com.notelab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import com.notelab.common.JsonUtil;

/**
 * TRPG 跑团服务：
 *  - AI 剧本生成（提示词构建、输出解析与结构修复）
 *  - 游戏引擎辅助（起始节点、节点查找、d100 掷骰）
 */
public final class TrpgService {

    private TrpgService() {}

    // ---------- 生成 ----------

    /** 构建生成剧本的 messages（system + user） */
    public static List<Map<String, String>> buildGenMessages(Map<String, Object> cfg) {
        String style = str(cfg.get("style"), "悬疑推理");
        String scale = str(cfg.get("scale"), "中");
        String nodeHint = scale.contains("短") ? "6-8" : scale.contains("长") ? "16-22" : "10-14";
        String sys = """
你是一位资深 TRPG 剧本设计师，擅长创作分支剧情、多结局的互动式跑团剧本。
请根据玩家提供的设定，创作一个 TRPG 剧本，严格以 JSON 格式输出。

要求：
1. 只输出一个 JSON 对象，不要输出 markdown 代码块，也不要输出任何解释文字。
2. JSON 结构：
{
  "title": "剧本标题",
  "intro": "剧本引言，100 字以内，点明背景与悬念",
  "characters": [{"name":"角色名","desc":"一句话描述"}],
  "nodes": [
    {"id":"唯一英文 id，第一个节点必须是 start","title":"场景标题","text":"场景叙述，100-250 字，第二人称，有画面感和氛围","choices":[
       {"id":"c1","text":"玩家可选的行动描述","next":"目标节点 id"},
       {"id":"c2","text":"需要检定的行动","next":"成功前往的节点 id","dice":{"target":40},"fail_next":"失败前往的节点 id"}
    ]},
    {"id":"end_a","ending":true,"title":"结局：名称","text":"结局叙述，收束剧情"}
  ]
3. 故事要有完整的起承转合；节点总数 %s 个左右；每个非结局场景 2-3 个选择；至少 2 个不同结局；所有 next/fail_next 必须指向已存在的节点 id，禁止悬空引用。
4. 约三分之一的选择可带掷骰检定：dice.target 为 d100 成功阈值（20-80，越难越低），且必须提供 fail_next 指向合理的失败发展节点。
5. 叙事风格：%s。角色与地点要融入剧情，事件作为故事核心驱动力。
""".formatted(nodeHint, style);
        String user = """
玩家设定：
- 背景：%s
- 人物：%s
- 地点：%s
- 核心事件：%s
- 标题偏好：%s

请输出完整 JSON 剧本。
""".formatted(
                str(cfg.get("background"), "（由你自由发挥）"),
                str(cfg.get("characters"), "（由你自由发挥）"),
                str(cfg.get("places"), "（由你自由发挥）"),
                str(cfg.get("event"), "（由你自由发挥）"),
                str(cfg.get("title"), "（由你起名）"));
        return List.of(
                Map.of("role", "system", "content", sys),
                Map.of("role", "user", "content", user));
    }

    /** 从模型输出中提取 JSON 对象（容忍 markdown 围栏与前后废话），解析失败抛 IllegalArgumentException */
    public static ObjectNode parseScenario(String content) {
        String s = content == null ? "" : content.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("模型未返回可解析的剧本 JSON");
        try {
            JsonNode node = JsonUtil.parse(s.substring(a, b + 1));
            if (!(node.isObject() && node.has("nodes") && node.get("nodes").isArray() && node.get("nodes").size() > 0)) {
                throw new IllegalArgumentException("缺少节点列表 nodes");
            }
            return (ObjectNode) node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败");
        }
    }

    /** 修复与规范化：补 id、坏链兜底、保证至少一个结局、骰子选择补 fail_next */
    public static ObjectNode normalize(ObjectNode sc) {
        ArrayNode nodes = (ArrayNode) sc.get("nodes");
        Set<String> ids = new HashSet<>();
        int seq = 0;
        for (JsonNode n : nodes) {
            ObjectNode on = (ObjectNode) n;
            if (on.path("id").asText("").isEmpty()) on.put("id", "n" + (++seq));
            ids.add(on.path("id").asText());
        }
        // 无选择的节点视为结局；找到第一个结局作为坏链兜底
        String fallbackEnding = null;
        for (JsonNode n : nodes) {
            ObjectNode on = (ObjectNode) n;
            JsonNode choices = on.get("choices");
            boolean noChoices = choices == null || !choices.isArray() || choices.size() == 0;
            if (noChoices) { on.put("ending", true); on.remove("choices"); }
            if (on.path("ending").asBoolean(false) && fallbackEnding == null) fallbackEnding = on.path("id").asText();
        }
        if (fallbackEnding == null) {
            ObjectNode last = (ObjectNode) nodes.get(nodes.size() - 1);
            last.put("ending", true);
            last.remove("choices");
            fallbackEnding = last.path("id").asText();
        }
        for (JsonNode n : nodes) {
            ObjectNode on = (ObjectNode) n;
            if (on.path("ending").asBoolean(false)) continue;
            ArrayNode choices = (ArrayNode) on.get("choices");
            for (JsonNode cj : choices) {
                ObjectNode c = (ObjectNode) cj;
                if (!ids.contains(c.path("next").asText(""))) c.put("next", fallbackEnding);
                if (c.has("dice") && !ids.contains(c.path("fail_next").asText(""))) {
                    c.put("fail_next", c.path("next").asText());
                }
            }
        }
        return sc;
    }

    // ---------- 引擎 ----------

    /** 起始节点：优先 id=start，否则第一个节点 */
    public static String startNodeId(ObjectNode sc) {
        ArrayNode nodes = (ArrayNode) sc.get("nodes");
        for (JsonNode n : nodes) if ("start".equals(n.path("id").asText(""))) return "start";
        return nodes.get(0).path("id").asText();
    }

    public static ObjectNode findNode(ObjectNode sc, String id) {
        if (id == null) return null;
        for (JsonNode n : (ArrayNode) sc.get("nodes")) {
            if (id.equals(n.path("id").asText(""))) return (ObjectNode) n;
        }
        return null;
    }

    /** d100：1-100 */
    public static int rollD100() {
        return ThreadLocalRandom.current().nextInt(1, 101);
    }

    private static String str(Object v, String def) {
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    /** 场景 JSON -> Map（用于 JSON 响应序列化） */
    public static Map<String, Object> toMap(ObjectNode sc) {
        return JsonUtil.MAPPER.convertValue(sc, LinkedHashMap.class);
    }
}