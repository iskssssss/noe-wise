package com.skillforge.core.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.time.BusinessTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class BusinessTimeWebConfigurationTest {
    @Test
    void outputsBeijingOffsetWithoutChangingInstantOrPersistenceMapper() throws Exception {
        ObjectMapper original = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Instant instant = Instant.parse("2026-12-31T16:00:00.123Z");
        for (String zone : new String[] {"UTC", "America/Los_Angeles", "Asia/Shanghai"}) {
            ObjectMapper http = BusinessTimeWebConfiguration.httpMapper(original.copy()
                    .setTimeZone(TimeZone.getTimeZone(zone)));
            assertThat(http.writeValueAsString(instant)).isEqualTo("\"2027-01-01T00:00:00.123+08:00\"");
            assertThat(http.readValue(http.writeValueAsString(instant), Instant.class)).isEqualTo(instant);
            assertThat(http.writeValueAsString(OffsetDateTime.parse("2026-12-31T11:00:00-05:00")))
                    .isEqualTo("\"2027-01-01T00:00:00+08:00\"");
            assertThat(http.writeValueAsString(LocalDateTime.parse("2027-01-01T00:00:00")))
                    .isEqualTo("\"2027-01-01T00:00:00+08:00\"");
            assertThat(http.writeValueAsString(LocalDate.parse("2027-01-01"))).isEqualTo("\"2027-01-01\"");
            assertThatThrownBy(() -> http.readValue("\"2027-01-01T00:00:00\"", Instant.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
        assertThat(original.writeValueAsString(instant)).isEqualTo("\"2026-12-31T16:00:00.123Z\"");
    }

    @Test
    void serializesWorkflowCallbackDeadlineInstantUsingConfiguredBeijingOffset() throws Exception {
        ObjectMapper original = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ObjectMapper http = BusinessTimeWebConfiguration.httpMapper(original);
        Instant deadline = Instant.parse("2026-12-31T16:00:00Z");

        String json = http.writeValueAsString(java.util.Map.of(
                "data", java.util.Map.of("deadline_at", deadline)));

        assertThat(json).contains("\"deadline_at\":\"2027-01-01T00:00:00+08:00\"");
    }

    @Test
    void monthWindowsUseBeijingMidnightIncludingLeapYearAndYearBoundary() {
        assertThat(BusinessTime.startOfMonth(Instant.parse("2026-12-31T16:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-31T16:00:00Z"));
        assertThat(BusinessTime.startOfMonth(Instant.parse("2026-12-31T15:59:59Z")))
                .isEqualTo(Instant.parse("2026-11-30T16:00:00Z"));
        assertThat(BusinessTime.startOfMonth(Instant.parse("2024-02-29T15:59:59Z")))
                .isEqualTo(Instant.parse("2024-01-31T16:00:00Z"));
        assertThat(BusinessTime.startOfMonth(Instant.parse("2024-02-29T16:00:00Z")))
                .isEqualTo(Instant.parse("2024-02-29T16:00:00Z"));
    }
}
