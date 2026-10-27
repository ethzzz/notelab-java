package com.notelab.controller;

import com.notelab.dao.DocumentDao;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B 端文档编辑：前缀 /api/docs。
 * 正文就是 .docx 原始字节（documents.content_docx），前端 SuperDoc 直接读写，服务端不解析版式。
 * 全部要求 B 端登录（AuthUtil）；路由由 PermService 启动自动登记进 perm_routes。
 */
@RestController
@RequestMapping("/api/docs")
public class DocController {

    /** 单个 DOCX 上限（解码后字节数）：10MB。base64 后约 13.6MB，仍在 nginx 50m 之内。 */
    private static final long MAX_DOC_BYTES = 10L * 1024 * 1024;
    /** 标题上限 */
    private static final int MAX_TITLE_LEN = 200;
    /** DOCX 是 ZIP 容器，固定以 PK\x03\x04 开头 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public static class DocSaveReq { public String title; public String docx_base64; }

    private record Decoded(byte[] bytes, String error) {}

    // ================= CRUD =================

    /** 列表：q 模糊匹配标题，不含正文 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(defaultValue = "0") long offset,
                                                    HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        int l = Math.min(Math.max(limit, 1), 100);
        long off = Math.max(offset, 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", DocumentDao.listPaged(q, l, off));
        body.put("total", DocumentDao.countFiltered(q));
        body.put("limit", l);
        body.put("offset", off);
        return ResponseEntity.ok(body);
    }

    /** 详情：元数据 + DOCX（base64），供编辑器直接加载 */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        Map<String, Object> row = DocumentDao.getMetaById(id);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "文档不存在"));
        byte[] docx = DocumentDao.getDocx(id);
        if (docx == null) return ResponseEntity.status(404).body(Map.of("error", "文档正文缺失"));
        Map<String, Object> body = new LinkedHashMap<>(row);
        body.put("docx_base64", Base64.getEncoder().encodeToString(docx));
        return ResponseEntity.ok(body);
    }

    /** 原样下载 DOCX */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) {
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"请先登录\"}".getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> row = DocumentDao.getMetaById(id);
        byte[] docx = row == null ? null : DocumentDao.getDocx(id);
        if (docx == null) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"文档不存在\"}".getBytes(StandardCharsets.UTF_8));
        }
        String title = String.valueOf(row.get("title"));
        String ascii = title.replaceAll("[^A-Za-z0-9._-]", "_");
        if (ascii.isBlank()) ascii = "document";
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + ascii + ".docx\"; filename*=UTF-8''" + encoded + ".docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(DOCX_MIME))
                .body(docx);
    }

    /** 新建文档（正文为 base64 的 .docx） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) DocSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        Decoded d = decodeDocx(req.docx_base64);
        if (d.error != null) return ResponseEntity.status(400).body(Map.of("error", d.error));
        Object idObj = me.get("id");
        Long createdBy = idObj instanceof Number n ? n.longValue() : null;
        long id = DocumentDao.create(req.title.trim(), d.bytes, createdBy);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    /** 更新标题与正文 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) DocSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!DocumentDao.exists(id)) return ResponseEntity.status(404).body(Map.of("error", "文档不存在"));
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        Decoded d = decodeDocx(req.docx_base64);
        if (d.error != null) return ResponseEntity.status(400).body(Map.of("error", d.error));
        DocumentDao.update(id, req.title.trim(), d.bytes);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!DocumentDao.exists(id)) return ResponseEntity.status(404).body(Map.of("error", "文档不存在"));
        DocumentDao.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<Map<String, Object>> validateSave(DocSaveReq req) {
        if (req == null || req.title == null || req.title.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
        }
        if (req.title.trim().length() > MAX_TITLE_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "标题最长 " + MAX_TITLE_LEN + " 字"));
        }
        return null;
    }

    /** base64 → DOCX 字节；校验可解码、非空、未超限、ZIP 魔数 */
    private static Decoded decodeDocx(String base64) {
        if (base64 == null || base64.isBlank()) return new Decoded(null, "缺少文档内容");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return new Decoded(null, "文档内容不是合法的 Base64");
        }
        if (bytes.length == 0) return new Decoded(null, "文档为空");
        if (bytes.length > MAX_DOC_BYTES) return new Decoded(null, "文档超过 10MB 上限");
        if (bytes.length < 4
                || bytes[0] != ZIP_MAGIC[0] || bytes[1] != ZIP_MAGIC[1]
                || bytes[2] != ZIP_MAGIC[2] || bytes[3] != ZIP_MAGIC[3]) {
            return new Decoded(null, "不是有效的 .docx 文件");
        }
        return new Decoded(bytes, null);
    }
}
