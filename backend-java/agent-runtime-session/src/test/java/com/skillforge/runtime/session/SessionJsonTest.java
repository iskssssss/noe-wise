package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SessionJsonTest {
    @Test void hostIntegerReceiptsRetainExactRevisionAndPrivateControlStrings() {
        var receipt = java.util.Map.of("plan_revision", Long.MAX_VALUE,
                "checkpointRevision", "12", "counts", java.util.List.of(1, (short) 2, (byte) 3));
        String wire = RuntimeJson.writeCanonical(receipt);
        assertEquals("{\"checkpointRevision\":\"12\",\"counts\":[1,2,3],\"plan_revision\":9223372036854775807}", wire);
        assertEquals(wire, RuntimeJson.writeCanonical(RuntimeJson.parseCanonical(wire, SessionFixtures.LIMITS)));
    }

    @Test void hostDataAllowsJsonWhitespaceWithoutChangingStringContentsOrWireRules() {
        String data = " \t\r\n{ \"z\" : [ true , null , 1e1 ], \"a\" : \" space \\/ slash \\n\" } \n";
        assertEquals("{\"a\":\" space / slash \\n\",\"z\":[true,null,10]}",
                RuntimeJson.writeCanonical(RuntimeJson.parse(data, SessionFixtures.LIMITS)));
        assertThrows(IllegalArgumentException.class, () -> RuntimeJson.parseCanonical(data, SessionFixtures.LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parseCanonical("{\"a\": \"value\"}", SessionFixtures.LIMITS));
    }

    @Test void hostDataStillRejectsDuplicateKeysInvalidWhitespaceAndTrailingContent() {
        for (String invalid : java.util.List.of("{ \"a\": 1, \"a\": 2 }", "{} {}", "{\"a\":1 2}",
                "{\"a\":\u00a01}", "[1, ]", "{\"a\":\"line\nvalue\"}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimeJson.parse(invalid, SessionFixtures.LIMITS), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parse(" ".repeat(SessionFixtures.LIMITS.maxCodeUnits()) + "{}", SessionFixtures.LIMITS));
    }

    @Test void naturalReverseAndInsertionOrderedMapsHaveTheSameCanonicalWireValue() {
        var insertion = new LinkedHashMap<String, Object>();
        insertion.put("z", "last"); insertion.put("a", "first");
        var natural = new TreeMap<>(insertion);
        var reverse = new TreeMap<String, Object>(Comparator.reverseOrder());
        reverse.putAll(insertion);
        for (var value : java.util.List.of(insertion, natural, reverse)) {
            assertEquals("{\"a\":\"first\",\"z\":\"last\"}", RuntimeJson.writeCanonical(value));
        }
    }

    @Test void canonicalRoundTripPreservesEscapesAndRejectsReorderedOrDuplicateKeys() {
        var text = "{\"a\":\"line\\n\\\"quoted\\\"\",\"z\":[true,null,\"中文\"]}";
        assertEquals(text, RuntimeJson.writeCanonical(RuntimeJson.parseCanonical(text, SessionFixtures.LIMITS)));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parseCanonical("{\"z\":\"last\",\"a\":\"first\"}", SessionFixtures.LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeJson.parseCanonical("{\"a\":\"first\",\"a\":\"last\"}", SessionFixtures.LIMITS));
    }

    @Test void nestedCanonicalJsonNumbersRoundTripAndRejectAliases() {
        // 规范形式是普通十进制：整十与小数都按原字节保留，指数与尾随零写法不是同一份 wire。
        for (String canonical : java.util.List.of("{\"kind\":\"all\",\"page\":1}", "{\"pageSize\":10}",
                "{\"total\":1200}", "{\"n\":0.25}", "{\"tiny\":0.0000001}", "{\"big\":9007199254740993123456789}",
                "{\"negative\":-10.5}", "{\"zero\":0}")) {
            assertEquals(canonical, RuntimeJson.writeCanonical(RuntimeJson.parseCanonical(canonical, SessionFixtures.LIMITS)), canonical);
        }
        for (String alias : java.util.List.of("{\"page\":1.0}", "{\"page\":01}", "{\"page\":+1}",
                "{\"page\":1e1}", "{\"page\":1E+2}", "{\"page\":10.0}", "{\"zero\":-0}",
                "{\"huge\":1e1000000}")) {
            assertThrows(IllegalArgumentException.class, () -> RuntimeJson.parseCanonical(alias, SessionFixtures.LIMITS), alias);
        }
    }
}
