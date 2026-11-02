package com.skillforge.core.chat.session;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 订阅维度的有序 Webhook 发布器；绝不修改 SSE 的 session_outbox 发布状态。 */
@Service
public class SessionWebhookPublicationService {

    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(1);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(5);

    private final SessionWebhookDeliveryLedger ledger;
    private final SessionEventSubscriptionMapper subscriptions;
    private final SessionOutboxMapper outbox;
    private final Duration lease;
    private final Duration retryDelay;

    @Autowired
    public SessionWebhookPublicationService(
            SessionWebhookDeliveryLedger ledger,
            SessionEventSubscriptionMapper subscriptions,
            SessionOutboxMapper outbox) {
        this(ledger, subscriptions, outbox, DEFAULT_LEASE, DEFAULT_RETRY_DELAY);
    }

    SessionWebhookPublicationService(
            SessionWebhookDeliveryLedger ledger,
            SessionEventSubscriptionMapper subscriptions,
            SessionOutboxMapper outbox,
            Duration lease,
            Duration retryDelay) {
        this.ledger = ledger;
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.lease = lease == null ? DEFAULT_LEASE : lease;
        this.retryDelay = retryDelay == null ? DEFAULT_RETRY_DELAY : retryDelay;
    }

    public SessionWebhookPublicationResult publishNext(
            String tenantId, long subscriptionId, String sessionId, Instant now, SessionWebhookTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        Instant effectiveNow = now == null ? Instant.now() : now;
        SessionWebhookDeliveryLedger.Claim delivery = ledger.claimNext(
                tenantId, subscriptionId, sessionId, effectiveNow, lease);
        if (delivery == null) {
            return SessionWebhookPublicationResult.noReadyDelivery();
        }
        SessionEventSubscriptionDO subscription = subscriptions.selectOne(
                new QueryWrapper<SessionEventSubscriptionDO>()
                        .eq("tenant_id", Long.parseLong(tenantId)).eq("id", subscriptionId)
                        .eq("status", SessionWebhookDeliveryLedger.ACTIVE));
        SessionOutboxEntryDO output = outbox.selectOne(new QueryWrapper<SessionOutboxEntryDO>()
                .eq("tenant_id", Long.parseLong(tenantId)).eq("id", delivery.outboxEventId()));
        if (subscription == null || output == null || !sessionId.equals(output.getSessionId())) {
            return new SessionWebhookPublicationResult(delivery.deliveryId(),
                    ledger.markUnresolved(tenantId, delivery, "WEBHOOK_DELIVERY_SOURCE_MISSING")
                            ? SessionWebhookPublicationResult.Status.UNRESOLVED
                            : SessionWebhookPublicationResult.Status.CLAIM_LOST);
        }
        try {
            transport.deliver(new SessionWebhookTarget(subscription.getEndpointUrl(),
                    subscription.getSigningSecretEncrypted()), SessionMailboxService.view(output));
            return new SessionWebhookPublicationResult(delivery.deliveryId(),
                    ledger.markDelivered(tenantId, delivery, effectiveNow)
                            ? SessionWebhookPublicationResult.Status.DELIVERED
                            : SessionWebhookPublicationResult.Status.CLAIM_LOST);
        } catch (SessionWebhookTransportException failure) {
            boolean updated = failure.retryable()
                    ? ledger.retry(tenantId, delivery, effectiveNow.plus(retryDelay), failure.errorCode())
                    : ledger.markUnresolved(tenantId, delivery, failure.errorCode());
            return new SessionWebhookPublicationResult(delivery.deliveryId(), updated
                    ? (failure.retryable() ? SessionWebhookPublicationResult.Status.RETRY_SCHEDULED
                    : SessionWebhookPublicationResult.Status.UNRESOLVED)
                    : SessionWebhookPublicationResult.Status.CLAIM_LOST);
        } catch (Exception failure) {
            return new SessionWebhookPublicationResult(delivery.deliveryId(),
                    ledger.retry(tenantId, delivery, effectiveNow.plus(retryDelay),
                            "WEBHOOK_DELIVERY_FAILED")
                            ? SessionWebhookPublicationResult.Status.RETRY_SCHEDULED
                            : SessionWebhookPublicationResult.Status.CLAIM_LOST);
        }
    }
}
