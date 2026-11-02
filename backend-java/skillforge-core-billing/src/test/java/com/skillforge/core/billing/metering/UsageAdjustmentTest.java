package com.skillforge.core.billing.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageAdjustmentTest {

    @Test
    void rejectsMeasuredFactsAndInvalidReferencesOrPricedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new UsageAdjustment(
                "event-1", 10L, UsageEventKind.MEASURED, AmountStatus.PRICED,
                null, null, BigDecimal.ONE, null, "v1", "CNY", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> UsageAdjustment.adjustment(
                "event-1", 0L, AmountStatus.UNPRICED, null, null));
        assertThrows(IllegalArgumentException.class, () -> UsageAdjustment.adjustment(
                "event-1", 10L, AmountStatus.PRICED, null, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> new UsageAdjustment(
                "event-1", 10L, UsageEventKind.ADJUSTMENT, AmountStatus.PRICED,
                null, null, BigDecimal.ONE, BigDecimal.ZERO, "v1", "CNY", Map.of()));
    }

    @Test
    void normalizesOptionalMetadataAndKeepsNegativeCompensationAmounts() {
        UsageAdjustment adjustment = new UsageAdjustment(
                "event-1", 10L, UsageEventKind.RECONCILIATION, AmountStatus.UNRESOLVED,
                null, null, new BigDecimal("-1.2500"), null, " ", " ", Map.of("reason", "late"));

        assertEquals(new BigDecimal("-1.2500"), adjustment.totalAmount());
        assertEquals(null, adjustment.currency());
        assertEquals("late", adjustment.meta().get("reason"));
    }
}
