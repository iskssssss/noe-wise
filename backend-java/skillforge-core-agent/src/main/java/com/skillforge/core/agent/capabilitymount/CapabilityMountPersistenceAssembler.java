package com.skillforge.core.agent.capabilitymount;

import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityMount.MountConfig;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import java.util.Objects;

/** CapabilityMount 领域对象与无 JSON/Map 的持久行之间的强类型转换器。 */
public final class CapabilityMountPersistenceAssembler {

    /** 将领域 mount 转成数据库行；Tool deployment 使用已解析的 Snowflake FK。 */
    public AgentCapabilityMountDO toRow(CapabilityMount mount, Long toolDeploymentId) {
        Objects.requireNonNull(mount, "mount");
        AgentCapabilityMountDO row = new AgentCapabilityMountDO();
        row.setId(parsePositive(mount.mountId(), "mountId"));
        row.setTenantId(requirePositive(mount.tenantId(), "tenantId"));
        row.setAgentId(requirePositive(mount.agentId(), "agentId"));
        row.setCapabilityType(mount.capability().type().name());
        row.setCapabilityDefinitionId(parsePositive(
                mount.capability().capabilityId(), "capabilityId"));
        row.setCapabilityVersionId(parsePositive(
                mount.capability().versionId(), "versionId"));
        row.setPriority(mount.priority());
        row.setStatus(mount.active() ? CapabilityMountStatus.ACTIVE.name()
                : CapabilityMountStatus.DISABLED.name());
        row.setRevision(mount.revision());

        switch (mount.config()) {
            case SkillMountConfig skill -> {
                row.setActivationMode(skill.activationMode().name());
                row.setTokenBudget(skill.tokenBudget());
            }
            case ToolMountConfig tool -> {
                row.setToolDeploymentId(requirePositive(toolDeploymentId, "toolDeploymentId"));
                row.setToolDeploymentRevision(tool.toolDeploymentRevision());
            }
            case WorkflowMountConfig workflow -> {
                row.setWorkflowInstanceId(parsePositive(
                        workflow.workflowInstanceId(), "workflowInstanceId"));
                row.setWorkflowInstanceRevision(workflow.workflowInstanceRevision());
                row.setWorkflowInstallationRevision(workflow.installationRevision());
            }
            case KnowledgeMountConfig knowledge -> {
                row.setKnowledgeBindingMode(knowledge.mode().name());
                if (knowledge.pinnedRevisionId() != null) {
                    row.setKnowledgePinnedRevisionId(parsePositive(
                            knowledge.pinnedRevisionId(), "pinnedRevisionId"));
                }
            }
        }
        return row;
    }

    /** 将数据库行重建成 CapabilityMount；Tool 必须由调用方提供稳定 deploymentKey。 */
    public CapabilityMount toDomain(AgentCapabilityMountDO row, String toolDeploymentKey) {
        Objects.requireNonNull(row, "row");
        long id = requirePositive(row.getId(), "id");
        long tenantId = requirePositive(row.getTenantId(), "tenantId");
        long agentId = requirePositive(row.getAgentId(), "agentId");
        CapabilityType type = enumValue(row.getCapabilityType(), CapabilityType.class,
                "capabilityType");
        CapabilityRef ref = new CapabilityRef(type,
                Long.toString(requirePositive(row.getCapabilityDefinitionId(),
                        "capabilityDefinitionId")),
                Long.toString(requirePositive(row.getCapabilityVersionId(),
                        "capabilityVersionId")));
        MountConfig config = switch (type) {
            case SKILL -> new SkillMountConfig(
                    enumValue(row.getActivationMode(), SkillActivationMode.class,
                            "activationMode"),
                    requirePositive(row.getTokenBudget(), "tokenBudget"));
            case TOOL -> new ToolMountConfig(
                    requireText(toolDeploymentKey, "toolDeploymentKey"),
                    requirePositive(row.getToolDeploymentRevision(),
                            "toolDeploymentRevision"));
            case WORKFLOW -> new WorkflowMountConfig(
                    Long.toString(requirePositive(row.getWorkflowInstanceId(),
                            "workflowInstanceId")),
                    requirePositive(row.getWorkflowInstanceRevision(),
                            "workflowInstanceRevision"),
                    requirePositive(row.getWorkflowInstallationRevision(), "workflowInstallationRevision"));
            case KNOWLEDGE_SOURCE -> new KnowledgeMountConfig(
                    enumValue(row.getKnowledgeBindingMode(),
                            KnowledgeMountConfig.BindingMode.class, "knowledgeBindingMode"),
                    row.getKnowledgePinnedRevisionId() == null ? null
                            : Long.toString(requirePositive(row.getKnowledgePinnedRevisionId(),
                                    "knowledgePinnedRevisionId")));
        };
        CapabilityMountStatus status = enumValue(row.getStatus(), CapabilityMountStatus.class,
                "status");
        return new CapabilityMount(
                Long.toString(id), tenantId, agentId, ref, config,
                Objects.requireNonNull(row.getPriority(), "priority"),
                requirePositive(row.getRevision(), "revision"), status.active());
    }

    private long parsePositive(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        try {
            return requirePositive(Long.parseLong(value), field);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer", exception);
        }
    }

    private long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new CapabilityMountPersistenceException(field + " is missing from persistent row");
        }
        return value;
    }

    private <E extends Enum<E>> E enumValue(String value, Class<E> type, String field) {
        if (value == null || value.isBlank()) {
            throw new CapabilityMountPersistenceException(field + " is missing from persistent row");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new CapabilityMountPersistenceException(
                    field + " has unsupported persistent value: " + value, exception);
        }
    }
}
