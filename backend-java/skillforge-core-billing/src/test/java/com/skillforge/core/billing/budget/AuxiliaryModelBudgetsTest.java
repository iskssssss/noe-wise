package com.skillforge.core.billing.budget;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;

import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证真实辅助守卫装配：可信根、次数分类、用量、未知和禁止借业务额度。 */
class AuxiliaryModelBudgetsTest {
    private final ExecutionBudgetPort port = mock(ExecutionBudgetPort.class);
    private final ExternalInvocationLedger ledger = mock(ExternalInvocationLedger.class);
    private final AuxiliaryModelBudgets budgets = new AuxiliaryModelBudgets(port, mock(OperationalSettingsService.class));
    private final ExternalInvocationGuard guard = new ExternalInvocationGuard(ledger, budgets);
    private final RunRef run = new RunRef(new TenantId("7"), "task-run");
    private final AuxiliaryModelBudgets.Request request = AuxiliaryModelBudgets.Request.freeze(1000,100,
            "summary", "model:3", List.of("真实输入"));

    private ExternalInvocationRequest invocation() {
        return new ExternalInvocationRequest("summary", "summary:attempt", ExternalOperationType.MODEL_CHAT,
                "provider", "model", "summary", new TraceContext("input","root","trace","span","event",null), Map.of());
    }
    private void grant() {
        when(port.reserve(any())).thenAnswer(call -> new Reservation.Granted(new Permit(call.getArgument(0), AttemptState.RESERVED)));
        when(port.markSent(run,"summary:attempt")).thenReturn(true);
        when(ledger.startAuxiliary(eq("7"),any(),any())).thenReturn(true);
    }

    @Test void standaloneAuxiliaryRegistersExplicitEnforcedMaintenancePolicy() {
        var settings = mock(OperationalSettingsService.class);
        when(settings.read(ChatBudgetSettings.GROUP)).thenReturn(new OperationalSettingsService.Snapshot(1, Map.of()));
        grant();
        try (var attempt = new AuxiliaryModelBudgets(port, settings).reserve("7", "summary", "summary:attempt", request)) {
            var registration = ArgumentCaptor.forClass(Registration.class);
            verify(port).register(registration.capture());
            assertInstanceOf(Policy.Enforced.class, registration.getValue().policy());
            var limits = registration.getValue().policy().requireEnforced();
            assertEquals(Scenario.MAINTENANCE, limits.scenario());
            assertEquals(0, limits.run().businessModels());
            assertEquals(1_024, limits.run().auxiliaryModels());
            assertEquals(20_000_000, limits.run().totalTokens());
            assertEquals("budget-policy.v4:maintenance", limits.sourceVersion());
        }
    }

    @Test void actualUsageSettlesAuxiliaryInTrustedTaskRoot() throws Exception {
        grant();
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port,run))) {
            assertEquals("完成", guard.invokeModel("7",invocation(),request,() -> {
                AuxiliaryModelBudgets.captureUsage(new LlmUsage(73,12,85)); return "完成";
            }));
        }
        var demand = ArgumentCaptor.forClass(Demand.class);
        verify(port).reserve(demand.capture());
        assertEquals(run,demand.getValue().run());
        assertEquals(Category.AUXILIARY_MODEL,demand.getValue().category());
        assertEquals(0,demand.getValue().resources().businessModels());
        assertFalse(demand.getValue().closing());
        var usage = ArgumentCaptor.forClass(Usage.class);
        verify(port).saveResult(eq(run),eq("summary:attempt"),usage.capture(),anyString());
        assertEquals(new Usage(OptionalLong.of(73),OptionalLong.of(12),OptionalLong.of(85)),usage.getValue());
        var order=inOrder(port,ledger);
        order.verify(port).recover(run); order.verify(port).reserve(any());
        order.verify(ledger).startAuxiliary(eq("7"),any(),any()); order.verify(port).markSent(run,"summary:attempt");
        order.verify(port).saveResult(eq(run),anyString(),any(),anyString());
        order.verify(port).settle(run,"summary:attempt");
    }

    @Test void unknownOutcomeRetainsReservationWithoutInventingUsage() {
        grant();
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port,run))) {
            assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                    () -> guard.invokeModel("7",invocation(),request,() -> {
                        throw new ExternalInvocationGuard.OutcomeUnknownException("unknown",null);
                    }));
        }
        verify(port,never()).saveResult(any(),anyString(),any(),anyString());
        verify(port,never()).settle(any(),anyString());
        verify(ledger).finish(eq("7"),anyString(),eq(ExternalInvocationStatus.UNRESOLVED),isNull(),anyString(),any());
    }

    @Test void exhaustedAuxiliaryNeverFallsBackToBusinessOrCallsProvider() {
        when(port.reserve(any())).thenReturn(new Reservation.Denied(Refusal.RUN_LIMIT,Optional.of(Dimension.AUXILIARY_MODELS)));
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port,run))) {
            var rejected=assertThrows(IllegalStateException.class,() -> guard.invokeModel("7",invocation(),request,() -> {
                fail("辅助耗尽不能调用供应商"); return null;
            }));
            assertEquals("execution_budget_run_auxiliary_model_calls_limit",rejected.getMessage());
        }
        verifyNoInteractions(ledger);
        verify(port,never()).markSent(any(),anyString());
    }

    @Test void fingerprintBindsSegmentationAndFrozenOutput() {
        var first=AuxiliaryModelBudgets.Request.freeze(100,10,"summary","model:3",List.of("ab","c"));
        assertNotEquals(first.fingerprint(),AuxiliaryModelBudgets.Request.freeze(100,10,"summary","model:3",List.of("a","bc")).fingerprint());
        assertNotEquals(first.fingerprint(),AuxiliaryModelBudgets.Request.freeze(100,20,"summary","model:3",List.of("ab","c")).fingerprint());
        assertEquals(first,AuxiliaryModelBudgets.Request.freeze(100,10,"summary","model:3",List.of("ab","c")));
    }
}
