package com.skillforge.core.capability.dto.connector;

import com.skillforge.core.capability.enums.ConnectorAuthType;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 创建连接器实例请求 DTO（2 层模型：实例承载完整连接配置）。
 *
 * <p>instance_id 由后端生成（{@code inst-<雪花>}）；executorType=mcp 且 baseUrl
 * 非空时后端自动 tools/list 发现能力合并进 capabilities；发现失败会拒绝创建。
 *
 * @param executorType       执行策略，当前创建入口仅支持 mcp
 * @param name               实例名称，必填
 * @param baseUrl            MCP 服务地址，必填
 * @param authType           认证类型，可选，默认 bearer
 * @param authCredential     认证凭证，可选；与 vaultSecretPath 互斥
 * @param vaultSecretPath    通用连接器字段；当前 MCP 创建入口不支持非空路径
 * @param capabilityMapping  capability 到路径的映射，可选
 * @param headers            MCP 仅支持 MCP-Protocol-Version（2026-07-28 或 2025-11-25）
 */
public record CreateConnectorInstanceRequest(
        @NotBlank String executorType,
        @NotBlank String name,
        @NotBlank String baseUrl,
        ConnectorAuthType authType,
        String authCredential,
        String vaultSecretPath,
        Map<String, Object> capabilityMapping,
        Map<String, Object> headers) {
}
