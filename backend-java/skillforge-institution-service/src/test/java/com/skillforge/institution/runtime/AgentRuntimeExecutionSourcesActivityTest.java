package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationResult;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** 活动frame跨控制线程的可见性与生命周期；预算写入仍只委托调用方SqlSession。 */
class AgentRuntimeExecutionSourcesActivityTest {
    @Test void currentReadDelegatesFrozenEntryAndArgumentsToTrustedPolicy() {
        var capabilities = mock(AgentRuntimePublishedCapabilities.class);
        var sources = new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class), mock(JdbcModelBindings.class),
                mock(JdbcMcpBindings.class), mock(JdbcKnowledgeBindings.class), mock(JdbcWorkflowBindings.class), mock(MediaPort.class), capabilities,
                mock(AgentRuntimeExecutionCatalog.class), mock(JdbcIdentityPolicyPort.class), mock(SessionTokenCounter.class), mock(JdbcExecutionBudgets.class),
                mock(ExecutionStore.class), mock(AgentRunBindings.class), new AgentRuntimeProperties(), mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), java.time.Clock.systemUTC(), mock(GroupMemoryRecall.class),
                com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        var frozen = mock(com.skillforge.runtime.session.spi.SessionToolDirectory.Entry.class);
        var arguments = new com.skillforge.runtime.api.TypedPayload("application/json", "{}");
        when(capabilities.currentRead(frozen, arguments)).thenReturn(true);
        assertThat(sources.currentRead(frozen, arguments)).isTrue();
        verify(capabilities).currentRead(frozen, arguments);
    }
    @Test void outputPreparationReadsCurrentPhaseWithoutTakingFullBudgetSnapshot() {
        var budgets=mock(JdbcExecutionBudgets.class);
        var output=mock(InstitutionOutputContract.class);
        @SuppressWarnings("unchecked") ObjectProvider<InstitutionOutputContract> outputProvider=mock(ObjectProvider.class);
        when(outputProvider.getObject()).thenReturn(output);
        var sources=new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class),mock(JdbcModelBindings.class),
                mock(JdbcMcpBindings.class),mock(JdbcKnowledgeBindings.class),mock(JdbcWorkflowBindings.class),mock(MediaPort.class),
                mock(AgentRuntimePublishedCapabilities.class),mock(AgentRuntimeExecutionCatalog.class),mock(JdbcIdentityPolicyPort.class),
                mock(SessionTokenCounter.class),budgets,mock(ExecutionStore.class),mock(AgentRunBindings.class),new AgentRuntimeProperties(),
                mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class),outputProvider,java.time.Clock.systemUTC(),mock(GroupMemoryRecall.class), com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        var reference=new RunRef(new TenantId("phase-tenant"),"phase-run");
        var run=mock(ExecutionStore.Run.class);var identity=mock(RunIdentity.class);
        when(run.identity()).thenReturn(identity);when(identity.runRef()).thenReturn(reference);
        var configuration=mock(com.skillforge.runtime.session.AgentExecutionProgram.Configuration.class);
        when(configuration.tools()).thenReturn(java.util.List.of());
        var policy=new com.skillforge.runtime.session.AgentExecutionOutputs.Policy(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                com.skillforge.runtime.api.port.ExecutionBudgetPort.ClosingSpec.TEXT,false);
        when(output.prepareOutput(run,null,java.util.List.of())).thenReturn(policy);
        when(budgets.phase(reference)).thenReturn(com.skillforge.runtime.api.port.ExecutionBudgetPort.Phase.BUSINESS,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Phase.CLOSING);
        sources.begin(reference);
        try {
            var active=(java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(sources,"active");
            org.springframework.test.util.ReflectionTestUtils.setField(active.get(reference),"configuration",configuration);
            assertThat(sources.prepareOutput(run,configuration).orElseThrow().closing()).isFalse();
            assertThat(sources.prepareOutput(run,configuration).orElseThrow().closing()).isTrue();
            verify(budgets,times(2)).phase(reference);verifyNoMoreInteractions(budgets);
        } finally {sources.end(reference);}
    }

    @Test void timeSampleReadsTrustedHostClockOnceWithoutLoadingRuntimeFacts() {
        var clock=mock(java.time.Clock.class);var instant=Instant.parse("2026-10-22T04:05:06Z");when(clock.instant()).thenReturn(instant);
        var store=mock(ExecutionStore.class);var sources=new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class),mock(JdbcModelBindings.class),
                mock(JdbcMcpBindings.class),mock(JdbcKnowledgeBindings.class),mock(JdbcWorkflowBindings.class),mock(MediaPort.class),mock(AgentRuntimePublishedCapabilities.class),
                mock(AgentRuntimeExecutionCatalog.class),mock(JdbcIdentityPolicyPort.class),mock(SessionTokenCounter.class),mock(JdbcExecutionBudgets.class),store,mock(AgentRunBindings.class),
                new AgentRuntimeProperties(),mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class),clock,mock(GroupMemoryRecall.class), com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        assertThat(sources.sampleModelTime(mock(ExecutionStore.Run.class)).sampledAt()).isEqualTo(instant);verify(clock).instant();verifyNoMoreInteractions(clock);verifyNoInteractions(store);
    }
    /** Steering使用执行权限而取消使用取消权限，跨owner请求不能进入策略评估。 */
    @Test
    void steeringAndResumeRequireExecuteWhileCancellationRequiresCancelForExactOwner() {
        var identities = mock(JdbcIdentityPolicyPort.class); var session = mock(SqlSession.class);
        var sources = new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class), mock(JdbcModelBindings.class),
                mock(JdbcMcpBindings.class), mock(JdbcKnowledgeBindings.class), mock(JdbcWorkflowBindings.class), mock(MediaPort.class), mock(AgentRuntimePublishedCapabilities.class),
                mock(AgentRuntimeExecutionCatalog.class), identities, mock(SessionTokenCounter.class), mock(JdbcExecutionBudgets.class),
                mock(ExecutionStore.class), mock(AgentRunBindings.class), new AgentRuntimeProperties(), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), java.time.Clock.systemUTC(),mock(GroupMemoryRecall.class), com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        var tenant = new TenantId("1"); var owner = SessionOwner.user(new SessionRef(tenant, "controls"), new AgentId("2"), new EndUserId(7));
        var input = new InputId("controls-input");
        var identity = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "controls-run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("controls-verification"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var run = mock(ExecutionStore.Run.class); when(run.identity()).thenReturn(identity);
        var actor = mock(VerifiedIdentity.class); when(actor.owner()).thenReturn(owner);
        when(identities.authorize(eq(session), any())).thenReturn(new AuthorizationResult.Allowed(1, new ResultRef("audit")));
        assertThat(sources.allowedControl(session, run, actor, JdbcExecutionStore.Control.RESUME, input)).isTrue();
        assertThat(sources.allowedControl(session, run, actor, JdbcExecutionStore.Control.STEER, input)).isTrue();
        assertThat(sources.allowedControl(session, run, actor, JdbcExecutionStore.Control.CANCEL, input)).isTrue();
        var requests = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(identities, times(3)).authorize(eq(session), requests.capture());
        assertThat(requests.getAllValues()).extracting(AuthorizationRequest::action).containsExactly("session.execute", "session.execute", "session.cancel");
        clearInvocations(identities);
        when(identities.authorizeResponseReplay(eq(session),any(),eq(input))).thenReturn(new AuthorizationResult.Allowed(1,new ResultRef("replay-audit")));
        assertThat(sources.allowedControl(session,run,actor,JdbcExecutionStore.Control.RESUME_REPLAY,input)).isTrue();
        verify(identities).authorizeResponseReplay(eq(session),any(),eq(input));
        verify(identities,never()).authorize(eq(session),any());
        clearInvocations(identities);
        when(actor.owner()).thenReturn(SessionOwner.user(new SessionRef(tenant, "other-session"), new AgentId("2"), new EndUserId(7)));
        assertThat(sources.allowedControl(session, run, actor, JdbcExecutionStore.Control.STEER, input)).isFalse();
        verifyNoInteractions(identities);
    }

    /** drive和timer确认同一真实区间，不能因timer没有ThreadLocal而跳过预算活动。 */
    @Test
    void timerThreadConfirmsSameActiveIntervalInCallerTransaction() throws Exception {
        var budgets = mock(JdbcExecutionBudgets.class); var session = mock(SqlSession.class);
        var store = mock(ExecutionStore.class); var identities = mock(JdbcIdentityPolicyPort.class);
        var models = mock(JdbcModelBindings.class); var mcp = mock(JdbcMcpBindings.class);
        var sources = new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class), models, mcp, mock(JdbcKnowledgeBindings.class), mock(JdbcWorkflowBindings.class), mock(MediaPort.class),
                mock(AgentRuntimePublishedCapabilities.class), mock(AgentRuntimeExecutionCatalog.class), identities, mock(SessionTokenCounter.class), budgets,
                store, mock(AgentRunBindings.class), new AgentRuntimeProperties(), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), java.time.Clock.systemUTC(),mock(GroupMemoryRecall.class), com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        var run = new RunRef(new TenantId("1"), "cross-thread-activity");
        var timer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("activity-frame-test-", 0).factory());
        sources.begin(run);
        try {
            sources.confirmActivity(session, run);
            timer.submit(() -> sources.confirmActivity(session, run)).get(10, TimeUnit.SECONDS);
            var interval = ArgumentCaptor.forClass(String.class); var started = ArgumentCaptor.forClass(Instant.class);
            var through = ArgumentCaptor.forClass(Instant.class);
            verify(budgets, times(2)).recordActivity(eq(session), eq(run), interval.capture(), started.capture(), through.capture());
            assertThat(interval.getAllValues()).containsOnly(interval.getValue());
            assertThat(started.getAllValues()).containsOnly(started.getValue());
            assertThat(through.getAllValues().get(1)).isAfterOrEqualTo(through.getAllValues().getFirst());
            assertThat(through.getAllValues().getFirst()).isAfterOrEqualTo(started.getValue());
            verifyNoMoreInteractions(budgets); verifyNoInteractions(session, store, identities, models, mcp);
        } finally {
            timer.shutdownNow(); assertThat(timer.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); sources.end(run);
        }
        // 已释放drive之外的准确迟到事实不虚构新的活动区间。
        sources.confirmActivity(session, run);
        verify(budgets, times(2)).recordActivity(eq(session), eq(run), any(), any(), any());
    }
}
