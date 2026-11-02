package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 产品领域入口仅委托唯一typed执行链，领域拒绝不能启动另一条Runtime路径。 */
class AgentRuntimeInboundGatewayTest {
    @Test void submittedMessageAndCancellationUseOnlyTypedGateway() {
        var fixture=new Fixture();var request=new ChatRequest("核查订单","session","7",null,null,null,null,null,"input",null,java.util.Map.of());
        var submitted=new ChatRuntimeGateway.Submission(request,fixture.session,"7",9L,null);
        var accepted=new ChatResult("queued","session","run:input",null,null,null,"input",false);
        when(fixture.executions.submit(submitted)).thenReturn(accepted);
        assertSame(accepted,fixture.gateway.submit(submitted));
        verifyNoInteractions(fixture.goals);
        when(fixture.executions.cancelChat(fixture.session,"run:input")).thenReturn(accepted);
        assertSame(accepted,fixture.gateway.cancelChat(fixture.session,"run:input"));
        verifyNoInteractions(fixture.store,fixture.log);
    }

    @Test void deniedPlanSelectionCannotAppendDomainFact() {
        var fixture=new Fixture();
        when(fixture.executions.authorizeSession(eq(fixture.session),any(),eq("runtime.plan.select"))).thenThrow(new SecurityException("revoked"));
        assertThrows(SecurityException.class,()->fixture.gateway.selectPlan(fixture.session,"select",true));
        verifyNoInteractions(fixture.log,fixture.store);
    }

    @Test void workflowAssociationQueriesExactCurrentOwnerAndExecution() {
        var fixture=new Fixture();var owner=TeamWorkService.owner(fixture.session);
        var actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(new TenantId("1"),owner.requireUser()),owner,java.util.Set.of("workflow.query"),1,new ResultRef("verified"));
        when(fixture.executions.authorizeSession(eq(fixture.session),any(),eq("runtime.session.query"))).thenReturn(actor);
        when(fixture.workflows.query(any())).thenReturn(Optional.empty());
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->fixture.gateway.requireWorkflowExecution(fixture.session,"sdk-execution"));
        var query=org.mockito.ArgumentCaptor.forClass(WorkflowPortal.WorkflowQuery.class);verify(fixture.workflows).query(query.capture());
        assertEquals(actor,query.getValue().identity());assertEquals(new ExecutionRef(new TenantId("1"),"sdk-execution"),query.getValue().executionRef());
        verifyNoInteractions(fixture.store,fixture.log);
    }

    @Test void scheduledGoalCallbacksBindOwnerTenantAndRestoreItOnSuccessAndFailure() {
        var fixture=new Fixture();var owner=TeamWorkService.owner(fixture.session);
        var actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(new TenantId("1"),owner.requireUser()),owner,
                java.util.Set.of("sessions.read"),1,new ResultRef("verified"));
        when(fixture.executions.authorizeSession(eq(fixture.session),any(),eq("runtime.session.query"))).thenReturn(actor);
        when(fixture.bindings.currentPrimaryAgent(owner)).thenReturn(new AgentId("7"));
        when(fixture.store.load(any())).thenReturn(Optional.empty());
        when(fixture.goals.read(owner)).thenReturn(Optional.empty());
        var captured=org.mockito.ArgumentCaptor.forClass(AgentRuntimeGoalDriver.Callback.class);
        var admissionFailure=new IllegalStateException("capture-before-admission");
        when(fixture.goals.createFromAdmission(eq(owner),eq(new AgentId("7")),eq("create"),eq("汇总"),eq(3),captured.capture(),any()))
                .thenThrow(admissionFailure);
        try {
            com.skillforge.common.context.TenantContext.setTenantId("1");
            assertSame(admissionFailure,assertThrows(IllegalStateException.class,()->fixture.gateway.createGoal(fixture.session,"create","汇总",3)));
            var callback=captured.getValue();var run=new RunRef(new TenantId("1"),"run:goal-start:create");
            com.skillforge.common.context.TenantContext.clear();
            when(fixture.executions.authorizeSession(eq(fixture.session),any(),eq("runtime.session.query"))).thenAnswer(invocation->{
                assertEquals("1",com.skillforge.common.context.TenantContext.getTenantId());return actor;
            });
            when(fixture.store.events(eq(owner.sessionRef()),eq(0L),eq(100))).thenAnswer(invocation->{
                assertEquals("1",com.skillforge.common.context.TenantContext.getTenantId());return java.util.List.of();
            });
            assertTrue(callback.latestRun(run).isEmpty());
            assertNull(com.skillforge.common.context.TenantContext.getTenantId());
            assertTrue(callback.idle());
            assertNull(com.skillforge.common.context.TenantContext.getTenantId());
            doAnswer(invocation->{assertEquals("1",com.skillforge.common.context.TenantContext.getTenantId());return null;})
                    .when(fixture.executions).wakeGoalRound(run);
            callback.wake(run);
            assertNull(com.skillforge.common.context.TenantContext.getTenantId());
            var revoked=new SecurityException("revoked");
            when(fixture.executions.authorizeSession(eq(fixture.session),any(),eq("runtime.session.query"))).thenThrow(revoked);
            assertSame(revoked,assertThrows(SecurityException.class,()->callback.latestRun(run)));
            assertNull(com.skillforge.common.context.TenantContext.getTenantId());
            com.skillforge.common.context.TenantContext.setTenantId("2");
            clearInvocations(fixture.executions,fixture.store);
            assertThrows(SecurityException.class,()->callback.latestRun(run));
            assertEquals("2",com.skillforge.common.context.TenantContext.getTenantId());
            verifyNoInteractions(fixture.executions,fixture.store);
        } finally {com.skillforge.common.context.TenantContext.clear();}
    }

    private static final class Fixture {
        final AgentRuntimeExecutionGateway executions=mock(AgentRuntimeExecutionGateway.class);
        final ExecutionStore store=mock(ExecutionStore.class);
        final SessionLogPort log=mock(SessionLogPort.class);
        final AgentRunBindings bindings=mock(AgentRunBindings.class);
        final AgentRuntimeGoalDriver goals=mock(AgentRuntimeGoalDriver.class);
        final WorkflowPortal workflows=mock(WorkflowPortal.class);
        final SessionView session=SessionView.builder().tenantId(1L).sessionId("session").agentId(7L).endUserId(9L).build();
        final AgentRuntimeInboundGateway gateway=new AgentRuntimeInboundGateway(executions,store,bindings,log,
                Clock.systemUTC(),goals,mock(DeliverableService.class),workflows,mock(AgentRuntimeExecutionProjection.class));
    }
}
