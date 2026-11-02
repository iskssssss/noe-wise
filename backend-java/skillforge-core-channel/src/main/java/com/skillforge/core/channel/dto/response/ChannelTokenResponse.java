package com.skillforge.core.channel.dto.response;

/**
 * 渠道令牌响应 DTO。
 *
 * @param id           渠道 ID
 * @param channelToken 明文渠道令牌
 */
public record ChannelTokenResponse(
        String id,
        String channelToken) {
}
