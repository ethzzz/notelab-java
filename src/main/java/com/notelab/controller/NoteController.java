package com.notelab.controller;

import com.notelab.dao.NoteDao;
import com.notelab.model.NoteType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B 端笔记 / PRD：前缀 /api/notes，Notion 式 Markdown 文档（保存/列表/编辑/查看/导出）。
 * 正文以 Markdown 存 notes 表（content_md），渲染在前端完成（marked + DOMPurify）。
 *
 * <p><b>文档类型</b>：{@code type=note|prd}（缺省 note，见 {@link NoteType}）。笔记与 PRD 是同一套东西，
 * 只在「哪个页面里看」上不同，所以走同一个 Controller、同一张表，靠一列区分。
 * 每个读写入口都带类型校验：拿 PRD 的 id 去笔记接口取/改/删一律 404，避免两页之间互相误伤。
 *
 * <p>⚠️ 类型是**归类维度**，不是安全边界（详见 {@link NoteType} 类注释）。
 *
 * <p>全部要求 B 端登录（AuthUtil）；路由由 PermService 启动自动登记到 perm_routes。
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    /** 正文上限（UTF-8 字节数）：2MB */
    private static final long MAX_MD_BYTES = 2L * 1024 * 1024;
    /** 标题上限 */
    private static final int MAX_TITLE_LEN = 200;

    public static class NoteSaveReq { public String title; public String content_md; public String type; }

    // ================= CRUD =================

    /** 列表：q 模糊匹配标题，不含正文；type 决定看哪一类文档 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(defaultValue = "0") long offset,
                                                    HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        String docType = NoteType.normalize(type);
        if (docType == null) return badType();
        int l = Math.min(Math.max(limit, 1), 100);
        long off = Math.max(offset, 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", NoteDao.listPaged(docType, q, l, off));
        body.put("total", NoteDao.countFiltered(docType, q));
        body.put("limit", l);
        body.put("offset", off);
        body.put("type", docType);
        return ResponseEntity.ok(body);
    }

    /** 详情：含 Markdown 正文（类型不符 → 404） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id,
                                                   @RequestParam(required = false) String type,
                                                   HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        String docType = NoteType.normalize(type);
        if (docType == null) return badType();
        Map<String, Object> row = NoteDao.getByIdInType(id, docType);
        if (row == null) return notFound(docType);
        return ResponseEntity.ok(row);
    }

    /** 新建文档 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) NoteSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        Object idObj = me.get("id");
        Long createdBy = idObj instanceof Number n ? n.longValue() : null;
        // validateSave 已保证归一结果非 null
        String docType = NoteType.normalize(req.type);
        long id = NoteDao.create(docType, req.title.trim(), req.content_md, createdBy);
        return ResponseEntity.ok(Map.of("ok", true, "id", id, "type", docType));
    }

    /** 更新标题与正文（类型不符 → 404） */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) NoteSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        String docType = NoteType.normalize(req.type);
        if (!NoteDao.existsInType(id, docType)) return notFound(docType);
        NoteDao.update(id, req.title.trim(), req.content_md);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id,
                                                      @RequestParam(required = false) String type,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        String docType = NoteType.normalize(type);
        if (docType == null) return badType();
        if (!NoteDao.existsInType(id, docType)) return notFound(docType);
        NoteDao.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 导出（真实文件响应，避免 blob: 触发 Chrome 不安全下载拦截） =================

    /** 单篇导出：服务端直接返回 .md 文件（Content-Disposition: attachment） */
    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportOne(@PathVariable long id,
                                       @RequestParam(required = false) String type,
                                       HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        String docType = NoteType.normalize(type);
        if (docType == null) return ResponseEntity.status(400).body(new byte[0]);
        Map<String, Object> row = NoteDao.getByIdInType(id, docType);
        if (row == null) return ResponseEntity.status(404).body(new byte[0]);
        String title = row.get("title") == null ? NoteType.NOTE : String.valueOf(row.get("title"));
        String md = row.get("content_md") == null ? "" : String.valueOf(row.get("content_md"));
        return buildMdAttachment(md, title);
    }

    /** 全量导出：服务端合并同类文档为一个 .md（以 `## 标题` 分隔）；q 关键字同列表筛选 */
    @GetMapping("/export-all")
    public ResponseEntity<?> exportAll(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String type,
                                       HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        String docType = NoteType.normalize(type);
        if (docType == null) return ResponseEntity.status(400).body(new byte[0]);
        List<Map<String, Object>> rows = NoteDao.listAll(docType, q);
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String kind = NoteType.label(docType);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(kind).append("导出（共 ").append(rows.size()).append(" 篇）\n\n")
                .append("> 导出时间：").append(now).append("\n\n");
        for (Map<String, Object> r : rows) {
            String t = r.get("title") == null ? "未命名" : String.valueOf(r.get("title"));
            String c = r.get("content_md") == null ? "" : String.valueOf(r.get("content_md"));
            sb.append("\n---\n\n## ").append(t).append("\n\n").append(c.replaceAll("\\s*$", "")).append("\n\n");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        return buildMdAttachment(sb.toString(), kind + "-全部-" + stamp);
    }

    /** 客户端内容导出（草稿 / 当前编辑器内容）：回显为 .md 附件，避免 blob: 下载 */
    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> exportPosted(@RequestParam("filename") String filename,
                                               @RequestParam("content_md") String contentMd,
                                               HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (contentMd != null && contentMd.getBytes(StandardCharsets.UTF_8).length > 20L * 1024 * 1024) {
            return ResponseEntity.status(413).body(new byte[0]);
        }
        return buildMdAttachment(contentMd, filename);
    }

    /** 构造 text/markdown 附件响应（UTF-8 文件名，兼容 ASCII 回退 + RFC5987 filename*） */
    private ResponseEntity<byte[]> buildMdAttachment(String content, String rawName) {
        String safe = safeFileName(rawName) + ".md";
        byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        String ascii = safe.replaceAll("[^\\x20-\\x7E]", "_");
        String star = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + star)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(data);
    }

    private static String safeFileName(String name) {
        if (name == null) return NoteType.NOTE;
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /**
     * 保存校验。
     *
     * <p>⚠️ 类型在这儿一并校验（非法值 400）：{@link NoteType#normalize} 对非法值返回 null，
     * 若直接透传给 DAO 就会写进一条谁也筛不出来的孤儿文档。
     */
    private static ResponseEntity<Map<String, Object>> validateSave(NoteSaveReq req) {
        if (req == null || req.title == null || req.title.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
        }
        if (req.title.trim().length() > MAX_TITLE_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "标题最多 " + MAX_TITLE_LEN + " 字"));
        }
        if (NoteType.normalize(req.type) == null) return badType();
        if (req.content_md == null) req.content_md = "";
        if (req.content_md.getBytes(StandardCharsets.UTF_8).length > MAX_MD_BYTES) {
            return ResponseEntity.status(400).body(Map.of("error", "正文超过 2MB 上限"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> badType() {
        return ResponseEntity.status(400)
                .body(Map.of("error", "不支持的文档类型，仅支持 " + String.join(" / ", NoteType.ALL)));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String docType) {
        return ResponseEntity.status(404).body(Map.of("error", NoteType.label(docType) + "不存在"));
    }
}
