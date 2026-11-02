package com.skillforge.core.agent.dto.response;

import com.skillforge.core.agent.model.AgentTemplateDefinition;

/** 机构可选的完整模板发布版本，不携带任何连接凭据。 */
public record AgentTemplateItemResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*",description="稳定定义ID")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id, String templateKey, String name, String version, String description,
        String iconUrl, AgentTemplateDefinition definition) { }
