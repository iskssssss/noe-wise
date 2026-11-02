package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Webhook worker 只能确认自身账本，并为网络失败保留重试责任。 */
class SessionWebhookPublicationServiceTest {

    private final SessionWebhookDeliveryLedger ledger = mock(SessionWebhookDeliveryLedger.class);
    private final SessionEventSubscriptionMapper subscriptions = mock(SessionEventSubscriptionMapper.class);
    private final SessionOutboxMapper outbox = mock(SessionOutboxMapper.class);
    private final SessionWebhookPublicationService service = new SessionWebhookPublicationService(
            ledger, subscriptions, outbox, Duration.ofMinutes(1), Duration.ofSeconds(5));

    @Test
    void noReadyDeliveryDoesNotCallTransportOrConfirmAnything() {
        when(ledger.claimNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(null);

        SessionWebhookPublicationResult result = service.publishNext(
                "7", 12L, "session-1", Instant.now(), (subscription, output) -> fail("must not deliver"));

        assertEquals(SessionWebhookPublicationResult.Status.NO_READY_DELIVERY, result.status());
        verify(ledger, never()).markDelivered(anyString(), any(), any());
    }

    @Test
    void successfulTransportConfirmsOnlyWebhookDelivery() {
        SessionWebhookDeliveryLedger.Claim delivery = delivery();
        when(ledger.claimNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(delivery);
        when(subscriptions.selectOne(any())).thenReturn(subscription());
        when(outbox.selectOne(any())).thenReturn(output());
        when(ledger.markDelivered("7", delivery, Instant.parse("2026-08-24T12:00:00Z"))).thenReturn(true);

        SessionWebhookPublicationResult result = service.publishNext("7", 12L, "session-1",
                Instant.parse("2026-08-24T12:00:00Z"),
                (subscription, output) -> assertEquals("event-1", output.getEventId()));

        assertEquals(SessionWebhookPublicationResult.Status.DELIVERED, result.status());
        verify(ledger).markDelivered("7", delivery, Instant.parse("2026-08-24T12:00:00Z"));
    }

    @Test
    void retryableTransportFailureReturnsTheClaimToWebhookQueue() {
        prepareReadyDelivery();
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        when(ledger.retry("7", delivery(), now.plusSeconds(5), "WEBHOOK_HTTP_503")).thenReturn(true);

        SessionWebhookPublicationResult result = service.publishNext("7", 12L, "session-1", now,
                (subscription, output) -> { throw new SessionWebhookTransportException("WEBHOOK_HTTP_503", true); });

        assertEquals(SessionWebhookPublicationResult.Status.RETRY_SCHEDULED, result.status());
        verify(ledger, never()).markUnresolved(anyString(), any(), anyString());
    }

    @Test
    void permanentTransportFailureBecomesUnresolvedWithoutRetry() {
        prepareReadyDelivery();
        when(ledger.markUnresolved("7", delivery(), "WEBHOOK_HTTP_401")).thenReturn(true);

        SessionWebhookPublicationResult result = service.publishNext("7", 12L, "session-1", Instant.now(),
                (subscription, output) -> { throw new SessionWebhookTransportException("WEBHOOK_HTTP_401", false); });

        assertEquals(SessionWebhookPublicationResult.Status.UNRESOLVED, result.status());
        verify(ledger, never()).retry(anyString(), any(), any(), anyString());
    }

    @Test
    void missingSubscriptionOrOutboxIsUnresolvedAndTransportIsRequired() {
        when(ledger.claimNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(delivery());
        when(subscriptions.selectOne(any())).thenReturn(null);
        when(outbox.selectOne(any())).thenReturn(output());
        when(ledger.markUnresolved("7", delivery(), "WEBHOOK_DELIVERY_SOURCE_MISSING")).thenReturn(true);

        SessionWebhookPublicationResult result = service.publishNext("7", 12L, "session-1", Instant.now(),
                (subscription, output) -> fail("missing subscription must not send"));

        assertEquals(SessionWebhookPublicationResult.Status.UNRESOLVED, result.status());
        assertThrows(IllegalArgumentException.class,
                () -> service.publishNext("7", 12L, "session-1", Instant.now(), null));
    }

    @Test
    void transportExceptionCarriesDefaultCodeAndOriginalCause() {
        IllegalStateException cause = new IllegalStateException("network");
        SessionWebhookTransportException failure =
                new SessionWebhookTransportException(null, true, cause);

        assertEquals("WEBHOOK_DELIVERY_FAILED", failure.errorCode());
        assertEquals(cause, failure.getCause());
        org.junit.jupiter.api.Assertions.assertTrue(failure.retryable());
    }

    @Test
    void everyLateTransportOutcomeReportsClaimLostWithTheOriginalVersion() {
        prepareReadyDelivery();
        Instant now = Instant.parse("2026-08-24T12:00:00Z");

        assertEquals(SessionWebhookPublicationResult.Status.CLAIM_LOST,
                service.publishNext("7", 12L, "session-1", now, (target, output) -> { }).status());
        assertEquals(SessionWebhookPublicationResult.Status.CLAIM_LOST,
                service.publishNext("7", 12L, "session-1", now, (target, output) -> {
                    throw new SessionWebhookTransportException("WEBHOOK_HTTP_503", true);
                }).status());
        assertEquals(SessionWebhookPublicationResult.Status.CLAIM_LOST,
                service.publishNext("7", 12L, "session-1", now, (target, output) -> {
                    throw new SessionWebhookTransportException("WEBHOOK_HTTP_401", false);
                }).status());
        assertEquals(SessionWebhookPublicationResult.Status.CLAIM_LOST,
                service.publishNext("7", 12L, "session-1", now, (target, output) -> {
                    throw new IllegalStateException("transport failed");
                }).status());

        verify(ledger).markDelivered("7", delivery(), now);
        verify(ledger).retry("7", delivery(), now.plusSeconds(5), "WEBHOOK_HTTP_503");
        verify(ledger).markUnresolved("7", delivery(), "WEBHOOK_HTTP_401");
        verify(ledger).retry("7", delivery(), now.plusSeconds(5), "WEBHOOK_DELIVERY_FAILED");
    }

    private void prepareReadyDelivery() {
        when(ledger.claimNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(delivery());
        when(subscriptions.selectOne(any())).thenReturn(subscription());
        when(outbox.selectOne(any())).thenReturn(output());
    }

    private SessionWebhookDeliveryLedger.Claim delivery() {
        return new SessionWebhookDeliveryLedger.Claim(99L, 5L, 2);
    }

    private SessionEventSubscriptionDO subscription() {
        SessionEventSubscriptionDO subscription = new SessionEventSubscriptionDO();
        subscription.setId(12L);
        subscription.setStatus(SessionWebhookDeliveryLedger.ACTIVE);
        return subscription;
    }

    private SessionOutboxEntryDO output() {
        SessionOutboxEntryDO output = new SessionOutboxEntryDO();
        output.setId(5L);
        output.setEventId("event-1");
        output.setSessionId("session-1");
        return output;
    }

    private void fail(String message) {
        throw new AssertionError(message);
    }
}
