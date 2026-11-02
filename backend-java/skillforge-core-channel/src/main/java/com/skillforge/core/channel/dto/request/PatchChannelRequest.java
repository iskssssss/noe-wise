package com.skillforge.core.channel.dto.request;

import com.skillforge.core.channel.enums.ChannelStatus;
import java.util.Map;

/**
 * 更新渠道请求 DTO。
 *
 * @param name     渠道名称，可选
 * @param config   渠道业务配置，可选
 * @param uiConfig 渠道 UI 配置，可选
 * @param status   渠道状态，可选
 */
public record PatchChannelRequest(
        String name,
        Map<String, Object> config,
        Map<String, Object> uiConfig,
        ChannelStatus status) {
}
