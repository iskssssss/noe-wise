package com.skillforge.core.billing.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 测试显式配置数据，不属于供应商内置政策。 */
public final class TimePricingFixtures {
    private TimePricingFixtures() { }

    public static TimePricingPolicy policy() {
        return new TimePricingPolicy("Asia/Shanghai", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                List.of(new TimePricingPolicy.Period(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new TimePricingPolicy.Period(LocalTime.of(14, 0), LocalTime.of(18, 0))),
                List.of(), List.of(), BigDecimal.ONE, new BigDecimal("0.5"), List.of(),
                "https://example.invalid/pricing", "configured-test-r1", null);
    }

    public static TimePricingPolicy withMultipliers(BigDecimal peak, BigDecimal off) {
        TimePricingPolicy old = policy();
        return new TimePricingPolicy(old.timeZone(), old.validFrom(), old.validThrough(), old.peakWeekdays(),
                old.peakPeriods(), old.peakDateRanges(), old.offPeakDateRanges(), peak, off, old.applicableModels(),
                old.sourceUrl(), old.sourceVersion(), old.calendarSourceUrl());
    }
}
