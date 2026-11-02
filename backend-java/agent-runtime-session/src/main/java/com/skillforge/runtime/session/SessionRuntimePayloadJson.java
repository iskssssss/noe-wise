package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.json.RuntimeJson;

/** Session 动态 payload 与领域 JsonNode 消费者之间的树映射。语法与预算归公共 JSON 边界。 */
final class SessionRuntimePayloadJson {
    private static final ObjectMapper READ_MAPPING = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final ObjectMapper WRITE_MAPPING = READ_MAPPING.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private SessionRuntimePayloadJson() { }

    static JsonNode parse(String text, CodecLimits limits) {
        try {
            return READ_MAPPING.valueToTree(RuntimeJson.parse(text, limits));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /** 保留领域树字段顺序及 Map 的稳定键序；树映射不重新解析文本。 */
    static String write(Object value) {
        try {
            return RuntimeJson.write(WRITE_MAPPING.convertValue(value, Object.class));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("session_runtime_payload_invalid");
    }
}
