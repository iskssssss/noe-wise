package com.skillforge.core.agent.dto.response;

import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig;
import com.skillforge.core.capability.CapabilityType;

/**
 * CapabilityMount 响应项。
 *
 * <p>四个配置响应字段与 CapabilityType 一一对应，未选中的字段为 null；不暴露
 * 可自由扩展的 JSON/Map 配置。
 */
public record CapabilityMountResponse(
        String mountId,
        CapabilityType capabilityType,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long capabilityDefinitionId,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long capabilityVersionId,
        SkillMountConfigResponse skillConfig,
        ToolMountConfigResponse toolConfig,
        WorkflowMountConfigResponse workflowConfig,
        KnowledgeMountConfigResponse knowledgeConfig,
        int priority,
        long revision,
        CapabilityMountStatus status) {

    /** 将 core-agent 强类型领域 mount 投影为 API 响应。 */
    public static CapabilityMountResponse from(CapabilityMount mount) {
        Long definitionId = Long.valueOf(mount.capability().capabilityId());
        Long versionId = Long.valueOf(mount.capability().versionId());
        SkillMountConfigResponse skill = null;
        ToolMountConfigResponse tool = null;
        WorkflowMountConfigResponse workflow = null;
        KnowledgeMountConfigResponse knowledge = null;
        switch (mount.config()) {
            case SkillMountConfig value -> skill = new SkillMountConfigResponse(
                    value.activationMode(), value.tokenBudget());
            case ToolMountConfig value -> tool = new ToolMountConfigResponse(
                    value.toolDeploymentId(), value.toolDeploymentRevision());
            case WorkflowMountConfig value -> workflow = new WorkflowMountConfigResponse(
                    value.workflowInstanceId(), value.workflowInstanceRevision(), value.installationRevision());
            case KnowledgeMountConfig value -> knowledge = new KnowledgeMountConfigResponse(
                    value.mode(), value.pinnedRevisionId());
        }
        return new CapabilityMountResponse(
                mount.mountId(), mount.capability().type(), definitionId, versionId,
                skill, tool, workflow, knowledge, mount.priority(), mount.revision(),
                mount.active() ? CapabilityMountStatus.ACTIVE : CapabilityMountStatus.DISABLED);
    }
}
