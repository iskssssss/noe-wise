package com.skillforge.core.chat.trajectory;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrajectoryListServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    @Test void filtersPublicFactsBeforeCountingAndPagingAndKeepsLiteralKeywords() {
        var mapper = mock(TrajectoryListMapper.class);
        var projection = mock(TrajectoryProjectionService.class);
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
        var authorizer = new SessionObservationAuthorizer(access);
        var candidates = IntStream.range(0, 50).mapToObj(index -> new TrajectoryListMapper.Candidate(
                7L, "session", "root-" + index, NOW.minusSeconds(index).getEpochSecond(), 0)).toList();
        when(mapper.readAt()).thenReturn(NOW);
        when(mapper.candidates(any())).thenAnswer(invocation -> {
            TrajectoryListMapper.Candidates query = invocation.getArgument(0);
            assertEquals(NOW.minusSeconds(7 * 86400L).getEpochSecond(), query.fromSecond());
            assertEquals(NOW.getEpochSecond(), query.toSecond());
            return candidates;
        });
        when(projection.summary(any(), any())).thenAnswer(invocation -> {
            TrajectoryQuery.Resource resource = invocation.getArgument(1);
            int index = Integer.parseInt(resource.rootInputId().substring(5));
            return summary(resource.rootInputId(), index % 2 == 0 ? "100%_匹配" : "其他输入", "实际输出", "120", NOW);
        });
        var reads = reads();
        when(reads.targets(null, 0, 100)).thenReturn(List.of(7L));
        var service = new TrajectoryListService(mapper, projection, authorizer, reads);
        var query = new TrajectoryListQuery(null, "trace", null, null, "model", "tool", "completed",
                null, null, 100L, 200L, "%_", "实际", 2, 20);
        var result = service.list(authorizer.platformAdmin(), query);
        assertEquals(25L, result.total());
        assertEquals(5, result.items().size());
        assertEquals("root-40", result.items().getFirst().rootInputId());
        assertEquals("root-48", result.items().getLast().rootInputId());
    }

    @Test void unknownOrRunningDurationsNeverMatchNumericRanges() {
        var query = new TrajectoryListQuery(null, null, null, null, null, null, null,
                null, null, 0L, 1000L, null, null, 1, 20);
        assertFalse(TrajectoryListService.matches(summary("unknown", "in", "out", null, NOW), query));
        assertFalse(TrajectoryListService.matches(summary("running", "in", "out", "10", null), query));
        assertTrue(TrajectoryListService.matches(summary("zero", "in", "out", "0", NOW), query));
        assertFalse(TrajectoryListService.matches(summary("large", "in", "out", "9007199254740993", NOW), query));
    }

    @Test void mergesTenantLocalBatchesInGlobalOrderBeforeFilteredPaging() {
        var mapper = mock(TrajectoryListMapper.class);
        var projection = mock(TrajectoryProjectionService.class);
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
        var authorizer = new SessionObservationAuthorizer(access);
        var reads = reads();
        when(reads.targets(null, 0, 100)).thenReturn(List.of(7L, 8L));
        when(mapper.readAt()).thenReturn(NOW);
        when(mapper.candidates(any())).thenAnswer(call -> {
            TrajectoryListMapper.Candidates query = call.getArgument(0);
            assertTrue(query.tenantId() == 7L || query.tenantId() == 8L);
            return IntStream.range((int) query.offset(), Math.min(105, (int) query.offset() + query.limit()))
                    .mapToObj(index -> new TrajectoryListMapper.Candidate(query.tenantId(), "session",
                            query.tenantId() + ":" + index, NOW.minusSeconds(index * 2L + query.tenantId() - 7).getEpochSecond(), 0))
                    .toList();
        });
        when(projection.summary(any(), any())).thenAnswer(call -> {
            TrajectoryQuery.Resource resource = call.getArgument(1);
            int index = Integer.parseInt(resource.rootInputId().split(":")[1]);
            return summary(resource.rootInputId(), index % 2 == 0 ? "match" : "other", "output", "1", NOW);
        });
        var service = new TrajectoryListService(mapper, projection, authorizer, reads);
        var query = new TrajectoryListQuery(null, null, null, null, null, null, null,
                null, null, null, null, "match", null, 6, 20);
        var result = service.list(authorizer.platformAdmin(), query);
        assertEquals(106, result.total());
        assertEquals(List.of("7:100", "8:100", "7:102", "8:102", "7:104", "8:104"),
                result.items().stream().map(TrajectoryView.Summary::rootInputId).toList());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(4)).candidates(any());
    }

    @Test void noVisibleSessionsMeansNoCandidateOrBodyReadsAndForeignTenantIsRejected() {
        var mapper = mock(TrajectoryListMapper.class);
        var projection = mock(TrajectoryProjectionService.class);
        var access = mock(FoundationAccess.class);
        var subject = new FoundationAccess.Subject(false, "7", "99");
        when(access.currentSubject()).thenReturn(subject);
        when(access.visibleSessionIds(subject, "read")).thenReturn(List.of());
        when(mapper.readAt()).thenReturn(NOW);
        var authorizer = new SessionObservationAuthorizer(access);
        var scope = authorizer.tenant();
        var service = new TrajectoryListService(mapper, projection, authorizer, reads());
        assertEquals(0L, service.list(scope, query(7L, 20)).total());
        verify(mapper, never()).candidates(any());
        verify(projection, never()).summary(any(), any());
        assertThrows(ApiException.class, () -> service.list(scope, query(8L, 20)));
        assertThrows(ApiException.class, () -> service.list(scope, query(7L, 21)));
    }

    private static TrajectoryListQuery query(Long tenant, int pageSize) {
        return new TrajectoryListQuery(tenant, null, null, null, null, null, null,
                null, null, null, null, null, null, 1, pageSize);
    }

    private static PlatformObservationAccess reads() {
        var reads = mock(PlatformObservationAccess.class);
        when(reads.read(anyLong(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        return reads;
    }

    private static TrajectoryView.Summary summary(String root, String input, String output,
                                                  String duration, Instant finished) {
        return new TrajectoryView.Summary("7", "机构", "session", root, "12", "Agent", input, output,
                "COMPLETED", "COMPLETED", NOW.minusSeconds(1), finished, duration,
                List.of("trace"), List.of("model"), List.of("tool"), 1, 1, 1, null, null);
    }
}
