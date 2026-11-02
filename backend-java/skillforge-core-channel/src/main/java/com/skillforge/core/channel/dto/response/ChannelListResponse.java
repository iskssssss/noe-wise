package com.skillforge.core.channel.dto.response;

import java.util.List;

/**
 * 渠道列表响应 DTO。
 *
 * @param total 渠道总数
 * @param items 渠道列表
 */
public record ChannelListResponse(
        int total,
        List<ChannelResponse> items) {
}
