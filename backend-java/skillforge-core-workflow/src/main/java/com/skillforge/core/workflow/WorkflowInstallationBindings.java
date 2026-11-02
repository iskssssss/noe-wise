package com.skillforge.core.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityRef;
import java.util.List;
import java.util.Objects;

/** 安装者显式选择的精确目标；发布定义不保存租户 Deployment 或 Agent 身份。 */
public record WorkflowInstallationBindings(
        @JsonProperty("capabilities") List<CapabilityBinding> capabilities,
        @JsonProperty("workflows") List<WorkflowBinding> workflows,
        @JsonProperty("agents") List<AgentBinding> agents) {
    public WorkflowInstallationBindings {
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        workflows = List.copyOf(workflows == null ? List.of() : workflows);
        agents = List.copyOf(agents == null ? List.of() : agents);
    }

    public record CapabilityBinding(String slot, CapabilityRef capability,
            @JsonProperty("tool_deployment_id") String toolDeploymentId,
            @JsonProperty("tool_deployment_revision") Long toolDeploymentRevision,
            @JsonProperty("knowledge_revision") Long knowledgeRevision) {
        public CapabilityBinding { requireText(slot); Objects.requireNonNull(capability); }
    }

    public record WorkflowBinding(String workflow,
            @JsonProperty("instance_id") long instanceId,
            @JsonProperty("definition_version_id") long definitionVersionId,
            @JsonProperty("installation_revision") long installationRevision) {
        public WorkflowBinding {
            requireText(workflow);
            if (instanceId <= 0 || definitionVersionId <= 0 || installationRevision <= 0)
                throw new IllegalArgumentException("子图必须绑定精确安装版本");
        }
    }

    public record AgentBinding(String slot, @JsonProperty("agent_id") String agentId) {
        public AgentBinding { requireText(slot); requireText(agentId); }
    }

    public static WorkflowInstallationBindings empty() { return new WorkflowInstallationBindings(List.of(), List.of(), List.of()); }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("安装槽与目标不能为空");
    }
}
