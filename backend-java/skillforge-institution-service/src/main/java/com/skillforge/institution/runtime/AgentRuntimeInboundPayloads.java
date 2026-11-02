package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 委派原输入的规范JSON编码，以及真实Plan工具响应的严格record解码。 */
final class AgentRuntimeInboundPayloads {
    private static final Set<String> PLAN_REVIEW_DECISIONS = Set.of("approve", "keep_planning");
    private static final JsonMapper RECORD_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    private AgentRuntimeInboundPayloads() { }

    /** 原Plan评审回复保留用户看到的精确版本；动作和等待归属由typed执行入口核实。 */
    record PlanReviewBody(@JsonDeserialize(using = DecimalRevisionDeserializer.class) String checkpointRevision,
            String decision, String feedback) {
        PlanReviewBody {
            revision(checkpointRevision);
            if (!PLAN_REVIEW_DECISIONS.contains(decision) || feedback == null)
                throw new IllegalArgumentException("plan_review_response_invalid");
        }
    }

    /** 版本只接受规范正十进制字符串，不把JSON数字强制转换成身份。 */
    static final class DecimalRevisionDeserializer extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING))
                throw context.wrongTokenException(parser, String.class, JsonToken.VALUE_STRING,
                        "checkpoint revision must be a decimal string");
            String value = parser.getText();
            revision(value);
            return value;
        }
    }

    static <T extends Record> T decode(String text, Class<T> type) throws IOException {
        T result = RECORD_JSON.readValue(text, type);
        if (result == null) throw new IllegalArgumentException("runtime response object required");
        return result;
    }

    private static void revision(String value) {
        if (value == null) throw new IllegalArgumentException("plan_review_revision_required");
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || !Long.toString(parsed).equals(value)) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("plan_review_revision_invalid");
        }
    }

    /** 编码 MESSAGE 输入；额外参数作为受信入口快照保存，不投影为模型 Tool arguments。 */
    static String inputText(String text, Map<String, Object> extraParameters) {
        // 技术尝试 trace 属于 InboundEnvelope，不参与业务输入指纹；合法重试可有新 HTTP trace。
        Map<String, Object> business = new java.util.TreeMap<>();
        if (extraParameters != null) business.putAll(extraParameters);
        business.remove("trace_id");
        business.remove("span_id");
        return object("content", List.of(Map.of("type", "text", "text", text == null ? "" : text)),
                "extraParameters", business);
    }

    /** 编码固定键序对象；字符串以外的值按结构化 JSON 写入，不把 Map/List 打成 Java toString。 */
    static String object(Object... keysAndValues) {
        if (keysAndValues == null || keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("canonical object fields must be pairs");
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (int index = 0; index < keysAndValues.length; index += 2) {
            Object keyObject = keysAndValues[index];
            Object value = keysAndValues[index + 1];
            if (!(keyObject instanceof String key) || key.isBlank() || value == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(jsonString(key)).append(':').append(jsonValue(value));
        }
        if (first) {
            throw new IllegalArgumentException("canonical object must contain a field");
        }
        return json.append('}').toString();
    }

    static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return jsonString(text);
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Float number) {
            if (number.isNaN() || number.isInfinite()) {
                throw new IllegalArgumentException("canonical number must be finite");
            }
            return Float.toString(number);
        }
        if (value instanceof Double number) {
            if (number.isNaN() || number.isInfinite()) {
                throw new IllegalArgumentException("canonical number must be finite");
            }
            return Double.toString(number);
        }
        if (value instanceof Map<?, ?> map) {
            return objectFromMap(map);
        }
        if (value instanceof Collection<?> items) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append(jsonValue(item));
            }
            return json.append(']').toString();
        }
        return jsonString(String.valueOf(value));
    }

    private static String objectFromMap(Map<?, ?> map) {
        TreeMap<String, Object> ordered = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            ordered.put(key, entry.getValue());
        }
        if (ordered.isEmpty()) {
            return "{}";
        }
        Object[] fields = new Object[ordered.size() * 2];
        int index = 0;
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            fields[index++] = entry.getKey();
            fields[index++] = entry.getValue();
        }
        return object(fields);
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
