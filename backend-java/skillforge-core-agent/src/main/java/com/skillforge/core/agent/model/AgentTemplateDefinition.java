package com.skillforge.core.agent.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.dto.request.SkillMountConfigRequest;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import java.util.List;

/** 可发布、可安装的 Agent 完整定义；只保存公共能力引用，不保存租户资源或凭据。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentTemplateDefinition(
        String systemPrompt, String roleDescription, String modelConfigId,
        AgentExecutionConfig executionConfig,
        List<BaseCapability> capabilities,
        List<ResourceSlot> resourceSlots,
        List<DelegationSlot> delegationSlots) {

    public AgentTemplateDefinition {
        executionConfig = executionConfig == null ? AgentExecutionConfig.inherited() : executionConfig;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        resourceSlots = resourceSlots == null ? List.of() : List.copyOf(resourceSlots);
        delegationSlots = delegationSlots == null ? List.of() : List.copyOf(delegationSlots);
    }

    public static AgentTemplateDefinition empty() {
        return new AgentTemplateDefinition("", "", null, null, null, null, null);
    }

    /** SKILL 使用 skillConfig；TOOL 使用登记的 platformToolName，由安装器创建租户部署。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BaseCapability(CapabilityType capabilityType,
            @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*",description="稳定基础能力定义ID")
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) Long capabilityDefinitionId,
            @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*",description="精确基础能力发布版本ID")
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) Long capabilityVersionId, SkillMountConfigRequest skillConfig,
            String platformToolName, Integer priority) { }

    /** 机构资源槽：独立安装可跳过；公共定义部署缺少声明绑定时实例未就绪。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResourceSlot(String key, String label, String description, CapabilityType capabilityType) { }

    /** 公开协作角色说明；实际授权仅来自机构选择的目标 Agent。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DelegationSlot(String key, String label, String description) { }
}
