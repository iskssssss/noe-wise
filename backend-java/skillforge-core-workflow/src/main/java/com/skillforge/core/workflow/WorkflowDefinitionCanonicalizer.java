package com.skillforge.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.shared.security.TokenHash;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic content addressing for the complete, sanitized published definition. */
final class WorkflowDefinitionCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private WorkflowDefinitionCanonicalizer() {
    }

    static String sha256(Map<String, Object> definition) {
        return TokenHash.sha256Hex(canonicalJson(definition));
    }

    static String canonicalJson(Map<String, Object> definition) {
        try {
            return MAPPER.writeValueAsString(normalize(definition));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Workflow definition cannot be normalized", exception);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                normalized.add(normalize(item));
            }
            return normalized;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                normalized.add(normalize(java.lang.reflect.Array.get(value, index)));
            }
            return normalized;
        }
        return value;
    }
}
