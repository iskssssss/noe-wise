package com.skillforge.runtime.starter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentRuntimeAdmissionBudgetTest {
    @Test void publishedZeroBusinessAllowanceIsNotReplacedWithASyntheticCall() {
        var budget = new AgentRuntimeAdmissionConfiguration.Budget(0, 1, 0, 10, 10, 20, Duration.ofSeconds(1));
        assertEquals(0, budget.maxBusinessModelCalls());
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeAdmissionConfiguration.Budget(-1, 1, 0, 10, 10, 20, Duration.ofSeconds(1)));
    }
}
