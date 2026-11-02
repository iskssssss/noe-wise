package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Open Platform 资源目录的安全摘要；目录查询不返回资源正文。 */
public record OpenPlatformResourceCatalogItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"agent", "channel", "connector", "knowledge_base", "persona", "policy", "skill", "tool", "workflow"})
        String resourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"archived", "draft", "published", "ready"})
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String contentHash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String etag,
        Instant createdAt,
        Instant updatedAt,
        @Schema(types = {"string", "null"})
        Instant publishedAt) {
}
