package com.skillforge.core.chat.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.integration.publicread.RuntimeCompletedAnswerReader;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.chat.session.SessionTraceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionObservationServiceTest {

    @Test
    void typedTraceUsesActualActionKindsAndAlreadyPublicEventSources() {
        SessionObservationRepository repository = mock(SessionObservationRepository.class);
        Instant time = Instant.parse("2026-10-09T08:00:00Z");
        when(repository.find(any(), anyLong(), any())).thenReturn(Optional.of(session()));
        when(repository.readAt()).thenReturn(time);
        when(repository.traceUpperBound(7L, "session-1", "root-1")).thenReturn(6L);
        when(repository.trace(7L, "session-1", "root-1", 0, 6, 51)).thenReturn(List.of(
                publicEvent(1, "execution.model-result", "MODEL", time),
                publicEvent(2, "execution.tool-result", "TOOL", time),
                publicEvent(3, "execution.waiting", "WORKFLOW", time),
                publicEvent(4, "execution.waiting", "DELEGATION", time),
                publicEvent(5, "execution.model-started", null, time),
                publicEvent(6, "execution.completed", null, time)));
        var service = new SessionObservationService(repository,
                mock(SessionTraceService.class), mock(ObservationReadAudit.class), new ObjectMapper(),
                mock(RuntimeCompletedAnswerReader.class), mock(PublicOutputGuard.class), mock(PlatformObservationAccess.class));

        var result = service.trace(
                new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99"),
                new SessionObservationQuery.Trace(7L, "session-1", "root-1", null, 50, null));

        assertEquals(List.of("MODEL", "TOOL", "WORKFLOW", "DELEGATION", "MODEL", "RUNTIME"),
                result.items().stream().map(SessionObservationView.TraceEvent::nodeType).toList());
        org.junit.jupiter.api.Assertions.assertTrue(result.items().stream()
                .allMatch(item -> "FULL".equals(item.contentAvailability())));
    }

    @Test
    void aggregatesProviderReportsPerAttemptWithoutDoubleCountingRevisions() {
        SessionObservationRepository repository = mock(SessionObservationRepository.class);
        Instant readAt = Instant.parse("2026-09-30T08:00:00Z");
        when(repository.find(any(), anyLong(), any())).thenReturn(Optional.of(session()));
        when(repository.readAt()).thenReturn(readAt);
        when(repository.usage(7L, "session-1", null, null)).thenReturn(List.of(
                usage("attempt-a", null, null, null, null, null,
                        "UNKNOWN", Instant.parse("2026-09-30T07:00:00Z")),
                usage("attempt-a", 100L, 10L, 80L, null, null,
                        "PROVIDER_REPORTED", Instant.parse("2026-09-30T07:10:00Z")),
                usage("attempt-b", 300L, 20L, 0L, null, null,
                        "PROVIDER_REPORTED", Instant.parse("2026-09-30T07:20:00Z")),
                usage("attempt-c", 200L, null, null, null, null,
                        "PROVIDER_REPORTED", Instant.parse("2026-09-30T07:30:00Z"))));
        SessionObservationService service = new SessionObservationService(repository,
                mock(SessionTraceService.class), mock(ObservationReadAudit.class), new ObjectMapper(),
                mock(RuntimeCompletedAnswerReader.class), mock(PublicOutputGuard.class), mock(PlatformObservationAccess.class));

        var result = service.usage(
                new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99"),
                new SessionObservationQuery.Usage(7L, "session-1", null, null, 50));

        assertEquals("PARTIAL", result.coverage().status());
        assertEquals(3, result.coverage().totalAttempts());
        assertEquals(2, result.coverage().reportedAttempts());
        assertEquals(1, result.coverage().partialAttempts());
        assertEquals("600", result.totals().promptTokens());
        assertEquals("30", result.totals().completionTokens());
        assertEquals("80", result.totals().cachedInputTokens());
        assertNull(result.totals().cacheWriteInputTokens());
        assertEquals("400", result.cache().pairedInputTokens());
        assertEquals("80", result.cache().pairedCachedInputTokens());
        assertEquals("0.2", result.cache().hitRatio());
        assertEquals("AVAILABLE", result.cache().availability());
        assertEquals(2, result.cache().pairedAttemptCount());
        assertEquals(3, result.cache().eligibleAttemptCount());
        assertEquals(List.of("REPORTED", "REPORTED", "PARTIAL"),
                result.attempts().stream().map(SessionObservationView.UsageAttempt::reportStatus).toList());
        org.junit.jupiter.api.Assertions.assertTrue(result.attempts().stream().noneMatch(SessionObservationView.UsageAttempt::diagnosticAvailable));
        org.junit.jupiter.api.Assertions.assertTrue(result.attempts().stream().allMatch(attempt ->
                "session-1".equals(attempt.sourceSessionId()) && "root-1".equals(attempt.sourceRootInputId())));
    }

    private static SessionObservationRepository.UsageRow usage(
            String attemptId, Long prompt, Long completion, Long cached, Long cacheWrite,
            Long reasoning, String source, Instant ingestedAt) {
        long sequence = attemptId.charAt(attemptId.length() - 1) - 'a' + 1L;
        return new SessionObservationRepository.UsageRow(sequence, attemptId, "invocation-" + attemptId,
                "node-" + attemptId, "run-1", "provider", "model", "model.chat", source,
                prompt, completion, cached, cacheWrite, reasoning, ingestedAt, false,"session-1","root-1");
    }

    private static SessionObservationRepository.TraceRow publicEvent(
            long sequence, String type, String kind, Instant time) {
        return new SessionObservationRepository.TraceRow(sequence, "input-1", "root-1", "run-1",
                null, "action-" + sequence, "event-" + sequence, type, "trace-1", null, "public-event.v1",
                "{\"summary\":\"安全公开概要\"}", kind, null, time);
    }

    private static SessionObservationRepository.SessionRow session() {
        Instant time = Instant.parse("2026-09-30T07:00:00Z");
        return new SessionObservationRepository.SessionRow(7L, "机构", "session-1", 8L,
                "用户", "AGENT", 9L, "Agent", null, null, 10L, "web", "网页",
                "NONE", "COMPLETED", null, 0, 1, null, null, null, null,
                time, time, time, 1, null, 0, 0);
    }
}
