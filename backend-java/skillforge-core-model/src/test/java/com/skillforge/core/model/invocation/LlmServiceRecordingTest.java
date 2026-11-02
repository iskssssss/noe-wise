package com.skillforge.core.model.invocation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.budget.AuxiliaryModelBudgets;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.resilience.ResilienceFixtures;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.ModelPort;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 捕获点先于网络和业务裁决；采集故障不得重发或覆盖供应商语义。 */
class LlmServiceRecordingTest {
    private final ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
    private final ModelCallRecords records = mock(ModelCallRecords.class);
    private final CostRecorder costs = mock(CostRecorder.class);
    private final ExternalInvocationGuard guard = mock(ExternalInvocationGuard.class);
    private final ModelCallRecordCodec codec = new ModelCallRecordCodec();
    private final LlmService service = service();

    @Test void recordsTheActualParametersBeforeSendAndTheFullReturnBeforeAccounting() throws Exception {
        when(runtime.generate(any(), any())).thenReturn(completed("stop", List.of()));
        assertEquals("原返回", call().content());
        var input = ArgumentCaptor.forClass(TypedPayload.class);
        var output = ArgumentCaptor.forClass(TypedPayload.class);
        var invocation = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(guard).invokeModel(eq("7"), invocation.capture(), any(), any());
        var order = inOrder(records, runtime, costs);
        order.verify(records).recordRequest(eq(7L), eq(invocation.getValue().invocationId()), eq(invocation.getValue().attemptId()), input.capture());
        order.verify(runtime).generate(eq("deepseek"), any());
        order.verify(records).recordOutcome(eq(7L), eq(invocation.getValue().invocationId()), eq(invocation.getValue().attemptId()), output.capture());
        order.verify(costs).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        var request = codec.decodeRequest(input.getValue());
        assertEquals(73, request.generation().maxOutputTokens());
        assertEquals("PT4S", request.timeout());
        assertEquals("原系统指令", request.messages().getFirst().contents().getFirst().text());
        assertEquals("原用户问题", request.messages().getLast().contents().getFirst().text());
        var result = codec.decodeOutcome(output.getValue());
        assertEquals("原返回", result.content());
        assertEquals("原推理", result.reasoningContent());
        assertEquals(2L, result.usage().cachedInputTokens());
        assertNull(result.usage().cacheWriteInputTokens());
    }

    @Test void truncatedAndUnexpectedToolReturnsAreRecordedBeforeTheHostRejectsThem() {
        when(runtime.generate(any(), any())).thenReturn(completed("length", List.of()));
        assertThrows(LlmService.TruncatedOutputException.class, this::call);
        var output = ArgumentCaptor.forClass(TypedPayload.class);
        verify(records).recordOutcome(eq(7L), anyString(), anyString(), output.capture());
        assertEquals("length", codec.decodeOutcome(output.getValue()).finishReasonCode());
        assertEquals("原返回", codec.decodeOutcome(output.getValue()).content());
        verify(costs).recordLlm(eq("7"), any(), eq("deepseek"), eq("model-x"),
                argThat(usage -> Long.valueOf(5).equals(usage.promptTokens()) && Long.valueOf(3).equals(usage.completionTokens())),
                eq(1), any(), any());
        reset(records, runtime);
        var actualTools = List.of(new ModelProvider.ToolCall("actual-call", "unexpected", "{invalid"));
        when(runtime.generate(any(), any())).thenReturn(completed("tool_calls", actualTools));
        assertThrows(ApiException.class, this::call);
        verify(records).recordOutcome(eq(7L), anyString(), anyString(), output.capture());
        assertEquals(actualTools, codec.decodeOutcome(output.getValue()).toolCalls());
        verify(costs, times(2)).recordLlm(eq("7"), any(), eq("deepseek"), eq("model-x"),
                argThat(usage -> Long.valueOf(5).equals(usage.promptTokens()) && Long.valueOf(3).equals(usage.completionTokens())),
                eq(1), any(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void rejectedReportedUsageFlowsThroughOriginalAttemptToBudgetAndCosts() throws Exception {
        var port = mock(ExecutionBudgetPort.class);
        var ledger = mock(ExternalInvocationLedger.class);
        var run = new RunRef(new TenantId("7"), "task-run");
        var realService = service(realGuard(port, ledger));
        var reported = new ModelPort.ModelUsage(OptionalLong.of(0), OptionalLong.empty(),
                OptionalLong.of(0), OptionalLong.empty(), OptionalLong.empty());
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Rejected("provider_rejected", Optional.empty(), reported));
        var attempt = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> { attempt.set(ExternalInvocationScope.current().attemptId()); return null; })
                .when(costs).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run))) {
            assertThrows(ApiException.class, () -> call(realService));
        }
        verify(costs).recordLlm(eq("7"), any(), eq("deepseek"), eq("model-x"),
                argThat(usage -> usage.usageSource() == UsageSource.PROVIDER_REPORTED
                        && Long.valueOf(0).equals(usage.promptTokens()) && usage.completionTokens() == null
                        && Long.valueOf(0).equals(usage.cachedInputTokens()) && usage.totalTokens() == null),
                eq(1), eq(new ModelRevisionRef("10", 1)), isNull());
        verify(port).saveResult(eq(run), eq(attempt.get()), eq(new ExecutionBudgetPort.Usage(
                OptionalLong.of(0), OptionalLong.empty(), OptionalLong.empty())), anyString());
        verify(port).settle(run, attempt.get());
        verify(ledger).finish(eq("7"), eq(attempt.get()), eq(ExternalInvocationStatus.FAILED), isNull(), anyString(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void failedAccountingKeepsRejectionAndBudgetUnreported() throws Exception {
        var port = mock(ExecutionBudgetPort.class);
        var ledger = mock(ExternalInvocationLedger.class);
        var run = new RunRef(new TenantId("7"), "task-run");
        var realService = service(realGuard(port, ledger));
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Rejected("provider_rejected", Optional.empty(),
                new ModelPort.ModelUsage(OptionalLong.of(5), OptionalLong.of(3))));
        doThrow(new IllegalStateException("private-accounting-error")).when(costs).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run))) {
            var failure = assertThrows(ApiException.class, () -> call(realService));
            assertEquals("LLM 服务调用失败", failure.getDetail());
        }
        var attempt = ArgumentCaptor.forClass(String.class);
        verify(port).saveResult(eq(run), attempt.capture(), eq(ExecutionBudgetPort.Usage.unreported()), anyString());
        verify(ledger).finish(eq("7"), eq(attempt.getValue()), eq(ExternalInvocationStatus.FAILED), isNull(), anyString(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void missingRejectedUsageAndUnknownDoNotInventReportedBudget() throws Exception {
        var port = mock(ExecutionBudgetPort.class);
        var ledger = mock(ExternalInvocationLedger.class);
        var run = new RunRef(new TenantId("7"), "task-run");
        var realService = service(realGuard(port, ledger));
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Rejected("provider_rejected"));
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run))) {
            assertThrows(ApiException.class, () -> call(realService));
        }
        verify(costs, never()).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        verify(port).saveResult(eq(run), anyString(), eq(ExecutionBudgetPort.Usage.unreported()), anyString());
        reset(port, ledger, runtime);
        realGuard(port, ledger);
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown",
                new ModelPort.ModelUsage(OptionalLong.of(5), OptionalLong.of(3))));
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run))) {
            assertThrows(LlmService.ModelOutcomeUnknownException.class, () -> call(realService));
        }
        verify(port, never()).saveResult(any(), anyString(), any(), anyString());
        verify(ledger).finish(eq("7"), anyString(), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), anyString(), any());
        verify(costs, never()).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        verify(costs).recordUsageReport(eq("7"), any(), any(), eq("model-x"), eq(1L),
                argThat(usage -> Long.valueOf(5).equals(usage.promptTokens())
                        && Long.valueOf(3).equals(usage.completionTokens())), eq(java.util.Map.of()));
    }

    @Test void unknownReportedPartialUsageRetainsAttemptAndMeterContextWithoutSettling() throws Exception {
        var port = mock(ExecutionBudgetPort.class);
        var ledger = mock(ExternalInvocationLedger.class);
        var run = new RunRef(new TenantId("7"), "task-run");
        var realService = service(realGuard(port, ledger));
        var context = CostContext.builder().eventId("event-1").requestId("request-1")
                .rootInputId("root-1").sessionId("session-1").runId("run-1").callPoint("test.model").build();
        var reported = new ModelPort.ModelUsage(OptionalLong.of(0), OptionalLong.empty(),
                OptionalLong.of(0), OptionalLong.empty(), OptionalLong.empty());
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown", reported));
        try (var budget = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run));
             var meter = MeterScope.open(context)) {
            assertThrows(LlmService.ModelOutcomeUnknownException.class, () -> call(realService));
        }
        var started = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        var reportedAttempt = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        var reportedContext = ArgumentCaptor.forClass(CostContext.class);
        var usage = ArgumentCaptor.forClass(LlmUsage.class);
        verify(ledger).startAuxiliary(eq("7"), started.capture(), any());
        verify(costs).recordUsageReport(eq("7"), reportedContext.capture(), reportedAttempt.capture(),
                eq("model-x"), eq(1L), usage.capture(), eq(java.util.Map.of()));
        assertEquals(started.getValue(), reportedAttempt.getValue());
        assertEquals(context, reportedContext.getValue());
        assertEquals(UsageSource.PROVIDER_REPORTED, usage.getValue().usageSource());
        assertEquals(Long.valueOf(0), usage.getValue().promptTokens());
        assertEquals(Long.valueOf(0), usage.getValue().cachedInputTokens());
        assertNull(usage.getValue().completionTokens());
        assertNull(usage.getValue().totalTokens());
        verify(ledger).finish(eq("7"), eq(started.getValue().attemptId()), eq(ExternalInvocationStatus.UNRESOLVED),
                isNull(), anyString(), any());
        verify(port, never()).saveResult(any(), anyString(), any(), anyString());
        verify(port, never()).settle(any(), anyString());
        verify(costs, never()).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void unknownReportedUsageWithoutMeterScopeUsesStartedAttemptContext() throws Exception {
        var port = mock(ExecutionBudgetPort.class);
        var ledger = mock(ExternalInvocationLedger.class);
        var run = new RunRef(new TenantId("7"), "task-run");
        var realService = service(realGuard(port, ledger));
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown",
                new ModelPort.ModelUsage(OptionalLong.of(5), OptionalLong.empty())));
        try (var budget = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run))) {
            assertThrows(LlmService.ModelOutcomeUnknownException.class, () -> call(realService));
        }
        var started = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        var report = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        var context = ArgumentCaptor.forClass(CostContext.class);
        verify(ledger).startAuxiliary(eq("7"), started.capture(), any());
        verify(costs).recordUsageReport(eq("7"), context.capture(), report.capture(), eq("model-x"), eq(1L), any(), eq(java.util.Map.of()));
        assertEquals(started.getValue(), report.getValue());
        var trace = (com.skillforge.core.shared.tracing.TraceContext) started.getValue().context();
        assertEquals(trace, context.getValue().traceContext());
        assertEquals(trace.inputId(), context.getValue().requestId());
        assertEquals(trace.rootInputId(), context.getValue().rootInputId());
        assertEquals("auxiliary.invocation", context.getValue().callPoint());
        verify(port, never()).saveResult(any(), anyString(), any(), anyString());
        verify(port, never()).settle(any(), anyString());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void unknownUsageRecordingFailureDoesNotReplaceUnknownOrSettle() throws Exception {
        var port = mock(ExecutionBudgetPort.class);
        var ledger = mock(ExternalInvocationLedger.class);
        var run = new RunRef(new TenantId("7"), "task-run");
        var realService = service(realGuard(port, ledger));
        var accountingFailure = new IllegalStateException("private-accounting-error");
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown",
                new ModelPort.ModelUsage(OptionalLong.of(5), OptionalLong.empty())));
        doThrow(accountingFailure).when(costs).recordUsageReport(anyString(), any(), any(), anyString(), anyLong(), any(), any());
        LlmService.ModelOutcomeUnknownException failure;
        try (var budget = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(port, run))) {
            failure = assertThrows(LlmService.ModelOutcomeUnknownException.class, () -> call(realService));
        }
        var original = assertInstanceOf(ExternalInvocationGuard.OutcomeUnknownException.class, failure.getCause());
        assertArrayEquals(new Throwable[] {accountingFailure}, original.getSuppressed());
        verify(ledger).finish(eq("7"), anyString(), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), anyString(), any());
        verify(port, never()).saveResult(any(), anyString(), any(), anyString());
        verify(port, never()).settle(any(), anyString());
        verify(costs, never()).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void filteredOutputRecordsUsageAndFailsWithoutReturningContent() {
        when(runtime.generate(any(), any())).thenReturn(completed("content_filter", List.of()));
        var failure = assertThrows(LlmService.ContentFilteredException.class, this::call);
        assertEquals("model_content_filtered", failure.getMessage());
        var order = inOrder(records, runtime, costs);
        order.verify(records).recordRequest(eq(7L), anyString(), anyString(), any());
        order.verify(runtime).generate(eq("deepseek"), any());
        order.verify(records).recordOutcome(eq(7L), anyString(), anyString(), any());
        order.verify(costs).recordLlm(eq("7"), any(), eq("deepseek"), eq("model-x"),
                argThat(usage -> Long.valueOf(5).equals(usage.promptTokens()) && Long.valueOf(3).equals(usage.completionTokens())),
                eq(1), any(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void rejectedAndUnknownRemainDistinctRecordedFactsWithoutRetry() {
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Rejected("provider_rejected"));
        assertThrows(ApiException.class, this::call);
        var output = ArgumentCaptor.forClass(TypedPayload.class);
        verify(records).recordOutcome(eq(7L), anyString(), anyString(), output.capture());
        assertEquals(ModelCallRecordCodec.OutcomeKind.REJECTED, codec.decodeOutcome(output.getValue()).kind());
        reset(records, runtime);
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown"));
        var unknown = assertThrows(LlmService.ModelOutcomeUnknownException.class, this::call);
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, unknown.getStatus());
        assertInstanceOf(ExternalInvocationGuard.OutcomeUnknownException.class, unknown.getCause());
        verify(records).recordOutcome(eq(7L), anyString(), anyString(), output.capture());
        assertEquals(ModelCallRecordCodec.OutcomeKind.OUTCOME_UNKNOWN, codec.decodeOutcome(output.getValue()).kind());
        verify(costs, never()).recordUsageReport(anyString(), any(), any(), anyString(), anyLong(), any(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void failedRequestCaptureLeavesTheRealSuccessAndSingleSendIntact() {
        doThrow(new IllegalStateException("private-request-secret")).when(records).recordRequest(anyLong(), anyString(), anyString(), any());
        when(runtime.generate(any(), any())).thenReturn(completed("stop", List.of()));
        assertEquals("原返回", call().content());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
        verify(records, never()).recordOutcome(anyLong(), anyString(), anyString(), any());
    }

    @Test void failedOutcomeCaptureCannotReplaceTheRealOutcomeOrCauseAnotherSend() {
        doThrow(new IllegalStateException("private-response-secret")).when(records).recordOutcome(anyLong(), anyString(), anyString(), any());
        when(runtime.generate(any(), any())).thenReturn(completed("stop", List.of()));
        assertEquals("原返回", call().content());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    @Test void aProviderExceptionKeepsOnlyTheOriginalRequestWithoutInventingAResult() {
        when(runtime.generate(any(), any())).thenThrow(new IllegalStateException("private-provider-secret"));
        var failure = assertThrows(ApiException.class, this::call);
        assertEquals("LLM 服务调用失败", failure.getDetail());
        assertFalse(failure.toString().contains("private-provider-secret"));
        verify(records).recordRequest(eq(7L), anyString(), anyString(), any());
        verify(records, never()).recordOutcome(anyLong(), anyString(), anyString(), any());
        verify(runtime, times(1)).generate(eq("deepseek"), any());
    }

    private LlmResult call() {
        return call(service);
    }
    private LlmResult call(LlmService target) {
        return target.generatePinned("7", "10", 1, "原系统指令", "原用户问题", new ModelPort.ResponseFormat.Text(),
                Duration.ofSeconds(4), 73, () -> false);
    }
    private ExternalInvocationGuard realGuard(ExecutionBudgetPort port, ExternalInvocationLedger ledger) {
        when(port.reserve(any())).thenAnswer(call -> new ExecutionBudgetPort.Reservation.Granted(
                new ExecutionBudgetPort.Permit(call.getArgument(0), ExecutionBudgetPort.AttemptState.RESERVED)));
        when(port.markSent(any(), anyString())).thenReturn(true);
        when(ledger.startAuxiliary(eq("7"), any(), any())).thenReturn(true);
        return new ExternalInvocationGuard(ledger, new AuxiliaryModelBudgets(port, mock(OperationalSettingsService.class)));
    }
    private static ModelProvider.InvocationResult.Completed completed(String finish, List<ModelProvider.ToolCall> calls) {
        return new ModelProvider.InvocationResult.Completed("原返回", calls, "model-x",
                new ModelPort.ModelUsage(OptionalLong.of(5), OptionalLong.of(3), OptionalLong.of(2), OptionalLong.empty(), OptionalLong.of(1)),
                finish, "原推理");
    }
    private LlmService service() {
        try {
            when(guard.invokeModel(any(), any(), any(), any())).thenAnswer(invocation -> {
                ExternalInvocationGuard.ExternalCall<?> action = invocation.getArgument(3);
                return action.call();
            });
        } catch (Exception impossible) { throw new AssertionError(impossible); }
        return service(guard);
    }
    private LlmService service(ExternalInvocationGuard chosenGuard) {
        var catalog = mock(PlatformModelCatalogService.class);
        var http = mock(LlmHttpSupport.class);
        var model = new PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("private-credential");
        return new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(), mock(OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(), chosenGuard, costs, records);
    }
}
