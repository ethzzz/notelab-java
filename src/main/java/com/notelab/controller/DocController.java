package com.notelab.controller;

import com.notelab.dao.DocumentDao;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B 端文档编辑：前缀 /api/docs。
 * 文档正文以富文本 HTML 存 documents 表；导入 .docx 时服务端用 POI 解析为 HTML（复杂排版降级）。
 * 全部要求 B 端登录（AuthUtil）；路由由 PermService 启动自动登记进 perm_routes。
 */
@RestController
@RequestMapping("/api/docs")
public class DocController {

    /** 导入正文上限（Base64 解码后字节数）：5MB */
    private static final long MAX_DOC_BYTES = 5L * 1024 * 1024;
    /** 标题上限 */
    private static final int MAX_TITLE_LEN = 200;

    public static class DocSaveReq { public String title; public String content_html; }
    public static class DocImportReq { public String name; public String data; }

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

    /** 详情：含正文 */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        Map<String, Object> row = DocumentDao.getById(id);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "文档不存在"));
        return ResponseEntity.ok(row);
    }

    /** 新建文档 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) DocSaveReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        ResponseEntity<Map<String, Object>> bad = validateSave(req);
        if (bad != null) return bad;
        Object idObj = me.get("id");
        Long createdBy = idObj instanceof Number n ? n.longValue() : null;
        long id = DocumentDao.create(req.title.trim(), req.content_html, createdBy);
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
        DocumentDao.update(id, req.title.trim(), req.content_html);
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
        if (req.content_html == null) req.content_html = "";
        return null;
    }

    // ================= docx 导入 =================

    /** 导入 .docx（base64）：POI 解析为 HTML 返回，不落库（由前端进编辑器后自行保存） */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importDocx(@RequestBody(required = false) DocImportReq req,
                                                          HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (req == null || req.data == null || req.data.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "缺少文件内容"));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(req.data.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", "文件内容不是合法的 Base64"));
        }
        if (bytes.length == 0) return ResponseEntity.status(400).body(Map.of("error", "文件为空"));
        if (bytes.length > MAX_DOC_BYTES) {
            return ResponseEntity.status(400).body(Map.of("error", "文档超过 5MB 上限"));
        }
        String html;
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    sb.append(paragraphToHtml(p));
                } else if (el instanceof XWPFTable t) {
                    sb.append(tableToHtml(t));
                }
            }
            html = sb.toString();
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "docx 解析失败，请确认文件为有效的 .docx"));
        }
        if (html.isBlank()) html = "<p><br></p>";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("title", cleanTitle(req.name));
        body.put("html", html);
        return ResponseEntity.ok(body);
    }

    /** 文件名去扩展名与非法字符作为默认标题 */
    private static String cleanTitle(String name) {
        String t = name == null ? "" : name.trim();
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        if (t.toLowerCase().endsWith(".docx")) t = t.substring(0, t.length() - 5);
        t = t.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (t.isEmpty()) t = "未命名文档";
        if (t.length() > MAX_TITLE_LEN) t = t.substring(0, MAX_TITLE_LEN);
        return t;
    }

    // ---- POI → HTML（降级策略：标题级别/加粗/斜体/下划线/颜色/列表/表格，图片转文字占位） ----

    private static String paragraphToHtml(XWPFParagraph p) {
        String inner = runsToHtml(p.getRuns());
        String tag = headingTag(p);
        if (tag != null) {
            return "<" + tag + ">" + inner + "</" + tag + ">";
        }
        if (p.getNumIlvl() != null) {
            // 有序/无序列表交给前端编辑器还原：统一输出带符号的普通段落（简化策略）
            return "<p>" + inner + "</p>";
        }
        return "<p>" + (inner.isEmpty() ? "<br>" : inner) + "</p>";
    }

    /** 依据样式 ID 推断标题级别（常见中英文模板：Heading1/1/标题1 等） */
    private static String headingTag(XWPFParagraph p) {
        String styleId = p.getStyleID();
        if (styleId == null) return null;
        String s = styleId.toLowerCase();
        if (s.matches(".*heading\\s*1$") || s.equals("1") || s.contains("标题1")) return "h1";
        if (s.matches(".*heading\\s*2$") || s.equals("2") || s.contains("标题2")) return "h2";
        if (s.matches(".*heading\\s*3$") || s.equals("3") || s.contains("标题3")) return "h3";
        return null;
    }

    private static String runsToHtml(List<XWPFRun> runs) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : runs) {
            if (!r.getEmbeddedPictures().isEmpty()) {
                sb.append("<em>[图片]</em>");
            }
            String text = escapeHtml(r.text());
            if (text.isEmpty()) continue;
            if (r.isBold()) text = "<strong>" + text + "</strong>";
            if (r.isItalic()) text = "<em>" + text + "</em>";
            if (r.getUnderline() != UnderlinePatterns.NONE) text = "<u>" + text + "</u>";
            String color = r.getColor();
            if (color != null && color.matches("[0-9a-fA-F]{6}")) {
                text = "<span style=\"color:#" + color + "\">" + text + "</span>";
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static String tableToHtml(XWPFTable t) {
        StringBuilder sb = new StringBuilder("<table>");
        for (XWPFTableRow row : t.getRows()) {
            sb.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append("<td>");
                List<XWPFParagraph> ps = cell.getParagraphs();
                for (int i = 0; i < ps.size(); i++) {
                    if (i > 0) sb.append("<br>");
                    sb.append(runsToHtml(ps.get(i).getRuns()));
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
