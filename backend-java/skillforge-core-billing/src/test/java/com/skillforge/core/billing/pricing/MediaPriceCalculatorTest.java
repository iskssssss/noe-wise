package com.skillforge.core.billing.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MediaUsage;
import com.skillforge.core.billing.pricing.MediaCostPrice.Unit;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 媒体明确单位只消费对应供应商报告，不以调用数或请求参数补量。 */
class MediaPriceCalculatorTest {
    private static final ModelRevisionRef MODEL = new ModelRevisionRef("81", 3);

    @Test void imageAndVideoCountsUseExactLongMetricsInsteadOfOneCallOrDuration() {
        var images = calculator(scalar(Unit.OUTPUT_IMAGE, "2.5", null));
        var image = images.calculateMedia(MODEL, CostType.MEDIA_IMAGE, usage(null, null, 3L, null, null, null), null);
        assertThat(image.preciseTotal()).isEqualByComparingTo("7.5");
        assertThat(image.total()).isEqualTo(8L);
        assertThat(image.preciseInput()).isNull(); assertThat(image.preciseOutput()).isNull();
        var videos = calculator(scalar(Unit.OUTPUT_VIDEO, "0.000001", null));
        assertThat(videos.calculateMedia(MODEL, CostType.MEDIA_VIDEO,
                usage(null, null, null, 9_007_199_254_740_993L, null, "99"), null).preciseTotal())
                .isEqualByComparingTo("9007199254.740993");
    }

    @Test void videoSecondsRequireMatchingOriginalResolutionAndRealProviderDuration() {
        var calculator = calculator(scalar(Unit.VIDEO_SECOND, "12.5", "720P"));
        var usage = usage(null, null, null, null, null, "2.125");
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_VIDEO, usage, new MediaPricingContext("720P"))
                .preciseTotal()).isEqualByComparingTo("26.5625");
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_VIDEO, usage, null).preciseTotal()).isNull();
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_VIDEO, usage, new MediaPricingContext("1080P"))
                .preciseTotal()).isNull();
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_VIDEO, MediaUsage.empty(), new MediaPricingContext("720P"))
                .preciseTotal()).isNull();
    }

    @Test void inputAndOutputAudioSecondsCannotCrossOperationKinds() {
        var usage = usage(null, null, null, null, null, "2.08");
        var input = calculator(scalar(Unit.INPUT_AUDIO_SECOND, "3", null));
        var output = calculator(scalar(Unit.OUTPUT_AUDIO_SECOND, "5", null));
        assertThat(input.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION, usage, null).preciseTotal())
                .isEqualByComparingTo("6.24");
        assertThat(input.calculateMedia(MODEL, CostType.MEDIA_SPEECH, usage, null).preciseTotal()).isNull();
        assertThat(output.calculateMedia(MODEL, CostType.MEDIA_SPEECH, usage, null).preciseTotal())
                .isEqualByComparingTo("10.4");
        assertThat(output.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION, usage, null).preciseTotal()).isNull();
    }

    @Test void billingCharactersUseReportedQuantityWithAnExplicitPerCharacterPrice() {
        var calculator = calculator(scalar(Unit.BILLING_CHARACTER, "0.00125", null));
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_SPEECH,
                usage(null, null, null, null, 1001L, null), null).preciseTotal()).isEqualByComparingTo("1.25125");
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION,
                usage(null, null, null, null, 1001L, null), null).preciseTotal()).isNull();
    }

    @Test void tokenCostsRequireBothReportedDirectionsIncludingExplicitZeroPrice() {
        var calculator = calculator(new MediaCostPrice(Unit.TOKENS, null, new BigDecimal("12.5"), BigDecimal.ZERO, null));
        var partial = calculator.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION,
                usage(1L, null, null, null, null, null), null);
        assertThat(partial.preciseInput()).isEqualByComparingTo("0.0000125");
        assertThat(partial.preciseOutput()).isNull(); assertThat(partial.preciseTotal()).isNull();
        var complete = calculator.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION,
                usage(1L, 0L, null, null, null, null), null);
        assertThat(complete.preciseTotal()).isEqualByComparingTo("0.0000125");
        assertThat(complete.preciseOutput()).isZero();
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_IMAGE,
                usage(1L, 0L, null, null, null, null), null).preciseTotal()).isEqualByComparingTo("0.0000125");
        assertThat(calculator.calculateMedia(MODEL, CostType.TOOL_CALL,
                usage(1L, 0L, null, null, null, null), null).preciseTotal()).isNull();
    }

    @Test void unconfiguredMediaPricingCannotUseGenericQuantityOrTokenPrices() {
        var calculator = calculator(null);
        var amount = calculator.calculateMedia(MODEL, CostType.MEDIA_IMAGE, usage(1L, 1L, 2L, null, null, null), null);
        assertThat(amount.preciseTotal()).isNull(); assertThat(amount.currency()).isEqualTo("CNY");
        assertThat(amount.pricingSnapshotVersion()).isEqualTo("revision-81-3");
    }

    @Test void zeroIsKnownOnlyWhenRequiredUsageAndPriceAreBothKnown() {
        var free = calculator(scalar(Unit.OUTPUT_IMAGE, "0", null));
        assertThat(free.calculateMedia(MODEL, CostType.MEDIA_IMAGE, MediaUsage.empty(), null).preciseTotal()).isNull();
        assertThat(free.calculateMedia(MODEL, CostType.MEDIA_IMAGE, usage(null, null, 9L, null, null, null), null)
                .preciseTotal()).isZero();
        var priced = calculator(scalar(Unit.OUTPUT_IMAGE, "2", null));
        assertThat(priced.calculateMedia(MODEL, CostType.MEDIA_IMAGE, usage(null, null, 0L, null, null, null), null)
                .preciseTotal()).isZero();
    }

    @Test void decimalDurationIsRoundedOnlyAtTheTwelvePlaceMoneyBoundary() {
        var calculator = calculator(scalar(Unit.INPUT_AUDIO_SECOND, "1", null));
        var usage = usage(null, null, null, null, null, "0.0000000000005");
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION, usage, null).preciseTotal())
                .isEqualTo(new BigDecimal("0.000000000001"));
        assertThat(usage.durationSeconds()).isEqualTo(new BigDecimal("0.0000000000005"));
    }

    @Test void storageCapacityBoundaryKeepsTheRawMetricAndReturnsUnknownMoneyOnOverflow() {
        var calculator = calculator(scalar(Unit.INPUT_AUDIO_SECOND, "1", null));
        assertThat(calculator.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION,
                usage(null, null, null, null, null, "999999999999.9999999999994"), null).preciseTotal())
                .isEqualByComparingTo("999999999999.999999999999");
        var oversized = usage(null, null, null, null, null, "999999999999.9999999999995");
        var amount = calculator.calculateMedia(MODEL, CostType.MEDIA_TRANSCRIPTION, oversized, null);
        assertThat(amount.preciseTotal()).isNull(); assertThat(amount.total()).isNull();
        assertThat(amount.currency()).isEqualTo("CNY");
        assertThat(oversized.durationSeconds()).isEqualByComparingTo("999999999999.9999999999995");
        assertThat(calculator(scalar(Unit.OUTPUT_IMAGE, "9223372036854775807", null))
                .calculateMedia(MODEL, CostType.MEDIA_IMAGE, usage(null, null, Long.MAX_VALUE, null, null, null), null)
                .preciseTotal()).isNull();
    }

    @Test void mediaPriceEnforcesShapeDecimalRangeAndResolutionWithoutGuessing() {
        assertThat(scalar(Unit.OUTPUT_IMAGE, "12.500000", null).pricePerUnitCents()).isEqualTo(new BigDecimal("12.5"));
        assertThatThrownBy(() -> scalar(Unit.OUTPUT_IMAGE, "-1", null)).hasMessage("media_price_invalid");
        assertThatThrownBy(() -> scalar(Unit.OUTPUT_IMAGE, "0.0000001", null)).hasMessage("media_price_invalid");
        assertThatThrownBy(() -> scalar(Unit.OUTPUT_IMAGE, "9223372036854775808", null)).hasMessage("media_price_invalid");
        assertThatThrownBy(() -> new MediaCostPrice(Unit.TOKENS, null, BigDecimal.ONE, null, null))
                .hasMessage("media_price_fields_invalid");
        assertThatThrownBy(() -> new MediaCostPrice(Unit.OUTPUT_IMAGE, BigDecimal.ONE, BigDecimal.ZERO, null, null))
                .hasMessage("media_price_fields_invalid");
        assertThatThrownBy(() -> scalar(Unit.VIDEO_SECOND, "1", null)).hasMessage("media_price_resolution_invalid");
        assertThatThrownBy(() -> scalar(Unit.OUTPUT_IMAGE, "1", "720P")).hasMessage("media_price_resolution_invalid");
    }

    private static MediaUsage usage(Long input, Long output, Long images, Long videos, Long characters, String seconds) {
        return new MediaUsage(input, output, images, videos, characters, seconds == null ? null : new BigDecimal(seconds));
    }

    private static MediaCostPrice scalar(Unit unit, String price, String resolution) {
        return new MediaCostPrice(unit, new BigDecimal(price), null, null, resolution);
    }

    private static PriceCalculator calculator(MediaCostPrice price) {
        var source = mock(ModelPriceSource.class);
        when(source.snapshot()).thenThrow(new AssertionError("media pricing must use the frozen model revision"));
        when(source.snapshot(MODEL)).thenReturn(new ModelPriceSource.Snapshot("revision-81-3", Map.of(
                new ModelPriceSource.Key("original-provider", "original-model"),
                new ModelPriceSource.Price(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("99"), "CNY", price, null, null))));
        return new PriceCalculator(source);
    }
}
