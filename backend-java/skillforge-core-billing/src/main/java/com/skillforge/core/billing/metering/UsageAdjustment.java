package com.skillforge.core.billing.metering;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 对原始用量事实的追加对账或补偿；不能表达新的 measured 事件。 */
public record UsageAdjustment(
        String eventId,
        Long originalEventId,
        UsageEventKind kind,
        AmountStatus amountStatus,
        BigDecimal inputAmount,
        BigDecimal outputAmount,
        BigDecimal totalAmount,
        BigDecimal exchangeRateToCny,
        String pricingSnapshotVersion,
        String currency,
        Map<String, Object> meta) {

    public UsageAdjustment {
        eventId = required(eventId, "eventId");
        if (originalEventId == null || originalEventId <= 0) {
            throw new IllegalArgumentException("originalEventId must be positive");
        }
        Objects.requireNonNull(kind, "kind");
        if (kind == UsageEventKind.MEASURED) {
            throw new IllegalArgumentException("adjustment kind must not be measured");
        }
        Objects.requireNonNull(amountStatus, "amountStatus");
        if (amountStatus == AmountStatus.PRICED && totalAmount == null) {
            throw new IllegalArgumentException("priced adjustment requires totalAmount");
        }
        if (exchangeRateToCny != null && exchangeRateToCny.signum() <= 0) {
            throw new IllegalArgumentException("exchangeRateToCny must be positive");
        }
        if (amountStatus == AmountStatus.PRICED && (currency == null || currency.isBlank())) {
            throw new IllegalArgumentException("priced adjustment requires currency");
        }
        pricingSnapshotVersion = pricingSnapshotVersion == null || pricingSnapshotVersion.isBlank()
                ? null : pricingSnapshotVersion;
        currency = currency == null || currency.isBlank() ? null : currency;
        meta = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(meta, "meta")));
    }

    public static UsageAdjustment adjustment(String eventId, long originalEventId,
            AmountStatus amountStatus, BigDecimal totalAmount, String currency) {
        return new UsageAdjustment(eventId, originalEventId, UsageEventKind.ADJUSTMENT, amountStatus,
                null, null, totalAmount, null, null, currency, Map.of());
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
