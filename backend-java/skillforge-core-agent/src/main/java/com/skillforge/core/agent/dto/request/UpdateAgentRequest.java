package com.skillforge.core.agent.dto.request;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.enums.AgentStatus;
import java.util.Map;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
/**
 * 更新 Agent 请求 DTO。
 *
 * @param name            Agent 名称，可选
 * @param status          草稿、停用或归档状态，可选；发布使用独立发布接口
 * @param systemPrompt    system prompt，可选（空串表示清空）
 * @param roleDescription 角色描述，可选（空串表示清空）
 * @param executionConfig 运行预算覆盖，可选（null 不修改；空对象重置为继承）
 * @param modelConfigId   平台 LLM 模型 ID；null 不修改，空串清除为继承机构默认
 * @param config          Agent 通用配置，可选；memory.automatic_extraction 仅布尔 true 开启普通消息自动记忆提取，缺省关闭
 * @param delegationConfig Agent 委派配置，可选；null 不修改
 */
public record UpdateAgentRequest(
        String name,
        AgentStatus status,
        String systemPrompt,
        String roleDescription,
        AgentExecutionConfig executionConfig,
        String modelConfigId,
        Map<String, Object> config,
        @JsonProperty("delegation_config") AgentDelegationConfig delegationConfig,
        String iconUrl) {
}
