package com.skillforge.core.billing.metering;

import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.pricing.TestPriceCalculators;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.shared.tracing.TraceContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 报告幂等按五字段核验，部分报告和大整数不得被成功/金额状态覆盖。 */
class ProviderUsageReportTest {
    private final CostEventMapper mapper = mock(CostEventMapper.class);
    private final CostRecorder recorder = new CostRecorder(mapper, TestPriceCalculators.unpriced());
    private final CostContext context = CostContext.builder().sessionId("session").rootInputId("root")
            .requestId("input").runId("run").callPoint("agent-runtime.model").build();
    private final ExternalInvocationRequest invocation = new ExternalInvocationRequest("invocation", "attempt",
            ExternalOperationType.MODEL_CHAT, "provider", "service", "attempt",
            new TraceContext("input", "root", "trace", "span", "event", null), Map.of());

    @Test void recordsPartialUsageWithoutInventingOutputOrPrice() {
        when(mapper.insertUsageReport(any())).thenReturn(1);
        var usage = new LlmUsage(9007199254740993L, 0L, null, null, null, null, UsageSource.PROVIDER_REPORTED, null);
        recorder.recordUsageReport("7", context, invocation, "model", 1, usage, Map.of());
        var capture = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insertUsageReport(capture.capture());
        var event = capture.getValue();
        assertEquals(9007199254740993L, event.getPromptTokens());
        assertEquals(0L, event.getCachedInputTokens());
        assertNull(event.getCompletionTokens());
        assertNull(event.getTotalAmountCents());
        assertEquals("session", event.getSessionId());
        assertEquals("root", event.getRootInputId());
        assertEquals("usage_report", event.getUsageEventKind());
        assertEquals("PROVIDER_REPORTED", event.getUsageSource());
    }

    @Test void identicalReplayIsAcceptedAndDifferentCacheReportConflicts() {
        when(mapper.insertUsageReport(any())).thenReturn(1);
        var usage = new LlmUsage(100L, 80L, null, 10L, null, 110L, UsageSource.PROVIDER_REPORTED, null);
        recorder.recordUsageReport("7", context, invocation, "model", 1, usage, Map.of());
        var capture = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insertUsageReport(capture.capture());
        when(mapper.insertUsageReport(any())).thenReturn(0);
        when(mapper.selectOne(any())).thenReturn(capture.getValue());
        assertDoesNotThrow(() -> recorder.recordUsageReport("7", context, invocation, "model", 1, usage, Map.of()));
        var changed = new LlmUsage(100L, 79L, null, 10L, null, 110L, UsageSource.PROVIDER_REPORTED, null);
        assertThrows(IllegalStateException.class,
                () -> recorder.recordUsageReport("7", context, invocation, "model", 1, changed, Map.of()));
    }

    @Test void unchangedObservationsDoNotInsertOrTurnUnknownIntoZero() {
        var effective = new CostEventDO();
        effective.setReportRevision(3L); effective.setPromptTokens(100L); effective.setCompletionTokens(10L);
        effective.setCachedInputTokens(80L);
        when(mapper.effectiveUsageReport(7L, "attempt")).thenReturn(effective);
        recorder.recordUsageReport("7", context, invocation, "model", 4,
                new LlmUsage(100L, 80L, null, null, null, null, UsageSource.PROVIDER_REPORTED, null), Map.of());
        recorder.recordUsageReport("7", context, invocation, "model", 5, LlmUsage.empty(), Map.of());
        recorder.recordUsageReport("7", context, invocation, "model", 6,
                new LlmUsage(100L, 80L, null, 10L, null, 110L, UsageSource.PROVIDER_REPORTED, null), Map.of());
        verify(mapper, never()).insertUsageReport(any());

        when(mapper.insertUsageReport(any())).thenReturn(1);
        recorder.recordUsageReport("7", context, invocation, "model", 7,
                new LlmUsage(null, null, 0L, null, null, null, UsageSource.PROVIDER_REPORTED, null), Map.of());
        var capture = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insertUsageReport(capture.capture());
        assertEquals(0L, capture.getValue().getCacheWriteInputTokens());
        assertNull(capture.getValue().getPromptTokens());
    }

    @Test void firstUnknownAndActualProviderCorrectionRemainFacts() {
        when(mapper.insertUsageReport(any())).thenReturn(1);
        recorder.recordUsageReport("7", context, invocation, "model", 0, LlmUsage.empty(), Map.of());
        var effective = new CostEventDO(); effective.setReportRevision(9L); effective.setPromptTokens(100L);
        when(mapper.effectiveUsageReport(7L, "attempt")).thenReturn(effective);
        recorder.recordUsageReport("7", context, invocation, "model", 10,
                new LlmUsage(101L, null, null, 0L, null, 101L, UsageSource.PROVIDER_REPORTED, null), Map.of());
        var capture = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper, times(2)).insertUsageReport(capture.capture());
        assertEquals("UNKNOWN", capture.getAllValues().getFirst().getUsageSource());
        assertNull(capture.getAllValues().getFirst().getPromptTokens());
        assertEquals(10L, capture.getValue().getReportRevision());
        assertEquals(101L, capture.getValue().getPromptTokens());
        assertEquals(0L, capture.getValue().getCompletionTokens());
    }

    @Test void measuredOwnsCompleteTerminalSnapshotWithoutInventingProviderRevision() {
        var effective = new CostEventDO(); effective.setReportRevision(9L); effective.setPromptTokens(100L);
        when(mapper.effectiveUsageReport(7L, "attempt")).thenReturn(effective);
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        try (var meter = MeterScope.open(context);
                var invocationScope = com.skillforge.core.billing.invocation.ExternalInvocationScope.open(invocation)) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                    new LlmUsage(101L, null, null, 0L, null, 101L, UsageSource.PROVIDER_REPORTED, null), 1, Map.of());
        }
        var capture = ArgumentCaptor.forClass(CostEventDO.class); verify(mapper).insert(capture.capture());
        assertNull(capture.getValue().getReportRevision()); assertEquals(9L, capture.getValue().getUsageSnapshotRevision());
        assertEquals(101L, capture.getValue().getPromptTokens()); assertEquals(0L, capture.getValue().getCompletionTokens());
        verify(mapper, never()).insertUsageReport(any());
    }

    @Test void failedAttemptPricesMergedProviderFieldsAtOriginalDispatchTime() {
        var pricing = mock(com.skillforge.core.billing.pricing.PriceCalculator.class);
        var recorder = new CostRecorder(mapper, pricing);
        var ref = new com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef("81", 3);
        var started = java.time.Instant.parse("2026-01-01T12:00:00Z");
        var report = new CostEventDO(); report.setReportRevision(4L); report.setPromptTokens(100L);
        report.setCachedInputTokens(0L); report.setCompletionTokens(10L);
        when(mapper.effectiveProviderReports(7L, "attempt")).thenReturn(report);
        when(mapper.effectiveUsageReport(7L, "attempt")).thenReturn(report);
        when(mapper.modelAttemptStartedAt(7L, "attempt")).thenReturn(started);
        when(pricing.calculate(eq(ref), eq(7L), eq(CostType.LLM_CHAT), eq("provider"), eq("model"), any(), eq(1), eq(started)))
                .thenReturn(new com.skillforge.core.billing.pricing.PriceCalculator.Amounts(2L, 1L, 3L, "CNY",
                        java.math.BigDecimal.valueOf(2), java.math.BigDecimal.ONE, java.math.BigDecimal.valueOf(3), "frozen", null));
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        recorder.recordFailedModelUsage("7", context, invocation, "model", ref, Map.of());
        var usage = ArgumentCaptor.forClass(LlmUsage.class);
        verify(pricing).calculate(eq(ref), eq(7L), eq(CostType.LLM_CHAT), eq("provider"), eq("model"), usage.capture(), eq(1), eq(started));
        assertEquals(100L, usage.getValue().promptTokens()); assertEquals(0L, usage.getValue().cachedInputTokens());
        assertNull(usage.getValue().cacheWriteInputTokens()); assertEquals(10L, usage.getValue().completionTokens());
        assertEquals(110L, usage.getValue().totalTokens());
        var event = ArgumentCaptor.forClass(CostEventDO.class); verify(mapper).insert(event.capture());
        assertEquals("attempt:usage", event.getValue().getEventId()); assertEquals(4L, event.getValue().getUsageSnapshotRevision());
        assertEquals("PROVIDER_REPORTED", event.getValue().getUsageSource()); assertEquals(3L, event.getValue().getTotalAmountCents());

    }

    @Test void failedAttemptDoesNotInventUsageAndRetainsReportedZeroAndMissingFields() {
        var pricing = mock(com.skillforge.core.billing.pricing.PriceCalculator.class);
        var recorder = new CostRecorder(mapper, pricing);
        var ref = new com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef("81", 3);
        var empty = new CostEventDO(); empty.setReportRevision(0L);
        when(mapper.effectiveProviderReports(7L, "attempt")).thenReturn(null, empty);
        recorder.recordFailedModelUsage("7", context, invocation, "model", ref, Map.of());
        recorder.recordFailedModelUsage("7", context, invocation, "model", ref, Map.of());
        verifyNoInteractions(pricing); verify(mapper, never()).insert(any(CostEventDO.class));
        var partial = new CostEventDO(); partial.setReportRevision(1L); partial.setPromptTokens(0L);
        when(mapper.effectiveProviderReports(7L, "attempt")).thenReturn(partial);
        when(mapper.effectiveUsageReport(7L, "attempt")).thenReturn(partial);
        when(pricing.calculate(eq(ref), eq(7L), eq(CostType.LLM_CHAT), eq("provider"), eq("model"), any(), eq(1), isNull()))
                .thenReturn(new com.skillforge.core.billing.pricing.PriceCalculator.Amounts(0L, null, null, "CNY",
                        java.math.BigDecimal.ZERO, null, null, "frozen", null));
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        recorder.recordFailedModelUsage("7", context, invocation, "model", ref, Map.of());
        var event = ArgumentCaptor.forClass(CostEventDO.class); verify(mapper).insert(event.capture());
        assertEquals(0L, event.getValue().getPromptTokens()); assertNull(event.getValue().getCompletionTokens());
        assertNull(event.getValue().getTokens()); assertNull(event.getValue().getTotalAmount());
    }

    @Test void failedAttemptRejectsExistingTerminalMeasurement() {
        when(mapper.selectOne(any())).thenReturn(new CostEventDO());
        var ref = new com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef("81", 3);
        assertEquals("model_terminal_usage_conflict", assertThrows(IllegalStateException.class,
                () -> recorder.recordFailedModelUsage("7", context, invocation, "model", ref, Map.of())).getMessage());
        verify(mapper, never()).insert(any(CostEventDO.class));
    }

    @Test void toolAttemptsKeepUnknownQuantityAndActualInvocationIsOneUnpricedCall() {
        var tool=new ExternalInvocationRequest("tool-invocation","tool-attempt",ExternalOperationType.TOOL_CALL,
                "mcp","search","tool-call",invocation.context(),Map.of());
        when(mapper.insertUsageReport(any())).thenReturn(1);
        try(var meter=MeterScope.open(context)) { recorder.recordAuxiliaryStarted("7",tool); }
        var started=ArgumentCaptor.forClass(CostEventDO.class);verify(mapper).insertUsageReport(started.capture());
        assertEquals("tool.call",started.getValue().getEventType());assertEquals("call",started.getValue().getMeasurementUnit());
        assertNull(started.getValue().getQuantity());assertNull(started.getValue().getTotalAmount());
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        try(var attempt=com.skillforge.core.billing.invocation.ExternalInvocationScope.open(tool)) {
            recorder.recordExternal("7",CostType.TOOL_CALL,"mcp","search",context,Map.of());
        }
        var actual=ArgumentCaptor.forClass(CostEventDO.class);verify(mapper).insert(actual.capture());
        assertEquals("tool.call",actual.getValue().getEventType());assertEquals(1,actual.getValue().getQuantity());
        assertEquals("call",actual.getValue().getMeasurementUnit());assertEquals("unpriced",actual.getValue().getAmountStatus());
        assertNull(actual.getValue().getTotalAmount());assertEquals("tool-attempt:usage",actual.getValue().getEventId());
    }
}
