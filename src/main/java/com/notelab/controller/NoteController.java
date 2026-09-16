package com.notelab.controller;

import com.notelab.dao.NoteDao;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B 端笔记：前缀 /api/notes，Notion 式 Markdown 笔记（保存/列表/编辑/查看）。
 * 正文以 Markdown 存 notes 表（content_md），渲染在前端完成（marked + DOMPurify）。
 * 全部要求 B 端登录（AuthUtil）；路由由 PermService 启动自动登记到 perm_routes。
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    /** 正文上限（UTF-8 字节数）：2MB */
    private static final long MAX_MD_BYTES = 2L * 1024 * 1024;
    /** 标题上限 */
    private static final int MAX_TITLE_LEN = 200;

    public static class NoteSaveReq { public String title; public String content_md; }

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
        body.put("items", NoteDao.listPaged(q, l, off));
        body.put("total", NoteDao.countFiltered(q));
        body.put("limit", l);
        body.put("offset", off);
        return ResponseEntity.ok(body);
    }

    /** 详情：含 Markdown 正文 */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        Map<String, Object> row = NoteDao.getById(id);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "笔记不存在"));
        return ResponseEntity.ok(row);
    }

    /** 新建笔记 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) NoteSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        Object idObj = me.get("id");
        Long createdBy = idObj instanceof Number n ? n.longValue() : null;
        long id = NoteDao.create(req.title.trim(), req.content_md, createdBy);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    /** 更新标题与正文 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) NoteSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!NoteDao.exists(id)) return ResponseEntity.status(404).body(Map.of("error", "笔记不存在"));
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        NoteDao.update(id, req.title.trim(), req.content_md);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!NoteDao.exists(id)) return ResponseEntity.status(404).body(Map.of("error", "笔记不存在"));
        NoteDao.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<Map<String, Object>> validateSave(NoteSaveReq req) {
        if (req == null || req.title == null || req.title.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
        }
        if (req.title.trim().length() > MAX_TITLE_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "标题最多 " + MAX_TITLE_LEN + " 字"));
        }
        if (req.content_md == null) req.content_md = "";
        if (req.content_md.getBytes(StandardCharsets.UTF_8).length > MAX_MD_BYTES) {
            return ResponseEntity.status(400).body(Map.of("error", "正文超过 2MB 上限"));
        }
        return null;
    }
}
