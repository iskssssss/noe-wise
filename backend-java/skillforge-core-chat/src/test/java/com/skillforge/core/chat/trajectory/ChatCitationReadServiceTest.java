package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChatCitationReadServiceTest {
    private static final String MESSAGE = com.skillforge.runtime.session.AgentExecutionProgram.finishedEventId(new RunRef(new TenantId("7"), "run")).value();
    private static final String ID = "k0123456789abcdef";
    private final TrajectoryRepository repository = mock(TrajectoryRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ChatRuntimeHistory history = mock(ChatRuntimeHistory.class);
    private final SessionObservationAuthorizer authorizer = mock(SessionObservationAuthorizer.class);
    private final KnowledgeObservationAccess knowledge = mock(KnowledgeObservationAccess.class);
    private final ObservationReadAudit audit = mock(ObservationReadAudit.class);
    private final ChatCitationReadService service = new ChatCitationReadService(repository, sessions, history, authorizer, knowledge, audit,
            new PublicOutputGuard("private-artifacts"), new ObjectMapper(), 100000, 10000, 100);

    @Test void readsOnlyCitedItemFromAdoptedResultAndChecksCurrentSourceAuthority() {
        setup();
        var answer = service.read("session", MESSAGE, ID);
        assertEquals("引用正文", answer.text());
        assertEquals("v1", answer.sourceVersion());
        verify(authorizer).requireSession(any(), eq(7L), eq("session"));
        verify(knowledge).requireRead(any(), any(), any(), any(), eq(Set.of(new KnowledgeSourceVersion("10", "v1"))), eq(false));
        verify(audit).record(eq("7"), eq("2"), eq("chat.citation.read"), eq("session"), eq("session"), anyString(), anyMap());
        assertFalse(answer.text().contains("未引用正文"));
    }

    @Test void refusesUnknownCitationAndMissingOrAmbiguousAdoptedAttempt() {
        setup();
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, "kffffffffffffffff"));
        when(repository.citationAttempts(7, "run", "result")).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        when(repository.citationAttempts(7, "run", "result")).thenReturn(List.of(
                new TrajectoryRepository.CitationAttempt("request", "result", 1), new TrajectoryRepository.CitationAttempt("request2", "result", 1)));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verifyNoInteractions(knowledge, audit);
    }

    @Test void revokedKnowledgeAccessDoesNotReturnOrAuditContent() {
        setup();
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "denied"))
                .when(knowledge).requireRead(any(), any(), any(), any(), any(), eq(false));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verifyNoInteractions(audit);
    }

    @Test void rejectsDifferentFrozenSourceVersion() {
        setup();
        String altered = """
                {"agentId":"3","inputId":"input","rootInputId":"input","traceId":"trace","spanId":"span","status":"COMPLETED","text":"回答","evidence":[{"citationId":"k0123456789abcdef",
                "itemId":"item-1","sourceId":"10","sourceVersion":"v2","bundleId":"bundle",
                "version":"1","resultRef":"result"}]}
                """;
        when(repository.citationEvent(7, "session", MESSAGE))
                .thenReturn(new TrajectoryRepository.CitationEvent("run", "3", "input", "input", "trace", "span", null, altered));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verifyNoInteractions(knowledge, audit);
    }

    @Test void rejectsFinalMessageBlockedByPublicOutputGuard() {
        setup();
        String event = """
                {"agentId":"3","inputId":"input","rootInputId":"input","traceId":"trace","spanId":"span","status":"COMPLETED","text":"api_key=real-secret-value","evidence":[{"citationId":"k0123456789abcdef",
                "itemId":"item-1","sourceId":"10","sourceVersion":"v1","bundleId":"bundle",
                "version":"1","resultRef":"result"}]}
                """;
        when(repository.citationEvent(7, "session", MESSAGE))
                .thenReturn(new TrajectoryRepository.CitationEvent("run", "3", "input", "input", "trace", "span", null, event));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verify(repository, never()).citationAttempts(anyLong(), anyString(), anyString());
        verifyNoInteractions(knowledge, audit);
    }

    @Test void unprojectedOrRedactedCitationCannotReadPrivateCompletion() {
        setup();
        var current = sessions.require("7", "session");
        when(history.message(current, MESSAGE)).thenReturn(Optional.of(new ChatMessageRecord(MESSAGE, "assistant", "回答", Instant.EPOCH,
                java.util.Map.of("state", "completed", "citations", List.of()))));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verify(repository, never()).citationEvent(anyLong(), anyString(), anyString());
        verifyNoInteractions(knowledge, audit);
    }

    @Test void requestTraceMustMatchTheOriginalRun() {
        setup();
        var original = repository.citationEvent(7, "session", MESSAGE);
        when(repository.citationEvent(7, "session", MESSAGE)).thenReturn(new TrajectoryRepository.CitationEvent(
                "run", "3", "input", "other-input", "trace", "span", null, original.canonicalJson()));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verifyNoInteractions(knowledge, audit);
    }

    @Test void sessionDenialPreventsLookup() {
        setup();
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "denied"))
                .when(authorizer).requireSession(any(), eq(7L), eq("session"));
        assertThrows(ApiException.class, () -> service.read("session", MESSAGE, ID));
        verify(repository, never()).citationEvent(anyLong(), anyString(), anyString());
        verifyNoInteractions(knowledge, audit);
    }

    private void setup() {
        var scope = mock(SessionObservationScope.class);
        when(scope.tenantId()).thenReturn(7L);
        when(scope.actorId()).thenReturn("2");
        when(authorizer.tenant()).thenReturn(scope);
        var sessionView = mock(SessionView.class);
        when(sessions.require("7", "session")).thenReturn(sessionView);
        when(history.message(sessionView, MESSAGE)).thenReturn(Optional.of(new ChatMessageRecord(MESSAGE, "assistant", "回答", Instant.EPOCH,
                java.util.Map.of("state", "completed", "citations", List.of(java.util.Map.of("citation_id", ID,
                        "item_id", "item-1", "source_id", "10", "source_version", "v1"))))));
        String event = """
                {"agentId":"3","inputId":"input","rootInputId":"input","traceId":"trace","spanId":"span","status":"COMPLETED","text":"回答","evidence":[{"citationId":"k0123456789abcdef",
                "itemId":"item-1","sourceId":"10","sourceVersion":"v1","bundleId":"bundle",
                "version":"1","resultRef":"result"}]}
                """;
        when(repository.citationEvent(7, "session", MESSAGE)).thenReturn(new TrajectoryRepository.CitationEvent("run", "3", "input", "input", "trace", "span", null, event));
        when(repository.citationAttempts(7, "run", "result")).thenReturn(List.of(new TrajectoryRepository.CitationAttempt("request", "result", 1)));
        var tenant = new TenantId("7"); var session = new SessionRef(tenant, "session"); var agent = new AgentId("3");
        var input = new InputId("input"); var source = new KnowledgeSourceVersion("10", "v1");
        var budget = new KnowledgeBudget(2, 10, 1000, 10000, 1000); var now = Instant.parse("2026-10-25T00:00:00Z");
        var target = new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:mount"),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "capability", "version"), "10", 1,
                Set.of(source), Set.of(KnowledgeOperatorType.KEYWORD), Duration.ofSeconds(5));
        var request = new KnowledgeRequest("execution-knowledge:run:1", tenant, new RunRequester.EndUser(new EndUserId(23L)),
                agent, session, new TraceEnvelope("trace", "span", input, Optional.empty()), KnowledgeMode.RESEARCH,
                "intent", new KnowledgeOperation.Search("查询"), Set.of("10"), Set.of(source),
                Set.of(KnowledgeOperatorType.KEYWORD), budget, now, Optional.empty(), true);
        var call = new AgentExecutionCodec.KnowledgeCallRequest(target, request,
                new CapabilitySnapshotRef(session, input, "snapshot", 1), new EventId("event"),
                "call", "knowledge_query", new TypedPayload("application/json", "{}"));
        var first = new EvidenceItem("item-1", "10", "v1", KnowledgeOperatorType.KEYWORD,
                new TypedPayload("skillforge.runtime.knowledge.chunk.v1", "{\"text\":\"引用正文\"}"), List.of(), List.of(), 1, "tenant");
        var second = new EvidenceItem("item-2", "10", "v1", KnowledgeOperatorType.KEYWORD,
                new TypedPayload("skillforge.runtime.knowledge.chunk.v1", "{\"text\":\"未引用正文\"}"), List.of(), List.of(), 1, "tenant");
        var step = new KnowledgeStep("step", "查询", Set.of("10"), KnowledgeOperatorType.KEYWORD, List.of(), budget);
        var bundle = new EvidenceBundle("bundle", "execution-knowledge:run:1", new KnowledgePlan("plan", "execution-knowledge:run:1", List.of(step), "done", "planner"),
                List.of(first, second), List.of(), EvidenceSufficiency.PARTIAL,
                List.of(new KnowledgeTraceStep("step", "selection", List.of("item-1", "item-2"), List.of("item-1", "item-2"), 2, 1)), 1, now, Optional.empty());
        var limits = new CodecLimits(100000, 10000, 100); var codec = new AgentExecutionCodec(limits);
        var payloads = new SessionRuntimePayloads(); var core = new CoreRuntime(limits, payloads);
        when(repository.material(7, "request")).thenReturn(codec.encodeKnowledgeRequest(call));
        when(repository.material(7, "result")).thenReturn(codec.encodeOutcome(core.portalResults(payloads, payloads, payloads)
                .encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, new KnowledgeOutcome.Retrieved(bundle, new ResultRef("business-result")))));
    }
}
