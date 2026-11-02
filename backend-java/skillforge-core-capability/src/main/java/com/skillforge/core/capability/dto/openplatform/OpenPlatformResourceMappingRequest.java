package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 外部资源映射请求；不承载资源正文。 */
public record OpenPlatformResourceMappingRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1) String resourceId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1) String contentHash) {
}
