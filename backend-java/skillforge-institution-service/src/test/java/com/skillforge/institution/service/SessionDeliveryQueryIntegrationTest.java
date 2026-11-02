package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 真实 PostgreSQL 上的投递汇总必须以 Outbox 事件为 SSE 事实，并精确限定 Run 归属。 */
class SessionDeliveryQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SessionOutboxMapper outbox;

    @Autowired
    private SessionEventSubscriptionMapper subscriptions;

    @Autowired
    private SessionOutboxWebhookDeliveryMapper webhookDeliveries;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void deliveryQueriesKeepOutboxCountsDistinctFilterTheOwnershipTripleAndReturnFailureTail() {
        String suffix = UUID.randomUUID().toString();
        long tenant = SnowflakeIds.nextId();
        long otherTenant = SnowflakeIds.nextId();
        String session = "delivery-session-" + suffix;
        String run = "delivery-run-" + suffix;
        Instant base = Instant.parse("2026-09-25T10:00:00Z");

        TenantContext.setTenantId(Long.toString(tenant));
        SessionOutboxEntryDO first = insertOutbox(tenant, session, run, 1, suffix, base);
        SessionOutboxEntryDO second = insertOutbox(tenant, session, run, 2, suffix, base.plusSeconds(1));
        insertDelivery(tenant, first, "delivered", "WEBHOOK_200", base.plusSeconds(2));
        insertDelivery(tenant, first, "unresolved", "WEBHOOK_422", base.plusSeconds(3));
        insertDelivery(tenant, second, "unresolved", "WEBHOOK_503", base.plusSeconds(4));

        // 这三项只改变租户、会话或 Run 之一；任何一个漏过滤都会污染汇总或失败尾部。
        SessionOutboxEntryDO otherRun = insertOutbox(tenant, session, "other-run-" + suffix,
                3, suffix, base.plusSeconds(5));
        insertDelivery(tenant, otherRun, "unresolved", "OTHER_RUN", base.plusSeconds(6));
        SessionOutboxEntryDO otherSession = insertOutbox(tenant, "other-session-" + suffix, run,
                1, suffix, base.plusSeconds(7));
        insertDelivery(tenant, otherSession, "unresolved", "OTHER_SESSION", base.plusSeconds(8));

        TenantContext.setTenantId(Long.toString(otherTenant));
        SessionOutboxEntryDO foreignTenant = insertOutbox(otherTenant, session, run,
                1, suffix, base.plusSeconds(9));
        insertDelivery(otherTenant, foreignTenant, "unresolved", "OTHER_TENANT", base.plusSeconds(10));

        TenantContext.setTenantId(Long.toString(tenant));
        var counts = outbox.deliveryCounts(tenant, session, run);
        List<SessionOutboxWebhookDeliveryDO> failures = outbox.latestDeliveryFailures(tenant, session, run, 10);

        // 同一已提交 Outbox 被多个 Webhook 账本关联时，可重放输出仍只计一次。
        assertEquals(2L, counts.outboxTotal());
        assertEquals(1L, counts.webhookDelivered());
        assertEquals(2L, counts.webhookFailed());
        assertEquals(List.of("WEBHOOK_503", "WEBHOOK_422"),
                failures.stream().map(SessionOutboxWebhookDeliveryDO::getErrorCode).toList());
    }

    private SessionOutboxEntryDO insertOutbox(long tenant, String session, String run, long sequence,
            String suffix, Instant timestamp) {
        SessionOutboxEntryDO value = new SessionOutboxEntryDO();
        value.setId(SnowflakeIds.nextId());
        value.setTenantId(tenant);
        value.setSessionId(session);
        value.setRuntimeRunId(run);
        value.setEventId("delivery-event-" + value.getId() + "-" + suffix);
        value.setSessionSequence(sequence);
        value.setEventType("message.delta");
        value.setCreatedAt(timestamp);
        value.setUpdatedAt(timestamp);
        outbox.insert(value);
        return value;
    }

    private void insertDelivery(long tenant, SessionOutboxEntryDO event, String status,
            String errorCode, Instant timestamp) {
        SessionEventSubscriptionDO subscription = new SessionEventSubscriptionDO();
        subscription.setId(SnowflakeIds.nextId());
        subscription.setTenantId(tenant);
        subscription.setSessionId(event.getSessionId());
        subscription.setEndpointUrl("https://delivery.test/" + subscription.getId());
        subscription.setSigningSecretEncrypted("encrypted-test-secret");
        subscription.setStatus("active");
        subscription.setCreatedAt(timestamp);
        subscription.setUpdatedAt(timestamp);
        subscriptions.insert(subscription);

        SessionOutboxWebhookDeliveryDO delivery = new SessionOutboxWebhookDeliveryDO();
        delivery.setId(SnowflakeIds.nextId());
        delivery.setTenantId(tenant);
        delivery.setSubscriptionId(subscription.getId());
        delivery.setOutboxEventId(event.getId());
        delivery.setEventId(event.getEventId());
        delivery.setSessionId(event.getSessionId());
        delivery.setSessionSequence(event.getSessionSequence());
        delivery.setStatus(status);
        delivery.setAttempts(1);
        delivery.setAvailableAt(timestamp);
        delivery.setDeliveredAt("delivered".equals(status) ? timestamp : null);
        delivery.setErrorCode(errorCode);
        delivery.setCreatedAt(timestamp);
        delivery.setUpdatedAt(timestamp);
        webhookDeliveries.insert(delivery);
    }

}
