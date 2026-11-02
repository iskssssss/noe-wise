package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentRuntimeWorkflowCancellationsTest {
    private final TenantId tenant=new TenantId("1");
    private final SessionOwner owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(1));
    private final InputId input=new InputId("input");
    private final RunIdentity identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("original-actor"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
    private final ActionRef action=new ActionRef(tenant,"workflow-action");
    private final Instant now=Instant.parse("2026-10-22T00:00:00Z");
    private final ExecutionStore store=mock(ExecutionStore.class);
    private final SdkWorkflowPortal sdk=mock(SdkWorkflowPortal.class);
    private final JdbcIdentityPolicyPort identities=mock(JdbcIdentityPolicyPort.class);
    private final AgentRuntimeHostProvisioner provisioner=mock(AgentRuntimeHostProvisioner.class);
    private final AgentRuntimeProperties properties=mock(AgentRuntimeProperties.class);
    private final WorkflowCancellationMapper mapper=mock(WorkflowCancellationMapper.class);
    private final SessionExecutionWorkflows codec=new SessionExecutionWorkflows(new CodecLimits(262144,20000,64));

    @Test void activeOrUnownedRunCannotAcquireAWorkflowCancellationIdentity() {
        when(store.load(identity.runRef())).thenReturn(Optional.of(run(false)));
        subject().changed(identity.runRef());
        verifyNoInteractions(sdk,identities,provisioner,mapper);
    }

    @Test void committedCancellationReadsOriginalDispatchAndUsesOnlyPurposeBuiltCleanup() {
        var target=new WorkflowPortal.FrozenWorkflowTarget(new WorkflowPortal.WorkflowHandle("workflow:1"),"instance",1,
                new CapabilityRef(CapabilityType.WORKFLOW,"1","1"),Duration.ofSeconds(30),1);
        var execution=new ExecutionRef(tenant,"actual-execution");
        var dispatch=new SessionExecutionWorkflows.Dispatch(new CapabilitySnapshotRef(owner.sessionRef(),input,"snapshot",1),target,execution,
                new TypedPayload("application/json","{}"),List.of(),"actual-model-call","run_workflow",new TypedPayload("application/json","{\"handle\":\"workflow:1\"}"));
        var material=new ExecutionStore.Material(new ResultRef("original-workflow-request"),codec.encode(dispatch));
        var fingerprint=AgentExecutionCodec.digest(material.content().schemaId()+"\0"+material.content().canonicalJson());
        var original=new ExecutionStore.Action(action,identity.runRef(),1,ExecutionStore.Kind.WORKFLOW,"workflow:1",new ResultRef("capability"),material.reference(),fingerprint,
                ExecutionStore.ActionStatus.WAITING_INPUT,"original-attempt",Optional.empty(),Optional.empty());
        when(store.load(identity.runRef())).thenReturn(Optional.of(run(true)));when(store.action(action)).thenReturn(Optional.of(original));
        when(store.material(tenant,material.reference())).thenReturn(Optional.of(material));
        when(properties.getWorkerId()).thenReturn("worker");
        var authentication=mock(AuthenticationRef.class);var actor=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant,"worker"),owner,Set.of("sessions.write"),1,new ResultRef("current-cancel-actor"));
        when(identities.registerTrusted(any(),eq(owner),eq("chat"),eq("workflow-cancel"),eq(Duration.ofMinutes(5)))).thenReturn(authentication);
        when(identities.verify(any())).thenReturn(new IdentityPolicyPort.IdentityResult.Verified(actor));

        subject().changed(identity.runRef());

        verify(sdk).cancelCommittedParent(eq(actor),eq(execution),eq(new InputId("workflow-parent-cancel:parent:workflow-action")),argThat(control->control.deadlineAt().equals(now.plusSeconds(5))));
        verifyNoMoreInteractions(sdk);verify(store,never()).dispatch(any());verify(store,never()).admit(any());
    }

    @Test void boundedRecoveryOnlyRechecksAddressesAndRejectsUnboundedLimits() {
        when(mapper.cancelledParents("1",3)).thenReturn(List.of("parent"));when(store.load(identity.runRef())).thenReturn(Optional.empty());
        var subject=subject();subject.recover(tenant,3);
        verify(store).load(identity.runRef());verifyNoInteractions(sdk,identities);
        assertThrows(IllegalArgumentException.class,()->subject.recover(tenant,101));
    }

    private ExecutionStore.Run run(boolean cancelled) {
        return new ExecutionStore.Run(identity,new ResultRef("profile"),cancelled?RunStatus.CANCELLED:RunStatus.RUNNING,2,0,cancelled,
                cancelled?ExecutionStore.Phase.FINISHED:ExecutionStore.Phase.READY,new ExecutionStore.Progress(0,0,0,new ResultRef("context")),
                Optional.empty(),Optional.of(action),Optional.empty(),Optional.empty(),new ResultRef("actor"),Optional.empty(),now,now);
    }
    private AgentRuntimeWorkflowCancellations subject(){return new AgentRuntimeWorkflowCancellations(store,sdk,codec,identities,provisioner,properties,mapper,Clock.fixed(now,ZoneOffset.UTC));}
}
