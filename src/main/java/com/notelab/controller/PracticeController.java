package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.dao.PracticeDao;
import com.notelab.dao.PracticeImageDao;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 练习实践：记录做完每道练习题后的解答（Markdown 正文，可配图）。
 *
 * <h3>三块存储，别混</h3>
 * <ul>
 *   <li><b>记录</b>（标题 / 类型 / 正文 / 创建更新时间人）：表 {@code practice_record}
 *       （见 {@link PracticeDao}）。随条数无限增长，且删一条不该重写整份配置 —— 所以不进 ui_config。</li>
 *   <li><b>插图</b>：表 {@code practice_image}（见 {@link PracticeImageDao}）。正文里只留短 URL，
 *       不把 base64 塞进 Markdown（textarea 里一个几十万字符的 data URL 会让编辑器直接卡住）。</li>
 *   <li><b>类型字典</b>：{@code ui_config.practice} 的 {@code types}（**配置**，不是运行数据）。
 *       暂不做编辑界面，但存储上已可覆盖 —— 以后要加类型配置页时不需要迁移。</li>
 * </ul>
 *
 * <h3>「非内容数据」与「内容数据」分开写</h3>
 * 编辑页把两类数据分成两个保存动作（{@code PUT /records/{id}} 的 {@code kind} 参数）：
 * <ul>
 *   <li>{@code kind=meta} → 只改标题/类型，正文一个字都不碰；</li>
 *   <li>{@code kind=content} → 只改正文。</li>
 * </ul>
 * 分开的意义是**防覆盖**：顶部改个标题不该把下面正在写的正文一起提交。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    /** ui_config 里的键（只存 types 字典；记录本身在表里） */
    static final String KEY = "practice";

    static final int MAX_TITLE = 200;
    static final int MAX_TYPE = 32;
    /** 正文上限：50 万字符（约 500KB 文本）。写再长的题解也够，同时挡住误贴整份日志 */
    static final int MAX_CONTENT_CHARS = 500_000;
    /** 单张插图上限：与 application.properties 的 multipart 配置一致，这里是服务端最后一道校验 */
    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /** 只收这几种图片类型。不做「任意文件上传」——那等于开了个任意文件托管入口 */
    static final Set<String> IMAGE_MIMES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    // ==================================================================================
    // 类型字典（懒 seed 初值）
    // ==================================================================================

    /**
     * 内置语言类型。用户在需求里点名的是 js / ts / java / nodejs / html，
     * 这里补成一份「够用的起步集」；要加类型不用改代码（ui_config.practice.types 覆盖）。
     */
    private static final List<Map<String, Object>> BASE_TYPES = List.of(
            type("js", "JavaScript"),
            type("ts", "TypeScript"),
            type("nodejs", "Node.js"),
            type("html", "HTML"),
            type("css", "CSS"),
            type("java", "Java"),
            type("python", "Python"),
            type("go", "Go"),
            type("rust", "Rust"),
            type("c", "C"),
            type("cpp", "C++"),
            type("sql", "SQL"),
            type("lua", "Lua"),
            type("shell", "Shell"),
            type("other", "其他"));

    private static Map<String, Object> type(String value, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        return m;
    }

    // ==================================================================================
    // 元信息：GET /api/practice/meta
    // ==================================================================================

    /**
     * 页面初始化用：类型下拉的数据源 + 插图体积概览。
     *
     * <p>插图体积摊在页面上是刻意的 —— 图片直接进库，没有 {@code ls} 可看，不显示就只能等它撑爆。
     */
    @GetMapping("/meta")
    public ResponseEntity<Map<String, Object>> meta(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("types", typesOf(UiConfigService.getConfig()));
        try {
            body.put("images", PracticeImageDao.summary());
        } catch (Exception e) {
            body.put("images", Map.of("count", 0L, "bytes", 0L));
        }
        return ResponseEntity.ok(body);
    }

    /** types 出口：库里没有或列表为空 → 回内置（懒 seed，不主动写库） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> typesOf(Map<String, Object> cfg) {
        Object o = cfg.get(KEY);
        if (o instanceof Map) {
            Object ts = ((Map<String, Object>) o).get("types");
            if (ts instanceof List && !((List<?>) ts).isEmpty()) {
                return (List<Map<String, Object>>) ts;
            }
        }
        return new ArrayList<>(BASE_TYPES);
    }

    // ==================================================================================
    // 记录：列表 / 详情
    // ==================================================================================

    /**
     * 列表（**不含正文**）。title 模糊匹配；types 逗号分隔多选（任一命中）。
     *
     * <p>分页参数脏了不报错、按默认值收敛 —— 列表页拿不到数据比拿到第二页更糟。
     */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> records(@RequestParam(required = false) String title,
                                                       @RequestParam(required = false) String types,
                                                       @RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String size,
                                                       HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int p = Math.max(1, intOf(page, 1));
        int n = Math.max(1, Math.min(PracticeDao.MAX_SIZE, intOf(size, PracticeDao.DEFAULT_SIZE)));
        List<String> ts = splitTypes(types);
        try {
            long total = PracticeDao.countRecords(title, ts);
            List<Map<String, Object>> items = PracticeDao.listRecords(title, ts, (p - 1) * n, n);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            body.put("total", total);
            body.put("page", p);
            body.put("size", n);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
    }

    /** 单条（**含正文**）：编辑页/查看页打开时取 */
    @GetMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> record(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> r;
        try {
            r = PracticeDao.getRecord(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        return ResponseEntity.ok(r);
    }

    // ==================================================================================
    // 记录：增 / 改 / 删
    // ==================================================================================

    /** 新建记录（一级页面的「创建记录」弹窗：只填标题 + 类型），正文留空，进二级页面再写。 */
    @PostMapping("/records")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体必须是 JSON 对象"));
        }
        String title = JsonSanitizer.str(node, "title", MAX_TITLE).trim();
        String type = JsonSanitizer.str(node, "type", MAX_TYPE).trim();
        if (title.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
        if (type.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "类型不能为空"));

        try {
            long id = PracticeDao.insertRecord(title, type, "", uid(user), uname(user));
            return ResponseEntity.ok(Map.of("ok", true, "id", id));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "创建失败：" + e));
        }
    }

    /**
     * 改一条记录。body：{@code {"kind":"meta","title":…,"type":…}} 或 {@code {"kind":"content","content":…}}。
     *
     * <p>⚠️ 刻意**不用**「提供了哪个字段就改哪个」的 PATCH 语义：那样「只想改标题」的请求一旦漏传
     * content，正文就被清空，而且从请求体上看不出这是失误还是本意。kind 把意图写在明面上。
     */
    @PutMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (PracticeDao.getRecord(id) == null) {
            return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        }
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体必须是 JSON 对象"));
        }
        String kind = JsonSanitizer.str(node, "kind", 16).trim();
        try {
            if ("meta".equals(kind)) {
                String title = JsonSanitizer.str(node, "title", MAX_TITLE).trim();
                String type = JsonSanitizer.str(node, "type", MAX_TYPE).trim();
                if (title.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
                if (type.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "类型不能为空"));
                PracticeDao.updateMeta(id, title, type, uid(user), uname(user));
            } else if ("content".equals(kind)) {
                // ⚠️ 这里**不能用 JsonSanitizer.str**：它按字符上限截断，而正文上限 50 万字符，
                //    被静默截断比直接报错更难查。先取原值、再显式判超限。
                JsonNode c = node.get("content");
                String content = c == null || c.isNull() ? "" : c.asText("");
                if (content.length() > MAX_CONTENT_CHARS) {
                    return ResponseEntity.status(400).body(Map.of("error",
                            "内容过大（>" + (MAX_CONTENT_CHARS / 1000) + "K 字符），精简后再保存"));
                }
                PracticeDao.updateContent(id, content, uid(user), uname(user));
            } else {
                return ResponseEntity.status(400).body(Map.of("error", "kind 必须是 meta 或 content"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int n;
        try {
            n = PracticeDao.deleteRecord(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "删除失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true, "deleted", n));
    }

    // ==================================================================================
    // 插图：上传 / 直出
    // ==================================================================================

    /**
     * 上传一张插图，返回可直接写进 Markdown 的 URL。
     *
     * <p>⚠️ 服务端**必须自己校验** mime 与体积：前端那套压缩/类型判断只是体验层，绕过它只要一行命令。
     * 白名单之外一律拒 —— 否则这里就变成了任意文件上传入口。
     */
    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file,
                                                           HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "没有收到文件"));
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "图片过大（>" + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB），压缩后再传"));
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase();
        // 有些客户端给的是 application/octet-stream，此时按文件名后缀兜一次
        if (!IMAGE_MIMES.contains(mime)) {
            String byName = mimeByName(file.getOriginalFilename());
            if (byName == null) {
                return ResponseEntity.status(400).body(Map.of("error", "只支持 PNG / JPEG / WebP / GIF 图片"));
            }
            mime = byName;
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "读取文件失败：" + e));
        }
        if (data.length == 0) return ResponseEntity.status(400).body(Map.of("error", "文件内容为空"));
        try {
            long id = PracticeImageDao.insertImage(mime, data.length, data, uid(user));
            return ResponseEntity.ok(Map.of("ok", true, "id", id, "url", imageUrl(id)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
    }

    /**
     * 直出图片字节。
     *
     * <p>⚠️ 图片**不可变**（id 唯一、内容写入后不再改），所以给浏览器长缓存 + immutable：
     * 编辑页反复刷新时不必重复拉同一张图。权限仍由受限前缀兜着（非超管 403），
     * 缓存头里用 {@code private} 是刻意的 —— 别让中间代理缓存到别人手里。
     */
    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return ResponseEntity.status(401).build();
        Map<String, Object> r;
        try {
            r = PracticeImageDao.getImage(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
        if (r == null) return ResponseEntity.status(404).build();
        byte[] data = (byte[]) r.get("data");
        String mime = String.valueOf(r.get("mime"));
        MediaType mt;
        try {
            mt = MediaType.parseMediaType(mime);
        } catch (Exception e) {
            mt = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mt)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(data);
    }

    /** 图片的对外路径（前端插入 Markdown 用，与 {@link #image} 的映射一致） */
    private static String imageUrl(long id) {
        return "/api/practice/images/" + id;
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    private static JsonNode parse(String raw) {
        try {
            if (raw == null || raw.isBlank()) return null;
            return JsonUtil.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件名后缀 → mime（仅在客户端没给对 Content-Type 时兜底） */
    private static String mimeByName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return null;
    }

    /** "js,ts,js" → [js, ts]（去空去重，顺序保留） */
    private static List<String> splitTypes(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) {
            for (String x : s.split(",")) {
                String v = x.trim();
                if (!v.isEmpty()) out.add(v.length() > MAX_TYPE ? v.substring(0, MAX_TYPE) : v);
            }
        }
        return new ArrayList<>(out);
    }

    private static int intOf(String s, int dft) {
        if (s == null || s.isBlank()) return dft;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static Long uid(Map<String, Object> user) {
        Object o = user.get("id");
        return o instanceof Number n ? n.longValue() : null;
    }

    private static String uname(Map<String, Object> user) {
        return String.valueOf(user.getOrDefault("username", ""));
    }
}
