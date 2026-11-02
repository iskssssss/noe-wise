package com.skillforge.core.chat.persistence;

/** Outbox 与 Webhook 固定聚合列；无匹配事实时 SQL COUNT 返回全零。 */
public record DeliveryCounts(long outboxTotal,
        long webhookPending, long webhookDelivered, long webhookFailed, long webhookUnknown) { }
