package com.skillforge.runtime.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 预期值来自明确的字节规则，而非旧 parser 对拍。 */
class RuntimeJsonTest {
    private static final CodecLimits LIMITS = new CodecLimits(20_000, 1000, 128);

    @Test
    void documentAndCanonicalMaterialHaveSeparateEntrances() {
        String document = " \t{\"z\" : [true, null], \"a\" : \"\\u0061\\/\"}\r\n";
        Object parsed = RuntimeJson.parse(document, LIMITS);
        assertEquals("{\"a\":\"a/\",\"z\":[true,null]}", RuntimeJson.writeCanonical(parsed));
        assertEquals("{\"z\":[true,null],\"a\":\"a/\"}", RuntimeJson.write(parsed));
        assertThrows(IllegalArgumentException.class, () -> RuntimeJson.parseCanonical(document, LIMITS));
        RuntimeJson.parseCanonical("{\"a\":\"a/\",\"z\":[true,null]}", LIMITS);
    }

    @Test
    void numbersKeepPrecisionAndCanonicalIdentity() {
        Object parsed = RuntimeJson.parse("[9007199254740993123456789,1.250,-0,1e2,1e-7]", LIMITS);
        List<?> values = assertInstanceOf(List.class, parsed);
        assertEquals(new BigInteger("9007199254740993123456789"), values.get(0));
        assertEquals(new BigDecimal("1.250"), values.get(1));
        assertEquals("[9007199254740993123456789,1.25,0,100,0.0000001]", RuntimeJson.writeCanonical(parsed));
        for (String nonCanonical : List.of("-0", "1.0", "1e2", "1E+2")) {
            assertThrows(IllegalArgumentException.class, () -> RuntimeJson.parseCanonical(nonCanonical, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> RuntimeJson.writeCanonical(1.25));
    }

    @Test
    void decimalExpansionHasTheExistingThousandDigitBoundary() {
        assertEquals("1" + "0".repeat(999), RuntimeJson.writeCanonical(RuntimeJson.parse("1e999", LIMITS)));
        assertEquals("-1" + "0".repeat(999), RuntimeJson.writeCanonical(RuntimeJson.parse("-1e999", LIMITS)));
        assertEquals("0." + "0".repeat(997) + "1", RuntimeJson.writeCanonical(RuntimeJson.parse("1e-998", LIMITS)));
        for (String oversized : List.of("1e1000", "1e-999", "1e1000000", "1e-1000000")) {
            assertThrows(IllegalArgumentException.class, () -> RuntimeJson.parse(oversized, LIMITS));
        }
    }

    @Test
    void canonicalEscapingAndUtf16KeyOrderingAreStable() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("\ue000", "last");
        fields.put("\ud800\udc00", "first");
        fields.put("a", "\u0000\u000b\u001f\b\t\n\f\r\"\\/中文");
        assertEquals("{\"a\":\"\\u0000\\u000b\\u001f\\b\\t\\n\\f\\r\\\"\\\\/中文\","
                + "\"\ud800\udc00\":\"first\",\"\ue000\":\"last\"}", RuntimeJson.writeCanonical(fields));
    }

    @Test
    void invalidSyntaxAndUnicodeAreRejectedWithoutInputInErrors() {
        for (String input : List.of("", "true false", "01", "+1", "1.", "1e", "[true,]", "{\"a\":1,}",
                "/*x*/true", "\u00a0true", "{\"a\":null,\"\\u0061\":true}", "\"\\ud800\"", "\"\udc00\"")) {
            var error = assertThrows(IllegalArgumentException.class, () -> RuntimeJson.parse(input, LIMITS));
            assertEquals("invalid_runtime_json", error.getMessage());
            assertNull(error.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> RuntimeJson.write("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeJson.write(Map.of("\udc00", true)));
    }

    @Test
    void limitsCountRootKeysValuesAndUtf16CodeUnits() {
        RuntimeJson.parse("{\"a\":null}", new CodecLimits(10, 3, 2));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parse("{\"a\":null}", new CodecLimits(10, 2, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parse("{\"a\":null}", new CodecLimits(10, 3, 1)));
        RuntimeJson.parse("[]", new CodecLimits(2, 1, 1));
        RuntimeJson.parse("\"\ud800\udc00\"", new CodecLimits(4, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parse("\"\ud800\udc00\"", new CodecLimits(3, 1, 1)));
    }

    @Test
    void nullAndMissingRemainDistinctInTheJdkTree() {
        Map<?, ?> fields = assertInstanceOf(Map.class, RuntimeJson.parse("{\"zero\":0,\"present\":null}", LIMITS));
        assertEquals(true, fields.containsKey("present"));
        assertEquals(false, fields.containsKey("missing"));
        assertNull(fields.get("present"));
        assertEquals(BigInteger.ZERO, fields.get("zero"));
    }
}
