package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/** Open Platform 资源 revision 安全投影。 */
public record OpenPlatformResourceRevisionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"agent", "channel", "connector", "knowledge_base", "persona", "policy", "skill", "tool", "workflow"})
        String resourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"archived", "draft", "published", "ready"})
        String status,
        Map<String, Object> content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String contentHash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String etag,
        Instant createdAt,
        Instant updatedAt,
        @Schema(types = {"string", "null"})
        Instant publishedAt) {
}
