package com.skillforge.core.capability;

import com.skillforge.core.capability.CapabilityMetadata.Status;
import com.skillforge.core.capability.CapabilityMetadata.Trust;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig;
import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 在 input 边界校验并冻结 CapabilityMount；运行时不得绕过此解析入口。 */
public final class CapabilityMountResolver {

    /** Capability Registry 与类型专属 Deployment/实例的只读解析边界。 */
    public interface Registry {
        Optional<CapabilityDescriptor> find(Long tenantId, CapabilityRef ref);

        /** 新执行按类型规则解析后冻结；Skill 必须保留挂载的精确版本与激活配置，不能跟随默认指针。 */
        Optional<CapabilityMount> resolveCurrentMount(Long tenantId, CapabilityMount mount);

        Optional<ToolDeployment> findToolDeployment(Long tenantId, String deploymentId);

        boolean workflowInstanceMatches(
                Long tenantId, String workflowInstanceId, long workflowInstanceRevision,
                CapabilityRef workflowVersion);

        /**
         * Resolves a Knowledge mount to the exact published revision used by this input.
         *
         * <p>A PINNED_REVISION result must preserve the requested published version and
         * revision. A FOLLOW_ACTIVE result must return a copy with the same capability
         * definition, the active published CapabilityVersion and a PINNED_REVISION config.
         */
        Optional<CapabilityMount> resolveKnowledgeMount(
                Long tenantId, CapabilityMount mount);
    }

    private final Registry registry;
    private final Clock clock;

    /** 原租户、部署身份及冻结版本均已核实，唯独该部署明确失活；终态解释归调用宿主。 */
    public static final class InactiveToolDeploymentException extends IllegalArgumentException {
        private InactiveToolDeploymentException() { super("tool_deployment_inactive"); }
    }

    public CapabilityMountResolver(Registry registry, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 校验全部挂载后一次性冻结；任一挂载无效时不产生部分快照。 */
    public CapabilityMountSnapshot freeze(
            String snapshotId,
            String inputId,
            Long tenantId,
            Long agentId,
            long generation,
            List<CapabilityMount> mounts) {
        if (generation <= 0) {
            throw invalid("generation must be positive");
        }
        List<CapabilityMount> frozen = new ArrayList<>();
        if (mounts != null) {
            for (CapabilityMount mount : mounts) {
                if (mount == null) {
                    throw invalid("mount must not be null");
                }
                if (mount.active()) {
                    frozen.add(mount);
                }
            }
        }
        List<CapabilityMount> resolved = new ArrayList<>(frozen.size());
        for (CapabilityMount mount : frozen) {
            validateOwnership(tenantId, agentId, mount);
            CapabilityMount selected = registry.resolveCurrentMount(tenantId, mount)
                    .orElseThrow(() -> invalid("current capability publication is not available"));
            validateResolvedIdentity(mount, selected);
            resolved.add(validateFrozen(tenantId, agentId, selected));
        }
        return new CapabilityMountSnapshot(
                snapshotId, inputId, tenantId, agentId, generation, resolved, clock.instant());
    }

    /** 恢复仅验证原快照，不重新选择当前发布版本。 */
    public CapabilityMountSnapshot restore(CapabilityMountSnapshot snapshot) {
        for (CapabilityMount mount : snapshot.mounts()) {
            validateOwnership(snapshot.tenantId(), snapshot.agentId(), mount);
            validateFrozen(snapshot.tenantId(), snapshot.agentId(), mount);
        }
        return snapshot;
    }

    private CapabilityMount validateFrozen(Long tenantId, Long agentId, CapabilityMount mount) {
            Optional<CapabilityDescriptor> descriptorResult = registry.find(
                    tenantId, mount.capability());
            if (descriptorResult == null || descriptorResult.isEmpty()) {
                throw invalid("capability version is not registered");
            }
            CapabilityDescriptor descriptor = descriptorResult.get();
            validateDescriptor(tenantId, mount, descriptor);
            CapabilityMount resolvedMount = validateTarget(tenantId, agentId, mount);
            if (!resolvedMount.capability().equals(mount.capability())) {
                Optional<CapabilityDescriptor> resolvedDescriptorResult = registry.find(
                        tenantId, resolvedMount.capability());
                if (resolvedDescriptorResult == null || resolvedDescriptorResult.isEmpty()) {
                    throw invalid("resolved capability version is not registered");
                }
                CapabilityDescriptor resolvedDescriptor = resolvedDescriptorResult.get();
                validateDescriptor(tenantId, resolvedMount, resolvedDescriptor);
            }
            return resolvedMount;
    }

    private void validateResolvedIdentity(CapabilityMount requested, CapabilityMount selected) {
        if (!requested.mountId().equals(selected.mountId())
                || !Objects.equals(requested.tenantId(), selected.tenantId())
                || !Objects.equals(requested.agentId(), selected.agentId())
                || requested.priority() != selected.priority()
                || requested.revision() != selected.revision() || !selected.active()
                || requested.capability().type() != selected.capability().type()
                || !requested.capability().capabilityId().equals(selected.capability().capabilityId())) {
            throw invalid("current capability resolution changed stable mount identity");
        }
    }

    private void validateOwnership(Long tenantId, Long agentId, CapabilityMount mount) {
        if (!Objects.equals(tenantId, mount.tenantId())
                || !Objects.equals(agentId, mount.agentId())) {
            throw invalid("mount ownership does not match input");
        }
    }

    private void validateDescriptor(
            Long tenantId, CapabilityMount mount, CapabilityDescriptor descriptor) {
        CapabilityMetadata metadata = descriptor.metadata();
        if (!metadata.ref().equals(mount.capability())) {
            throw invalid("registry returned another capability version");
        }
        if (metadata.status() != Status.PUBLISHED) {
            throw invalid("capability version is not published");
        }
        if (metadata.trust() == Trust.TENANT && !Objects.equals(tenantId, metadata.tenantId())) {
            throw invalid("tenant capability belongs to another tenant");
        }
    }

    private CapabilityMount validateTarget(
            Long tenantId, Long agentId, CapabilityMount mount) {
        return switch (mount.config()) {
            case ToolMountConfig tool -> {
                validateToolDeployment(tenantId, mount, tool);
                yield mount;
            }
            case WorkflowMountConfig workflow -> {
                if (!registry.workflowInstanceMatches(
                        tenantId, workflow.workflowInstanceId(),
                        workflow.workflowInstanceRevision(), mount.capability())) {
                    throw invalid("workflow instance does not match mounted version");
                }
                yield mount;
            }
            case KnowledgeMountConfig knowledge -> {
                Optional<CapabilityMount> resolvedResult = registry.resolveKnowledgeMount(
                        tenantId, mount);
                if (resolvedResult == null || resolvedResult.isEmpty()) {
                    throw invalid("knowledge source revision is not available");
                }
                CapabilityMount resolved = resolvedResult.get();
                validateResolvedKnowledgeMount(tenantId, agentId, mount, resolved);
                yield resolved;
            }
            case CapabilityMount.SkillMountConfig ignored -> {
                // Skill 没有第二个执行目标；发布版本本身就是完整指令制品。
                yield mount;
            }
        };
    }

    private void validateResolvedKnowledgeMount(
            Long tenantId, Long agentId, CapabilityMount requested, CapabilityMount resolved) {
        if (!Objects.equals(tenantId, resolved.tenantId())
                || !Objects.equals(agentId, resolved.agentId())
                || !Objects.equals(requested.mountId(), resolved.mountId())
                || requested.priority() != resolved.priority()
                || requested.revision() != resolved.revision()
                || !resolved.active()
                || resolved.capability().type() != CapabilityType.KNOWLEDGE_SOURCE
                || !resolved.capability().capabilityId()
                        .equals(requested.capability().capabilityId())
                || !(resolved.config() instanceof KnowledgeMountConfig resolvedConfig)
                || resolvedConfig.mode() != KnowledgeMountConfig.BindingMode.PINNED_REVISION
                || resolvedConfig.pinnedRevisionId() == null
                || resolvedConfig.pinnedRevisionId().isBlank()) {
            throw invalid("resolved knowledge mount is incomplete or crosses its definition");
        }
        if (requested.config() instanceof KnowledgeMountConfig requestedConfig
                && requestedConfig.mode() == KnowledgeMountConfig.BindingMode.PINNED_REVISION
                && (!requested.capability().equals(resolved.capability())
                        || !Objects.equals(requestedConfig.pinnedRevisionId(),
                                resolvedConfig.pinnedRevisionId()))) {
            throw invalid("pinned knowledge mount resolved to another version or revision");
        }
    }

    private void validateToolDeployment(
            Long tenantId, CapabilityMount mount, ToolMountConfig config) {
        Optional<ToolDeployment> deploymentResult = registry.findToolDeployment(
                tenantId, config.toolDeploymentId());
        if (deploymentResult == null || deploymentResult.isEmpty()) {
            throw invalid("tool deployment is not registered");
        }
        ToolDeployment deployment = deploymentResult.get();
        if (!Objects.equals(tenantId, deployment.tenantId())
                || deployment.revision() < config.toolDeploymentRevision()
                || !mount.capability().capabilityId().equals(deployment.toolVersion().capabilityId())) {
            throw invalid("tool deployment does not match mounted version");
        }
        if (!deployment.active()) {
            // 类型只承载同一冻结目标的失活事实；错误目标/版本不能冒充执行撤权。
            if (!config.toolDeploymentId().equals(deployment.deploymentId())
                    || !mount.capability().equals(deployment.toolVersion())) {
                throw invalid("tool deployment does not match mounted version");
            }
            throw new InactiveToolDeploymentException();
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
