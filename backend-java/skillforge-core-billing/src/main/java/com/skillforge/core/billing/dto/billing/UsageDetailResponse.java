package com.skillforge.core.billing.dto.billing;

import java.util.List;

/**
 * 月度用量详情响应 DTO（基于 usage_events 最小单元聚合，N28）。
 *
 * @param total       当前计费周期的总用量
 * @param eventTypes  按事件类型聚合的用量
 * @param grouped     按维度分组统计（group_by 参数生效时非空）
 */
public record UsageDetailResponse(
        UsageAggregateRow total,
        List<UsageByTypeRow> eventTypes,
        List<UsageGroupedRow> grouped) {
}
