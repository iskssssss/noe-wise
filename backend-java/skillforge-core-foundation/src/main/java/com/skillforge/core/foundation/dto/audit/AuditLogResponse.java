package com.skillforge.core.foundation.dto.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 审计日志响应项 DTO。
 *
 * @param id           日志主键
 * @param action       操作类型
 * @param actorId      操作者 ID
 * @param resourceType 被操作资源类型
 * @param resourceId   被操作资源 ID
 * @param message      可读操作描述
 * @param traceId      链路追踪 ID
 * @param payload      操作附加数据
 * @param createdAt    创建时间
 */
public record AuditLogResponse(
        Long id,
        String action,
        String actorId,
        String resourceType,
        String resourceId,
        String message,
        String traceId,
        Map<String, Object> payload,
        Instant createdAt) {
}
