package com.skillforge.core.agent.dto.response;

import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import java.util.List;

/** 机构可见的模板发布摘要，不携带模板提示词或连接凭据。 */
public record AgentTemplateItemResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*",description="稳定定义ID")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id, String templateKey, String name, String version, String description,
        String iconUrl, TemplateSummary definition) {

    /** 仅投影模板目录与安装所需的公开字段。 */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TemplateSummary(String roleDescription, String modelConfigId, AgentExecutionConfig executionConfig,
            List<AgentTemplateDefinition.BaseCapability> capabilities,
            List<AgentTemplateDefinition.ResourceSlot> resourceSlots,
            List<AgentTemplateDefinition.DelegationSlot> delegationSlots) {
        public static TemplateSummary from(AgentTemplateDefinition definition) {
            return new TemplateSummary(definition.roleDescription(), definition.modelConfigId(), definition.executionConfig(),
                    definition.capabilities(), definition.resourceSlots(), definition.delegationSlots());
        }
    }
}
