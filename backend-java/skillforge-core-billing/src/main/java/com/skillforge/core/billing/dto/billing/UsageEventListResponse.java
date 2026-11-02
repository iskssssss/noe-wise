package com.skillforge.core.billing.dto.billing;

import java.util.List;

/**
 * 用量事件流水分页响应（usage_events 最小单元，N28）。
 *
 * @param total 总条数
 * @param items 事件明细列表
 */
public record UsageEventListResponse(long total, List<UsageEventItem> items) {
}
