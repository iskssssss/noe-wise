package com.skillforge.institution.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.session.AgentExecutionContextViews;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionContextSummary;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/** 真实摘要入口保留父预算与取消，冷却恢复只采用同owner和配置的提交事件。 */
class AgentRuntimeExecutionSourcesSummaryTest {
    @Test void summaryUsesFrozenActorRemainingActiveTimeAndParentBudgetScope() {
        try (var f = new Fixture()) {
            f.activeTime(Duration.ofSeconds(240));
            var history = List.of(new ModelMessage("old", ModelRole.USER, "旧事实"));
            var current = List.of(new ModelMessage("current", ModelRole.USER, "当前目标"));
            var expected = SessionContextSummary.Outcome.unavailable("SUMMARY_MODEL_UNAVAILABLE");
            when(f.summaries.summarize(any())).thenAnswer(call -> {
                SessionContextSummary.Request request = call.getArgument(0);
                assertThat(request.key()).isEqualTo(new SessionPreparations.Key(f.identity, "agent-execution", 7));
                assertThat(request.actor()).isSameAs(f.actor);
                assertThat(request.history()).isEqualTo(history); assertThat(request.currentContext()).isEqualTo(current);
                assertThat(request.sourceDigest()).isEqualTo("source-digest"); assertThat(request.maxOutputTokens()).isEqualTo(800);
                assertThat(request.remaining()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60));
                assertThat(ExecutionBudgetScope.current().orElseThrow().run()).isEqualTo(f.reference);
                assertThat(ExecutionBudgetScope.current().orElseThrow().port()).isSameAs(f.budgets);
                assertThat(request.cancelled().getAsBoolean()).isFalse();
                when(f.control.ownershipLost()).thenReturn(true);
                assertThat(request.cancelled().getAsBoolean()).isTrue();
                return expected;
            });
            assertThat(f.sources.summarizeContext(f.run, f.configuration, history, current, "source-digest", 800, 7)).isSameAs(expected);
            verifyNoInteractions(f.store, f.budgets);
        }
    }

    @Test void exhaustedActiveBudgetDoesNotAcceptAnotherSummary() {
        try (var f = new Fixture()) {
            f.activeTime(Duration.ofSeconds(300));
            assertThat(f.sources.summarizeContext(f.run, f.configuration,
                    List.of(new ModelMessage("old", ModelRole.USER, "事实")), List.of(), "digest", 800, 1).reason())
                    .isEqualTo("SUMMARY_TIME_EXHAUSTED");
            verifyNoInteractions(f.summaries);
        }
    }

    @Test void cooldownUsesLatestSequenceAndIgnoresOtherConfigurationsWithoutLoadingRunsOrUnrelatedEvents() {
        try (var f = new Fixture()) {
            var until = Instant.parse("2026-10-27T01:05:00Z");
            String scope=f.scope();
            var first = IntStream.rangeClosed(1,100).mapToObj(index -> f.event(index,scope,"1","2026-10-27T01:00:30Z")).toList();
            var second=List.of(f.event(101,scope,"2",until.toString()),f.event(102,"other-configuration","ignored","ignored"));
            when(f.compactions.forOwner(f.owner,"agent.context-compaction-attempt",0,100)).thenReturn(first);
            when(f.compactions.forOwner(f.owner,"agent.context-compaction-attempt",100,100)).thenReturn(second);
            assertThat(f.sources.contextCompactionCooldown(f.run,f.configuration)).contains(new AgentExecutionContextViews.Cooldown(2,until));
            verify(f.compactions).forOwner(f.owner,"agent.context-compaction-attempt",0,100);
            verify(f.compactions).forOwner(f.owner,"agent.context-compaction-attempt",100,100);
            verifyNoInteractions(f.store); verifyNoMoreInteractions(f.compactions);
        }
    }

    @Test void malformedMatchingCooldownIsRejectedInsteadOfResettingFailuresOrSkippingOldFacts() {
        try (var f = new Fixture()) {
            String scope=f.scope();
            var newer=f.event(2,scope,"0","2026-10-27T01:05:00Z");
            var invalidFacts=List.of(
                    f.event(1,scope,"01","2026-10-27T01:00:30Z"),
                    f.event(1,scope,"1","invalid-time"),
                    new AgentRuntimeCompactionMapper.Fact(1,"unexpected.v1","{}"),
                    new AgentRuntimeCompactionMapper.Fact(1,"skillforge.runtime.execution.context-compaction-attempt.v1","{}"));
            for(var invalid:invalidFacts) {
                var page=List.of(invalid,newer);
                when(f.compactions.forOwner(f.owner,"agent.context-compaction-attempt",0,100)).thenReturn(page);
                assertThatThrownBy(() -> f.sources.contextCompactionCooldown(f.run,f.configuration))
                        .isInstanceOf(IllegalStateException.class).hasMessage("execution_context_compaction_event_invalid");
            }
            verifyNoInteractions(f.store);
        }
    }

    @Test void nonAdvancingCooldownSequenceIsRejected() {
        try (var f=new Fixture()) {
            String scope=f.scope();
            var page=List.of(f.event(0,scope,"1","2026-10-27T01:00:30Z"));
            when(f.compactions.forOwner(f.owner,"agent.context-compaction-attempt",0,100)).thenReturn(page);
            assertThatThrownBy(()->f.sources.contextCompactionCooldown(f.run,f.configuration))
                    .isInstanceOf(IllegalStateException.class).hasMessage("execution_context_compaction_event_invalid");
            verifyNoInteractions(f.store);
        }
    }

    static final class Fixture implements AutoCloseable {
        private final TenantId tenant;
        final RunRef reference;
        final SessionOwner owner;
        private final RunIdentity identity = mock(RunIdentity.class);
        private final VerifiedIdentity actor = mock(VerifiedIdentity.class);
        final ExecutionStore.Run run = mock(ExecutionStore.Run.class);
        final ExecutionStore store = mock(ExecutionStore.class);
        private final AgentRuntimeCompactionMapper compactions;
        private final JdbcExecutionBudgets budgets = mock(JdbcExecutionBudgets.class);
        private final SessionContextSummary summaries = mock(SessionContextSummary.class);
        private final ExecutionCoordinator.Control control = mock(ExecutionCoordinator.Control.class);
        final AgentExecutionProgram.Configuration configuration = mock(AgentExecutionProgram.Configuration.class);
        final AgentRuntimeExecutionSources sources;
        private final Object frame;

        private Fixture() {
            this(mock(AgentRuntimeCompactionMapper.class),SessionOwner.user(new SessionRef(new TenantId("summary-tenant"),"summary-session"),new AgentId("7"),new EndUserId(9)));
        }
        Fixture(AgentRuntimeCompactionMapper compactions,SessionOwner owner) {
            this.compactions=compactions; this.owner=owner; this.tenant=owner.sessionRef().tenantId(); this.reference=new RunRef(tenant,"current");
            when(identity.runRef()).thenReturn(reference); when(identity.owner()).thenReturn(owner); when(run.identity()).thenReturn(identity);
            when(actor.owner()).thenReturn(owner); when(configuration.actor()).thenReturn(actor);
            when(configuration.budget()).thenReturn(new LoopBudget.Enforced(10, 10, 10, 10_000, 10_000, 20_000, Duration.ofSeconds(300)));
            var profile = mock(SessionPreparations.Profile.class); when(profile.reference()).thenReturn(new ResultRef("frozen-profile"));
            when(profile.maxTokens()).thenReturn(100_000L); when(configuration.profile()).thenReturn(profile);
            var target = mock(FrozenModelTarget.class);
            when(target.binding()).thenReturn(new com.skillforge.runtime.api.port.ModelPort.ModelBindingRef(tenant, "summary-binding", 1));
            when(target.effectiveModelId()).thenReturn("summary-model");
            when(configuration.target()).thenReturn(target); when(configuration.modelTools()).thenReturn(List.of());
            sources = new AgentRuntimeExecutionSources(mock(AgentRuntimeModelConfiguration.class), mock(JdbcModelBindings.class), mock(JdbcMcpBindings.class),
                    mock(JdbcKnowledgeBindings.class), mock(JdbcWorkflowBindings.class), mock(MediaPort.class), mock(AgentRuntimePublishedCapabilities.class),
                    mock(AgentRuntimeExecutionCatalog.class), mock(JdbcIdentityPolicyPort.class), mock(SessionTokenCounter.class), budgets, store,
                    mock(AgentRunBindings.class), new AgentRuntimeProperties(), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                    mock(ObjectProvider.class), Clock.systemUTC(), mock(GroupMemoryRecall.class), summaries, new ObjectMapper(), compactions);
            sources.begin(reference);
            var active = (java.util.Map<?, ?>) ReflectionTestUtils.getField(sources, "active"); frame = active.get(reference);
            ReflectionTestUtils.setField(frame, "configuration", configuration); ReflectionTestUtils.setField(frame, "control", control);
        }

        private void activeTime(Duration elapsed) { ReflectionTestUtils.setField(frame, "confirmedActive", elapsed); }
        String scope() { return AgentExecutionContextViews.scope(configuration, configuration.modelTools()); }
        private AgentRuntimeCompactionMapper.Fact event(long sequence, String scope, String failures, String until) {
            return new AgentRuntimeCompactionMapper.Fact(sequence,"skillforge.runtime.execution.context-compaction-attempt.v1",
                    "{\"scope\":\""+scope+"\",\"failures\":\""+failures+"\",\"until\":\""+until+"\"}");
        }
        @Override public void close() { sources.end(reference); }
    }
}
