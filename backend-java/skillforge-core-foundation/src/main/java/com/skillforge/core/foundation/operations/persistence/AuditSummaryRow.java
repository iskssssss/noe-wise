package com.skillforge.core.foundation.operations.persistence;

import java.time.Instant;

/** 审计目录的九列数据库投影，不持有 payload。 */
public record AuditSummaryRow(Long id, String actorId, String action, String category, String outcome,
        String resourceType, String message, String traceId, Instant createdAt) { }
