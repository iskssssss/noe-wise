package com.skillforge.core.channel.dto.response;

/**
 * 渠道解绑 Agent 响应 DTO。
 *
 * @param status    解绑状态
 * @param channelId 渠道 ID
 * @param agentId   Agent ID
 */
public record UnbindAgentResponse(
        String status,
        String channelId,
        String agentId) {
}
