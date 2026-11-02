package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import org.springframework.http.ResponseEntity;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.McpConnectorDiscovery;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.capability.constant.ConnectorConstants;
import com.skillforge.core.capability.enums.ConnectorAuthType;
import com.skillforge.core.capability.dto.connector.UpdateConnectorInstanceRequest;
import com.skillforge.core.capability.dto.connector.ConnectorInstanceDetailResponse;
import com.skillforge.core.capability.dto.connector.ConnectorInstanceListResponse;
import com.skillforge.core.capability.dto.connector.ConnectorInstanceResponse;
import com.skillforge.core.capability.dto.connector.CreateConnectorInstanceRequest;
import com.skillforge.core.capability.dto.connector.CreateConnectorInstanceResponse;
import com.skillforge.core.shared.dto.resource.DeleteResponse;
import com.skillforge.runtime.adapter.mcp.McpDiscoveryClient;
import com.skillforge.core.capability.dto.connector.McpToolItemResponse;
import com.skillforge.core.capability.dto.connector.McpToolListResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.vault.VaultProvider;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接器管理接口控制器。
 *
 * <p>提供租户连接器实例创建、列表、详情、更新、删除及 MCP 工具发现接口。
 * 仍被当前工具部署或其他资源引用的实例不能删除。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {

    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
            "x-auth-token",
            "x-access-token",
            "access-token",
            "x-amz-security-token");

    /** 连接器实例 MyBatis-Plus 服务。 */
    private final ConnectorInstanceService instanceService;

    /** 应用层加密服务，用于加密连接器凭证。 */
    private final CryptoService cryptoService;

    /** Vault 密钥提供者，用于按 secret path 解析凭证。 */
    private final VaultProvider vaultProvider;

    /** MCP 客户端，用于 @mcp 类型实例的工具发现（tools/list）。 */
    private final McpDiscoveryClient mcpDiscoveryClient;

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ConnectorController.class);

    /**
     * 构造连接器控制器。
     *
     * @param instanceService     实例服务
     * @param cryptoService       加密服务
     * @param vaultProvider       Vault 密钥提供者
     * @param mcpDiscoveryClient      Runtime MCP 发现客户端
     */
    public ConnectorController(
            ConnectorInstanceService instanceService,
            CryptoService cryptoService,
            VaultProvider vaultProvider,
            McpDiscoveryClient mcpDiscoveryClient) {
        this.instanceService = instanceService;
        this.cryptoService = cryptoService;
        this.vaultProvider = vaultProvider;
        this.mcpDiscoveryClient = mcpDiscoveryClient;
    }

    /**
     * 创建租户连接器实例（2 层模型：实例承载完整连接配置）。
     *
     * <p>executorType=mcp 且 baseUrl 非空时，用请求凭证自动 tools/list 发现能力
     * 合并进 capabilities；远端或协议失败会阻止创建，合法空目录仍可创建。
     *
     * @param request 实例请求
     * @return 创建结果
     */
    @RequirePermission("foundation.connectors.create")
    @PostMapping("/instances")
    public ApiResponse<CreateConnectorInstanceResponse> createInstance(
            @Valid @RequestBody CreateConnectorInstanceRequest request) {
        return createInstance(request, null, null);
    }

    public record ConnectorCreateCommand(@JsonProperty("request_id") java.util.UUID requestId,
            @Valid @NotNull CreateConnectorInstanceRequest payload) { }
    public record ConnectorCreateReceipt(@JsonProperty("request_id") java.util.UUID requestId,
            @JsonProperty("instance_id") String instanceId) { }

    @RequirePermission("foundation.connectors.create")
    @PostMapping("/instances/create-with-receipt")
    public ApiResponse<ConnectorCreateReceipt> createWithReceipt(@Valid @RequestBody ConnectorCreateCommand command) {
        if (command.requestId() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "创建请求标识不能为空");
        String actor = creationActor();
        try {
            var result = createInstance(command.payload(), command.requestId().toString(), actor).data();
            return ApiResponses.wrap(new ConnectorCreateReceipt(command.requestId(), result.instanceId()));
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "CONNECTOR_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }

    @RequirePermission("foundation.connectors.read")
    @GetMapping("/instances/create-with-receipt/result")
    public ResponseEntity<ApiResponse<ConnectorCreateReceipt>> creationResult(@RequestParam("request_id") java.util.UUID requestId) {
        String actor = creationActor();
        var instance = instanceService.getOne(new LambdaQueryWrapper<ConnectorInstanceDO>()
                .select(ConnectorInstanceDO::getId, ConnectorInstanceDO::getInstanceId)
                .eq(ConnectorInstanceDO::getTenantId, Long.parseLong(TenantIds.requireTenantId()))
                .eq(ConnectorInstanceDO::getCreateRequestId, requestId.toString())
                .eq(ConnectorInstanceDO::getCreateActorId, actor));
        return instance == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ApiResponses.wrap(new ConnectorCreateReceipt(requestId, instance.getInstanceId())));
    }

    private static String creationActor() {
        String actor = RequestContext.getUserId();
        if (actor == null || actor.isBlank() || ChannelContext.getChannelId() != null || ServicePrincipalContext.get() != null)
            throw new ApiException(HttpStatus.FORBIDDEN, "仅机构成员可核对连接器创建请求");
        return actor;
    }

    private ApiResponse<CreateConnectorInstanceResponse> createInstance(
            CreateConnectorInstanceRequest request, String createRequestId, String actor) {
        String tenantId = TenantIds.requireTenantId();
        requireValidExecutor(request.executorType());
        requireSingleCredentialSource(request.authCredential(), request.vaultSecretPath());
        Map<String, Object> headers = requirePublicHeaders(request.headers());
        requireMcpConfiguration(request.baseUrl(), request.authType() == null ? ConnectorConstants.DEFAULT_AUTH_TYPE : request.authType().code(),
                request.vaultSecretPath(), headers, request.authCredential());
        String credential = null;
        String vaultSecretPath = null;
        if (hasText(request.vaultSecretPath())) {
            vaultSecretPath = request.vaultSecretPath();
            credential = resolveVaultCredential(vaultSecretPath);
        } else if (hasText(request.authCredential())) {
            credential = request.authCredential();
        }
        ConnectorInstanceDO instance = new ConnectorInstanceDO();
        instance.setId(SnowflakeIds.nextId());
        instance.setInstanceId(ConnectorConstants.INSTANCE_ID_PREFIX + SnowflakeIds.nextId());
        instance.setCreateRequestId(createRequestId);
        instance.setCreateActorId(actor);
        instance.setExecutorType(request.executorType());
        instance.setTenantId(Long.parseLong(tenantId));
        instance.setName(request.name());
        instance.setBaseUrl(request.baseUrl());
        instance.setAuthType(request.authType() == null
                ? ConnectorConstants.DEFAULT_AUTH_TYPE : request.authType().code());
        instance.setVaultSecretPath(vaultSecretPath);
        instance.setAuthCredentialEncrypted(
                credential == null ? null : encryptCredential(credential));
        instance.setCapabilityMapping(request.capabilityMapping() == null
                ? Map.of() : request.capabilityMapping());
        instance.setHeaders(headers);
        instance.setStatus(ConnectorConstants.STATUS_ACTIVE);
        instance.setVisibility(ConnectorConstants.VISIBILITY_TENANT);
        try {
            instance.setCapabilities(discoverCapabilities(instance, credential));
        } catch (ApiException failure) {
            // 目录发现先于数据库写入；仅此处可确认创建尚未发生。
            if (createRequestId == null) throw failure;
            throw new ApiException(failure.getStatus(), "CONNECTOR_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        if (!instanceService.save(instance)) throw new IllegalStateException("连接器创建未保存");
        log.info("创建连接器实例完成, instanceId: {}, executorType: {}, tenantId: {}, 凭证: {}, 能力数: {}",
                instance.getInstanceId(), instance.getExecutorType(), tenantId,
                instance.getAuthCredentialEncrypted() == null ? "未配置" : "已配置(加密)",
                instance.getCapabilities() == null ? 0 : instance.getCapabilities().size());
        return ApiResponses.wrap(new CreateConnectorInstanceResponse(
                ConnectorConstants.STATUS_CREATED,
                instance.getInstanceId(),
                instance.getId(),
                ConnectorConstants.MASKED_CREDENTIAL));
    }

    /**
     * 列出当前租户连接器实例。
     *
     * @return 实例列表
     */
    @RequirePermission("foundation.connectors.read")
    @GetMapping("/instances")
    public ApiResponse<ConnectorInstanceListResponse> listInstances() {
        List<ConnectorInstanceDO> instances = instanceService.list(
                new LambdaQueryWrapper<ConnectorInstanceDO>()
                        .select(ConnectorInstanceDO::getId, ConnectorInstanceDO::getInstanceId,
                                ConnectorInstanceDO::getName, ConnectorInstanceDO::getBaseUrl,
                                ConnectorInstanceDO::getExecutorType, ConnectorInstanceDO::getCapabilities,
                                ConnectorInstanceDO::getStatus)
                        .eq(ConnectorInstanceDO::getTenantId, Long.parseLong(TenantIds.requireTenantId())));
        List<ConnectorInstanceResponse> items = instances.stream().map(instance ->
                new ConnectorInstanceResponse(
                        instance.getId(),
                        instance.getInstanceId(),
                        instance.getName(),
                        instance.getBaseUrl(),
                        instance.getExecutorType(),
                        instance.getCapabilities() == null ? List.of() : instance.getCapabilities(),
                        instance.getStatus(),
                        ConnectorConstants.MASKED_CREDENTIAL)).toList();
        return ApiResponses.wrap(new ConnectorInstanceListResponse(items.size(), items));
    }

    /**
     * 管理面仅接受 Runtime 已装配的 MCP；HTTP 通过 PLATFORM ToolDeployment 提供。
     *
     * @param executorType 执行策略
     */
    private void requireValidExecutor(String executorType) {
        if (!ConnectorConstants.EXECUTOR_TYPE_MCP.equals(executorType)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "连接器执行策略仅支持 mcp");
        }
    }

    /**
     * MCP 能力自动发现（完整 tools/list 目录）。
     *
     * <p>仅 executorType=mcp 时执行。合法空目录返回空清单；远端或协议失败会
     * 阻止连接器创建，避免把“目录不可用”持久化成“Server 没有 Tool”。
     *
     * @param instance     尚待保存的连接器实例
     * @param credential   明文凭证（可空）
     * @return 能力清单（工具名列表），非 MCP 返回空列表
     */
    private List<String> discoverCapabilities(
            ConnectorInstanceDO instance, String credential) {
        if (!ConnectorConstants.EXECUTOR_TYPE_MCP.equals(instance.getExecutorType())) {
            return List.of();
        }
        McpDiscoveryClient.Catalog catalog = loadMcpCatalog(instance,
                ConnectorAuthType.NONE.code().equals(instance.getAuthType()) ? null : credential);
        List<String> names = catalog.tools().stream()
                .map(tool -> tool.name())
                .toList();
        log.info("MCP 能力自动发现完成, instanceId: {}, 能力数: {}",
                instance.getInstanceId(), names.size());
        return names;
    }

    /**
     * 获取连接器实例详情（凭证不回传明文，恒为脱敏值）。
     *
     * @param instanceId 实例业务 ID
     * @return 实例详情
     */
    @RequirePermission("foundation.connectors.read")
    @GetMapping("/instances/detail")
    public ApiResponse<ConnectorInstanceDetailResponse> getInstance(@RequestParam("instanceId") String instanceId) {
        Long tenantId = Long.parseLong(TenantIds.requireTenantId());
        ConnectorInstanceDO instance = findTenantInstance(instanceId, tenantId);
        return ApiResponses.wrap(detail(instance));
    }

    /**
     * 拉取 MCP 执行策略连接器实例暴露的工具清单（MCP tools/list 标准发现）。
     *
     * <p>仅 executor_type=mcp 实例支持（显式字段判断，与
     * {@code ConnectorExecutorFactory} 一致）；实例凭证解密后携带 Bearer 头调用。
     *
     * @param instanceId 实例业务 ID
     * @return 完整 MCP Tool 定义、稳定 Server 身份和缓存提示
     */
    @RequirePermission("foundation.connectors.read")
    @GetMapping("/instances/mcp-tools")
    public ApiResponse<McpToolListResponse> listMcpTools(@RequestParam("instanceId") String instanceId) {
        Long tenantId = Long.parseLong(TenantIds.requireTenantId());
        ConnectorInstanceDO instance = findTenantInstance(instanceId, tenantId);
        if (!ConnectorConstants.EXECUTOR_TYPE_MCP.equals(instance.getExecutorType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅 mcp 执行策略连接器支持工具发现: " + instance.getExecutorType());
        }
        requireMcpConfiguration(instance.getBaseUrl(), instance.getAuthType(), instance.getVaultSecretPath(), instance.getHeaders(), instance.getAuthCredentialEncrypted());
        String apiKey = ConnectorAuthType.NONE.code().equals(instance.getAuthType())
                ? null : decryptStoredCredential(instance.getAuthCredentialEncrypted());
        McpDiscoveryClient.Catalog catalog = loadMcpCatalog(instance, apiKey);
        List<McpToolItemResponse> items = catalog.tools().stream()
                .map(tool -> new McpToolItemResponse(
                        catalog.qualifiedName(tool), tool.name(), tool.title().orElse(null),
                        tool.description(), tool.inputSchema(), tool.outputSchema().orElse(null),
                        tool.icons().stream().map(icon -> new McpToolItemResponse.IconResponse(
                                icon.src(), icon.mimeType().orElse(null), icon.sizes(),
                                icon.theme().orElse(null))).toList(),
                        tool.annotations().orElse(null),
                        com.skillforge.core.capability.ToolDefinition.supportsName(tool.name()) ? null
                                : "工具名需为 1 至 128 个英文字母、数字、下划线、连字符或点，请联系工具提供方调整名称"))
                .toList();
        log.info("MCP 工具发现完成, instanceId: {}, 工具数: {}", instanceId, items.size());
        return ApiResponses.wrap(new McpToolListResponse(
                catalog.serverIdentity(), catalog.cacheScope().orElse(null),
                catalog.ttlMs(), items.size(), items));
    }

    /** 获取类型化目录；远端失败与合法空目录保持可观察区别。 */
    private McpDiscoveryClient.Catalog loadMcpCatalog(
            ConnectorInstanceDO instance, String credential) {
        return McpConnectorDiscovery.list(mcpDiscoveryClient, instance, credential);
    }

    /**
     * 更新连接器实例（PATCH 局部编辑；未提供的名称和地址保留原值）。
     *
     * <p>凭证更新语义：authCredential null/空 = 保留原凭证；非空 = 重加密更新；
     * vaultSecretPath 非空 = 更新路径且优先解析 Vault 凭证。capabilityMapping /
     * headers 为 null 保留、非 null 整体替换。status 仅接受 active/disabled。
     * instance_id / visibility 不可改。
     *
     * @param instanceId 实例业务 ID
     * @param request    更新请求
     * @return 更新后的实例详情
     */
    @RequirePermission("foundation.connectors.update")
    @PatchMapping("/instances/update")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<ConnectorInstanceDetailResponse> updateInstanceEndpoint(
            @Valid @RequestBody UpdateInstanceCommand command) {
        return ApiResponses.wrap(updateInstance(command.instanceId(), command.payload()));
    }

    public ConnectorInstanceDetailResponse updateInstance(String instanceId, @Valid UpdateConnectorInstanceRequest request) {
        Long tenantId = Long.parseLong(TenantIds.requireTenantId());
        ConnectorInstanceDO instance = findTenantInstance(instanceId, tenantId);
        if (request.name() != null && request.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "连接器名称不能为空白");
        }
        if (request.baseUrl() != null && request.baseUrl().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "连接器服务地址不能为空白");
        }
        if (request.status() != null
                && !ConnectorConstants.STATUS_ACTIVE.equals(request.status())
                && !ConnectorConstants.STATUS_DISABLED.equals(request.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "状态取值仅支持 active/disabled: " + request.status());
        }
        if (hasText(request.executorType())) {
            requireValidExecutor(request.executorType());
        }
        requireSingleCredentialSource(request.authCredential(), request.vaultSecretPath());
        Map<String, Object> headers = request.headers() == null
                ? null : requirePublicHeaders(request.headers());
        boolean mcp = ConnectorConstants.EXECUTOR_TYPE_MCP.equals(
                hasText(request.executorType()) ? request.executorType() : instance.getExecutorType());
        String authType = request.authType() == null ? instance.getAuthType() : request.authType().code();
        if (mcp) requireMcpConfiguration(request.baseUrl() == null ? instance.getBaseUrl() : request.baseUrl(), authType,
                request.vaultSecretPath() != null ? request.vaultSecretPath()
                        : hasText(request.authCredential()) ? null : instance.getVaultSecretPath(),
                headers == null ? instance.getHeaders() : headers,
                ConnectorAuthType.NONE.code().equals(authType) ? request.authCredential()
                        : hasText(request.authCredential()) ? request.authCredential() : instance.getAuthCredentialEncrypted());
        else mcpProtocol(headers);
        String encryptedCredential = null;
        String vaultSecretPath = null;
        if (hasText(request.vaultSecretPath())) {
            vaultSecretPath = request.vaultSecretPath();
            encryptedCredential = encryptCredential(resolveVaultCredential(vaultSecretPath));
        } else if (hasText(request.authCredential())) {
            encryptedCredential = encryptCredential(request.authCredential());
        }
        boolean credentialUpdated = false;
        if (mcp && ConnectorAuthType.NONE.code().equals(authType)) {
            instance.setAuthCredentialEncrypted("");
            credentialUpdated = true;
        }
        if (mcp && request.vaultSecretPath() != null) instance.setVaultSecretPath(request.vaultSecretPath());
        if (request.name() != null) instance.setName(request.name());
        if (request.baseUrl() != null) instance.setBaseUrl(request.baseUrl());
        if (hasText(request.executorType())) {
            instance.setExecutorType(request.executorType());
        }
        if (request.capabilities() != null) {
            instance.setCapabilities(request.capabilities());
        }
        if (request.authType() != null) {
            instance.setAuthType(request.authType().code());
        }
        if (encryptedCredential != null) {
            instance.setVaultSecretPath(mcp ? "" : vaultSecretPath);
            instance.setAuthCredentialEncrypted(encryptedCredential);
            credentialUpdated = true;
        }
        if (request.capabilityMapping() != null) {
            instance.setCapabilityMapping(request.capabilityMapping());
        }
        if (headers != null) {
            instance.setHeaders(headers);
        }
        if (request.status() != null) {
            instance.setStatus(request.status());
        }
        instance.setUpdatedAt(Instant.now());
        if (!instanceService.updateById(instance)) {
            throw new IllegalStateException("连接器更新未写入");
        }
        // 公开回执取同事务内的持久行，不能把写入前的 Java 时间精度当作数据库事实。
        ConnectorInstanceDO persisted = readTenantInstance(instanceId, tenantId);
        if (persisted == null) {
            throw new IllegalStateException("连接器更新后的持久回执缺失");
        }
        log.info("更新连接器实例完成 instanceId={} credentialUpdated={}",
                instance.getInstanceId(), credentialUpdated);
        return detail(persisted);
    }

    /**
     * 删除无引用的连接器实例；当前部署引用在应用服务事务及数据库外键中校验。
     *
     * @param instanceId 实例业务 ID
     * @return 删除结果
     */
    @RequirePermission("foundation.connectors.delete")
    @DeleteMapping("/instances/delete")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<DeleteResponse> deleteInstanceEndpoint(
            @Valid @RequestBody DeleteInstanceCommand command) {
        return ApiResponses.wrap(deleteInstance(command.instanceId()));
    }

    public DeleteResponse deleteInstance(String instanceId) {
        Long tenantId = Long.parseLong(TenantIds.requireTenantId());
        instanceService.deleteUnused(tenantId, instanceId);
        log.info("删除连接器实例完成 tenantId={} instanceId={}", tenantId, instanceId);
        return new DeleteResponse(ConnectorConstants.STATUS_DELETED);
    }

    /**
     * 按实例业务 ID 查询当前租户实例（跨租户一律 404 不泄露）。
     *
     * @param instanceId 实例业务 ID
     * @param tenantId   租户 ID
     * @return 连接器实例
     */
    private ConnectorInstanceDO findTenantInstance(String instanceId, Long tenantId) {
        ConnectorInstanceDO instance = readTenantInstance(instanceId, tenantId);
        if (instance == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "连接器实例不存在: " + instanceId);
        }
        return instance;
    }

    /** 详情与更新回执复用同一租户/实例查询边界；缺失由所属操作明确分类。 */
    private ConnectorInstanceDO readTenantInstance(String instanceId, Long tenantId) {
        return instanceService.getOne(new LambdaQueryWrapper<ConnectorInstanceDO>()
                .eq(ConnectorInstanceDO::getInstanceId, instanceId)
                .eq(ConnectorInstanceDO::getTenantId, tenantId)
                .last("LIMIT 1"));
    }

    /**
     * 构建实例详情响应（凭证恒为脱敏值，不回传明文）。
     *
     * @param instance 连接器实例
     * @return 实例详情 DTO
     */
    private ConnectorInstanceDetailResponse detail(ConnectorInstanceDO instance) {
        return new ConnectorInstanceDetailResponse(
                instance.getId(),
                instance.getInstanceId(),
                instance.getName(),
                instance.getBaseUrl(),
                instance.getExecutorType(),
                instance.getCapabilities() == null ? List.of() : instance.getCapabilities(),
                instance.getAuthType(),
                ConnectorConstants.MASKED_CREDENTIAL,
                instance.getVaultSecretPath(),
                instance.getCapabilityMapping(),
                publicHeaders(instance.getHeaders()),
                instance.getStatus(),
                instance.getVisibility(),
                instance.getLastHealthCheckAt(),
                instance.getHealthCheckResult(),
                instance.getCreatedAt(),
                instance.getUpdatedAt());
    }

    private void requireSingleCredentialSource(String authCredential, String vaultSecretPath) {
        if (hasText(authCredential) && hasText(vaultSecretPath)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "authCredential 与 vaultSecretPath 只能选择一种凭据来源");
        }
    }

    private String resolveVaultCredential(String vaultSecretPath) {
        Object resolved;
        try {
            resolved = vaultProvider.resolve(vaultSecretPath);
        } catch (RuntimeException unavailable) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Vault 凭据不可用");
        }
        if (resolved == null || String.valueOf(resolved).isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Vault 凭据不可用");
        }
        return String.valueOf(resolved);
    }

    private String encryptCredential(String credential) {
        try {
            String encrypted = cryptoService.encrypt(credential);
            if (!hasText(encrypted)) {
                throw new IllegalArgumentException("credential_empty");
            }
            return encrypted;
        } catch (RuntimeException unavailable) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "连接器凭据不可用");
        }
    }

    private String decryptStoredCredential(String encryptedCredential) {
        return McpConnectorDiscovery.decrypt(cryptoService, encryptedCredential);
    }

    private static void requireMcpConfiguration(String endpoint, String authType, String vaultSecretPath,
            Map<String, Object> headers, String credential) {
        McpConnectorDiscovery.validate(endpoint, authType, vaultSecretPath, headers, credential);
    }

    private static String mcpProtocol(Map<String, Object> headers) {
        return McpConnectorDiscovery.protocol(headers);
    }

    private Map<String, Object> requirePublicHeaders(Map<String, Object> headers) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (headers == null) {
            return Map.of();
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (isSensitiveHeader(entry.getKey())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "headers 不得包含认证信息，请使用 authCredential 或 vaultSecretPath");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> publicHeaders(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (!isSensitiveHeader(name)) {
                result.put(name, value);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private boolean isSensitiveHeader(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return SENSITIVE_HEADER_NAMES.contains(normalized)
                || normalized.endsWith("-authorization")
                || normalized.endsWith("-api-key")
                || normalized.endsWith("-auth-token")
                || normalized.endsWith("-access-token")
                || normalized.endsWith("-security-token");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }



    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpdateInstanceCommand(
            @JsonProperty("instance_id") @NotNull String instanceId,
            @JsonProperty("payload") @NotNull @Valid UpdateConnectorInstanceRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record DeleteInstanceCommand(
            @JsonProperty("instance_id") @NotNull String instanceId) {}
}
