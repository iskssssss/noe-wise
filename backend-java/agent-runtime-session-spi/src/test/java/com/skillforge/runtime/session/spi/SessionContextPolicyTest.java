package com.skillforge.runtime.session.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionContextPolicyTest {

    @Test
    void exposesApprovedDefaults() {
        var policy = SessionContextPolicy.defaults();

        assertEquals("context-policy.v2", policy.version());
        assertEquals(12_500, policy.immediateToolTokens());
        assertEquals(8_192, policy.pressureToolCharacters());
        assertEquals(4_096, policy.previewHeadCharacters());
        assertEquals(1_024, policy.previewTailCharacters());
        assertEquals(75, policy.pressurePercent());
        assertEquals(50, policy.summaryTargetPercent());
        assertEquals(20, policy.recentPercent());
    }

    @Test
    void rejectsPoliciesThatCannotReduceContext() {
        assertThrows(IllegalArgumentException.class, () -> new SessionContextPolicy(
                "context-policy.invalid", 1, 5_120, 4_096, 1_024, 80, 60, 16));
        assertThrows(IllegalArgumentException.class, () -> new SessionContextPolicy(
                "context-policy.invalid", 1, 8_192, 4_096, 1_024, 80, 80, 16));
        assertThrows(IllegalArgumentException.class, () -> new SessionContextPolicy(
                "context-policy.invalid", 1, 8_192, 4_096, 1_024, 80, 60, 60));
    }
}
