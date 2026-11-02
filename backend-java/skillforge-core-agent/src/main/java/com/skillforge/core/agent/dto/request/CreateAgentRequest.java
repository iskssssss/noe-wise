package com.skillforge.core.agent.dto.request;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
/** 直接创建 Agent 草稿；config.memory.automatic_extraction 仅布尔 true 开启自动记忆提取，缺省关闭；模板安装使用独立接口。 */
public record CreateAgentRequest(
        String agentKey,
        @NotBlank String name,
        String systemPrompt,
        String modelConfigId,
        Map<String, Object> config) {
}
