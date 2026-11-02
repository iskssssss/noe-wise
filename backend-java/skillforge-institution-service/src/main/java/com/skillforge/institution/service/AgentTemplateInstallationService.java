package com.skillforge.institution.service;

import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.agent.dto.request.*;
import com.skillforge.core.agent.dto.response.*;
import com.skillforge.core.agent.mapper.AgentTemplateInstallationMapper;
import com.skillforge.core.agent.model.*;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.common.TenantIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将公共模板安装成机构独立 Agent；数据库副作用和幂等回执只有一个事务提交点。 */
@Service
public class AgentTemplateInstallationService {
    private final AgentTemplateService templates;
    private final AgentService agents;
    private final AgentManagementService management;
    private final com.skillforge.core.agent.management.AgentConfigurationPolicy configurations;
    private final CapabilityMountManagementService mounts;
    private final CapabilityCatalogService catalog;
    private final CapabilityCatalogDatabaseContext context;
    private final TenantToolDeploymentProviderValidator providers;
    private final AgentTemplateInstallationMapper receipts;
    private final ObjectMapper json;
    private final com.skillforge.core.foundation.access.FoundationOrganizationService organization;
    private final com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota;
    private final com.skillforge.core.foundation.catalog.entitlements.FeatureGate features;

    public AgentTemplateInstallationService(AgentTemplateService templates, AgentService agents,
            AgentManagementService management, com.skillforge.core.agent.management.AgentConfigurationPolicy configurations,
            CapabilityMountManagementService mounts,
            CapabilityCatalogService catalog, CapabilityCatalogDatabaseContext context,
            TenantToolDeploymentProviderValidator providers, AgentTemplateInstallationMapper receipts,
            ObjectMapper json,
            com.skillforge.core.foundation.access.FoundationOrganizationService organization,
            com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota,
            com.skillforge.core.foundation.catalog.entitlements.FeatureGate features) {
        this.organization = organization; this.quota = quota; this.features = features;
        this.templates = templates; this.agents = agents; this.management = management; this.configurations = configurations; this.mounts = mounts;
        this.catalog = catalog; this.context = context; this.providers = providers; this.receipts = receipts;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** 锁定顺序为请求→模板族→模板；失败回滚全部实例、部署、挂载和计数。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentTemplateInstallationResponse install(InstallAgentTemplateRequest request) {
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        context.bindTenant(tenant);
        String fingerprint = fingerprint(request);
        receipts.lockRequest(tenant, request.requestId());
        var previous = receipts.find(tenant, request.requestId());
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) throw conflict("安装请求标识已用于不同参数");
            if (previous.agentId() == null) throw conflict("该安装创建的 Agent 已删除，请使用新的安装请求");
            return response(tenant, previous.agentId());
        }
        features.require(Long.toString(tenant), "agents");
        quota.requireCapacity(Long.toString(tenant), com.skillforge.core.foundation.catalog.entitlements.ResourceKind.AGENT, 1);
        AgentTemplateDO template = templates.getById(request.templateId());
        if (template == null) throw new ApiException(HttpStatus.NOT_FOUND, "Agent 模板不存在");
        templates.lockFamily(template.getTemplateKey());
        template = templates.lockTemplate(template.getId());
        if (!"published".equals(template.getStatus())) throw invalid("Agent 模板未发布");
        templates.requireLibraryTemplate(template);
        template = templates.currentPublished(template.getId());
        templates.requireInstallable(template);
        var definition = templates.resolveCurrentDefinition(template.getDefinition());
        validateBindings(definition, request);

        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId()); agent.setTenantId(tenant); agent.setAgentKey("agent-" + agent.getId());
        agent.setTemplateId(template.getId()); agent.setTemplateVersion(template.getVersion());
        agent.setName(request.name() == null || request.name().isBlank() ? template.getName() : request.name().trim());
        agent.setIconUrl(template.getIconUrl()); agent.setSystemPrompt(definition.systemPrompt());
        agent.setRoleDescription(definition.roleDescription()); agent.setModelConfigId(Long.valueOf(definition.modelConfigId()));
        agent.setExecutionConfig(definition.executionConfig()); agent.setConfig(Map.of());
        List<String> targetIds = request.delegationBindings().stream().map(InstallAgentTemplateRequest.DelegationBinding::targetAgentId).toList();
        agent.setDelegationConfig(configurations.requireDelegationConfig(new com.skillforge.core.agent.management.AgentConfigurationPolicy.DelegationSource(
                tenant, agent.getId(), agent.getDelegationConfig()), new AgentDelegationConfig(!targetIds.isEmpty(), targetIds)));
        agent.setStatus("draft"); agent.setCreatedAt(Instant.now()); agent.setUpdatedAt(agent.getCreatedAt());
        configurations.requireReady(new com.skillforge.core.agent.management.AgentConfigurationPolicy.ReadyConfiguration(tenant,
                agent.getSystemPrompt(), agent.getModelConfigId(), agent.getExecutionConfig(), agent.getConfig()));
        if (!agents.save(agent)) throw new IllegalStateException("Agent 安装写入失败");
        organization.registerResource("agent", agent.getId().toString());
        for (var capability : definition.capabilities()) {
            ToolMountConfigRequest toolConfig = null;
            if (capability.capabilityType() == CapabilityType.TOOL) {
                providers.validate(tenant, ToolDeployment.ProviderType.PLATFORM, capability.platformToolName(), ToolDeployment.Environment.PRODUCTION);
                var deployment = catalog.createDeployment(CapabilityCatalogScope.tenant(tenant),
                        "agent-" + agent.getId() + "-tool-" + capability.capabilityDefinitionId(), capability.capabilityVersionId(),
                        ToolDeployment.ProviderType.PLATFORM, capability.platformToolName(), ToolDeployment.Environment.PRODUCTION, "ACTIVE");
                toolConfig = new ToolMountConfigRequest(deployment.deploymentKey(), deployment.revision());
            }
            mounts.create(tenant, agent.getId(), new CapabilityMountRequest(capability.capabilityType(),
                    capability.capabilityDefinitionId(), capability.capabilityVersionId(), capability.skillConfig(),
                    toolConfig, null, null, capability.priority(), CapabilityMountStatus.ACTIVE));
        }
        for (var binding : request.resourceBindings()) mounts.create(tenant, agent.getId(), binding.mount());
        management.publish(agent.getId().toString());
        templates.recordInstallation(template.getId());
        if (receipts.insert(tenant, request.requestId(), fingerprint, template.getId(), agent.getId()) != 1)
            throw new IllegalStateException("Agent 安装回执写入失败");
        return response(tenant, agent.getId());
    }

    /** 只读取已有安装回执，不重放安装；Agent 仍按当前主体权限读取。 */
    @Transactional(readOnly = true)
    public InstallationReceipt result(String requestId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{1,128}"))
            throw invalid("安装请求标识无效");
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        context.bindTenant(tenant);
        var receipt = receipts.find(tenant, requestId);
        if (receipt == null || receipt.agentId() == null) return null;
        var agent = management.getAgentItem(receipt.agentId().toString());
        return new InstallationReceipt(requestId, agent.id().toString());
    }
    public record InstallationReceipt(@com.fasterxml.jackson.annotation.JsonProperty("request_id") String requestId,
            @com.fasterxml.jackson.annotation.JsonProperty("agent_id") String agentId) { }

    private void validateBindings(AgentTemplateDefinition definition, InstallAgentTemplateRequest request) {
        Map<String, CapabilityType> resources = new HashMap<>();
        definition.resourceSlots().forEach(slot -> resources.put(slot.key(), slot.capabilityType()));
        Set<String> selected = new HashSet<>();
        for (var binding : request.resourceBindings()) {
            var mount = binding.mount();
            if (!selected.add(binding.slotKey()) || !resources.containsKey(binding.slotKey()) || mount == null
                    || resources.get(binding.slotKey()) != mount.capabilityType()
                    || mount.status() != null && mount.status() != CapabilityMountStatus.ACTIVE)
                throw invalid("增强资源配置重复、未声明或与声明类型不符");
        }
        Set<String> roles = new HashSet<>();
        definition.delegationSlots().forEach(slot -> roles.add(slot.key())); selected.clear();
        for (var binding : request.delegationBindings()) {
            if (!roles.contains(binding.slotKey()) || !selected.add(binding.slotKey()))
                throw invalid("协作角色重复或不属于当前模板");
        }
    }

    private AgentTemplateInstallationResponse response(long tenantId, long agentId) {
        return new AgentTemplateInstallationResponse(management.getAgentItem(Long.toString(agentId)),
                CapabilityMountSetResponse.from(mounts.read(tenantId, agentId)));
    }
    private String fingerprint(InstallAgentTemplateRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("安装请求摘要生成失败", e); }
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
