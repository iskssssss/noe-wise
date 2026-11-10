package com.skillforge.core.agent.dto.response;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.enums.AgentStatus;
import java.time.Instant;
/**
 * Agent 列表项响应 DTO。
 *
 * @param id           主键
 * @param agentKey     业务 Key
 * @param name         Agent 名称
 * @param templateId   模板主键
 * @param templateName 模板名称
 * @param status       Agent 状态
 * @param createdAt    创建时间
 * @param delegationConfig Agent 原生委派配置
 */
public record AgentItemResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String agentKey,
        String name,
        Long templateId,
        String templateName,
        AgentStatus status,
        Instant createdAt,
        @JsonProperty("delegation_config") AgentDelegationConfig delegationConfig,
        String iconUrl, String roleDescription, int revision,
        @JsonProperty("published_revision_id") Long publishedRevisionId,
        @JsonProperty("published_revision") Integer publishedRevision,
        @JsonProperty("has_draft_changes") boolean hasDraftChanges,
        @JsonProperty("definition_managed") boolean definitionManaged) {
}
