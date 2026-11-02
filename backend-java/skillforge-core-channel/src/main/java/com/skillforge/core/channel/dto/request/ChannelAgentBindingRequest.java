package com.skillforge.core.channel.dto.request;

/**
 * 渠道-单 Agent 绑定请求项 DTO。
 *
 * @param agentId          Agent Long
 * @param priority         路由优先级
 * @param isDefault        是否默认 Agent
 * @param trafficPercentage 流量百分比
 */
public record ChannelAgentBindingRequest(
        Long agentId,
        Integer priority,
        Boolean isDefault,
        Integer trafficPercentage) {
}
