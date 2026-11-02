package com.skillforge.core.chat.dto.openplatform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建 Session Outbox 出站 Webhook 订阅；签名密钥只在请求中出现，不会回显。 */
public record CreateSessionWebhookSubscriptionRequest(
        String sessionId,
        @NotBlank @Size(max = 2048) String endpointUrl,
        @NotBlank @Size(max = 4096) String signingSecret) {
}
