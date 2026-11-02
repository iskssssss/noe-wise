package com.skillforge.core.billing.pricing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.shared.security.TokenHash;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 两时段成本配置；供应商事实由发布数据提供，计算器不内置供应商、年份、日历或倍率。 */
public record TimePricingPolicy(
        @JsonProperty("time_zone") String timeZone,
        @JsonProperty("valid_from") LocalDate validFrom,
        @JsonProperty("valid_through") LocalDate validThrough,
        @JsonProperty("peak_weekdays") @JsonDeserialize(contentUsing = WeekdayReader.class) List<DayOfWeek> peakWeekdays,
        @JsonProperty("peak_periods") List<Period> peakPeriods,
        @JsonProperty("peak_date_ranges") List<DateRange> peakDateRanges,
        @JsonProperty("off_peak_date_ranges") List<DateRange> offPeakDateRanges,
        @JsonProperty("peak_multiplier") @JsonSerialize(using = MultiplierWriter.class)
        @JsonDeserialize(using = MultiplierReader.class)
        @Schema(type = "string", nullable = true, description = "0至999999999999.999999999999的十进制字符串，最多12位小数；null表示倍率未知") BigDecimal peakMultiplier,
        @JsonProperty("off_peak_multiplier") @JsonSerialize(using = MultiplierWriter.class)
        @JsonDeserialize(using = MultiplierReader.class)
        @Schema(type = "string", nullable = true, description = "0至999999999999.999999999999的十进制字符串，最多12位小数；null表示倍率未知") BigDecimal offPeakMultiplier,
        @JsonProperty("applicable_models") List<Scope> applicableModels,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("source_version") @Schema(maxLength = TimePricingPolicy.MAXIMUM_SOURCE_VERSION_CODE_POINTS, nullable = true) String sourceVersion,
        @JsonProperty("calendar_source_url") String calendarSourceUrl) {

    public enum Band { PEAK, OFF_PEAK, UNKNOWN }
    private static final int MAXIMUM_SOURCE_VERSION_CODE_POINTS = 256;
    private static final int MAXIMUM_SCOPE_IDENTIFIER_CODE_POINTS = 128;
    private static final BigDecimal MAXIMUM_MULTIPLIER = new BigDecimal("999999999999.999999999999");
    private static final ObjectWriter SNAPSHOT_WRITER = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build().writerFor(TimePricingPolicy.class);

    public TimePricingPolicy {
        if (timeZone == null || timeZone.isBlank()) throw new IllegalArgumentException("time_pricing_time_zone_invalid");
        ZoneId.of(timeZone);
        if (validFrom == null || validThrough == null || validThrough.isBefore(validFrom))
            throw new IllegalArgumentException("time_pricing_date_coverage_invalid");
        peakWeekdays = immutable(peakWeekdays);
        peakPeriods = immutable(peakPeriods);
        peakDateRanges = immutable(peakDateRanges);
        offPeakDateRanges = immutable(offPeakDateRanges);
        applicableModels = immutable(applicableModels);
        if (new HashSet<>(peakWeekdays).size() != peakWeekdays.size())
            throw new IllegalArgumentException("time_pricing_weekdays_duplicated");
        for (int index = 0; index < peakPeriods.size(); index++) {
            Period left = peakPeriods.get(index);
            for (int next = index + 1; next < peakPeriods.size(); next++) {
                Period right = peakPeriods.get(next);
                if (left.start().isBefore(right.end()) && right.start().isBefore(left.end()))
                    throw new IllegalArgumentException("time_pricing_periods_overlap");
            }
        }
        validateRanges(peakDateRanges, validFrom, validThrough);
        validateRanges(offPeakDateRanges, validFrom, validThrough);
        for (DateRange peak : peakDateRanges) {
            for (DateRange off : offPeakDateRanges) {
                if (peak.overlaps(off)) throw new IllegalArgumentException("time_pricing_date_ranges_overlap");
            }
        }
        peakMultiplier = normalizeMultiplier(peakMultiplier);
        offPeakMultiplier = normalizeMultiplier(offPeakMultiplier);
        if (exceedsLength(sourceVersion, MAXIMUM_SOURCE_VERSION_CODE_POINTS))
            throw new IllegalArgumentException("time_pricing_source_version_too_long");
    }

    /** 时段为当地时间的左闭右开区间；跨午夜时段须由配置拆开。 */
    public record Period(@JsonProperty("start") LocalTime start, @JsonProperty("end") LocalTime end) {
        public Period {
            if (start == null || end == null || !start.isBefore(end))
                throw new IllegalArgumentException("time_pricing_period_invalid");
        }
    }

    /** 日期区间含首尾两天；例外日期仍受整份配置的日历覆盖范围约束。 */
    public record DateRange(@JsonProperty("from") LocalDate from, @JsonProperty("through") LocalDate through) {
        public DateRange {
            if (from == null || through == null || through.isBefore(from))
                throw new IllegalArgumentException("time_pricing_date_range_invalid");
        }

        private boolean contains(LocalDate date) { return !date.isBefore(from) && !date.isAfter(through); }
        private boolean overlaps(DateRange other) { return !through.isBefore(other.from) && !other.through.isBefore(from); }
    }

    /** 空字段不限该维度；多个适用项为或关系，空列表适用于所有 LLM 配置。 */
    public record Scope(
            @JsonProperty("provider_id") @Schema(maxLength = MAXIMUM_SCOPE_IDENTIFIER_CODE_POINTS, nullable = true) String providerId,
            @JsonProperty("service_id") @Schema(maxLength = MAXIMUM_SCOPE_IDENTIFIER_CODE_POINTS, nullable = true) String serviceId,
            @JsonProperty("profile_version") @Schema(maxLength = MAXIMUM_SCOPE_IDENTIFIER_CODE_POINTS, nullable = true) String profileVersion,
            @JsonProperty("model_id") String modelId) {
        public Scope {
            for (String value : new String[]{providerId, serviceId, profileVersion, modelId}) {
                if (value != null && value.isBlank()) throw new IllegalArgumentException("time_pricing_scope_invalid");
            }
            for (String value : new String[]{providerId, serviceId, profileVersion}) {
                if (exceedsLength(value, MAXIMUM_SCOPE_IDENTIFIER_CODE_POINTS))
                    throw new IllegalArgumentException("time_pricing_scope_identifier_too_long");
            }
        }

        private boolean matches(String provider, String service, String profile, String model) {
            return (providerId == null || providerId.equals(provider))
                    && (serviceId == null || serviceId.equals(service))
                    && (profileVersion == null || profileVersion.equals(profile))
                    && (modelId == null || modelId.equals(model));
        }
    }

    public boolean appliesTo(String provider, String service, String profile, String model) {
        return applicableModels.isEmpty() || applicableModels.stream().anyMatch(scope -> scope.matches(provider, service, profile, model));
    }

    /** 必须传入原调用开始时间；缺失或日历未覆盖时不推测当前价。 */
    public Band classification(Instant startedAt) {
        if (startedAt == null) return Band.UNKNOWN;
        var local = startedAt.atZone(ZoneId.of(timeZone));
        LocalDate date = local.toLocalDate();
        if (date.isBefore(validFrom) || date.isAfter(validThrough)) return Band.UNKNOWN;
        if (offPeakDateRanges.stream().anyMatch(range -> range.contains(date))) return Band.OFF_PEAK;
        boolean peakDate = peakDateRanges.stream().anyMatch(range -> range.contains(date))
                || peakWeekdays.contains(local.getDayOfWeek());
        if (!peakDate) return Band.OFF_PEAK;
        LocalTime time = local.toLocalTime();
        return peakPeriods.stream().anyMatch(period -> !time.isBefore(period.start()) && time.isBefore(period.end()))
                ? Band.PEAK : Band.OFF_PEAK;
    }

    public BigDecimal multiplier(Band band) {
        return switch (band) {
            case PEAK -> peakMultiplier;
            case OFF_PEAK -> offPeakMultiplier;
            case UNKNOWN -> null;
        };
    }

    /** 价格回执包含完整配置的摘要；来源元数据变化同样产生不同身份。 */
    public String snapshotKey() {
        try {
            return TokenHash.sha256Hex(SNAPSHOT_WRITER.writeValueAsString(this));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("time_pricing_snapshot_encoding_failed", failure);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("time_pricing_required_collection_missing");
        return List.copyOf(values);
    }

    private static boolean exceedsLength(String value, int maximumCodePoints) {
        return value != null && value.codePointCount(0, value.length()) > maximumCodePoints;
    }

    private static void validateRanges(List<DateRange> ranges, LocalDate from, LocalDate through) {
        for (int index = 0; index < ranges.size(); index++) {
            DateRange left = ranges.get(index);
            if (left.from().isBefore(from) || left.through().isAfter(through))
                throw new IllegalArgumentException("time_pricing_date_range_outside_coverage");
            for (int next = index + 1; next < ranges.size(); next++) {
                if (left.overlaps(ranges.get(next))) throw new IllegalArgumentException("time_pricing_date_ranges_overlap");
            }
        }
    }

    private static BigDecimal normalizeMultiplier(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        BigDecimal normalized = stripped.setScale(Math.max(0, stripped.scale()));
        if (normalized.signum() < 0 || normalized.scale() > 12 || normalized.compareTo(MAXIMUM_MULTIPLIER) > 0)
            throw new IllegalArgumentException("time_pricing_multiplier_invalid");
        return normalized;
    }

    /** 星期使用明确名称，不接受枚举序号。 */
    public static final class WeekdayReader extends StdDeserializer<DayOfWeek> {
        public WeekdayReader() { super(DayOfWeek.class); }

        @Override public DayOfWeek deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String text = parser.getText();
                for (DayOfWeek day : DayOfWeek.values()) {
                    if (day.name().equals(text)) return day;
                }
            }
            return context.reportInputMismatch(DayOfWeek.class, "星期必须为MONDAY至SUNDAY的名称");
        }
    }

    /** 倍率以字符串传输，拒绝 JSON 浮点数和科学记数，避免修改配置时损失精度。 */
    public static final class MultiplierReader extends StdDeserializer<BigDecimal> {
        public MultiplierReader() { super(BigDecimal.class); }

        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String text = parser.getText();
                if (text.length() <= 25 && text.matches("^(0|[1-9][0-9]*)(\\.[0-9]{1,12})?$")) {
                    BigDecimal value = new BigDecimal(text);
                    if (value.compareTo(MAXIMUM_MULTIPLIER) <= 0) return normalizeMultiplier(value);
                }
            }
            return context.reportInputMismatch(BigDecimal.class, "倍率必须为0至999999999999.999999999999的十进制字符串，最多12位小数");
        }
    }

    /** BigDecimal.toString会把小倍率写成科学记数；API统一使用普通十进制。 */
    public static final class MultiplierWriter extends StdScalarSerializer<BigDecimal> {
        public MultiplierWriter() { super(BigDecimal.class); }

        @Override public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(value.toPlainString());
        }
    }
}
