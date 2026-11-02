package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 沿平台 schema 子集校验约束。MCP/OpenAPI 超集先投影进子集：未知关键字丢弃，
 * {@code const} 转为枚举，{@code nullable} 转为类型联合；{@code oneOf}/{@code anyOf}
 * 保留分支及同级约束，分别要求恰好一个或至少一个分支匹配。联合内无法解析的引用、
 * allOf 按交集校验，无法保真的分支显式拒绝；显式空 schema 仍合法。
 * 引用目标与兄弟同名约束不一致时拒绝，不以覆盖模拟尚未支持的约束交集。
 */
final class SessionRuntimeJsonSchema {
    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");
    private static final Set<String> SCHEMA_URIS = Set.of(
            "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2019-09/schema",
            "https://json-schema.org/draft/2020-12/schema");
    private static final Set<String> CONSTRAINTS = Set.of(
            "type", "enum", "const", "properties", "required", "additionalProperties", "items",
            "minLength", "maxLength", "minimum", "maximum", "nullable", "oneOf", "anyOf", "allOf", "$ref");
    private static final int MAX_DEPTH = 32;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private SessionRuntimeJsonSchema() { }

    /** 根必须是 object；投影失败或形态非法时仍抛出 {@code session_runtime_payload_invalid}。 */
    static JsonNode project(JsonNode schema) {
        require(schema != null && schema.isObject());
        JsonNode projected = projectNode(schema, schema, new LinkedHashSet<>(), 0, false, false);
        schema(projected);
        return projected;
    }

    static void schema(JsonNode schema) {
        require(schema.isObject());
        schema.fields().forEachRemaining(entry -> {
            var value = entry.getValue();
            switch (entry.getKey()) {
                case "title", "description", "$comment" -> require(value.isTextual());
                case "default", "examples" -> { }
                case "$schema" -> require(value.isTextual() && SCHEMA_URIS.contains(value.textValue()));
                case "type" -> {
                    if (value.isTextual()) { require(TYPES.contains(value.textValue())); }
                    else {
                        require(value.isArray() && !value.isEmpty());
                        var types = new HashSet<String>();
                        for (var type : value) { require(type.isTextual() && TYPES.contains(type.textValue()) && types.add(type.textValue())); }
                    }
                }
                case "enum" -> require(value.isArray() && !value.isEmpty());
                case "properties" -> {
                    require(value.isObject());
                    value.elements().forEachRemaining(SessionRuntimeJsonSchema::schema);
                }
                case "oneOf", "anyOf", "allOf" -> {
                    require(value.isArray() && !value.isEmpty());
                    value.elements().forEachRemaining(SessionRuntimeJsonSchema::schema);
                }
                case "required" -> {
                    require(value.isArray());
                    var names = new HashSet<String>();
                    for (var name : value) { require(name.isTextual() && names.add(name.textValue())); }
                }
                case "additionalProperties" -> { if (!value.isBoolean()) { schema(value); } }
                case "items" -> schema(value);
                case "minLength", "maxLength" -> require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0);
                case "minimum", "maximum" -> require(value.isNumber());
                default -> throw SessionRuntimePayloadJson.invalid();
            }
        });
    }

    static void validate(JsonNode schema, JsonNode value) {
        require(firstViolation(schema, value, "") == null);
    }

    /** 校验与诊断共用同一递归及首错顺序；只携带受限路径和固定关键字。 */
    static SessionRuntimePayloads.SchemaViolation firstViolation(JsonNode schema, JsonNode value, String path) {
        var type = schema.get("type");
        if (type != null) {
            boolean matches = type.isTextual() && type(value, type.textValue());
            if (type.isArray()) { for (var item : type) { matches |= type(value, item.textValue()); } }
            if (!matches) return violation(path, "type");
        }
        if (schema.has("enum")) {
            boolean matches = false;
            for (var candidate : schema.get("enum")) { matches |= equal(candidate, value); }
            if (!matches) return violation(path, "enum");
        }
        if (value.isTextual()) {
            int size = value.textValue().codePointCount(0, value.textValue().length());
            if (schema.has("minLength") && size < schema.get("minLength").intValue()) return violation(path, "minLength");
            if (schema.has("maxLength") && size > schema.get("maxLength").intValue()) return violation(path, "maxLength");
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) return violation(path, "minimum");
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) return violation(path, "maximum");
        }
        if (value.isObject()) {
            if (schema.has("required")) for (var name : schema.get("required")) {
                if (!value.has(name.textValue())) return violation(childPath(path, name.textValue()), "required");
            }
            var properties = schema.path("properties");
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String child = childPath(path, entry.getKey());
                SessionRuntimePayloads.SchemaViolation failure = null;
                if (properties.has(entry.getKey())) failure = firstViolation(properties.get(entry.getKey()), entry.getValue(), child);
                else if (schema.has("additionalProperties")) {
                    var additional = schema.get("additionalProperties");
                    if (additional.isBoolean()) {
                        if (!additional.booleanValue()) return violation(child, "additionalProperties");
                    } else failure = firstViolation(additional, entry.getValue(), child);
                }
                if (failure != null) return failure;
            }
        }
        if (value.isArray() && schema.has("items")) {
            for (int index = 0; index < value.size(); index++) {
                var failure = firstViolation(schema.get("items"), value.get(index), childPath(path, Integer.toString(index)));
                if (failure != null) return failure;
            }
        }
        if (schema.has("allOf")) {
            for (JsonNode branch : schema.get("allOf")) {
                var failure = firstViolation(branch, value, path);
                if (failure != null) return failure;
            }
        }
        // 联合约束与同级字段约束同时成立；只报告固定组合子，不回显分支参数或枚举值。
        for (String keyword : List.of("oneOf", "anyOf")) {
            if (!schema.has(keyword)) continue;
            int matches = 0;
            for (JsonNode branch : schema.get(keyword)) {
                if (firstViolation(branch, value, path) == null) matches++;
                if (("anyOf".equals(keyword) && matches > 0) || matches > 1) break;
            }
            if (matches == 0 || ("oneOf".equals(keyword) && matches != 1)) return violation(path, keyword);
        }
        return null;
    }

    private static SessionRuntimePayloads.SchemaViolation violation(String path, String keyword) {
        return new SessionRuntimePayloads.SchemaViolation(path, keyword);
    }

    /** 动态键仅展示短 ASCII 标识符；其他名称和超长路径使用固定占位，不回显凭据式键。 */
    private static String childPath(String parent, String name) {
        if (parent.endsWith("/_")) return parent;
        String segment = name.matches("[A-Za-z_][A-Za-z0-9_]{0,31}|[0-9]{1,10}") ? name : "_";
        String path = parent + "/" + segment;
        if (path.length() <= 256) return path;
        int boundary = Math.min(parent.length(), 254);
        while (boundary > 0 && parent.charAt(boundary - 1) != '/') boundary--;
        return parent.substring(0, Math.max(0, boundary - 1)) + "/_";
    }
    private static JsonNode projectNode(JsonNode node, JsonNode root, Set<String> refs, int depth, boolean strictChoice, boolean strictIntersection) {
        if (node == null || !node.isObject() || depth > MAX_DEPTH) {
            require(!strictChoice && !strictIntersection);
            return empty();
        }
        boolean intersection = strictIntersection || node.has("allOf");
        boolean strict = strictChoice || hasChoice(node) || intersection;
        if (intersection) {
            node.fieldNames().forEachRemaining(key -> require(CONSTRAINTS.contains(key)
                    || Set.of("title", "description", "$comment", "default", "examples", "$schema", "$defs", "definitions").contains(key)));
            require(!(node.has("const") && node.has("enum")));
            if (node.path("nullable").asBoolean(false)) require(node.has("type"));
        }
        if (node.has("$ref")) { return projectRef(node, root, refs, depth, strict, intersection); }
        ObjectNode out = NODES.objectNode();
        node.fields().forEachRemaining(entry -> copyField(out, entry.getKey(), entry.getValue(), root, refs, depth, strict, intersection));
        return out;
    }

    private static JsonNode projectRef(JsonNode node, JsonNode root, Set<String> refs, int depth, boolean strictChoice, boolean strictIntersection) {
        JsonNode refNode = node.get("$ref");
        if (!refNode.isTextual()) { require(!strictChoice); return empty(); }
        String pointer = refNode.textValue();
        if (!refs.add(pointer)) { require(!strictChoice); return empty(); }
        JsonNode resolved = resolve(root, pointer);
        if (resolved == null || !resolved.isObject()) {
            refs.remove(pointer);
            require(!strictChoice);
            return empty();
        }
        boolean strict = strictChoice || hasChoice(resolved);
        ObjectNode merged = resolved.deepCopy();
        node.fields().forEachRemaining(entry -> {
            if (!"$ref".equals(entry.getKey())) {
                if (CONSTRAINTS.contains(entry.getKey()) && merged.has(entry.getKey()))
                    require(equal(merged.get(entry.getKey()), entry.getValue()));
                merged.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        JsonNode projected = projectNode(merged, root, refs, depth + 1, strict, strictIntersection);
        refs.remove(pointer);
        return projected;
    }

    private static boolean readTypes(JsonNode schema, Set<String> types) {
        JsonNode type = schema.get("type");
        if (type == null) { return false; }
        if (type.isTextual()) {
            if (!TYPES.contains(type.textValue())) { throw SessionRuntimePayloadJson.invalid(); }
            types.add(type.textValue());
            return true;
        }
        if (!type.isArray() || type.isEmpty()) { throw SessionRuntimePayloadJson.invalid(); }
        for (JsonNode item : type) {
            if (!item.isTextual() || !TYPES.contains(item.textValue())) { throw SessionRuntimePayloadJson.invalid(); }
            types.add(item.textValue());
        }
        return true;
    }

    private static void copyField(ObjectNode out, String key, JsonNode value, JsonNode root, Set<String> refs, int depth, boolean strictChoice, boolean strictIntersection) {
        switch (key) {
            case "title", "description", "$comment" -> {
                if (!value.isTextual()) { throw SessionRuntimePayloadJson.invalid(); }
                out.set(key, value.deepCopy());
            }
            case "default", "examples" -> out.set(key, value.deepCopy());
            case "$schema" -> {
                if (!value.isTextual() || !SCHEMA_URIS.contains(value.textValue())) { throw SessionRuntimePayloadJson.invalid(); }
                out.set(key, value.deepCopy());
            }
            case "type" -> copyType(out, value);
            case "enum" -> {
                if (!value.isArray() || value.isEmpty()) { throw SessionRuntimePayloadJson.invalid(); }
                out.set(key, value.deepCopy());
            }
            case "const" -> {
                if (!out.has("enum")) {
                    ArrayNode enums = NODES.arrayNode();
                    enums.add(value.deepCopy());
                    out.set("enum", enums);
                }
            }
            case "properties" -> {
                if (!value.isObject()) { require(!strictIntersection); return; }
                ObjectNode properties = NODES.objectNode();
                value.fields().forEachRemaining(property ->
                        properties.set(property.getKey(), projectNode(property.getValue(), root, refs, depth + 1, strictChoice, strictIntersection)));
                out.set(key, properties);
            }
            case "oneOf", "anyOf", "allOf" -> {
                require(value.isArray() && !value.isEmpty());
                ArrayNode choices = NODES.arrayNode();
                for (JsonNode branch : value) {
                    require(branch.isObject() && depth < MAX_DEPTH);
                    var projected = projectNode(branch, root, refs, depth + 1, true, strictIntersection || "allOf".equals(key));
                    if (!"allOf".equals(key) || !annotationOnly(projected)) choices.add(projected);
                }
                if (!choices.isEmpty()) out.set(key, choices);
            }
            case "required" -> {
                if (!value.isArray()) { throw SessionRuntimePayloadJson.invalid(); }
                out.set(key, value.deepCopy());
            }
            case "additionalProperties" -> {
                if (value.isBoolean()) { out.set(key, value.deepCopy()); }
                else { out.set(key, projectNode(value, root, refs, depth + 1, strictChoice, strictIntersection)); }
            }
            case "items" -> {
                if (value.isObject()) { out.set(key, projectNode(value, root, refs, depth + 1, strictChoice, strictIntersection)); }
                else if (value.isArray() && !value.isEmpty()) { require(!strictIntersection); out.set(key, projectNode(value.get(0), root, refs, depth + 1, strictChoice, strictIntersection)); }
                else { require(!strictIntersection); }
            }
            case "minLength", "maxLength" -> {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) { throw SessionRuntimePayloadJson.invalid(); }
                out.set(key, value.deepCopy());
            }
            case "minimum", "maximum" -> {
                if (!value.isNumber()) { throw SessionRuntimePayloadJson.invalid(); }
                out.set(key, value.deepCopy());
            }
            case "nullable" -> {
                if (strictIntersection) require(value.isBoolean());
                if (value.isBoolean() && value.booleanValue()) { addType(out, "null"); }
            }
            default -> { }
        }
    }

    private static boolean annotationOnly(JsonNode schema) {
        var names = schema.fieldNames();
        while (names.hasNext()) {
            if (!Set.of("title", "description", "$comment", "default", "examples", "$schema").contains(names.next())) return false;
        }
        return true;
    }

    /** 只识别当前已支持的选择联合，不把未知关键字的容忍策略扩大为完整标准解释。 */
    private static boolean hasChoice(JsonNode node) {
        return node.has("oneOf") || node.has("anyOf");
    }

    private static void copyType(ObjectNode out, JsonNode value) {
        var types = new LinkedHashSet<String>();
        if (value.isTextual()) {
            if (!TYPES.contains(value.textValue())) { throw SessionRuntimePayloadJson.invalid(); }
            types.add(value.textValue());
        } else if (value.isArray() && !value.isEmpty()) {
            for (JsonNode item : value) {
                if (!item.isTextual() || !TYPES.contains(item.textValue())) { throw SessionRuntimePayloadJson.invalid(); }
                types.add(item.textValue());
            }
        } else { throw SessionRuntimePayloadJson.invalid(); }
        if (out.has("type")) { readTypes(out, types); }
        writeTypes(out, types);
    }

    private static void addType(ObjectNode out, String type) {
        var types = new LinkedHashSet<String>();
        if (out.has("type")) { readTypes(out, types); }
        types.add(type);
        writeTypes(out, types);
    }

    private static void writeTypes(ObjectNode out, Set<String> types) {
        if (types.size() == 1) { out.put("type", types.iterator().next()); return; }
        ArrayNode array = NODES.arrayNode();
        types.forEach(array::add);
        out.set("type", array);
    }

    private static JsonNode resolve(JsonNode root, String ref) {
        if (ref == null || !ref.startsWith("#")) { return null; }
        String path = ref.substring(1);
        if (path.isEmpty()) { return root; }
        if (!path.startsWith("/")) { return null; }
        JsonNode current = root;
        for (String raw : path.substring(1).split("/")) {
            if (current == null) { return null; }
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current.isObject()) { current = current.get(key); }
            else if (current.isArray() && key.chars().allMatch(Character::isDigit)) {
                current = current.get(Integer.parseInt(key));
            } else { return null; }
        }
        return current;
    }

    private static ObjectNode empty() { return NODES.objectNode(); }

    private static boolean equal(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) { return left.decimalValue().compareTo(right.decimalValue()) == 0; }
        if (left.isContainerNode() && right.isContainerNode()) {
            if (left.getNodeType() != right.getNodeType() || left.size() != right.size()) { return false; }
            if (left.isArray()) {
                for (int i = 0; i < left.size(); i++) { if (!equal(left.get(i), right.get(i))) { return false; } }
            } else {
                var names = left.fieldNames();
                while (names.hasNext()) { var name = names.next(); if (!right.has(name) || !equal(left.get(name), right.get(name))) { return false; } }
            }
            return true;
        }
        return left.equals(right);
    }

    private static boolean type(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "boolean" -> value.isBoolean();
            case "null" -> value.isNull(); case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }

    private static void require(boolean condition) { if (!condition) { throw SessionRuntimePayloadJson.invalid(); } }
}
