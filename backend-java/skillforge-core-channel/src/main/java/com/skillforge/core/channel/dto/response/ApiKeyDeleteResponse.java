package com.skillforge.core.channel.dto.response;

/**
 * OpenAPI API Key 吊销响应 DTO。
 *
 * @param channelId 渠道主键
 * @param status    吊销状态（revoked）
 */
public record ApiKeyDeleteResponse(
        Long channelId,
        String status) {
}
