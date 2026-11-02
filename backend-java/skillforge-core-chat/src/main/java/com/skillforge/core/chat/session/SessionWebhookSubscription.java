package com.skillforge.core.chat.session;

import java.time.Instant;

/** 当前租户的订阅管理回执；不含明文或加密签名材料，也不具有持久化写入能力。 */
public record SessionWebhookSubscription(
        Long subscriptionId,
        String sessionId,
        String endpointUrl,
        String status,
        Instant createdAt) {
}
