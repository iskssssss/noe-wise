package com.skillforge.core.chat.dto.handoff;

/**
 * 创建转人工工单请求 DTO。
 *
 * @param reason      转人工原因，可选，默认 user_request
 * @param summary     会话摘要，可选
 * @param sessionId   关联产品会话 ID，必填
 * @param executionId 关联工作流执行 ID，可选
 * @param priority    优先级，可选，默认 0
 * @param commandId   本次创建命令的标准 UUID
 */
public record CreateHandoffTicketRequest(
        String reason,
        String summary,
        String sessionId,
        String executionId,
        Integer priority,
        String commandId) {
}
