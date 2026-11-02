package com.skillforge.core.model;

import com.skillforge.core.billing.pricing.TimePricingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 峰谷规则和预设的类型化关系存储，SQL 归同名 XML。 */
@Mapper
public interface TimePricingMapper {
    int insertRule(@Param("id") long id, @Param("p") TimePricingPolicy policy);
    int insertWeekday(@Param("id") long id, @Param("ordinal") int ordinal, @Param("day") String day);
    int insertPeriod(@Param("id") long id, @Param("ordinal") int ordinal,
            @Param("startNanos") long startNanos, @Param("endNanos") long endNanos);
    int insertDate(@Param("id") long id, @Param("band") String band, @Param("ordinal") int ordinal,
            @Param("d") TimePricingPolicy.DateRange date);
    int insertScope(@Param("id") long id, @Param("ordinal") int ordinal, @Param("s") TimePricingPolicy.Scope scope);
    List<RuleRow> rules(@Param("ids") List<Long> ids);
    List<WeekdayRow> weekdays(@Param("ids") List<Long> ids);
    List<PeriodRow> periods(@Param("ids") List<Long> ids);
    List<DateRow> dates(@Param("ids") List<Long> ids);
    List<ScopeRow> scopes(@Param("ids") List<Long> ids);
    List<PresetRow> listPresets();
    PresetRow preset(@Param("id") String id);
    List<PresetRow> history(@Param("id") String id);
    int createPreset(@Param("id") String id, @Param("name") String name, @Param("rule") long rule,
            @Param("actor") String actor);
    int updatePreset(@Param("id") String id, @Param("expected") long expected, @Param("name") String name,
            @Param("rule") long rule, @Param("actor") String actor);
    int advancePreset(@Param("id") String id, @Param("expected") long expected,
            @Param("revision") long revision, @Param("actor") String actor);
    int appendPreset(@Param("id") String id, @Param("revision") long revision, @Param("name") String name,
            @Param("rule") long rule, @Param("actor") String actor);

    record RuleRow(long id, String timeZone, LocalDate validFrom, LocalDate validThrough,
            BigDecimal peakMultiplier, BigDecimal offPeakMultiplier, String sourceUrl,
            String sourceVersion, String calendarSourceUrl) { }
    record WeekdayRow(long ruleId, String weekday) { }
    record PeriodRow(long ruleId, long startNanos, long endNanos) { }
    record DateRow(long ruleId, String band, LocalDate from, LocalDate through) { }
    record ScopeRow(long ruleId, String providerId, String serviceId, String profileVersion, String modelId) { }
    record PresetRow(String id, String name, long revision, Long publishedRevision,
            boolean hasUnpublishedChanges, long ruleId) { }
}
