package com.notelab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 全局共享 ObjectMapper（等价 Python json.dumps(..., ensure_ascii=False)：UTF-8 原文输出）。 */
public final class JsonUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static String write(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(String s) throws Exception {
        return MAPPER.readTree(s);
    }
}
