package com.skillforge.core.billing.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** 外部汇率 Provider SPI；实现必须返回写时可审计的 CNY 汇率快照。 */
public interface ExchangeRateProvider {

    /**
     * 获取指定币种在事件时间附近的 CNY 汇率。
     *
     * @param currency 币种 ISO 代码
     * @param occurredAt 事件发生时间
     * @return 有效快照；Provider 不可用或无法证明时返回 empty
     */
    Optional<Rate> toCny(String currency, Instant occurredAt);

    /** 不可变汇率快照。 */
    record Rate(BigDecimal toCny, Instant observedAt, String snapshotVersion) {
        public Rate {
            if (toCny == null || toCny.signum() <= 0 || toCny.scale() > 12) {
                throw new IllegalArgumentException("exchange rate must be positive and bounded");
            }
            if (observedAt == null) {
                throw new IllegalArgumentException("exchange rate observedAt is required");
            }
            if (snapshotVersion == null || snapshotVersion.isBlank()
                    || snapshotVersion.length() > 128) {
                throw new IllegalArgumentException("exchange rate snapshotVersion is required");
            }
        }
    }
}
