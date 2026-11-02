package com.skillforge.core.foundation.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Entry identity is explicit, positive, and never synthesized by the runtime. */
class EndUserIdentityTest {

    @Test
    void acceptsNumericEntryValueAndCanonicalizesIt() {
        assertEquals(42L, EndUserIdentity.require("0042", "test"));
        assertEquals(42L, EndUserIdentity.require(Map.of("end_user_id", 42), "test"));
    }

    @Test
    void rejectsMissingInvalidAndNonPositiveEntryValues() {
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require(Map.of(), "test"));
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require("not-a-number", "test"));
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require(0, "test"));
        // 身份边界不得把小数、非有限数或超范围值截断/饱和成另一个用户 ID。
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require(100.9D, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require(Double.NaN, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require(Double.POSITIVE_INFINITY, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> EndUserIdentity.require(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), "test"));
    }
}
