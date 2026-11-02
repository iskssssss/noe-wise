package com.skillforge.core.workflow;

import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 安装时物化默认值并校验业务参数；不可变修订保存最终值。 */
final class WorkflowInstallationConfiguration {
    private WorkflowInstallationConfiguration() {}

    static Map<String, Object> resolve(Map<String, Object> definition, Map<String, Object> supplied) {
        var meta = WorkflowDefinitionContract.object(definition.getOrDefault("meta", Map.of()), "meta");
        var input = supplied == null ? Map.<String, Object>of() : supplied;
        var result = new LinkedHashMap<String, Object>();
        var declared = new java.util.HashSet<String>();
        for (Object raw : WorkflowDefinitionContract.list(meta.getOrDefault("config_schema", java.util.List.of()), "config_schema")) {
            var field = WorkflowDefinitionContract.object(raw, "config_schema item");
            String key = WorkflowDefinitionContract.text(field, "key");
            if (!key.matches("[a-z][a-z0-9_]{0,63}") || !declared.add(key)) throw invalid(key);
            Object value = input.containsKey(key) ? input.get(key) : field.get("default");
            if (value == null) {
                if (Boolean.TRUE.equals(field.get("required"))) throw invalid(key);
                continue;
            }
            String type = WorkflowDefinitionContract.text(field, "type");
            boolean valid = switch (type) {
                case "string", "textarea" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "integer", "number" -> value instanceof Number && numeric(value, field, type.equals("integer"));
                case "enum" -> value instanceof String && WorkflowDefinitionContract.list(field.get("enum_values"), "enum_values").contains(value);
                default -> false;
            };
            if (!valid || Boolean.TRUE.equals(field.get("required")) && value instanceof String text && text.isBlank()) throw invalid(key);
            result.put(key, value);
        }
        if (!declared.containsAll(input.keySet())) throw new IllegalArgumentException("安装配置包含未声明字段");
        return Map.copyOf(result);
    }

    private static boolean numeric(Object value, Map<String, Object> field, boolean integer) {
        try {
            var number = new BigDecimal(value.toString());
            if (integer) number.toBigIntegerExact();
            return (!field.containsKey("min") || number.compareTo(new BigDecimal(field.get("min").toString())) >= 0)
                    && (!field.containsKey("max") || number.compareTo(new BigDecimal(field.get("max").toString())) <= 0);
        } catch (ArithmeticException | NumberFormatException invalid) { return false; }
    }

    private static IllegalArgumentException invalid(String key) { return new IllegalArgumentException("安装配置不满足定义: " + key); }
}
