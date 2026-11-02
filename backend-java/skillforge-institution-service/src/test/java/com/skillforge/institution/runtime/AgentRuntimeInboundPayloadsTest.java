package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 入口 JSON 必须保留结构化对象，不能把 Map 打成 Java toString。 */
class AgentRuntimeInboundPayloadsTest {

    @Test
    void requestTraceDoesNotChangeBusinessPayloadFingerprint() {
        assertEquals(AgentRuntimeInboundPayloads.inputText("hello", Map.of("trace_id", "first", "span_id", "a")),
                AgentRuntimeInboundPayloads.inputText("hello", Map.of("trace_id", "retry", "span_id", "b")));
    }

    @Test
    void encodesNestedMapsAndListsAsJson() {
        String json = AgentRuntimeInboundPayloads.object(
                "action", "approve",
                "context", Map.of("choice", "yes", "count", 2),
                "execution_id", "run:1");
        assertTrue(json.contains("\"action\":\"approve\""));
        assertTrue(json.contains("\"choice\":\"yes\""));
        assertTrue(json.contains("\"count\":2"));
        assertTrue(json.contains("\"execution_id\":\"run:1\""));
        assertTrue(!json.contains("choice=yes"));
    }

    @Test
    void encodesInputTextAndRejectsEmptyObjects() {
        assertEquals("{\"content\":[{\"text\":\"hello\",\"type\":\"text\"}],\"extraParameters\":{}}",
                AgentRuntimeInboundPayloads.inputText("hello", Map.of()));
        assertEquals("{\"content\":[{\"text\":\"hello\",\"type\":\"text\"}],\"extraParameters\":{\"buildingParkId\":\"clinic-1\"}}",
                AgentRuntimeInboundPayloads.inputText("hello",
                        Map.of("buildingParkId", "clinic-1")));
        assertThrows(IllegalArgumentException.class, AgentRuntimeInboundPayloads::object);
        assertEquals("{\"items\":[1,\"a\"]}", AgentRuntimeInboundPayloads.object("items", List.of(1, "a")));
    }
}
