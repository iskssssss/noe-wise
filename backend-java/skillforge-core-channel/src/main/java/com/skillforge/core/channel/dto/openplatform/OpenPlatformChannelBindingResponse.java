package com.skillforge.core.channel.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform ChannelBinding 安全投影。 */
public record OpenPlatformChannelBindingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long channelId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String deploymentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long resourceRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long bindingRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String etag) {
}
