package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 预算拒绝保留锁内资源原因；公开ABI不允许以历史单字段构造器省略超限事实。 */
class ExecutionBudgetPortContractTest {
    @Test void cumulativePoliciesCannotRepresentObserveOnlyAsNumericLimits() {
        var usage = new Resources(1, 1, 1, 1, 10, 10, 20);
        var limits = new Limits(Scenario.STANDARD, usage, usage, usage, 60, 60, "finite-v1");
        Policy finite = new Policy.Enforced(limits);
        assertSame(limits, finite.requireEnforced());
        assertEquals(Scenario.STANDARD, finite.scenario());
        Policy observed = new Policy.ObserveOnly(Scenario.STANDARD, "observed-v1");
        assertEquals("observed-v1", observed.sourceVersion());
        assertEquals("budget_policy_not_supported", assertThrows(IllegalArgumentException.class,
                observed::requireEnforced).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Policy.ObserveOnly(Scenario.STANDARD, " "));
        assertEquals(List.of("scenario", "sourceVersion"), Arrays.stream(Policy.ObserveOnly.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(List.of("run", "root", "role", "policy"), Arrays.stream(Registration.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test void denialAbiAndStableCodesPreserveScopeAndResource() {
        assertEquals(List.of("reason", "exhaustedDimension"), Arrays.stream(Reservation.Denied.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        String[] names = {"business_model_calls", "auxiliary_model_calls", "tool_calls", "knowledge_calls",
                "input_tokens", "output_tokens", "total_tokens"};
        for (var scope : List.of(Refusal.ROOT_LIMIT, Refusal.RUN_LIMIT)) {
            for (var dimension : Dimension.values()) {
                var denied = new Reservation.Denied(scope, Optional.of(dimension));
                String scopeName = scope == Refusal.ROOT_LIMIT ? "root" : "run";
                assertEquals("execution_budget_" + scopeName + "_" + names[dimension.ordinal()] + "_limit", denied.reasonCode());
                assertEquals(Optional.of(dimension), denied.exhaustedDimension());
            }
            assertThrows(IllegalArgumentException.class, () -> new Reservation.Denied(scope, Optional.empty()));
        }
        for (var reason : List.of(Refusal.CLOSING_REQUIRED, Refusal.ACTIVE_TIME_LIMIT, Refusal.REQUEST_CONFLICT)) {
            assertEquals("execution_budget_" + reason.name().toLowerCase(java.util.Locale.ROOT),
                    new Reservation.Denied(reason, Optional.empty()).reasonCode());
            assertThrows(IllegalArgumentException.class, () -> new Reservation.Denied(reason, Optional.of(Dimension.TOTAL_TOKENS)));
        }
    }

    @Test void firstExceededDimensionUsesActualAmountsAndDeterministicOrder() {
        var capacity = new Resources(1, 1, 1, 1, 10, 10, 20);
        assertTrue(capacity.fits(capacity));
        assertTrue(capacity.firstExceededDimension(capacity).isEmpty());
        for (var first : Dimension.values()) {
            long[] requested = new long[Dimension.values().length];
            for (var dimension : Dimension.values())
                requested[dimension.ordinal()] = capacity.value(dimension) + (dimension.ordinal() >= first.ordinal() ? 1 : 0);
            var amount = Resources.from(requested);
            assertEquals(Optional.of(first), amount.firstExceededDimension(capacity));
            assertFalse(amount.fits(capacity));
        }
    }
}
