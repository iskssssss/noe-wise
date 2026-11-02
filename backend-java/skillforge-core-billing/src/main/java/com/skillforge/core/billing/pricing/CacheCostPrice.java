package com.skillforge.core.billing.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** 总输入包含缓存子集；明确的缓存成本模式和分/百万Token单价。 */
public record CacheCostPrice(Mode mode, BigDecimal readPricePerMillionCents, BigDecimal writePricePerMillionCents) {
    private static final BigDecimal MAXIMUM_PRICE = new BigDecimal("9223372036854775807");

    public enum Mode { CACHE_READ, CACHE_READ_WRITE }

    public CacheCostPrice {
        Objects.requireNonNull(mode, "mode");
        readPricePerMillionCents = normalize(readPricePerMillionCents);
        writePricePerMillionCents = normalize(writePricePerMillionCents);
        if (mode == Mode.CACHE_READ && writePricePerMillionCents != null)
            throw new IllegalArgumentException("cache_write_price_not_applicable");
    }

    private static BigDecimal normalize(BigDecimal price) {
        if (price == null) return null;
        BigDecimal stripped = price.stripTrailingZeros();
        if (price.signum() < 0 || price.compareTo(MAXIMUM_PRICE) > 0 || stripped.scale() > 6)
            throw new IllegalArgumentException("cache_price_invalid");
        return stripped.setScale(Math.max(0, stripped.scale()));
    }
}
