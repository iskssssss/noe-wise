package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.SessionExecutionPlans;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 定向通知只在业务提交后读取；补偿扫描有界且不能冒用普通人类等待。 */
class AgentRuntimePlanDependenciesTest {
    @Test
    void businessTransactionDefersObservationUntilSuccessfulCommit() {
        var fixture = new Fixture(); var run = new RunRef(fixture.tenant, "plan-notification");
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            fixture.dependencies.changed(run); verifyNoInteractions(fixture.store);
            TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCommit());
            verify(fixture.store).load(run);
        } finally {
            TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void humanWaitCannotBeResolvedByPlanNotification() {
        var fixture = new Fixture(); var reference = new RunRef(fixture.tenant, "human-wait");
        var wait = new ExecutionStore.WaitRef(new ActionRef(fixture.tenant, "human-action"), 1);
        var run = mock(ExecutionStore.Run.class); when(run.phase()).thenReturn(ExecutionStore.Phase.WAITING); when(run.waiting()).thenReturn(Optional.of(wait));
        var waiting = mock(ExecutionStore.Waiting.class); when(waiting.kind()).thenReturn(ExecutionStore.WaitKind.HUMAN);
        when(fixture.store.load(reference)).thenReturn(Optional.of(run)); when(fixture.store.waiting(reference, wait)).thenReturn(Optional.of(waiting));
        fixture.dependencies.changed(reference);
        verify(fixture.store, never()).acceptDependency(any(), any());
        verifyNoInteractions(fixture.plans, fixture.bindings, fixture.identities, fixture.workers, fixture.mapper);
    }

    @Test
    void planWaitWithoutExactOriginalBusinessEventCannotProduceSystemResponse() {
        var fixture = new Fixture(); var reference = new RunRef(fixture.tenant, "plan-wait"); var input = new InputId("plan-input");
        var owner = SessionOwner.user(new SessionRef(fixture.tenant, "plan-session"), new AgentId("2"), new EndUserId(3));
        var identity = new RunIdentity(owner, new AgentId((owner).target().id()), input, reference, new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var wait = new ExecutionStore.WaitRef(new ActionRef(fixture.tenant, "model-action"), 1);
        var dependency = new ExecutionStore.PlanWait("work", 3, "delegation-wait:model-action");
        var run = mock(ExecutionStore.Run.class); when(run.phase()).thenReturn(ExecutionStore.Phase.WAITING);
        when(run.waiting()).thenReturn(Optional.of(wait)); when(run.identity()).thenReturn(identity);
        var waiting = mock(ExecutionStore.Waiting.class); when(waiting.kind()).thenReturn(ExecutionStore.WaitKind.PLAN);
        when(waiting.internal()).thenReturn(Optional.of(dependency));
        when(fixture.store.load(reference)).thenReturn(Optional.of(run)); when(fixture.store.waiting(reference, wait)).thenReturn(Optional.of(waiting));
        var binding = mock(AgentRunBindings.Binding.class); when(binding.owner()).thenReturn(owner); when(binding.actor()).thenReturn(identity.agentId());
        when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY); when(fixture.bindings.find(reference)).thenReturn(Optional.of(binding));
        fixture.dependencies.changed(reference);
        verify(fixture.mapper).source(identity, dependency, "plan-wait:model-action", false);
        verify(fixture.store, never()).acceptDependency(any(), any()); verifyNoInteractions(fixture.plans, fixture.identities, fixture.workers);
    }

    @Test
    void boundedRecoveryUsesKeysetCursorAndNeverCreatesExecutionWork() {
        var fixture = new Fixture();
        when(fixture.mapper.waiting("1", "", 2)).thenReturn(List.of("plan-a", "plan-b"));
        when(fixture.mapper.waiting("1", "plan-b", 2)).thenReturn(List.of());
        when(fixture.store.load(any())).thenAnswer(call -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("1"); return Optional.empty();
        });
        fixture.dependencies.recover(fixture.tenant, 2); fixture.dependencies.recover(fixture.tenant, 2); fixture.dependencies.recover(fixture.tenant, 2);
        verify(fixture.mapper, times(2)).waiting("1", "", 2); verify(fixture.mapper).waiting("1", "plan-b", 2);
        verifyNoInteractions(fixture.workers, fixture.plans);
        assertThatThrownBy(() -> fixture.dependencies.recover(fixture.tenant, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    private static final class Fixture {
        private final TenantId tenant = new TenantId("1");
        private final JdbcExecutionStore store = mock(JdbcExecutionStore.class);
        @SuppressWarnings("unchecked") private final ObjectProvider<InstitutionPlanHost> plans = mock(ObjectProvider.class);
        private final AgentRunBindings bindings = mock(AgentRunBindings.class);
        private final JdbcIdentityPolicyPort identities = mock(JdbcIdentityPolicyPort.class);
        @SuppressWarnings("unchecked") private final ObjectProvider<AgentRuntimeExecutionWorker> workers = mock(ObjectProvider.class);
        private final AgentRuntimePlanDependencies.Mapper mapper = mock(AgentRuntimePlanDependencies.Mapper.class);
        private final AgentRuntimePlanDependencies dependencies = new AgentRuntimePlanDependencies(store, plans, bindings,
                mock(AgentRuntimeHostProvisioner.class), identities, workers, mapper, new ObjectMapper(),
                new SessionExecutionPlans(new CodecLimits(100_000, 10_000, 20)), "worker");

        private Fixture() { when(store.load(any())).thenReturn(Optional.empty()); }
    }
}
