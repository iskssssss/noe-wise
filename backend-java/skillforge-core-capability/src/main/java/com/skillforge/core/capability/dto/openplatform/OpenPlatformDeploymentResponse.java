package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform Deployment 安全投影。 */
public record OpenPlatformDeploymentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String deploymentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"agent", "channel", "connector", "knowledge_base", "persona", "policy", "skill", "tool", "workflow"})
        String resourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long resourceRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String environment,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long deploymentRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String etag) {
}
