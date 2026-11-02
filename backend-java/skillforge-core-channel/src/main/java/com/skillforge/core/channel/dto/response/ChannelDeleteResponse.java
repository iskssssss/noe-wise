package com.skillforge.core.channel.dto.response;

import com.skillforge.core.channel.enums.ChannelStatus;

/**
 * 渠道停用响应 DTO。
 *
 * @param status 停用后状态
 * @param id     渠道 ID
 */
public record ChannelDeleteResponse(
        ChannelStatus status,
        String id) {
}
