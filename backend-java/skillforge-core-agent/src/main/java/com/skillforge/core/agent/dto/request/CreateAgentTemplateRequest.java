package com.skillforge.core.agent.dto.request;

import com.skillforge.core.agent.model.AgentTemplateDefinition;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 平台模板创建请求；定义为完整对象替换，草稿允许尚未选择模型。 */
public record CreateAgentTemplateRequest(
        @NotBlank(message = "name 必填") String name,
        String category, List<String> tags, String description, String iconUrl,
        String priceType, Integer priceCents, AgentTemplateDefinition definition,
        @jakarta.validation.constraints.NotNull(message = "distribution_policy 必填") com.skillforge.core.agent.model.AgentDistributionPolicy distributionPolicy) { }
