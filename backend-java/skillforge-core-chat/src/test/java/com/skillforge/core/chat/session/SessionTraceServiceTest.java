package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.persistence.SessionTraceMapper;
import com.skillforge.core.chat.tracing.SessionTraceEventRow;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 公开事件内容保留资源归属与私有上下文脱敏，不再测试退役的五阶段分组。 */
class SessionTraceServiceTest {
    private final SessionTraceMapper traces = mock(SessionTraceMapper.class);
    private final SessionTraceService service = new SessionTraceService(traces, new ObjectMapper());

    @Test
    void readsCommittedContentOnlyWithinTheRequestedTenantAndSession() {
        when(traces.findEvent("7", "session-1", "event-8"))
                .thenReturn(event("execution.tool-result", "{\"summary\":\"已完成\"}"));

        var content = service.content("7", "session-1", "session-event:event-8").orElseThrow();

        assertEquals("FULL", content.availability());
        assertEquals("skillforge.test.v1", content.schemaId());
        assertEquals("session-event:event-8", content.contentRef());
        assertEquals(Map.of("summary", "已完成"), content.value());
        verify(traces).findEvent("7", "session-1", "event-8");
        assertTrue(service.content("8", "session-1", "session-event:event-8").isEmpty());
        assertTrue(service.content("7", "another-session", "session-event:event-8").isEmpty());
    }

    @Test
    void rejectsPrivateOrEmptyReferencesBeforeReadingAnyLedger() {
        assertTrue(service.content("7", "session-1", "private-ledger:event-8").isEmpty());
        assertTrue(service.content("7", "session-1", "session-event: ").isEmpty());
        assertTrue(service.content("7", "session-1", null).isEmpty());
        verifyNoInteractions(traces);
    }

    @Test
    void requiresExplicitResourceIdentityBeforeReadingContent() {
        assertThrows(IllegalArgumentException.class, () -> service.content(" ", "session-1", "session-event:event-8"));
        assertThrows(IllegalArgumentException.class, () -> service.content("7", "", "session-event:event-8"));
        verifyNoInteractions(traces);
    }

    @Test
    void publicTraceRedactsProviderReasoningBatchesAndKeepsOnlyBatchMetadata() {
        var reasoning = event("agent.model-reasoning", """
                {"attemptId":"attempt-9","batchOrdinal":"3",
                 "sourceSequenceFrom":"17","sourceSequenceThrough":"24",
                 "content":"private provider reasoning","delta":"private streamed delta",
                 "reasoningContent":"private legacy reasoning","reasoning_content":"private alternate reasoning"}
                """);
        reasoning.setPayloadSchemaId("skillforge.runtime.agent.model-reasoning.v2");
        when(traces.findEvent("7", "session-1", "reasoning-8")).thenReturn(reasoning);

        var content = service.content("7", "session-1", "session-event:reasoning-8").orElseThrow();

        assertEquals("REDACTED", content.availability());
        assertEquals("skillforge.runtime.agent.model-reasoning.v2", content.schemaId());
        assertEquals(Map.of("attemptId", "attempt-9", "batchOrdinal", "3",
                "sourceSequenceFrom", "17", "sourceSequenceThrough", "24"), content.value());
        assertFalse(content.value().toString().contains("private"));
    }

    @Test
    void removesReasoningFieldsFromModelResultWhileKeepingDeclaredOutputAndMetadata() {
        var result = event("agent.model-result", """
                {"operation":{"key":"operation-1"},
                 "output":{"schemaId":"application/json","canonicalJson":"{\\"answer\\":\\"已受理\\"}"},
                 "effectiveModelId":"model-a","usage":{"inputTokens":12,"outputTokens":8},
                 "finishReasonCode":"stop","reasoningContent":"private reasoning",
                 "reasoning_content":"private alternate reasoning"}
                """);
        result.setPayloadSchemaId("skillforge.runtime.agent.model-result.v3");
        when(traces.findEvent("7", "session-1", "model-8")).thenReturn(result);

        var content = service.content("7", "session-1", "session-event:model-8").orElseThrow();

        Map<?, ?> projection = (Map<?, ?>) content.value();
        assertEquals("REDACTED", content.availability());
        assertEquals("model-a", projection.get("effectiveModelId"));
        assertEquals("stop", projection.get("finishReasonCode"));
        assertEquals(Map.of("schemaId", "application/json", "canonicalJson", "{\"answer\":\"已受理\"}"), projection.get("output"));
        assertFalse(projection.containsKey("reasoningContent"));
        assertFalse(projection.containsKey("reasoning_content"));
        assertFalse(projection.toString().contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent.preparation-instructions", "agent.context-snapshot", "agent.context-view"})
    void privateContextCannotRevealPromptReasoningBlocksOrCompressedHistory(String eventType) {
        var context = event(eventType, """
                {"content":"private role instruction","profileRef":"role-1",
                 "modelRequest":{"messages":[{"reasoningContent":"private reasoning"}]},
                 "blocks":[{"content":{"canonicalJson":"private reasoning"}}],
                 "summary":"private summary","previews":["private preview"]}
                """);
        when(traces.findEvent("7", "session-1", "private-context")).thenReturn(context);

        var content = service.content("7", "session-1", "session-event:private-context").orElseThrow();

        assertEquals("REDACTED", content.availability());
        assertEquals(Map.of("redactionReason", "private_model_context"), content.value());
        assertFalse(content.value().toString().contains("private reasoning"));
        assertTrue(context.getPayloadCanonicalJson().contains("private reasoning"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent.model-reasoning", "agent.model-result"})
    void malformedPrivateModelMaterialDoesNotFallBackToRawContent(String eventType) {
        when(traces.findEvent("7", "session-1", "invalid"))
                .thenReturn(event(eventType, "private malformed model material"));

        var content = service.content("7", "session-1", "session-event:invalid").orElseThrow();

        assertEquals("REDACTED", content.availability());
        assertEquals(Map.of(), content.value());
    }

    @Test
    void invalidCommittedPublicPayloadPreservesAnExplicitReadFailure() {
        when(traces.findEvent("7", "session-1", "invalid"))
                .thenReturn(event("execution.tool-result", "invalid json"));

        assertThrows(IllegalStateException.class,
                () -> service.content("7", "session-1", "session-event:invalid"));
    }

    private static SessionTraceEventRow event(String eventType, String payload) {
        var row = new SessionTraceEventRow();
        row.setEventType(eventType);
        row.setPayloadSchemaId("skillforge.test.v1");
        row.setPayloadCanonicalJson(payload);
        return row;
    }
}
