package com.skillforge.core.capability.dto.connector;

import java.util.List;

/**
 * 连接器实例列表响应 DTO。
 *
 * @param total 实例总数
 * @param items 实例列表
 */
public record ConnectorInstanceListResponse(
        int total,
        List<ConnectorInstanceResponse> items) {
}
