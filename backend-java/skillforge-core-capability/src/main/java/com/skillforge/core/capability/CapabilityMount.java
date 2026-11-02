package com.skillforge.core.capability;

import java.util.Locale;

import java.util.Objects;

/** Agent 对某个能力版本的类型化授权挂载。 */
public record CapabilityMount(
        String mountId,
        Long tenantId,
        Long agentId,
        CapabilityRef capability,
        MountConfig config,
        int priority,
        long revision,
        boolean active) {

    public sealed interface MountConfig permits
            SkillMountConfig, ToolMountConfig, WorkflowMountConfig, KnowledgeMountConfig {
        CapabilityType type();
    }

    public record SkillMountConfig(
            SkillActivationMode activationMode,
            long tokenBudget) implements MountConfig {
        public SkillMountConfig {
            Objects.requireNonNull(activationMode, "activationMode");
            if (tokenBudget <= 0) {
                throw new IllegalArgumentException("tokenBudget must be positive");
            }
        }
        @Override public CapabilityType type() { return CapabilityType.SKILL; }
    }

    public record ToolMountConfig(
            String toolDeploymentId,
            long toolDeploymentRevision) implements MountConfig {
        public ToolMountConfig {
            if (toolDeploymentId == null || toolDeploymentId.isBlank()) {
                throw new IllegalArgumentException("toolDeploymentId must not be blank");
            }
            if (toolDeploymentRevision <= 0) {
                throw new IllegalArgumentException("toolDeploymentRevision must be positive");
            }
        }
        @Override public CapabilityType type() { return CapabilityType.TOOL; }
    }

    public record WorkflowMountConfig(
            String workflowInstanceId,
            long workflowInstanceRevision,
            long installationRevision) implements MountConfig {
        public WorkflowMountConfig {
            if (workflowInstanceId == null || workflowInstanceId.isBlank()) {
                throw new IllegalArgumentException("workflowInstanceId must not be blank");
            }
            if (workflowInstanceRevision <= 0) {
                throw new IllegalArgumentException("workflowInstanceRevision must be positive");
            }
            if (installationRevision <= 0) throw new IllegalArgumentException("installationRevision must be positive");
        }
        @Override public CapabilityType type() { return CapabilityType.WORKFLOW; }
    }

    public record KnowledgeMountConfig(
            BindingMode mode,
            String pinnedRevisionId) implements MountConfig {
        public enum BindingMode { FOLLOW_ACTIVE, PINNED_REVISION }
        public KnowledgeMountConfig {
            Objects.requireNonNull(mode, "mode");
            if (mode == BindingMode.PINNED_REVISION
                    && (pinnedRevisionId == null || pinnedRevisionId.isBlank())) {
                throw new IllegalArgumentException("pinned revision is required");
            }
            if (mode == BindingMode.FOLLOW_ACTIVE && pinnedRevisionId != null) {
                throw new IllegalArgumentException("follow-active mount must not pin revision");
            }
        }
        @Override public CapabilityType type() { return CapabilityType.KNOWLEDGE_SOURCE; }
    }

    public CapabilityMount {
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(config, "config");
        if (capability.type() != config.type()) {
            throw new IllegalArgumentException("mount config type does not match capability");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    /** 当前 input 内模型使用的不透明句柄。 */
    public String handle() {
        return capability.type().name().toLowerCase(Locale.ROOT) + ":" + mountId;
    }
}
