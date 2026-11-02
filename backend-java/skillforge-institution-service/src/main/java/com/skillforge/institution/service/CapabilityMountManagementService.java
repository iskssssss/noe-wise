package com.skillforge.institution.service;

import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountNotFoundException;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountRevisionConflictException;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand;
import com.skillforge.core.agent.capabilitymount.DeleteCapabilityMountCommand;
import com.skillforge.core.agent.capabilitymount.UpdateCapabilityMountCommand;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.KnowledgeMountConfigRequest;
import com.skillforge.core.agent.dto.request.SkillMountConfigRequest;
import com.skillforge.core.agent.dto.request.ToolMountConfigRequest;
import com.skillforge.core.agent.dto.request.WorkflowMountConfigRequest;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityMount.MountConfig;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.shared.exception.ApiException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 机构 Agent CapabilityMount 管理边界。
 *
 * <p>该服务是四类挂载的唯一引用校验入口：先读取并校验所有 Catalog、Deployment、
 * Workflow 和 Knowledge 事实，再把已经构造好的强类型命令交给 core-agent
 * persistence。任何引用不满足合同的请求都不会进入 mount mutation。
 */
@Service
public class CapabilityMountManagementService {

    private static final String CATALOG_PUBLISHED = "PUBLISHED";
    private static final String DEPLOYMENT_ACTIVE = "ACTIVE";

    private final WorkflowReferenceService workflows;
    private final KnowledgeReferenceService knowledge;
    private final CapabilityMountPersistenceService persistence;
    private final AgentService agents;
    private final InstitutionResourceAccess access;
    private final CapabilityCatalogService catalog;
    private final CapabilityCatalogDatabaseContext catalogDatabaseContext;

    @Autowired
    public CapabilityMountManagementService(
            WorkflowReferenceService workflows, KnowledgeReferenceService knowledge,
            CapabilityMountPersistenceService persistence,
            CapabilityCatalogService catalog,
            CapabilityCatalogDatabaseContext catalogDatabaseContext,
            AgentService agents, InstitutionResourceAccess access) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.access = access;
        this.agents = Objects.requireNonNull(agents, "agents");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.catalogDatabaseContext = Objects.requireNonNull(
                catalogDatabaseContext, "catalogDatabaseContext");
    }

    /** 读取当前 Agent 装配；空装配首次读取建立 generation=1。 */
    @Transactional(rollbackFor = Exception.class)
    public com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult read(
            Long tenantId, Long agentId) {
        bindTenant(tenantId);
        access.require("agent", agentId.toString(), "read");
        try {
            return persistence.read(requirePositive(tenantId, "tenantId"),
                    requireAgentId(agentId));
        } catch (CapabilityMountNotFoundException exception) {
            throw notFound("Agent 或 CapabilityMount 装配不存在");
        }
    }

    /** 创建一个经过完整引用校验的 CapabilityMount。 */
    @Transactional(rollbackFor = Exception.class)
    public com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult create(
            Long tenantId, Long agentId, CapabilityMountRequest request) {
        long tenant = requirePositive(tenantId, "tenantId");
        long agent = requireAgentId(agentId);
        bindTenant(tenant);
        requireEditable(tenant, agent);
        ValidatedMount validated = validateRequest(tenant, request);

        // 不调用会隐式 ensure 的 read()，否则首次成功 create 的 generation 会错误地
        // 从 1 变成 2。数据库唯一约束仍是并发竞态的最终保护。
        Optional<com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult> existing =
                persistence.readIfPresent(tenant, agent);
        if (existing.isPresent() && existing.get().mounts().stream().anyMatch(mount ->
                mount.capability().capabilityId().equals(validated.capability().capabilityId()))) {
            throw conflict("同一 Agent 已挂载该 capability definition");
        }
        try {
            return persistence.create(new CreateCapabilityMountCommand(
                    tenant, agent, validated.capability(), validated.config(),
                    request.priority() == null ? 0 : request.priority(),
                    status(request)));
        } catch (CapabilityMountNotFoundException exception) {
            throw notFound("CapabilityMount 引用资源不存在");
        } catch (CapabilityMountRevisionConflictException exception) {
            throw conflict("CapabilityMount revision 冲突");
        } catch (DataIntegrityViolationException exception) {
            throw conflict("同一 Agent 已挂载该 capability definition");
        } catch (IllegalArgumentException exception) {
            throw invalid("CapabilityMount 强类型配置非法", exception);
        }
    }

    /** 以 mount revision CAS 更新 CapabilityMount。 */
    @Transactional(rollbackFor = Exception.class)
    public com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult update(
            Long tenantId, Long agentId, String mountId, long expectedRevision,
            CapabilityMountRequest request) {
        long tenant = requirePositive(tenantId, "tenantId");
        long agent = requireAgentId(agentId);
        bindTenant(tenant);
        requireEditable(tenant, agent);
        if (expectedRevision <= 0) {
            throw invalid("If-Match revision 必须为正数", null);
        }
        com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult currentSet =
                persistence.readIfPresent(tenant, agent).orElseThrow(
                        () -> notFound("CapabilityMount 不存在"));
        CapabilityMount current = currentSet.mounts().stream()
                .filter(mount -> mount.mountId().equals(mountId))
                .findFirst()
                .orElseThrow(() -> notFound("CapabilityMount 不存在"));
        if (current.revision() != expectedRevision) {
            throw conflict("CapabilityMount revision 已过期");
        }

        ValidatedMount validated = validateRequest(tenant, request);
        if (current.capability().type() != validated.capability().type()
                || !current.capability().capabilityId().equals(
                        validated.capability().capabilityId())) {
            throw invalid("CapabilityMount 的 capability type/definition identity 不可修改", null);
        }
        try {
            return persistence.update(new UpdateCapabilityMountCommand(
                    tenant, agent, mountId, expectedRevision, validated.capability(),
                    validated.config(), request.priority() == null ? 0 : request.priority(),
                    status(request)));
        } catch (CapabilityMountNotFoundException exception) {
            throw notFound("CapabilityMount 不存在");
        } catch (CapabilityMountRevisionConflictException exception) {
            throw conflict("CapabilityMount revision 已过期");
        } catch (DataIntegrityViolationException exception) {
            throw invalid("CapabilityMount 引用约束不满足", exception);
        } catch (IllegalArgumentException exception) {
            throw invalid("CapabilityMount 强类型配置非法", exception);
        }
    }

    /** 替换工作流引用时保留挂载自身的优先级和启用状态。 */
    @Transactional(rollbackFor = Exception.class)
    public com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult updateWorkflowReference(
            Long tenantId, Long agentId, String mountId, long expectedRevision, CapabilityMountRequest request) {
        bindTenant(tenantId);
        var existing = persistence.readIfPresent(tenantId, agentId).orElseThrow(() -> notFound("CapabilityMount 不存在"))
                .mounts().stream().filter(value -> value.mountId().equals(mountId)).findFirst()
                .orElseThrow(() -> notFound("CapabilityMount 不存在"));
        var preserved = new CapabilityMountRequest(request.capabilityType(), request.capabilityDefinitionId(),
                request.capabilityVersionId(), request.skillConfig(), request.toolConfig(), request.workflowConfig(),
                request.knowledgeConfig(), existing.priority(), existing.active() ? CapabilityMountStatus.ACTIVE : CapabilityMountStatus.DISABLED);
        return update(tenantId, agentId, mountId, expectedRevision, preserved);
    }

    /** 以 mount revision CAS 删除 CapabilityMount。 */
    @Transactional(rollbackFor = Exception.class)
    public com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult delete(
            Long tenantId, Long agentId, String mountId, long expectedRevision) {
        long tenant = requirePositive(tenantId, "tenantId");
        long agent = requireAgentId(agentId);
        bindTenant(tenant);
        requireEditable(tenant, agent);
        if (expectedRevision <= 0) {
            throw invalid("If-Match revision 必须为正数", null);
        }
        try {
            return persistence.delete(new DeleteCapabilityMountCommand(
                    tenant, agent, mountId, expectedRevision));
        } catch (CapabilityMountNotFoundException exception) {
            throw notFound("CapabilityMount 不存在");
        } catch (CapabilityMountRevisionConflictException exception) {
            throw conflict("CapabilityMount revision 已过期");
        } catch (IllegalArgumentException exception) {
            throw notFound("CapabilityMount 不存在");
        }
    }

    /** 安装复制挂载配置；目标身份始终独立拥有工作草稿。 */
    @Transactional(rollbackFor = Exception.class)
    public void copyToDraft(long tenantId, long sourceId, long draftId) {
        bindTenant(tenantId);
        requireEditable(tenantId, draftId);
        for (var mount : persistence.read(tenantId, sourceId).mounts()) {
            persistence.create(new CreateCapabilityMountCommand(tenantId, draftId,
                    mount.capability(), mount.config(), mount.priority(),
                    mount.active() ? CapabilityMountStatus.ACTIVE : CapabilityMountStatus.DISABLED));
        }
    }

    /** 历史恢复只改稳定身份下的工作草稿挂载，发布快照不受影响。 */
    @Transactional(rollbackFor = Exception.class)
    public void restoreDraft(long tenantId, long agentId,
            com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult snapshot) {
        bindTenant(tenantId);
        requireEditable(tenantId, agentId);
        for (var current : persistence.read(tenantId, agentId).mounts()) {
            persistence.delete(new DeleteCapabilityMountCommand(tenantId, agentId,
                    current.mountId(), current.revision()));
        }
        for (var mount : snapshot.mounts()) {
            persistence.create(new CreateCapabilityMountCommand(tenantId, agentId,
                    mount.capability(), mount.config(), mount.priority(),
                    mount.active() ? CapabilityMountStatus.ACTIVE : CapabilityMountStatus.DISABLED));
        }
    }

    private void requireEditable(long tenantId, long agentId) {
        access.require("agent", Long.toString(agentId), "manage");
        agents.requireEditable(agents.lockVersion(tenantId, agentId));
        agents.markDraftChanged(tenantId, agentId);
    }

    private ValidatedMount validateRequest(long tenantId, CapabilityMountRequest request) {
        access.requireOrdinaryCapability(tenantId,request.capabilityDefinitionId().toString());
        if (request == null || request.capabilityType() == null) {
            throw invalid("capabilityType 是必填字段", null);
        }
        if (request.capabilityDefinitionId() == null || request.capabilityDefinitionId() <= 0) {
            throw invalid("capabilityDefinitionId 必须为正数", null);
        }

        CapabilityType type = request.capabilityType();
        CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(tenantId);
        CapabilityDefinitionView definition = catalog.getDefinition(
                scope, request.capabilityDefinitionId());
        CapabilityVersionView version = catalog.getCurrentPublishedVersion(scope, request.capabilityDefinitionId());
        validateCatalogIdentity(tenantId, type, definition, version);
        MountConfig config = typedConfig(tenantId, type, version, request);
        if (type == CapabilityType.SKILL) {
            validateSkillActivation(version, config);
        }
        validateTarget(tenantId, type, version.id(), config);
        return new ValidatedMount(new CapabilityRef(type,
                Long.toString(definition.id()), Long.toString(version.id())), config);
    }

    /** 公共Agent部署在每次新Run冻结时复用完整资源校验，并只生成不可变执行挂载。 */
    @Transactional(readOnly=true)
    public CapabilityMount resolveDeploymentBinding(long tenantId,long agentId,String mountId,
            long bindingRevision,CapabilityMountRequest request) {
        org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CapabilityMountManagementService.class);
        log.debug("部署资源绑定解析进入 tenantId={} agentId={} mountId={}",tenantId,agentId,mountId);
        bindTenant(tenantId);
        if(request.status()!=null&&!request.status().active())throw invalid("部署槽位必须为ACTIVE",null);
        ValidatedMount validated=validateRequest(tenantId,request);
        var result=new CapabilityMount(mountId,tenantId,agentId,validated.capability(),validated.config(),
                request.priority()==null?0:request.priority(),bindingRevision,true);
        log.debug("部署资源绑定解析退出 tenantId={} agentId={} type={}",tenantId,agentId,validated.capability().type());
        return result;
    }

    private void validateCatalogIdentity(
            long tenantId,
            CapabilityType requestedType,
            CapabilityDefinitionView definition,
            CapabilityVersionView version) {
        if (definition == null || version == null) {
            throw notFound("Capability definition/version 不存在");
        }
        if (definition.capabilityType() != requestedType
                || version.capabilityType() != requestedType) {
            throw invalid("CapabilityMount capability type 与 Catalog 类型不匹配", null);
        }
        if (!Objects.equals(definition.id(), version.definitionId())
                || !Objects.equals(definition.tenantId(), version.tenantId())) {
            throw invalid("CapabilityMount definition/version identity 不匹配", null);
        }
        if (definition.tenantId() != null && !Objects.equals(definition.tenantId(), tenantId)) {
            throw notFound("Capability definition/version 不存在");
        }
        if (!CATALOG_PUBLISHED.equals(definition.status())
                || !CATALOG_PUBLISHED.equals(version.status())) {
            throw invalid("CapabilityMount 只允许挂载 PUBLISHED definition/version", null);
        }
    }

    private MountConfig typedConfig(long tenantId, CapabilityType type,
            CapabilityVersionView version, CapabilityMountRequest request) {
        int count = nonNullCount(request.skillConfig(), request.toolConfig(),
                request.workflowConfig(), request.knowledgeConfig());
        if (count != 1) {
            throw invalid("必须且只能提供一个类型化 CapabilityMount config", null);
        }
        try {
            return switch (type) {
                case SKILL -> {
                    requireSelected(type, request.skillConfig() != null,
                            request.toolConfig(), request.workflowConfig(), request.knowledgeConfig());
                    SkillMountConfigRequest value = request.skillConfig();
                    if (value.activationMode() == null || value.tokenBudget() == null) {
                        throw invalid("SKILL config 必须包含 activationMode/tokenBudget", null);
                    }
                    yield new SkillMountConfig(com.skillforge.core.capability.SkillActivationMode.valueOf(
                            String.valueOf(version.descriptor().get("activation_mode"))), value.tokenBudget());
                }
                case TOOL -> {
                    requireSelected(type, request.toolConfig() != null,
                            request.skillConfig(), request.workflowConfig(), request.knowledgeConfig());
                    ToolMountConfigRequest value = request.toolConfig();
                    if (value.toolDeploymentId() == null) {
                        throw invalid("TOOL config 必须包含 toolDeploymentId", null);
                    }
                    var deployment = catalog.getDeployment(CapabilityCatalogScope.tenant(tenantId), value.toolDeploymentId());
                    yield new ToolMountConfig(value.toolDeploymentId(), deployment.revision());
                }
                case WORKFLOW -> {
                    requireSelected(type, request.workflowConfig() != null,
                            request.skillConfig(), request.toolConfig(), request.knowledgeConfig());
                    WorkflowMountConfigRequest value = request.workflowConfig();
                    if (value.workflowInstanceId() == null) {
                        throw invalid("WORKFLOW config 必须包含 workflowInstanceId", null);
                    }
                    var currentVersion = workflows.capabilityDefinition(tenantId, version.id());
                    if (currentVersion == null) throw notFound("Workflow 当前发布版本不存在");
                    var instance = workflows.instance(tenantId, positiveResourceId(value.workflowInstanceId(), "workflowInstanceId"));
                    if (instance == null || instance.installationRevision() == null) throw notFound("Workflow 安装修订不存在");
                    yield new WorkflowMountConfig(value.workflowInstanceId(), currentVersion.id(), instance.installationRevision());
                }
                case KNOWLEDGE_SOURCE -> {
                    requireSelected(type, request.knowledgeConfig() != null,
                            request.skillConfig(), request.toolConfig(), request.workflowConfig());
                    KnowledgeMountConfigRequest value = request.knowledgeConfig();
                    yield new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null);
                }
            };
        } catch (IllegalArgumentException exception) {
            throw invalid("CapabilityMount 强类型配置非法", exception);
        }
    }

    private void requireSelected(CapabilityType type, boolean selected, Object... others) {
        if (!selected || java.util.Arrays.stream(others).anyMatch(Objects::nonNull)) {
            throw invalid("CapabilityMount config 与 capabilityType 不匹配: " + type, null);
        }
    }

    private int nonNullCount(Object... values) {
        int count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Runtime {@code SkillActivation.Mount} 要求挂载模式等于发布 descriptor；
     * 管理面若写入不一致，Session 准备会确定性失败并占住 Inbox。
     */
    private void validateSkillActivation(CapabilityVersionView version, MountConfig config) {
        if (!(config instanceof SkillMountConfig skill)) {
            throw invalid("SKILL config 与 capabilityType 不匹配", null);
        }
        Object published = version.descriptor() == null
                ? null : version.descriptor().get("activation_mode");
        if (!(published instanceof String mode) || !mode.equals(skill.activationMode().name())) {
            throw invalid("SKILL 挂载 activationMode 必须与已发布版本一致", null);
        }
    }

    private void validateTarget(
            long tenantId, CapabilityType type, Long capabilityVersionId, MountConfig config) {
        switch (config) {
            case SkillMountConfig ignored -> {
                if (type != CapabilityType.SKILL) {
                    throw invalid("SKILL config 与 capabilityType 不匹配", null);
                }
            }
            case ToolMountConfig tool -> validateTool(tenantId, capabilityVersionId, tool);
            case WorkflowMountConfig workflow -> validateWorkflow(
                    tenantId, capabilityVersionId, workflow);
            case KnowledgeMountConfig knowledge -> validateKnowledge(
                    tenantId, capabilityVersionId, knowledge);
        }
    }

    private void validateTool(long tenantId, Long capabilityVersionId, ToolMountConfig config) {
        CapabilityDeploymentView deployment = catalog.getDeployment(
                CapabilityCatalogScope.tenant(tenantId), config.toolDeploymentId());
        if (deployment == null) {
            throw notFound("Tool Deployment 不存在");
        }
        if (!Objects.equals(deployment.tenantId(), tenantId)) {
            throw notFound("Tool Deployment 不存在");
        }
        var deploymentVersion = catalog.getVersion(CapabilityCatalogScope.tenant(tenantId), deployment.toolVersionId());
        var selectedVersion = catalog.getVersion(CapabilityCatalogScope.tenant(tenantId), capabilityVersionId);
        if (!DEPLOYMENT_ACTIVE.equals(deployment.status())
                || !Objects.equals(deployment.revision(), config.toolDeploymentRevision())
                || !Objects.equals(deploymentVersion.definitionId(), selectedVersion.definitionId())) {
            throw invalid("Tool Deployment 必须为当前租户 ACTIVE 且绑定同一稳定 Tool", null);
        }
    }

    private void validateWorkflow(
            long tenantId, Long capabilityVersionId, WorkflowMountConfig config) {
        long instanceId = positiveResourceId(config.workflowInstanceId(), "workflowInstanceId");
        WorkflowReferenceService.Instance instance = workflows.instance(tenantId, instanceId);
        if (instance == null) {
            throw notFound("Workflow instance 不存在");
        }
        if (!WorkflowConstants.INSTANCE_STATUS_PUBLISHED.equals(instance.status())) {
            throw invalid("Workflow instance 必须为 published", null);
        }
        if (instance.definitionVersionId() == null) {
            throw invalid("Workflow instance 缺少 definitionVersionId", null);
        }
        WorkflowReferenceService.Definition installedVersion = workflows.definition(tenantId, instance.definitionVersionId());
        WorkflowReferenceService.Definition definitionVersion = workflows.definition(tenantId, config.workflowInstanceRevision());
        if (definitionVersion == null) {
            throw notFound("Workflow DefinitionVersion 不存在");
        }
        if (definitionVersion != null) access.require("workflow", definitionVersion.containerId().toString(), "execute");
        if (installedVersion == null || !Objects.equals(installedVersion.tenantId(), tenantId)
                || !Objects.equals(installedVersion.containerId(), definitionVersion.containerId())
                || !Objects.equals(definitionVersion.capabilityVersionId(), capabilityVersionId)) {
            throw invalid("Workflow DefinitionVersion 的 CapabilityVersion 不匹配", null);
        }
    }

    private void validateKnowledge(
            long tenantId, Long capabilityVersionId, KnowledgeMountConfig config) {
        if (config.mode() == KnowledgeMountConfig.BindingMode.PINNED_REVISION) {
            long revisionId = positiveResourceId(config.pinnedRevisionId(), "pinnedRevisionId");
            KnowledgeReferenceService.Revision revision = knowledge.publishedRevision(tenantId, revisionId);
            if (revision == null) {
                throw notFound("Knowledge published revision 不存在");
            }
            access.require("knowledge", revision.kbId().toString(), "execute");
            if (!Objects.equals(revision.capabilityVersionId(), capabilityVersionId)) {
                throw invalid("PINNED Knowledge revision 的 CapabilityVersion 不匹配", null);
            }
            return;
        }

        if (config.pinnedRevisionId() != null) {
            throw invalid("FOLLOW_ACTIVE config 不得包含 pinnedRevisionId", null);
        }
        boolean matchesActive = knowledge.publishedForCapability(tenantId, capabilityVersionId)
                .stream()
                .anyMatch(revision -> isActiveRevision(tenantId, revision));
        if (!matchesActive) {
            throw invalid("FOLLOW_ACTIVE 必须匹配 KnowledgeBase 当前 active published revision", null);
        }
    }

    private boolean isActiveRevision(long tenantId, KnowledgeReferenceService.Revision revision) {
        if (revision.kbId() == null) {
            return false;
        }
        KnowledgeReferenceService.Base base = knowledge.base(tenantId, revision.kbId());
        return base != null && Objects.equals(base.activeRevisionId(), revision.id())
                && access.allowed("knowledge", base.id().toString(), "execute");
    }

    private CapabilityMountStatus status(CapabilityMountRequest request) {
        return request.status() == null ? CapabilityMountStatus.ACTIVE : request.status();
    }

    private void bindTenant(long tenantId) {
        catalogDatabaseContext.bindTenant(tenantId);
    }

    private long requireAgentId(Long agentId) {
        return requirePositive(agentId, "agentId");
    }

    private long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw notFound(field + " 不存在");
        }
        return value;
    }

    private long positiveResourceId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 必须为正数", null);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " 必须为正数", exception);
        }
    }

    private ApiException invalid(String message, Throwable cause) {
        return cause == null
                ? new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message)
                : new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, cause);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    private ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    private record ValidatedMount(CapabilityRef capability, MountConfig config) {
    }
}
