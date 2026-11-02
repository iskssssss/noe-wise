package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** 精确Workflow依赖只接受当前真实等待，恢复观察不拥有Engine执行权。 */
class AgentRuntimeWorkflowRecoveryWorkerTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final RunIdentity IDENTITY = new RunIdentity(SessionOwner.user(new SessionRef(TENANT,"session"),new AgentId("1"),new EndUserId(7)), new AgentId((SessionOwner.user(new SessionRef(TENANT,"session"),new AgentId("1"),new EndUserId(7))).target().id()), new InputId("original"), new RunRef(TENANT,"parent"), new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()), new ResultRef("verified"), new RunRequester.EndUser((SessionOwner.user(new SessionRef(TENANT,"session"),new AgentId("1"),new EndUserId(7))).requireUser()), java.util.Optional.empty(), new RunOriginRef((SessionOwner.user(new SessionRef(TENANT,"session"),new AgentId("1"),new EndUserId(7))).sessionRef().tenantId(), "input:" + (new InputId("original")).value()));
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT,"workflow");
    private static final ExecutionStore.WaitRef WAIT = new ExecutionStore.WaitRef(new ActionRef(TENANT,"source-action"),3);
    private static final ExecutionStore.WorkflowWait EXACT = new ExecutionStore.WorkflowWait(EXECUTION,"suspend:real","engine:action",17);
    private static final VerifiedIdentity ACTOR = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT,"worker"),IDENTITY.owner(),Set.of("sessions.write"),1,new ResultRef("current-verified"));

    @Test void exactWaitRejectsDifferentExecutionRevisionOrActionAndCancelledParent() {
        assertDoesNotThrow(()->AgentRuntimeWorkflowRecoveryWorker.requireWaiting(run(false),waiting(EXACT),execution(17,"engine:action")));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeWorkflowRecoveryWorker.requireWaiting(run(false),waiting(EXACT),execution(18,"engine:action")));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeWorkflowRecoveryWorker.requireWaiting(run(false),waiting(EXACT),execution(17,"different")));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeWorkflowRecoveryWorker.requireWaiting(run(true),waiting(EXACT),execution(17,"engine:action")));
    }
    @Test void observationInputUsesRealParentActionGenerationAndExecutionRevision() {
        var input = AgentRuntimeWorkflowRecoveryWorker.observationInput(IDENTITY.runRef(),WAIT,EXACT);
        assertEquals(input,AgentRuntimeWorkflowRecoveryWorker.observationInput(IDENTITY.runRef(),WAIT,EXACT));
        assertNotEquals(input,AgentRuntimeWorkflowRecoveryWorker.observationInput(IDENTITY.runRef(),new ExecutionStore.WaitRef(WAIT.action(),4),EXACT));
        assertNotEquals(input,AgentRuntimeWorkflowRecoveryWorker.observationInput(IDENTITY.runRef(),WAIT,new ExecutionStore.WorkflowWait(EXECUTION,EXACT.suspendRef(),EXACT.actionKey(),18)));
    }
    @Test void cancelledOrAbsentParentNeverAuthenticatesOrObservesAnExternalExecution() {
        var fixture = new Fixture(); when(fixture.store.load(IDENTITY.runRef())).thenReturn(Optional.empty());
        fixture.subject.wake(IDENTITY.runRef());
        when(fixture.store.load(IDENTITY.runRef())).thenReturn(Optional.of(run(true)));
        fixture.subject.wake(IDENTITY.runRef());
        verify(fixture.cancellations).changed(IDENTITY.runRef());
        verifyNoInteractions(fixture.identities,fixture.executions,fixture.workflows,fixture.worker);
    }
    @Test void responseProvesSameSqlSessionExecutionAndCurrentIamBeforeAtomicDeliveryCommit() {
        var fixture = new Fixture(); var sql = mock(SqlSession.class); var execution = execution(17,"engine:action");
        var commit = mock(AgentRuntimeWorkflowRecoveryWorker.ResponseCommit.class);
        var input = new InputId("actual-response"); var response = new TypedPayload("application/json","{\"answer\":\"真实响应\"}");
        when(fixture.identities.authorize(eq(sql),any())).thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1,new ResultRef("allowed")));
        when(fixture.store.acceptDependency(any(),any())).thenAnswer(call -> {
            ExecutionStore.Resume command = call.getArgument(0); JdbcExecutionStore.DependencyGuard proof = call.getArgument(1);
            assertEquals(ACTOR,command.actor()); assertEquals(input,command.input()); assertEquals(response,command.response().content());
            proof.require(sql,run(false),waiting(EXACT),List.of(),command.response());
            return new ExecutionStore.ResumeResult(run(false),waiting(EXACT),true);
        });
        try (var ledger = mockStatic(JdbcExecutionLedgerPort.class)) {
            ledger.when(()->JdbcExecutionLedgerPort.lockBorrowed(sql,EXECUTION)).thenReturn(new JdbcExecutionLedgerPort.LockedExecution(execution,NOW));
            assertTrue(fixture.subject.respond(run(false),ACTOR,input,WAIT,9,response,commit).accepted());
        }
        verify(fixture.workflows).requireResumeResponse(sql,ACTOR,execution,response);
        verify(commit).confirm(sql,execution); verify(fixture.worker).wake(IDENTITY.runRef());
        verify(fixture.workflows,never()).executeQueued(any(),any(),any());
    }
    @Test void staleExecutionCannotConfirmCallbackOrWakeParent() {
        var fixture = new Fixture(); var sql = mock(SqlSession.class); var commit = mock(AgentRuntimeWorkflowRecoveryWorker.ResponseCommit.class);
        when(fixture.store.acceptDependency(any(),any())).thenAnswer(call -> {
            ExecutionStore.Resume command=call.getArgument(0); JdbcExecutionStore.DependencyGuard proof=call.getArgument(1);
            proof.require(sql,run(false),waiting(EXACT),List.of(),command.response()); return null;
        });
        try(var ledger=mockStatic(JdbcExecutionLedgerPort.class)) {
            ledger.when(()->JdbcExecutionLedgerPort.lockBorrowed(sql,EXECUTION)).thenReturn(new JdbcExecutionLedgerPort.LockedExecution(execution(18,"engine:action"),NOW));
            assertThrows(IllegalArgumentException.class,()->fixture.subject.respond(run(false),ACTOR,new InputId("response"),WAIT,9,new TypedPayload("application/json","{}"),commit));
        }
        verifyNoInteractions(commit,fixture.worker,fixture.workflows);
    }
    @Test void currentWorkflowIamRejectionCannotConfirmOriginalCallbackProofOrWake() {
        var fixture=new Fixture();var sql=mock(SqlSession.class);var commit=mock(AgentRuntimeWorkflowRecoveryWorker.ResponseCommit.class);
        when(fixture.identities.authorize(eq(sql),any())).thenReturn(new IdentityPolicyPort.AuthorizationResult.Denied("workflow_permission_revoked",2,new ResultRef("denied")));
        when(fixture.store.acceptDependency(any(),any())).thenAnswer(call -> {
            ExecutionStore.Resume command=call.getArgument(0);JdbcExecutionStore.DependencyGuard proof=call.getArgument(1);
            proof.require(sql,run(false),waiting(EXACT),List.of(),command.response());return null;
        });
        var execution=execution(17,"engine:action");
        try(var ledger=mockStatic(JdbcExecutionLedgerPort.class)) {
            ledger.when(()->JdbcExecutionLedgerPort.lockBorrowed(sql,EXECUTION)).thenReturn(new JdbcExecutionLedgerPort.LockedExecution(execution,NOW));
            assertThrows(IllegalArgumentException.class,()->fixture.subject.respond(run(false),ACTOR,new InputId("response"),WAIT,9,new TypedPayload("application/json","{}"),commit));
        }
        verifyNoInteractions(commit,fixture.worker,fixture.workflows);
    }
    private static ExecutionStore.Run run(boolean cancelled) {
        return new ExecutionStore.Run(IDENTITY,new ResultRef("configuration"),RunStatus.SUSPENDED,9,1,cancelled,ExecutionStore.Phase.WAITING,
                new ExecutionStore.Progress(1,0,0,new ResultRef("context")),Optional.empty(),Optional.of(WAIT.action()),Optional.empty(),Optional.of(WAIT),ACTOR.verificationRef(),Optional.empty(),NOW,NOW);
    }
    private static ExecutionStore.Waiting waiting(ExecutionStore.WorkflowWait exact) {
        return new ExecutionStore.Waiting(WAIT,ExecutionStore.WaitKind.WORKFLOW,IDENTITY.runRef(),"source-attempt",Map.of(),Optional.of(exact),Optional.empty(),Optional.empty(),OptionalLong.empty());
    }
    private static ExecutionRecord execution(long revision,String action) {
        return new ExecutionRecord(EXECUTION,IDENTITY,"unit","definition",ExecutionMode.FOREGROUND,Optional.of(WAIT.action()),Optional.empty(),WorkflowExecutionState.WAITING_EXTERNAL,
                revision,new TypedPayload("private-checkpoint","{}"),Optional.of(new ExecutionWait(EXACT.suspendRef(),action)),Optional.of(new ResultRef("real-wait-result")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),NOW,NOW);
    }
    private static final class Fixture {
        final JdbcExecutionStore store=mock(JdbcExecutionStore.class); final JdbcExecutionLedgerPort executions=mock(JdbcExecutionLedgerPort.class);
        final JdbcIdentityPolicyPort identities=mock(JdbcIdentityPolicyPort.class); final SdkWorkflowPortal workflows=mock(SdkWorkflowPortal.class);
        final AgentRuntimeWorkflowNodeExecutions nodes=mock(AgentRuntimeWorkflowNodeExecutions.class); final AgentRuntimeExecutionWorker worker=mock(AgentRuntimeExecutionWorker.class);
        final AgentRuntimeWorkflowCancellations cancellations=mock(AgentRuntimeWorkflowCancellations.class);
        final AgentRuntimeProperties properties=new AgentRuntimeProperties();
        final AgentRuntimeWorkflowRecoveryWorker subject;
        Fixture() {
            @SuppressWarnings("unchecked") var provider=(ObjectProvider<AgentRuntimeExecutionWorker>)mock(ObjectProvider.class); when(provider.getObject()).thenReturn(worker);
            @SuppressWarnings("unchecked") var cancellationProvider=(ObjectProvider<AgentRuntimeWorkflowCancellations>)mock(ObjectProvider.class); when(cancellationProvider.getObject()).thenReturn(cancellations);
            subject=new AgentRuntimeWorkflowRecoveryWorker(store,executions,identities,properties,mock(AgentRuntimeHostProvisioner.class),workflows,nodes,mock(WorkflowNodeRecoveryMapper.class),provider,cancellationProvider,Clock.fixed(NOW,ZoneOffset.UTC),new ObjectMapper());
        }
    }
}
