package com.skillforge.runtime.session.spi;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionAdmissionBudgetTest {
    @Test void zeroBusinessAllowanceIsExplicitAndNegativeAllowanceIsRejected() {
        var frozen = new SessionAdmissionFacts.Budget(0, 1, 0, 10, 10, 20, Duration.ofSeconds(1));
        assertEquals(0, frozen.maxBusinessModelCalls());
        assertEquals(List.of("maxBusinessModelCalls", "maxToolCalls", "maxKnowledgeCalls", "maxInputTokens",
                "maxOutputTokens", "maxTotalTokens", "maxWallTime"), Arrays.stream(SessionAdmissionFacts.Budget.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertThrows(IllegalArgumentException.class, () -> new SessionAdmissionFacts.Budget(-1, 1, 0, 10, 10, 20, Duration.ofSeconds(1)));
    }
}
