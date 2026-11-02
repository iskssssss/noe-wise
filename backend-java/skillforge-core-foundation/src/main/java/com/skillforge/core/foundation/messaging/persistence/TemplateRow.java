package com.skillforge.core.foundation.messaging.persistence;

import java.time.Instant;

/** 不可变模板版本；同作用域模板当前指针仅由模板发布用例维护。 */
public record TemplateRow(String id, String scopeKey, String tenantId, String templateKey,
        String subject, String body, String variableNames, boolean current, Instant createdAt) { }
