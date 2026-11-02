package com.skillforge.core.chat.session;

import com.skillforge.core.foundation.application.CryptoService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.shared.sandbox.SandboxGuard;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 受租户 RBAC 保护的 Session Outbox Webhook 订阅配置服务。 */
@Service
public class SessionWebhookSubscriptionService {

    private final SessionEventSubscriptionMapper subscriptions;
    private final CryptoService crypto;
    private final SandboxGuard sandboxGuard;

    public SessionWebhookSubscriptionService(
            SessionEventSubscriptionMapper subscriptions, CryptoService crypto, SandboxGuard sandboxGuard) {
        this.subscriptions = subscriptions;
        this.crypto = crypto;
        this.sandboxGuard = sandboxGuard;
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionWebhookSubscription create(
            String tenantId, String sessionId, String endpointUrl, String signingSecret) {
        long tenant = tenant(tenantId);
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("Webhook signing secret must not be blank");
        }
        String endpoint = sandboxGuard.assertSafe(validateEndpoint(endpointUrl));
        SessionEventSubscriptionDO subscription = new SessionEventSubscriptionDO();
        subscription.setId(SnowflakeIds.nextId());
        subscription.setTenantId(tenant);
        subscription.setSessionId(sessionId == null || sessionId.isBlank() ? null : sessionId.trim());
        subscription.setEndpointUrl(endpoint);
        subscription.setSigningSecretEncrypted(crypto.encrypt(signingSecret));
        subscription.setStatus(SessionWebhookDeliveryLedger.ACTIVE);
        Instant now = Instant.now();
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        subscriptions.insert(subscription);
        // 首创建回执与后续安全目录共享同一公开视图，保留数据库实际时间。
        SessionEventSubscriptionDO persisted = subscriptions.selectOne(new QueryWrapper<SessionEventSubscriptionDO>()
                .eq("tenant_id", tenant).eq("id", subscription.getId()));
        if (persisted == null) throw new IllegalStateException("webhook_subscription_receipt_missing");
        return view(persisted);
    }

    @Transactional(readOnly = true)
    public List<SessionWebhookSubscription> list(String tenantId) {
        return subscriptions.selectList(new QueryWrapper<SessionEventSubscriptionDO>()
                .eq("tenant_id", tenant(tenantId)).orderByDesc("created_at")).stream()
                .map(SessionWebhookSubscriptionService::view).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean pause(String tenantId, long subscriptionId) {
        Instant now = Instant.now();
        return subscriptions.update(null, new UpdateWrapper<SessionEventSubscriptionDO>()
                .eq("tenant_id", tenant(tenantId)).eq("id", subscriptionId)
                .eq("status", SessionWebhookDeliveryLedger.ACTIVE)
                .set("status", "paused").set("updated_at", now)) > 0;
    }

    private static SessionWebhookSubscription view(SessionEventSubscriptionDO subscription) {
        return new SessionWebhookSubscription(subscription.getId(), subscription.getSessionId(),
                subscription.getEndpointUrl(), subscription.getStatus(), subscription.getCreatedAt());
    }

    private long tenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return Long.parseLong(tenantId);
    }

    private String validateEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("Webhook endpoint must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(endpointUrl.trim());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Webhook endpoint is invalid", malformed);
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Webhook endpoint must be an absolute HTTP(S) URL");
        }
        return uri.toString();
    }
}
