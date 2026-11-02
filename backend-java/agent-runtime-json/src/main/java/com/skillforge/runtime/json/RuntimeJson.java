package com.skillforge.runtime.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.SerializableString;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 单一 JSON 语法边界；领域结构、字段 presence 和内容身份留给消费者。 */
public final class RuntimeJson {
    private static final int MAX_NUMBER_DIGITS = 1000;
    private static final CharacterEscapes ESCAPES = new CharacterEscapes() {
        private final int[] codes = standardAsciiEscapesForJSON();
        {
            for (int index = 0; index < 32; index++) {
                if (codes[index] == ESCAPE_STANDARD) { codes[index] = ESCAPE_CUSTOM; }
            }
        }
        @Override public int[] getEscapeCodesForAscii() { return codes; }
        @Override public SerializableString getEscapeSequence(int character) {
            if (character >= 32 || codes[character] != ESCAPE_CUSTOM) { return null; }
            return new SerializedString("\\u00" + Character.forDigit(character >>> 4, 16)
                    + Character.forDigit(character & 15, 16));
        }
    };

    private RuntimeJson() { }

    /** 普通文档允许标准空白及任意键序；数值不经过浮点转换。 */
    public static Object parse(String input, CodecLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (input == null || input.length() > limits.maxCodeUnits()) { throw invalid(); }
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(limits.maxDepth())
                        .maxStringLength(limits.maxCodeUnits())
                        .maxNameLength(limits.maxCodeUnits())
                        .maxNumberLength(limits.maxCodeUnits()).build()).build();
        try (JsonParser parser = factory.createParser(input)) {
            if (parser.nextToken() == null) { throw invalid(); }
            Object value = read(parser, limits, new int[1], 1);
            if (parser.nextToken() != null) { throw invalid(); }
            return value;
        } catch (IOException | ArithmeticException failure) {
            // 第三方错误可能含原文；边界只返回固定协议错误。
            throw invalid();
        }
    }

    /** 规范材料必须与确定性编码逐 UTF-16 单元一致。 */
    public static Object parseCanonical(String input, CodecLimits limits) {
        Object value = parse(input, limits);
        if (!writeCanonical(value).equals(input)) { throw invalid(); }
        return value;
    }

    /** 普通输出保留 Map 声明顺序，仍检查 JDK 值类型及 Unicode。 */
    public static String write(Object value) { return encode(value, false); }

    /** 按未转义 UTF-16 键排序，数值零折叠、去尾零并展开普通十进制。 */
    public static String writeCanonical(Object value) { return encode(value, true); }

    private static Object read(JsonParser parser, CodecLimits limits, int[] nodes, int depth) throws IOException {
        if (++nodes[0] > limits.maxNodes() || depth > limits.maxDepth()) { throw invalid(); }
        return switch (parser.currentToken()) {
            case VALUE_NULL -> null;
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_STRING -> unicode(parser.getText());
            case VALUE_NUMBER_INT -> {
                BigInteger number = parser.getBigIntegerValue();
                decimal(new BigDecimal(number));
                yield number;
            }
            case VALUE_NUMBER_FLOAT -> {
                BigDecimal number = parser.getDecimalValue();
                decimal(number);
                yield number;
            }
            case START_ARRAY -> {
                var values = new ArrayList<Object>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    values.add(read(parser, limits, nodes, depth + 1));
                }
                yield values;
            }
            case START_OBJECT -> {
                var values = new LinkedHashMap<String, Object>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME || ++nodes[0] > limits.maxNodes()
                            || depth + 1 > limits.maxDepth()) { throw invalid(); }
                    String name = unicode(parser.currentName());
                    if (parser.nextToken() == null) { throw invalid(); }
                    values.put(name, read(parser, limits, nodes, depth + 1));
                }
                yield values;
            }
            default -> throw invalid();
        };
    }

    private static String encode(Object value, boolean canonical) {
        var output = new StringWriter();
        try (JsonGenerator generator = new JsonFactory().createGenerator(output)) {
            generator.setCharacterEscapes(ESCAPES);
            writeValue(generator, value, canonical);
        } catch (IOException | ArithmeticException failure) { throw invalid(); }
        return output.toString();
    }

    private static void writeValue(JsonGenerator generator, Object value, boolean canonical) throws IOException {
        if (value == null) { generator.writeNull(); }
        else if (value instanceof String text) { generator.writeString(unicode(text)); }
        else if (value instanceof Boolean flag) { generator.writeBoolean(flag); }
        else if (value instanceof BigDecimal number) { generator.writeNumber(decimal(number)); }
        else if (value instanceof BigInteger number) { generator.writeNumber(decimal(new BigDecimal(number))); }
        else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            generator.writeNumber(value.toString());
        } else if (value instanceof List<?> values) {
            generator.writeStartArray();
            for (Object item : values) { writeValue(generator, item, canonical); }
            generator.writeEndArray();
        } else if (value instanceof Map<?, ?> values) {
            Map<String, Object> fields = canonical ? new TreeMap<>() : new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String name)) { throw invalid(); }
                fields.put(unicode(name), entry.getValue());
            }
            generator.writeStartObject();
            for (var field : fields.entrySet()) {
                generator.writeFieldName(field.getKey());
                writeValue(generator, field.getValue(), canonical);
            }
            generator.writeEndObject();
        } else { throw invalid(); }
    }

    private static String decimal(BigDecimal value) {
        if (value.signum() == 0) { return "0"; }
        BigDecimal normalized = value.stripTrailingZeros();
        long precision = normalized.precision();
        long scale = normalized.scale();
        long length = scale <= 0 ? precision - scale : scale >= precision ? scale + 2 : precision + 1;
        if (length > MAX_NUMBER_DIGITS) { throw invalid(); }
        return normalized.toPlainString();
    }

    private static String unicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) { throw invalid(); }
            } else if (Character.isLowSurrogate(character)) { throw invalid(); }
        }
        return text;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_runtime_json"); }
}
