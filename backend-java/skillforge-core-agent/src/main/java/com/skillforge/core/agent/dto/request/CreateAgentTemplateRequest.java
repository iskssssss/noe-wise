package com.skillforge.core.agent.dto.request;

import com.skillforge.core.agent.model.AgentTemplateDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 平台模板创建请求；定义为完整对象替换，草稿允许尚未选择模型。 */
public record CreateAgentTemplateRequest(
        @NotBlank(message = "name 必填")
        @CodePointLength(max = 128, message = "名称最多 128 个字符")
        @Schema(description = "模板名称，最多 128 个 Unicode 码点", maxLength = 128)
        String name,
        String category, List<String> tags, String description, String iconUrl,
        String priceType, Integer priceCents, AgentTemplateDefinition definition,
        @jakarta.validation.constraints.NotNull(message = "distribution_policy 必填") com.skillforge.core.agent.model.AgentDistributionPolicy distributionPolicy) { }
