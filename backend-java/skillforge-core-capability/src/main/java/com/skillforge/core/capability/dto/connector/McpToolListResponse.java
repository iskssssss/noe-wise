package com.skillforge.core.capability.dto.connector;

import java.util.List;

/**
 * MCP 工具清单响应 DTO。
 *
 * @param serverIdentity MCP Server 稳定身份
 * @param cacheScope Server 声明的缓存 scope，可空
 * @param ttlMs 当前客户端采用的 TTL；未缓存时为 0
 * @param total 工具总数
 * @param items 工具列表
 */
public record McpToolListResponse(
        String serverIdentity,
        String cacheScope,
        long ttlMs,
        int total,
        List<McpToolItemResponse> items) {
}
