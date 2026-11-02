package com.skillforge.core.capability.dto.connector;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.List;

/**
 * 连接器实例响应项 DTO。
 *
 * @param id             实例主键
 * @param instanceId     实例业务 ID
 * @param name           实例名称
 * @param baseUrl        服务地址
 * @param executorType   执行策略（mock / mcp / http）
 * @param capabilities   能力清单
 * @param status         实例状态
 * @param authCredential 凭证脱敏值
 */
public record ConnectorInstanceResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,
        String instanceId,
        String name,
        String baseUrl,
        String executorType,
        List<String> capabilities,
        String status,
        String authCredential) {
}
