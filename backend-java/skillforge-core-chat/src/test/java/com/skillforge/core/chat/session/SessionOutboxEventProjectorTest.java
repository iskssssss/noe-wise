package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 传输投影必须保留冻结 Outbox 的 cursor 和因果字段。 */
class SessionOutboxEventProjectorTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"process.started", "process.delta", "process.finished"})
    void allProcessTransportsPreserveOnlyPublicStageFields(String eventName) {
        var output = new SessionOutboxEntryDO();
        output.setSessionId("session-1");
        output.setEventId("event-1");
        output.setSessionSequence(8L);
        output.setCreatedAt(Instant.EPOCH);
        var stage = Map.of("id", "frozen-stage", "index", 2, "private", "not-public");
        output.setPayload(Map.of("event", eventName, "data", Map.of(
                "process_id", "process-1", "stage", stage,
                "presentation", Map.of("title", "查找已有成果", "summary", "", "results", java.util.List.of(), "references", java.util.List.of()))));

        var event = SessionOutboxEventProjector.project(SessionMailboxService.view(output));
        assertEquals(Map.of("id", "frozen-stage", "index", 2), event.data().get("stage"));
        assertFalse(event.data().toString().contains("not-public"));
        assertTrue(output.getPayload().toString().contains("not-public"));
        assertEquals(8L, event.sessionSequence());
        assertEquals(event, SessionOutboxEventProjector.project(SessionMailboxService.view(output)));
    }

    @Test
    void rejectsInvalidPublicStageIdentity() {
        var output = new SessionOutboxEntryDO();
        output.setSessionId("session-1");
        output.setEventId("event-1");
        for (var stage : java.util.List.of(Map.of("id", "", "index", 1), Map.of("id", "stage", "index", 0),
                Map.of("id", "stage", "index", 1.5), Map.of("id", "stage", "index", "1"))) {
            output.setPayload(Map.of("event", "process.delta", "data", Map.of("stage", stage)));
            assertThrows(IllegalArgumentException.class, () -> SessionOutboxEventProjector.project(SessionMailboxService.view(output)));
        }
    }

    @Test
    void projectsFrozenOutputIntoTransportParityShape() {
        SessionOutboxEntryDO output = new SessionOutboxEntryDO();
        output.setSessionId("session-1");
        output.setEventId("event-1");
        output.setSessionSequence(8L);
        output.setInputId("input-1");
        output.setRootInputId("root-1");
        output.setCausedByEventId("cause-1");
        output.setCreatedAt(Instant.parse("2026-08-24T12:00:00Z"));
        output.setPayload(Map.of("event", "message.delta", "data", Map.of("content", "hello")));

        SessionOutboxEventProjector.Event event = SessionOutboxEventProjector.project(SessionMailboxService.view(output));

        assertEquals("message.delta", event.event());
        assertEquals("event-1", event.eventId());
        assertEquals(8L, event.sessionSequence());
        assertEquals("input-1", event.data().get("input_id"));
        assertEquals("root-1", event.data().get("root_input_id"));
        assertEquals("cause-1", event.data().get("caused_by_event_id"));
        assertEquals(1, event.data().get("event_version"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.data().put("content", "mutated"));
    }

    @Test
    void replayRemovesPrivateReasoningWithoutChangingFrozenPayloadOrCursor() {
        var output = new SessionOutboxEntryDO();
        output.setSessionId("session-1");
        output.setEventId("event-1");
        output.setSessionSequence(8L);
        output.setCreatedAt(Instant.parse("2026-08-24T12:00:00Z"));
        String secret = "内部价格七万元";
        output.setPayload(Map.of("event", "process.delta", "data", Map.of(
                "type", "reasoning", "process_id", "process-1", "status", "failed",
                "summary", secret, "delta", secret, "content", secret,
                "detail", Map.of("reasoning_content", secret, "reason_code", "INVALID_DECISION"))));
        var event = SessionOutboxEventProjector.project(SessionMailboxService.view(output));
        assertFalse(event.data().toString().contains(secret));
        assertEquals("failed", event.data().get("status"));
        assertFalse(event.data().containsKey("detail"));
        assertTrue(event.data().containsKey("presentation"));
        assertFalse(event.data().toString().contains("INVALID_DECISION"));
        assertEquals("event-1", event.eventId());
        assertEquals(8L, event.sessionSequence());
        assertTrue(output.getPayload().toString().contains(secret));
    }

    @Test
    void rejectsMissingStableTransportIdentity() {
        SessionOutboxEntryDO output = new SessionOutboxEntryDO();
        output.setSessionId("session-1");
        output.setPayload(Map.of("event", "message.delta"));

        assertThrows(IllegalArgumentException.class, () -> SessionOutboxEventProjector.project(SessionMailboxService.view(output)));
    }
}
