package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.runtime.adapter.jdbc.callback.JdbcCallbackRegistrationPort;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.core.chat.session.SessionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

class AgentRuntimeWorkflowCallbackDeliveriesTest {
    @Test void oldIngressAuthenticationDoesNotExpireOriginalHmacEvidenceOrReplaceOriginalEnvelope() throws Exception {
        var fixture=new Delivery();fixture.prove(false);
        var accepted=fixture.subject.acceptVerified(fixture.verified);
        assertInstanceOf(CallbackDeliveryResult.Ready.class,accepted);
        verify(fixture.recovery).currentActor(fixture.parent.identity(),fixture.envelope.inputId());
        verify(fixture.callbacks).confirmWorkflowDelivery(fixture.sql,fixture.receipt.deliveryRef(),fixture.registration,fixture.envelope);
        assertEquals(new AuthenticationRef("expired-original-ingress-auth"),fixture.envelope.authenticationRef());
        assertEquals(fixture.oldTime,fixture.envelope.receivedAt());
    }
    @Test void missingHmacProofCannotBeMadeValidByFreshWorkerIdentity() throws Exception {
        var fixture=new Delivery();fixture.prove(true);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.subject.acceptVerified(fixture.verified));
        verify(fixture.callbacks).confirmWorkflowDelivery(fixture.sql,fixture.receipt.deliveryRef(),fixture.registration,fixture.envelope);
    }
    @Test void currentWorkerAuthorizationRejectionLeavesOriginalDeliveryUnconfirmed() throws Exception {
        var fixture=new Delivery();
        when(fixture.recovery.currentActor(fixture.parent.identity(),fixture.envelope.inputId())).thenThrow(new SecurityException("current_iam_rejected"));
        assertThrows(SecurityException.class,()->fixture.subject.acceptVerified(fixture.verified));
        verify(fixture.callbacks,never()).confirmWorkflowDelivery(any(),any(),any(),any());
        verify(fixture.recovery,never()).respond(any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void filteredFullPageStillAdvancesActualDeliveryCursorAndEmptyPageRestartsBoundedScan() {
        var registry=mock(JdbcCallbackRegistrationPort.class);var recovery=mock(AgentRuntimeWorkflowRecoveryWorker.class);
        var subject=new AgentRuntimeWorkflowCallbackDeliveries(registry,mock(ExecutionStore.class),recovery,mock(SessionService.class));
        var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("1"),new EndUserId(7));
        var first=new CallbackReadyDelivery(new CallbackDeliveryRef(tenant,"delivery:1"),new CallbackRef(tenant,"other:1"),owner,1,Instant.EPOCH);
        var second=new CallbackReadyDelivery(new CallbackDeliveryRef(tenant,"delivery:2"),new CallbackRef(tenant,"other:2"),owner,1,Instant.EPOCH);
        when(registry.scanReadyAfter(tenant,"",2)).thenReturn(List.of(first,second));
        when(registry.scanReadyAfter(tenant,"delivery:2",2)).thenReturn(List.of());
        when(registry.find(first.callbackRef())).thenReturn(Optional.empty());when(registry.find(second.callbackRef())).thenReturn(Optional.empty());
        subject.recover(tenant,2);subject.recover(tenant,2);subject.recover(tenant,2);
        verify(registry,times(2)).scanReadyAfter(tenant,"",2);verify(registry).scanReadyAfter(tenant,"delivery:2",2);
        verify(registry,never()).delivery(any());verifyNoInteractions(recovery);
        assertThrows(IllegalArgumentException.class,()->subject.recover(tenant,101));
    }
    @Test void nonWorkflowDeliveryNeverEntersTypedAcceptance() {
        var registry=mock(JdbcCallbackRegistrationPort.class);
        var subject=new AgentRuntimeWorkflowCallbackDeliveries(registry,mock(ExecutionStore.class),mock(AgentRuntimeWorkflowRecoveryWorker.class),mock(SessionService.class));
        var verified=mock(VerifiedCallbackDelivery.class);var callback=new CallbackRef(new TenantId("1"),"unknown");when(verified.callbackRef()).thenReturn(callback);
        when(registry.find(callback)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,()->subject.acceptVerified(verified));verify(registry,never()).acceptVerified(any());
    }
    private static final class Delivery {
        final Instant oldTime=Instant.parse("2026-01-01T00:00:00Z");
        final TenantId tenant=new TenantId("1");final SessionOwner owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("1"),new EndUserId(7));
        final InputId originalInput=new InputId("original");final RunRef run=new RunRef(tenant,"parent");
        final RunIdentity identity=new RunIdentity(owner, new AgentId((owner).target().id()), originalInput, run, new TraceEnvelope("trace","span",originalInput,Optional.empty()), new ResultRef("original-verification"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (originalInput).value()));
        final ExecutionRef executionRef=new ExecutionRef(tenant,"workflow");
        final ExecutionStore.WaitRef wait=new ExecutionStore.WaitRef(new ActionRef(tenant,"source-action"),3);
        final ExecutionStore.Run parent=new ExecutionStore.Run(identity,new ResultRef("configuration"),RunStatus.SUSPENDED,9,1,false,ExecutionStore.Phase.WAITING,
                new ExecutionStore.Progress(1,0,0,new ResultRef("context")),Optional.empty(),Optional.of(wait.action()),Optional.empty(),Optional.of(wait),identity.causationIdentityRef(),Optional.empty(),oldTime,oldTime);
        final ExecutionStore.Waiting waiting=new ExecutionStore.Waiting(wait,ExecutionStore.WaitKind.WORKFLOW,run,"source-attempt",Map.of(),
                Optional.of(new ExecutionStore.WorkflowWait(executionRef,"original-suspend","engine-action",17)),Optional.empty(),Optional.empty(),OptionalLong.empty());
        final CallbackRef callback=new CallbackRef(tenant,"callback");
        final CallbackRegistration registration=new CallbackRegistration(new CallbackRegistrationDraft(callback,owner,new AgentId("1"),
                new CallbackTarget.ExecutionTarget(executionRef,run,"engine-action",17),"workflow-async-task",CallbackPayloads.EXECUTION_SCHEMA,CallbackPayloads.SIGNATURE_POLICY,new CredentialRef(tenant,"credential")),
                identity.causationIdentityRef(),CallbackRegistrationStatus.ACTIVE,1,oldTime,oldTime);
        final VerifiedIdentity current=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant,"current-worker"),owner,Set.of("sessions.write"),2,new ResultRef("current-worker-verification"));
        final JdbcCallbackRegistrationPort callbacks=mock(JdbcCallbackRegistrationPort.class);final ExecutionStore store=mock(ExecutionStore.class);
        final AgentRuntimeWorkflowRecoveryWorker recovery=mock(AgentRuntimeWorkflowRecoveryWorker.class);final SessionService sessions=mock(SessionService.class);
        final SqlSession sql=mock(SqlSession.class);final AgentRuntimeWorkflowCallbackDeliveries subject=new AgentRuntimeWorkflowCallbackDeliveries(callbacks,store,recovery,sessions);
        final InboundEnvelope envelope;final CallbackDeliveryReceipt receipt;final VerifiedCallbackDelivery verified;
        final TypedPayload response=new TypedPayload(com.skillforge.runtime.session.spi.SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA,"{\"result\":{},\"status\":\"completed\"}");
        Delivery() throws Exception {
            var input=new InputId("signed-callback-input");var body=new ObjectMapper().writeValueAsString(List.of("1","workflow","parent","engine-action","17",response.schemaId(),response.canonicalJson()));
            envelope=new InboundEnvelope(new AuthenticationRef("expired-original-ingress-auth"),Optional.of(identity.causationIdentityRef()),owner.sessionRef(),owner.target(),new AgentId("1"),owner.subject(),identity.requester(),input,new EventId("original-callback"),
                    InboundKind.CALLBACK,new RunTarget.ExistingRun(run),"callback","workflow-async-task",new TraceEnvelope("callback-trace","callback-span",input,Optional.empty()),new TypedPayload(CallbackPayloads.EXECUTION_SCHEMA,body),oldTime,identity.sourceMessageRef(),identity.originRef());
            receipt=new CallbackDeliveryReceipt(callback,new CallbackDeliveryRef(tenant,"delivery"),CallbackDeliveryStatus.READY,1,Optional.of(envelope),Optional.empty(),Optional.empty(),oldTime);
            verified=new VerifiedCallbackDelivery(callback,1,receipt.deliveryRef(),"original-proof-fingerprint",envelope,oldTime);
            when(callbacks.find(callback)).thenReturn(Optional.of(registration));var accepted=new CallbackDeliveryResult.Ready(receipt);when(callbacks.acceptVerified(verified)).thenReturn(accepted);
            when(store.load(run)).thenReturn(Optional.of(parent));when(store.waiting(run,wait)).thenReturn(Optional.of(waiting));
            when(sessions.requireForSystem("1","session")).thenReturn(org.mockito.Mockito.mock(SessionView.class));when(recovery.currentActor(identity,input)).thenReturn(current);
        }
        void prove(boolean missing) {
            if(missing)doThrow(new ExecutionStore.Rejection("callback_workflow_proof_missing")).when(callbacks).confirmWorkflowDelivery(sql,receipt.deliveryRef(),registration,envelope);
            when(recovery.respond(eq(parent),eq(current),eq(envelope.inputId()),eq(wait),eq(parent.revision()),eq(response),any())).thenAnswer(call -> {
                var actual=new ExecutionRecord(executionRef,identity,"unit","definition",ExecutionMode.FOREGROUND,Optional.of(wait.action()),Optional.empty(),WorkflowExecutionState.WAITING_EXTERNAL,
                        17,new TypedPayload("checkpoint","{}"),Optional.of(new ExecutionWait("original-suspend","engine-action")),Optional.of(new ResultRef("wait-result")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),oldTime,oldTime);
                call.<AgentRuntimeWorkflowRecoveryWorker.ResponseCommit>getArgument(6).confirm(sql,actual);
                return new ExecutionStore.ResumeResult(parent,waiting,true);
            });
        }
    }
}
