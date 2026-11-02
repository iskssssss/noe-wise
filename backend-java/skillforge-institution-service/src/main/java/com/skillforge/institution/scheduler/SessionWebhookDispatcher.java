package com.skillforge.institution.scheduler;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.chat.session.SessionWebhookDeliveryLedger;
import com.skillforge.core.chat.session.SessionWebhookPublicationResult;
import com.skillforge.core.chat.session.SessionWebhookPublicationService;
import com.skillforge.core.chat.session.SessionWebhookTransport;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描独立 Webhook 账本；每个订阅的顺序由其首条未终结 delivery 控制。 */
@Component
public class SessionWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SessionWebhookDispatcher.class);
    private static final int SUBSCRIPTION_SCAN_LIMIT = 100;
    private static final int SESSION_SCAN_LIMIT = 100;
    private static final int MAX_DELIVERIES_PER_SESSION = 20;

    private final TenantMapper tenants;
    private final SessionWebhookDeliveryLedger ledger;
    private final SessionWebhookPublicationService publication;
    private final SessionWebhookTransport transport;

    public SessionWebhookDispatcher(
            TenantMapper tenants,
            SessionWebhookDeliveryLedger ledger,
            SessionWebhookPublicationService publication,
            SessionWebhookTransport transport) {
        this.tenants = tenants;
        this.ledger = ledger;
        this.publication = publication;
        this.transport = transport;
    }

    @Scheduled(cron = "${skillforge.session-webhook-cron:*/2 * * * * *}", zone = "Asia/Shanghai")
    public void dispatchReadyDeliveries() {
        List<TenantDO> all = tenants.selectList(null);
        if (all == null) {
            return;
        }
        for (TenantDO tenant : all) {
            if (tenant == null || tenant.getId() == null) {
                continue;
            }
            String tenantId = String.valueOf(tenant.getId());
            TenantContext.setTenantId(tenantId);
            try {
                for (Long subscriptionId : ledger.listActiveSubscriptionIds(tenantId, SUBSCRIPTION_SCAN_LIMIT)) {
                    for (String sessionId : ledger.listSessionsWithPendingDelivery(
                            tenantId, subscriptionId, SESSION_SCAN_LIMIT)) {
                        publishSession(tenantId, subscriptionId, sessionId);
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void publishSession(String tenantId, Long subscriptionId, String sessionId) {
        if (subscriptionId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        for (int completed = 0; completed < MAX_DELIVERIES_PER_SESSION; completed++) {
            try {
                SessionWebhookPublicationResult result = publication.publishNext(
                        tenantId, subscriptionId, sessionId, Instant.now(), transport);
                if (result.status() != SessionWebhookPublicationResult.Status.DELIVERED) {
                    return;
                }
            } catch (RuntimeException failure) {
                log.warn("Session Webhook 账本发布异常 tenant={} subscription={} session={}",
                        tenantId, subscriptionId, sessionId, failure);
                return;
            }
        }
    }
}
