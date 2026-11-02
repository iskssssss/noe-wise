package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionOutboxEntryTest {
    @Test void frozenOutputAndTransportKeepOriginalIdentityAndDeeplyCopyOpenPayload() {
        var items = new ArrayList<>(List.of("original"));
        var nested = new LinkedHashMap<String, Object>(); nested.put("items", items); nested.put("optional", null);
        var payload = new LinkedHashMap<String, Object>(); payload.put("event", "message.delta"); payload.put("data", nested);
        var output = SessionOutboxEntry.builder().tenantId(7L).sessionId("session").eventId("event")
                .sessionSequence(9L).createdAt(Instant.EPOCH).payload(payload)
                .firstMessageSequence(1L).firstMessageCreatedAt(Instant.EPOCH.minusSeconds(1)).build();
        var event = SessionOutboxEventProjector.project(output);
        items.add("changed"); nested.put("new", "changed"); payload.clear();

        assertEquals("event", event.eventId()); assertEquals(9L, event.sessionSequence());
        assertEquals(1L, output.getFirstMessageSequence());
        assertEquals(List.of("original"), event.data().get("items"));
        assertTrue(event.data().containsKey("optional")); assertNull(event.data().get("optional"));
        assertFalse(event.data().containsKey("new"));
        assertThrows(UnsupportedOperationException.class, () -> output.getPayload().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) output.getPayload().get("data")).clear());
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) event.data().get("items")).clear());
    }
}
