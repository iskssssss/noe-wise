package com.skillforge.core.agent.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 平台运营后台 Agent 模板列表项响应 DTO。
 *
 * @param id             模板主键（雪花 ID）
 * @param templateKey    模板业务标识（对外展示，创建时生成 tpl- 前缀）
 * @param name           模板名称
 * @param version        当前发布模板版本号（semver），首次发布为 1.0.0，再次发布 minor +1
 * @param category       模板分类标签
 * @param tags           模板标签数组
 * @param description    模板描述
 * @param iconUrl        图标 URL
 * @param author         作者名称
 * @param authorType     作者类型：platform / isv / tenant
 * @param status         模板状态：draft / published / deprecated / archived
 * @param priceType      价格类型：free / paid
 * @param priceCents     价格（分），0 表示免费
 * @param installCount   累计安装次数（机构据此模板创建 Agent 的计数）
 * @param publishedAt    发布时间，未发布为 null
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record AdminAgentTemplateItemResponse(
        @Schema(description = "模板主键（雪花 ID）") @com.fasterxml.jackson.annotation.JsonProperty("definition_id") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @Schema(description = "模板业务标识（对外展示，创建时生成 tpl- 前缀）") String templateKey,
        @Schema(description = "模板名称") String name,
        @Schema(description = "模板版本号（semver），创建为 1.0.0，改版 minor +1") String version,
        @Schema(description = "模板分类标签") String category,
        @Schema(description = "模板标签数组") List<String> tags,
        @Schema(description = "模板描述") String description,
        @Schema(description = "图标 URL") String iconUrl,
        @Schema(description = "作者名称") String author,
        @Schema(description = "作者类型：platform / isv / tenant", example = "platform") String authorType,
        @Schema(description = "模板状态：draft / published / deprecated / archived", example = "draft") String status,
        @Schema(description = "价格类型：free / paid", example = "free") String priceType,
        @Schema(description = "价格（分），0 表示免费", example = "0") Integer priceCents,
        @Schema(description = "累计安装次数（机构据此模板创建 Agent 的计数）") Integer installCount,
        @Schema(description = "发布时间，未发布为 null") Instant publishedAt,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "更新时间") Instant updatedAt,
        @Schema(description = "工作草稿存在未发布修改") boolean hasUnpublishedChanges,
        com.skillforge.core.agent.model.AgentDistributionPolicy distributionPolicy,String ownerKind,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long ownerTenantId,Long draftRevision) {
    /** 当前发布修订沿用唯一semver指针，未首次发布为空。 */
    @com.fasterxml.jackson.annotation.JsonProperty(value="current_published_revision",access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String currentPublishedRevision() { return publishedAt==null?null:version; }
}
