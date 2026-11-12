package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeExecutionGatewayUncertainStatusTest {
    @Test void statusReadsUncertainPhaseWithoutChangingRunOrOtherStatuses() {
        var fixture = new AgentRuntimeExecutionGatewayGoalTest.Fixture(true);
        var ref = new RunRef(new TenantId("1"), "run:original");
        var run = fixture.store.load(ref).orElseThrow();
        record Sample(ExecutionStore.Phase phase, RunStatus status, String visible) { }
        for (var sample : List.of(
                new Sample(ExecutionStore.Phase.UNCERTAIN, RunStatus.RUNNING, "outcome_unknown"),
                new Sample(ExecutionStore.Phase.UNCERTAIN, RunStatus.SUSPENDED, "outcome_unknown"),
                new Sample(ExecutionStore.Phase.AWAITING_RESULT, RunStatus.RUNNING, "running"),
                new Sample(ExecutionStore.Phase.WAITING, RunStatus.SUSPENDED, "suspended"),
                new Sample(ExecutionStore.Phase.FINISHED, RunStatus.COMPLETED, "completed"),
                new Sample(ExecutionStore.Phase.FINISHED, RunStatus.CANCELLED, "cancelled"))) {
            when(run.phase()).thenReturn(sample.phase());
            when(run.status()).thenReturn(sample.status());
            clearInvocations(fixture.store);
            var result = TenantContext.callWithTenant("1", () -> fixture.gateway.chatRunStatus(fixture.session, ref.runId()));
            assertEquals(sample.visible(), result.status());
            assertEquals("session", result.sessionId());
            assertEquals(ref.runId(), result.executionId());
            verify(fixture.store).load(ref);
            verifyNoMoreInteractions(fixture.store);
        }
    }
}
