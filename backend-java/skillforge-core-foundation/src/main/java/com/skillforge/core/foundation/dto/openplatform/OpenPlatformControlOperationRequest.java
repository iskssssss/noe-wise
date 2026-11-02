package com.skillforge.core.foundation.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Open Platform 非会话控制操作受理请求。 */
public record OpenPlatformControlOperationRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String operationId,
        @Schema(types={"string","null"}) String rootOperationId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String operationType,
        List<OpenPlatformControlOperationItemRequest> items) {
}
