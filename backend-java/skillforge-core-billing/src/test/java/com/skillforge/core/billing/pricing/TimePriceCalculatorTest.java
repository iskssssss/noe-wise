package com.skillforge.core.billing.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 原Attempt时间决定折扣，完整输入先合桶再舍入，不因完成时刻或当前价改变历史。 */
class TimePriceCalculatorTest {
    private static final ModelRevisionRef MODEL = new ModelRevisionRef("81", 3);
    private static final Instant PEAK = Instant.parse("2026-03-25T01:00:00Z");
    private static final Instant OFF = Instant.parse("2026-03-25T04:00:00Z");

    @Test void peakKeepsConfiguredPricesAndOffPeakHalvesTheWholeInputAndOutputAmounts() {
        var calculator = calculator(price("25", "125", "5", TimePricingFixtures.policy()));
        var usage = usage(100L, 40L, 10L);
        var peak = calculate(calculator, usage, PEAK); var off = calculate(calculator, usage, OFF);
        assertThat(peak.preciseInput()).isEqualByComparingTo("0.0017");
        assertThat(peak.preciseOutput()).isEqualByComparingTo("0.00125");
        assertThat(peak.preciseTotal()).isEqualByComparingTo("0.00295");
        assertThat(off.preciseInput()).isEqualByComparingTo("0.00085");
        assertThat(off.preciseOutput()).isEqualByComparingTo("0.000625");
        assertThat(off.preciseTotal()).isEqualByComparingTo("0.001475");
        assertThat(off.timePricing()).isEqualTo(TimePricingFixtures.policy());
        assertThat(peak.pricingSnapshotVersion()).isEqualTo("frozen-time-price@time-" + TimePricingFixtures.policy().snapshotKey() + "-PEAK-2026-03-25T01:00:00Z");
        assertThat(off.pricingSnapshotVersion()).isEqualTo("frozen-time-price@time-" + TimePricingFixtures.policy().snapshotKey() + "-OFF_PEAK-2026-03-25T04:00:00Z");
    }

    @Test void missingOrUncoveredStartRetainsItsIdentityButCannotUseTheCurrentClock() {
        var calculator = calculator(price("25", "125", "5", TimePricingFixtures.policy()));
        var missing = calculate(calculator, usage(100L, 40L, 10L), null);
        var nextYear = calculate(calculator, usage(100L, 40L, 10L), Instant.parse("2027-03-25T01:00:00Z"));
        assertThat(missing.preciseInput()).isNull(); assertThat(missing.preciseOutput()).isNull(); assertThat(missing.preciseTotal()).isNull();
        assertThat(missing.pricingSnapshotVersion()).endsWith("-UNKNOWN-null");
        assertThat(nextYear.preciseTotal()).isNull(); assertThat(nextYear.pricingSnapshotVersion()).endsWith("-UNKNOWN-2027-03-25T01:00:00Z");
    }

    @Test void absentTimePolicyIgnoresBothMissingAndUncoveredTimestamps() {
        var calculator = calculator(price("25", "125", "5", null));
        var missing = calculate(calculator, usage(100L, 40L, 10L), null);
        assertThat(missing).isEqualTo(calculate(calculator, usage(100L, 40L, 10L), Instant.parse("2027-03-25T01:00:00Z")));
        assertThat(missing.preciseTotal()).isEqualByComparingTo("0.00295");
        assertThat(missing.pricingSnapshotVersion()).isEqualTo("frozen-time-price"); assertThat(missing.timePricing()).isNull();
    }

    @Test void discountIsRoundedOnceAfterCombiningInputBuckets() {
        var calculator = calculator(price("0.000001", null, "0.000001", TimePricingFixtures.policy()));
        var off = calculate(calculator, usage(2L, 1L, 0L), OFF);
        assertThat(off.preciseInput()).isEqualTo(new BigDecimal("0.000000000001"));
        assertThat(off.preciseTotal()).isEqualTo(new BigDecimal("0.000000000001"));
    }

    @Test void discountHappensBeforeCapacityChecksSoARepresentableOffPeakAmountIsNotLost() {
        var calculator = calculator(price("1500000000000", null, null, TimePricingFixtures.policy()));
        var usage = usage(1_000_000L, 0L, 0L);
        var peak = calculate(calculator, usage, PEAK); var off = calculate(calculator, usage, OFF);
        assertThat(peak.preciseInput()).isNull(); assertThat(peak.preciseTotal()).isNull();
        assertThat(off.preciseInput()).isEqualByComparingTo("750000000000");
        assertThat(off.preciseTotal()).isEqualByComparingTo("750000000000");
    }

    @Test void missingCacheReportsKeepDiscountedKnownOutputWhileKnownZeroBucketsStayZero() {
        var calculator = calculator(price("25", "125", "5", TimePricingFixtures.policy()));
        var partial = calculate(calculator, usage(100L, null, 10L), OFF);
        assertThat(partial.preciseInput()).isNull(); assertThat(partial.preciseOutput()).isEqualByComparingTo("0.000625");
        assertThat(partial.preciseTotal()).isNull();
        var zero = calculate(calculator(price(null, null, null, TimePricingFixtures.policy())), usage(0L, 0L, 0L), OFF);
        assertThat(zero.preciseTotal()).isZero();
    }

    @Test void configuredMultipliersAlsoApplyToUniformAndCacheReadWritePrices() {
        var policy = TimePricingFixtures.withMultipliers(new BigDecimal("2"), new BigDecimal("0.25"));
        var uniform = calculator(new ModelPriceSource.Price(new BigDecimal("25"), new BigDecimal("125"), null, "CNY", null, null, policy));
        var usage = new LlmUsage(100L, 40L, 20L, 10L, null, null, UsageSource.PROVIDER_REPORTED, null);
        assertThat(calculate(uniform, usage, PEAK).preciseTotal()).isEqualByComparingTo("0.0075");
        assertThat(calculate(uniform, usage, OFF).preciseTotal()).isEqualByComparingTo("0.0009375");
        var readWrite = calculator(new ModelPriceSource.Price(new BigDecimal("25"), new BigDecimal("125"), null, "CNY", null,
                new CacheCostPrice(CacheCostPrice.Mode.CACHE_READ_WRITE, new BigDecimal("5"), new BigDecimal("50")), policy));
        assertThat(calculate(readWrite, usage, PEAK).preciseTotal()).isEqualByComparingTo("0.0069");
        assertThat(calculate(readWrite, usage, OFF).preciseTotal()).isEqualByComparingTo("0.0008625");
        assertThat(calculate(readWrite, usage, PEAK).pricingSnapshotVersion()).contains(policy.snapshotKey());
    }

    @Test void missingConfiguredMultiplierRemainsUnknownEvenForReportedZeroTokens() {
        var policy = TimePricingFixtures.withMultipliers(BigDecimal.ONE, null);
        var calculator = calculator(price("25", "125", "5", policy));
        var known = calculate(calculator, usage(100L, 40L, 10L), PEAK);
        assertThat(known.preciseTotal()).isEqualByComparingTo("0.00295");
        assertThat(calculate(calculator, usage(100L, 40L, 10L), OFF).preciseTotal()).isNull();
        assertThat(calculate(calculator, usage(0L, 0L, 0L), OFF).preciseTotal()).isNull();
    }

    @Test void changingConfigurationChangesOnlyNewCalculationsAndZeroMultiplierDoesNotInventUsage() {
        var oldCalculator = calculator(price("25", "125", "5", TimePricingFixtures.policy()));
        var old = calculate(oldCalculator, usage(100L, 40L, 10L), OFF);
        var updated = calculator(price("25", "125", "5", TimePricingFixtures.withMultipliers(BigDecimal.ONE, new BigDecimal("0.25"))));
        assertThat(calculate(updated, usage(100L, 40L, 10L), OFF).preciseTotal()).isEqualByComparingTo("0.0007375");
        assertThat(calculate(oldCalculator, usage(100L, 40L, 10L), OFF)).isEqualTo(old);
        assertThat(calculate(updated, usage(100L, 40L, 10L), OFF).pricingSnapshotVersion()).isNotEqualTo(old.pricingSnapshotVersion());
        var zero = calculator(price("25", "125", "5", TimePricingFixtures.withMultipliers(BigDecimal.ONE, BigDecimal.ZERO)));
        assertThat(calculate(zero, usage(100L, 40L, 10L), OFF).preciseTotal()).isZero();
        assertThat(calculate(zero, usage(null, null, null), OFF).preciseTotal()).isNull();
    }

    private static LlmUsage usage(Long input, Long read, Long output) {
        return new LlmUsage(input, read, null, output, null, null, UsageSource.PROVIDER_REPORTED, null);
    }
    private static ModelPriceSource.Price price(String input, String output, String read, TimePricingPolicy policy) {
        return new ModelPriceSource.Price(decimal(input), decimal(output), null, "CNY", null,
                new CacheCostPrice(CacheCostPrice.Mode.CACHE_READ, decimal(read), null), policy);
    }
    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private static PriceCalculator.Amounts calculate(PriceCalculator calculator, LlmUsage usage, Instant startedAt) {
        return calculator.calculate(MODEL, 7L, CostType.LLM_CHAT, "alias", "alias", usage, 1, startedAt);
    }
    private static PriceCalculator calculator(ModelPriceSource.Price price) {
        var source = mock(ModelPriceSource.class);
        when(source.snapshot()).thenThrow(new AssertionError("time price must use frozen revision"));
        when(source.snapshot(MODEL)).thenReturn(new ModelPriceSource.Snapshot("frozen-time-price", Map.of(
                new ModelPriceSource.Key("provider", "model"), price)));
        return new PriceCalculator(source);
    }
}
