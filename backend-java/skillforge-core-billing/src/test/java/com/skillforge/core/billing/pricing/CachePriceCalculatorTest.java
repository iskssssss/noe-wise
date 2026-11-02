package com.skillforge.core.billing.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.pricing.CacheCostPrice.Mode;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 缓存子集属于总输入，单价未知与计量未知分别保留，推理Token不重复收费。 */
class CachePriceCalculatorTest {
    private static final ModelRevisionRef MODEL = new ModelRevisionRef("81", 3);

    @Test void readModeChargesOnlyTheInclusiveInputRemainderAndCacheReadBucket() {
        var calculator = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ, decimal("20"), null)));
        var amount = calculate(calculator, usage(100L, 60L, 10L, 10L));
        assertThat(amount.preciseInput()).isEqualByComparingTo("0.0092");
        assertThat(amount.preciseOutput()).isEqualByComparingTo("0.008");
        assertThat(amount.preciseTotal()).isEqualByComparingTo("0.0172");
        assertThat(amount.pricingSnapshotVersion()).isEqualTo("cache-frozen-revision-3");
    }

    @Test void readWriteModeUsesThreeDisjointBucketsAndTheFullWriteRate() {
        var calculator = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, decimal("20"), decimal("250"))));
        var amount = calculate(calculator, usage(100L, 60L, 10L, 10L));
        assertThat(amount.preciseInput()).isEqualByComparingTo("0.0097");
        assertThat(amount.preciseOutput()).isEqualByComparingTo("0.008");
        assertThat(amount.preciseTotal()).isEqualByComparingTo("0.0177");
    }

    @Test void absentCacheConfigurationKeepsTheTotalInputUniformPrice() {
        var amount = calculate(calculator(price("200", "800", null)), usage(100L, 60L, 10L, 10L));
        assertThat(amount.preciseInput()).isEqualByComparingTo("0.02");
        assertThat(amount.preciseTotal()).isEqualByComparingTo("0.028");
    }

    @Test void missingRequiredReportsNeverBecomeZeroAndPreserveTheKnownOutput() {
        var read = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ, decimal("20"), null)));
        var readWrite = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, decimal("20"), decimal("250"))));
        for (LlmUsage usage : List.of(usage(null, 0L, 0L, 10L), usage(0L, null, 0L, 10L), usage(100L, null, 10L, 10L))) {
            assertUnknownInputWithKnownOutput(calculate(read, usage));
            assertUnknownInputWithKnownOutput(calculate(readWrite, usage));
        }
        assertUnknownInputWithKnownOutput(calculate(readWrite, usage(100L, 60L, null, 10L)));
    }

    @Test void negativeOrOverlappingInputSubsetsAreUnknownWithoutLongOverflow() {
        var calculator = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, decimal("20"), decimal("250"))));
        for (LlmUsage usage : List.of(usage(-1L, 0L, 0L, 10L), usage(100L, -1L, 0L, 10L),
                usage(100L, 0L, -1L, 10L), usage(100L, 101L, 0L, 10L), usage(100L, 60L, 41L, 10L),
                usage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 10L)))
            assertUnknownInputWithKnownOutput(calculate(calculator, usage));
    }

    @Test void eachExplicitZeroBucketCostsZeroEvenWhenItsOwnRateIsUnknown() {
        var allZero = calculate(calculator(price(null, "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, null, null))),
                usage(0L, 0L, 0L, 10L));
        assertThat(allZero.preciseInput()).isZero(); assertThat(allZero.preciseTotal()).isEqualByComparingTo("0.008");
        var allCached = calculate(calculator(price(null, "800", new CacheCostPrice(Mode.CACHE_READ, decimal("20"), null))),
                usage(100L, 100L, null, 10L));
        assertThat(allCached.preciseInput()).isEqualByComparingTo("0.002");
        var noCache = calculate(calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, null, null))),
                usage(100L, 0L, 0L, 10L));
        assertThat(noCache.preciseInput()).isEqualByComparingTo("0.02");
        var zeroWithUnknownRates = calculate(calculator(price(null, null, new CacheCostPrice(Mode.CACHE_READ_WRITE, null, null))),
                usage(0L, 0L, 0L, 0L));
        assertThat(zeroWithUnknownRates.preciseTotal()).isZero();
        assertThat(calculate(calculator(price(null, null, new CacheCostPrice(Mode.CACHE_READ_WRITE, null, null))),
                usage(0L, 0L, 0L, null)).preciseTotal()).isNull();
    }

    @Test void nonzeroUnknownRateDoesNotFallBackToTheOrdinaryInputRate() {
        var unknownRead = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ, null, null)));
        assertUnknownInputWithKnownOutput(calculate(unknownRead, usage(100L, 1L, null, 10L)));
        var unknownWrite = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, decimal("20"), null)));
        assertUnknownInputWithKnownOutput(calculate(unknownWrite, usage(100L, 0L, 1L, 10L)));
        var unknownOrdinary = calculator(price(null, "800", new CacheCostPrice(Mode.CACHE_READ, decimal("20"), null)));
        assertUnknownInputWithKnownOutput(calculate(unknownOrdinary, usage(100L, 99L, null, 10L)));
    }

    @Test void subCentBucketPrecisionIsNotRoundedToIntegerCentsBeforeSumming() {
        var amount = calculate(calculator(price("0.000001", "0.000001",
                new CacheCostPrice(Mode.CACHE_READ_WRITE, decimal("0.000001"), decimal("0.000001")))), usage(3L, 1L, 1L, 1L));
        assertThat(amount.preciseInput()).isEqualTo(decimal("0.000000000003"));
        assertThat(amount.preciseTotal()).isEqualTo(decimal("0.000000000004")); assertThat(amount.total()).isZero();
    }

    @Test void oversizedCacheAmountLeavesTheRepresentableOtherDirectionKnown() {
        var hugeInput = calculator(price("9223372036854775807", "800", new CacheCostPrice(Mode.CACHE_READ, null, null)));
        assertUnknownInputWithKnownOutput(calculate(hugeInput, usage(Long.MAX_VALUE, 0L, null, 10L)));
        var hugeOutput = calculate(calculator(price("200", "9223372036854775807", new CacheCostPrice(Mode.CACHE_READ, null, null))),
                usage(100L, 0L, null, Long.MAX_VALUE));
        assertThat(hugeOutput.preciseInput()).isEqualByComparingTo("0.02");
        assertThat(hugeOutput.preciseOutput()).isNull(); assertThat(hugeOutput.preciseTotal()).isNull();
        var hugeTotal = calculate(calculator(price("900000000000", "900000000000", new CacheCostPrice(Mode.CACHE_READ, null, null))),
                usage(1_000_000L, 0L, null, 1_000_000L));
        assertThat(hugeTotal.preciseInput()).isEqualByComparingTo("900000000000");
        assertThat(hugeTotal.preciseOutput()).isEqualByComparingTo("900000000000");
        assertThat(hugeTotal.preciseTotal()).isNull();
    }

    @Test void cacheConfigurationDoesNotChangeEmbeddingOrRerankArithmetic() {
        var calculator = calculator(price("200", "800", new CacheCostPrice(Mode.CACHE_READ_WRITE, null, null)));
        var usage = usage(100L, null, null, null);
        assertThat(calculator.calculate(MODEL, 7L, CostType.LLM_EMBEDDING, "alias", "alias", usage, null, null).preciseTotal())
                .isEqualByComparingTo("0.02");
        assertThat(calculator.calculate(MODEL, 7L, CostType.LLM_RERANK, "alias", "alias", usage, 2, null).preciseTotal())
                .isEqualByComparingTo("0.02");
    }

    @Test void optionalPricesKeepNullAndExplicitZeroWhileRejectingInvalidDecimalValues() {
        assertThat(new CacheCostPrice(Mode.CACHE_READ, null, null).readPricePerMillionCents()).isNull();
        assertThat(new CacheCostPrice(Mode.CACHE_READ, decimal("0.000000"), null).readPricePerMillionCents()).isEqualTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> new CacheCostPrice(Mode.CACHE_READ, BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessage("cache_write_price_not_applicable");
        for (String value : List.of("-1", "0.0000001", "9223372036854775808"))
            assertThatThrownBy(() -> new CacheCostPrice(Mode.CACHE_READ, decimal(value), null)).hasMessage("cache_price_invalid");
    }

    private static void assertUnknownInputWithKnownOutput(PriceCalculator.Amounts amount) {
        assertThat(amount.preciseInput()).isNull(); assertThat(amount.input()).isNull();
        assertThat(amount.preciseOutput()).isEqualByComparingTo("0.008");
        assertThat(amount.preciseTotal()).isNull(); assertThat(amount.total()).isNull();
    }

    private static LlmUsage usage(Long input, Long read, Long write, Long output) {
        return new LlmUsage(input, read, write, output, output == null ? null : Math.min(output, 7L), null, UsageSource.PROVIDER_REPORTED, null);
    }

    private static ModelPriceSource.Price price(String input, String output, CacheCostPrice cache) {
        return new ModelPriceSource.Price(decimal(input), decimal(output), null, "CNY", null, cache, null);
    }

    private static PriceCalculator.Amounts calculate(PriceCalculator calculator, LlmUsage usage) {
        return calculator.calculate(MODEL, 7L, CostType.LLM_CHAT, "returned-provider-alias", "returned-model-alias", usage, 1, null);
    }

    private static PriceCalculator calculator(ModelPriceSource.Price price) {
        var source = mock(ModelPriceSource.class);
        when(source.snapshot()).thenThrow(new AssertionError("cache cost must use the original model revision"));
        when(source.snapshot(MODEL)).thenReturn(new ModelPriceSource.Snapshot("cache-frozen-revision-3", Map.of(
                new ModelPriceSource.Key("original-provider", "original-model"), price)));
        return new PriceCalculator(source);
    }

    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
}
