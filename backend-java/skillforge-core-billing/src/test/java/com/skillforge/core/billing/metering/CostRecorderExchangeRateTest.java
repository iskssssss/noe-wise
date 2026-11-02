package com.skillforge.core.billing.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.pricing.ExchangeRateProvider;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.pricing.TestPriceCalculators;
import com.skillforge.core.billing.persistence.CostEventMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CostRecorderExchangeRateTest {

    @AfterEach
    void clearScope() {
        MeterScope.clear();
    }

    @Test
    void cnyUsesAnAuditableIdentitySnapshot() {
        CostEventMapper mapper = mapper();
        CostRecorder recorder = new CostRecorder(mapper, calculator("CNY"));

        record(recorder);

        CostEventDO event = capture(mapper);
        assertEquals(BigDecimal.ONE, event.getExchangeRateToCny());
        assertEquals("priced", event.getAmountStatus());
        assertEquals("model-pricing-test@fx-cny-identity-v1", event.getPricingSnapshotVersion());
    }

    @Test
    void missingForeignExchangeProviderNeverPretendsCurrencyIsCny() {
        CostEventMapper mapper = mapper();
        CostRecorder recorder = new CostRecorder(mapper, calculator("USD"));

        record(recorder);

        CostEventDO event = capture(mapper);
        assertNull(event.getExchangeRateToCny());
        assertEquals("unresolved", event.getAmountStatus());
        assertEquals("USD", event.getCurrency());
    }

    @Test
    void providerSnapshotIsStoredWithoutRecomputingHistoricalAmount() {
        CostEventMapper mapper = mapper();
        ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
        when(provider.toCny(any(), any())).thenReturn(Optional.of(
                new ExchangeRateProvider.Rate(new BigDecimal("7.123456789012"),
                        Instant.parse("2026-08-25T00:00:00Z"), "fx-42")));
        CostRecorder recorder = new CostRecorder(mapper, calculator("USD"), provider);

        record(recorder);

        CostEventDO event = capture(mapper);
        assertEquals(new BigDecimal("7.123456789012"), event.getExchangeRateToCny());
        assertEquals("priced", event.getAmountStatus());
        assertEquals("model-pricing-test@fx-fx-42", event.getPricingSnapshotVersion());
        assertEquals(0, new BigDecimal("1.5").compareTo(event.getTotalAmount()));
    }

    @Test
    void invalidProviderSnapshotIsRejectedBeforeItCanReachTheLedger() {
        assertThrows(IllegalArgumentException.class, () -> new ExchangeRateProvider.Rate(
                BigDecimal.ZERO, Instant.now(), "fx-1"));
        assertThrows(IllegalArgumentException.class, () -> new ExchangeRateProvider.Rate(
                BigDecimal.ONE, Instant.now(), ""));
    }

    private CostEventMapper mapper() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        return mapper;
    }

    private PriceCalculator calculator(String currency) {
        return TestPriceCalculators.priced(
                "provider", "model", 1_000_000L, 500_000L, currency);
    }

    private void record(CostRecorder recorder) {
        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .eventId("fx-event")
                .requestId("request-1")
                .callPoint("llm.chat")
                .build())) {
            recorder.recordLlm("7", CostType.LLM_CHAT, "provider", "model",
                    new LlmUsage(1, 1, 2), null, Map.of());
        }
    }

    private CostEventDO capture(CostEventMapper mapper) {
        ArgumentCaptor<CostEventDO> captor = ArgumentCaptor.forClass(CostEventDO.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
