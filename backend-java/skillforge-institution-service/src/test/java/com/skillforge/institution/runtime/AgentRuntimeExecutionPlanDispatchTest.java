package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 实际Sources派发消费者的事务接线；成员必须继续经过PlanPort，不能借PRIMARY编排身份或绕过拒绝。 */
class AgentRuntimeExecutionPlanDispatchTest {
    /** 两种冻结角色都核当前计划，只有PRIMARY建立TeamWorkService拥有的编排作用域。 */
    @Test void primaryAndDelegateBothRequirePlanGuardButOnlyPrimaryUsesTeamScope() {
        for (var role : AgentRunBindings.Role.values()) {
            var fixture = new Fixture(role);
            assertTrue(fixture.sources.planAllows(fixture.session,fixture.run,fixture.prepared,fixture.binding));
            verify(fixture.plans).requireDispatch(fixture.identity,fixture.prepared.action(),ExecutionStore.Kind.MODEL,true,false,false);
            if (role == AgentRunBindings.Role.PRIMARY)
                verify(fixture.teams).participatePlan(eq(fixture.identity),eq(fixture.binding),any());
            else verifyNoInteractions(fixture.teams);
            verify(fixture.store).participate(eq(fixture.session),eq(fixture.tenant),any());
            verify(fixture.plans).participate(eq(fixture.session),eq(fixture.identity),eq(fixture.binding),eq(0L),any());
        }
    }

    /** 成员当前计划核验拒绝时不改走主Agent作用域，也不将异常变成成功派发。 */
    @Test void delegatePlanRejectionRemainsRejectedWithoutPrimaryFallback() {
        var fixture = new Fixture(AgentRunBindings.Role.DELEGATE);
        when(fixture.prepared.kind()).thenReturn(ExecutionStore.Kind.KNOWLEDGE);
        var action = fixture.prepared.action();
        doThrow(new IllegalArgumentException("plan_member_step_not_active")).when(fixture.plans)
                .requireDispatch(fixture.identity,action,ExecutionStore.Kind.KNOWLEDGE,true,false,false);
        assertFalse(fixture.sources.planAllows(fixture.session,fixture.run,fixture.prepared,fixture.binding));
        verifyNoInteractions(fixture.teams);
    }

    /** 仅装配实际消费者所需端口；本用例不声明真实数据库或Provider已验收。 */
    private static final class Fixture {
        final TenantId tenant = new TenantId("1");
        final SqlSession session = mock(SqlSession.class);
        final JdbcExecutionStore store = mock(JdbcExecutionStore.class);
        final InstitutionPlanHost plans = mock(InstitutionPlanHost.class);
        final TeamWorkService teams = mock(TeamWorkService.class);
        final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class);
        final ExecutionStore.Run run = mock(ExecutionStore.Run.class);
        final ExecutionStore.Prepared prepared = mock(ExecutionStore.Prepared.class);
        final RunIdentity identity;
        final AgentRuntimeExecutionSources sources;

        /** 借用同一SqlSession运行每层生产回调，让PRIMARY-only误接线能由调用断言直接检出。 */
        Fixture(AgentRunBindings.Role role) {
            var agent = new AgentId(role == AgentRunBindings.Role.PRIMARY ? "7" : "8");
            var owner = SessionOwner.user(new SessionRef(tenant,role == AgentRunBindings.Role.PRIMARY ? "parent-session" : "delegation:exact-child"),agent,new EndUserId(9));
            var input = new InputId("input");
            identity = new RunIdentity(owner, agent, input, new RunRef(tenant,"run-" + role.name()), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            when(binding.role()).thenReturn(role); when(run.identity()).thenReturn(identity);
            when(prepared.kind()).thenReturn(ExecutionStore.Kind.MODEL); when(prepared.action()).thenReturn(new ActionRef(tenant,"action"));
            when(store.participate(eq(session),eq(tenant),any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
            when(plans.participate(eq(session),eq(identity),eq(binding),eq(0L),any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
            when(teams.participatePlan(eq(identity),eq(binding),any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
            sources = new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class),mock(JdbcModelBindings.class),mock(JdbcMcpBindings.class),
                    mock(JdbcKnowledgeBindings.class),mock(JdbcWorkflowBindings.class),mock(MediaPort.class),mock(AgentRuntimePublishedCapabilities.class),
                    mock(AgentRuntimeExecutionCatalog.class),mock(JdbcIdentityPolicyPort.class),mock(SessionTokenCounter.class),mock(JdbcExecutionBudgets.class),
                    store,mock(AgentRunBindings.class),new AgentRuntimeProperties(),provider(mock(AgentDelegationHost.class)),provider(plans),provider(teams),
                    provider(mock(InstitutionOutputContract.class)),Clock.systemUTC(),mock(GroupMemoryRecall.class), com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        }
    }

    /** 只提供当前用例拥有的正式注入依赖，不增加生产测试入口。 */
    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") var result = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(result.getObject()).thenReturn(value); return result;
    }
}
