package com.skillforge.core.capability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.CapabilityDefinitionDO;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import java.util.Objects;
import java.util.Optional;

/** 基于统一权威表的 CapabilityMountResolver Registry Adapter。 */
public final class PersistentCapabilityRegistry implements CapabilityMountResolver.Registry {

    @FunctionalInterface
    public interface WorkflowInstanceResolver {
        boolean matches(
                Long tenantId, String instanceId, long instanceRevision,
                CapabilityRef workflowVersion);
    }

    @FunctionalInterface
    public interface MountTargetResolver {
        Optional<CapabilityMount> resolve(Long tenantId, CapabilityMount mount);
    }

    private final CapabilityDefinitionMapper definitionMapper;
    private final CapabilityVersionMapper versionMapper;
    private final ToolDeploymentRecordMapper deploymentMapper;
    private final CapabilityDescriptorCodec descriptorCodec;
    private final WorkflowInstanceResolver workflowResolver;
    private final MountTargetResolver targetResolver;

    public PersistentCapabilityRegistry(
            CapabilityDefinitionMapper definitionMapper,
            CapabilityVersionMapper versionMapper,
            ToolDeploymentRecordMapper deploymentMapper,
            CapabilityDescriptorCodec descriptorCodec,
            WorkflowInstanceResolver workflowResolver,
            MountTargetResolver targetResolver) {
        this.definitionMapper = Objects.requireNonNull(definitionMapper, "definitionMapper");
        this.versionMapper = Objects.requireNonNull(versionMapper, "versionMapper");
        this.deploymentMapper = Objects.requireNonNull(deploymentMapper, "deploymentMapper");
        this.descriptorCodec = Objects.requireNonNull(descriptorCodec, "descriptorCodec");
        this.workflowResolver = Objects.requireNonNull(workflowResolver, "workflowResolver");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
    }

    @Override
    public Optional<CapabilityMount> resolveCurrentMount(Long tenantId, CapabilityMount mount) {
        if (mount == null || !Objects.equals(tenantId, mount.tenantId())) return Optional.empty();
        if (mount.capability().type() == CapabilityType.SKILL) {
            return find(tenantId, mount.capability())
                    .filter(descriptor -> descriptor instanceof SkillDescriptor skill
                            && mount.config() instanceof CapabilityMount.SkillMountConfig config
                            && skill.activationMode() == config.activationMode())
                    .map(descriptor -> mount);
        }
        if (mount.capability().type() == CapabilityType.WORKFLOW
                || mount.capability().type() == CapabilityType.KNOWLEDGE_SOURCE) {
            CapabilityMount candidate = mount;
            if (mount.config() instanceof CapabilityMount.KnowledgeMountConfig) {
                candidate = copy(mount, mount.capability(), new CapabilityMount.KnowledgeMountConfig(
                        CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
            }
            return targetResolver.resolve(tenantId, candidate);
        }
        Optional<CapabilityDescriptor> current = findCurrentPublished(tenantId, mount.capability());
        if (current.isEmpty()) return Optional.empty();
        CapabilityRef ref = current.get().metadata().ref();
        CapabilityMount.MountConfig config = mount.config();
        if (config instanceof CapabilityMount.ToolMountConfig tool) {
            var deployment = findToolDeployment(tenantId, tool.toolDeploymentId()).orElse(null);
            if (deployment == null || !deployment.active()
                    || !deployment.toolVersion().capabilityId().equals(ref.capabilityId())) return Optional.empty();
            config = new CapabilityMount.ToolMountConfig(deployment.deploymentId(), deployment.revision());
        }
        return Optional.of(copy(mount, ref, config));
    }

    private Optional<CapabilityDescriptor> findCurrentPublished(Long tenantId, CapabilityRef ref) {
        Long definitionId = positiveId(ref.capabilityId());
        if (tenantId == null || definitionId == null) return Optional.empty();
        CapabilityDefinitionDO definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityDefinitionDO>()
                        .eq(CapabilityDefinitionDO::getId, definitionId)
                        .eq(CapabilityDefinitionDO::getCapabilityType, ref.type().name())
                        .eq(CapabilityDefinitionDO::getStatus, "PUBLISHED")
                        .and(query -> query.isNull(CapabilityDefinitionDO::getTenantId)
                                .or().eq(CapabilityDefinitionDO::getTenantId, tenantId))
                        .last("LIMIT 1"));
        if (definition == null || definition.getDefaultVersionId() == null) return Optional.empty();
        return find(tenantId, new CapabilityRef(ref.type(), ref.capabilityId(),
                Long.toString(definition.getDefaultVersionId())));
    }

    private static CapabilityMount copy(CapabilityMount source, CapabilityRef ref,
            CapabilityMount.MountConfig config) {
        return new CapabilityMount(source.mountId(), source.tenantId(), source.agentId(), ref,
                config, source.priority(), source.revision(), source.active());
    }

    @Override
    public Optional<CapabilityDescriptor> find(Long tenantId, CapabilityRef ref) {
        Long definitionId = positiveId(ref.capabilityId());
        Long versionId = positiveId(ref.versionId());
        if (tenantId == null || definitionId == null || versionId == null) {
            return Optional.empty();
        }
        CapabilityVersionDO version = versionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, versionId)
                        .eq(CapabilityVersionDO::getDefinitionId, definitionId)
                        .eq(CapabilityVersionDO::getCapabilityType, ref.type().name())
                        .eq(CapabilityVersionDO::getStatus, "PUBLISHED")
                        .and(query -> query.isNull(CapabilityVersionDO::getTenantId)
                                .or().eq(CapabilityVersionDO::getTenantId, tenantId))
                        .last("LIMIT 1"));
        if (version == null) {
            return Optional.empty();
        }
        CapabilityDefinitionDO definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityDefinitionDO>()
                        .eq(CapabilityDefinitionDO::getId, definitionId)
                        .eq(CapabilityDefinitionDO::getCapabilityType, ref.type().name())
                        .eq(CapabilityDefinitionDO::getStatus, "PUBLISHED")
                        .and(query -> query.isNull(CapabilityDefinitionDO::getTenantId)
                                .or().eq(CapabilityDefinitionDO::getTenantId, tenantId))
                        .last("LIMIT 1"));
        if (definition == null || !Objects.equals(version.getTenantId(), definition.getTenantId())) {
            return Optional.empty();
        }
        try {
            return Optional.of(descriptorCodec.decode(
                    ref,
                    version.getTenantId(),
                    version.getName(),
                    version.getDescription(),
                    version.getTrustLevel(),
                    version.getStatus(),
                    version.getContentHash(),
                    version.getDescriptor()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ToolDeployment> findToolDeployment(Long tenantId, String deploymentId) {
        if (tenantId == null || deploymentId == null || deploymentId.isBlank()) {
            return Optional.empty();
        }
        ToolDeploymentRecordDO deployment = deploymentMapper.selectOne(
                new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                        .eq(ToolDeploymentRecordDO::getTenantId, tenantId)
                        .eq(ToolDeploymentRecordDO::getDeploymentKey, deploymentId)
                        .last("LIMIT 1"));
        if (deployment == null) {
            return Optional.empty();
        }
        CapabilityVersionDO version = versionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityVersionDO>()
                        .eq(CapabilityVersionDO::getId, deployment.getToolVersionId())
                        .eq(CapabilityVersionDO::getCapabilityType, CapabilityType.TOOL.name())
                        .eq(CapabilityVersionDO::getStatus, "PUBLISHED")
                        .and(query -> query.isNull(CapabilityVersionDO::getTenantId)
                                .or().eq(CapabilityVersionDO::getTenantId, tenantId))
                        .last("LIMIT 1"));
        if (version == null) {
            return Optional.empty();
        }
        CapabilityRef stableTool = new CapabilityRef(CapabilityType.TOOL,
                String.valueOf(version.getDefinitionId()), String.valueOf(version.getId()));
        var current = findCurrentPublished(tenantId, stableTool).orElse(null);
        if (current == null) return Optional.empty();
        try {
            return Optional.of(new ToolDeployment(
                    deployment.getDeploymentKey(),
                    deployment.getTenantId(),
                    current.metadata().ref(),
                    ToolDeployment.ProviderType.valueOf(deployment.getProviderType()),
                    deployment.getProviderRef(),
                    deployment.getRevision(),
                    ToolDeployment.Environment.valueOf(deployment.getEnvironment()),
                    "ACTIVE".equals(deployment.getStatus())));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean workflowInstanceMatches(
            Long tenantId, String workflowInstanceId, long workflowInstanceRevision,
            CapabilityRef workflowVersion) {
        return workflowResolver.matches(
                tenantId, workflowInstanceId, workflowInstanceRevision, workflowVersion);
    }

    @Override
    public Optional<CapabilityMount> resolveKnowledgeMount(
            Long tenantId, CapabilityMount mount) {
        return targetResolver.resolve(tenantId, mount);
    }

    private Long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
