package com.skillforge.core.billing.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** 媒体供应商成本的显式单位与单价；与机构售价及通用调用次数价格独立。 */
public record MediaCostPrice(Unit unit, BigDecimal pricePerUnitCents,
        BigDecimal inputPricePerMillionCents, BigDecimal outputPricePerMillionCents,
        String videoResolution) {
    private static final BigDecimal MAXIMUM_PRICE = new BigDecimal("9223372036854775807");

    public enum Unit {
        OUTPUT_IMAGE, OUTPUT_VIDEO, VIDEO_SECOND, INPUT_AUDIO_SECOND, OUTPUT_AUDIO_SECOND, BILLING_CHARACTER, TOKENS
    }

    public MediaCostPrice {
        Objects.requireNonNull(unit, "unit");
        pricePerUnitCents = normalize(pricePerUnitCents);
        inputPricePerMillionCents = normalize(inputPricePerMillionCents);
        outputPricePerMillionCents = normalize(outputPricePerMillionCents);
        if (unit == Unit.TOKENS
                ? pricePerUnitCents != null || inputPricePerMillionCents == null || outputPricePerMillionCents == null
                : pricePerUnitCents == null || inputPricePerMillionCents != null || outputPricePerMillionCents != null)
            throw new IllegalArgumentException("media_price_fields_invalid");
        if (unit == Unit.VIDEO_SECOND
                ? !java.util.Set.of("480P", "720P", "1080P").contains(videoResolution == null ? "" : videoResolution)
                : videoResolution != null)
            throw new IllegalArgumentException("media_price_resolution_invalid");
    }

    private static BigDecimal normalize(BigDecimal price) {
        if (price == null) return null;
        BigDecimal stripped = price.stripTrailingZeros();
        if (price.signum() < 0 || price.compareTo(MAXIMUM_PRICE) > 0 || stripped.scale() > 6)
            throw new IllegalArgumentException("media_price_invalid");
        return stripped.setScale(Math.max(0, stripped.scale()));
    }
}
