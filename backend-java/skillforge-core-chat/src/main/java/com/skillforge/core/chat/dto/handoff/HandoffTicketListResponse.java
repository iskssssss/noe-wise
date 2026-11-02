package com.skillforge.core.chat.dto.handoff;

import java.util.List;

/**
 * 转人工工单列表响应 DTO。
 *
 * @param total 工单总数
 * @param items 工单列表
 */
public record HandoffTicketListResponse(
        int total,
        List<HandoffTicketResponse> items) {
}
