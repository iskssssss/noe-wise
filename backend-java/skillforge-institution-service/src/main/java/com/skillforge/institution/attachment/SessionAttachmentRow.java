package com.skillforge.institution.attachment;

import java.time.Instant;

/** 会话附件不可变元数据及按需关联正文；注册时正文交给独立所有者，元数据列表的 content 为 null。 */
public record SessionAttachmentRow(String id, String tenantId, String sessionId, String name,
        String inputPath, long size, String sha256, byte[] content,
        Instant createdAt, Instant expiresAt) { }
