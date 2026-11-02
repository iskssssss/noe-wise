package com.skillforge.core.capability.dto.connector;

import com.skillforge.core.capability.enums.ConnectorAuthType;
import java.util.List;
import java.util.Map;

/**
 * 更新连接器实例请求 DTO（PATCH 局部编辑，2 层模型）。
 *
 * <p>null/空字段语义：name / baseUrl 为 null 保留原值，提供时不可为空白；authType 为 null 保留原值；authCredential 为 null/空保留原凭证
 * （非空则重加密更新并清除 Vault 路径）；其他既有类型 vaultSecretPath 非空时更新 Vault 凭证；MCP 禁止非空路径，显式空串清除原路径；
 * 两种凭据来源同时提供会被拒绝；
 * capabilityMapping / headers 为 null 保留、非 null 整体替换；executorType / capabilities
 * 为 null 保留、非 null 整体替换；status 为 null 保留。
 * 不可改字段：instance_id。
 *
 * @param name                实例名称，可选（null = 保留，非 null 须非空白）
 * @param baseUrl             服务地址，可选（null = 保留，非 null 须非空白）
 * @param executorType        执行策略，可选（null = 保留；当前非空仅允许 mcp）
 * @param capabilities        能力清单，可选（null = 保留，非 null = 整体替换）
 * @param authType            认证类型，可选（null = 保留原值）
 * @param authCredential      认证凭证，可选（null/空 = 保留；非空 = 更新并清除 Vault 路径）
 * @param vaultSecretPath     Vault 密钥路径，可选（非空 = 更新；与 authCredential 互斥）
 * @param capabilityMapping   capability 到路径的映射，可选（null = 保留，非 null = 整体替换）
 * @param headers             公开非敏感附加请求头；MCP 仅允许 MCP-Protocol-Version（2026-07-28 或 2025-11-25）
 * @param status              实例状态，可选（null = 保留；非空须为 active/disabled，否则 422）
 */
public record UpdateConnectorInstanceRequest(
        String name,
        String baseUrl,
        String executorType,
        List<String> capabilities,
        ConnectorAuthType authType,
        String authCredential,
        String vaultSecretPath,
        Map<String, Object> capabilityMapping,
        Map<String, Object> headers,
        String status) {
}
