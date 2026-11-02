package com.skillforge.core.chat.dto.openplatform;

import java.time.Instant;

/** 出站 Webhook 的安全管理视图；不返回签名密钥或密文。 */
public record SessionWebhookSubscriptionResponse(
        Long id,
        String sessionId,
        String endpointUrl,
        String status,
        Instant createdAt) {
}
