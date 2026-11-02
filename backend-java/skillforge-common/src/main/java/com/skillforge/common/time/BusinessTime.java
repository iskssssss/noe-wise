package com.skillforge.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 全平台业务日历统一使用北京时间，绝对时刻不做偏移。 */
public final class BusinessTime {
    public static final String ZONE_ID = "Asia/Shanghai";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private BusinessTime() { }

    public static Clock clock() {
        return Clock.system(ZONE);
    }

    /** 对外展示保留原时刻，输出明确的北京时间偏移。 */
    public static String format(Instant instant) {
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(ZONE));
    }

    public static Instant startOfMonth(Instant instant) {
        return instant.atZone(ZONE).toLocalDate().withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
    }
}
