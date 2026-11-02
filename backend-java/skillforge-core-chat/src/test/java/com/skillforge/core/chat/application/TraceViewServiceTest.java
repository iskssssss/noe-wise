package com.skillforge.core.chat.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.application.BillingTraceFacts.Usage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.application.BillingTraceFacts.Invocation;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper.TraceEntry;
import com.skillforge.core.chat.tracing.TraceQuery;
import com.skillforge.core.chat.tracing.TraceView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TraceView 从独立权威账本组合、脱敏和多币种聚合测试。 */
class TraceViewServiceTest {

    private final BillingTraceQueryService billing = mock(BillingTraceQueryService.class);
    private final SessionOutboxMapper outbox = mock(SessionOutboxMapper.class);
    private final TraceViewService service = new TraceViewService(
            billing, outbox);

    @Test
    void queryCombinesRootInputTimelineAndUsage() {
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        var costBuilder = Usage.builder();
        costBuilder.eventId("usage-1");
        costBuilder.eventType("llm.chat");
        costBuilder.tokens(12L);
        costBuilder.promptTokens(8L);
        costBuilder.completionTokens(4L);
        costBuilder.usageSource(UsageSource.PROVIDER_REPORTED.name());
        costBuilder.totalAmountCents(3L);
        costBuilder.currency("CNY");
        costBuilder.occurredAt(now);
        costBuilder.traceId("trace-1");
        costBuilder.invocationId("invocation-1");
        costBuilder.attemptId("attempt-1");
        var cost = costBuilder.build();
        when(billing.usageForRootInput(7L, "root-1")).thenReturn(List.of(cost));

        var invocationBuilder = Invocation.builder();
        invocationBuilder.invocationId("invocation-1");
        invocationBuilder.attemptId("attempt-1");
        invocationBuilder.operationType("model.chat");
        invocationBuilder.status(ExternalInvocationStatus.SUCCEEDED.name());
        invocationBuilder.traceId("trace-1");
        invocationBuilder.startedAt(now);
        invocationBuilder.endedAt(now.plusMillis(10));
        var invocation = invocationBuilder.build();
        when(billing.invocationsForRootInput(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(invocation));

        var output = new TraceEntry("output-1", "message.delta", "trace-1", now, 1L, null);
        when(outbox.traceEntries(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(output));

        TraceView view = service.query("7", new TraceQuery("root-1",
                TraceQuery.Audience.USER, null, now.minusSeconds(1), now.plusSeconds(1)));

        assertEquals("root-1", view.rootInputId());
        assertEquals(3, view.timeline().size());
        assertEquals(1, view.usageSummary().calls());
        assertEquals("12", view.usageSummary().tokens());
        assertEquals(3L, view.usageSummary().amountByCurrency().get("CNY"));
        assertEquals(0, view.usageSummary().unknownUsageCalls());
        TraceView.TraceEntry attempt = view.timeline().stream()
                .filter(item -> "external_invocation".equals(item.sourceDomain()))
                .findFirst().orElseThrow();
        assertEquals(List.of("usage-1"), attempt.usageRefs());
        assertTrue(view.timeline().stream().anyMatch(item -> "session_outbox".equals(item.sourceDomain())));
    }

    @Test
    void largeTokenAggregatesRemainExactAndMissingOutputStaysUnknown() {
        var firstBuilder = Usage.builder();
        firstBuilder.eventId("large-1"); firstBuilder.eventType("llm.chat"); firstBuilder.promptTokens(Long.MAX_VALUE);
        firstBuilder.usageSource(UsageSource.PROVIDER_REPORTED.name()); firstBuilder.occurredAt(Instant.now());
        var first = firstBuilder.build();
        var secondBuilder = Usage.builder();
        secondBuilder.eventId("large-2"); secondBuilder.eventType("llm.chat"); secondBuilder.promptTokens(Long.MAX_VALUE);
        secondBuilder.usageSource(UsageSource.PROVIDER_REPORTED.name()); secondBuilder.occurredAt(Instant.now());
        var second = secondBuilder.build();
        when(billing.usageForRootInput(7L, "large-root")).thenReturn(List.of(first, second));
        when(billing.invocationsForRootInput(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());
        when(outbox.traceEntries(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());
        var result = service.query("7", new TraceQuery("large-root", TraceQuery.Audience.USER, null, null, null));
        assertEquals("18446744073709551614", result.usageSummary().promptTokens());
        org.junit.jupiter.api.Assertions.assertNull(result.usageSummary().completionTokens());
    }

    @Test
    void userAudienceHidesDeveloperCallPoint() {
        var costBuilder = Usage.builder();
        costBuilder.eventId("usage-1");
        costBuilder.eventType("request");
        costBuilder.occurredAt(Instant.now());
        costBuilder.callPoint("chat.request");
        var cost = costBuilder.build();
        when(billing.usageForRootInput(7L, "root-2")).thenReturn(List.of(cost));
        when(billing.invocationsForRootInput(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());
        when(outbox.traceEntries(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());

        TraceView view = service.query("7", new TraceQuery("root-2", TraceQuery.Audience.USER, null, null, null));

        Map<String, Object> detail = view.timeline().get(0).sanitizedDetail();
        assertFalse(detail.containsKey("callPoint"));
    }

    @Test
    void developerAudienceIncludesCallPointAndEmptySourcesAreSafe() {
        var costBuilder = Usage.builder();
        costBuilder.eventId("usage-1");
        costBuilder.eventType("request");
        costBuilder.occurredAt(Instant.now());
        costBuilder.callPoint("chat.request");
        var cost = costBuilder.build();
        when(billing.usageForRootInput(7L, "root-3")).thenReturn(List.of(cost));
        when(billing.invocationsForRootInput(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());
        when(outbox.traceEntries(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());

        TraceView view = service.query("7", new TraceQuery("root-3", TraceQuery.Audience.DEVELOPER, null, null, null));

        assertEquals("chat.request", view.timeline().get(0).sanitizedDetail().get("callPoint"));
        assertTrue(view.unresolvedItems().isEmpty());
    }

    @Test
    void unknownUsageIsVisibleWithoutPretendingItsTokensAreZero() {
        var costBuilder = Usage.builder();
        costBuilder.eventId("usage-unknown");
        costBuilder.eventType("llm.chat");
        costBuilder.occurredAt(Instant.now());
        costBuilder.usageSource(UsageSource.UNKNOWN.name());
        var cost = costBuilder.build();
        when(billing.usageForRootInput(7L, "root-4")).thenReturn(List.of(cost));
        var unresolvedBuilder = Invocation.builder();
        unresolvedBuilder.attemptId("attempt-unknown");
        unresolvedBuilder.operationType("model.chat");
        unresolvedBuilder.status(ExternalInvocationStatus.UNRESOLVED.name());
        unresolvedBuilder.startedAt(cost.getOccurredAt());
        var unresolved = unresolvedBuilder.build();
        when(billing.invocationsForRootInput(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(unresolved));
        when(outbox.traceEntries(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());

        TraceView view = service.query("7", new TraceQuery("root-4", TraceQuery.Audience.DEVELOPER,
                null, null, null));

        assertEquals(1, view.usageSummary().unknownUsageCalls());
        org.junit.jupiter.api.Assertions.assertNull(view.usageSummary().tokens());
        assertEquals(List.of("usage:usage-unknown", "external_invocation:attempt-unknown"), view.unresolvedItems());
        TraceView.TraceEntry usageEntry = view.timeline().stream()
                .filter(item -> "usage_ledger".equals(item.sourceDomain()))
                .findFirst().orElseThrow();
        assertEquals(UsageSource.UNKNOWN.name(), usageEntry.sanitizedDetail().get("usageSource"));
        assertFalse(usageEntry.sanitizedDetail().containsKey("tokens"));
    }

    @Test void outboxProjectionPreservesWindowOrderingAndAudienceRedaction() {
        Instant from = Instant.parse("2026-10-30T00:00:00Z");
        when(billing.usageForRootInput(7L, "root-window")).thenReturn(List.of());
        when(billing.invocationsForRootInput(7L, "root-window")).thenReturn(List.of());
        when(outbox.traceEntries(7L, "root-window")).thenReturn(List.of(
                new TraceEntry("earlier", "message.delta", null, from.minusNanos(1), 1L, "private-earlier"),
                new TraceEntry("z", "message.delta", "trace-z", from, 2L, "source-z"),
                new TraceEntry("a", "message.card", "trace-a", from, null, null),
                new TraceEntry("later", "message.delta", null, from.plusSeconds(1), 3L, "private-later"),
                new TraceEntry("undated", "message.delta", null, null, 4L, "private-undated")));
        var user = service.query("7", new TraceQuery("root-window", TraceQuery.Audience.USER,
                null, from, from.plusSeconds(1)));
        assertEquals(List.of("a", "z"), user.timeline().stream().map(TraceView.TraceEntry::sourceEventId).toList());
        assertEquals(Map.of("sessionSequence", 0L), user.timeline().getFirst().sanitizedDetail());
        assertEquals(Map.of("sessionSequence", 2L), user.timeline().getLast().sanitizedDetail());
        for (var audience : List.of(TraceQuery.Audience.DEVELOPER, TraceQuery.Audience.TENANT_ADMIN)) {
            var detail = service.query("7", new TraceQuery("root-window", audience, null, from, from.plusSeconds(1)));
            assertEquals(Map.of("sessionSequence", 0L, "sourceEventId", ""), detail.timeline().getFirst().sanitizedDetail());
            assertEquals(Map.of("sessionSequence", 2L, "sourceEventId", "source-z"), detail.timeline().getLast().sanitizedDetail());
        }
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.times(3)).traceEntries(7L, "root-window");
        org.mockito.Mockito.verifyNoMoreInteractions(outbox);
    }

}
