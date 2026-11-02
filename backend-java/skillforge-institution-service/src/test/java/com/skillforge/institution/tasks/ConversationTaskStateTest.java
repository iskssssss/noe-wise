package com.skillforge.institution.tasks;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationTaskStateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void emptyObservedValueIsPreservedWhileMissingOrNonTextValuesAreRejected() throws Exception {
        var patch = json.readTree("{\"facts\":[{\"key\":\"stderr\",\"value\":\"\",\"sourceEventId\":\"tool\",\"confirmed\":true}]}");
        var sources = List.of(source("tool", "agent.tool-result"));
        var result = ConversationTaskState.merge(json, null, patch, sources);
        assertEquals("", result.path("facts").path("stderr").path("value").textValue());
        assertEquals("tool", result.path("facts").path("stderr").path("sourceEventId").textValue());
        assertFalse(result.path("facts").path("stderr").path("confirmed").asBoolean());
        for (String value : List.of("null", "{}", "[]", "0", "false")) {
            var invalid = json.readTree("{\"facts\":[{\"key\":\"stderr\",\"value\":" + value + ",\"sourceEventId\":\"tool\"}]}");
            assertThrows(IllegalArgumentException.class, () -> ConversationTaskState.merge(json, null, invalid, sources));
        }
        var missing = json.readTree("{\"facts\":[{\"key\":\"stderr\",\"sourceEventId\":\"tool\"}]}");
        assertThrows(IllegalArgumentException.class, () -> ConversationTaskState.merge(json, null, missing, sources));
    }

    @Test void correctionReplacesOnlyNamedFactAndRetainsPendingUnlessExplicitlyCleared() throws Exception {
        var original = json.readTree("{\"facts\":{\"门店\":{\"value\":\"旧门店\"},\"客户\":{\"value\":\"甲\"}},\"pending\":[\"时间\"]}");
        var patch = json.readTree("{\"action\":\"update\",\"facts\":[{\"key\":\"门店\",\"value\":\"新门店\",\"sourceEventId\":\"input\",\"confirmed\":true}]}");
        var result = ConversationTaskState.merge(json, original, patch, List.of(source("input", "inbound.message")));
        assertEquals("新门店", result.path("facts").path("门店").path("value").asText());
        assertEquals("甲", result.path("facts").path("客户").path("value").asText());
        assertEquals("时间", result.path("pending").get(0).asText());
        assertEquals("旧门店", original.path("facts").path("门店").path("value").asText());
        var cleared = ConversationTaskState.merge(json, result, json.readTree("{\"pending\":[]}"), List.of());
        assertTrue(cleared.path("pending").isEmpty());
    }

    @Test void rejectsMissingSourceAndModelForgedConfirmation() throws Exception {
        var patch = json.readTree("{\"facts\":[{\"key\":\"时间\",\"value\":\"明天\",\"sourceEventId\":\"missing\"}]}");
        assertThrows(IllegalArgumentException.class, () -> ConversationTaskState.merge(json, null, patch, List.of()));
        var forged = json.readTree("{\"confirmation\":{\"approved\":true}}");
        assertThrows(IllegalArgumentException.class, () -> ConversationTaskState.merge(json, null, forged, List.of()));
    }

    @Test void toolObservationDoesNotBecomeUserConfirmation() throws Exception {
        var patch = json.readTree("{\"facts\":[{\"key\":\"推荐\",\"value\":\"下午\",\"sourceEventId\":\"tool\",\"confirmed\":true}]}");
        var result = ConversationTaskState.merge(json, null, patch, List.of(source("tool", "tool.result")));
        assertFalse(result.path("facts").path("推荐").path("confirmed").asBoolean());
    }

    @Test void validatedAgentHitlCorrectionKeepsUserConfirmationButOtherActionsDoNot() throws Exception {
        var patch = json.readTree("{\"facts\":[{\"key\":\"项目代号\",\"value\":\"FIX-1\",\"sourceEventId\":\"reply\",\"confirmed\":true}]}");
        var raw = source("reply", "inbound.action");
        var reply = new CommittedSessionEvent(raw.owner(), raw.inputId(), raw.runRef(), raw.executionRef(),
                raw.actionRef(), raw.delegationRef(), raw.eventId(), raw.sessionSequence(), raw.eventType(),
                raw.eventVersion(), raw.trace(), new TypedPayload(
                com.skillforge.institution.runtime.AgentRuntimeHostContracts.AGENT_HITL_RESPONSE_SCHEMA,
                "{\"checkpointRevision\":\"12\",\"pendingRef\":\"pending\",\"response\":\"纠正为FIX-1\"}"), raw.occurredAt());
        assertTrue(ConversationTaskState.merge(json, null, patch, List.of(reply))
                .path("facts").path("项目代号").path("confirmed").asBoolean());
        assertFalse(ConversationTaskState.merge(json, null, patch, List.of(raw))
                .path("facts").path("项目代号").path("confirmed").asBoolean());
    }

    private CommittedSessionEvent source(String id, String type) {
        var owner = SessionOwner.user(new SessionRef(new TenantId("tenant"), "session"), new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input");
        return new CommittedSessionEvent(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                new EventId(id), 1, type, "1", new TraceEnvelope("trace", "span", input, Optional.empty()),
                new TypedPayload("input.v1", "{}"), Instant.EPOCH);
    }
}
