package com.skillforge.core.foundation.dto.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 审计日志查询对象。
 *
 * <p>GET /api/v1/audit/logs 的查询参数封装；
 * 缺省值与租户边界由审计应用服务归一化。
 *
 * @param action   操作类型筛选，可空
 * @param tenantId 机构 ID 筛选，可空
 * @param limit    返回条数上限，缺省 20
 */
public record AuditLogQuery(
        @Schema(description = "操作类型筛选，可空") String action,
        @Schema(description = "机构 ID 筛选，可空") String tenantId,
        @Schema(description = "每页条数，缺省 20，上限 200") Integer limit,
        @Schema(description = "页码，从 1 开始") Integer page,
        @Schema(description = "操作者 ID，精确匹配") String actorId,
        @Schema(description = "资源类型，精确匹配") String resourceType,
        @Schema(description = "起始时间，含边界") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @Schema(description = "截止时间，不含边界") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
}
