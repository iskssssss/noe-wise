package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Workflow工具身份必须由原已加载节点和当前Store发送权共同证明。 */
class AgentRuntimeWorkflowToolSourceTest {
    @Test void unpreparedNodeCannotMarkAnyToolAsWorkflowOrigin() {
        var fixture=new Fixture(false);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.subject.requireCall(fixture.registered,fixture.prepared));
        verify(fixture.store,never()).authorizeSend(any(),any());
    }

    @Test void originalFrozenNodeStillRequiresCurrentStoreAuthorization() {
        var fixture=new Fixture(true);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.subject.requireCall(fixture.registered,fixture.prepared));
        verify(fixture.store).authorizeSend(fixture.registered,fixture.prepared);
        when(fixture.store.authorizeSend(fixture.registered,fixture.prepared)).thenReturn(true);
        assertDoesNotThrow(()->fixture.subject.requireCall(fixture.registered,fixture.prepared));
    }

    @Test void actorOrRequestDriftCannotBorrowThePreparedNodeSource() {
        var actorDrift=new Fixture(true);
        when(actorDrift.registered.actor()).thenReturn(mock(VerifiedIdentity.class));
        assertThrows(ExecutionStore.Rejection.class,()->actorDrift.subject.requireCall(actorDrift.registered,actorDrift.prepared));
        verify(actorDrift.store,never()).authorizeSend(any(),any());
        var requestDrift=new Fixture(true);
        var changed=new ExecutionStore.Material(new ResultRef("changed-request"),new TypedPayload("application/json","{\"changed\":true}"));
        when(requestDrift.prepared.request()).thenReturn(changed);
        when(requestDrift.codec.decodeTool(changed.content())).thenReturn(requestDrift.tool);
        assertThrows(ExecutionStore.Rejection.class,()->requestDrift.subject.requireCall(requestDrift.registered,requestDrift.prepared));
        verify(requestDrift.store,never()).authorizeSend(any(),any());
    }

    @Test void usageOriginUsesOriginalParentWhileOrdinaryRunsNeedNoExtraRead() {
        var f=new Fixture(false);
        assertEquals(f.parent,f.subject.resourceOrigin(f.registered.run()));
        clearInvocations(f.store);
        assertEquals(f.parent,f.subject.resourceOrigin(f.parentRun));
        verifyNoInteractions(f.store);
    }

    @Test void usageOriginRejectsForgedIdentityParentConfigurationOrInputSource() {
        var f=new Fixture(false);
        when(f.parentRun.configuration()).thenReturn(new ResultRef("another-parent-profile"));
        assertThrows(ExecutionStore.Rejection.class,()->f.subject.resourceOrigin(f.registered.run()));
        when(f.parentRun.configuration()).thenReturn(new ResultRef("parent-profile"));
        when(f.store.originalInput(f.identity.runRef())).thenReturn(Optional.empty());
        assertThrows(ExecutionStore.Rejection.class,()->f.subject.resourceOrigin(f.registered.run()));
        when(f.store.load(f.identity.runRef())).thenReturn(Optional.of(f.parentRun));
        assertThrows(ExecutionStore.Rejection.class,()->f.subject.resourceOrigin(f.identity));
    }

    private static final class Fixture {
        final JdbcExecutionStore store=mock(JdbcExecutionStore.class);
        final AgentExecutionCodec codec=mock(AgentExecutionCodec.class);
        final ExecutionStore.DispatchResult.Registered registered=mock(ExecutionStore.DispatchResult.Registered.class);
        final ExecutionStore.Prepared prepared=mock(ExecutionStore.Prepared.class);
        final AgentExecutionCodec.ToolRequest tool;
        final AgentRuntimeWorkflowNodeExecutions subject;
        final RunIdentity parent,identity;
        final ExecutionStore.Run parentRun=mock(ExecutionStore.Run.class);
        Fixture(boolean load) {
            var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(1));
            var input=new InputId("parent-input");var verification=new ResultRef("parent-verification");
            parent=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent"), new TraceEnvelope("trace","span",input,Optional.empty()), verification, new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            var node=new WorkflowNodeCallIdentity(parent,new ExecutionRef(tenant,"execution"),"tool",1,1);
            identity=WorkflowNodeSource.runIdentity(node);var configuration=WorkflowNodeRequests.reference(identity.runRef());
            var actor=new VerifiedIdentity(new PrincipalRef.ServicePrincipal(tenant,"principal","chat",1),owner,Set.of("tool.invoke"),1,verification);
            var run=mock(ExecutionStore.Run.class);when(run.identity()).thenReturn(identity);when(run.configuration()).thenReturn(configuration);when(run.actorVerificationRef()).thenReturn(verification);
            var request=new ExecutionStore.Material(new ResultRef("original-tool-request"),new TypedPayload("application/json","{}"));
            var material=new ExecutionStore.Material(configuration,new TypedPayload(WorkflowNodeRequests.SCHEMA,"{}"));
            var capability=new ResultRef("original-capability");var binding=mock(ToolBinding.class);when(binding.providerType()).thenReturn(ToolBinding.ProviderType.PLATFORM);
            tool=new AgentExecutionCodec.ToolRequest(mock(FrozenToolTarget.class),binding,new TypedPayload("application/json","{}"),"call","tool");
            var requests=mock(WorkflowNodeRequests.class);var identities=mock(JdbcIdentityPolicyPort.class);
            var frozen=new WorkflowNodeRequests.Frozen(node,new ResultRef("parent-profile"),ExecutionStore.Kind.TOOL,"tool-target",capability,request,
                    ExecutionBudgetPort.Category.TOOL,ExecutionBudgetPort.Resources.ZERO,Optional.empty());
            when(parentRun.identity()).thenReturn(parent);when(parentRun.configuration()).thenReturn(frozen.profile());
            when(store.load(parent.runRef())).thenReturn(Optional.of(parentRun));when(store.load(identity.runRef())).thenReturn(Optional.of(run));
            when(store.originalInput(identity.runRef())).thenReturn(Optional.of(request));
            when(store.material(tenant,configuration)).thenReturn(Optional.of(material));when(requests.read(material)).thenReturn(frozen);
            when(identities.originalVerification(owner,verification)).thenReturn(Optional.of(actor));when(codec.decodeTool(request.content())).thenReturn(tool);
            when(registered.run()).thenReturn(run);when(registered.actor()).thenReturn(actor);
            var attempt=mock(ExecutionStore.Attempt.class);when(attempt.continuation()).thenReturn(Optional.empty());when(registered.attempt()).thenReturn(attempt);
            when(prepared.kind()).thenReturn(ExecutionStore.Kind.TOOL);when(prepared.target()).thenReturn("tool-target");when(prepared.capability()).thenReturn(capability);when(prepared.request()).thenReturn(request);
            subject=new AgentRuntimeWorkflowNodeExecutions(store,mock(ExecutionBudgetPort.class),identities,mock(JdbcModelBindings.class),mock(JdbcMcpBindings.class),mock(JdbcKnowledgeBindings.class),
                    requests,mock(WorkflowParser.class),mock(RegisteredExecutionProviderCalls.class),codec,mock(RegisteredWorkflowScriptCalls.class));
            subject.begin(identity.runRef());if(load)subject.configuration(run);
        }
    }
}
