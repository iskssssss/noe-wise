package com.skillforge.core.chat.dto.handoff;

import com.skillforge.core.chat.enums.HandoffTicketStatus;

/**
 * 转人工工单操作结果 DTO。
 *
 * @param id     工单业务 ID
 * @param status 操作后的工单状态
 */
public record HandoffActionResponse(
        String id,
        HandoffTicketStatus status) {
}
