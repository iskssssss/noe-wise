package com.skillforge.core.foundation.dto.audit;

import java.util.List;

/**
 * 审计日志列表响应 DTO。
 *
 * @param total 命中总数
 * @param items 日志项列表
 */
public record AuditLogListResponse(
        long total,
        long page,
        long limit,
        List<AuditLogResponse> items) {
}
