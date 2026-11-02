package com.skillforge.core.foundation.messaging.persistence;

import java.time.Instant;

/** 邮件可靠投递行；租约token约束写权，dispatching失去租约后只能转unknown。 */
public record DeliveryRow(String id, String scopeKey, String tenantId, String notificationId,
        String mailboxId, String status, String errorCode, int attempts, String leaseToken,
        Instant leaseUntil, Instant updatedAt) { }
