package com.skillforge.core.agent.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.hibernate.validator.constraints.CodePointLength;
/** 直接创建 Agent 草稿；config.memory.automatic_extraction 仅布尔 true 开启自动记忆提取，缺省关闭；模板安装使用独立接口。 */
public record CreateAgentRequest(
        String agentKey,
        @NotBlank
        @CodePointLength(max = 128, message = "名称最多 128 个字符")
        @Schema(description = "Agent 名称，最多 128 个 Unicode 码点", maxLength = 128)
        String name,
        String systemPrompt,
        String modelConfigId,
        Map<String, Object> config) {
}
