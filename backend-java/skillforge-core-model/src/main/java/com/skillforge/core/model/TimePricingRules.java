package com.skillforge.core.model;

import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/** 保存完整不可变规则；引用解析只读精确身份，不查询预设当前指针。 */
@Transactional
public class TimePricingRules {
    private final TimePricingMapper mapper;

    public TimePricingRules(TimePricingMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public Long save(TimePricingPolicy policy) {
        if (policy == null) return null;
        long id = SnowflakeIds.nextId();
        mapper.insertRule(id, policy);
        for (int i = 0; i < policy.peakWeekdays().size(); i++)
            mapper.insertWeekday(id, i, policy.peakWeekdays().get(i).name());
        for (int i = 0; i < policy.peakPeriods().size(); i++) {
            var period = policy.peakPeriods().get(i);
            mapper.insertPeriod(id, i, period.start().toNanoOfDay(), period.end().toNanoOfDay());
        }
        for (int i = 0; i < policy.peakDateRanges().size(); i++) mapper.insertDate(id, "PEAK", i, policy.peakDateRanges().get(i));
        for (int i = 0; i < policy.offPeakDateRanges().size(); i++) mapper.insertDate(id, "OFF_PEAK", i, policy.offPeakDateRanges().get(i));
        for (int i = 0; i < policy.applicableModels().size(); i++) mapper.insertScope(id, i, policy.applicableModels().get(i));
        return id;
    }

    public TimePricingPolicy read(Long id) { return id == null ? null : readAll(List.of(id)).get(id); }

    /** 列表一次读取所有规则与明细，避免逐模型查询。 */
    public Map<Long, TimePricingPolicy> readAll(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var distinct = ids.stream().distinct().toList();
        var weekdays = mapper.weekdays(distinct).stream().collect(Collectors.groupingBy(TimePricingMapper.WeekdayRow::ruleId));
        var periods = mapper.periods(distinct).stream().collect(Collectors.groupingBy(TimePricingMapper.PeriodRow::ruleId));
        var dates = mapper.dates(distinct).stream().collect(Collectors.groupingBy(TimePricingMapper.DateRow::ruleId));
        var scopes = mapper.scopes(distinct).stream().collect(Collectors.groupingBy(TimePricingMapper.ScopeRow::ruleId));
        Map<Long, TimePricingPolicy> result = new HashMap<>();
        for (var row : mapper.rules(distinct)) {
            var ruleDates = dates.getOrDefault(row.id(), List.of());
            result.put(row.id(), new TimePricingPolicy(row.timeZone(), row.validFrom(), row.validThrough(),
                    weekdays.getOrDefault(row.id(), List.of()).stream().map(d -> DayOfWeek.valueOf(d.weekday())).toList(),
                    periods.getOrDefault(row.id(), List.of()).stream().map(p -> new TimePricingPolicy.Period(LocalTime.ofNanoOfDay(p.startNanos()), LocalTime.ofNanoOfDay(p.endNanos()))).toList(),
                    ruleDates.stream().filter(d -> "PEAK".equals(d.band())).map(d -> new TimePricingPolicy.DateRange(d.from(), d.through())).toList(),
                    ruleDates.stream().filter(d -> "OFF_PEAK".equals(d.band())).map(d -> new TimePricingPolicy.DateRange(d.from(), d.through())).toList(),
                    row.peakMultiplier(), row.offPeakMultiplier(),
                    scopes.getOrDefault(row.id(), List.of()).stream().map(s -> new TimePricingPolicy.Scope(s.providerId(), s.serviceId(), s.profileVersion(), s.modelId())).toList(),
                    row.sourceUrl(), row.sourceVersion(), row.calendarSourceUrl()));
        }
        if (result.size() != distinct.size()) throw new IllegalStateException("model_time_pricing_rule_missing");
        return Map.copyOf(result);
    }
}
