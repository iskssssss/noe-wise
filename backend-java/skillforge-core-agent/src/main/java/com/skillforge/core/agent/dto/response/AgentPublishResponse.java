package com.skillforge.core.agent.dto.response;
/**
 * Agent 发布响应 DTO。
 *
 * @param status  发布操作状态，固定为 published
 * @param agentId Agent 主键
 */
public record AgentPublishResponse(
        String status,
        Long agentId) {
}
