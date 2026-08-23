package com.notelab.common;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行数据转换（对外 JSON 契约核心）：把 MyBatis-Plus 实体/注解 SQL 的 Map 结果
 * 转成与旧手写 JDBC 的 Db.row() 完全一致的出口格式：
 *  - LinkedHashMap，key 为 snake_case 小写，且严格按调用方传入的 fields 顺序
 *    （即原 SQL 的投影列序，前端响应逐字节对齐）；
 *  - LocalDateTime / java.sql.Timestamp 一律格式化为 'yyyy-MM-dd HH:mm:ss'
 *    （对齐 Python 的 str(datetime)）；
 *  - null 值保留。
 */
public final class RowUtil {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private RowUtil() {}

    /** 实体 → 行 Map（字段按 fields 顺序投影）；entity 为 null 时返回 null（对齐 queryOne 无结果语义） */
    public static Map<String, Object> row(Object entity, String... fields) {
        if (entity == null) return null;
        Map<String, Field> fm = fieldsOf(entity.getClass());
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (String f : fields) {
            Field fld = fm.get(f);
            if (fld == null) {
                throw new IllegalArgumentException("未知字段: " + entity.getClass().getSimpleName() + "." + f);
            }
            try {
                m.put(f, norm(fld.get(entity)));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return m;
    }

    /** 实体列表 → 行列表 */
    public static List<Map<String, Object>> rows(List<?> entities, String... fields) {
        List<Map<String, Object>> out = new ArrayList<>(entities.size());
        for (Object e : entities) out.add(row(e, fields));
        return out;
    }

    /**
     * 归一化注解 SQL（JOIN 等）返回的 Map 行：key 小写 + datetime 格式化，
     * 并按 fields 顺序重投影（MyBatis Map 结果不保证列序，这里重建确定性键序）。
     */
    public static Map<String, Object> norm(Map<String, Object> raw, String... fields) {
        if (raw == null) return null;
        LinkedHashMap<String, Object> lower = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            lower.put(e.getKey().toLowerCase(), norm(e.getValue()));
        }
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (String f : fields) m.put(f, lower.get(f));
        return m;
    }

    /** 归一化 Map 行列表 */
    public static List<Map<String, Object>> norms(List<Map<String, Object>> raws, String... fields) {
        List<Map<String, Object>> out = new ArrayList<>(raws.size());
        for (Map<String, Object> r : raws) out.add(norm(r, fields));
        return out;
    }

    /** datetime → 'yyyy-MM-dd HH:mm:ss' 字符串；其余原样返回 */
    public static Object norm(Object v) {
        if (v instanceof LocalDateTime l) return l.format(TS);
        if (v instanceof Timestamp t) return t.toLocalDateTime().format(TS);
        return v;
    }

    /** 类 → (snake_case 字段名 → Field) 缓存，沿继承链收集，跳过静态字段 */
    private static Map<String, Field> fieldsOf(Class<?> cls) {
        return FIELD_CACHE.computeIfAbsent(cls, c -> {
            LinkedHashMap<String, Field> m = new LinkedHashMap<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    m.putIfAbsent(camelToSnake(f.getName()), f);
                }
            }
            return m;
        });
    }

    private static String camelToSnake(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
