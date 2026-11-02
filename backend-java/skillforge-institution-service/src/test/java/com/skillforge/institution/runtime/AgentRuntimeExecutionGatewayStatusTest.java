package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentRuntimeExecutionGatewayStatusTest {
    private static final ServicePrincipalContext.Identity PRINCIPAL = new ServicePrincipalContext.Identity(
            "principal", "1", "integration", 1, java.util.Set.of("sessions.read"));
    private static final RunRef REF = new RunRef(new TenantId("1"), "run:original");

    @Test void allFourFinishedStatusesReturnExactStatusWithoutReadingHistoryAfterCurrentAuthorization() {
        for (var status : List.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TERMINATED)) {
            var f = fixture(); var run = run(f, status, ExecutionStore.Phase.FINISHED, false);
            when(f.store.load(REF)).thenReturn(Optional.of(run)); clearInvocations(f.store);
            var result = TenantContext.callWithTenant("1", () -> f.gateway.openPlatformStatus(PRINCIPAL, f.session, REF.runId()));
            assertEquals("session", result.sessionId()); assertEquals(REF.runId(), result.runId());
            assertEquals(status.name().toLowerCase(Locale.ROOT), result.status()); assertTrue(result.pendingInteractions().isEmpty());
            verify(f.iam).verify(any());
            verify(f.iam).authorize(argThat(request -> request.action().equals("runtime.run.query")));
            verifyNoInteractions(history(f)); assertOnlyStatusRead(f);
        }
    }

    @Test void everyNonFinishedPhaseIncludingBatchesRetainsHistoryProjectionAndExactRunFiltering() {
        for (var phase : List.of(ExecutionStore.Phase.READY, ExecutionStore.Phase.AWAITING_RESULT,
                ExecutionStore.Phase.WAITING, ExecutionStore.Phase.UNCERTAIN)) {
            for (boolean batch : List.of(false, true)) {
                var status = phase == ExecutionStore.Phase.WAITING || phase == ExecutionStore.Phase.UNCERTAIN
                        ? RunStatus.SUSPENDED : RunStatus.RUNNING;
                var f = fixture(); var run = run(f, status, phase, batch);
                when(f.store.load(REF)).thenReturn(Optional.of(run)); clearInvocations(f.store);
                var own = Map.<String,Object>of("execution_id", REF.runId(), "safe_public_value", "projected");
                var other = Map.<String,Object>of("execution_id", "other-run", "safe_public_value", "other");
                var response = mock(ChatHistoryResponse.class); when(response.pendingInteractions()).thenReturn(List.of(other, own));
                when(history(f).read(f.session)).thenReturn(response);
                var result = TenantContext.callWithTenant("1", () -> f.gateway.openPlatformStatus(PRINCIPAL, f.session, REF.runId()));
                assertEquals(status.name().toLowerCase(Locale.ROOT), result.status()); assertEquals(List.of(own), result.pendingInteractions());
                var ordered = inOrder(f.iam, history(f));
                ordered.verify(f.iam).verify(any()); ordered.verify(f.iam).authorize(any()); ordered.verify(history(f)).read(f.session);
                verifyNoMoreInteractions(history(f)); assertOnlyStatusRead(f);
            }
        }
    }

    @Test void foreignSessionOrEndUserCannotUseFinishedStateToBypassOwnership() {
        for (boolean foreignUser : List.of(false, true)) {
            var f = fixture(); var finished = run(f, RunStatus.COMPLETED, ExecutionStore.Phase.FINISHED, false);
            when(f.store.load(REF)).thenReturn(Optional.of(finished));
            clearInvocations(f.store);
            var session = foreignUser ? f.session.toBuilder().endUserId(99L).build() : f.session.toBuilder().sessionId("foreign").build();
            assertThrows(SecurityException.class, () -> TenantContext.runWithTenant("1", () -> f.gateway.openPlatformStatus(PRINCIPAL, session, REF.runId())));
            verifyNoInteractions(history(f), f.iam, f.access); assertOnlyStatusRead(f);
        }
    }

    @Test void currentIamDenialStillRejectsFinishedRunBeforeHistoryOrAnyExecutionSideEffect() {
        var f = fixture(); var finished = run(f, RunStatus.COMPLETED, ExecutionStore.Phase.FINISHED, false);
        when(f.store.load(REF)).thenReturn(Optional.of(finished));
        when(f.iam.authorize(any())).thenReturn(new IdentityPolicyPort.AuthorizationResult.Denied("revoked", 2, new ResultRef("denied")));
        clearInvocations(f.store);
        assertThrows(SecurityException.class, () -> TenantContext.runWithTenant("1", () -> f.gateway.openPlatformStatus(PRINCIPAL, f.session, REF.runId())));
        verify(f.iam).authorize(argThat(request -> request.action().equals("runtime.run.query")));
        verifyNoInteractions(history(f)); assertOnlyStatusRead(f);
    }

    private static AgentRuntimeExecutionGatewayGoalTest.Fixture fixture() {
        var f = new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        when(f.iam.authorize(any())).thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1, new ResultRef("allowed")));
        return f;
    }
    private static AgentRuntimeExecutionProjection history(AgentRuntimeExecutionGatewayGoalTest.Fixture f) {
        return (AgentRuntimeExecutionProjection) ReflectionTestUtils.getField(f.gateway, "history");
    }
    private static void assertOnlyStatusRead(AgentRuntimeExecutionGatewayGoalTest.Fixture f) {
        verify(f.store).load(REF); verifyNoMoreInteractions(f.store);
        verifyNoInteractions(f.references, f.goals, f.product, f.sessions,
                ReflectionTestUtils.getField(f.gateway, "program"), ReflectionTestUtils.getField(f.gateway, "worker"));
    }
    private static ExecutionStore.Run run(AgentRuntimeExecutionGatewayGoalTest.Fixture f, RunStatus status, ExecutionStore.Phase phase, boolean batch) {
        var identity = f.store.load(REF).orElseThrow().identity();
        var action = new com.skillforge.runtime.api.port.ActionRef(REF.tenantId(), "waiting-action");
        var time = Instant.parse("2026-10-29T00:00:00Z");
        return new ExecutionStore.Run(identity, new ResultRef("configuration"), status, 1, 0, status == RunStatus.CANCELLED, phase,
                new ExecutionStore.Progress(0, 0, 0, new ResultRef("context")), Optional.empty(),
                phase == ExecutionStore.Phase.WAITING && !batch ? Optional.of(action) : Optional.empty(),
                batch ? Optional.of(new ExecutionStore.BatchRef(REF, "source-attempt", 2)) : Optional.empty(),
                phase == ExecutionStore.Phase.WAITING && !batch ? Optional.of(new ExecutionStore.WaitRef(action, 1)) : Optional.empty(),
                new ResultRef("actor"), Optional.empty(), time, time);
    }
}
