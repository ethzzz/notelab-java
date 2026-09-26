package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 爬塔素材清单：GET /api/spire-assets/catalog（B 端登录可见，只读）。
 *
 * <p>为什么要后端扫盘：B 端浏览器**读不到 C 端仓库**（两个独立 Next 应用、两个 basePath），
 * 而素材的真相源就是 C 端 public/spire 下的真实文件。后端与 C 端同机部署，
 * 因此直接扫盘下发，而不是在 Java 里再手抄一份常量清单（抄一份就会漂移，加素材必须改 Java）。
 *
 * <p>返回结构：
 * <pre>
 * { available, root, urlPrefix, warning?, manifestVersion?, total,
 *   groups: [ { key, label, count, items: [ {name, rel, url, ext, kind, bytes, meta?} ] } ] }
 * </pre>
 *
 * <p>安全边界：**只读遍历配置目录**，不接受任何路径入参（故无穿越风险），
 * 深度上限 3 层、条目上限 2000、跳过点开头的隐藏项。目录不存在时返回 available=false 而不报错
 * —— 本地开发或 C 端尚未部署时不该把 B 端页面打挂。
 */
@RestController
@RequestMapping("/api/spire-assets")
public class SpireAssetController {

    /** 遍历深度上限（根 + 2 层足够：art/ / png/ / svg/ 都是单层） */
    private static final int MAX_DEPTH = 3;
    /** 条目上限，防呆：素材目录被误指向大目录时不至于把响应撑爆 */
    private static final int MAX_ITEMS = 2000;

    /** 目录 → 中文分组名；未登记的目录直接用其相对路径当名字 */
    private static final Map<String, String> GROUP_LABEL = Map.of(
            "art", "地图节点整图（位图 · 已接管节点与连线渲染）",
            "png", "素材包 2 倍图（矢量层对应的位图）",
            "svg", "素材包矢量源",
            "", "根目录（清单 / 配置，非素材）");

    /**
     * 扩展名 → 素材大类（决定 B 端下拉是否把它当图片候选）。
     *
     * <p>⚠️ 不能写成 `Map.of(...)`：11 组键值 = 22 个参数，**超过了 Map.of 的 10 组上限**
     * （超了报 "no suitable method found for of(...)"，而不是"参数太多"，很容易看错）。
     * 条目一多就该用构造器去填。
     */
    private static final Map<String, String> KIND_BY_EXT = buildKindByExt();

    private static Map<String, String> buildKindByExt() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String e : new String[]{"png", "jpg", "jpeg", "webp", "gif", "svg", "avif"}) m.put(e, "image");
        for (String e : new String[]{"mp3", "ogg", "wav", "m4a"}) m.put(e, "audio");
        return Map.copyOf(m);
    }

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> catalog(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();

        Path root = Paths.get(AppConfig.spireAssetRoot()).toAbsolutePath().normalize();
        String urlBase = trimSlash(AppConfig.spireAssetUrlPrefix()) + "/spire";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root.toString());
        out.put("urlPrefix", urlBase);

        if (!Files.isDirectory(root)) {
            out.put("available", false);
            out.put("warning", "素材目录不存在：" + root
                    + "（可用环境变量 SPIRE_ASSET_ROOT 指定 C 端 public/spire 的实际路径）");
            out.put("total", 0);
            out.put("groups", List.of());
            return ResponseEntity.ok(out);
        }

        // manifest.json 是按相对路径登记元数据的（png/icon-normal.png、art/icon-boss.png…）
        Map<String, Map<String, Object>> meta = loadManifestMeta(root);
        out.put("manifestVersion", manifestVersion(root));

        // key = 相对目录（"" 表示根），保持插入顺序
        Map<String, List<Map<String, Object>>> byDir = new LinkedHashMap<>();
        int[] total = {0};
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isHidden(p, root))
                    .sorted()
                    .forEach(p -> {
                        if (total[0] >= MAX_ITEMS) return;
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
                        String name = p.getFileName().toString();
                        String ext = extOf(name);

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", name);
                        item.put("rel", rel);
                        item.put("url", urlBase + "/" + rel);
                        item.put("ext", ext);
                        item.put("kind", KIND_BY_EXT.getOrDefault(ext, "other"));
                        try {
                            item.put("bytes", Files.size(p));
                        } catch (IOException ignored) {
                            item.put("bytes", 0);
                        }
                        Map<String, Object> m = meta.get(rel);
                        if (m != null && !m.isEmpty()) item.put("meta", m);

                        byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(item);
                        total[0]++;
                    });
        } catch (IOException e) {
            out.put("available", false);
            out.put("warning", "遍历素材目录失败：" + e.getMessage());
            out.put("total", 0);
            out.put("groups", List.of());
            return ResponseEntity.ok(out);
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byDir.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", e.getKey().isEmpty() ? "__root__" : e.getKey());
            g.put("dir", e.getKey());
            g.put("label", GROUP_LABEL.getOrDefault(e.getKey(), e.getKey()));
            g.put("count", e.getValue().size());
            g.put("items", e.getValue());
            groups.add(g);
        }

        boolean truncated = total[0] >= MAX_ITEMS;
        out.put("available", true);
        out.put("total", total[0]);
        out.put("groups", groups);
        if (truncated) out.put("warning", "素材数量超过上限 " + MAX_ITEMS + "，已截断");
        return ResponseEntity.ok(out);
    }

    /** 相对路径 → 该素材在 manifest 里的元数据（name/kind/layer/accent/notes），取不到就为空 */
    private static Map<String, Map<String, Object>> loadManifestMeta(Path root) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path mf = root.resolve("manifest.json");
        if (!Files.isRegularFile(mf)) return out;
        try {
            JsonNode node = JsonUtil.parse(Files.readString(mf));
            JsonNode arr = node.get("assets");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode a : arr) {
                Map<String, Object> m = new LinkedHashMap<>();
                String id = text(a, "id");
                if (!id.isEmpty()) m.put("id", id);
                String nm = text(a, "name");
                if (!nm.isEmpty()) m.put("name", nm);
                String kind = text(a, "kind");
                if (!kind.isEmpty()) m.put("kind", kind);
                String layer = text(a, "layer");
                if (!layer.isEmpty()) m.put("layer", layer);
                String accent = text(a, "accent");
                if (!accent.isEmpty()) m.put("accent", accent);
                String notes = text(a, "notes");
                if (!notes.isEmpty()) m.put("notes", notes);
                if (m.isEmpty()) continue;
                // 三条登记路径都映射到同一条元数据（同一素材的 svg / png / art 三种形态）
                for (String key : List.of("png", "svg", "art")) {
                    String rel = text(a, key);
                    if (!rel.isEmpty()) out.putIfAbsent(rel, m);
                }
            }
        } catch (Exception ignored) {
            // manifest 坏了不该让整个清单失败——没有元数据照样能选素材
        }
        return out;
    }

    private static String manifestVersion(Path root) {
        Path mf = root.resolve("manifest.json");
        if (!Files.isRegularFile(mf)) return "";
        try {
            return text(JsonUtil.parse(Files.readString(mf)), "version");
        } catch (Exception e) {
            return "";
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }

    /** 点开头的隐藏文件，以及相对路径里任一段以点开头 */
    private static boolean isHidden(Path p, Path root) {
        for (Path seg : root.relativize(p)) {
            String s = seg.toString();
            if (s.startsWith(".")) return true;
        }
        return false;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String trimSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
