package com.skillforge.core.billing.dto.billing;

import java.util.List;

/**
 * 请求列表分页响应（支撑用户与 Agent 的聊天全链路流水入口）。
 *
 * @param total 总条数
 * @param items 请求列表项（含全链路聚合）
 */
public record RequestListResponse(long total, List<RequestListItem> items) {
}
