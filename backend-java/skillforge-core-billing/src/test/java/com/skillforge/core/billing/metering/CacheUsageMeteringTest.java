package com.skillforge.core.billing.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.pricing.CacheCostPrice;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.PriceCalculator;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 正式CostRecorder把真实缓存子集交给计价器；未知字段不因保存或零整数投影丢失。 */
class CacheUsageMeteringTest {
    @Test void knownCacheReadWriteBucketsPersistTheirPreciseCombinedInputCost() {
        var event = record(new LlmUsage(100L, 60L, 10L, 10L, 7L, 110L, UsageSource.PROVIDER_REPORTED, null));
        assertThat(event.getPromptTokens()).isEqualTo(100L); assertThat(event.getCachedInputTokens()).isEqualTo(60L);
        assertThat(event.getCacheWriteInputTokens()).isEqualTo(10L); assertThat(event.getCompletionTokens()).isEqualTo(10L);
        assertThat(event.getInputAmount()).isEqualByComparingTo("0.0097");
        assertThat(event.getOutputAmount()).isEqualByComparingTo("0.008");
        assertThat(event.getTotalAmount()).isEqualByComparingTo("0.0177"); assertThat(event.getTotalAmountCents()).isZero();
        assertThat(event.getAmountStatus()).isEqualTo("priced");
        assertThat(event.getPricingSnapshotVersion()).isEqualTo("frozen-cache-price@fx-cny-identity-v1");
        assertThat(event.getPricingModelId()).isNull(); assertThat(event.getPricingModelRevision()).isNull();
    }

    @Test void missingCacheReadReportPreservesKnownOutputButNeverCreatesZeroTotal() {
        var event = record(new LlmUsage(100L, null, 10L, 10L, null, 110L, UsageSource.PROVIDER_REPORTED, null));
        assertThat(event.getCachedInputTokens()).isNull(); assertThat(event.getCacheWriteInputTokens()).isEqualTo(10L);
        assertThat(event.getInputAmount()).isNull(); assertThat(event.getOutputAmount()).isEqualByComparingTo("0.008");
        assertThat(event.getTotalAmount()).isNull(); assertThat(event.getTotalAmountCents()).isNull();
        assertThat(event.getAmountStatus()).isEqualTo("unpriced");
    }

    private static CostEventDO record(LlmUsage usage) {
        var mapper = mock(CostEventMapper.class); when(mapper.selectCount(any())).thenReturn(0L);
        var source = mock(ModelPriceSource.class); var revision = new ModelRevisionRef("81", 3);
        var cache = new CacheCostPrice(CacheCostPrice.Mode.CACHE_READ_WRITE, new BigDecimal("20"), new BigDecimal("250"));
        when(source.snapshot(revision)).thenReturn(new ModelPriceSource.Snapshot("frozen-cache-price", Map.of(
                new ModelPriceSource.Key("provider", "model"),
                new ModelPriceSource.Price(new BigDecimal("200"), new BigDecimal("800"), null, "CNY", null, cache, null))));
        var recorder = new CostRecorder(mapper, new PriceCalculator(source));
        try (var scope = MeterScope.openIsolated(CostContext.builder().eventId("cache-cost:usage").callPoint("cache.cost.test").build())) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model", usage, 1, revision, Map.of());
        }
        var saved = ArgumentCaptor.forClass(CostEventDO.class); verify(mapper).insert(saved.capture()); return saved.getValue();
    }
}
