package com.skillforge.core.billing.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.pricing.PriceCalculator.Amounts;
import com.skillforge.core.billing.pricing.ModelPriceSource.Key;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.ModelPriceSource.Price;
import com.skillforge.core.billing.pricing.ModelPriceSource.Snapshot;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 平台模型不可变价格快照的写时计价测试。 */
class PriceCalculatorTest {
    private ModelPriceSource pricing;
    private PriceCalculator calculator;

    @BeforeEach
    void setUp() {
        pricing = mock(ModelPriceSource.class);
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(
                BigDecimal.ONE, new BigDecimal("2"), null, "CNY", null, null, null)));
        calculator = new PriceCalculator(pricing);
    }

    @Test
    void chatCalculatesInputAndOutputFromOneSnapshot() {
        Amounts amounts = calculator.calculate(1L, CostType.LLM_CHAT, "openai_compatible", "deepseek-chat", usage(1_000_000L, 500_000L), null, null);

        assertEquals(1L, amounts.input());
        assertEquals(1L, amounts.output());
        assertEquals(2L, amounts.total());
        assertEquals("pricing-v7", amounts.pricingSnapshotVersion());
    }

    @Test
    void embeddingUsesInputPrice() {
        Amounts amounts = calculator.calculate(1L, CostType.LLM_EMBEDDING, "openai_compatible", "deepseek-chat", usage(2_000_000L, null), null, null);

        assertEquals(2L, amounts.input());
        assertNull(amounts.output());
        assertEquals(2L, amounts.total());
    }

    @Test
    void rerankUsesQuantityPrice() {
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(
                null, null, new BigDecimal("100"), "CNY", null, null, null)));

        Amounts amounts = calculator.calculate(1L, CostType.LLM_RERANK, "openai_compatible", "deepseek-chat", LlmUsage.empty(), 5, null);

        assertEquals(500L, amounts.total());
    }

    @Test void rerankInputTokenPriceUsesReportedPromptInsteadOfDocumentCount() {
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(new BigDecimal("50"), null, null, "CNY", null, null, null)));
        var amount = calculator.calculate(1L, CostType.LLM_RERANK, "openai_compatible", "deepseek-chat", usage(79L, null), 2, null);
        assertEquals(new BigDecimal("0.003950000000"), amount.preciseTotal());
        assertEquals(amount.preciseInput(), amount.preciseTotal());
        assertNull(amount.preciseOutput());
        assertEquals(0L, amount.total());
    }

    @Test void rerankMissingTokensCannotSwitchFrozenTokenPriceToQuantityOrBecomeFree() {
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(new BigDecimal("50"), null, null, "CNY", null, null, null)));
        var unknown = calculator.calculate(1L, CostType.LLM_RERANK, "openai_compatible", "deepseek-chat", LlmUsage.empty(), 2, null);
        assertNull(unknown.preciseTotal());
        assertNull(unknown.total());
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(BigDecimal.ZERO, null, null, "CNY", null, null, null)));
        assertNull(calculator.calculate(1L, CostType.LLM_RERANK, "openai_compatible", "deepseek-chat", LlmUsage.empty(), 2, null).preciseTotal());
    }

    @Test void rerankQuantityContractDoesNotSwitchToTokensAndAmbiguousPricesAreRejected() {
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(null, null, new BigDecimal("2.5"), "CNY", null, null, null)));
        assertEquals(new BigDecimal("5.0"), calculator.calculate(1L, CostType.LLM_RERANK, "openai_compatible", "deepseek-chat", usage(79L, null), 2, null).preciseTotal());
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(new BigDecimal("50"), null, BigDecimal.ZERO, "CNY", null, null, null)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> calculator.calculate(1L, CostType.LLM_RERANK, "openai_compatible", "deepseek-chat", usage(79L, null), 2, null));
    }

    @Test
    void noPriceReturnsUnknownAmountWithAuditableVersion() {
        when(pricing.snapshot(any(), any())).thenReturn(new Snapshot("pricing-empty", Map.of()));

        Amounts amounts = calculator.calculate(1L, CostType.LLM_CHAT, "openai_compatible", "unknown", usage(1_000_000L, 0L), null, null);

        assertNull(amounts.input());
        assertNull(amounts.output());
        assertNull(amounts.total());
        assertEquals("pricing-empty", amounts.pricingSnapshotVersion());
    }

    @Test
    void partialUsageKeepsKnownComponentAndSubCentPrecision() {
        Amounts partial = calculator.calculate(1L, CostType.LLM_CHAT, "openai_compatible", "deepseek-chat", usage(1_000_000L, null), null, null);
        Amounts precise = calculator.calculate(1L, CostType.LLM_CHAT, "openai_compatible", "deepseek-chat", usage(1L, 1L), null, null);

        assertEquals(1L, partial.input());
        assertNull(partial.total());
        assertEquals(new BigDecimal("0.000001000000"), precise.preciseInput());
        assertEquals(new BigDecimal("0.000002000000"), precise.preciseOutput());
        assertEquals(new BigDecimal("0.000003000000"), precise.preciseTotal());
    }

    @Test
    void frozenRevisionUsesItsOnlyPriceDespiteProviderModelAlias() {
        ModelRevisionRef reference = new ModelRevisionRef("81", 3);
        when(pricing.snapshot(reference)).thenReturn(snapshot(new Price(
                new BigDecimal("7"), new BigDecimal("11"), null, "CNY", null, null, null)));

        Amounts amounts = calculator.calculate(reference, 1L, CostType.LLM_CHAT, "provider-response-alias", "model-response-alias", usage(1_000_000L, 1_000_000L), null, null);

        assertEquals(7L, amounts.input());
        assertEquals(11L, amounts.output());
        assertEquals(18L, amounts.total());
        assertEquals("pricing-v7", amounts.pricingSnapshotVersion());
    }

    private static LlmUsage usage(Long input, Long output) {
        return new LlmUsage(input, null, null, output, null, null, UsageSource.PROVIDER_REPORTED, null);
    }

    private Snapshot snapshot(Price price) {
        return new Snapshot("pricing-v7",
                Map.of(new Key("openai_compatible", "deepseek-chat"), price));
    }

    @Test void realToolCallsRequireQuantityPriceAndNeverBecomeFreeWhenPriceIsMissing() {
        when(pricing.snapshot(any(), any())).thenReturn(snapshot(new Price(null,null,new BigDecimal("25.5"),"CNY", null, null, null)));
        assertEquals(new BigDecimal("51.0"),calculator.calculate(1L, CostType.TOOL_CALL, "openai_compatible", "deepseek-chat", LlmUsage.empty(), 2, null).preciseTotal());
        assertNull(calculator.calculate(1L, CostType.TOOL_CALL, "openai_compatible", "deepseek-chat", LlmUsage.empty(), null, null).total());
        when(pricing.snapshot(any(), any())).thenReturn(new Snapshot("no-tool-price",Map.of()));
        var missing=calculator.calculate(1L, CostType.TOOL_CALL, "mcp", "remote-tool", LlmUsage.empty(), 1, null);
        assertNull(missing.total());assertNull(missing.preciseTotal());
    }
}
