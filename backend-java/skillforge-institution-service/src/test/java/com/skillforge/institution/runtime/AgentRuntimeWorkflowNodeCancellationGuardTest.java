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
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

class AgentRuntimeWorkflowNodeCancellationGuardTest {
    @Test void originalTransactionLocksRealCancelledParentAndRejectsNodeBeforeProviderAuthorization() {
        var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(1));
        var input=new InputId("input");var parentIdentity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("cause"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var key=new WorkflowNodeCallIdentity(parentIdentity,new ExecutionRef(tenant,"execution"),"script",1,1);var identity=WorkflowNodeSource.runIdentity(key);
        var actor=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant,"worker"),owner,Set.of("sessions.write"),1,new ResultRef("original-actor"));
        var request=new ExecutionStore.Material(new ResultRef("original-script-reference"),new TypedPayload(WorkflowScriptNodeCodec.REQUEST_SCHEMA,"{}"));
        var frozen=new WorkflowNodeRequests.Frozen(key,new ResultRef("parent-profile"),ExecutionStore.Kind.TOOL,"workflow-script:task",new ResultRef("capability"),request,
                ExecutionBudgetPort.Category.TOOL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0),Optional.empty());
        var store=mock(JdbcExecutionStore.class);var identities=mock(JdbcIdentityPolicyPort.class);var requests=mock(WorkflowNodeRequests.class);
        var run=mock(ExecutionStore.Run.class);when(run.identity()).thenReturn(identity);when(run.configuration()).thenReturn(WorkflowNodeRequests.reference(identity.runRef()));when(run.actorVerificationRef()).thenReturn(actor.verificationRef());
        var configuration=new ExecutionStore.Material(run.configuration(),new TypedPayload(WorkflowNodeRequests.SCHEMA,"{}"));
        when(store.material(tenant,run.configuration())).thenReturn(Optional.of(configuration));when(requests.read(configuration)).thenReturn(frozen);
        when(identities.originalVerification(owner,actor.verificationRef())).thenReturn(Optional.of(actor));
        var session=mock(SqlSession.class);var parent=mock(ExecutionStore.Run.class);when(parent.identity()).thenReturn(parentIdentity);when(parent.cancelled()).thenReturn(true);
        when(store.lockBorrowed(session,parentIdentity.runRef())).thenReturn(parent);
        var subject=new AgentRuntimeWorkflowNodeExecutions(store,mock(ExecutionBudgetPort.class),identities,mock(JdbcModelBindings.class),mock(JdbcMcpBindings.class),
                mock(JdbcKnowledgeBindings.class),requests,mock(WorkflowParser.class),mock(RegisteredExecutionProviderCalls.class),mock(AgentExecutionCodec.class),mock(RegisteredWorkflowScriptCalls.class));
        subject.begin(identity.runRef());subject.configuration(run);
        var prepared=new ExecutionStore.Prepared(new ActionRef(tenant,"action"),1,ExecutionStore.Kind.TOOL,frozen.target(),frozen.capability(),request,"a".repeat(64));

        assertFalse(subject.allowed(session,run,actor,prepared));

        verify(store).lockBorrowed(session,parentIdentity.runRef());verifyNoInteractions(session);verify(identities,never()).authorize(any(SqlSession.class),any());subject.end(identity.runRef());
    }
}
