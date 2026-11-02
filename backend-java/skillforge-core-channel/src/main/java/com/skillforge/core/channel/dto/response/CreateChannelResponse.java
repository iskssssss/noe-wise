package com.skillforge.core.channel.dto.response;

import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import java.util.Map;

/**
 * 创建渠道响应 DTO，包含仅此一次的明文令牌。
 *
 * @param id          渠道主键
 * @param name        渠道名称
 * @param type        渠道类型
 * @param status      渠道状态
 * @param channelToken 明文渠道令牌
 * @param config      渠道业务配置
 * @param uiConfig    渠道 UI 配置
 */
public record CreateChannelResponse(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String name,
        ChannelType type,
        ChannelStatus status,
        String channelToken,
        Map<String, Object> config,
        Map<String, Object> uiConfig) {
}
