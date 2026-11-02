package com.skillforge.core.foundation.operations.persistence;

import java.time.Instant;

/** 安全审计 CSV 的固定列投影；不读取列表详情所需的 payload。 */
public record AuditExportRow(
        Long id, Long tenantId, String actorId, String action, String category, String outcome,
        String resourceType, String resourceId, String message, String traceId, Instant createdAt) {}
