package com.skillforge.core.agent.dto.response;

import com.skillforge.core.agent.model.AgentTemplateDefinition;
import java.time.Instant;
import java.util.List;

/** 模板完整编辑视图；公开元数据与不可变 Agent 定义共同组成一个发布版本。 */
public record AdminAgentTemplateDetailResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("definition_id") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id, String templateKey, String name, String version, String category,
        List<String> tags, String description, String iconUrl, String author, String authorType,
        String status, String priceType, Integer priceCents, Integer installCount,
        Instant publishedAt, Instant createdAt, Instant updatedAt,
        AgentTemplateDefinition definition, boolean hasUnpublishedChanges,
        com.skillforge.core.agent.model.AgentDistributionPolicy distributionPolicy,String ownerKind,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long ownerTenantId,Long draftRevision) {
    /** 当前发布修订取唯一semver指针，不新增第二份发布状态。 */
    @com.fasterxml.jackson.annotation.JsonProperty("current_published_revision")
    public String currentPublishedRevision() { return publishedAt==null?null:version; }
}
