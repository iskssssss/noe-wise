package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.mockito.ArgumentCaptor;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Webhook 账本必须独立于 SSE 发布状态，并按订阅内序列领取。 */
class SessionWebhookDeliveryLedgerTest {

    @Test void internalClaimStillRunsInsideTheConfiguredSpringTransactionProxy() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var target = new SessionWebhookDeliveryLedger(mock(SessionEventSubscriptionMapper.class),
                mock(SessionOutboxWebhookDeliveryMapper.class));
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var attributes = new org.springframework.transaction.annotation.ProxyTransactionManagementConfiguration()
                .transactionAttributeSource();
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager, attributes));

        var ledger = (SessionWebhookDeliveryLedger) proxy.getProxy();
        assertNull(ledger.claimNext("7", 12L, "session-1", Instant.EPOCH, Duration.ofMinutes(1)));

        verify(manager).getTransaction(any());
        verify(manager).commit(any());
        verify(manager, never()).rollback(any());
    }

    private final SessionEventSubscriptionMapper subscriptions = mock(SessionEventSubscriptionMapper.class);
    private final SessionOutboxWebhookDeliveryMapper deliveries = mock(SessionOutboxWebhookDeliveryMapper.class);
    private final SessionWebhookDeliveryLedger ledger = new SessionWebhookDeliveryLedger(subscriptions, deliveries);

    @Test
    void batchQueriesSubscriptionsOnceAndPreservesEveryOutputDeliveryResponsibility() {
        SessionEventSubscriptionDO first = new SessionEventSubscriptionDO();
        first.setId(12L);
        SessionEventSubscriptionDO second = new SessionEventSubscriptionDO();
        second.setId(13L);
        when(subscriptions.activeForSession(7L, "session-1")).thenReturn(List.of(first, second));

        ledger.enqueueBatch("7", List.of(output(5L, "event-1", 1L), output(6L, "event-2", 2L)));

        ArgumentCaptor<List<SessionOutboxWebhookDeliveryDO>> capture = ArgumentCaptor.forClass(List.class);
        verify(subscriptions).activeForSession(7L, "session-1");
        verify(deliveries).insertDeliveries(capture.capture());
        var rows = capture.getValue();
        assertEquals(List.of(12L, 13L, 12L, 13L), rows.stream().map(SessionOutboxWebhookDeliveryDO::getSubscriptionId).toList());
        assertEquals(List.of(5L, 5L, 6L, 6L), rows.stream().map(SessionOutboxWebhookDeliveryDO::getOutboxEventId).toList());
        assertEquals(List.of(1L, 1L, 2L, 2L), rows.stream().map(SessionOutboxWebhookDeliveryDO::getSessionSequence).toList());
        rows.forEach(row -> assertEquals(SessionWebhookDeliveryLedger.QUEUED, row.getStatus()));
    }

    @Test
    void emptyBatchDoesNotQuerySubscriptionsOrInsertDeliveries() {
        ledger.enqueueBatch("7", List.of());
        org.mockito.Mockito.verifyNoInteractions(subscriptions, deliveries);
    }

    @Test
    void batchRejectsForeignTenantOrSessionBeforeAnyDeliveryWrites() {
        SessionOutboxEntryDO foreignTenant = output(5L, "event-1", 1L);
        foreignTenant.setTenantId(8L);
        assertThrows(IllegalArgumentException.class, () -> ledger.enqueueBatch("7", List.of(foreignTenant)));
        SessionOutboxEntryDO foreignSession = output(6L, "event-2", 2L);
        foreignSession.setSessionId("other-session");
        assertThrows(IllegalArgumentException.class, () -> ledger.enqueueBatch("7",
                List.of(output(5L, "event-1", 1L), foreignSession)));
        org.mockito.Mockito.verifyNoInteractions(subscriptions, deliveries);
    }

    @Test
    void claimPassesTheLeaseCutoffAndReturnsTheImmutableClaim() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        var expected = new SessionWebhookDeliveryLedger.Claim(99L, 5L, 2);
        when(deliveries.claimNext(7L, 12L, "session-1", now, now.minusSeconds(60))).thenReturn(expected);

        var claimed = ledger.claimNext("7", 12L, "session-1", now, Duration.ofMinutes(1));

        assertEquals(expected, claimed);
        verify(deliveries).claimNext(7L, 12L, "session-1", now, now.minusSeconds(60));
    }

    @Test
    void deliveredAndRetryOnlyTransitionClaimedRows() {
        var claim = new SessionWebhookDeliveryLedger.Claim(99L, 5L, 2);
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        Instant availableAt = now.plusSeconds(60);
        when(deliveries.markDelivered(7L, claim, now)).thenReturn(1);
        when(deliveries.retry(eq(7L), eq(claim), eq(availableAt), eq("WEBHOOK_FAILED"), any()))
                .thenReturn(1);

        assertEquals(true, ledger.markDelivered("7", claim, now));
        assertEquals(true, ledger.retry("7", claim, availableAt, "WEBHOOK_FAILED"));
    }

    @Test
    void discoversOnlyIdsFromActiveSubscriptionViewAndCanMarkUnresolved() {
        SessionEventSubscriptionDO valid = new SessionEventSubscriptionDO();
        valid.setId(12L);
        SessionEventSubscriptionDO malformed = new SessionEventSubscriptionDO();
        when(subscriptions.selectList(any(Wrapper.class))).thenReturn(List.of(valid, malformed));
        var claim = new SessionWebhookDeliveryLedger.Claim(99L, 5L, 2);
        when(deliveries.markUnresolved(eq(7L), eq(claim), eq("WEBHOOK_HTTP_401"), any())).thenReturn(1);

        assertEquals(List.of(12L), ledger.listActiveSubscriptionIds("7", 10));
        assertEquals(List.of(), ledger.listActiveSubscriptionIds("7", 0));
        SessionOutboxWebhookDeliveryDO session = new SessionOutboxWebhookDeliveryDO();
        session.setSessionId("session-1");
        SessionOutboxWebhookDeliveryDO malformedSession = new SessionOutboxWebhookDeliveryDO();
        when(deliveries.selectList(any(Wrapper.class))).thenReturn(List.of(session, malformedSession));
        assertEquals(List.of("session-1"), ledger.listSessionsWithPendingDelivery("7", 12L, 10));
        assertEquals(List.of(), ledger.listSessionsWithPendingDelivery("7", 12L, 0));
        assertEquals(true, ledger.markUnresolved("7", claim, "WEBHOOK_HTTP_401"));
    }

    private SessionOutboxEntryDO output(long id, String eventId, long sequence) {
        SessionOutboxEntryDO output = new SessionOutboxEntryDO();
        output.setId(id);
        output.setTenantId(7L);
        output.setSessionId("session-1");
        output.setEventId(eventId);
        output.setSessionSequence(sequence);
        return output;
    }

}
