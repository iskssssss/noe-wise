package com.skillforge.core.channel.dto.channel;

/**
 * 渠道绑定结果响应 DTO。
 *
 * @param status 绑定状态
 * @param count  绑定数量
 */
public record BindResultResponse(
        String status,
        int count) {
}
