package com.skillforge.runtime.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DesktopProjectionTest {
    @Test void boundedReadMatchesFullHistoryIncludingSavedToolBeforeActionOrderAndOldConfirmation() {
        var full = history(false, 20); var window = window(full);
        var expected = DesktopProjection.bound(full.deepCopy());
        var actual = DesktopProjection.bound(window, 300, 150, 230);
        assertEquals(expected, actual);
        assertEquals(260, actual.path("omittedMessages").asInt());
        assertEquals(50, actual.path("omittedRuns").asInt());
        assertEquals(110, actual.path("omittedEvents").asInt());
        var events = actual.path("events");
        assertEquals("saved-tool-50", events.get(80).path("id").asText());
        assertEquals("action-19", events.get(119).path("id").asText());
        assertEquals("old-run-0", actual.path("pendingConfirmations").get(0).path("runId").asText());
    }

    @Test void byteClippingAddsToDatabaseOmissionsWithoutDoubleCounting() {
        var full = history(true, 100); var window = window(full);
        var expected = DesktopProjection.bound(full.deepCopy());
        var actual = DesktopProjection.bound(window, 300, 150, 310);
        assertEquals(expected, actual);
        assertEquals(300 - actual.path("messages").size(), actual.path("omittedMessages").asInt());
        assertEquals(150 - actual.path("runs").size(), actual.path("omittedRuns").asInt());
        assertEquals(310 - actual.path("events").size(), actual.path("omittedEvents").asInt());
        assertTrue(actual.path("omittedEvents").asInt() > 190, "Escaped UTF-8 output must exercise the final byte limiter");
        assertTrue(DesktopJson.write(actual).getBytes(StandardCharsets.UTF_8).length <= DesktopProjection.MAX_STATE_BYTES);
        assertEquals(full.path("pendingConfirmations"), actual.path("pendingConfirmations"));
    }

    private static ObjectNode history(boolean large, int actions) {
        var value = DesktopJson.object(); value.putObject("session").put("id", "session");
        var runs = value.putArray("runs"); var messages = value.putArray("messages"); var events = value.putArray("events");
        String body = large ? "\u0001".repeat(8000) : "body";
        for (int index = 0; index < 150; index++) {
            runs.addObject().put("id", "run-" + index).put("status", "COMPLETED");
            messages.addObject().put("role", "user").put("runId", "run-" + index).put("text", body);
            messages.addObject().put("role", "assistant").put("runId", "run-" + index).put("text", body);
        }
        for (int index = 0; index < 140; index++) addEvent(events, "process", "process-" + index, body);
        for (int index = 0; index < 70; index++) addEvent(events, "tool", "saved-tool-" + index, body);
        for (int index = 0; index < actions; index++) addEvent(events, "tool", "action-" + index, body);
        value.putArray("pendingConfirmations").addObject().put("runId", "old-run-0").put("waitingId", "original-wait")
                .put("command", "original command").put("workspace", "original workspace");
        return value;
    }

    private static void addEvent(ArrayNode values, String type, String id, String body) {
        values.addObject().put("type", type).put("id", id).put("body", body).put("detail", body).put("extra", body);
    }

    private static ObjectNode window(ObjectNode full) {
        var value = full.deepCopy();
        value.set("runs", tail((ArrayNode) full.required("runs"), 100));
        value.set("messages", tail((ArrayNode) full.required("messages"), 80));
        var process = DesktopJson.JSON.createArrayNode(); var tools = DesktopJson.JSON.createArrayNode();
        for (var event : full.required("events")) (event.path("type").asText().equals("tool") ? tools : process).add(event);
        var events = value.putArray("events");
        tail(process, 80).forEach(events::add); tail(tools, 40).forEach(events::add);
        return value;
    }

    private static ArrayNode tail(ArrayNode values, int count) {
        var result = DesktopJson.JSON.createArrayNode();
        for (int index = Math.max(0, values.size() - count); index < values.size(); index++) result.add(values.get(index).deepCopy());
        return result;
    }
}
