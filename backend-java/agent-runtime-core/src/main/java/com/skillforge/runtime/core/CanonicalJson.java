package com.skillforge.runtime.core;

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
import java.util.Optional;

/** 注册模型准备 schema 的规范 JSON 原语；只通过有界解析接收输入，不拼接原始片段或反射业务对象。 */
final class CanonicalJson {

    /** 只提供纯函数，不建立可变 encoder 实例或共享缓冲区。 */
    private CanonicalJson() {
    }

    /** 保存只能由本类创建的完整规范值，阻止调用方拼接未经转义的原始正文。 */
    static final class Value {
        private final Object jsonValue;
        private final Kind kind;
        private final String text;
        private final List<Field> fields;
        private final List<Value> elements;
        private final boolean flag;

        /** 接收已由封闭工厂编码的内容；构造器不向 Core 其他类开放。 */
        private Value(Kind kind, String text, List<Field> fields, List<Value> elements, boolean flag) {
            this.jsonValue = switch (kind) {
                case STRING -> text;
                case NUMBER -> new BigDecimal(text);
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

        /** schema 解码必须一次检查精确字段集合，既拒绝未知字段也拒绝缺省必需字段。 */
        Value requireFields(String... names) {
            requireKind(Kind.OBJECT);
            var expected = new HashSet<>(List.of(names));
            if (expected.size() != names.length || fields.size() != expected.size()) {
                throw new IllegalArgumentException("canonical object fields do not match registered schema");
            }
            for (Field field : fields) {
                if (!expected.contains(field.name())) {
                    throw new IllegalArgumentException("canonical object fields do not match registered schema");
                }
            }
            return this;
        }

        /** 原始 decision 的可选字段只允许缺失，显式 null 仍由其值类型拒绝；不改变已有精确字段入口。 */
        Value requireMembers(List<String> required, List<String> optional) {
            requireKind(Kind.OBJECT);
            var allowed = new HashSet<>(required);
            if (allowed.size() != required.size()) { throw new IllegalArgumentException("duplicate schema member"); }
            for (String name : optional) {
                if (!allowed.add(name)) { throw new IllegalArgumentException("duplicate schema member"); }
            }
            for (Field field : fields) {
                if (!allowed.contains(field.name())) { throw new IllegalArgumentException("unknown schema member"); }
            }
            for (String name : required) { field(name); }
            return this;
        }

        /** 可选成员保留缺失与 null 的区别，不从其他字段或当前配置补值。 */
        Optional<Value> optionalField(String name) {
            requireKind(Kind.OBJECT);
            return fields.stream().filter(field -> field.name().equals(name)).map(Field::value).findFirst();
        }

        /** 只返回真实存在的成员，不把缺失与显式 null 合并。 */
        Value field(String name) {
            requireKind(Kind.OBJECT);
            for (Field field : fields) {
                if (field.name().equals(name)) {
                    return field.value();
                }
            }
            throw new IllegalArgumentException("required canonical object field is missing");
        }

        /** 字符串不得从数字、boolean 或 null 自动强制转换。 */
        String stringValue() {
            requireKind(Kind.STRING);
            return text;
        }

        /** 精确解析注册的十进制整数字符串，拒绝加号、负零、前导零、Unicode 数字和溢出。 */
        long longValue() {
            String decimal = stringValue();
            try {
                long result = Long.parseLong(decimal);
                if (!Long.toString(result).equals(decimal)) {
                    throw new IllegalArgumentException("integer is not canonical decimal");
                }
                return result;
            } catch (NumberFormatException exception) {
                // 这是预期的协议错误；不携带包含原始输入的 NumberFormatException 消息。
                throw new IllegalArgumentException("invalid canonical integer");
            }
        }

        /** JSON Schema integer 字段保留为 number；只接受可无损落入 long 的整数值。 */
        long integralNumberValue() {
            requireKind(Kind.NUMBER);
            try {
                return new BigDecimal(text).longValueExact();
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new IllegalArgumentException("invalid JSON integer");
            }
        }

        /** int 字段必须先通过 long 的精确词法校验，再检查窄化范围而非截断。 */
        int intValue() {
            long value = longValue();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("canonical integer is outside int range");
            }
            return (int) value;
        }

        /** 布尔字段只接受 JSON boolean。 */
        boolean booleanValue() {
            requireKind(Kind.BOOLEAN);
            return flag;
        }

        /** 数组读取保留顺序并返回不可变视图。 */
        List<Value> arrayValue() {
            requireKind(Kind.ARRAY);
            return elements;
        }

        /** 动态键协议只读访问对象成员；返回规范排序后的不可变字段。 */
        List<Field> objectFields() {
            requireKind(Kind.OBJECT);
            return fields;
        }

        /** Optional 只在真实 null 时为空，空字符串或空对象不能充当缺省值。 */
        boolean isNull() {
            return kind == Kind.NULL;
        }

        /** 对错误类型给出固定协议错误，避免异常转储请求正文。 */
        private void requireKind(Kind expected) {
            if (kind != expected) {
                throw new IllegalArgumentException("canonical JSON value has wrong type");
            }
        }

        /** 日志不转储可能包含模型输入的规范载荷。 */
        @Override
        public String toString() {
            return "CanonicalJson.Value[REDACTED]";
        }
    }

    /** Runtime 注册结构继续用字符串整数；Tool arguments 额外接纳标准 JSON number。 */
    private enum Kind { STRING, NUMBER, OBJECT, ARRAY, BOOLEAN, NULL }

    /** 对象成员保持名称和已编码值分离，不引入 Map 业务边界。 */
    record Field(String name, Value value) {

        /** JSON 允许空字段名，但拒绝 null 和非法 UTF-16；schema 白名单由上层检查。 */
        Field {
            requireUnicode(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** 将 Unicode 字符串按 RFC 8785 转义；不正规化内容或转义斜线和有效非 ASCII 字符。 */
    static Value string(String text) {
        requireUnicode(text);
        return new Value(Kind.STRING, text, List.of(), List.of(), false);
    }

    /** 模型准备 schema 的整数是十进制字符串，避免 JSON number 丢失 64-bit 精度。 */
    static Value integer(long value) {
        return string(Long.toString(value));
    }

    /** Tool arguments 的 JSON number 使用任意精度十进制归一化，不经 double 丢失信息。 */
    static Value number(String lexical) {
        String encoded;
        try {
            encoded = RuntimeJson.writeCanonical(new BigDecimal(lexical));
        } catch (NumberFormatException failure) {
            // 数值词法错误不得把原始输入带入异常消息。
            throw new IllegalArgumentException("invalid JSON number");
        }
        return new Value(Kind.NUMBER, encoded, List.of(), List.of(), false);
    }

    /** 显式编码布尔值，不使用字符串模拟能力标志。 */
    static Value bool(boolean value) {
        return new Value(Kind.BOOLEAN, "", List.of(), List.of(), value);
    }

    /** Optional 缺省值明确编码为 null，而不是删除字段改变 schema。 */
    static Value nil() {
        return new Value(Kind.NULL, "", List.of(), List.of(), false);
    }

    /** 复制成员并按未转义的 UTF-16 名称排序；重复名称不能被覆盖或去重。 */
    static Value object(List<Field> fields) {
        List<Field> frozen = List.copyOf(fields);
        HashSet<String> names = new HashSet<>();
        for (Field field : frozen) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("duplicate canonical object member");
            }
        }
        List<Field> ordered = frozen.stream().sorted(Comparator.comparing(Field::name)).toList();
        return new Value(Kind.OBJECT, "", ordered, List.of(), false);
    }

    /** 数组只复制并保持声明顺序；排序 Set 的责任由所属注册 schema 明确承担。 */
    static Value array(List<Value> values) {
        List<Value> frozen = List.copyOf(values);
        return new Value(Kind.ARRAY, "", List.of(), frozen, false);
    }

    /** 仅在构造 TypedPayload 或执行明确的持久化时取出规范正文。 */
    static String encode(Value value) {
        return RuntimeJson.writeCanonical(Objects.requireNonNull(value, "value").jsonValue);
    }

    /** 在构造任何解析树前检查大小，并要求重编码与输入逐 UTF-16 单元相等。 */
    static Value parse(String input, CodecLimits limits) {
        return fromJson(RuntimeJson.parseCanonical(input, limits));
    }

    /** 原始 decision 允许标准 JSON 布局，持久读取仍要求规范字节。 */
    static Value parseDocument(String input, CodecLimits limits) {
        return fromJson(RuntimeJson.parse(input, limits));
    }

    /** 公共语法树只在领域入口映射，字段与类型契约仍由 Value 检查。 */
    private static Value fromJson(Object value) {
        if (value == null) { return nil(); }
        if (value instanceof String text) { return string(text); }
        if (value instanceof Boolean flag) { return bool(flag); }
        if (value instanceof Number number) { return number(number.toString()); }
        if (value instanceof List<?> elements) {
            return array(elements.stream().map(CanonicalJson::fromJson).toList());
        }
        if (value instanceof Map<?, ?> members) {
            return object(members.entrySet().stream()
                    .map(entry -> new Field((String) entry.getKey(), fromJson(entry.getValue())))
                    .toList());
        }
        throw new IllegalArgumentException("unsupported runtime JSON value");
    }

    /** 按 schema + NUL + UTF-8 canonical bytes 计算域分离摘要，不依赖平台默认字符集。 */
    static String hash(String schemaId, Value value) {
        requireUnicode(schemaId);
        if (schemaId.isBlank() || schemaId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("schemaId must be nonblank and must not contain NUL");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(schemaId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(encode(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必须提供 SHA-256；环境故障保留原因链，不降级为其他 hash。
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    /** 仅摘要规范值正文；Action resolution key 的 logical schema 位于摘要外层。 */
    static String contentHash(Value value) {
        Objects.requireNonNull(value, "value");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encode(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime", exception);
        }
    }

    /** UTF-8 编码前拒绝孤立 surrogate，不能让标准编码器静默替换并形成摘要碰撞。 */
    private static void requireUnicode(String text) {
        RuntimeJson.write(Objects.requireNonNull(text, "text"));
    }
}
