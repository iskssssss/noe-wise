package com.skillforge.core.capability.connector;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 解析 ISO 8601 时间并转换到目标 IANA 时区。 */
@Component
public class ConvertDatetimeToolHandler implements PlatformToolHandler {

    @Override
    public String toolName() {
        return "convert_datetime";
    }

    @Override
    public boolean isError(Map<String, Object> result) {
        return Boolean.FALSE.equals(result.get("success"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        try {
            String datetime = required(args, "datetime");
            String sourceTimezone = optional(args, "source_timezone", "Asia/Shanghai");
            String targetTimezone = optional(args, "target_timezone", "Asia/Shanghai");
            String outputFormat = optional(args, "output_format", "ISO_OFFSET_DATE_TIME");
            Instant instant = parse(datetime, sourceTimezone);
            ZonedDateTime converted = instant.atZone(ZoneId.of(targetTimezone));
            return Map.of("success", true, "timezone", converted.getZone().getId(),
                    "value", format(converted, outputFormat));
        } catch (DateTimeException e) {
            return Map.of("success", false, "error", "target_timezone 必须是有效的 IANA 时区");
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private Instant parse(String value, String sourceTimezone) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 没有 offset 时按 source_timezone 解释。
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续按本地日期时间解析。
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.of(sourceTimezone)).toInstant();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("datetime 必须是 ISO 8601 时间，source_timezone 必须是有效的 IANA 时区");
        }
    }

    private String format(ZonedDateTime value, String outputFormat) {
        return switch (outputFormat) {
            case "ISO_OFFSET_DATE_TIME" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
            case "ISO_LOCAL_DATE_TIME" -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
            case "ISO_LOCAL_DATE" -> DateTimeFormatter.ISO_LOCAL_DATE.format(value);
            case "EPOCH_MILLIS" -> String.valueOf(value.toInstant().toEpochMilli());
            default -> throw new IllegalArgumentException(
                    "output_format 必须是 ISO_OFFSET_DATE_TIME、ISO_LOCAL_DATE_TIME、ISO_LOCAL_DATE 或 EPOCH_MILLIS");
        };
    }

    private String required(Map<String, Object> args, String key) {
        String value = optional(args, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        return value;
    }

    private String optional(Map<String, Object> args, String key, String defaultValue) {
        Object value = args == null ? null : args.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }
}
