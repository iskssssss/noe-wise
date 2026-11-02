package com.skillforge.core.model;

import java.math.BigDecimal;

/** 模型单价使用小数分；Token 折算后最多十二位小数分，与用量精确金额列一致。 */
final class ModelPrices {
    static final int MAX_SCALE = 6;
    static final BigDecimal MAXIMUM = new BigDecimal("9223372036854775807");

    private ModelPrices() { }

    static boolean valid(BigDecimal value) {
        return value == null || value.signum() >= 0
                && value.compareTo(MAXIMUM) <= 0
                && value.stripTrailingZeros().scale() <= MAX_SCALE;
    }

    /** 去除无意义尾零并保留普通十进制形式，使相同价格使用同一快照表示。 */
    static BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        if (!valid(value)) throw new IllegalArgumentException("model_price_invalid");
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(0, stripped.scale()));
    }
}
