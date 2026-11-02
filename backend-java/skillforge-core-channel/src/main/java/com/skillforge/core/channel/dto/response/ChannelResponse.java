package com.skillforge.core.channel.dto.response;

import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import java.util.List;
import java.util.Map;

/**
 * 渠道响应项 DTO。
 *
 * @param id            渠道主键
 * @param name          渠道名称
 * @param type          渠道类型
 * @param status        渠道状态
 * @param channelToken  令牌脱敏值
 * @param config        渠道业务配置，不含认证凭据
 * @param uiConfig      渠道 UI 配置
 * @param agentBindings 渠道 Agent 绑定摘要（id 为 Agent ID，name 取 Agent 名称）
 * @param apiKeyIssued  是否已签发 OpenAPI 凭据，不公开哈希或密文
 */
public record ChannelResponse(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String name,
        ChannelType type,
        ChannelStatus status,
        String channelToken,
        Map<String, Object> config,
        Map<String, Object> uiConfig,
        List<BindingItem> agentBindings,
        boolean apiKeyIssued) {

    /**
     * 渠道绑定摘要项。
     *
     * @param id                资源 ID（Agent ID）
     * @param name              资源显示名称
     * @param priority          路由优先级
     * @param trafficPercentage 灰度流量百分比（0-100）
     * @param isDefault         是否默认资源
     */
    public record BindingItem(
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
            String name,
            Integer priority,
            Integer trafficPercentage,
            Boolean isDefault) {
    }
}
