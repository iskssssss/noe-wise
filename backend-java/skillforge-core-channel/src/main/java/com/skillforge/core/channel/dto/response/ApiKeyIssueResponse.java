package com.skillforge.core.channel.dto.response;

/**
 * OpenAPI API Key 签发/轮换响应 DTO。
 *
 * @param channelId 渠道主键
 * @param apiKey    API Key 公钥（ak_ 前缀）；明文仅签发/轮换响应返回一次
 * @param apiSecret API secret（参与 HMAC-SHA256 签名）；明文仅签发/轮换响应返回一次
 */
public record ApiKeyIssueResponse(
        Long channelId,
        String apiKey,
        String apiSecret) {
}
