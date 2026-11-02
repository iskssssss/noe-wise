package com.skillforge.runtime.memory;

import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.json.RuntimeJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/** Memory 事件 codec 私有的严格规范 JSON 原语。 */
final class MemoryJson {
    private MemoryJson() { }

    record Limits(int maxCodeUnits, int maxNodes, int maxDepth) {
        Limits {
            if (maxCodeUnits <= 0 || maxNodes <= 0 || maxDepth <= 0 || maxDepth > 128) {
                throw new IllegalArgumentException("invalid Memory JSON limits");
            }
        }
    }

    static final class Value {
        private final Object jsonValue;
        private final Kind kind;
        private final String text;
        private final List<Field> fields;
        private final List<Value> elements;
        private final boolean flag;

        private Value(
                Kind kind,
                String text,
                List<Field> fields,
                List<Value> elements,
                boolean flag) {
            this.jsonValue = switch (kind) {
                case STRING -> text;
                case BOOLEAN -> flag;
                case NULL -> null;
                case ARRAY -> elements.stream().map(value -> value.jsonValue).toList();
                case OBJECT -> {
                    Map<String, Object> members = new LinkedHashMap<>();
                    for (Field field : fields) {
                        members.put(field.name(), field.value().jsonValue);
                    }
                    yield members;
                }
            };
            this.kind = kind;
            this.text = text;
            this.fields = fields;
            this.elements = elements;
            this.flag = flag;
        }

        Value requireFields(String... names) {
            requireKind(Kind.OBJECT);
            Set<String> expected = new HashSet<>(List.of(names));
            if (expected.size() != names.length || fields.size() != expected.size()) {
                throw invalidSchema();
            }
            for (Field field : fields) {
                if (!expected.contains(field.name())) {
                    throw invalidSchema();
                }
            }
            return this;
        }

        Value field(String name) {
            requireKind(Kind.OBJECT);
            for (Field field : fields) {
                if (field.name().equals(name)) {
                    return field.value();
                }
            }
            throw invalidSchema();
        }

        String stringValue() {
            requireKind(Kind.STRING);
            return text;
        }

        boolean booleanValue() {
            requireKind(Kind.BOOLEAN);
            return flag;
        }

        List<Value> arrayValue() {
            requireKind(Kind.ARRAY);
            return elements;
        }

        boolean isNull() {
            return kind == Kind.NULL;
        }

        private void requireKind(Kind expected) {
            if (kind != expected) {
                throw invalidSchema();
            }
        }

        @Override
        public String toString() {
            return "MemoryJson.Value[REDACTED]";
        }
    }

    private enum Kind { STRING, OBJECT, ARRAY, BOOLEAN, NULL }

    record Field(String name, Value value) {
        Field {
            requireUnicode(name);
            value = Objects.requireNonNull(value, "value");
        }
    }

    static Field field(String name, Value value) {
        return new Field(name, value);
    }

    static Value string(String text) {
        requireUnicode(text);
        return new Value(Kind.STRING, text, List.of(), List.of(), false);
    }

    static Value integer(long value) {
        return string(Long.toString(value));
    }

    static Value decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Memory decimal must be finite");
        }
        String canonical = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return string(canonical.equals("-0") ? "0" : canonical);
    }

    static Value bool(boolean value) {
        return new Value(Kind.BOOLEAN, "", List.of(), List.of(), value);
    }

    static Value nil() {
        return new Value(Kind.NULL, "", List.of(), List.of(), false);
    }

    static Value object(Field... fields) {
        return object(List.of(fields));
    }

    static Value object(List<Field> fields) {
        List<Field> frozen = List.copyOf(fields);
        HashSet<String> names = new HashSet<>();
        for (Field field : frozen) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("duplicate Memory JSON member");
            }
        }
        List<Field> ordered = frozen.stream().sorted(Comparator.comparing(Field::name)).toList();
        return new Value(Kind.OBJECT, "", ordered, List.of(), false);
    }

    static Value array(List<Value> elements) {
        List<Value> frozen = List.copyOf(elements);
        return new Value(Kind.ARRAY, "", List.of(), frozen, false);
    }

    static String encode(Value value) {
        return RuntimeJson.writeCanonical(Objects.requireNonNull(value, "value").jsonValue);
    }

    static Value parse(String input, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        return fromJson(RuntimeJson.parseCanonical(input,
                new CodecLimits(limits.maxCodeUnits(), limits.maxNodes(), limits.maxDepth())));
    }

    /** 公共语法树只在领域入口映射，字段与类型契约仍由 Value 检查。 */
    private static Value fromJson(Object value) {
        if (value == null) { return nil(); }
        if (value instanceof String text) { return string(text); }
        if (value instanceof Boolean flag) { return bool(flag); }
        if (value instanceof List<?> elements) {
            return array(elements.stream().map(MemoryJson::fromJson).toList());
        }
        if (value instanceof Map<?, ?> members) {
            return object(members.entrySet().stream()
                    .map(entry -> new Field((String) entry.getKey(), fromJson(entry.getValue())))
                    .toList());
        }
        throw new IllegalArgumentException("Memory JSON does not accept numbers");
    }

    static String hash(String schemaId, Value value) {
        requireUnicode(schemaId);
        if (schemaId.isBlank() || schemaId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("schemaId must be nonblank and contain no NUL");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(schemaId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(encode(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static IllegalArgumentException invalidSchema() {
        return new IllegalArgumentException("Memory JSON does not match registered schema");
    }

    private static void requireUnicode(String text) {
        RuntimeJson.write(Objects.requireNonNull(text, "text"));
    }
}
