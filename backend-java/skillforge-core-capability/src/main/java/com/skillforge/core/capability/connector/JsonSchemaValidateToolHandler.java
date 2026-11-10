package com.skillforge.core.capability.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 校验 JSON Schema 的平台支持子集。
 *
 * <p>支持 type、enum、properties、required、additionalProperties、items、minLength、maxLength、
 * minimum、maximum、minItems 与 maxItems；不支持的关键字会作为 schema 错误返回，避免静默忽略约束。
 */
@Component
public class JsonSchemaValidateToolHandler implements PlatformToolHandler {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ERRORS = 100;
    private static final String MIN_ITEMS = "minItems";
    private static final String MAX_ITEMS = "maxItems";

    private final ObjectMapper objectMapper;

    public JsonSchemaValidateToolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolName() {
        return "json_schema_validate";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        if (args == null || !args.containsKey("value") || !(args.get("schema") instanceof Map<?, ?> schema)) {
            return Map.of("success", false, "error", "value 与 schema（对象）为必填参数");
        }
        return validateValue(args.get("value"), schema);
    }

    /**
     * 校验任意值是否满足给定 schema，不引入运行时用户身份。
     *
     * @param value  待校验值
     * @param schema JSON Schema 对象
     * @return 校验结果
     */
    public Map<String, Object> validateValue(Object value, Map<?, ?> schema) {
        List<String> errors = new ArrayList<>();
        validate(objectMapper.valueToTree(value), objectMapper.valueToTree(schema), "$", 0, errors);
        return Map.of("success", true, "valid", errors.isEmpty(), "errors", errors);
    }

    private void validate(JsonNode value, JsonNode schema, String path, int depth, List<String> errors) {
        if (errors.size() >= MAX_ERRORS) {
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add(path + ": schema 嵌套深度不得超过 " + MAX_DEPTH);
            return;
        }
        if (!schema.isObject()) {
            errors.add(path + ": schema 必须是对象");
            return;
        }
        Iterator<String> fields = schema.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!isSupported(field)) {
                errors.add(path + ": 不支持的 schema 关键字 " + field);
            }
        }
        if (!errors.isEmpty() && errors.size() >= MAX_ERRORS) {
            return;
        }
        validateType(value, schema.get("type"), path, errors);
        validateEnum(value, schema.get("enum"), path, errors);
        validateString(value, schema, path, errors);
        validateNumber(value, schema, path, errors);
        validateObject(value, schema, path, depth, errors);
        validateArray(value, schema, path, depth, errors);
    }

    private boolean isSupported(String field) {
        return switch (field) {
            case "type", "enum", "properties", "required", "additionalProperties", "items",
                    "minLength", "maxLength", "minimum", "maximum", MIN_ITEMS, MAX_ITEMS -> true;
            default -> false;
        };
    }

    private void validateType(JsonNode value, JsonNode declared, String path, List<String> errors) {
        if (declared == null) {
            return;
        }
        boolean valid = declared.isTextual() ? hasType(value, declared.asText()) : declared.isArray()
                && stream(declared).anyMatch(type -> type.isTextual() && hasType(value, type.asText()));
        if (!valid) {
            errors.add(path + ": 类型不符合 schema.type");
        }
    }

    private boolean hasType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private void validateEnum(JsonNode value, JsonNode enumValues, String path, List<String> errors) {
        if (enumValues != null && (!enumValues.isArray() || !stream(enumValues).anyMatch(value::equals))) {
            errors.add(path + ": 值不在 schema.enum 中");
        }
    }

    private void validateString(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (!value.isTextual()) {
            return;
        }
        int length = value.asText().length();
        if (schema.has("minLength") && length < schema.get("minLength").asInt(-1)) {
            errors.add(path + ": 长度小于 schema.minLength");
        }
        if (schema.has("maxLength") && length > schema.get("maxLength").asInt(Integer.MAX_VALUE)) {
            errors.add(path + ": 长度大于 schema.maxLength");
        }
    }

    private void validateNumber(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (!value.isNumber()) {
            return;
        }
        BigDecimal number = value.decimalValue();
        if (schema.has("minimum") && number.compareTo(schema.get("minimum").decimalValue()) < 0) {
            errors.add(path + ": 小于 schema.minimum");
        }
        if (schema.has("maximum") && number.compareTo(schema.get("maximum").decimalValue()) > 0) {
            errors.add(path + ": 大于 schema.maximum");
        }
    }

    private void validateObject(JsonNode value, JsonNode schema, String path, int depth, List<String> errors) {
        if (!value.isObject()) {
            return;
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                if (!field.isTextual()) {
                    errors.add(path + ": schema.required 项必须是字符串");
                } else if (!value.has(field.asText())) {
                    errors.add(path + ": 缺少必填字段 " + field.asText());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            errors.add(path + ": schema.properties 必须是对象");
            return;
        }
        if (properties != null) {
            Iterator<Map.Entry<String, JsonNode>> entries = properties.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (value.has(entry.getKey())) {
                    validate(value.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey(), depth + 1, errors);
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional == null || additional.isBoolean() && additional.booleanValue()) {
            return;
        }
        if (!additional.isBoolean() && !additional.isObject()) {
            errors.add(path + ": schema.additionalProperties 必须是布尔值或对象");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (properties != null && properties.has(entry.getKey())) {
                continue;
            }
            if (additional.isBoolean()) {
                errors.add(path + ": 不允许额外字段 " + entry.getKey());
            } else {
                validate(entry.getValue(), additional, path + "." + entry.getKey(), depth + 1, errors);
            }
        }
    }

    private void validateArray(JsonNode value, JsonNode schema, String path, int depth, List<String> errors) {
        if (!value.isArray()) return;
        for (String bound : List.of(MIN_ITEMS, MAX_ITEMS)) {
            var constraint = schema.get(bound);
            if (constraint == null) continue;
            if (!constraint.isIntegralNumber() || constraint.bigIntegerValue().signum() < 0)
                errors.add(path + ": schema." + bound + " 必须是非负整数");
            else {
                int comparison = java.math.BigInteger.valueOf(value.size()).compareTo(constraint.bigIntegerValue());
                if (MIN_ITEMS.equals(bound) ? comparison < 0 : comparison > 0)
                    errors.add(path + ": 数组长度不符合 schema." + bound);
            }
        }
        if (schema.get("items") == null) return;
        for (int index = 0; index < value.size(); index++) {
            validate(value.get(index), schema.get("items"), path + "[" + index + "]", depth + 1, errors);
        }
    }

    private java.util.stream.Stream<JsonNode> stream(JsonNode node) {
        List<JsonNode> nodes = new ArrayList<>();
        node.forEach(nodes::add);
        return nodes.stream();
    }
}
