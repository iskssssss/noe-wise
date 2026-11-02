package com.skillforge.core.capability.connector;

import java.util.List;
import java.util.Map;

/** 平台工具 Schema 的三个基础结构；领域字段与约束保留在所属工具定义中。 */
final class ToolSchemas {
    private ToolSchemas() { }

    static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required,
                "additionalProperties", false);
    }

    static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    static Map<String, Object> property(String type) {
        return Map.of("type", type);
    }
}
