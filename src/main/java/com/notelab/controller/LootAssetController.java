package com.notelab.controller;

import com.notelab.common.AppConfig;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * 摸金物品图片清单：GET /api/loot-assets（B 端登录可见，只读）。
 *
 * <p>为什么后端扫盘：B 端浏览器<b>读不到 C 端仓库</b>（两个独立 Next 应用），而图片真相源
 * 就是 C 端 public/loot 下的真实文件。后端与 C 端同机部署，直接扫盘下发，
 * 而不是在 Java 里手抄一份常量清单（抄一份就会漂移：加图必须改 Java）。
 *
 * <p>返回结构（刻意做扁，B 端只要一个字符串数组就能喂 Select）：
 * <pre>{ available, root, urlPrefix, files: ["/loot/gold.png", ...], warning? }</pre>
 *
 * <p>安全边界：<b>只读遍历配置目录</b>，不接受任何路径入参（故无穿越风险）；
 * 深度上限 3 层、条目上限 500、只收图片扩展名、跳过点开头的隐藏项。
 * 目录不存在返回 available=false + 空 files，<b>不抛错</b> —— 本地开发或 C 端尚未部署时
 * 不该把「物品配置」页打挂（B 端静默退回 emoji）。
 */
@RestController
@RequestMapping("/api/loot-assets")
public class LootAssetController {

    private static final int MAX_DEPTH = 3;
    private static final int MAX_ITEMS = 500;

    /** 只收这些扩展名（SVG 也算图，但 C 端 <img> 能直接吃） */
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif", "svg", "avif");

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();

        Path root = Paths.get(AppConfig.lootAssetRoot()).toAbsolutePath().normalize();
        String base = trimSlash(AppConfig.lootAssetUrlPrefix()) + "/loot";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root.toString());
        out.put("urlPrefix", base);

        if (!Files.isDirectory(root)) {
            out.put("available", false);
            out.put("warning", "图片目录不存在：" + root + "（可用环境变量 LOOT_ASSET_ROOT 指定 C 端 public/loot 的实际路径）");
            out.put("files", List.of());
            out.put("total", 0);
            return ResponseEntity.ok(out);
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isHidden(p, root))
                    .sorted()
                    .forEach(p -> {
                        if (files.size() >= MAX_ITEMS) return;
                        String name = p.getFileName().toString();
                        if (!IMAGE_EXT.contains(extOf(name))) return;
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        files.add(base + "/" + rel);
                    });
        } catch (IOException e) {
            out.put("available", false);
            out.put("warning", "遍历图片目录失败：" + e.getMessage());
            out.put("files", List.of());
            out.put("total", 0);
            return ResponseEntity.ok(out);
        }

        out.put("available", true);
        out.put("files", files);
        out.put("total", files.size());
        if (files.size() >= MAX_ITEMS) out.put("warning", "图片数量超过上限 " + MAX_ITEMS + "，已截断");
        return ResponseEntity.ok(out);
    }

    /** 相对路径里任一段以点开头（含隐藏文件） */
    private static boolean isHidden(Path p, Path root) {
        for (Path seg : root.relativize(p)) {
            if (seg.toString().startsWith(".")) return true;
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
