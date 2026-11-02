package com.skillforge.institution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Production tenant-scoped adapters for Workflow and Knowledge mount references. */
public final class CapabilityMountReferenceResolvers {

    private static final String CAPABILITY_PUBLISHED = "PUBLISHED";

    private final WorkflowReferenceService workflows;
    private final KnowledgeReferenceService knowledge;
    private final CapabilityVersionMapper capabilityVersionMapper;

    public CapabilityMountReferenceResolvers(
            CapabilityVersionMapper capabilityVersionMapper,
            WorkflowReferenceService workflows, KnowledgeReferenceService knowledge) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.capabilityVersionMapper = Objects.requireNonNull(
                capabilityVersionMapper, "capabilityVersionMapper");
    }

    /** 新执行通过稳定安装实例/知识定义选当前发布版本；精确修订仅存在于返回快照。 */
    public Optional<CapabilityMount> resolvePublishedMount(Long tenantId, CapabilityMount mount) {
        if (mount.capability().type() == CapabilityType.KNOWLEDGE_SOURCE) return resolveKnowledgeMount(tenantId, mount);
        if (!(mount.config() instanceof CapabilityMount.WorkflowMountConfig config)) return Optional.empty();
        var instance = workflows.instance(tenantId, positiveId(config.workflowInstanceId()));
        if (instance == null || !WorkflowConstants.INSTANCE_STATUS_PUBLISHED.equals(instance.status()) || instance.definitionVersionId() == null) return Optional.empty();
        var anchor = workflows.definition(tenantId, instance.definitionVersionId());
        if (anchor == null) return Optional.empty();
        var container = workflows.container(tenantId, anchor.containerId());
        if (container == null || container.activeDefinitionVersionId() == null
                || "archived".equals(container.status()) || "disabled".equals(container.status())) return Optional.empty();
        var version = workflows.definition(tenantId, container.activeDefinitionVersionId());
        if (version == null || !Objects.equals(version.containerId(), anchor.containerId()) || version.capabilityVersionId() == null) return Optional.empty();
        var capability = capabilityVersionMapper.selectOne(new LambdaQueryWrapper<CapabilityVersionDO>()
                .eq(CapabilityVersionDO::getId, version.capabilityVersionId())
                .eq(CapabilityVersionDO::getDefinitionId, positiveId(mount.capability().capabilityId()))
                .eq(CapabilityVersionDO::getTenantId, tenantId)
                .eq(CapabilityVersionDO::getStatus, CAPABILITY_PUBLISHED).last("LIMIT 1"));
        if (capability == null) return Optional.empty();
        return Optional.of(new CapabilityMount(mount.mountId(), mount.tenantId(), mount.agentId(),
                new CapabilityRef(CapabilityType.WORKFLOW, mount.capability().capabilityId(), Long.toString(capability.getId())),
                new CapabilityMount.WorkflowMountConfig(config.workflowInstanceId(), version.id(), instance.installationRevision()),
                mount.priority(), mount.revision(), mount.active()));
    }

    /** Resolves an installed Workflow instance to its exact immutable DefinitionVersion. */
    public boolean workflowInstanceMatches(
            Long tenantId, String workflowInstanceId, long workflowInstanceRevision,
            CapabilityRef workflowVersion) {
        if (!positive(tenantId) || workflowVersion == null
                || workflowVersion.type() != CapabilityType.WORKFLOW
                || workflowInstanceRevision <= 0) {
            return false;
        }
        long instanceId = positiveId(workflowInstanceId);
        long definitionId = positiveId(workflowVersion.capabilityId());
        long capabilityVersionId = positiveId(workflowVersion.versionId());
        if (instanceId <= 0 || definitionId <= 0 || capabilityVersionId <= 0) {
            return false;
        }
        CapabilityVersionDO capabilityVersion = capabilityVersionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, capabilityVersionId)
                        .eq(CapabilityVersionDO::getDefinitionId, definitionId)
                        .eq(CapabilityVersionDO::getCapabilityType,
                                CapabilityType.WORKFLOW.name())
                        .eq(CapabilityVersionDO::getTenantId, tenantId)
                        .eq(CapabilityVersionDO::getStatus, CAPABILITY_PUBLISHED)
                        .last("LIMIT 1"));
        if (capabilityVersion == null
                || !Objects.equals(capabilityVersion.getId(), capabilityVersionId)
                || !Objects.equals(capabilityVersion.getDefinitionId(), definitionId)
                || !Objects.equals(capabilityVersion.getTenantId(), tenantId)
                || !CapabilityType.WORKFLOW.name().equals(capabilityVersion.getCapabilityType())
                || !CAPABILITY_PUBLISHED.equals(capabilityVersion.getStatus())) {
            return false;
        }
        WorkflowReferenceService.Instance instance = workflows.instance(tenantId, instanceId);
        if (instance == null
                || !Objects.equals(tenantId, instance.tenantId())
                || !WorkflowConstants.INSTANCE_STATUS_PUBLISHED.equals(instance.status())
                || instance.definitionVersionId() == null) {
            return false;
        }

        WorkflowReferenceService.Definition definitionVersion = workflows.definition(tenantId, workflowInstanceRevision);
        var anchor = workflows.definition(tenantId, instance.definitionVersionId());
        return definitionVersion != null && anchor != null
                && Objects.equals(anchor.tenantId(), tenantId)
                && Objects.equals(anchor.containerId(), definitionVersion.containerId())
                && Objects.equals(tenantId, definitionVersion.tenantId())
                && Objects.equals(definitionVersion.id(), workflowInstanceRevision)
                && Objects.equals(definitionVersion.capabilityVersionId(), capabilityVersionId);
    }

    /**
     * Resolves a Knowledge mount to a published revision. FOLLOW_ACTIVE returns a new
     * mount pinned to the sole active revision for the same Knowledge capability definition.
     */
    public Optional<CapabilityMount> resolveKnowledgeMount(
            Long tenantId, CapabilityMount mount) {
        if (!positive(tenantId) || mount == null
                || !Objects.equals(tenantId, mount.tenantId())
                || mount.agentId() == null || mount.agentId() <= 0
                || !mount.active()
                || mount.capability().type() != CapabilityType.KNOWLEDGE_SOURCE
                || !(mount.config() instanceof KnowledgeMountConfig config)) {
            return Optional.empty();
        }
        long definitionId = positiveId(mount.capability().capabilityId());
        long configuredVersionId = positiveId(mount.capability().versionId());
        if (definitionId <= 0 || configuredVersionId <= 0) {
            return Optional.empty();
        }
        return switch (config.mode()) {
            case PINNED_REVISION -> resolvePinned(
                    tenantId, mount, definitionId, configuredVersionId, config);
            case FOLLOW_ACTIVE -> resolveActive(
                    tenantId, mount, definitionId);
        };
    }

    private Optional<CapabilityMount> resolvePinned(
            Long tenantId, CapabilityMount mount, long definitionId,
            long configuredVersionId, KnowledgeMountConfig config) {
        long revisionId = positiveId(config.pinnedRevisionId());
        if (revisionId <= 0) {
            return Optional.empty();
        }
        KnowledgeReferenceService.Revision revision = knowledge.publishedRevision(tenantId, revisionId);
        if (revision == null
                || !Objects.equals(tenantId, revision.tenantId())
                || !KnowledgeConstants.REVISION_STATUS_PUBLISHED.equals(revision.status())
                || !Objects.equals(revision.capabilityVersionId(), configuredVersionId)) {
            return Optional.empty();
        }
        CapabilityVersionDO capabilityVersion = capabilityVersionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, configuredVersionId)
                        .eq(CapabilityVersionDO::getDefinitionId, definitionId)
                        .eq(CapabilityVersionDO::getCapabilityType,
                                CapabilityType.KNOWLEDGE_SOURCE.name())
                        .eq(CapabilityVersionDO::getTenantId, tenantId)
                        .eq(CapabilityVersionDO::getStatus, CAPABILITY_PUBLISHED)
                        .last("LIMIT 1"));
        if (capabilityVersion == null
                || !Objects.equals(capabilityVersion.getId(), configuredVersionId)
                || !Objects.equals(capabilityVersion.getDefinitionId(), definitionId)
                || !Objects.equals(tenantId, capabilityVersion.getTenantId())) {
            return Optional.empty();
        }
        return Optional.of(mount);
    }

    private Optional<CapabilityMount> resolveActive(
            Long tenantId, CapabilityMount mount, long definitionId) {
        List<CapabilityVersionDO> versions = nonNull(capabilityVersionMapper.selectList(
                new LambdaQueryWrapper<CapabilityVersionDO>()
                        .select(CapabilityVersionDO::getId, CapabilityVersionDO::getDefinitionId,
                                CapabilityVersionDO::getTenantId, CapabilityVersionDO::getCapabilityType,
                                CapabilityVersionDO::getStatus)
                        .eq(CapabilityVersionDO::getDefinitionId, definitionId)
                        .eq(CapabilityVersionDO::getCapabilityType,
                                CapabilityType.KNOWLEDGE_SOURCE.name())
                        .eq(CapabilityVersionDO::getTenantId, tenantId)
                        .eq(CapabilityVersionDO::getStatus, CAPABILITY_PUBLISHED)));
        List<Long> publishedVersionIds = versions.stream()
                .filter(version -> version != null && version.getId() != null && version.getId() > 0
                        && Objects.equals(version.getDefinitionId(), definitionId)
                        && Objects.equals(version.getTenantId(), tenantId)
                        && CapabilityType.KNOWLEDGE_SOURCE.name().equals(version.getCapabilityType())
                        && CAPABILITY_PUBLISHED.equals(version.getStatus()))
                .map(CapabilityVersionDO::getId).distinct().toList();
        if (publishedVersionIds.isEmpty()) return Optional.empty();

        List<KnowledgeReferenceService.Revision> candidates = knowledge
                .activePublishedForCapabilityVersions(tenantId, publishedVersionIds).stream()
                .filter(revision -> Objects.equals(tenantId, revision.tenantId())
                        && KnowledgeConstants.REVISION_STATUS_PUBLISHED.equals(revision.status())
                        && revision.id() != null
                        && publishedVersionIds.contains(revision.capabilityVersionId()))
                .toList();
        if (candidates.size() != 1) return Optional.empty();

        var candidate = candidates.getFirst();
        CapabilityRef actualRef = new CapabilityRef(
                CapabilityType.KNOWLEDGE_SOURCE,
                mount.capability().capabilityId(),
                Long.toString(candidate.capabilityVersionId()));
        return Optional.of(new CapabilityMount(
                mount.mountId(), mount.tenantId(), mount.agentId(), actualRef,
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION,
                        Long.toString(candidate.id())),
                mount.priority(), mount.revision(), true));
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static long positiveId(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1L;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values;
    }

}
