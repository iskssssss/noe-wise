package com.skillforge.core.foundation.messaging.persistence;

import java.time.Instant;

/** 消息持久行；邮件正文是加密密文，站内正文仅供所属成员读取。 */
public record MessageRow(String id, String scopeKey, String tenantId, String recipientMemberId,
        String recipientEmail, String deduplicationKey, String fingerprint, String templateId,
        String title, String body, String emailSubjectCipher, String emailBodyCipher,
        String announcementId, boolean inApp, Instant readAt, Instant createdAt) { }
