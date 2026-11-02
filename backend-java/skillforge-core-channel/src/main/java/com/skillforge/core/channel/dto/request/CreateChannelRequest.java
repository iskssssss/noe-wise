package com.skillforge.core.channel.dto.request;

import com.skillforge.core.channel.enums.ChannelType;
import java.util.List;
import java.util.Map;

/**
 * 创建渠道请求 DTO。
 *
 * @param name      渠道名称，可选，默认「未命名渠道」
 * @param channelType 渠道类型，可选，默认 web_widget
 * @param config    渠道业务配置，可选
 * @param uiConfig  渠道 UI 配置，可选
 * @param agentBindings 创建时一并绑定的智能体，可选
 */
public record CreateChannelRequest(
        String name,
        ChannelType channelType,
        Map<String, Object> config,
        Map<String, Object> uiConfig,
        List<ChannelAgentBindingRequest> agentBindings) {
}
