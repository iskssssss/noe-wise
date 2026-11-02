package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 外部资源映射的安全 revision/ETag 投影。 */
public record OpenPlatformResourceMappingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String integrationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"agent", "channel", "connector", "knowledge_base", "persona", "policy", "skill", "tool", "workflow"})
        String resourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String externalRef,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String etag,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String contentHash) {
}
