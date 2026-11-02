package com.skillforge.core.agent.systemagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.*;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.invocation.ExternalInvocationDO;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 恢复查询不得把暂时不可用或尚未结束的 Runtime 误写成失败。 */
class SystemAgentExecutionTest {
    final SystemAgentService agents=mock(SystemAgentService.class);
    final SystemAgentRunner runner=mock(SystemAgentRunner.class);
    final ObjectProvider<SystemAgentRunner> provider=mock(ObjectProvider.class);
    final LlmService llm=mock(LlmService.class);
    final SystemAgentExecution execution=new SystemAgentExecution(agents,llm,provider,new ObjectMapper());
    final RunView view=new RunView("1","2",1L,true,"RUNNING",null,null,null,null,0,Instant.now());
    final FrozenRun run=new FrozenRun("1","2","3",null,"AGENT",1L,true,
            new Config("系统","","prompt","4",30,5,List.of()),1,"input",List.of(),view);

    SystemAgentExecutionTest() {
        when(provider.getObject()).thenReturn(runner);
        when(agents.pendingDebugRuns()).thenReturn(List.of(run));
        when(agents.run("1","3")).thenReturn(view);
    }
    @Test void dispatchGateRejectionPreservesReadyRunAndDoesNotCallProvider() {
        when(agents.acceptPurpose("purpose", "3", "key", "input")).thenReturn(run);
        var paused = new IllegalStateException("memory_group_extraction_paused");
        Runnable guard = () -> { throw paused; };
        when(agents.claimGuarded(run, guard)).thenAnswer(call -> { guard.run(); return true; });
        assertSame(paused, assertThrows(IllegalStateException.class,
                () -> execution.executePurposeGuarded("purpose", "3", "key", "input", guard)));
        verifyNoInteractions(llm, runner);
        verify(agents, never()).claim(run);
        verify(agents, never()).finish(any(), any(), any(), any(), any(), anyLong());
    }

    @Test void transientRecoveryQueryFailurePreservesRunning() {
        when(runner.completed(run)).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(execution::recoverDebugRuns);
        verify(agents,never()).finish(any(),any(),any(),any(),any(),anyLong());
    }
    @Test void unfinishedRuntimePreservesRunning() {
        when(runner.completed(run)).thenReturn(Optional.empty());
        execution.recoverDebugRuns();
        verify(agents,never()).finish(any(),any(),any(),any(),any(),anyLong());
    }
    @Test void confirmedRuntimeFailureFinishesFailed() {
        when(runner.completed(run)).thenThrow(new SystemAgentRunner.TerminalFailure("failed"));
        execution.recoverDebugRuns();
        verify(agents).finish(run,null,"system_agent_execution_failed",null,null,0);
    }
    @Test void pollingTimeoutDoesNotFinishOrResubmitRun() {
        when(agents.acceptPurpose("purpose","3","key","input")).thenReturn(run);
        when(agents.claim(run)).thenReturn(true,false);
        when(runner.execute(run)).thenThrow(new SystemAgentRunner.Pending("running"));
        assertEquals(view,execution.executePurpose("purpose","3","key","input"));
        assertEquals(view,execution.executePurpose("purpose","3","key","input"));
        verify(runner,times(1)).execute(run);
        verify(agents,never()).finish(any(),any(),any(),any(),any(),anyLong());
    }

    @Test void controlledModelCallPassesDynamicLimits() {
        var model = new FrozenRun("2", "4", "3", "other", "MODEL_TASK", 1L, false,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L, "input", List.of(), view);
        var completed = new RunView("2", "4", 1L, false, "COMPLETED", "ok", null, null, null, 1, Instant.now());
        when(agents.acceptPurpose("other", "3", "key", "input")).thenReturn(model);
        when(agents.claim(model)).thenReturn(true);
        when(llm.generatePinned(eq("3"), eq("5"), eq(2L), eq("prompt"), eq("input"),
                any(ResponseFormat.class), eq(java.time.Duration.ofSeconds(5)), eq(123), any()))
                .thenReturn(new LlmResult("ok", "model", null));
        when(agents.finish(eq(model), eq("ok"), isNull(), isNull(), isNull(), anyLong())).thenReturn(completed);

        assertEquals(completed, execution.executePurposeControlled("other", "3", "key", "input",
                new SystemAgentExecution.ExecutionLimits(123, java.time.Duration.ofSeconds(5), () -> false,
                        new com.skillforge.runtime.api.ResultRef("root"))).run());
        verify(llm).generatePinned(eq("3"), eq("5"), eq(2L), eq("prompt"), eq("input"),
                any(ResponseFormat.class), eq(java.time.Duration.ofSeconds(5)), eq(123), any());
    }

    @Test void truncatedSummaryKeepsSafeSpecificFailureInsteadOfGenericExecutionError() {
        var summary = new FrozenRun("2", "4", "3", "context-summary", "MODEL_TASK", 1L, false,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L,
                "{\"maxOutputTokens\":100,\"remainingMillis\":5000,\"messages\":[]}", List.of(), view);
        when(agents.acceptPurpose("context-summary", "3", "key", "input")).thenReturn(summary);
        when(agents.claim(summary)).thenReturn(true);
        when(llm.generatePinnedSummary(anyString(), anyString(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt(), any())).thenThrow(new LlmService.TruncatedOutputException());
        when(agents.finish(eq(summary), isNull(), eq("system_agent_output_truncated"), isNull(), isNull(), anyLong()))
                .thenReturn(view);
        try (var parent = parentScopes()) {
            execution.executePurposeControlled("context-summary", "3", "key", "input",
                    new SystemAgentExecution.ExecutionLimits(100, java.time.Duration.ofSeconds(5), () -> false,
                            new com.skillforge.runtime.api.ResultRef("root")));
        }
        verify(agents).finish(eq(summary), isNull(), eq("system_agent_output_truncated"), isNull(), isNull(), anyLong());
    }

    @Test void filteredSummaryFinishesWithSpecificFailureAndNoOutput() {
        var summary = new FrozenRun("2", "4", "3", "context-summary", "MODEL_TASK", 1L, false,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L,
                "{\"maxOutputTokens\":100,\"remainingMillis\":5000,\"messages\":[]}", List.of(), view);
        when(agents.acceptPurpose("context-summary", "3", "key", "input")).thenReturn(summary);
        when(agents.claim(summary)).thenReturn(true);
        when(llm.generatePinnedSummary(anyString(), anyString(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt(), any())).thenThrow(new LlmService.ContentFilteredException());
        when(agents.finish(eq(summary), isNull(), eq("model_content_filtered"), isNull(), isNull(), anyLong()))
                .thenReturn(view);
        try (var parent = parentScopes()) {
            execution.executePurposeControlled("context-summary", "3", "key", "input",
                    new SystemAgentExecution.ExecutionLimits(100, java.time.Duration.ofSeconds(5), () -> false,
                            new com.skillforge.runtime.api.ResultRef("root")));
        }
        verify(agents).finish(eq(summary), isNull(), eq("model_content_filtered"), isNull(), isNull(), anyLong());
        verify(llm, times(1)).generatePinnedSummary(anyString(), anyString(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt(), any());
    }

    @Test void repeatedContextSummaryRunDoesNotCallProviderAgain() {
        var summary = new FrozenRun("2", "4", "3", "context-summary", "MODEL_TASK", 1L, false,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L,
                "{\"maxOutputTokens\":100,\"remainingMillis\":5000,\"messages\":[]}", List.of(), view);
        var completed = new RunView("2", "4", 1L, false, "COMPLETED", "{\"content\":\"摘要\",\"coveredMessages\":1}",
                null, null, null, 1, Instant.now());
        when(agents.acceptPurpose("context-summary", "3", "key", "input")).thenReturn(summary, summary);
        when(agents.claim(summary)).thenReturn(true, false);
        when(agents.run(summary.id(), summary.tenantId())).thenReturn(completed);
        when(llm.generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("prompt"), eq(summary.input()),
                isA(ResponseFormat.Text.class), eq(java.time.Duration.ofSeconds(5)), eq(100), any()))
                .thenReturn(new LlmService.SummaryResult(new LlmResult("摘要", "model", null), 1));
        when(agents.finish(eq(summary), anyString(), isNull(), isNull(), isNull(), anyLong())).thenReturn(completed);
        var limits = new SystemAgentExecution.ExecutionLimits(200, java.time.Duration.ofSeconds(20), () -> false,
                new com.skillforge.runtime.api.ResultRef("root"));

        try (var parent = parentScopes()) {
            execution.executePurposeControlled("context-summary", "3", "key", "input", limits);
            execution.executePurposeControlled("context-summary", "3", "key", "input",
                    new SystemAgentExecution.ExecutionLimits(50, java.time.Duration.ofSeconds(2), () -> false,
                            new com.skillforge.runtime.api.ResultRef("root")));
        }

        verify(llm, times(1)).generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("prompt"), eq(summary.input()),
                isA(ResponseFormat.Text.class), eq(java.time.Duration.ofSeconds(5)), eq(100), any());
    }

    @Test void controlledContextSummaryRejectsNonModelExecution() {
        var invalid = new FrozenRun("3", "4", "3", "context-summary", "AGENT", 1L, false,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L,
                "{\"maxOutputTokens\":100,\"remainingMillis\":5000}", List.of(), view);
        when(agents.acceptPurpose("context-summary", "3", "key", "input")).thenReturn(invalid);

        try (var parent = parentScopes()) {
            assertEquals("context_summary_execution_invalid", assertThrows(IllegalArgumentException.class,
                    () -> execution.executePurposeControlled("context-summary", "3", "key", "input",
                            summaryLimits())).getMessage());
        }
        verify(agents, never()).claim(invalid);
    }

    @Test void contextSummaryUsesOriginalInputOutputLimitAndModelWithShorterRemainingWindow() {
        var summary = new FrozenRun("summary-run", "4", "3", "context-summary", "MODEL_TASK", 1L, false,
                new Config("摘要", "", "原提示", "5", 30, 1, List.of()), 2L,
                "{\"sourceDigest\":\"digest\",\"maxOutputTokens\":100,\"remainingMillis\":5000,\"messages\":[]}", List.of(), view);
        var completed = new RunView("summary-run", "4", 1L, false, "COMPLETED",
                "{\"content\":\"原摘要\",\"coveredMessages\":1}", null, 10L, 3L, 1, Instant.now());
        String changedLimits = "{\"sourceDigest\":\"digest\",\"maxOutputTokens\":200,\"remainingMillis\":1000,\"messages\":[]}";
        when(agents.acceptPurpose("context-summary", "3", "key", changedLimits)).thenReturn(summary);
        when(agents.claim(summary)).thenReturn(true, false);
        when(agents.run(summary.id(), summary.tenantId())).thenReturn(completed);
        when(llm.generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("原提示"), eq(summary.input()),
                isA(ResponseFormat.Text.class), eq(Duration.ofSeconds(1)), eq(100), any()))
                .thenReturn(new LlmService.SummaryResult(new LlmResult("原摘要", "model", null), 1));
        when(agents.finish(eq(summary), eq(completed.output()), isNull(), isNull(), isNull(), anyLong()))
                .thenReturn(completed);

        try (var parent = parentScopes()) {
            var first = execution.executePurposeControlled("context-summary", "3", "key", changedLimits,
                    new SystemAgentExecution.ExecutionLimits(200, Duration.ofSeconds(1), () -> false, new ResultRef("preparation")));
            var replay = execution.executePurposeControlled("context-summary", "3", "key", changedLimits,
                    new SystemAgentExecution.ExecutionLimits(300, Duration.ofMillis(500), () -> false, new ResultRef("preparation")));
            assertEquals(completed, first.run());
            assertEquals(completed, replay.run());
            assertEquals(first.configurationRef(), replay.configurationRef());
            assertEquals(first.invocationRef(), replay.invocationRef());
        }
        verify(llm, times(1)).generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("原提示"), eq(summary.input()),
                isA(ResponseFormat.Text.class), eq(Duration.ofSeconds(1)), eq(100), any());
        verifyNoInteractions(runner);
    }

    @Test void contextSummaryUnknownResultNeverDispatchesAgain() {
        var unknown = new RunView("summary-run", "4", 1L, false, "FAILED", null,
                "system_agent_outcome_unknown", null, null, 1, Instant.now());
        var summary = new FrozenRun("summary-run", "4", "3", "context-summary", "MODEL_TASK", 1L, false,
                new Config("摘要", "", "原提示", "5", 30, 1, List.of()), 2L,
                "{\"maxOutputTokens\":100,\"remainingMillis\":5000}", List.of(), unknown);
        when(agents.acceptPurpose("context-summary", "3", "key", "input")).thenReturn(summary);
        when(agents.claim(summary)).thenReturn(false);
        when(agents.run(summary.id(), summary.tenantId())).thenReturn(unknown);

        try (var parent = parentScopes()) {
            for (var remaining : List.of(Duration.ofSeconds(5), Duration.ofMillis(500))) {
                var result = execution.executePurposeControlled("context-summary", "3", "key", "input",
                        new SystemAgentExecution.ExecutionLimits(100, remaining, () -> false, new ResultRef("preparation")));
                assertEquals(unknown, result.run());
                assertEquals(new ResultRef("system-agent-invocation:summary-run"), result.invocationRef());
            }
        }
        verifyNoInteractions(llm, runner);
        verify(agents, never()).finish(any(), any(), any(), any(), any(), anyLong());
    }

    @Test void unknownModelResultKeepsTheOriginalRunUnknownWithoutRedispatch() {
        var model = new FrozenRun("2", "4", "3", "other", "MODEL_TASK", 1L, true,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L, "input", List.of(), view);
        var unknown = new RunView("2", "4", 1L, true, "FAILED", null,
                "system_agent_outcome_unknown", null, null, 1, Instant.now());
        when(agents.acceptPurpose("other", "3", "key", "input")).thenReturn(model);
        when(agents.claim(model)).thenReturn(true, false);
        when(agents.run("2", "3")).thenReturn(unknown);
        when(llm.generatePinned(eq("3"), eq("5"), eq(2L), eq("prompt"), eq("input"),
                any(ResponseFormat.class), any(Duration.class), anyInt(), any()))
                .thenThrow(new LlmService.ModelOutcomeUnknownException(
                        new com.skillforge.core.billing.invocation.ExternalInvocationGuard.OutcomeUnknownException("unknown", null)));
        when(agents.finish(eq(model), isNull(), eq("system_agent_outcome_unknown"), isNull(), isNull(), anyLong()))
                .thenReturn(unknown);

        assertEquals(unknown, execution.executePurpose("other", "3", "key", "input"));
        assertEquals(unknown, execution.executePurpose("other", "3", "key", "input"));
        verify(llm, times(1)).generatePinned(eq("3"), eq("5"), eq(2L), eq("prompt"), eq("input"),
                any(ResponseFormat.class), any(Duration.class), anyInt(), any());
        verify(agents, times(1)).finish(eq(model), isNull(), eq("system_agent_outcome_unknown"), isNull(), isNull(), anyLong());
    }

    @Test void contextSummaryRequiresBusinessParentBeforeAcceptanceOrModelDispatch() {
        assertEquals("context_summary_parent_budget_missing", assertThrows(IllegalArgumentException.class,
                () -> execution.executePurposeControlled("context-summary", "3", "key", "input", summaryLimits())).getMessage());
        verify(agents, never()).acceptPurpose(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(llm, runner);
    }

    @Test void contextSummaryRejectsWrongBudgetOrContradictorySourceIdentity() {
        var mismatches = List.of(
                CostContext.builder().runId("other-run").sessionId("parent-session").agentId(42L)
                        .requestId("input").rootInputId("root").traceContext(sourceTrace()).build(),
                CostContext.builder().runId("parent-run").sessionId("parent-session").agentId(42L)
                        .requestId("other-input").rootInputId("root").traceContext(sourceTrace()).build(),
                CostContext.builder().runId("parent-run").sessionId("parent-session").agentId(42L)
                        .requestId("input").rootInputId("other-root").traceContext(sourceTrace()).build(),
                CostContext.builder().runId("parent-run").requestId("input").rootInputId("root")
                        .traceContext(sourceTrace()).build());
        for (var mismatch : mismatches) {
            try (var parent = parentScopes(mismatch, parentRun())) {
                assertEquals("context_summary_parent_identity_mismatch", assertThrows(IllegalArgumentException.class,
                        () -> execution.executePurposeControlled("context-summary", "3", "key", "input", summaryLimits())).getMessage());
            }
        }
        try (var parent = parentScopes(parentCost(), new RunRef(new TenantId("other-tenant"), "parent-run"))) {
            assertEquals("context_summary_parent_identity_mismatch", assertThrows(IllegalArgumentException.class,
                    () -> execution.executePurposeControlled("context-summary", "3", "key", "input", summaryLimits())).getMessage());
        }
        verify(agents, never()).acceptPurpose(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(llm, runner);
        assertNull(MeterScope.current());
        assertTrue(ExecutionBudgetScope.current().isEmpty());
    }

    @Test void contextSummaryKeepsBusinessRootAndSourceTraceWhileScopingTechnicalInvocation() {
        var summary = new FrozenRun("summary-run", "4", "3", "context-summary", "MODEL_TASK", 1L, false,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L,
                "{\"maxOutputTokens\":100,\"remainingMillis\":5000,\"messages\":[]}", List.of(), view);
        var parentCost = parentCost();
        var binding = new ExecutionBudgetScope.Binding(mock(ExecutionBudgetPort.class), parentRun());
        when(agents.acceptPurpose("context-summary", "3", "key", "input")).thenReturn(summary);
        when(agents.claim(summary)).thenReturn(true);
        when(llm.generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("prompt"), eq(summary.input()),
                isA(ResponseFormat.Text.class), eq(Duration.ofSeconds(5)), eq(100), any())).thenAnswer(call -> {
            var actual = MeterScope.current();
            assertEquals("parent-run", actual.runId()); assertEquals("parent-session", actual.sessionId());
            assertEquals(42L, actual.agentId()); assertEquals(7L, actual.endUserId());
            assertEquals("input", actual.requestId()); assertEquals("root", actual.rootInputId());
            assertEquals(sourceTrace(), actual.traceContext());
            assertEquals("preparation:summary-run", actual.eventId());
            assertNotEquals(actual.eventId(), actual.rootInputId());
            assertEquals("summary-run", actual.scopeId()); assertEquals("system_agent_run", actual.scopeType());
            assertEquals("system-agent.task", actual.callPoint()); assertSame(binding, ExecutionBudgetScope.current().orElseThrow());
            assertLedgerAndUsageKeepOriginalBusinessIdentity(actual);
            return new LlmService.SummaryResult(new LlmResult("摘要", "model", null), 1);
        });
        when(agents.finish(eq(summary), anyString(), isNull(), isNull(), isNull(), anyLong())).thenReturn(view);
        try (var meter = MeterScope.openIsolated(parentCost); var budget = ExecutionBudgetScope.open(binding)) {
            execution.executePurposeControlled("context-summary", "3", "key", "input", summaryLimits());
            assertSame(parentCost, MeterScope.current()); assertSame(binding, ExecutionBudgetScope.current().orElseThrow());
        }
        verify(llm).generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("prompt"), eq(summary.input()),
                isA(ResponseFormat.Text.class), eq(Duration.ofSeconds(5)), eq(100), any());
        assertNull(MeterScope.current()); assertTrue(ExecutionBudgetScope.current().isEmpty());
    }

    @Test void contextSummaryDebugRemainsAnIndependentTechnicalInvocation() {
        var summary = new FrozenRun("summary-debug", "4", "3", "context-summary", "MODEL_TASK", null, true,
                new Config("系统", "", "prompt", "5", 30, 1, List.of()), 2L, "debug-input", List.of(), view);
        when(agents.detail("4")).thenReturn(new View("4", "context-summary", "context-summary", "MODEL_TASK",
                "draft", 1, null, summary.config(), null, Instant.now()));
        when(agents.acceptDebug("4", "DRAFT", 1, "key", "debug-input", "actor")).thenReturn(summary);
        when(agents.claim(summary)).thenReturn(true);
        when(llm.generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("prompt"), eq("debug-input"),
                isA(ResponseFormat.Text.class), any(), anyInt(), any())).thenAnswer(call -> {
            var actual = MeterScope.current();
            assertNull(actual.runId()); assertNull(actual.sessionId()); assertNull(actual.endUserId());
            assertEquals("system-agent.debug", actual.callPoint());
            assertEquals(actual.eventId(), actual.traceContext().inputId());
            assertEquals(actual.eventId(), actual.traceContext().rootInputId());
            assertTrue(ExecutionBudgetScope.current().isEmpty());
            return new LlmService.SummaryResult(new LlmResult("摘要", "model", null), 1);
        });
        when(agents.finish(eq(summary), anyString(), isNull(), isNull(), isNull(), anyLong())).thenReturn(view);

        assertEquals(view, execution.debug("4", "DRAFT", 1, "key", "debug-input", "actor"));

        verify(llm).generatePinnedSummary(eq("3"), eq("5"), eq(2L), eq("prompt"), eq("debug-input"),
                isA(ResponseFormat.Text.class), any(), anyInt(), any());
        assertNull(MeterScope.current());
    }

    @Test void taskContextKeepsHostRoutingAndJsonResultWithoutAgentActionEnvelope() {
        var task = new FrozenRun("2", "4", "3", "task-context", "MODEL_TASK", 1L, false,
                new Config("任务维护", "", "用途配置：所有查询都返回 none", "5", 30, 1, List.of()),
                2L, "input", List.of(), view);
        var completed = new RunView("2", "4", 1L, false, "COMPLETED", "{\"action\":\"direct\"}",
                null, null, null, 1, Instant.now());
        when(agents.acceptPurpose("task-context", "3", "key", "input")).thenReturn(task);
        when(agents.claim(task)).thenReturn(true);
        when(llm.generatePinned(eq("3"), eq("5"), eq(2L), anyString(), eq("input"),
                any(ResponseFormat.class), any(), eq(123), any()))
                .thenReturn(new LlmResult("{\"action\":\"direct\"}", "model", null));
        when(agents.finish(eq(task), anyString(), isNull(), isNull(), isNull(), anyLong())).thenReturn(completed);

        assertEquals(completed, execution.executePurposeControlled("task-context", "3", "key", "input",
                new SystemAgentExecution.ExecutionLimits(123, java.time.Duration.ofSeconds(5), () -> false,
                        new com.skillforge.runtime.api.ResultRef("root"))).run());
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).generatePinned(eq("3"), eq("5"), eq(2L), prompt.capture(), eq("input"),
                isA(ResponseFormat.JsonObject.class), any(), eq(123), any());
        assertEquals(task.config().systemPrompt(), prompt.getValue());
        assertFalse(prompt.getValue().contains("respond.output"));
        verifyNoInteractions(runner);
    }

    @Test void memoryInputIsValidatedBeforeAnyModelDispatch() {
        var memory = memoryRun("{\"userId\":7,\"text\":\"legacy\"}");
        when(agents.acceptPurpose("memory-extraction", "3", "key", "input")).thenReturn(memory);
        when(agents.claim(memory)).thenReturn(true);
        when(agents.finish(eq(memory), isNull(), anyString(), isNull(), isNull(), anyLong())).thenReturn(view);

        execution.executePurpose("memory-extraction", "3", "key", "input");

        verifyNoInteractions(llm, runner);
        verify(agents).finish(eq(memory), isNull(), eq("memory_extraction_input_invalid"), isNull(), isNull(), anyLong());
    }

    @Test void invalidMemoryOutputRetainsProviderEvidenceAndUsageWithoutReplay() {
        String input = """
                {"schemaVersion":2,"userId":7,"sources":[{"id":"s0","text":"偏好 Markdown",
                  "occurredAt":"2026-10-23T00:00:00Z"}],"existingFacts":[]}
                """;
        var memory = memoryRun(input);
        when(agents.acceptPurpose("memory-extraction", "3", "key", "input")).thenReturn(memory);
        when(agents.claim(memory)).thenReturn(true, false);
        String output = """
                {"schemaVersion":2,"candidates":[{"type":"preference","subject":"user:7","predicate":"format",
                 "object":{"text":"HTML"},"signal":"observed","sensitive":false,"scope":"user_agent",
                 "temporal":{"kind":"stable","from":null,"to":null},"conditions":[],
                 "evidence":[{"sourceId":"s0","quote":"fabricated evidence"}],
                 "relation":{"kind":"new","target":null},"uncertainties":[]}]}
                """;
        when(llm.generatePinned(eq("3"), eq("5"), eq(2L), eq("prompt"), eq(input),
                isA(ResponseFormat.JsonObject.class), any(), anyInt(), any()))
                .thenReturn(new LlmResult(output, "model", new com.skillforge.core.billing.metering.LlmUsage(41, 17, 58)));
        var failed = new RunView("2", "4", 1L, false, "FAILED", output, "memory_extraction_evidence_invalid",
                41L, 17L, 1, Instant.now());
        when(agents.finish(eq(memory), eq(output), eq("memory_extraction_evidence_invalid"),
                eq(41L), eq(17L), anyLong())).thenReturn(failed);
        when(agents.run(memory.id(), memory.tenantId())).thenReturn(failed);

        assertEquals(failed, execution.executePurpose("memory-extraction", "3", "key", "input"));
        assertEquals(failed, execution.executePurpose("memory-extraction", "3", "key", "input"));

        verify(llm, times(1)).generatePinned(eq("3"), eq("5"), eq(2L), eq("prompt"), eq(input),
                isA(ResponseFormat.JsonObject.class), any(), anyInt(), any());
        verify(agents, times(1)).finish(eq(memory), eq(output), eq("memory_extraction_evidence_invalid"), eq(41L), eq(17L), anyLong());
        verifyNoInteractions(runner);
    }

    @Test void memoryDebugUsesProductionInputContractWithoutChangingSourceTime() throws Exception {
        String input = """
                {"schemaVersion":2,"userId":7,"sources":[{"id":"s0","text":"偏好 Markdown",
                  "occurredAt":"2024-01-01T00:00:00Z"}],"existingFacts":[]}
                """;
        var contract = new MemoryExtractionCandidates(new ObjectMapper());
        String normalized = contract.encodeInput(contract.decodeInput(input));
        var memory = memoryRun(normalized);
        when(agents.detail("4")).thenReturn(new View("4", "memory-extraction", "memory-extraction", "MODEL_TASK",
                "draft", 1, null, memory.config(), null, Instant.now()));
        when(agents.acceptDebug("4", "DRAFT", 1, "key", normalized, "actor")).thenReturn(memory);
        when(agents.claim(memory)).thenReturn(false);
        when(agents.run(memory.id(), memory.tenantId())).thenReturn(view);

        execution.debug("4", "DRAFT", 1, "key", input, "actor");

        verify(agents).acceptDebug("4", "DRAFT", 1, "key", normalized, "actor");
        verifyNoInteractions(llm, runner);
    }

    private FrozenRun memoryRun(String input) {
        return new FrozenRun("2", "4", "3", "memory-extraction", "MODEL_TASK", 1L, false,
                new Config("记忆提取", "", "prompt", "5", 30, 1, List.of()), 2L, input, List.of(), view);
    }

    private static RunRef parentRun() {
        return new RunRef(new TenantId("3"), "parent-run");
    }

    private static TraceContext sourceTrace() {
        return new TraceContext("input", "root", "trace", "span", "request-event", "origin-event");
    }

    private static CostContext parentCost() {
        return CostContext.builder().eventId("parent-event").runId("parent-run").sessionId("parent-session")
                .agentId(42L).requestId("input").rootInputId("root").endUserId(7L).traceContext(sourceTrace()).build();
    }

    private static SystemAgentExecution.ExecutionLimits summaryLimits() {
        return new SystemAgentExecution.ExecutionLimits(100, Duration.ofSeconds(5), () -> false, new ResultRef("preparation"));
    }

    private static ParentScopes parentScopes() {
        return parentScopes(parentCost(), parentRun());
    }

    private static ParentScopes parentScopes(CostContext cost, RunRef run) {
        return new ParentScopes(MeterScope.openIsolated(cost),
                ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(mock(ExecutionBudgetPort.class), run)));
    }

    private record ParentScopes(MeterScope meter, ExecutionBudgetScope budget) implements AutoCloseable {
        @Override public void close() { budget.close(); meter.close(); }
    }

    /** 使用正式账本/计量映射器验证同一个真实来源贯穿开始、累计报告与终态计量；不发送模型请求。 */
    private static void assertLedgerAndUsageKeepOriginalBusinessIdentity(CostContext cost) {
        var invocations = mock(ExternalInvocationMapper.class);
        var events = mock(CostEventMapper.class);
        var prices = mock(PriceCalculator.class);
        when(invocations.insert(any(ExternalInvocationDO.class))).thenReturn(1);
        when(events.insertUsageReport(any(CostEventDO.class))).thenReturn(1);
        when(events.insert(any(CostEventDO.class))).thenReturn(1);
        when(events.selectCount(any())).thenReturn(0L);
        var usage = new LlmUsage(10, 3, 13);
        when(prices.calculate(isNull(), eq(3L), eq(CostType.LLM_CHAT), eq("provider"), eq("model"),
                eq(usage), eq(1), isNull())).thenReturn(
                        new PriceCalculator.Amounts(null, null, null, null, null, null, null, "unpriced-model", null));
        var recorder = new CostRecorder(events, prices);
        var ledger = new ExternalInvocationLedger(invocations, recorder);
        var request = new ExternalInvocationRequest("summary-invocation", "summary-attempt", ExternalOperationType.MODEL_CHAT,
                "provider", "model", "summary-key", cost.traceContext(), Map.of());
        assertTrue(ledger.startAuxiliary("3", request, Instant.now()));
        recorder.recordUsageReport("3", cost, request, "model", 1, usage, Map.of());
        try (var scope = ExternalInvocationScope.open(request)) {
            recorder.recordLlm("3", CostType.LLM_CHAT, "provider", "model", usage, 1, Map.of());
        }
        var invocation = org.mockito.ArgumentCaptor.forClass(ExternalInvocationDO.class);
        verify(invocations).insert(invocation.capture());
        assertEquals("input", invocation.getValue().getInputId()); assertEquals("root", invocation.getValue().getRootInputId());
        assertEquals("trace", invocation.getValue().getTraceId()); assertEquals("span", invocation.getValue().getSpanId());
        assertEquals("request-event", invocation.getValue().getSourceEventId());
        var reports = org.mockito.ArgumentCaptor.forClass(CostEventDO.class);
        verify(events, times(2)).insertUsageReport(reports.capture());
        var measured = org.mockito.ArgumentCaptor.forClass(CostEventDO.class);
        verify(events).insert(measured.capture());
        for (var event : List.of(reports.getAllValues().getFirst(), reports.getAllValues().getLast(), measured.getValue())) {
            assertEquals(3L, event.getTenantId()); assertEquals("parent-run", event.getRunId());
            assertEquals("parent-session", event.getSessionId()); assertEquals(42L, event.getAgentId()); assertEquals(7L, event.getEndUserId());
            assertEquals("input", event.getRequestId()); assertEquals("root", event.getRootInputId());
            assertEquals("root", event.getMeta().get("root_input_id"));
            assertEquals("trace", event.getMeta().get("trace_id")); assertEquals("span", event.getMeta().get("span_id"));
            assertEquals("summary-invocation", event.getInvocationId()); assertEquals("summary-attempt", event.getAttemptId());
        }
        assertNull(reports.getAllValues().getFirst().getPromptTokens());
        assertEquals(10L, reports.getAllValues().getLast().getPromptTokens());
        assertEquals(10L, measured.getValue().getPromptTokens()); assertEquals(3L, measured.getValue().getCompletionTokens());
        assertNull(ExternalInvocationScope.current());
    }
}
