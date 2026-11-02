package com.skillforge.core.model;

import com.skillforge.core.billing.pricing.TimePricingPolicy;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 测试自行提供可编辑规则，不依赖供应商内置代码。 */
final class TimePricingTestPolicies {
    private TimePricingTestPolicies() { }
    static TimePricingPolicy configured(List<TimePricingPolicy.Scope> scopes) {
        return new TimePricingPolicy("Asia/Shanghai", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                List.of(new TimePricingPolicy.Period(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new TimePricingPolicy.Period(LocalTime.of(14, 0), LocalTime.of(18, 0))),
                List.of(), List.of(), BigDecimal.ONE, new BigDecimal("0.5"), scopes,
                "https://pricing.example.cn/rates", "test-2026-v1", null);
    }
}
