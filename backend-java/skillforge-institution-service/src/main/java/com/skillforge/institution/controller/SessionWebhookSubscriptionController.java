package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.chat.dto.openplatform.CreateSessionWebhookSubscriptionRequest;
import com.skillforge.core.chat.dto.openplatform.SessionWebhookSubscriptionResponse;
import com.skillforge.core.chat.session.SessionWebhookSubscription;
import com.skillforge.core.chat.session.SessionWebhookSubscriptionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 管理 Session Outbox 的出站 Webhook 订阅；订阅不具有任何入站执行能力。 */
@RestController
@RequestMapping("/api/v1/webhook-subscriptions")
public class SessionWebhookSubscriptionController {

    private final SessionWebhookSubscriptionService subscriptions;

    public SessionWebhookSubscriptionController(SessionWebhookSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @RequirePermission("foundation.webhooks.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionWebhookSubscriptionResponse> create(
            @Valid @RequestBody CreateSessionWebhookSubscriptionRequest request) {
        return ApiResponses.wrap(response(subscriptions.create(TenantIds.requireTenantId(), request.sessionId(),
                request.endpointUrl(), request.signingSecret())));
    }

    @RequirePermission("foundation.webhooks.read")
    @GetMapping
    public ApiResponse<List<SessionWebhookSubscriptionResponse>> list() {
        return ApiResponses.wrap(subscriptions.list(TenantIds.requireTenantId()).stream().map(this::response).toList());
    }

    @RequirePermission("foundation.webhooks.update")
    @PatchMapping("/pause")
    public ApiResponse<Map<String, Object>> pauseEndpoint(
            @Valid @RequestBody PauseCommand command) {
        return ApiResponses.wrap(pause(command.subscriptionId()));
    }

    public Map<String, Object> pause(long subscriptionId) {
        return Map.of("id", subscriptionId, "paused",
                subscriptions.pause(TenantIds.requireTenantId(), subscriptionId));
    }

    private SessionWebhookSubscriptionResponse response(SessionWebhookSubscription source) {
        return new SessionWebhookSubscriptionResponse(source.subscriptionId(), source.sessionId(),
                source.endpointUrl(), source.status(), source.createdAt());
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record PauseCommand(
            @JsonProperty("subscription_id") @NotNull Long subscriptionId) {}
}
