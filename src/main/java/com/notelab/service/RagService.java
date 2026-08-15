package com.notelab.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.notelab.common.AppConfig;

/**
 * 文档问答 RAG：内存文档库 + 磁盘持久化（data/uploads/*.txt）。
 * chunk_text / bigram 检索逻辑与 Python 版完全一致。
 */
public final class RagService {

    public static final class Doc {
        public final String text;
        public final List<String> chunks;

        public Doc(String text, List<String> chunks) {
            this.text = text;
            this.chunks = chunks;
        }
    }

    private static final Map<String, Doc> DOCS = new LinkedHashMap<>();
    private static final Pattern SPLIT_RE = Pattern.compile("(?<=[。！？!?；;\n])");
    private static final Pattern NAME_CLEAN_RE = Pattern.compile("[^\\w\\u4e00-\\u9fa5\\-]+", Pattern.UNICODE_CHARACTER_CLASS);

    private RagService() {}

    public static Path uploadDir() {
        Path p = AppConfig.dataDir().resolve("uploads");
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
        }
        return p;
    }

    /** 启动时从磁盘加载 *.txt（对应 Python _load_docs_from_disk） */
    public static synchronized void init() {
        Path dir = uploadDir();
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".txt")) continue;
                try {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    DOCS.put(fn.substring(0, fn.length() - 4), new Doc(text, chunkText(text)));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized void put(String name, String content) {
        DOCS.put(name, new Doc(content, chunkText(content)));
    }

    public static synchronized List<Map<String, Object>> docSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Doc> e : DOCS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("chunks", e.getValue().chunks.size());
            out.add(m);
        }
        return out;
    }

    public static synchronized boolean isEmpty() {
        return DOCS.isEmpty();
    }

    /** 文件名清洗：re.sub(r"[^\w\u4e00-\u9fa5\-]+", "_", name.strip()) or "文档" */
    public static String cleanName(String name) {
        String cleaned = NAME_CLEAN_RE.matcher(name.trim()).replaceAll("_");
        return cleaned.isEmpty() ? "文档" : cleaned;
    }

    /** chunk_text：按句末标点切分后打包为 ≤size 字符的块（与 Python 版逐行对齐） */
    public static List<String> chunkText(String text) {
        return chunkText(text, 200);
    }

    public static List<String> chunkText(String text, int size) {
        text = text.trim();
        if (text.isEmpty()) return List.of();
        String[] parts = SPLIT_RE.split(text, -1);
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            if (buf.length() + p.length() <= size) {
                buf.append(p);
            } else {
                if (buf.length() > 0) chunks.add(buf.toString().trim());
                while (p.length() > size) {
                    chunks.add(p.substring(0, size).trim());
                    p = p.substring(size);
                }
                buf = new StringBuilder(p);
            }
        }
        if (!buf.toString().trim().isEmpty()) chunks.add(buf.toString().trim());
        List<String> out = new ArrayList<>();
        for (String c : chunks) {
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    /** _bigrams */
    private static Set<String> bigrams(String s) {
        s = s.replaceAll("\\s+", "");
        Set<String> out = new HashSet<>();
        if (s.length() < 2) {
            if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /** rag_retrieve：bigram 重叠打分，top_k */
    public static synchronized List<Map<String, Object>> retrieve(String question, int topK) {
        Set<String> qg = bigrams(question);
        if (qg.isEmpty()) return List.of();
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map.Entry<String, Doc> e : DOCS.entrySet()) {
            List<String> chunks = e.getValue().chunks;
            for (int idx = 0; idx < chunks.size(); idx++) {
                Set<String> cg = bigrams(chunks.get(idx));
                if (cg.isEmpty()) continue;
                int inter = 0;
                for (String g : qg) {
                    if (cg.contains(g)) inter++;
                }
                if (inter == 0) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("doc", e.getKey());
                m.put("idx", idx);
                m.put("text", chunks.get(idx));
                m.put("score", BigDecimal.valueOf((double) inter / qg.size())
                        .setScale(3, RoundingMode.HALF_EVEN).doubleValue());
                scored.add(m);
            }
        }
        scored.sort(Comparator.comparingDouble((Map<String, Object> x) -> ((Number) x.get("score")).doubleValue()).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }
}
