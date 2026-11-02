package com.skillforge.core.chat.dto.handoff;

import com.skillforge.core.chat.enums.HandoffTicketStatus;
import java.time.Instant;

/**
 * 转人工工单响应项 DTO。
 *
 * @param id        工单业务 ID
 * @param user      C 端用户 ID
 * @param reason    转人工原因
 * @param summary   会话摘要
 * @param priority  优先级
 * @param status    工单状态
 * @param sessionId 关联会话 ID
 * @param createdAt 创建时间
 */
public record HandoffTicketResponse(
        String id,
        String user,
        String reason,
        String summary,
        Integer priority,
        HandoffTicketStatus status,
        String sessionId,
        Instant createdAt,
        String assignedTo,
        long revision,
        String resolutionNote) {
}
