package com.skillforge.institution.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.AvailableToolDeployment;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.McpConnectorDiscovery;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import com.skillforge.runtime.adapter.mcp.McpDiscoveryClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 工具接入的事务编排。MCP 目录外部读取在事务外，发布、默认绑定和 Connector 名单在同一事务。 */
@Service
public class ToolOnboardingService {
    private static final String PUBLISHED = "PUBLISHED";
    private static final String INITIAL_VERSION = "mcp-initial";
    private static final long TIMEOUT_MS = 30_000;
    private static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final ToolDeployment.Environment DEFAULT_ENVIRONMENT = ToolDeployment.Environment.DEVELOPMENT;
    private static final String POLICY_SUMMARY = "已授权 Agent 调用；可能读写外部数据；失败后不自动重试";

    private final FoundationAccess access;
    private final CapabilityCatalogDatabaseContext context;
    private final ConnectorInstanceService connectors;
    private final CryptoService crypto;
    private final McpDiscoveryClient discovery;
    private final CapabilityCatalogService catalog;
    private final CapabilityProducerPublicationBridge publications;
    private final TenantToolDeploymentProviderValidator providers;
    private final PlatformToolCatalog platformTools;
    private final ObjectMapper json;
    private final ToolOnboardingReceiptMapper receipts;
    private final TransactionTemplate transactions;

    public ToolOnboardingService(FoundationAccess access, CapabilityCatalogDatabaseContext context,
            ConnectorInstanceService connectors, CryptoService crypto, McpDiscoveryClient discovery,
            CapabilityCatalogService catalog, CapabilityProducerPublicationBridge publications,
            TenantToolDeploymentProviderValidator providers, PlatformToolCatalog platformTools,
            ObjectMapper json, ToolOnboardingReceiptMapper receipts, PlatformTransactionManager transactions) {
        this.access = access;
        this.context = context;
        this.connectors = connectors;
        this.crypto = crypto;
        this.discovery = discovery;
        this.catalog = catalog;
        this.publications = publications;
        this.providers = providers;
        this.platformTools = platformTools;
        this.json = json;
        this.receipts = receipts;
        this.transactions = new TransactionTemplate(transactions);
    }

    public ToolOnboardingViews.Receipt onboard(Long tenant, UUID requestId, String connectorId, List<String> names, boolean allowAgentCalls) {
        // 聚合动作不能因为绕过旧 Controller 而少检查任一创建、发布或名单修改权限。
        access.require("foundation.connectors.read");
        access.require("foundation.connectors.update");
        access.require("foundation.capabilities.read");
        access.require("foundation.capabilities.create");
        access.require("foundation.capabilities.publish");
        if (requestId == null || !allowAgentCalls || connectorId == null || connectorId.isBlank() || connectorId.length() > 64)
            throw invalid("请求身份、连接器和 Agent 调用授权无效");
        List<String> selected = selection(names);
        String actor = access.currentSubject().memberId();
        String fingerprint = hashJson(new CommandFingerprint(connectorId, selected, allowAgentCalls));
        UUID owner = UUID.randomUUID();
        ToolOnboardingViews.Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant, requestId.toString(), actor, fingerprint, owner.toString(), connectorId, encodeNames(selected)) == 1) return null;
            return Objects.requireNonNull(existing(tenant, requestId, actor, fingerprint));
        });
        if (previous != null) return previous;
        CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(tenant);
        Step<Snapshot> initial = transactions.execute(status -> {
            try {
                context.bindTenant(tenant);
                ConnectorInstanceDO connector = connector(tenant, connectorId, false);
                providers.validate(tenant, ToolDeployment.ProviderType.MCP, connectorId, DEFAULT_ENVIRONMENT);
                Set<String> missing = selected.stream().filter(name -> catalog.findDefinitionForWrite(
                        scope, CapabilityType.TOOL, key(tenant, connectorId, name)).isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                return new Step<>(new Snapshot(connector, missing), null);
            } catch (ApiException failure) {
                if (!failure.getStatus().is4xxClientError()) throw failure;
                status.setRollbackOnly();
                return new Step<Snapshot>(null, failure);
            }
        });
        if (initial.failure() != null) return reject(tenant, requestId, owner, connectorId, selected, initial.failure());
        Snapshot snapshot = initial.value();
        Map<String, McpDiscoveryClient.Tool> discovered = Map.of();
        try {
            if (!snapshot.missing().isEmpty()) {
                ConnectorInstanceDO connector = snapshot.connector();
                McpConnectorDiscovery.validate(connector.getBaseUrl(), connector.getAuthType(),
                        connector.getVaultSecretPath(), connector.getHeaders(), connector.getAuthCredentialEncrypted());
                String credential = "none".equals(connector.getAuthType()) ? null
                        : McpConnectorDiscovery.decrypt(crypto, connector.getAuthCredentialEncrypted());
                discovered = McpConnectorDiscovery.list(discovery, connector, credential).tools().stream()
                        .collect(Collectors.toMap(McpDiscoveryClient.Tool::name, Function.identity()));
                for (String name : snapshot.missing()) {
                    if (!discovered.containsKey(name)) throw invalid("所选工具不在当前连接器发现目录中：" + name);
                }
            }
        } catch (ApiException failure) {
            // MCP 目录只读，BAD_GATEWAY 在业务事务开始前即可确定未接入。
            if (!failure.getStatus().is4xxClientError() && failure.getStatus() != HttpStatus.BAD_GATEWAY) throw failure;
            return reject(tenant, requestId, owner, connectorId, selected, failure);
        }
        Map<String, McpDiscoveryClient.Tool> tools = discovered;
        Step<ToolOnboardingViews.Receipt> outcome = transactions.execute(status -> {
            try {
                context.bindTenant(tenant);
                ConnectorInstanceDO current = connector(tenant, connectorId, true);
                providers.validate(tenant, ToolDeployment.ProviderType.MCP, connectorId, DEFAULT_ENVIRONMENT);
                if (!sameConnection(snapshot.connector(), current)) {
                    throw conflict("连接配置已变化，请重新读取目录后确认接入");
                }
                Map<String, CapabilityDeploymentView> deployments = catalog.listDeployments(scope, null).stream()
                        .collect(Collectors.toMap(CapabilityDeploymentView::deploymentKey, Function.identity()));
                for (String name : selected) {
                    String identity = key(tenant, connectorId, name);
                    CapabilityDefinitionView definition = catalog.findDefinitionForWrite(scope, CapabilityType.TOOL, identity)
                            .orElse(null);
                    CapabilityVersionView version;
                    if (definition == null) {
                        McpDiscoveryClient.Tool tool = tools.get(name);
                        if (tool == null) throw conflict("工具接入状态已变化，请先核对原选择");
                        Map<String, Object> descriptor = descriptor(tool);
                        String displayName = tool.title().filter(value -> !value.isBlank()).orElse(name).trim();
                        String description = tool.description() == null || tool.description().isBlank()
                                ? displayName : tool.description().trim();
                        var published = publications.publish(new CapabilityProducerPublicationRequest(scope,
                                CapabilityType.TOOL, identity, INITIAL_VERSION, displayName, description,
                                hashJson(Map.of("name", displayName, "description", description, "descriptor", descriptor)), descriptor));
                        definition = published.definition();
                        version = published.version();
                    } else {
                        // 已有稳定工具身份由管理面维护；接入不会改变其 policy、发布指针或追加修订。
                        if (!PUBLISHED.equals(definition.status())) throw conflict("已有工具尚不可用，请在高级管理中核对发布状态");
                        version = catalog.getCurrentPublishedVersion(scope, definition.id());
                        if (!name.equals(version.descriptor().get("tool_name"))) throw conflict("稳定工具身份与所选工具不一致");
                        if (!Boolean.TRUE.equals(version.descriptor().get("model_invocable"))
                                || !"NEVER".equals(version.descriptor().get("confirmation_policy"))) {
                            throw conflict("已有工具尚不可供 Agent 直接调用，请在高级授权配置核对发布；本次接入不会修改原策略");
                        }
                    }
                    CapabilityDeploymentView deployment = deployments.get(identity);
                    if (deployment == null) {
                        deployment = catalog.createDeployment(scope, identity, version.id(), ToolDeployment.ProviderType.MCP,
                                connectorId, DEFAULT_ENVIRONMENT, "ACTIVE");
                        deployments.put(identity, deployment);
                    } else {
                        CapabilityVersionView anchor = catalog.getVersion(scope, deployment.toolVersionId());
                        if (!definition.id().equals(anchor.definitionId())
                                || deployment.providerType() != ToolDeployment.ProviderType.MCP
                                || !connectorId.equals(deployment.providerRef())
                                || deployment.environment() != DEFAULT_ENVIRONMENT) {
                            throw conflict("已有默认服务绑定与所选连接器不一致");
                        }
                        if (!"ACTIVE".equals(deployment.status())) throw conflict("已有默认服务绑定已停用，请在高级管理中核对");
                    }
                }
                LinkedHashSet<String> allowed = new LinkedHashSet<>(current.getCapabilities() == null
                        ? List.of() : current.getCapabilities());
                if (allowed.addAll(selected)) {
                    ConnectorInstanceDO update = new ConnectorInstanceDO();
                    update.setId(current.getId());
                    update.setCapabilities(List.copyOf(allowed));
                    if (!connectors.updateById(update)) throw conflict("连接器工具名单更新失败");
                    current.setCapabilities(update.getCapabilities());
                }
                var selection = selectionResult(scope, current, selected);
                if (selection.items().stream().anyMatch(item -> item.status() != ToolOnboardingViews.Status.AVAILABLE))
                    throw conflict("所选工具的可用状态已变化，请重新核对");
                var items = selection.items().stream().map(item -> new ToolOnboardingViews.ReceiptItem(
                        item.toolName(), item.definitionId(), item.versionId(), item.deploymentKey(), item.environment())).toList();
                var receipt = new ToolOnboardingViews.Receipt(requestId, ToolOnboardingViews.OperationStatus.SUCCEEDED,
                        connectorId, selected, items, null, null);
                if (receipts.complete(tenant, requestId.toString(), owner.toString(), "SUCCEEDED", encode(receipt)) != 1)
                    throw new IllegalStateException("工具接入回执写入失败");
                return new Step<>(receipt, null);
            } catch (ApiException failure) {
                if (!failure.getStatus().is4xxClientError()) throw failure;
                status.setRollbackOnly();
                return new Step<ToolOnboardingViews.Receipt>(null, failure);
            }
        });
        return outcome.failure() == null ? outcome.value() : reject(tenant, requestId, owner, connectorId, selected, outcome.failure());
    }

    private ToolOnboardingViews.Receipt reject(Long tenant, UUID requestId, UUID owner,
            String connectorId, List<String> selected, ApiException failure) {
        String message = String.valueOf(failure.getDetail());
        if (message.length() > 512) message = message.substring(0, 512);
        var receipt = new ToolOnboardingViews.Receipt(requestId, ToolOnboardingViews.OperationStatus.REJECTED,
                connectorId, selected, null, failure.getCode(), message);
        String response = encode(receipt);
        transactions.executeWithoutResult(status -> {
            context.bindTenant(tenant);
            if (receipts.complete(tenant, requestId.toString(), owner.toString(), "REJECTED", response) != 1)
                throw new IllegalStateException("工具接入拒绝回执写入失败");
        });
        return receipt;
    }

    /** 只读原操作者的原命令状态或终态回执；未查到不能断言失败。 */
    @Transactional(readOnly = true)
    public ToolOnboardingViews.Receipt result(Long tenant, UUID requestId) {
        access.require("foundation.connectors.read");
        access.require("foundation.capabilities.read");
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        if (row == null || !access.currentSubject().memberId().equals(row.actorRef())) return null;
        return receipt(requestId, row);
    }

    private ToolOnboardingViews.Receipt existing(Long tenant, UUID requestId, String actor, String fingerprint) {
        var row = receipts.find(tenant, requestId.toString());
        if (row == null) return null;
        if (!actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_ONBOARD_REQUEST_CONFLICT",
                    "工具接入请求标识已用于不同参数或操作人", false);
        return receipt(requestId, row);
    }

    private ToolOnboardingViews.Receipt receipt(UUID requestId, ToolOnboardingReceiptMapper.Row row) {
        if ("PROCESSING".equals(row.status()))
            return new ToolOnboardingViews.Receipt(requestId, ToolOnboardingViews.OperationStatus.PROCESSING,
                    row.connectorId(), decodeNames(row.toolNamesJson()), null, null, null);
        return decode(row.responseJson());
    }

    private String encodeNames(List<String> names) {
        try { return json.writeValueAsString(names); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工具选择无法编码", failure); }
    }

    private List<String> decodeNames(String value) {
        try { return List.of(json.readValue(value, String[].class)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工具选择无法读取", failure); }
    }

    private String encode(ToolOnboardingViews.Receipt receipt) {
        try {
            String value = json.writeValueAsString(receipt);
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RECEIPT_BYTES)
                throw invalid("工具接入回执超过大小上限，请减少本次选择");
            return value;
        } catch (JsonProcessingException failure) { throw new IllegalStateException("工具接入回执无法编码", failure); }
    }

    private ToolOnboardingViews.Receipt decode(String value) {
        try { return json.readValue(value, ToolOnboardingViews.Receipt.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工具接入回执无法读取", failure); }
    }

    /** 只查询同一稳定选择的当前事实；缺失既不授权重建，也不证明原未知写未提交。 */
    @Transactional(readOnly = true)
    public ToolOnboardingViews.Selection onboarding(Long tenant, String connectorId, List<String> names) {
        context.bindTenant(tenant);
        access.require("foundation.connectors.read");
        access.require("foundation.capabilities.read");
        return selectionResult(CapabilityCatalogScope.tenant(tenant), connector(tenant, connectorId, false), selection(names));
    }

    @Transactional(readOnly = true)
    public ToolOnboardingViews.Available available(Long tenant) {
        context.bindTenant(tenant);
        access.require("foundation.capabilities.read");
        CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(tenant);
        var deployments = catalog.availableToolDeployments(scope);
        Map<String, ConnectorInstanceDO> activeConnectors = connectors.list(new LambdaQueryWrapper<ConnectorInstanceDO>()
                .select(ConnectorInstanceDO::getInstanceId, ConnectorInstanceDO::getName, ConnectorInstanceDO::getCapabilities)
                .eq(ConnectorInstanceDO::getTenantId, tenant).eq(ConnectorInstanceDO::getStatus, "active")
                .eq(ConnectorInstanceDO::getExecutorType, "mcp")).stream()
                .collect(Collectors.toMap(ConnectorInstanceDO::getInstanceId, Function.identity()));
        Map<Long, AvailableToolDeployment> current = new LinkedHashMap<>();
        Map<Long, List<ToolOnboardingViews.Deployment>> grouped = new LinkedHashMap<>();
        for (var deployment : deployments) {
            String label;
            if (deployment.providerType() == ToolDeployment.ProviderType.MCP) {
                ConnectorInstanceDO connector = activeConnectors.get(deployment.providerRef());
                if (connector == null || connector.getCapabilities() == null
                        || !connector.getCapabilities().contains(deployment.toolName())) continue;
                label = connector.getName();
            } else {
                if (!platformTools.names().contains(deployment.providerRef())) continue;
                label = deployment.providerRef();
            }
            current.putIfAbsent(deployment.definitionId(), deployment);
            grouped.computeIfAbsent(deployment.definitionId(), ignored -> new ArrayList<>()).add(
                    new ToolOnboardingViews.Deployment(deployment.deploymentKey(), deployment.providerType().name(),
                            deployment.providerRef(), label, deployment.environment().name()));
        }
        List<ToolOnboardingViews.AvailableTool> items = grouped.entrySet().stream().map(entry -> {
            var version = current.get(entry.getKey());
            return new ToolOnboardingViews.AvailableTool(version.definitionId().toString(), version.versionId().toString(),
                    version.name(), version.description(), version.toolName(),
                    List.copyOf(entry.getValue()));
        }).toList();
        return new ToolOnboardingViews.Available(items);
    }

    private ToolOnboardingViews.Selection selectionResult(CapabilityCatalogScope scope,
            ConnectorInstanceDO connector, List<String> selected) {
        Map<String, CapabilityDeploymentView> deployments = catalog.listDeployments(scope, null).stream()
                .collect(Collectors.toMap(CapabilityDeploymentView::deploymentKey, Function.identity()));
        List<ToolOnboardingViews.Item> items = new ArrayList<>();
        for (String name : selected) {
            String identity = key(scope.tenantId(), connector.getInstanceId(), name);
            var definition = catalog.findDefinitionForWrite(scope, CapabilityType.TOOL, identity);
            if (definition.isEmpty()) {
                items.add(new ToolOnboardingViews.Item(name, ToolOnboardingViews.Status.MISSING,
                        null, null, null, null, null, null, null, null, null));
                continue;
            }
            CapabilityDefinitionView stable = definition.orElseThrow();
            CapabilityVersionView version = PUBLISHED.equals(stable.status())
                    ? catalog.getCurrentPublishedVersion(scope, stable.id()) : null;
            CapabilityDeploymentView deployment = deployments.get(identity);
            boolean available = version != null && name.equals(version.descriptor().get("tool_name"))
                    && Boolean.TRUE.equals(version.descriptor().get("model_invocable"))
                    && "NEVER".equals(version.descriptor().get("confirmation_policy"))
                    && deployment != null && deployment.providerType() == ToolDeployment.ProviderType.MCP
                    && connector.getInstanceId().equals(deployment.providerRef())
                    && "ACTIVE".equals(deployment.status()) && "active".equals(connector.getStatus())
                    && connector.getCapabilities() != null && connector.getCapabilities().contains(name)
                    && stable.id().equals(catalog.getVersion(scope, deployment.toolVersionId()).definitionId());
            items.add(new ToolOnboardingViews.Item(name, available ? ToolOnboardingViews.Status.AVAILABLE
                    : ToolOnboardingViews.Status.UNAVAILABLE, stable.id().toString(),
                    version == null ? null : version.id().toString(), deployment == null ? null : deployment.deploymentKey(),
                    version == null ? stable.name() : version.name(), version == null ? stable.description() : version.description(),
                    connector.getInstanceId(), connector.getName(), deployment == null ? null : deployment.environment().name(),
                    version == null ? null : policySummary(version)));
        }
        return new ToolOnboardingViews.Selection(connector.getInstanceId(), List.copyOf(items));
    }

    private ConnectorInstanceDO connector(Long tenant, String instance, boolean lock) {
        if (instance == null || instance.isBlank()) throw invalid("请选择连接器");
        ConnectorInstanceDO value = connectors.getOne(new LambdaQueryWrapper<ConnectorInstanceDO>()
                .eq(ConnectorInstanceDO::getTenantId, tenant).eq(ConnectorInstanceDO::getInstanceId, instance)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (value == null || !"mcp".equals(value.getExecutorType())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MCP 连接器不存在");
        }
        return value;
    }

    private List<String> selection(List<String> names) {
        if (names == null || names.isEmpty() || names.size() > 100) throw invalid("请选择 1 至 100 项工具");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String name : names) {
            if (!com.skillforge.core.capability.ToolDefinition.supportsName(name))
                throw invalid("工具名需为 1 至 128 个英文字母、数字、下划线、连字符或点，请联系工具提供方调整名称");
            if (!values.add(name)) throw invalid("工具选择不能重复");
        }
        return List.copyOf(values);
    }

    private Map<String, Object> descriptor(McpDiscoveryClient.Tool tool) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("model_invocable", true);
        value.put("user_invocable", true);
        value.put("tool_name", tool.name());
        tool.title().ifPresent(title -> value.put("title", title));
        value.put("input_schema", tool.inputSchema());
        tool.outputSchema().ifPresent(schema -> value.put("output_schema", schema));
        value.put("icons", tool.icons().stream().map(icon -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("src", icon.src());
            icon.mimeType().ifPresent(type -> item.put("mimeType", type));
            item.put("sizes", icon.sizes());
            icon.theme().ifPresent(theme -> item.put("theme", theme));
            return item;
        }).toList());
        tool.annotations().ifPresent(annotations -> value.put("annotations", annotations));
        // 发布者已在 HTTP 边界显式授权 Agent 调用；远端 annotations 仍不能授予只读或安全重放语义。
        value.put("effect", "NON_IDEMPOTENT_WRITE");
        value.put("idempotent", false);
        value.put("confirmation_policy", "NEVER");
        value.put("timeout_ms", TIMEOUT_MS);
        value.put("parallel_safe", false);
        value.put("sensitive_fields", List.of());
        value.put("provider_requirements", List.of());
        return value;
    }

    private String policySummary(CapabilityVersionView version) {
        if (!Boolean.TRUE.equals(version.descriptor().get("model_invocable"))
                || !"NEVER".equals(version.descriptor().get("confirmation_policy"))) {
            return "当前策略需要高级授权配置，尚不可供 Agent 直接调用";
        }
        if ("NON_IDEMPOTENT_WRITE".equals(version.descriptor().get("effect"))) return POLICY_SUMMARY;
        return "沿用已发布工具策略；允许 Agent 调用";
    }

    private boolean sameConnection(ConnectorInstanceDO left, ConnectorInstanceDO right) {
        return Objects.equals(left.getBaseUrl(), right.getBaseUrl())
                && Objects.equals(left.getAuthType(), right.getAuthType())
                && Objects.equals(left.getAuthCredentialEncrypted(), right.getAuthCredentialEncrypted())
                && Objects.equals(left.getVaultSecretPath(), right.getVaultSecretPath())
                && Objects.equals(left.getHeaders(), right.getHeaders());
    }

    private String key(Long tenant, String connector, String name) {
        return "mcp-" + hash((tenant + "\0" + connector + "\0" + name).getBytes(StandardCharsets.UTF_8));
    }

    private String hashJson(Object value) {
        try {
            return hash(json.writeValueAsBytes(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("工具目录无法编码", failure);
        }
    }

    private String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private record CommandFingerprint(String connectorId, List<String> toolNames, boolean allowAgentCalls) {}
    private record Snapshot(ConnectorInstanceDO connector, Set<String> missing) {}
    private record Step<T>(T value, ApiException failure) {}
}
