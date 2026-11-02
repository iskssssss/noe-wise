package com.skillforge.core.billing.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.pricing.CacheCostPrice;
import com.skillforge.core.billing.pricing.ExchangeRateProvider;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.billing.pricing.TimePricingFixtures;
import com.skillforge.core.shared.tracing.TraceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 读取原Attempt持久时间决定峰谷及汇率；完成时刻不替代缺失的派发依据。 */
class TimeUsageMeteringTest {
    private static final ModelRevisionRef MODEL = new ModelRevisionRef("81", 3);
    private static final Instant STARTED = Instant.parse("2026-03-25T04:00:00Z");

    @Test void originalPersistedAttemptTimeControlsDiscountAndFxDespiteLaterCompletion() {
        var fixture = new Fixture(TimePricingFixtures.policy());
        when(fixture.mapper.modelAttemptStartedAt(7L, "attempt-time-1")).thenReturn(STARTED);
        var event = fixture.record(true);
        verify(fixture.mapper).modelAttemptStartedAt(7L, "attempt-time-1");
        verify(fixture.fx).toCny("USD", STARTED);
        assertThat(event.getTotalAmount()).isEqualByComparingTo("0.001475");
        assertThat(event.getAmountStatus()).isEqualTo("priced");
        assertThat(event.getPricingSnapshotVersion()).isEqualTo(
                "time-price-revision-3@time-" + TimePricingFixtures.policy().snapshotKey() + "-OFF_PEAK-2026-03-25T04:00:00Z@fx-time-fx");
        assertThat(event.getAttemptId()).isEqualTo("attempt-time-1");
        assertThat(event.getPricingModelId()).isNull(); assertThat(event.getPricingModelRevision()).isNull();
    }

    @Test void missingPersistedStartDoesNotFallBackToNowOrReadAnFxSnapshot() {
        var fixture = new Fixture(TimePricingFixtures.policy());
        var event = fixture.record(true);
        verify(fixture.mapper).modelAttemptStartedAt(7L, "attempt-time-1"); verifyNoInteractions(fixture.fx);
        assertThat(event.getTotalAmount()).isNull(); assertThat(event.getInputAmount()).isNull(); assertThat(event.getOutputAmount()).isNull();
        assertThat(event.getExchangeRateToCny()).isNull();
        assertThat(event.getPricingSnapshotVersion()).isEqualTo("time-price-revision-3@time-" + TimePricingFixtures.policy().snapshotKey() + "-UNKNOWN-null");
    }

    @Test void missingInvocationScopeNeverInventsAStartFromTheMeterContext() {
        var fixture = new Fixture(TimePricingFixtures.policy());
        var event = fixture.record(false);
        verify(fixture.mapper, never()).modelAttemptStartedAt(any(), anyString()); verifyNoInteractions(fixture.fx);
        assertThat(event.getTotalAmount()).isNull(); assertThat(event.getPricingSnapshotVersion()).endsWith("-UNKNOWN-null");
    }

    @Test void untimedPriceKeepsItsPriorCompletionTimeFxBehavior() {
        var fixture = new Fixture(null);
        when(fixture.mapper.modelAttemptStartedAt(7L, "attempt-time-1")).thenReturn(STARTED);
        Instant before = Instant.now(); var event = fixture.record(true); Instant after = Instant.now();
        var time = ArgumentCaptor.forClass(Instant.class); verify(fixture.fx).toCny(org.mockito.ArgumentMatchers.eq("USD"), time.capture());
        assertThat(time.getValue()).isBetween(before, after); assertThat(time.getValue()).isNotEqualTo(STARTED);
        assertThat(event.getTotalAmount()).isEqualByComparingTo("0.00295");
        assertThat(event.getPricingSnapshotVersion()).isEqualTo("time-price-revision-3@fx-time-fx");
    }

    private static final class Fixture {
        final CostEventMapper mapper = mock(CostEventMapper.class);
        final ExchangeRateProvider fx = mock(ExchangeRateProvider.class);
        final CostRecorder recorder;

        Fixture(TimePricingPolicy policy) {
            var source = mock(ModelPriceSource.class);
            var cache = new CacheCostPrice(CacheCostPrice.Mode.CACHE_READ, new BigDecimal("5"), null);
            when(source.snapshot(MODEL)).thenReturn(new ModelPriceSource.Snapshot("time-price-revision-3", Map.of(
                    new ModelPriceSource.Key("provider", "model"),
                    new ModelPriceSource.Price(new BigDecimal("25"), new BigDecimal("125"), null, "USD", null, cache, policy))));
            when(fx.toCny(anyString(), any())).thenAnswer(call -> Optional.of(new ExchangeRateProvider.Rate(
                    new BigDecimal("7.25"), call.getArgument(1), "time-fx")));
            when(mapper.selectCount(any())).thenReturn(0L);
            recorder = new CostRecorder(mapper, new PriceCalculator(source), fx);
        }

        CostEventDO record(boolean withAttempt) {
            var trace = new TraceContext("input-time-1", "root-time-1", "trace-time-1", "span-time-1", "event-time-1", null);
            var invocation = new ExternalInvocationRequest("invocation-time-1", "attempt-time-1", ExternalOperationType.MODEL_CHAT,
                    "provider", "model", "operation-time-1", trace, Map.of());
            try (var meter = MeterScope.openIsolated(CostContext.builder().eventId("event-time-1").requestId("input-time-1")
                        .callPoint("time.cost.test").traceContext(trace).build());
                    var attempt = withAttempt ? ExternalInvocationScope.open(invocation) : null) {
                recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                        new LlmUsage(100L, 40L, null, 10L, 3L, 110L, UsageSource.PROVIDER_REPORTED, null), 1, MODEL, Map.of());
            }
            var saved = ArgumentCaptor.forClass(CostEventDO.class); verify(mapper).insert(saved.capture()); return saved.getValue();
        }
    }
}
