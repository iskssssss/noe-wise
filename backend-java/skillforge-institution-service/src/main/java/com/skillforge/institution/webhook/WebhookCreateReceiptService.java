package com.skillforge.institution.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.session.SessionWebhookSubscriptionService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订阅创建与不含密钥的原请求回执同事务提交。 */
@Service
public class WebhookCreateReceiptService {
    private final FoundationAccess access;
    private final SessionWebhookSubscriptionService subscriptions;
    private final SessionEventSubscriptionMapper rows;
    private final CryptoService crypto;
    private final WebhookCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public WebhookCreateReceiptService(FoundationAccess access, SessionWebhookSubscriptionService subscriptions,
            SessionEventSubscriptionMapper rows, CryptoService crypto, WebhookCreateReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context) {
        this.access = access; this.subscriptions = subscriptions; this.rows = rows; this.crypto = crypto;
        this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Result create(long tenant, UUID requestId, String sessionId, String endpointUrl, String secret) {
        access.require("foundation.webhooks.create");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original != null) return replay(tenant, requestId, actor, sessionId, endpointUrl, secret, original);
        if (receipts.claim(tenant, requestId.toString(), actor) == 0) {
            original = receipts.find(tenant, requestId.toString());
            if (original == null) throw new IllegalStateException("Webhook 订阅原回执缺失");
            return replay(tenant, requestId, actor, sessionId, endpointUrl, secret, original);
        }
        com.skillforge.core.chat.session.SessionWebhookSubscription created;
        try {
            created = subscriptions.create(Long.toString(tenant), sessionId, endpointUrl, secret);
        } catch (IllegalArgumentException failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_CREATE_REJECTED", "Webhook 订阅配置无效", false);
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "WEBHOOK_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        if (receipts.complete(tenant, requestId.toString(), created.subscriptionId()) != 1)
            throw new IllegalStateException("Webhook 订阅回执未完成");
        return new Result(requestId, Long.toString(created.subscriptionId()));
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.webhooks.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original == null || !actor.equals(original.actorRef())) return null;
        if (original.subscriptionId() == null) throw new IllegalStateException("Webhook 订阅回执未完成");
        return new Result(requestId, Long.toString(original.subscriptionId()));
    }

    private Result replay(long tenant, UUID requestId, String actor, String sessionId, String endpointUrl,
            String secret, WebhookCreateReceiptMapper.Row original) {
        if (!actor.equals(original.actorRef())) throw conflict();
        if (original.subscriptionId() == null) throw new IllegalStateException("Webhook 订阅回执未完成");
        var row = rows.selectOne(new LambdaQueryWrapper<SessionEventSubscriptionDO>()
                .eq(SessionEventSubscriptionDO::getTenantId, tenant)
                .eq(SessionEventSubscriptionDO::getId, original.subscriptionId()));
        if (row == null) throw new IllegalStateException("Webhook 订阅原记录缺失");
        String normalizedSession = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        if (!Objects.equals(normalizedSession, row.getSessionId()) || !endpointUrl.trim().equals(row.getEndpointUrl())
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                    crypto.decrypt(row.getSigningSecretEncrypted()).getBytes(StandardCharsets.UTF_8))) throw conflict();
        return new Result(requestId, Long.toString(original.subscriptionId()));
    }

    private ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "Webhook 订阅请求标识已用于其他操作"); }
    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }
    public record Result(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("subscription_id") String subscriptionId) { }
}
