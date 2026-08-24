package com.notelab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import com.notelab.common.JsonUtil;
import com.notelab.dao.TrpgDao;

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

    // ---------- B/C 拆分阶段2：B/C 共用逻辑（自 TrpgController 原样迁入，行为不变） ----------

    /** choose 业务错误：携带 HTTP 状态码，由 Controller 原样映射为 {"error": ...} 响应 */
    public static final class ChooseException extends RuntimeException {
        public final int status;
        public ChooseException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** 对局所属剧本的解析（scenario_json → ObjectNode），损坏/缺失返回 empty */
    public static Optional<ObjectNode> scenarioOfPlay(Map<String, Object> play) {
        try {
            Map<String, Object> row = TrpgDao.getTrpgScenario(((Number) play.get("scenario_id")).longValue());
            return Optional.of((ObjectNode) JsonUtil.parse(String.valueOf(row.get("scenario_json"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 对局响应：当前节点全文 + 历史 + 状态（响应键集合与原 TrpgController.playPayload 完全一致） */
    public static Map<String, Object> playPayload(Map<String, Object> play, ObjectNode sc) {
        ObjectNode node = findNode(sc, String.valueOf(play.get("current_node")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("play_id", play.get("id"));
        out.put("scenario_id", play.get("scenario_id"));
        out.put("scenario_title", play.get("scenario_title"));
        out.put("state", play.get("state"));
        out.put("steps", play.get("steps"));
        out.put("ending_title", play.get("ending_title"));
        out.put("node", node == null ? null : toMap(node));
        // 历史条目附带所属节点全文（剧情文本+全部选项），回忆翻阅用；旧数据无 node_id 时按标题匹配
        try {
            JsonNode h = JsonUtil.parse(String.valueOf(play.get("history_json")));
            List<Map<String, Object>> hist = new ArrayList<>();
            if (h.isArray()) {
                for (JsonNode e : h) {
                    Map<String, Object> em = JsonUtil.MAPPER.convertValue(e, LinkedHashMap.class);
                    ObjectNode en = null;
                    Object nid = em.get("node_id");
                    if (nid != null && !String.valueOf(nid).isEmpty()) en = findNode(sc, String.valueOf(nid));
                    if (en == null && em.get("node_title") != null) {
                        for (JsonNode nj : sc.path("nodes")) {
                            if (String.valueOf(em.get("node_title")).equals(nj.path("title").asText(""))) {
                                en = (ObjectNode) nj;
                                break;
                            }
                        }
                    }
                    if (en != null) em.put("node", toMap(en));
                    hist.add(em);
                }
            }
            out.put("history", hist);
        } catch (Exception e) {
            out.put("history", List.of());
        }
        return out;
    }

    /**
     * 执行一次 choose 推进（B/C 共用引擎）：校验选项 → 掷骰判定 → 追加冒险日志 → 更新存档，
     * 返回最新存档 payload。业务错误抛 ChooseException（400 已结束/选项不存在、422 choice_id 必填、500 场景损坏）。
     */
    public static Map<String, Object> applyChoice(Map<String, Object> play, ObjectNode sc, String choiceId) {
        if ("ended".equals(play.get("state"))) throw new ChooseException(400, "该对局已结束");
        if (choiceId == null || choiceId.trim().isEmpty()) throw new ChooseException(422, "choice_id 必填");
        ObjectNode cur = findNode(sc, String.valueOf(play.get("current_node")));
        if (cur == null) throw new ChooseException(500, "当前场景损坏");

        ObjectNode choice = null;
        for (JsonNode cj : cur.path("choices")) {
            if (choiceId.equals(cj.path("id").asText(""))) { choice = (ObjectNode) cj; break; }
        }
        if (choice == null) throw new ChooseException(400, "选项不存在");

        // 掷骰判定（服务端权威掷骰）
        Map<String, Object> diceResult = null;
        String nextId;
        if (choice.has("dice")) {
            int target = choice.path("dice").path("target").asInt(50);
            int roll = rollD100();
            boolean success = roll <= target;
            diceResult = new LinkedHashMap<>();
            diceResult.put("roll", roll);
            diceResult.put("target", target);
            diceResult.put("success", success);
            nextId = success ? choice.path("next").asText() : choice.path("fail_next").asText(choice.path("next").asText());
        } else {
            nextId = choice.path("next").asText();
        }
        ObjectNode next = findNode(sc, nextId);
        if (next == null) next = findNode(sc, startNodeId(sc));

        // 追加冒险日志
        List<Map<String, Object>> history = new ArrayList<>();
        try {
            JsonNode h = JsonUtil.parse(String.valueOf(play.get("history_json")));
            if (h.isArray()) for (JsonNode e : h) history.add(JsonUtil.MAPPER.convertValue(e, LinkedHashMap.class));
        } catch (Exception ignored) {}
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("node_id", cur.path("id").asText(""));
        entry.put("node_title", cur.path("title").asText(""));
        entry.put("choice", choice.path("text").asText(""));
        if (diceResult != null) entry.put("dice", diceResult);
        history.add(entry);

        boolean ended = next.path("ending").asBoolean(false);
        int steps = ((Number) play.get("steps")).intValue() + 1;
        long playId = ((Number) play.get("id")).longValue();
        TrpgDao.updateTrpgPlay(playId, next.path("id").asText(), ended ? "ended" : "playing",
                ended ? next.path("title").asText("") : "", steps, JsonUtil.write(history));
        return playPayload(TrpgDao.getTrpgPlay(playId), sc);
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