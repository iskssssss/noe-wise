package com.skillforge.institution.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.chat.session.SessionWebhookSubscriptionService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 暂停订阅及原操作回执同事务提交。 */
@Service
public class WebhookPauseReceiptService {
    private final FoundationAccess access;
    private final SessionWebhookSubscriptionService subscriptions;
    private final WebhookPauseReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public WebhookPauseReceiptService(FoundationAccess access, SessionWebhookSubscriptionService subscriptions,
            WebhookPauseReceiptMapper receipts, CapabilityCatalogDatabaseContext context) {
        this.access = access; this.subscriptions = subscriptions; this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Result pause(long tenant, UUID requestId, long subscriptionId) {
        try {
            access.require("foundation.webhooks.update");
        } catch (ApiException failure) {
            if (!"foundation.permission.denied".equals(failure.getCode())) throw failure;
            throw new ApiException(HttpStatus.FORBIDDEN, "WEBHOOK_PAUSE_REJECTED", "没有暂停订阅的权限", false);
        }
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original != null) return replay(requestId, subscriptionId, actor, original);
        if (receipts.claim(tenant, requestId.toString(), actor, subscriptionId) == 0) {
            original = receipts.find(tenant, requestId.toString());
            if (original == null) throw new IllegalStateException("Webhook 暂停原回执缺失");
            return replay(requestId, subscriptionId, actor, original);
        }
        boolean changed = subscriptions.pause(Long.toString(tenant), subscriptionId);
        if (receipts.complete(tenant, requestId.toString(), changed) != 1)
            throw new IllegalStateException("Webhook 暂停回执未完成");
        return new Result(requestId, Long.toString(subscriptionId), changed);
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.webhooks.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original == null || !actor.equals(original.actorRef())) return null;
        if (original.changed() == null) throw new IllegalStateException("Webhook 暂停回执未完成");
        return new Result(requestId, Long.toString(original.subscriptionId()), original.changed());
    }

    private Result replay(UUID requestId, long subscriptionId, String actor, WebhookPauseReceiptMapper.Row original) {
        if (!actor.equals(original.actorRef()) || subscriptionId != original.subscriptionId())
            throw new ApiException(HttpStatus.CONFLICT, "Webhook 暂停请求标识已用于其他操作");
        if (original.changed() == null) throw new IllegalStateException("Webhook 暂停回执未完成");
        return new Result(requestId, Long.toString(subscriptionId), original.changed());
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }

    public record Result(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("subscription_id") String subscriptionId,
            @JsonProperty("changed") boolean changed) { }
}
