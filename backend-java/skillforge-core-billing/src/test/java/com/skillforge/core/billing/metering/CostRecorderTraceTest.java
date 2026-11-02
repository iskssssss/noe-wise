package com.skillforge.core.billing.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.pricing.PriceCalculator.Amounts;
import com.skillforge.core.billing.pricing.TestPriceCalculators;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.shared.tracing.TraceContext;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 用量事件必须保留 input/root/trace 因果元数据。 */
class CostRecorderTraceTest {

    @AfterEach
    void clearMeterScope() {
        MeterScope.clear();
        ExternalInvocationScope.clear();
    }

    @Test
    void recordLlmCopiesTraceContextIntoEventMeta() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        CostRecorder recorder = new CostRecorder(
                mapper, TestPriceCalculators.priced("provider", "model", 1, 1, "CNY"));
        TraceContext trace = new TraceContext("input", "root", "trace", "span", "event", "cause");

        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .requestId("input")
                .traceContext(trace)
                .callPoint("llm.chat")
                .build())) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                    new LlmUsage(10, 5, 15), null, Map.of("step", "planner"));
        }

        ArgumentCaptor<com.skillforge.core.billing.metering.CostEventDO> captor =
                ArgumentCaptor.forClass(com.skillforge.core.billing.metering.CostEventDO.class);
        verify(mapper).insert(captor.capture());
        Map<String, Object> meta = captor.getValue().getMeta();
        assertEquals("input", meta.get("input_id"));
        assertEquals("root", meta.get("root_input_id"));
        assertEquals("trace", meta.get("trace_id"));
        assertEquals("cause", meta.get("caused_by_event_id"));
        assertEquals("planner", meta.get("step"));
    }

    @Test
    void recordLlmWithoutScopeDoesNotCreateBillingFact() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        CostRecorder recorder = new CostRecorder(
                mapper, TestPriceCalculators.unpriced());

        recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                new LlmUsage(1, 1, 2), null, Map.of());

        verify(mapper, never()).insert(any(CostEventDO.class));
    }

    @Test
    void duplicateEventIsNotInsertedAgain() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        CostRecorder recorder = new CostRecorder(
                mapper, TestPriceCalculators.unpriced());
        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .requestId("input")
                .eventId("stable-event")
                .callPoint("llm.chat")
                .build())) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                    new LlmUsage(1, 1, 2), null, Map.of());
        }

        verify(mapper, never()).insert(any(CostEventDO.class));
    }

    @Test
    void recordLlmLinksUsageToTheActiveExternalAttempt() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        CostRecorder recorder = new CostRecorder(
                mapper, TestPriceCalculators.priced("provider", "model", 1, 1, "CNY"));
        TraceContext trace = new TraceContext("input", "root", "trace", "span", "event", "cause");
        ExternalInvocationRequest request = new ExternalInvocationRequest(
                "invocation-1", "attempt-1", ExternalOperationType.MODEL_CHAT,
                "provider", "model", "action-1", trace, Map.of());

        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .requestId("input")
                .eventId("action-1")
                .traceContext(trace)
                .build());
             ExternalInvocationScope invocationScope = ExternalInvocationScope.open(request)) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                    new LlmUsage(10, 5, 15), null, Map.of());
        }

        ArgumentCaptor<CostEventDO> captor = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insert(captor.capture());
        CostEventDO event = captor.getValue();
        assertEquals("attempt-1:usage", event.getEventId());
        assertEquals("invocation-1", event.getInvocationId());
        assertEquals("attempt-1", event.getAttemptId());
        assertNull(ExternalInvocationScope.current());
    }

    @Test
    void recordLlmForwardsTheFrozenModelRevisionToPricing() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        PriceCalculator calculator = mock(PriceCalculator.class);
        ModelRevisionRef pricingModel = new ModelRevisionRef("81", 3);
        var reported = new LlmUsage(10L, 2L, 1L, 5L, 1L, 15L, UsageSource.PROVIDER_REPORTED, null);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(calculator.calculate(pricingModel, 7L, CostType.LLM_CHAT,
                "provider", "returned-model-alias", reported, 1, null)).thenReturn(
                        new Amounts(1L, 2L, 3L, "CNY", BigDecimal.ONE,
                                BigDecimal.valueOf(2), BigDecimal.valueOf(3),
                                "model-pricing-exact", null));
        CostRecorder recorder = new CostRecorder(mapper, calculator);

        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .eventId("attempt-1:usage")
                .build())) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "returned-model-alias",
                    reported, 1, pricingModel, Map.of());
        }

        verify(calculator).calculate(pricingModel, 7L, CostType.LLM_CHAT,
                "provider", "returned-model-alias", reported, 1, null);
        ArgumentCaptor<CostEventDO> event = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insert(event.capture());
        assertEquals(2L, event.getValue().getCachedInputTokens());
        assertEquals(1L, event.getValue().getCacheWriteInputTokens());
        assertNull(event.getValue().getPricingModelId());
        assertNull(event.getValue().getPricingModelRevision());
        assertEquals("model-pricing-exact@fx-cny-identity-v1", event.getValue().getPricingSnapshotVersion());
    }

    @Test
    void recordRequestCreatesRootUsageFact() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        CostRecorder recorder = new CostRecorder(
                mapper, TestPriceCalculators.unpriced());

        recorder.recordRequest("7", "input:request", "input",
                CostContext.builder().sessionId("session").traceContext(
                        new TraceContext("input", "root", "trace", "span", "event", null)).build(),
                null);

        ArgumentCaptor<com.skillforge.core.billing.metering.CostEventDO> captor =
                ArgumentCaptor.forClass(com.skillforge.core.billing.metering.CostEventDO.class);
        verify(mapper).insert(captor.capture());
        assertEquals("input", captor.getValue().getRequestId());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getTokens());
        assertEquals("not_applicable", captor.getValue().getAmountStatus());
        assertEquals("input", captor.getValue().getMeta().get("input_id"));
        assertEquals("root", captor.getValue().getMeta().get("root_input_id"));
    }

    @Test
    void duplicateRequestRootIsIgnored() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        CostRecorder recorder = new CostRecorder(
                mapper, TestPriceCalculators.unpriced());

        recorder.recordRequest("7", "input:request", "input", CostContext.builder().build(), null);

        verify(mapper, never()).insert(any(CostEventDO.class));
    }
}
