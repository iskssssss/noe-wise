package com.skillforge.core.agent.dto.response;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.enums.AgentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
/**
 * Agent 详情响应 DTO。
 *
 * @param id             主键
 * @param agentKey       业务 Key
 * @param name           Agent 名称
 * @param templateId     来源模板主键（仅创建来源记录）
 * @param templateName   来源模板名称
 * @param status         Agent 状态
 * @param createdAt      创建时间
 * @param systemPrompt   system prompt（本体配置）
 * @param roleDescription 角色描述（本体配置）
 * @param executionConfig 运行预算覆盖（本体配置）
 * @param modelConfigId  平台 LLM 模型 ID；null 表示继承机构默认
 * @param config         Agent 通用配置
 * @param publishedAt          发布时间
 * @param mountSetGeneration  当前 CapabilityMount 装配代
 * @param mounts               带 revision 的类型化 CapabilityMount 列表
 * @param delegationConfig     Agent 原生委派配置
 */
public record AgentDetailResponse(
        Long id,
        String agentKey,
        String name,
        Long templateId,
        String templateName,
        AgentStatus status,
        Instant createdAt,
        String systemPrompt,
        String roleDescription,
        AgentExecutionConfig executionConfig,
        String modelConfigId,
        Map<String, Object> config,
        Instant publishedAt,
        long mountSetGeneration,
        List<CapabilityMountResponse> mounts,
        @JsonProperty("delegation_config") AgentDelegationConfig delegationConfig,
        String iconUrl, int revision,
        @JsonProperty("published_revision_id") Long publishedRevisionId,
        @JsonProperty("published_revision") Integer publishedRevision,
        @JsonProperty("has_draft_changes") boolean hasDraftChanges,
        @JsonProperty("definition_managed") boolean definitionManaged) {
}
