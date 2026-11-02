package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.chat.contract.ContextReferenceLimits;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.SessionContextReferenceReader;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextReferenceToolHandlerTest {
    private static final String REFERENCE_ID = "context:" + "a".repeat(64);
    private final SessionContextReferenceReader reader = mock(SessionContextReferenceReader.class);
    private final ExecutionContextReferenceReader readers = mock(ExecutionContextReferenceReader.class);
    private final ContextReferenceToolHandler handler = new ContextReferenceToolHandler(readers);
    @org.junit.jupiter.api.BeforeEach void bindActualCallReader(){when(readers.forCall(any())).thenReturn(reader);}

    @Test void invalidHistoryLimitsAndMissingModeGiveSafeFieldGuidanceBeforeAnyReaderCall() {
        for (int limit : java.util.List.of(4000, 200)) {
            var failure = assertThrows(ContextReferenceToolHandler.ArgumentsRejected.class, () -> handler.execute(
                    Map.of("mode", "history", "query", "private-query", "limit", limit),
                    context(false, Instant.now().plusSeconds(30))));
            var errors = (Map<?, ?>) failure.feedback().get("field_errors");
            assertTrue(errors.get("limit").toString().contains("1 至 100"));
            assertTrue(errors.get("limit").toString().contains("扫描原事件数"));
            assertTrue(errors.get("limit").toString().contains(Integer.toString(ContextReferenceLimits.MAX_PAGE_CHARACTERS)));
            assertFalse(failure.feedback().toString().contains("private-query"));
        }
        var failure = assertThrows(ContextReferenceToolHandler.ArgumentsRejected.class, () -> handler.execute(
                Map.of("around_source_event_id", "private-event", "window", 22), context(false, Instant.now().plusSeconds(30))));
        var errors = (Map<?, ?>) failure.feedback().get("field_errors");
        assertTrue(errors.get("mode").toString().contains("mode=history"));
        assertFalse(failure.feedback().toString().contains("private-event"));
        assertEquals(false, failure.feedback().get("success"));
        assertEquals("context_reference_arguments_rejected", failure.feedback().get("error"));
        verifyNoInteractions(reader);
    }

    @Test void literalFindReturnsActualOffsetAndKeepsModelPageLimit() {
        var page = new SessionContextReferenceReader.Page(reference(true, 100_000), "机构17；人数117", 80_010, false);
        when(reader.find(run(), REFERENCE_ID, "机构17", 0, ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS))
                .thenReturn(new SessionContextReferenceReader.LocatedPage(page, 80_000, true));
        var result = handler.execute(Map.of("reference_id", REFERENCE_ID, "find", "机构17", "limit", 32768),
                context(false, Instant.now().plusSeconds(30)));
        assertEquals(80_000L, result.get("offset")); assertEquals(true, result.get("match_found"));
        assertEquals("机构17；人数117", result.get("content"));
        verify(reader).find(run(), REFERENCE_ID, "机构17", 0, ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS);
        verify(reader, never()).read(any(), anyString(), anyLong(), anyInt());
    }

    @Test void missingLiteralDoesNotClaimFullReadAndInvalidFindDoesNotRead() {
        when(reader.find(run(), REFERENCE_ID, "未提供机构", 0, 4096)).thenReturn(new SessionContextReferenceReader.LocatedPage(
                new SessionContextReferenceReader.Page(reference(true, 100), "", 100, true), 100, false));
        var result = handler.execute(Map.of("reference_id", REFERENCE_ID, "find", "未提供机构"),
                context(false, Instant.now().plusSeconds(30)));
        assertEquals(false, result.get("match_found")); assertEquals("", result.get("content"));
        assertEquals(100L, result.get("offset")); assertEquals(100L, result.get("next_offset"));
        reset(reader);
        for (Object literal : java.util.List.of(" ", "字".repeat(201), 17))
            assertThrows(ContextReferenceToolHandler.ArgumentsRejected.class, () -> handler.execute(
                    Map.of("reference_id", REFERENCE_ID, "find", literal), context(false, Instant.now().plusSeconds(30))));
        verifyNoInteractions(reader);
    }

    @Test void toolResultIsNotAContextReferenceAndIsRejectedBeforeReading() {
        var rejected = assertThrows(ContextReferenceToolHandler.ArgumentsRejected.class,
                () -> handler.execute(Map.of("reference_id", "tool-result:" + "a".repeat(64)),
                        context(false, Instant.now().plusSeconds(30))));
        assertEquals("context_reference_arguments_rejected", rejected.getMessage());
        verifyNoInteractions(reader);
    }

    @Test void forwardsUnicodePageWithoutChangingContentOrMetadata() {
        String content = "第一页🙂\n第二页𠮷";
        var reference = reference(false, 12_345);
        when(reader.read(run(), REFERENCE_ID, 3, 9))
                .thenReturn(new SessionContextReferenceReader.Page(reference, content, 12, false));

        var result = handler.execute(Map.of("reference_id", REFERENCE_ID, "offset", 3, "limit", 9),
                context(false, Instant.now().plusSeconds(30)));

        assertEquals(true, result.get("success"));
        assertEquals(REFERENCE_ID, result.get("reference_id"));
        assertEquals(content, result.get("content"));
        assertEquals(12L, result.get("next_offset"));
        assertEquals(false, result.get("end"));
        assertEquals(false, result.get("source_complete"));
        assertEquals(12_345L, result.get("source_characters"));
        verify(reader).read(run(), REFERENCE_ID, 3, 9);
    }

    @Test void defaultsToZeroOffsetAnd4096UnicodeCharacters() {
        var reference = reference(true, 2);
        when(reader.read(run(), REFERENCE_ID, 0, 4096))
                .thenReturn(new SessionContextReferenceReader.Page(reference, "完整", 2, true));

        var result = handler.execute(Map.of("reference_id", REFERENCE_ID),
                context(false, Instant.now().plusSeconds(30)));

        assertEquals("完整", result.get("content"));
        assertEquals(true, result.get("end"));
        assertEquals(true, result.get("source_complete"));
        verify(reader).read(run(), REFERENCE_ID, 0, 4096);
    }

    @Test void rejectsInvalidOffsetsLimitsAndUnexpectedArgumentsBeforeReading() {
        var invalid = java.util.List.of(
                Map.<String, Object>of("reference_id", REFERENCE_ID, "offset", -1),
                Map.<String, Object>of("reference_id", REFERENCE_ID, "offset", 1.5),
                Map.<String, Object>of("reference_id", REFERENCE_ID, "limit", 0),
                Map.<String, Object>of("reference_id", REFERENCE_ID, "limit", ContextReferenceLimits.MAX_PAGE_CHARACTERS + 1),
                Map.<String, Object>of("reference_id", REFERENCE_ID, "limit", Long.MAX_VALUE),
                Map.<String, Object>of("reference_id", REFERENCE_ID, "limit", "4096"),
                Map.<String, Object>of("reference_id", "https://example.com/raw"),
                Map.<String, Object>of("reference_id", REFERENCE_ID, "unknown", 1));

        for (var arguments : invalid) assertThrows(IllegalArgumentException.class,
                () -> handler.execute(arguments, context(false, Instant.now().plusSeconds(30))), arguments::toString);
        verifyNoInteractions(reader);
    }

    @Test void maximumRequestedLimitRemainsAnUpperBoundAndPreservesAllUnicodePages() {
        int limit = ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS;
        String source = "🙂".repeat(limit * 2) + "末尾𠮷";
        int total = source.codePointCount(0, source.length());
        var reference = reference(false, total);
        when(reader.read(eq(run()), eq(REFERENCE_ID), anyLong(), eq(limit))).thenAnswer(invocation -> {
            long offset = invocation.getArgument(2);
            long next = Math.min(total, offset + limit);
            int start = source.offsetByCodePoints(0, Math.toIntExact(offset));
            int end = source.offsetByCodePoints(start, Math.toIntExact(next - offset));
            return new SessionContextReferenceReader.Page(reference, source.substring(start, end), next, next == total);
        });
        var received = new StringBuilder();
        long offset = 0;
        boolean end;
        do {
            var result = handler.execute(Map.of("reference_id", REFERENCE_ID, "offset", offset,
                    "limit", ContextReferenceLimits.MAX_PAGE_CHARACTERS), context(false, Instant.now().plusSeconds(30)));
            String content = (String) result.get("content");
            assertTrue(content.codePointCount(0, content.length()) <= limit);
            assertEquals(offset + content.codePointCount(0, content.length()), result.get("next_offset"));
            assertEquals(false, result.get("source_complete"));
            assertEquals((long) total, result.get("source_characters"));
            received.append(content);
            offset = (long) result.get("next_offset");
            end = (boolean) result.get("end");
            assertEquals(offset == total, end);
        } while (!end);
        assertEquals(source, received.toString());
        verify(reader).read(run(), REFERENCE_ID, 0, limit);
        verify(reader).read(run(), REFERENCE_ID, limit, limit);
        verify(reader).read(run(), REFERENCE_ID, limit * 2L, limit);
    }

    @Test void appliesSameSafePageBudgetToSourceReadsAndRejectsOversizedReaderOutput() {
        int limit = ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS;
        String content = "🙂".repeat(limit);
        when(reader.readSource(run(), "source", 7, limit)).thenReturn(new SessionContextReferenceReader.Page(
                reference(true, limit + 8L), content, limit + 7L, false));
        var result = handler.execute(Map.of("source_event_id", "source", "offset", 7,
                "limit", ContextReferenceLimits.MAX_PAGE_CHARACTERS), context(false, Instant.now().plusSeconds(30)));
        assertEquals(content, result.get("content"));
        assertEquals(limit + 7L, result.get("next_offset"));
        assertEquals(false, result.get("end"));
        verify(reader).readSource(run(), "source", 7, limit);

        when(reader.read(run(), REFERENCE_ID, 0, limit)).thenReturn(new SessionContextReferenceReader.Page(
                reference(true, limit + 1L), content + "尾", limit + 1L, true));
        var rejected = assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("reference_id", REFERENCE_ID,
                "limit", ContextReferenceLimits.MAX_PAGE_CHARACTERS), context(false, Instant.now().plusSeconds(30))));
        assertEquals("context_reference_read_rejected", rejected.getMessage());
    }

    @Test void rejectsAReferenceThatIsNotBoundToTheCurrentSessionOwner() {
        var tenant = new TenantId("tenant");
        var otherOwner = SessionOwner.user(new SessionRef(tenant, "other-session"),
                new AgentId("agent"), new EndUserId(1));
        var other = new ContextReference(REFERENCE_ID, otherOwner, new EventId("source"),
                new ResultRef("sha256:" + "b".repeat(64)), 2, true);
        when(reader.read(run(), REFERENCE_ID, 0, 4096))
                .thenReturn(new SessionContextReferenceReader.Page(other, "越权", 2, true));

        var rejected = assertThrows(IllegalStateException.class, () -> handler.execute(
                Map.of("reference_id", REFERENCE_ID), context(false, Instant.now().plusSeconds(30))));

        assertEquals("context_reference_read_rejected", rejected.getMessage());
    }

    @Test void rejectsAReaderPageForAnotherReference() {
        String otherId = "context:" + "c".repeat(64);
        var other = new ContextReference(otherId, run().owner(), new EventId("source"),
                new ResultRef("sha256:" + "b".repeat(64)), 2, true);
        when(reader.read(run(), REFERENCE_ID, 0, 4096))
                .thenReturn(new SessionContextReferenceReader.Page(other, "错页", 2, true));

        var rejected = assertThrows(IllegalStateException.class, () -> handler.execute(
                Map.of("reference_id", REFERENCE_ID), context(false, Instant.now().plusSeconds(30))));

        assertEquals("context_reference_read_rejected", rejected.getMessage());
    }

    @Test void rejectsInconsistentUnicodePageMetadata() {
        var reference = reference(true, 2);
        when(reader.read(run(), REFERENCE_ID, 0, 2))
                .thenReturn(new SessionContextReferenceReader.Page(reference, "完整", 1, true));

        var rejected = assertThrows(IllegalStateException.class, () -> handler.execute(
                Map.of("reference_id", REFERENCE_ID, "limit", 2),
                context(false, Instant.now().plusSeconds(30))));

        assertEquals("context_reference_read_rejected", rejected.getMessage());
    }

    @Test void rejectsCancellationAndElapsedDeadlineBeforeReading() {
        assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("reference_id", REFERENCE_ID),
                context(true, Instant.now().plusSeconds(30))));
        assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("reference_id", REFERENCE_ID),
                context(false, Instant.now().minusSeconds(1))));
        verifyNoInteractions(reader);
    }

    @Test void readsSafeSourceFromHistoryAndRejectsMixedModes() {
        when(reader.readSource(run(), "source", 0, 4096))
                .thenReturn(new SessionContextReferenceReader.Page(reference(true, 2), "原文", 2, true));
        var result = handler.execute(Map.of("source_event_id", "source"), context(false, Instant.now().plusSeconds(30)));
        assertEquals("原文", result.get("content"));
        for (var args : java.util.List.of(Map.<String, Object>of("source_event_id", "source", "reference_id", REFERENCE_ID),
                Map.<String, Object>of("mode", "history", "source_event_id", "source"),
                Map.<String, Object>of("mode", "history", "limit", 101),
                Map.<String, Object>of("mode", "history", "history_run_id", ""))) {
            assertThrows(IllegalArgumentException.class, () -> handler.execute(args, context(false, Instant.now().plusSeconds(30))));
        }
    }

    @Test void pagedHistoryCanBeEmptyWithoutClaimingEndOfRun() {
        var query = new SessionContextReferenceReader.HistoryQuery(Optional.of("previous"), 10, 20,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 20);
        when(reader.history(run(), query))
                .thenReturn(new SessionContextReferenceReader.HistoryPage(java.util.List.of(), 30, false));
        var result = handler.execute(Map.of("mode", "history", "history_run_id", "previous", "after_sequence", 10),
                context(false, Instant.now().plusSeconds(30)));
        assertEquals(false, result.get("end"));
        assertEquals(30L, result.get("next_sequence"));
        assertEquals(java.util.List.of(), result.get("records"));
        verify(reader).history(run(), query);
    }

    @Test void forwardsLiteralHistoryQueryAndValidatesReturnedMatches() {
        var query = new SessionContextReferenceReader.HistoryQuery(Optional.empty(), 5, 25,
                Optional.of("sUcCeEdEd.*"), Optional.empty(), Optional.empty(), Optional.empty(), 20);
        var entry = new SessionContextReferenceReader.HistoryEntry("source", "run", 9,
                Instant.parse("2026-01-01T10:00:00Z"), "literal SUCCEEDED.* marker");
        when(reader.history(run(), query)).thenReturn(
                new SessionContextReferenceReader.HistoryPage(java.util.List.of(entry), 12, true));

        var result = handler.execute(Map.of("mode", "history", "query", "sUcCeEdEd.*",
                "after_sequence", 5, "limit", 25), context(false, Instant.now().plusSeconds(30)));

        assertEquals(12L, result.get("next_sequence"));
        assertEquals(1, ((java.util.List<?>) result.get("records")).size());
        var record = (Map<?, ?>) ((java.util.List<?>) result.get("records")).getFirst();
        assertEquals("literal SUCCEEDED.* marker", record.get("content"));
        assertEquals(false, record.get("content_truncated"));
        assertEquals(true, record.get("query_match_in_content"));
        verify(reader).history(run(), query);
    }

    @Test void historySearchValidatesFullContentAndReportsWhenTheMatchIsOutsideThePreview() {
        var query = new SessionContextReferenceReader.HistoryQuery(Optional.empty(), 0, 20,
                Optional.of("NEEDLE"), Optional.empty(), Optional.empty(), Optional.empty(), 20);
        var text = "🙂".repeat(128) + " needle";
        var entry = new SessionContextReferenceReader.HistoryEntry("source", "run", 1, Instant.EPOCH, text);
        when(reader.history(run(), query)).thenReturn(new SessionContextReferenceReader.HistoryPage(java.util.List.of(entry), 1, true));
        var result = handler.execute(Map.of("mode", "history", "query", "NEEDLE"), context(false, Instant.now().plusSeconds(30)));
        var record = (Map<?, ?>) ((java.util.List<?>) result.get("records")).getFirst();
        assertEquals("🙂".repeat(128), record.get("content"));
        assertEquals(true, record.get("content_truncated"));
        assertEquals(false, record.get("query_match_in_content"));
        assertEquals("source", record.get("source_event_id"));
        assertEquals(1L, result.get("next_sequence"));
        assertEquals(true, result.get("end"));

        when(reader.history(run(), query)).thenReturn(new SessionContextReferenceReader.HistoryPage(
                java.util.List.of(new SessionContextReferenceReader.HistoryEntry("source", "run", 1, Instant.EPOCH, "not a match")), 1, true));
        assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("mode", "history", "query", "NEEDLE"),
                context(false, Instant.now().plusSeconds(30))));
    }

    @Test void maximumHistoryWindowBoundsEveryUnicodePreviewWithoutDroppingTheAnchor() {
        var query = new SessionContextReferenceReader.HistoryQuery(Optional.empty(), 0, 20,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("event-51"), 50);
        var entries = java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i ->
                new SessionContextReferenceReader.HistoryEntry("event-" + i, "run", i, Instant.EPOCH,
                        "🙂".repeat(129))).toList();
        when(reader.history(run(), query)).thenReturn(new SessionContextReferenceReader.HistoryPage(entries, 101, true));
        var result = handler.execute(Map.of("mode", "history", "around_source_event_id", "event-51", "window", 50),
                context(false, Instant.now().plusSeconds(30)));
        var records = (java.util.List<?>) result.get("records");
        assertEquals(101, records.size());
        assertEquals("event-51", ((Map<?, ?>) records.get(50)).get("source_event_id"));
        assertEquals(101L, result.get("next_sequence"));
        assertEquals(true, result.get("end"));
        for (var item : records) {
            var record = (Map<?, ?>) item;
            assertEquals("🙂".repeat(40), record.get("content"));
            assertEquals(true, record.get("content_truncated"));
            assertFalse(record.containsKey("query_match_in_content"));
        }
    }

    @Test void forwardsBoundedAnchorWindowAndRejectsConflictingShapes() {
        var query = new SessionContextReferenceReader.HistoryQuery(Optional.of("previous"), 0, 20,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("anchor"), 3);
        var entries = java.util.List.of(
                new SessionContextReferenceReader.HistoryEntry("before", "previous", 8,
                        Instant.parse("2026-01-01T09:59:00Z"), "before"),
                new SessionContextReferenceReader.HistoryEntry("anchor", "previous", 10,
                        Instant.parse("2026-01-01T10:00:00Z"), "anchor"),
                new SessionContextReferenceReader.HistoryEntry("after", "previous", 13,
                        Instant.parse("2026-01-01T10:01:00Z"), "after"));
        when(reader.history(run(), query)).thenReturn(
                new SessionContextReferenceReader.HistoryPage(entries, 13, true));

        var result = handler.execute(Map.of("mode", "history", "history_run_id", "previous",
                "around_source_event_id", "anchor", "window", 3),
                context(false, Instant.now().plusSeconds(30)));

        assertEquals(true, result.get("end"));
        assertEquals(3, ((java.util.List<?>) result.get("records")).size());
        verify(reader).history(run(), query);

        var invalid = java.util.List.of(
                Map.<String, Object>of("mode", "history", "around_source_event_id", "anchor", "after_sequence", 0),
                Map.<String, Object>of("mode", "history", "around_source_event_id", "anchor", "query", "x"),
                Map.<String, Object>of("mode", "history", "around_source_event_id", "anchor",
                        "from_time", "2026-01-01T10:00:00Z"),
                Map.<String, Object>of("mode", "history", "window", 3),
                Map.<String, Object>of("mode", "history", "around_source_event_id", "anchor", "limit", 20),
                Map.<String, Object>of("mode", "history", "around_source_event_id", "anchor", "window", 0),
                Map.<String, Object>of("mode", "history", "around_source_event_id", "anchor", "window", 51));
        for (var arguments : invalid) assertThrows(IllegalArgumentException.class,
                () -> handler.execute(arguments, context(false, Instant.now().plusSeconds(30))));
    }

    @Test void rejectsBlankOversizedOrNonStringHistoryQueriesBeforeReading() {
        var invalid = java.util.List.of(
                Map.<String, Object>of("mode", "history", "query", " "),
                Map.<String, Object>of("mode", "history", "query", "🙂".repeat(201)),
                Map.<String, Object>of("mode", "history", "query", 1));
        for (var arguments : invalid) assertThrows(IllegalArgumentException.class,
                () -> handler.execute(arguments, context(false, Instant.now().plusSeconds(30))));
        verifyNoInteractions(reader);
    }

    @Test void forwardsTimezoneQualifiedHistoryBoundsAndRejectsInvalidRanges() {
        var from = Instant.parse("2026-01-01T10:00:00Z");
        var to = Instant.parse("2026-01-01T18:00:00Z");
        var query = new SessionContextReferenceReader.HistoryQuery(Optional.empty(), 0, 20,
                Optional.empty(), Optional.of(from), Optional.of(to), Optional.empty(), 20);
        var entry = new SessionContextReferenceReader.HistoryEntry("source", "run", 1, from, "content");
        when(reader.history(run(), query)).thenReturn(
                new SessionContextReferenceReader.HistoryPage(java.util.List.of(entry), 1, true));

        var result = handler.execute(Map.of("mode", "history", "from_time", "2026-01-01T18:00:00+08:00",
                "to_time", "2026-01-01T18:00:00Z"), context(false, Instant.now().plusSeconds(30)));

        assertEquals("2026-01-01T10:00:00Z", ((Map<?, ?>) ((java.util.List<?>) result.get("records"))
                .getFirst()).get("occurred_at"));
        verify(reader).history(run(), query);

        var invalid = java.util.List.of(
                Map.<String, Object>of("mode", "history", "from_time", "2026-01-01T10:00:00"),
                Map.<String, Object>of("mode", "history", "from_time", "2026-01-01T18:00:01Z",
                        "to_time", "2026-01-01T18:00:00Z"));
        for (var arguments : invalid) assertThrows(IllegalArgumentException.class,
                () -> handler.execute(arguments, context(false, Instant.now().plusSeconds(30))));
    }

    @Test void rejectsHistoryAndReferenceWhenPermissionIsRevokedBeforeReturn() {
        var history = new SessionContextReferenceReader.HistoryQuery(Optional.empty(), 0, 20,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 20);
        when(reader.history(run(), history)).thenReturn(new SessionContextReferenceReader.HistoryPage(java.util.List.of(), 0, true));
        when(readers.forCall(any())).thenReturn(reader).thenThrow(new IllegalStateException("revoked"));
        assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("mode", "history"),
                context(false, Instant.now().plusSeconds(30))));
        reset(readers);
        when(readers.forCall(any())).thenReturn(reader).thenThrow(new IllegalStateException("revoked"));
        when(reader.readSource(run(), "source", 0, 4096))
                .thenReturn(new SessionContextReferenceReader.Page(reference(true, 2), "原文", 2, true));
        assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("source_event_id", "source"),
                context(false, Instant.now().plusSeconds(30))));
    }

    @Test void rejectsCancellationOrExpiredDeadlineAfterFinalAuthorization() {
        var history = new SessionContextReferenceReader.HistoryQuery(Optional.empty(), 0, 20,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 20);
        when(reader.history(run(), history)).thenReturn(new SessionContextReferenceReader.HistoryPage(java.util.List.of(), 0, true));
        when(reader.readSource(run(), "source", 0, 4096))
                .thenReturn(new SessionContextReferenceReader.Page(reference(true, 2), "原文", 2, true));
        for (var args : java.util.List.of(Map.<String, Object>of("mode", "history"),
                Map.<String, Object>of("source_event_id", "source"))) {
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var original = context(false, Instant.now().plusSeconds(30));
            var call = new PlatformToolExecutionContext(new PortCallContext(original.run(), original.callContext().identity(),
                    new InvocationControl(Instant.now().plusSeconds(30), cancelled::get)), "invocation", "attempt");
            reset(readers);
            when(readers.forCall(any())).thenReturn(reader).thenAnswer(invocation -> { cancelled.set(true); return reader; });
            assertThrows(IllegalStateException.class, () -> handler.execute(args, call));

            var expiring = spy(context(false, Instant.now().plusSeconds(30)));
            doReturn(Instant.now().plusSeconds(30), Instant.EPOCH).when(expiring).deadlineAt();
            reset(readers);
            when(readers.forCall(any())).thenReturn(reader);
            assertThrows(IllegalStateException.class, () -> handler.execute(args, expiring));
        }
    }

    private ContextReference reference(boolean complete, long characters) {
        return new ContextReference(REFERENCE_ID, run().owner(), new EventId("source"),
                new ResultRef("sha256:" + "b".repeat(64)), characters, complete);
    }

    private PlatformToolExecutionContext context(boolean cancelled, Instant deadline) {
        RunIdentity run = run();
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(
                run.owner().sessionRef().tenantId(), run.owner().requireUser()), run.owner(), Set.of(), 1,
                new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(deadline, () -> cancelled)),
                "invocation", "attempt");
    }

    private RunIdentity run() {
        var tenant = new TenantId("tenant");
        var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
        return new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
    }
}
