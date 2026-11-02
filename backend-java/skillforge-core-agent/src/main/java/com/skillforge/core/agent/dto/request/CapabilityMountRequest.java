package com.skillforge.core.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.shared.web.DecimalStringIdDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.capability.CapabilityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Agent CapabilityMount 管理请求。
 *
 * <p>四个 config 字段是封闭的、互斥的类型化配置；这里刻意没有通用
 * {@code Map config}，避免请求体成为运行权限扩展点。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CapabilityMountRequest(
        @NotNull CapabilityType capabilityType,
        @Schema(type="string",pattern="[1-9][0-9]*",description="稳定能力定义ID，正十进制字符串")
        @JsonSerialize(using=ToStringSerializer.class) @JsonDeserialize(using=DecimalStringIdDeserializer.class)
        @NotNull @Positive Long capabilityDefinitionId,
        @Schema(type="string",pattern="[1-9][0-9]*",description="精确能力版本ID；未指定时由正式绑定规则解析")
        @JsonSerialize(using=ToStringSerializer.class) @JsonDeserialize(using=DecimalStringIdDeserializer.class)
        @Positive Long capabilityVersionId,
        SkillMountConfigRequest skillConfig,
        ToolMountConfigRequest toolConfig,
        WorkflowMountConfigRequest workflowConfig,
        KnowledgeMountConfigRequest knowledgeConfig,
        Integer priority,
        CapabilityMountStatus status) {

    /** 管理 API 默认创建/更新为 ACTIVE。 */
    public CapabilityMountRequest(
            CapabilityType capabilityType,
            Long capabilityDefinitionId,
            Long capabilityVersionId,
            SkillMountConfigRequest skillConfig,
            ToolMountConfigRequest toolConfig,
            WorkflowMountConfigRequest workflowConfig,
            KnowledgeMountConfigRequest knowledgeConfig,
            Integer priority) {
        this(capabilityType, capabilityDefinitionId, capabilityVersionId,
                skillConfig, toolConfig, workflowConfig, knowledgeConfig, priority, null);
    }
}
