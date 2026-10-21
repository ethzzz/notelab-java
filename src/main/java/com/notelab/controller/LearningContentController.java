package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.model.LearningSeed;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编程学习：GET|POST /api/learning。
 *
 * <p>分层：语言（track）→ 阶段（stage）→ 课时（lesson）→ 随堂测试题（quiz）。
 * 每节课 = 知识讲解（markdown）+ 可直接运行的示例代码 + 选择题。
 *
 * <p>整包存 ui_config JSON 的 "learning" 键，不新建表；写法对齐 {@link DevNoteController}：
 * <ul>
 *   <li><b>手工白名单</b>：save() 里逐层 put，没登记的字段被静默丢弃；</li>
 *   <li><b>懒 seed</b>：库里无 learning 或 tracks 为空 → 回内置内容（{@link LearningSeed}）；</li>
 *   <li><b>整包覆盖写</b>：POST 提交全量 tracks 数组。</li>
 * </ul>
 *
 * <p><b>没有发布快照</b>：这是后台知识库，不是 C 端消费的内容，编辑即生效。
 *
 * <p><b>runners 是只读的</b>：运行能力表（哪门语言能不能在浏览器里跑、走什么方案）
 * 由 {@link LearningSeed#RUNNERS} 这份代码常量维护，**不接受前端提交** ——
 * 它描述的是本站的工程能力，不是可编辑的教学内容。save() 里刻意不 put 它。
 *
 * <p>⚠️ 已上线后改内置内容不生效：库里 tracks 非空时一律以库里的为准（用户的删改要保住）。
 * 想让内置新增的语言冒出来，得先把 tracks 清空（POST {@code {"tracks":[]}}）再 GET。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/learning")
public class LearningContentController {

    /**
     * 整包上限。2026-10-21 从 1MB 提到 2MB：课程从 88 节扩到 108 节、每节补到 2.4k+ 中文字后，
     * 紧凑 JSON 已到 1.26M 字符（约 2.1MB UTF-8）。ui_config 是 mediumtext（16MB），空间不是瓶颈；
     * 这里只是防误贴整份日志的闸门，别为了「看起来小」去截断正文。
     */
    private static final int MAX_LEARNING_CHARS = 2_000_000;
    static final int MAX_TRACKS = 30;
    static final int MAX_STAGES = 12;
    static final int MAX_LESSONS = 200;
    static final int MAX_QUIZ_PER_LESSON = 20;
    static final int MAX_OPTIONS = 8;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(learningOf(UiConfigService.getConfig()));
    }

    /**
     * C 端公开读取（**匿名可用**）。
     *
     * <p>C 端的「编程语言学习」页（notelab-c `/learn`）要求**未登录也能看**，
     * 而上面那个 GET 走的是 B 端后台鉴权 —— 所以单开一个只读出口，
     * 让「B 端编辑 → C 端消费」共用**同一份数据**，不必再往 C 端导一份静态副本。
     *
     * <p>⚠️ 路径必须登记进 {@code PermGuard.PUBLIC_PREFIXES}：
     * 只写在这里是不够的 —— 带着 C 端会话的访客会**先**被 CPermGuard 按用户组拦下，
     * 表现成「只有匿名能访问、登录了反而 403」。
     *
     * <p>返回体与 B 端 GET **完全一致**（同一个 {@link #learningOf}）：不裁剪字段，
     * 免得日后加字段时两边行为悄悄分叉。
     *
     * <p>⚠️ 只读、无副作用，所以不需要 lowcode 那套「无会话写入口」的节流；
     * 但也因为**没有发布快照**，B 端正在编辑的内容会立刻对 C 端可见 ——
     * 这是「编辑即公开」的取舍，符合本模块「后台知识库」的定位。
     */
    @GetMapping("/public")
    public ResponseEntity<Map<String, Object>> publicGet(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        Map<String, Object> body = learningOf(UiConfigService.getConfig());
        String etag = etagOf(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        // ⚠️ no-cache（**不是 no-store**）：允许浏览器存一份，但每次使用前必须带
        //    If-None-Match 回源确认 —— 内容没变就是 304（无响应体），变了立刻拿到新的。
        //    于是「B 端改完 C 端立刻生效」与「不重复下载 170KB」两者兼得。
        //    用 no-store 会退回「每次全量下载」，用 max-age 会让编辑延迟可见，都不取。
        headers.setCacheControl(CacheControl.noCache());
        if (etag.equals(ifNoneMatch)) {
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /**
     * 由响应体内容算一个强 ETag。
     *
     * <p>为什么不用 {@code ShallowEtagHeaderFilter}：那个过滤器会把响应体整份缓存在内存里
     * 再算摘要，注册时容易顺手套到整个 {@code /api/**} 上 —— 而本站有 SSE（长连接、不能缓冲）。
     * 这里只给这一个只读端点自己算，边界清楚。
     *
     * <p>用 SHA-256 而不是 {@code hashCode()}：后者碰撞太容易，一旦撞上就会把一个**已更新**的
     * 内容误判成「没变」，用户看到的永远是旧课程。摘要取前 16 个十六进制字符足够区分。
     */
    private static String etagOf(Map<String, Object> body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtil.write(body).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.append('"').toString();
        } catch (Exception e) {
            // 摘要算法不可用属于环境异常；退化成「每次都回全量」，功能不受影响，只是少了优化
            return "\"no-etag\"";
        }
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

        // ⚠️ 手工白名单：这里没 put 的键会被静默丢弃（存了刷新就没了且不报错）。
        //    新增切片/字段时，save() 的 put 与 learningOf() 的读取分支**两处都要改**。
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tracks", sanitizeTracks(node.get("tracks")));

        String json = JsonUtil.write(doc);
        if (json.length() > MAX_LEARNING_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "内容过大（>" + (MAX_LEARNING_CHARS / 1000) + "KB），精简几节课再保存"));
        }
        try {
            UiConfigService.update("learning", doc);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true,
                "tracks", ((List<?>) doc.get("tracks")).size(),
                "lessons", countLessons(doc)));
    }

    /**
     * learning 出口。
     *
     * <p>{@code runners} **永远**来自代码常量（只读）：它是本站的工程能力描述，
     * 不属于可编辑内容，前端提交的同名字段会被白名单丢掉。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> learningOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runners", new ArrayList<>(LearningSeed.RUNNERS));
        out.put("tracks", new ArrayList<>(LearningSeed.BASE_TRACKS));
        Object o = cfg.get("learning");
        if (o instanceof Map) {
            Object ts = ((Map<String, Object>) o).get("tracks");
            if (ts instanceof List && !((List<?>) ts).isEmpty()) out.put("tracks", ts);
        }
        return out;
    }

    private static int countLessons(Map<String, Object> doc) {
        int n = 0;
        Object tracks = doc.get("tracks");
        if (!(tracks instanceof List<?> tl)) return 0;
        for (Object t : tl) {
            if (!(t instanceof Map<?, ?> tm)) continue;
            Object stages = tm.get("stages");
            if (!(stages instanceof List<?> sl)) continue;
            for (Object s : sl) {
                if (!(s instanceof Map<?, ?> sm)) continue;
                Object lessons = sm.get("lessons");
                if (lessons instanceof List<?> ll) n += ll.size();
            }
        }
        return n;
    }

    // ==================================================================================
    // 净化（只做结构与体积校验；不合法丢该条，不让整包 400）
    // ==================================================================================

    static List<Map<String, Object>> sanitizeTracks(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        List<String> runners = LearningSeed.runnerIds();
        for (JsonNode t : node) {
            if (out.size() >= MAX_TRACKS) break;
            if (t == null || !t.isObject()) continue;
            String code = JsonSanitizer.str(t, "code", 32);
            String name = JsonSanitizer.str(t, "name", 60);
            if (code.isEmpty() || name.isEmpty() || seen.contains(code)) continue;
            seen.add(code);
            String runner = JsonSanitizer.str(t, "runner", 32);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            m.put("icon", JsonSanitizer.str(t, "icon", 8));
            m.put("desc", JsonSanitizer.str(t, "desc", 500));
            // 运行方案必须是已知 id，否则落到 none（前端据此显示"暂不执行"）
            m.put("runner", runners.contains(runner) ? runner : "none");
            m.put("stages", sanitizeStages(t.get("stages")));
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> sanitizeStages(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode s : node) {
            if (out.size() >= MAX_STAGES) break;
            if (s == null || !s.isObject()) continue;
            String id = JsonSanitizer.str(s, "id", 64);
            String name = JsonSanitizer.str(s, "name", 60);
            if (id.isEmpty() || name.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("goal", JsonSanitizer.str(s, "goal", 500));
            m.put("lessons", sanitizeLessons(s.get("lessons")));
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> sanitizeLessons(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode l : node) {
            if (out.size() >= MAX_LESSONS) break;
            if (l == null || !l.isObject()) continue;
            String id = JsonSanitizer.str(l, "id", 64);
            String title = JsonSanitizer.str(l, "title", 200);
            if (id.isEmpty() || title.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("minutes", JsonSanitizer.clampInt(l.get("minutes"), 1, 600, 10));
            m.put("content", JsonSanitizer.str(l, "content", 40_000));
            m.put("code", JsonSanitizer.str(l, "code", 20_000));
            m.put("lang", JsonSanitizer.str(l, "lang", 32));
            m.put("quiz", sanitizeQuiz(l.get("quiz")));
            out.add(m);
        }
        return out;
    }

    /**
     * 随堂测试题。
     *
     * <p>⚠️ {@code answer} 是正确选项的**下标**，必须落在 options 范围内 ——
     * 越界的题直接丢弃（留着会让「答对判断」永远为假，学生怎么选都是错）。
     */
    static List<Map<String, Object>> sanitizeQuiz(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode q : node) {
            if (out.size() >= MAX_QUIZ_PER_LESSON) break;
            if (q == null || !q.isObject()) continue;
            String text = JsonSanitizer.str(q, "q", 1000);
            if (text.isEmpty()) continue;
            List<String> options = JsonSanitizer.strList(q.get("options"), MAX_OPTIONS, 500);
            if (options.size() < 2) continue;   // 少于两个选项不构成选择题
            int answer = JsonSanitizer.clampInt(q.get("answer"), 0, MAX_OPTIONS - 1, -1);
            if (answer < 0 || answer >= options.size()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("q", text);
            m.put("options", options);
            m.put("answer", answer);
            m.put("explain", JsonSanitizer.str(q, "explain", 2000));
            out.add(m);
        }
        return out;
    }
}
