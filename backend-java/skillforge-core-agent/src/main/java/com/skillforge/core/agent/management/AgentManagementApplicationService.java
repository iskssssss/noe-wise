package com.skillforge.core.agent.management;

import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.AgentPublicationService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.core.agent.capabilitymount.CapabilityMountNotFoundException;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand;
import com.skillforge.core.agent.capabilitymount.DeleteCapabilityMountCommand;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.agent.enums.AgentStatus;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.model.AgentDelegationConfigJsonbTypeHandler;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Agent 管理应用边界，拥有草稿、发布与挂载恢复的业务规则和事务。 */
@Service
@Transactional(rollbackFor = Exception.class)
public class AgentManagementApplicationService {
    /** UpdateWrapper 不读取实体字段注解，JSONB 参数必须显式绑定类型处理器。 */
    private static final String JSONB_PARAMETER_MAPPING = "typeHandler=" + JsonbTypeHandler.class.getName();
    private static final String DELEGATION_CONFIG_JSONB_PARAMETER_MAPPING =
            "typeHandler=" + AgentDelegationConfigJsonbTypeHandler.class.getName();

    private final AgentTemplateService templateService;
    private final AgentService agentService;
    private final CapabilityMountPersistenceService mounts;
    private final CapabilityCatalogDatabaseContext database;
    private final WorkflowTestRegistry tests;
    private final WorkflowTestIsolation isolation;
    private final FoundationAccess access;
    private final AgentConfigurationPolicy configurations;
    private final AgentPublicationService publications;
    private final FoundationOrganizationService organization;
    private final ResourceQuota quota;
    private final FeatureGate features;

    public AgentManagementApplicationService(AgentTemplateService templateService, AgentService agentService,
            CapabilityMountPersistenceService mounts, CapabilityCatalogDatabaseContext database, WorkflowTestRegistry tests,
            WorkflowTestIsolation isolation, FoundationAccess access,
            AgentConfigurationPolicy configurations, AgentPublicationService publications,
            FoundationOrganizationService organization, ResourceQuota quota, FeatureGate features) {
        this.templateService = templateService;
        this.agentService = agentService;
        this.mounts = mounts;
        this.database = database;
        this.tests = tests;
        this.isolation = isolation;
        this.access = access;
        this.configurations = configurations;
        this.publications = publications;
        this.organization = organization;
        this.quota = quota;
        this.features = features;
    }

    public List<AgentManagementContracts.Template> listTemplates(AgentManagementContracts.Caller caller) {
        requireOperation(caller, "foundation.agents.read");
        return templateService.currentPublishedLibrary().stream().map(this::templateView).toList();
    }

    public AgentManagementContracts.Template templateDetail(AgentManagementContracts.Caller caller, long id) {
        requireOperation(caller, "foundation.agents.read");
        AgentTemplateDO template = templateService.getById(id);
        if (template == null || !AgentConstants.STATUS_PUBLISHED.equals(template.getStatus()))
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 模板不存在或未发布");
        templateService.requireLibraryTemplate(template);
        return templateView(templateService.currentPublished(template.getId()));
    }

    /** 授权范围来自宿主当前可信主体；查询、排序与测试资源隔离由所属领域执行。 */
    public List<AgentManagementContracts.Agent> list(AgentManagementContracts.Caller caller) {
        requireCaller(caller);
        long tenantId = caller.tenantId();
        var scope = caller.external() ? new FoundationAccess.ResourceFilter(true, List.of()) : access.resourceFilter("agent", "read");
        var query = new LambdaQueryWrapper<AgentDO>().eq(AgentDO::getTenantId, tenantId);
        if (!scope.all()) {
            if (scope.resourceIds().isEmpty()) query.eq(AgentDO::getId, -1L);
            else query.in(AgentDO::getId, scope.resourceIds().stream().map(Long::valueOf).toList());
        }
        List<AgentDO> candidates = agentService.list(query.orderByDesc(AgentDO::getCreatedAt));
        var testAgentIds = tests.ownedAgentIds(tenantId, candidates.stream().map(AgentDO::getId).toList());
        List<AgentDO> agents = candidates.stream().filter(agent -> !testAgentIds.contains(agent.getId())).toList();
        Map<Long, String> templateNames = loadTemplateNames(agents);
        return agents.stream().map(agent -> view(agent, agent.getTemplateId() == null
                ? null : templateNames.get(agent.getTemplateId()))).toList();
    }

    /** 直接创建独立草稿；套餐检查和资源登记与 Agent 写入处于同一事务。 */
    public AgentManagementContracts.Agent create(AgentManagementContracts.Caller caller, AgentManagementContracts.Create request) {
        requireOperation(caller, "foundation.agents.create");
        long tenant = caller.tenantId();
        features.require(Long.toString(tenant), "agents");
        quota.requireCapacity(Long.toString(tenant), ResourceKind.AGENT, 1);
        String key = request.agentKey() == null || request.agentKey().isBlank()
                ? Long.toString(SnowflakeIds.nextId()) : request.agentKey();
        if (agentService.count(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenant).eq(AgentDO::getAgentKey, key)) > 0)
            throw new ApiException(HttpStatus.CONFLICT, "agent_key 已存在");
        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId()); agent.setAgentKey(key); agent.setTenantId(tenant);
        agent.setName(request.name().trim()); agent.setSystemPrompt(request.systemPrompt());
        agent.setModelConfigId(configurations.requireLlmModelId(request.modelConfigId()));
        agent.setConfig(AgentConfigurationPolicy.requireConfig(request.config()));
        agent.setStatus(AgentConstants.STATUS_DRAFT);
        agentService.save(agent);
        if (!caller.external()) organization.registerResource("agent", agent.getId().toString());
        return view(agent, null);
    }

    public AgentManagementContracts.Agent get(AgentManagementContracts.Caller caller, String agentId) {
        requireResource(caller, agentId, "read");
        AgentDO agent = findAgent(caller, agentId);
        return view(agent, templateName(agent));
    }

    public AgentManagementContracts.Detail detail(AgentManagementContracts.Caller caller, String agentId) {
        requireResource(caller, agentId, "read");
        AgentDO agent = findAgent(caller, agentId);
        database.bindTenant(caller.tenantId());
        try {
            return new AgentManagementContracts.Detail(view(agent, templateName(agent)), mounts.read(caller.tenantId(), agent.getId()));
        } catch (CapabilityMountNotFoundException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 或 CapabilityMount 装配不存在");
        }
    }

    public AgentManagementContracts.Agent update(AgentManagementContracts.Caller caller, String agentId, AgentManagementContracts.Update request) {
        requireManageAndRead(caller, agentId);
        if (request.status() == AgentStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请通过发布接口发布智能体");
        }
        AgentDO found = findAgent(caller, agentId);
        AgentDO agent = agentService.lockVersion(found.getTenantId(), found.getId());
        if (request.name() != null || request.systemPrompt() != null || request.roleDescription() != null
                || request.executionConfig() != null || request.iconUrl() != null
                || request.config() != null || request.modelConfigId() != null || request.delegationConfig() != null) {
            agentService.requireEditable(agent);
        }
        if (request.status() == AgentStatus.DRAFT && (agent.getPublishedAt() != null
                || !AgentConstants.STATUS_DRAFT.equals(agent.getStatus()))) {
            throw new ApiException(HttpStatus.CONFLICT, "已发布智能体保留当前发布状态，草稿配置可直接编辑");
        }
        LambdaUpdateWrapper<AgentDO> update = new LambdaUpdateWrapper<>();
        update.eq(AgentDO::getId, agent.getId())
                .eq(AgentDO::getTenantId, agent.getTenantId());
        boolean changed = false;
        if (request.name() != null) {
            agent.setName(request.name());
            update.set(AgentDO::getName, agent.getName());
            changed = true;
        }
        if (request.status() != null) {
            if (request.status() != AgentStatus.DRAFT
                    && request.status() != AgentStatus.DISABLED
                    && request.status() != AgentStatus.ARCHIVED) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "status 必须是 draft / disabled / archived，发布请使用发布接口");
            }
            agent.setStatus(request.status().code());
            update.set(AgentDO::getStatus, agent.getStatus());
            changed = true;
        }
        if (request.systemPrompt() != null) {
            agent.setSystemPrompt(request.systemPrompt());
            update.set(AgentDO::getSystemPrompt, agent.getSystemPrompt());
            changed = true;
        }
        if (request.roleDescription() != null) {
            agent.setRoleDescription(request.roleDescription().isBlank()
                    ? null : request.roleDescription());
            update.set(AgentDO::getRoleDescription, agent.getRoleDescription());
            changed = true;
        }
        if (request.executionConfig() != null) {
            com.skillforge.core.foundation.runtimeconfig.AgentBudgetValidation.validate(request.executionConfig());
            agent.setExecutionConfig(request.executionConfig());
            update.set(AgentDO::getMaxBusinessModelCalls, agent.getMaxBusinessModelCalls())
                    .set(AgentDO::getMaxToolCalls, agent.getMaxToolCalls())
                    .set(AgentDO::getMaxKnowledgeCalls, agent.getMaxKnowledgeCalls())
                    .set(AgentDO::getMaxTotalTokens, agent.getMaxTotalTokens())
                    .set(AgentDO::getMaxWallTimeSeconds, agent.getMaxWallTimeSeconds());
            changed = true;
        }
        if (request.iconUrl() != null) {
            agent.setIconUrl(blankToNull(request.iconUrl()));
            update.set(AgentDO::getIconUrl, agent.getIconUrl());
            changed = true;
        }
        if (request.config() != null) {
            agent.setConfig(AgentConfigurationPolicy.requireConfig(request.config()));
            update.set(AgentDO::getConfig, agent.getConfig(), JSONB_PARAMETER_MAPPING);
            changed = true;
        }
        if (request.modelConfigId() != null) {
            agent.setModelConfigId(configurations.requireLlmModelId(request.modelConfigId()));
            update.set(AgentDO::getModelConfigId, agent.getModelConfigId());
            changed = true;
        }
        if (request.delegationConfig() != null) {
            agent.setDelegationConfig(configurations.requireDelegationConfig(delegationSource(agent), request.delegationConfig()));
            update.set(AgentDO::getDelegationConfig, agent.getDelegationConfig(),
                    DELEGATION_CONFIG_JSONB_PARAMETER_MAPPING);
            changed = true;
        }
        boolean definitionChanged = request.name() != null || request.systemPrompt() != null
                || request.roleDescription() != null || request.executionConfig() != null || request.iconUrl() != null
                || request.config() != null || request.modelConfigId() != null || request.delegationConfig() != null;
        if (definitionChanged) {
            agent.setHasDraftChanges(true);
            update.set(AgentDO::getHasDraftChanges, true);
        }
        if (changed) {
            agentService.update(update);
        }
        return view(agent, null);
    }

    public void delete(AgentManagementContracts.Caller caller, String agentId) {
        requireManageAndRead(caller, agentId);
        AgentDO found = findAgent(caller, agentId);
        AgentDO agent = agentService.lockVersion(found.getTenantId(), found.getId());
        if (agent.getPublishedAt() != null || AgentConstants.STATUS_PUBLISHED.equals(agent.getStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "已发布版本请停用或归档，不能删除");
        agentService.removeById(agent.getId());
        if (caller.external()) organization.forgetResourceForTenant(Long.toString(caller.tenantId()), "agent", agentId);
        else organization.forgetResource("agent", agentId);
    }

    public long publish(AgentManagementContracts.Caller caller, String agentId) {
        requireManageAndRead(caller, agentId);
        AgentDO found = findAgent(caller, agentId);
        AgentDO agent = agentService.lockVersion(found.getTenantId(), found.getId());
        agentService.requireIndependentDefinition(agent);
        requireReady(agent);
        publications.publish(agent);
        return agent.getId();
    }

    /** 保存与发布共用当前身份事务；先核原回执，重试不写草稿或改变当前发布指针。 */
    public AgentManagementContracts.SaveAndPublishReceipt saveAndPublish(AgentManagementContracts.Caller caller,
            String agentId, java.util.UUID requestId, AgentManagementContracts.Update payload) {
        if (caller.external()) throw new ApiException(HttpStatus.FORBIDDEN, "保存并发布仅供当前机构成员使用");
        requireOperation(caller, "foundation.agents.update");
        requireOperation(caller, "foundation.agents.publish");
        requireManageAndRead(caller, agentId);
        if (requestId == null || payload == null) throw new ApiException(HttpStatus.BAD_REQUEST, "请求身份和更新内容不能为空");
        if (payload.status() != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "保存并发布不接受状态修改");
        AgentDO found = findAgent(caller, agentId);
        AgentDO agent = agentService.lockVersion(found.getTenantId(), found.getId());
        String actor = "member:" + access.currentSubject().memberId();
        String fingerprint = publications.requestFingerprint(caller.tenantId(), agent.getId(), actor, payload);
        var previous = publications.findRequest(caller.tenantId(), agent.getId(), requestId);
        if (previous != null) {
            if (!actor.equals(previous.requestActorRef()) || !fingerprint.equals(previous.requestFingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_SAVE_PUBLISH_REQUEST_CONFLICT",
                        "保存发布请求标识已用于不同参数或操作人", false);
            return new AgentManagementContracts.SaveAndPublishReceipt(requestId, previous.agentId(), previous.id(),
                    previous.revision(), previous.publishedAt());
        }
        update(caller, agentId, payload);
        // 复用当前事务中的自身更新；无需发布前的另一次 HTTP 读取。
        agent = agentService.lockVersion(found.getTenantId(), found.getId());
        agentService.requireIndependentDefinition(agent);
        requireReady(agent);
        var published = publications.publish(agent, new AgentPublicationService.RequestIdentity(requestId, fingerprint, actor));
        return new AgentManagementContracts.SaveAndPublishReceipt(requestId, agent.getId(), published.revisionId(),
                published.revision(), published.publishedAt());
    }

    /** 当前读权限只允许核对原操作人的成功结果；缺失不证明没有在途事务，也不赋予执行权。 */
    @Transactional(readOnly = true)
    public AgentManagementContracts.SaveAndPublishReceipt saveAndPublishResult(AgentManagementContracts.Caller caller,
            String agentId, java.util.UUID requestId) {
        if (caller.external()) throw new ApiException(HttpStatus.FORBIDDEN, "保存发布回执仅供当前机构成员使用");
        requireOperation(caller, "foundation.agents.read");
        requireResource(caller, agentId, "read");
        if (requestId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "请求身份不能为空");
        AgentDO agent = findAgent(caller, agentId);
        var previous = publications.findRequest(caller.tenantId(), agent.getId(), requestId);
        if (previous == null) return null;
        if (!("member:" + access.currentSubject().memberId()).equals(previous.requestActorRef()))
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 保存发布回执不存在");
        return new AgentManagementContracts.SaveAndPublishReceipt(requestId, previous.agentId(), previous.id(),
                previous.revision(), previous.publishedAt());
    }

    /** 打开同一身份的工作草稿，保存不会产生新身份或新发布历史。 */
    public AgentManagementContracts.Agent revise(AgentManagementContracts.Caller caller, String agentId) {
        requireOperation(caller, "foundation.agents.create");
        requireManageAndRead(caller, agentId);
        AgentDO agent = findAgent(caller, agentId);
        agentService.requireEditable(agent);
        return view(agent, null);
    }

    public List<AgentManagementContracts.Revision> revisions(AgentManagementContracts.Caller caller, String agentId) {
        requireResource(caller, agentId, "read");
        AgentDO agent = findAgent(caller, agentId);
        return publications.history(agent.getTenantId(), agent.getId()).stream()
                .map(row -> new AgentManagementContracts.Revision(row.id(), row.revision(), row.publishedAt(), row.name(),
                        java.util.Objects.equals(row.id(), agent.getPublishedRevisionId()))).toList();
    }

    public AgentManagementContracts.Detail revisionDetail(AgentManagementContracts.Caller caller, String agentId, int revision) {
        requireResource(caller, agentId, "read");
        AgentDO identity = findAgent(caller, agentId);
        var publication = publications.revision(identity.getTenantId(), identity.getId(), revision);
        return new AgentManagementContracts.Detail(view(publication.agent(), templateName(publication.agent())), publication.mountSet());
    }

    /** 恢复只替换工作草稿和挂载，当前发布指针保持不变。 */
    public AgentManagementContracts.Agent restoreRevision(AgentManagementContracts.Caller caller, String agentId, int revision) {
        requireManageAndRead(caller, agentId);
        AgentDO found = findAgent(caller, agentId);
        AgentDO agent = agentService.lockVersion(found.getTenantId(), found.getId());
        agentService.requireEditable(agent);
        var publication = publications.revision(agent.getTenantId(), agent.getId(), revision);
        AgentDO previous = publication.agent();
        agent.setName(previous.getName()); agent.setSystemPrompt(previous.getSystemPrompt());
        agent.setRoleDescription(previous.getRoleDescription()); agent.setIconUrl(previous.getIconUrl());
        agent.setExecutionConfig(previous.getExecutionConfig()); agent.setModelConfigId(previous.getModelConfigId());
        agent.setConfig(previous.getConfig()); agent.setDelegationConfig(previous.getDelegationConfig());
        agent.setHasDraftChanges(true);
        agentService.update(new LambdaUpdateWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, agent.getTenantId()).eq(AgentDO::getId, agent.getId())
                .set(AgentDO::getName, agent.getName()).set(AgentDO::getSystemPrompt, agent.getSystemPrompt())
                .set(AgentDO::getRoleDescription, agent.getRoleDescription()).set(AgentDO::getIconUrl, agent.getIconUrl())
                .set(AgentDO::getModelConfigId, agent.getModelConfigId())
                .set(AgentDO::getMaxBusinessModelCalls, agent.getMaxBusinessModelCalls()).set(AgentDO::getMaxToolCalls, agent.getMaxToolCalls())
                .set(AgentDO::getMaxKnowledgeCalls, agent.getMaxKnowledgeCalls())
                .set(AgentDO::getMaxTotalTokens, agent.getMaxTotalTokens())
                .set(AgentDO::getMaxWallTimeSeconds, agent.getMaxWallTimeSeconds())
                .set(AgentDO::getConfig, agent.getConfig(), JSONB_PARAMETER_MAPPING)
                .set(AgentDO::getDelegationConfig, agent.getDelegationConfig(), DELEGATION_CONFIG_JSONB_PARAMETER_MAPPING)
                .set(AgentDO::getHasDraftChanges, true));
        restoreMounts(agent, publication.mountSet());
        return view(agent, null);
    }

    private void restoreMounts(AgentDO agent, CapabilityMountSetResult snapshot) {
        database.bindTenant(agent.getTenantId());
        agentService.markDraftChanged(agent.getTenantId(), agent.getId());
        for (var current : mounts.read(agent.getTenantId(), agent.getId()).mounts()) {
            mounts.delete(new DeleteCapabilityMountCommand(agent.getTenantId(), agent.getId(), current.mountId(), current.revision()));
        }
        for (var mount : snapshot.mounts()) {
            mounts.create(new CreateCapabilityMountCommand(agent.getTenantId(), agent.getId(), mount.capability(),
                    mount.config(), mount.priority(), mount.active() ? CapabilityMountStatus.ACTIVE : CapabilityMountStatus.DISABLED));
        }
    }

    /** 当前成员权限与测试隔离属于应用边界，直接调用应用服务也不能绕过。 */
    private void requireResource(AgentManagementContracts.Caller caller, String agentId, String action) {
        requireCaller(caller);
        isolation.requireOrdinary(caller.tenantId(), "agent", agentId);
        if (!caller.external()) access.requireResource("agent", agentId, action);
    }

    private void requireManageAndRead(AgentManagementContracts.Caller caller, String agentId) {
        requireResource(caller, agentId, "manage");
        requireResource(caller, agentId, "read");
    }

    private void requireOperation(AgentManagementContracts.Caller caller, String permission) {
        requireCaller(caller);
        if (!caller.external()) access.require(permission);
    }

    private void requireCaller(AgentManagementContracts.Caller caller) {
        String tenant = Long.toString(caller.tenantId());
        if (!tenant.equals(TenantContext.getTenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "租户上下文与资源归属不匹配");
        if (!caller.external()) {
            var subject = access.currentSubject();
            if (subject.platform() || !tenant.equals(subject.tenantId()))
                throw new ApiException(HttpStatus.FORBIDDEN, "租户上下文与当前成员不匹配");
        }
    }

    private AgentDO findAgent(AgentManagementContracts.Caller caller, String agentId) {
        long id;
        try { id = Long.parseLong(agentId); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在"); }
        AgentDO agent = agentService.getOne(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getId, id).eq(AgentDO::getTenantId, caller.tenantId()));
        if (agent == null) throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在");
        return agent;
    }

    private Map<Long, String> loadTemplateNames(List<AgentDO> agents) {
        List<Long> ids = agents.stream().map(AgentDO::getTemplateId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> names = new LinkedHashMap<>();
        templateService.listByIds(ids).forEach(template -> names.put(template.getId(), template.getName()));
        return names;
    }

    private String templateName(AgentDO agent) {
        AgentTemplateDO template = agent.getTemplateId() == null ? null : templateService.getById(agent.getTemplateId());
        return template == null ? null : template.getName();
    }

    private AgentManagementContracts.Template templateView(AgentTemplateDO template) {
        return new AgentManagementContracts.Template(template.getId(), template.getTemplateKey(), template.getName(),
                template.getVersion(), template.getDescription(), template.getIconUrl(), template.getDefinition());
    }

    private AgentManagementContracts.Agent view(AgentDO agent, String templateName) {
        return new AgentManagementContracts.Agent(agent.getId(), agent.getTenantId(), agent.getAgentKey(), agent.getName(),
                agent.getTemplateId(), templateName, AgentStatus.fromCode(agent.getStatus()), agent.getCreatedAt(),
                agent.getSystemPrompt(), agent.getRoleDescription(), agent.getExecutionConfig(), agent.getModelConfigId(),
                agent.getConfig(), agent.getPublishedAt(), agent.getDelegationConfig(), agent.getIconUrl(), agent.getRevision(),
                agent.getPublishedRevisionId(), Boolean.TRUE.equals(agent.getHasDraftChanges()));
    }

    private void requireReady(AgentDO agent) {
        configurations.requireReady(new AgentConfigurationPolicy.ReadyConfiguration(agent.getTenantId(), agent.getSystemPrompt(),
                agent.getModelConfigId(), agent.getExecutionConfig(), agent.getConfig()));
    }

    private AgentConfigurationPolicy.DelegationSource delegationSource(AgentDO agent) {
        return new AgentConfigurationPolicy.DelegationSource(agent.getTenantId(), agent.getId(), agent.getDelegationConfig());
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
