package com.skillforge.core.billing.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.shared.tracing.TraceContext;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 实测 Rerank Token、冻结输入单价和原 Attempt 必须一起进入不可变用量事实。 */
class RerankUsageMeteringTest {
    @Test void reportedInputTokensProducePreciseCostOnOriginalAttempt() {
        var event = record(new LlmUsage(79L, null, null, null, null, 79L, UsageSource.PROVIDER_REPORTED, null));
        assertThat(event.getPromptTokens()).isEqualTo(79L);
        assertThat(event.getTokens()).isEqualTo(79L);
        assertThat(event.getCompletionTokens()).isNull();
        assertThat(event.getQuantity()).isEqualTo(2);
        assertThat(event.getTotalAmount()).isEqualTo(new BigDecimal("0.003950000000"));
        assertThat(event.getAmountStatus()).isEqualTo("priced");
        assertThat(event.getUsageSource()).isEqualTo("PROVIDER_REPORTED");
        assertThat(event.getAttemptId()).isEqualTo("q07-rerank:attempt:1");
        assertThat(event.getEventId()).isEqualTo("q07-rerank:attempt:1:usage");
        assertThat(event.getPricingSnapshotVersion()).isEqualTo("rerank-price-v3@fx-cny-identity-v1");
    }

    @Test void missingReportKeepsNullTokensAndUnpricedCostDespiteKnownDocumentCount() {
        var event = record(LlmUsage.empty());
        assertThat(event.getTokens()).isNull();
        assertThat(event.getPromptTokens()).isNull();
        assertThat(event.getTotalAmount()).isNull();
        assertThat(event.getTotalAmountCents()).isNull();
        assertThat(event.getAmountStatus()).isEqualTo("unpriced");
        assertThat(event.getUsageSource()).isEqualTo("UNKNOWN");
        assertThat(event.getQuantity()).isEqualTo(2);
    }

    private CostEventDO record(LlmUsage usage) {
        var mapper = mock(CostEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        var prices = mock(ModelPriceSource.class);
        var revision = new ModelRevisionRef("82", 3);
        when(prices.snapshot(revision)).thenReturn(new ModelPriceSource.Snapshot("rerank-price-v3", Map.of(
                new ModelPriceSource.Key("aliyun-bailian", "qwen3.7-text-rerank"),
                new ModelPriceSource.Price(new BigDecimal("50"), null, null, "CNY", null, null, null))));
        var recorder = new CostRecorder(mapper, new PriceCalculator(prices));
        var request = new ExternalInvocationRequest("q07-rerank", "q07-rerank:attempt:1",
                ExternalOperationType.MODEL_RERANK, "aliyun-bailian", "qwen3.7-text-rerank", "q07-rerank",
                new TraceContext("input", "root", "trace", "span", "event", null), Map.of());
        try (var scope = MeterScope.open(CostContext.builder().eventId("q07-rerank").callPoint("knowledge.rerank").build());
                var attempt = ExternalInvocationScope.open(request)) {
            recorder.recordLlm("7", CostType.LLM_RERANK, "aliyun-bailian", "qwen3.7-text-rerank",
                    usage, 2, revision, null);
        }
        var capture = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insert(capture.capture());
        return capture.getValue();
    }
}
