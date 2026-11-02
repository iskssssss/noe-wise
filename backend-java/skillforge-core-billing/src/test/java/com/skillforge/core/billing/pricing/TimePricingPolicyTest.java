package com.skillforge.core.billing.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 从显式数据选择时段、日历、范围和倍率，日期不由供应商名或固定年份决定。 */
class TimePricingPolicyTest {
    private static final TimePricingPolicy POLICY = TimePricingFixtures.policy();

    @Test void configuredPeriodsHaveInclusiveStartsAndExclusiveEnds() {
        for (String time : List.of("09:00:00", "11:59:59.999999999", "14:00:00", "17:59:59"))
            assertThat(POLICY.classification(beijing("2026-03-25T" + time))).isEqualTo(TimePricingPolicy.Band.PEAK);
        for (String time : List.of("08:59:59", "12:00:00", "13:59:59", "18:00:00"))
            assertThat(POLICY.classification(beijing("2026-03-25T" + time))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
    }

    @Test void localCoverageUsesConfiguredTimezoneAndCanBeMovedToAnotherYear() {
        assertThat(POLICY.classification(null)).isEqualTo(TimePricingPolicy.Band.UNKNOWN);
        assertThat(POLICY.classification(Instant.parse("2025-12-31T15:59:59Z"))).isEqualTo(TimePricingPolicy.Band.UNKNOWN);
        assertThat(POLICY.classification(Instant.parse("2025-12-31T16:00:00Z"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
        assertThat(POLICY.classification(Instant.parse("2026-12-31T16:00:00Z"))).isEqualTo(TimePricingPolicy.Band.UNKNOWN);
        TimePricingPolicy nextYear = policy("UTC", LocalDate.parse("2034-01-01"), LocalDate.parse("2034-12-31"),
                List.of(DayOfWeek.values()), POLICY.peakPeriods(), List.of(), List.of(), List.of());
        assertThat(nextYear.classification(Instant.parse("2034-03-25T10:00:00Z"))).isEqualTo(TimePricingPolicy.Band.PEAK);
        assertThat(nextYear.classification(Instant.parse("2026-03-25T10:00:00Z"))).isEqualTo(TimePricingPolicy.Band.UNKNOWN);
        TimePricingPolicy utc = policy("UTC", POLICY.validFrom(), POLICY.validThrough(),
                POLICY.peakWeekdays(), POLICY.peakPeriods(), List.of(), List.of(), List.of());
        assertThat(utc.classification(Instant.parse("2026-03-25T01:00:00Z"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
        assertThat(POLICY.classification(Instant.parse("2026-03-25T01:00:00Z"))).isEqualTo(TimePricingPolicy.Band.PEAK);
    }

    @Test void configuredCalendarOverridesWeekdaysAndSupportsExtraPeakWeekendDates() {
        TimePricingPolicy calendar = policy(POLICY.timeZone(), POLICY.validFrom(), POLICY.validThrough(),
                POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(range("2026-03-28", "2026-03-29")), List.of(range("2026-03-24", "2026-03-25")), List.of());
        assertThat(calendar.classification(beijing("2026-03-24T10:00:00"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
        assertThat(calendar.classification(beijing("2026-03-25T10:00:00"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
        assertThat(calendar.classification(beijing("2026-03-28T10:00:00"))).isEqualTo(TimePricingPolicy.Band.PEAK);
        assertThat(calendar.classification(beijing("2026-03-29T12:00:00"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
        assertThat(calendar.classification(beijing("2026-04-04T10:00:00"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
    }

    @Test void configuredScopeMatchesOrAcrossRowsAndAndWithinRowsWithoutInferringModels() {
        var scoped = policy("UTC", POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(), List.of(), List.of(new TimePricingPolicy.Scope("vendor-a", "service-a", "r1", "model-a"),
                        new TimePricingPolicy.Scope("vendor-b", null, null, "model-b")));
        assertThat(scoped.appliesTo("vendor-a", "service-a", "r1", "model-a")).isTrue();
        assertThat(scoped.appliesTo("vendor-b", "any-service", "r99", "model-b")).isTrue();
        assertThat(scoped.appliesTo("vendor-a", "service-b", "r1", "model-a")).isFalse();
        assertThat(scoped.appliesTo("vendor-b", "any-service", "r99", "model-a")).isFalse();
        assertThat(POLICY.appliesTo("future-provider", "service", "r99", "future-model")).isTrue();
    }

    @Test void emptyPeakScheduleIsExplicitOffPeakAndCollectionsAreCopied() {
        var days = new ArrayList<>(POLICY.peakWeekdays());
        var unchanged = policy("UTC", POLICY.validFrom(), POLICY.validThrough(), days, POLICY.peakPeriods(), List.of(), List.of(), List.of());
        days.clear();
        assertThat(unchanged.peakWeekdays()).hasSize(5);
        assertThatThrownBy(() -> unchanged.peakWeekdays().clear()).isInstanceOf(UnsupportedOperationException.class);
        var offOnly = policy("UTC", POLICY.validFrom(), POLICY.validThrough(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(offOnly.classification(Instant.parse("2026-03-25T10:00:00Z"))).isEqualTo(TimePricingPolicy.Band.OFF_PEAK);
    }

    @Test void invalidCalendarAndTimeIntervalsCannotBeActivated() {
        assertThatThrownBy(() -> new TimePricingPolicy.Period(LocalTime.of(18, 0), LocalTime.of(9, 0)))
                .hasMessage("time_pricing_period_invalid");
        assertThatThrownBy(() -> new TimePricingPolicy.DateRange(LocalDate.parse("2026-03-26"), LocalDate.parse("2026-03-25")))
                .hasMessage("time_pricing_date_range_invalid");
        assertThatThrownBy(() -> policy("UTC", POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(),
                List.of(new TimePricingPolicy.Period(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new TimePricingPolicy.Period(LocalTime.of(11, 0), LocalTime.of(13, 0))), List.of(), List.of(), List.of()))
                .hasMessage("time_pricing_periods_overlap");
        assertThatThrownBy(() -> policy("UTC", POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(range("2026-03-24", "2026-03-25")), List.of(range("2026-03-25", "2026-03-26")), List.of()))
                .hasMessage("time_pricing_date_ranges_overlap");
        assertThatThrownBy(() -> policy("UTC", POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(), List.of(range("2026-03-24", "2026-03-25"), range("2026-03-25", "2026-03-26")), List.of()))
                .hasMessage("time_pricing_date_ranges_overlap");
        assertThatThrownBy(() -> policy("UTC", POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(range("2027-01-01", "2027-01-01")), List.of(), List.of()))
                .hasMessage("time_pricing_date_range_outside_coverage");
        assertThatThrownBy(() -> policy("UTC", POLICY.validFrom(), POLICY.validThrough(), null, List.of(), List.of(), List.of(), List.of()))
                .hasMessage("time_pricing_required_collection_missing");
        assertThatThrownBy(() -> policy("UTC", POLICY.validFrom(), POLICY.validThrough(), List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY),
                List.of(), List.of(), List.of(), List.of())).hasMessage("time_pricing_weekdays_duplicated");
    }

    @Test void configurableMultipliersCanBeZeroUnknownOrMoreThanOneButNotInvalid() {
        var configurable = TimePricingFixtures.withMultipliers(new BigDecimal("2.250000000000"), BigDecimal.ZERO);
        assertThat(configurable.multiplier(TimePricingPolicy.Band.PEAK)).isEqualByComparingTo("2.25");
        assertThat(configurable.multiplier(TimePricingPolicy.Band.OFF_PEAK)).isZero();
        assertThat(configurable.multiplier(TimePricingPolicy.Band.UNKNOWN)).isNull();
        assertThat(TimePricingFixtures.withMultipliers(null, null).multiplier(TimePricingPolicy.Band.PEAK)).isNull();
        assertThat(TimePricingFixtures.withMultipliers(new BigDecimal("999999999999.999999999999"), BigDecimal.ZERO).peakMultiplier())
                .isEqualByComparingTo("999999999999.999999999999");
        for (String bad : List.of("-0.5", "0.0000000000001", "1000000000000", "1000000000000000000000000"))
            assertThatThrownBy(() -> TimePricingFixtures.withMultipliers(new BigDecimal(bad), BigDecimal.ONE))
                    .hasMessage("time_pricing_multiplier_invalid");
    }

    @Test void typedJsonRoundTripKeepsDecimalPrecisionDatesAndNanosecondPeriods() throws Exception {
        var json = mapper();
        String encoded = json.writeValueAsString(POLICY);
        assertThat(encoded).contains("\"off_peak_multiplier\":\"0.5\"", "\"peak_weekdays\":[\"MONDAY\"", "\"time_zone\":\"Asia/Shanghai\"");
        assertThat(json.readValue(encoded, TimePricingPolicy.class)).isEqualTo(POLICY);
        assertThat(json.readValue(encoded, TimePricingPolicy.class).snapshotKey()).isEqualTo(POLICY.snapshotKey());
        var tiny = TimePricingFixtures.withMultipliers(new BigDecimal("0.000000000001"), new BigDecimal("999999999999.999999999999"));
        assertThat(json.writeValueAsString(tiny)).contains("\"peak_multiplier\":\"0.000000000001\"");
        assertThat(json.readValue(json.writeValueAsString(tiny), TimePricingPolicy.class)).isEqualTo(tiny);
        var fractional = policy("UTC", POLICY.validFrom(), POLICY.validThrough(), List.of(DayOfWeek.values()),
                List.of(new TimePricingPolicy.Period(LocalTime.of(9, 0, 0, 123456789), LocalTime.of(10, 0, 0, 987654321))),
                List.of(), List.of(), List.of());
        assertThat(json.readValue(json.writeValueAsString(fractional), TimePricingPolicy.class)).isEqualTo(fractional);
    }

    @Test void jsonRejectsOldPolicyNamesNumericWeekdaysAndNonStringMultipliers() throws Exception {
        var json = mapper(); String encoded = json.writeValueAsString(POLICY);
        for (String invalid : List.of("\"DEEPSEEK_CN_2026_V1\"", "0", "{}", "true",
                encoded.replace("\"MONDAY\"", "0"),
                encoded.replace("\"off_peak_multiplier\":\"0.5\"", "\"off_peak_multiplier\":0.5"),
                encoded.replace("\"off_peak_multiplier\":\"0.5\"", "\"off_peak_multiplier\":\"5e-1\""),
                encoded.replace("\"off_peak_multiplier\":\"0.5\"", "\"off_peak_multiplier\":\"0.0000000000001\"")))
            assertThatThrownBy(() -> json.readValue(invalid, TimePricingPolicy.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test void ruleIdentityChangesWithConfiguredMultiplierCalendarScopeOrSource() {
        assertThat(POLICY.snapshotKey()).hasSize(64);
        assertThat(TimePricingFixtures.withMultipliers(BigDecimal.ONE, new BigDecimal("0.25")).snapshotKey()).isNotEqualTo(POLICY.snapshotKey());
        var moved = policy("UTC", POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(), List.of(), List.of(), List.of());
        assertThat(moved.snapshotKey()).isNotEqualTo(POLICY.snapshotKey());
        var scoped = policy(POLICY.timeZone(), POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(), List.of(), List.of(new TimePricingPolicy.Scope(null, null, null, "model-a")));
        assertThat(scoped.snapshotKey()).isNotEqualTo(POLICY.snapshotKey());
        var calendar = policy(POLICY.timeZone(), POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(), POLICY.peakPeriods(),
                List.of(), List.of(range("2026-03-24", "2026-03-25")), List.of());
        assertThat(calendar.snapshotKey()).isNotEqualTo(POLICY.snapshotKey());
        var changedSource = new TimePricingPolicy(POLICY.timeZone(), POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(),
                POLICY.peakPeriods(), POLICY.peakDateRanges(), POLICY.offPeakDateRanges(), POLICY.peakMultiplier(), POLICY.offPeakMultiplier(),
                POLICY.applicableModels(), POLICY.sourceUrl(), "configured-test-r2", POLICY.calendarSourceUrl());
        assertThat(changedSource.snapshotKey()).isNotEqualTo(POLICY.snapshotKey());
    }

    @Test void relationalFieldLimitsCountUnicodeCodePointsWithoutTruncation() {
        String astral = new String(Character.toChars(0x1F600));
        String scopeBoundary = astral.repeat(128);
        assertThat(new TimePricingPolicy.Scope(scopeBoundary, scopeBoundary, scopeBoundary, null).providerId())
                .isEqualTo(scopeBoundary);
        for (int field = 0; field < 3; field++) {
            String[] identifiers = {null, null, null}; identifiers[field] = astral.repeat(129);
            assertThatThrownBy(() -> new TimePricingPolicy.Scope(identifiers[0], identifiers[1], identifiers[2], null))
                    .hasMessage("time_pricing_scope_identifier_too_long");
        }
        var boundary = new TimePricingPolicy(POLICY.timeZone(), POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(),
                POLICY.peakPeriods(), POLICY.peakDateRanges(), POLICY.offPeakDateRanges(), POLICY.peakMultiplier(), POLICY.offPeakMultiplier(),
                POLICY.applicableModels(), POLICY.sourceUrl(), astral.repeat(256), POLICY.calendarSourceUrl());
        assertThat(boundary.sourceVersion()).isEqualTo(astral.repeat(256));
        assertThatThrownBy(() -> new TimePricingPolicy(POLICY.timeZone(), POLICY.validFrom(), POLICY.validThrough(), POLICY.peakWeekdays(),
                POLICY.peakPeriods(), POLICY.peakDateRanges(), POLICY.offPeakDateRanges(), POLICY.peakMultiplier(), POLICY.offPeakMultiplier(),
                POLICY.applicableModels(), POLICY.sourceUrl(), astral.repeat(257), POLICY.calendarSourceUrl()))
                .hasMessage("time_pricing_source_version_too_long");
    }

    private static TimePricingPolicy policy(String zone, LocalDate from, LocalDate through, List<DayOfWeek> weekdays,
            List<TimePricingPolicy.Period> periods, List<TimePricingPolicy.DateRange> peakDates,
            List<TimePricingPolicy.DateRange> offDates, List<TimePricingPolicy.Scope> scope) {
        return new TimePricingPolicy(zone, from, through, weekdays, periods, peakDates, offDates,
                BigDecimal.ONE, new BigDecimal("0.5"), scope, POLICY.sourceUrl(), POLICY.sourceVersion(), null);
    }

    private static TimePricingPolicy.DateRange range(String from, String through) {
        return new TimePricingPolicy.DateRange(LocalDate.parse(from), LocalDate.parse(through));
    }
    private static ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
    private static Instant beijing(String value) { return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant(); }
}
