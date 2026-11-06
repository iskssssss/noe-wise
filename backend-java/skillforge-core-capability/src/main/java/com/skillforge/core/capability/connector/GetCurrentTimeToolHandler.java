package com.skillforge.core.capability.connector;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 返回平台可信的当前时间。 */
@Component
public class GetCurrentTimeToolHandler implements PlatformToolHandler {

    @Override
    public String toolName() {
        return "get_current_time";
    }

    @Override
    public boolean isError(Map<String, Object> result) {
        return Boolean.FALSE.equals(result.get("success"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        try {
            String timezone = args == null || args.get("timezone") == null
                    ? "Asia/Shanghai" : String.valueOf(args.get("timezone"));
            ZonedDateTime now = Instant.now().atZone(ZoneId.of(timezone));
            return Map.of("success", true, "timezone", now.getZone().getId(),
                    "iso_8601", now.toOffsetDateTime().toString(), "epoch_millis", now.toInstant().toEpochMilli());
        } catch (RuntimeException e) {
            return Map.of("success", false, "error", "timezone 必须是有效的 IANA 时区");
        }
    }
}
