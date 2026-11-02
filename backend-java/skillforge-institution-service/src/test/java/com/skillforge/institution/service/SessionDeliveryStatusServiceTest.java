package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.DeliveryCounts;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 投递状态只归纳持久 Outbox 与 Webhook 账本，不从 payload 或客户端连接推断。 */
class SessionDeliveryStatusServiceTest {

    @Test
    void committedOutputIsReplayAvailableRatherThanDeliveredAndWebhookFailuresAreBounded() {
        SessionOutboxMapper mapper = mock(SessionOutboxMapper.class);
        SessionOutboxWebhookDeliveryDO newest = failure("event-2", 3, "WEBHOOK_422");
        when(mapper.deliveryCounts(7L, "session-1", "run-1")).thenReturn(new DeliveryCounts(2, 0, 1, 1, 0));
        when(mapper.latestDeliveryFailures(7L, "session-1", "run-1", 10)).thenReturn(List.of(newest));

        var result = new SessionDeliveryStatusService(mapper)
                .status("7", "session-1", "run-1", "completed");

        assertEquals("completed", result.runtimeStatus());
        assertEquals("replay_available", result.sse().status());
        assertEquals(2,result.sse().replayAvailableCount());
        assertEquals("failed", result.webhook().status());
        assertEquals(List.of("event-2"), result.failures().stream().map(value -> value.eventId()).toList());
    }

    @Test
    void absentPersistentFactsRemainUnknown() {
        SessionOutboxMapper mapper = mock(SessionOutboxMapper.class);
        when(mapper.deliveryCounts(anyLong(), anyString(), anyString())).thenReturn(new DeliveryCounts(0, 0, 0, 0, 0));
        when(mapper.latestDeliveryFailures(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());

        var result = new SessionDeliveryStatusService(mapper)
                .status("7", "session-1", "run-1", "running");

        assertEquals("unknown", result.sse().status());
        assertEquals("unknown", result.webhook().status());
    }

    private static SessionOutboxWebhookDeliveryDO failure(String eventId, int attempts, String errorCode) {
        SessionOutboxWebhookDeliveryDO value = new SessionOutboxWebhookDeliveryDO();
        value.setEventId(eventId);
        value.setAttempts(attempts);
        value.setErrorCode(errorCode);
        value.setUpdatedAt(Instant.parse("2026-09-25T00:00:00Z"));
        return value;
    }
}
