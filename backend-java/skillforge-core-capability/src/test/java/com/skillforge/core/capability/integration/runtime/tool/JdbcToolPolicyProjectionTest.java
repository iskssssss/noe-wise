package com.skillforge.core.capability.integration.runtime.tool;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JdbcToolPolicyProjectionTest {
    @Test
    void utf8ByteLengthsPreserveUnicodeAndSeparatorCharacters() {
        var encoded = "3:a:b3:x|y6:工具";

        assertEquals(Set.of("a:b", "x|y", "工具"),
                JdbcToolPolicyProjection.decodeTextSet(encoded));
        assertEquals(List.of("a:b", "x|y", "工具"),
                JdbcToolPolicyProjection.canonicalTextSet(Set.of("工具", "x|y", "a:b")));
        assertEquals(Set.of(), JdbcToolPolicyProjection.decodeTextSet(""));
    }

    @Test
    void malformedUnsortedDuplicateAndNonCanonicalValuesAreRejected() {
        for (String encoded : List.of("1:b1:a", "1:a1:a", "01:a", "0:", "2:a", "1: a")) {
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcToolPolicyProjection.decodeTextSet(encoded));
        }
        assertThrows(IllegalArgumentException.class,
                () -> JdbcToolPolicyProjection.canonicalTextSet(Set.of(" padded ")));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcToolPolicyProjection.canonicalTextSet(Set.of("\uD800")));
    }
}
