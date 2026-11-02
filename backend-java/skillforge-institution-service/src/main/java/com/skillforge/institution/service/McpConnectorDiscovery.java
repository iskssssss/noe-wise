package com.skillforge.institution.service;

import com.skillforge.core.capability.constant.ConnectorConstants;
import com.skillforge.core.capability.enums.ConnectorAuthType;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.mcp.McpDiscoveryClient;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Connector 管理和工具接入共用同一个 MCP 配置与发现边界；凭据只用于当前外部读取。 */
public final class McpConnectorDiscovery {
    private McpConnectorDiscovery() {}

    public static McpDiscoveryClient.Catalog list(
            McpDiscoveryClient client, ConnectorInstanceDO instance, String credential) {
        try {
            return client.listTools(new McpDiscoveryClient.Request(instance.getInstanceId(),
                    instance.getBaseUrl(), credential, protocol(instance.getHeaders())));
        } catch (McpDiscoveryClient.DiscoveryException failure) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP 工具目录不可用", failure);
        }
    }

    public static String decrypt(CryptoService crypto, String encrypted) {
        if (!hasText(encrypted)) return null;
        try {
            String credential = crypto.decrypt(encrypted);
            if (!hasText(credential)) throw new IllegalArgumentException("credential_empty");
            return credential;
        } catch (RuntimeException unavailable) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "连接器凭据不可用", unavailable);
        }
    }

    /** 保存与发现采用和当前MCP Runtime相同的连接能力；不接受能保存却不能执行的配置。 */
    public static void validate(String endpoint, String authType, String vaultSecretPath, Map<String, Object> headers, String credential) {
        if (!Set.of(ConnectorAuthType.NONE.code(), ConnectorConstants.AUTH_TYPE_BEARER).contains(authType))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP 认证方式仅支持无认证或 Bearer Token，请选择后重新保存");
        if (hasText(vaultSecretPath))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP 暂不支持 Vault 路径，请清空路径并使用直接 Bearer Token 或无认证");
        if (headers != null && headers.keySet().stream().anyMatch(name -> !"MCP-Protocol-Version".equals(name)))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP 请求头仅支持 MCP-Protocol-Version，请移除其他附加请求头");
        if (headers != null && headers.containsKey("MCP-Protocol-Version") && headers.get("MCP-Protocol-Version") == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择有效的 MCP 协议版本");
        protocol(headers);
        if (ConnectorAuthType.NONE.code().equals(authType) && hasText(credential))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "无认证 MCP 连接器不能携带访问凭据，请编辑并保存以清除原凭据");
        if (ConnectorConstants.AUTH_TYPE_BEARER.equals(authType) && !hasText(credential))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Bearer Token 不能为空，请填写访问凭据后保存");
        URI address;
        try {
            address = URI.create(endpoint);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP 服务地址格式无效，请填写完整 HTTP(S) 地址");
        }
        if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme())) || address.getHost() == null
                || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP 服务地址须为完整 HTTP(S) 地址，不能包含用户信息、查询参数或片段");
        }
    }

    /** MCP 协议是显式连接配置，不能由远端错误文案或一次失败调用推断。 */
    public static String protocol(Map<String, Object> headers) {
        Object value = headers == null ? null : headers.get("MCP-Protocol-Version");
        if (value == null) return JdbcMcpBindings.PROTOCOL_VERSION;
        if (!(value instanceof String version) || !java.util.Set.of(JdbcMcpBindings.PROTOCOL_VERSION, "2025-11-25").contains(version)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP 协议版本不受支持");
        }
        return version;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
