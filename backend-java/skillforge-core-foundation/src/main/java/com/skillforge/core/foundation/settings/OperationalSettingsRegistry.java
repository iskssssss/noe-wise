package com.skillforge.core.foundation.settings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 运营配置分组、字段类型、代码默认值和跨字段约束的唯一注册表。 */
public final class OperationalSettingsRegistry {
    private static final Pattern SNAKE_CASE = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    private final Map<String, GroupDefinition> definitions;

    public OperationalSettingsRegistry(List<OperationalSettingsContributor> contributors) {
        var groups = new LinkedHashMap<String, GroupDefinition>();
        for (var contributor : contributors) {
            for (var group : contributor.groups()) add(groups, group);
        }
        definitions = Collections.unmodifiableMap(groups);
    }

    public List<GroupDefinition> groups() {
        return List.copyOf(definitions.values());
    }

    public GroupDefinition require(String key) {
        GroupDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new UnknownGroupException(key);
        }
        return definition;
    }

    public static Map<String, Object> normalize(
            GroupDefinition group, Map<String, Object> supplied, Map<String, Object> current) {
        if (supplied == null) {
            throw new InvalidSettingsException("values 必须是对象");
        }
        Set<String> unknown = new LinkedHashSet<>(supplied.keySet());
        unknown.removeAll(group.fields().keySet());
        if (!unknown.isEmpty()) {
            throw new InvalidSettingsException("未知配置字段: " + String.join(", ", unknown));
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (FieldDefinition field : group.fields().values()) {
            Object value;
            if (supplied.containsKey(field.key())) {
                value = field.normalize(supplied.get(field.key()));
            } else if (field.secret() && current != null && current.containsKey(field.key())) {
                value = current.get(field.key());
            } else {
                throw new InvalidSettingsException("缺少配置字段: " + field.key());
            }
            normalized.put(field.key(), value);
        }
        group.validator().accept(normalized);
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    public static GroupDefinition group(String key, String label, FieldDefinition... fields) {
        return group(key, label, values -> { }, stored -> false, fields);
    }

    public static GroupDefinition group(String key, String label,
            java.util.function.Consumer<Map<String, Object>> validator,
            java.util.function.Predicate<Map<String, Object>> retired, FieldDefinition... fields) {
        LinkedHashMap<String, FieldDefinition> mapped = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            if (!SNAKE_CASE.matcher(field.key()).matches() || mapped.put(field.key(), field) != null) {
                throw new IllegalStateException("运营配置字段注册重复或不是 snake_case: " + field.key());
            }
        }
        return new GroupDefinition(key, label, mapped, validator, retired, snapshot -> List.of());
    }

    private static void add(Map<String, GroupDefinition> groups, GroupDefinition group) {
        if (!SNAKE_CASE.matcher(group.key()).matches() || groups.put(group.key(), group) != null) {
            throw new IllegalStateException("运营配置分组注册重复或不是 snake_case: " + group.key());
        }
    }

    public static BigDecimal number(Map<String, Object> values, String key) {
        return new BigDecimal(values.get(key).toString());
    }

    public static FieldDefinition integer(
            String key, String label, String unit, long minimum, long maximum, long value) {
        return new FieldDefinition(key, label, "number", unit, BigDecimal.valueOf(minimum),
                BigDecimal.valueOf(maximum), List.of(), false, false, 0,
                Set.of(), value);
    }

    public static FieldDefinition decimal(String key, String label, String unit,
            String minimum, String maximum, String value) {
        return new FieldDefinition(key, label, "number", unit, new BigDecimal(minimum),
                new BigDecimal(maximum), List.of(), false, false, 0,
                Set.of(), new BigDecimal(value));
    }

    public static FieldDefinition bool(String key, String label, boolean value) {
        return new FieldDefinition(key, label, "boolean", null, null, null,
                List.of(), false, false, 0, Set.of(), value);
    }

    public static FieldDefinition select(String key, String label, String value, String... choices) {
        return new FieldDefinition(key, label, "select", null, null, null,
                List.of(choices), false, false, 128, Set.of(), value);
    }

    public static FieldDefinition string(String key, String label, String value,
            boolean allowBlank, boolean secret, int maxLength) {
        return new FieldDefinition(key, label, "string", null, null, null,
                List.of(), secret, allowBlank, maxLength, Set.of(), value);
    }

    public static FieldDefinition prompt(String key, String label, String value) {
        return new FieldDefinition(key, label, "text", null, null, null,
                List.of(), false, false, 200_000, Set.of(), value);
    }

    public record GroupDefinition(String key, String label, Map<String, FieldDefinition> fields,
            java.util.function.Consumer<Map<String, Object>> validator,
            java.util.function.Predicate<Map<String, Object>> retired,
            java.util.function.Function<OperationalSettingsService.Snapshot, List<OperationalSettingsService.DerivedBudget>> derived) {
        public GroupDefinition {
            java.util.Objects.requireNonNull(validator, "validator");
            java.util.Objects.requireNonNull(retired, "retired");
            java.util.Objects.requireNonNull(derived, "derived");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /** 领域贡献管理视图的派生展示；基础服务不计算运行预算。 */
        public GroupDefinition withDerived(java.util.function.Function<OperationalSettingsService.Snapshot,
                List<OperationalSettingsService.DerivedBudget>> projection) {
            return new GroupDefinition(key, label, fields, validator, retired, projection);
        }

        public Map<String, Object> defaults() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            fields.values().forEach(field -> values.put(field.key(), field.defaultValue()));
            return Collections.unmodifiableMap(values);
        }
    }

    public record FieldDefinition(
            String key,
            String label,
            String type,
            String unit,
            BigDecimal minimum,
            BigDecimal maximum,
            List<String> choices,
            boolean secret,
            boolean allowBlank,
            int maxLength,
            Set<String> templateVariables,
            Object defaultValue) {

        public FieldDefinition {
            choices = List.copyOf(choices);
            templateVariables = Set.copyOf(templateVariables);
        }

        Object normalize(Object value) {
            if (value == null) {
                throw new InvalidSettingsException(key + " 不能为空");
            }
            return switch (type) {
                case "number" -> normalizeNumber(value);
                case "boolean" -> normalizeBoolean(value);
                case "string", "text", "select" -> normalizeText(value);
                default -> throw new IllegalStateException("未知配置字段类型: " + type);
            };
        }

        private Object normalizeNumber(Object value) {
            if (!(value instanceof Number)) {
                throw new InvalidSettingsException(key + " 必须是数字");
            }
            BigDecimal number;
            try {
                number = new BigDecimal(value.toString()).stripTrailingZeros();
            } catch (NumberFormatException exception) {
                throw new InvalidSettingsException(key + " 必须是有限数字");
            }
            if (minimum != null && number.compareTo(minimum) < 0
                    || maximum != null && number.compareTo(maximum) > 0) {
                throw new InvalidSettingsException(key + " 超出允许范围");
            }
            if (defaultValue instanceof Long || defaultValue instanceof Integer) {
                try {
                    return number.longValueExact();
                } catch (ArithmeticException exception) {
                    throw new InvalidSettingsException(key + " 必须是整数");
                }
            }
            return number;
        }

        private Object normalizeBoolean(Object value) {
            if (!(value instanceof Boolean)) {
                throw new InvalidSettingsException(key + " 必须是布尔值");
            }
            return value;
        }

        private Object normalizeText(Object value) {
            if (!(value instanceof String text)) {
                throw new InvalidSettingsException(key + " 必须是字符串");
            }
            if (!allowBlank && text.isBlank()) {
                throw new InvalidSettingsException(key + " 不能为空");
            }
            if (text.length() > maxLength) {
                throw new InvalidSettingsException(key + " 长度超过上限");
            }
            if ("select".equals(type) && !choices.contains(text)) {
                throw new InvalidSettingsException(key + " 不是允许的选项");
            }
            if ("text".equals(type)) {
                validateTemplateVariables(text);
            }
            return text;
        }

        private void validateTemplateVariables(String text) {
            Set<String> actual = new LinkedHashSet<>();
            int cursor = 0;
            while ((cursor = text.indexOf("{{", cursor)) >= 0) {
                int end = text.indexOf("}}", cursor + 2);
                if (end < 0) {
                    throw new InvalidSettingsException(key + " 包含未闭合的模板变量");
                }
                String variable = text.substring(cursor + 2, end);
                if (!SNAKE_CASE.matcher(variable).matches()) {
                    throw new InvalidSettingsException(key + " 包含格式错误的模板变量");
                }
                actual.add(variable);
                cursor = end + 2;
            }
            if (text.indexOf("}}") >= 0 && text.indexOf("{{") < 0) {
                throw new InvalidSettingsException(key + " 包含未起始的模板变量");
            }
            if (!actual.equals(templateVariables)) {
                List<String> unknown = new ArrayList<>(actual);
                unknown.removeAll(templateVariables);
                List<String> missing = new ArrayList<>(templateVariables);
                missing.removeAll(actual);
                throw new InvalidSettingsException(key + " 模板变量不匹配，未知=" + unknown + "，缺少=" + missing);
            }
        }
    }

    static final class UnknownGroupException extends RuntimeException {
        UnknownGroupException(String group) {
            super("未知运营配置分组: " + group);
        }
    }

    public static final class InvalidSettingsException extends RuntimeException {
        public InvalidSettingsException(String message) {
            super(message);
        }
    }
}
