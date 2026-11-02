package com.skillforge.core.billing.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.metering.AmountStatus;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.UsageAdjustment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageReconciliationServiceTest {

    private static final Instant FROM = Instant.parse("2026-08-25T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-25T01:00:00Z");

    /** 配置存储或解密故障是失败，不得归类为正常未配置。 */
    @Test
    void tenantConfigurationFailureFailsWithoutCallingFallbackAndKeepsCursor() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider provider = mock(UsageReconciliationProvider.class);
        when(provider.providerId()).thenReturn("http");
        TenantUsageReconciliationConfigService config = mock(TenantUsageReconciliationConfigService.class);
        when(config.resolveForProvider("7")).thenThrow(new IllegalStateException("config unavailable"));
        UsageReconciliationService service = new UsageReconciliationService(recorder,
                new UsageReconciliationProviderRegistry(List.of(provider)), config);

        for (var result : List.of(service.reconcile("7", FROM, TO, "cursor-1"),
                service.reconcileWindow("7", FROM, TO, "cursor-1"))) {
            assertEquals("failed", result.status());
            assertEquals(1, result.failed());
            assertEquals("cursor-1", result.nextCursor());
        }
        verify(provider, never()).pull(any(), any(), any(), any());
        verify(recorder, never()).recordAdjustment(any(), any());
    }

    @Test
    void missingProviderSkipsWithoutCallingTheLedger() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationService service = new UsageReconciliationService(recorder);

        UsageReconciliationService.Result result = service.reconcile("7", FROM, TO, "cursor-1");

        assertEquals("skipped", result.status());
        assertEquals("cursor-1", result.nextCursor());
        verify(recorder, never()).recordAdjustment(any(), any());
    }

    @Test
    void providerFactsBecomeAppendOnlyReconciliationEventsAndFailuresStayPerFact() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider provider = (tenant, from, to, cursor) ->
                new UsageReconciliationProvider.Batch(List.of(
                        new UsageReconciliationProvider.Fact("bill-1", 101L, AmountStatus.PRICED,
                                null, null, new BigDecimal("1.2"), BigDecimal.ONE, "fx-1", "CNY", Map.of()),
                        new UsageReconciliationProvider.Fact("bill-2", 102L, AmountStatus.PRICED,
                                null, null, new BigDecimal("2.3"), BigDecimal.ONE, "fx-1", "CNY", Map.of())),
                        "cursor-2");
        org.mockito.Mockito.doAnswer(invocation -> {
            UsageAdjustment adjustment = invocation.getArgument(1);
            if ("bill-2".equals(adjustment.eventId())) {
                throw new IllegalArgumentException("conflict");
            }
            return null;
        }).when(recorder).recordAdjustment(any(), any());
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcile("7", FROM, TO, null);

        assertEquals("failed", result.status());
        assertEquals(1, result.processed());
        assertEquals(1, result.failed());
        assertEquals("", result.nextCursor());
    }

    @Test
    void invalidWindowIsRejectedBeforeProviderAccess() {
        UsageReconciliationProvider provider = (tenant, from, to, cursor) -> null;
        UsageReconciliationService service = new UsageReconciliationService(mock(CostRecorder.class), provider);

        assertThrows(IllegalArgumentException.class,
                () -> service.reconcile("7", TO, FROM, null));
    }

    @Test
    void configuredTenantProviderIsSelectedWithoutCallingAnotherProvider() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider http = mock(UsageReconciliationProvider.class);
        UsageReconciliationProvider dashscope = mock(UsageReconciliationProvider.class);
        when(http.providerId()).thenReturn("http");
        when(dashscope.providerId()).thenReturn("dashscope");
        when(dashscope.pull(any(), any(), any(), any()))
                .thenReturn(new UsageReconciliationProvider.Batch(
                        List.of(fact("selected-fact", 101L, AmountStatus.PRICED, "1.2")), "selected"));
        TenantUsageReconciliationConfigService config = mock(TenantUsageReconciliationConfigService.class);
        when(config.resolveForProvider("7")).thenReturn(new TenantUsageReconciliationConfigService.ResolvedTenantConfig(
                "dashscope", "https://billing.example", null, null, Map.of()));
        UsageReconciliationService service = new UsageReconciliationService(recorder,
                new UsageReconciliationProviderRegistry(List.of(http, dashscope)), config);

        UsageReconciliationService.Result result = service.reconcile("7", FROM, TO, "cursor-1");

        assertEquals("completed", result.status());
        assertEquals("selected", result.nextCursor());
        verify(http, never()).pull(any(), any(), any(), any());
    }

    @Test
    void windowWalksMultiplePagesWithTheSameTimeWindowAndEmptyTerminalCursor() {
        CostRecorder recorder = mock(CostRecorder.class);
        List<String> cursors = new ArrayList<>();
        List<Instant> fromValues = new ArrayList<>();
        List<Instant> toValues = new ArrayList<>();
        UsageReconciliationProvider provider = (tenant, from, to, cursor) -> {
            cursors.add(cursor);
            fromValues.add(from);
            toValues.add(to);
            if (cursors.size() == 1) {
                return new UsageReconciliationProvider.Batch(
                        List.of(fact("bill-1", 101L, AmountStatus.PRICED, "1.2")), "cursor-2");
            }
            return new UsageReconciliationProvider.Batch(
                    List.of(fact("bill-2", 102L, AmountStatus.UNRESOLVED, null)), "");
        };
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, null);

        assertEquals("completed", result.status());
        assertEquals(2, result.processed());
        assertEquals(0, result.failed());
        assertEquals("", result.nextCursor());
        assertEquals(null, cursors.getFirst());
        assertEquals("cursor-2", cursors.get(1));
        assertEquals(List.of(FROM, FROM), fromValues);
        assertEquals(List.of(TO, TO), toValues);
        verify(recorder, org.mockito.Mockito.times(2)).recordAdjustment(any(), any());
    }

    @Test
    void emptyBatchFailsAndKeepsTheRequestCursor() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider provider = (tenant, from, to, cursor) ->
                new UsageReconciliationProvider.Batch(List.of(), "provider-next");
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, "cursor-1");

        assertEquals("failed", result.status());
        assertEquals(0, result.processed());
        assertEquals(1, result.failed());
        assertEquals("cursor-1", result.nextCursor());
        verify(recorder, never()).recordAdjustment(any(), any());
    }

    @Test
    void providerExceptionFailsAndKeepsTheRequestCursor() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider provider = (tenant, from, to, cursor) -> {
            throw new IllegalStateException("provider unavailable");
        };
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, "cursor-1");

        assertEquals("failed", result.status());
        assertEquals(0, result.processed());
        assertEquals(1, result.failed());
        assertEquals("cursor-1", result.nextCursor());
    }

    @Test
    void pageFactFailureFailsWithoutAdvancingOrPullingTheNextPage() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider provider = mock(UsageReconciliationProvider.class);
        when(provider.providerId()).thenReturn("test");
        when(provider.pull("7", FROM, TO, "cursor-1"))
                .thenReturn(new UsageReconciliationProvider.Batch(List.of(
                        fact("bill-failed", 101L, AmountStatus.PRICED, "1.2"),
                        fact("bill-success", 102L, AmountStatus.PRICED, "2.3")), "provider-next"));
        org.mockito.Mockito.doAnswer(invocation -> {
            UsageAdjustment adjustment = invocation.getArgument(1);
            if ("bill-failed".equals(adjustment.eventId())) {
                throw new IllegalArgumentException("账本写入失败");
            }
            return null;
        }).when(recorder).recordAdjustment(any(), any());
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, "cursor-1");

        assertEquals("failed", result.status());
        assertEquals(1, result.processed());
        assertEquals(1, result.failed());
        assertEquals("cursor-1", result.nextCursor());
        verify(provider).pull("7", FROM, TO, "cursor-1");
        verify(provider, never()).pull("7", FROM, TO, "provider-next");
    }

    @Test
    void stalledCursorFailsClosedBeforeASecondPull() {
        CostRecorder recorder = mock(CostRecorder.class);
        UsageReconciliationProvider provider = (tenant, from, to, cursor) ->
                new UsageReconciliationProvider.Batch(
                        List.of(fact("bill-1", 101L, AmountStatus.PRICED, "1.2")), cursor);
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, "cursor-1");

        assertEquals("failed", result.status());
        assertEquals(1, result.processed());
        assertEquals(1, result.failed());
        assertEquals("cursor-1", result.nextCursor());
    }

    @Test
    void cursorCycleFailsClosedAndKeepsTheCurrentRequestCursor() {
        CostRecorder recorder = mock(CostRecorder.class);
        List<String> cursors = new ArrayList<>();
        UsageReconciliationProvider provider = (tenant, from, to, cursor) -> {
            cursors.add(cursor);
            String next = cursor == null ? "cursor-1" : switch (cursor) {
                case "" -> "cursor-1";
                case "cursor-1" -> "cursor-2";
                default -> "cursor-1";
            };
            return new UsageReconciliationProvider.Batch(
                    List.of(fact("bill-" + cursors.size(), 100L + cursors.size(),
                            AmountStatus.PRICED, "1.2")), next);
        };
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, null);

        assertEquals("failed", result.status());
        assertEquals(3, result.processed());
        assertEquals(1, result.failed());
        assertEquals("cursor-2", result.nextCursor());
        assertEquals(null, cursors.getFirst());
        assertEquals(List.of("cursor-1", "cursor-2"), cursors.subList(1, 3));
    }

    @Test
    void maximumPageCountPreventsUnboundedProviderPulls() {
        CostRecorder recorder = mock(CostRecorder.class);
        List<String> cursors = new ArrayList<>();
        UsageReconciliationProvider provider = (tenant, from, to, cursor) -> {
            cursors.add(cursor);
            int page = cursors.size();
            return new UsageReconciliationProvider.Batch(
                    List.of(fact("bill-" + page, 100L + page, AmountStatus.PRICED, "1.2")),
                    "cursor-" + page);
        };
        UsageReconciliationService service = new UsageReconciliationService(recorder, provider);

        UsageReconciliationService.Result result = service.reconcileWindow("7", FROM, TO, null);

        assertEquals("failed", result.status());
        assertEquals(UsageReconciliationService.MAX_PAGES, result.processed());
        assertEquals(1, result.failed());
        assertEquals("cursor-" + UsageReconciliationService.MAX_PAGES, result.nextCursor());
        assertEquals(UsageReconciliationService.MAX_PAGES, cursors.size());
    }

    private static UsageReconciliationProvider.Fact fact(String eventId, long originalEventId,
                                                         AmountStatus amountStatus, String totalAmount) {
        return new UsageReconciliationProvider.Fact(eventId, originalEventId, amountStatus,
                null, null, totalAmount == null ? null : new BigDecimal(totalAmount),
                BigDecimal.ONE, "price-v1", "CNY", Map.of());
    }
}
