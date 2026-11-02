package com.skillforge.core.foundation.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 控制操作的单项目标；结果由平台后续 worker 写回。 */
public record OpenPlatformControlOperationItemRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1) String itemKey,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1) String resourceRef) {
}
