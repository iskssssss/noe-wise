package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 用独立手写 JSON 字节预期验证编码，避免只用同一 codec 的往返测试得到假绿。 */
class CanonicalJsonTest {

    /** 原始文档只放宽标准 token 边界布局；与原持久 parse 的严格逐字节要求独立验收。 */
    @Test
    void documentLayoutDoesNotRelaxStoredBytesOrTokenGrammar() {
        var limits = new CodecLimits(1000, 100, 10);
        String raw = " \t{\r\n\"z\" : [ true , false , null ], \"a\" : \"\\u0061\\/\" }\n";
        assertEquals("{\"a\":\"a/\",\"z\":[true,false,null]}", CanonicalJson.encode(CanonicalJson.parseDocument(raw, limits)));
        // 供应商写法在此一次性归一化为普通十进制；整十不得变成指数，否则按字节比较的下游边界会拒绝合法值。
        assertEquals("[1,1.25,1,0,100,10,1200,0.0000001,9007199254740993123456789]",
                CanonicalJson.encode(CanonicalJson.parseDocument(
                        "[1, 1.250, 1e0, -0, 1E+2, 10, 1.2e3, 1e-7, 9007199254740993123456789]", limits)));
        // 短 token 不得膨胀成巨串；越界直接拒绝而不展开。
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parseDocument("1e1000000", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parseDocument("1e-1000000", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(raw, limits));
        for (String invalid : List.of("tr ue", "fa lse", "nu ll", "true false", "\"x\" \"y\"", "{ \"x\" : true , }",
                "[true,]", "\u00a0true", "//comment\ntrue", "{\"a\":true,\"\\u0061\":false}",
                "+1", "01", "-", "1.", "1e", "1e+")) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parseDocument(invalid, limits));
        }
    }

    /** 可选字段只区分缺失与实际值，不吞掉显式 null；字段白名单仍拒绝未知/缺失/重复 schema 定义。 */
    @Test
    void optionalMemberPresenceKeepsNullAndMissingDistinct() {
        var value = CanonicalJson.parseDocument("{ \"required\" : \"x\", \"optional\": null }", new CodecLimits(100, 10, 3));
        value.requireMembers(List.of("required"), List.of("optional"));
        assertEquals(true, value.optionalField("optional").orElseThrow().isNull());
        assertEquals(true, value.optionalField("absent").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> value.requireMembers(List.of("required"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> value.requireMembers(List.of("absent"), List.of("required", "optional")));
        assertThrows(IllegalArgumentException.class, () -> value.requireMembers(List.of("required"), List.of("required")));
        assertThrows(IllegalArgumentException.class, () -> value.requireMembers(List.of("required", "required"), List.of("optional")));
    }

    /** 所有 JSON 控制字符、引号和反斜线遵循最小转义，正文保持原 Unicode。 */
    @Test
    void writesExactStringEscapesWithoutNormalizingUnicode() {
        assertEquals("\"\\u0000\\u001f\\b\\t\\n\\f\\r\\\"\\\\/中😀\"",
                CanonicalJson.encode(CanonicalJson.string("\0\u001f\b\t\n\f\r\"\\/中😀")));
        assertNotEquals(CanonicalJson.encode(CanonicalJson.string("é")),
                CanonicalJson.encode(CanonicalJson.string("e\u0301")));
    }

    /** JCS 按未转义 UTF-16 key 排序，不按输入顺序、转义字节或 Unicode code point 排序。 */
    @Test
    void sortsObjectNamesButPreservesArrayOrder() {
        var value = CanonicalJson.object(List.of(
                new CanonicalJson.Field("\ue000", CanonicalJson.nil()),
                new CanonicalJson.Field("\ud800\udc00", CanonicalJson.bool(true)),
                new CanonicalJson.Field("z", CanonicalJson.array(List.of(
                        CanonicalJson.string("second"), CanonicalJson.string("first")))),
                new CanonicalJson.Field("a", CanonicalJson.bool(false))));
        assertEquals("{\"a\":false,\"z\":[\"second\",\"first\"],\"\ud800\udc00\":true,\"\ue000\":null}",
                CanonicalJson.encode(value));
    }

    /** 大于 IEEE-754 精确整数范围的值仍以原十进制字符串逐位保留。 */
    @Test
    void preservesFullSignedLongRangeAsStrings() {
        assertEquals("\"9223372036854775807\"", CanonicalJson.encode(CanonicalJson.integer(Long.MAX_VALUE)));
        assertEquals("\"-9223372036854775808\"", CanonicalJson.encode(CanonicalJson.integer(Long.MIN_VALUE)));
        assertEquals("\"0\"", CanonicalJson.encode(CanonicalJson.integer(0)));
    }

    /** 已构造值不随来源集合改变；不允许用后写覆盖重复字段。 */
    @Test
    void freezesCollectionsAndRejectsDuplicateMembers() {
        var fields = new ArrayList<>(List.of(new CanonicalJson.Field("x", CanonicalJson.string("original"))));
        var object = CanonicalJson.object(fields);
        fields.clear();
        assertEquals("{\"x\":\"original\"}", CanonicalJson.encode(object));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.object(List.of(
                new CanonicalJson.Field("x", CanonicalJson.nil()),
                new CanonicalJson.Field("x", CanonicalJson.bool(true)))));
        assertThrows(NullPointerException.class, () -> CanonicalJson.array(Arrays.asList(CanonicalJson.nil(), null)));
        assertThrows(NullPointerException.class, () -> CanonicalJson.object(null));
    }

    /** UTF-8 编码不得静默替换非法 surrogate；名称和载荷使用同一检查。 */
    @Test
    void rejectsMalformedUnicodeBeforeEncodingOrHashing() {
        for (String invalid : List.of("\ud800", "\udc00", "a\ud800b", "\ud800\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(invalid));
            assertThrows(IllegalArgumentException.class, () -> new CanonicalJson.Field(invalid, CanonicalJson.nil()));
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.hash(invalid, CanonicalJson.nil()));
        }
    }

    /** Schema、空值和原始字符串的 hash 域不可互换，日志不暴露载荷。 */
    @Test
    void separatesSchemaDomainsAndHidesPayloadInLogs() {
        assertNotEquals(CanonicalJson.hash("a", CanonicalJson.nil()), CanonicalJson.hash("b", CanonicalJson.nil()));
        assertNotEquals(CanonicalJson.hash("a", CanonicalJson.nil()), CanonicalJson.hash("a", CanonicalJson.string("null")));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.hash("a\0b", CanonicalJson.nil()));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.hash(" ", CanonicalJson.nil()));
        assertThrows(NullPointerException.class, () -> CanonicalJson.hash("a", null));
        assertEquals("CanonicalJson.Value[REDACTED]", CanonicalJson.string("private message").toString());
    }

    /** 使用手写规范载荷验证结构访问和重编码，而不是仅解析本 encoder 的输出。 */
    @Test
    void parsesExactGoldenValuesAndEnforcesNodeTypes() {
        String input = "{\"a\":[true,null,\"😀\"],\"b\":\"9223372036854775807\"}";
        var value = CanonicalJson.parse(input, new CodecLimits(100, 10, 3)).requireFields("a", "b");
        assertEquals(input, CanonicalJson.encode(value));
        assertEquals(true, value.field("a").arrayValue().get(0).booleanValue());
        assertEquals(true, value.field("a").arrayValue().get(1).isNull());
        assertEquals("😀", value.field("a").arrayValue().get(2).stringValue());
        assertEquals(Long.MAX_VALUE, value.field("b").longValue());
        assertThrows(IllegalArgumentException.class, () -> value.field("a").stringValue());
        assertThrows(IllegalArgumentException.class, () -> value.field("missing"));
        assertThrows(IllegalArgumentException.class, () -> value.requireFields("a"));
        assertThrows(IllegalArgumentException.class, () -> value.requireFields("a", "c"));
        assertThrows(IllegalArgumentException.class, () -> value.requireFields("a", "a"));
    }

    /** 可由普通 JSON parser 接受的非规范输入仍拒绝，不能自动修正后产生不同持久身份。 */
    @Test
    void rejectsNoncanonicalAndMalformedInputWithoutEchoingPayload() {
        assertEquals("1", CanonicalJson.encode(CanonicalJson.parse("1", new CodecLimits(10, 1, 1))));
        for (String input : List.of("", " null", "null ", "true false", "NaN", "{}{}",
                "{\"b\":null,\"a\":null}", "{\"a\":null,\"a\":true}", "{\"a\":null,}", "[null,]",
                "\"\\/\"", "\"\\u0061\"", "\"\\u000A\"", "\"\\uＦＦＦＦ\"", "\"\\uD800\"",
                "\"\\x01\"", "\"unterminated", "\"line\nbreak\"", "[", "{", "{\"a\"null}", "tru",
                "-0", "1.0", "1e0", "1E+0", "01", "+1", "1.", "1e", "1e+")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> CanonicalJson.parse(input, new CodecLimits(1000, 100, 10)), input);
            assertEquals(null, error.getCause());
        }
        var error = assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.parse("\"private-secret\"trailing", new CodecLimits(100, 10, 5)));
        assertEquals("invalid_runtime_json", error.getMessage());
    }

    /** 三个资源上限各自测试恰好可接纳与少一单位拒绝，字符长度使用 UTF-16 code unit。 */
    @Test
    void checksSizeNodesAndDepthBeforeUnboundedWork() {
        String input = "{\"a\":[true,null,\"😀\"]}";
        assertEquals(input, CanonicalJson.encode(CanonicalJson.parse(input, new CodecLimits(input.length(), 6, 3))));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(input, new CodecLimits(input.length() - 1, 6, 3)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(input, new CodecLimits(input.length(), 5, 3)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(input, new CodecLimits(input.length(), 6, 2)));
        assertEquals("{}", CanonicalJson.encode(CanonicalJson.parse("{}", new CodecLimits(2, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new CodecLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CodecLimits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CodecLimits(1, 1, 129));
    }

    /** 深层恶意输入必须得到受控异常，不触发 JVM StackOverflowError。 */
    @Test
    void boundsDeeplyNestedInputAtTheDeclaredHardLimit() {
        String accepted = "[".repeat(127) + "null" + "]".repeat(127);
        assertEquals(accepted, CanonicalJson.encode(CanonicalJson.parse(accepted, new CodecLimits(1000, 128, 128))));
        String rejected = "[".repeat(500) + "null" + "]".repeat(500);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(rejected, new CodecLimits(2000, 2000, 128)));
    }

    /** 词法为 JSON 字符串也不能掩盖非法整数或窄化溢出。 */
    @Test
    void rejectsIntegerAliasesAndOverflow() {
        for (String value : List.of("01", "+1", "-0", "1.0", "1e2", "１", "9223372036854775808", " 1")) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(value).longValue(), value);
        }
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string("2147483648").intValue());
        assertEquals(Integer.MIN_VALUE, CanonicalJson.string("-2147483648").intValue());
    }
}
