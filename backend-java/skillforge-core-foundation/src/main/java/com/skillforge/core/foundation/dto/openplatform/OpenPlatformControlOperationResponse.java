package com.skillforge.core.foundation.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

import com.skillforge.core.foundation.contract.ControlOperationItem;
import java.time.Instant;
import java.util.List;

/** Open Platform 控制操作的安全状态投影。 */
public record OpenPlatformControlOperationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String rootOperationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String integrationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String operationType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"accepted", "cancelled", "failed", "partially_succeeded", "processing", "review_required", "succeeded"})
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String idempotencyKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean created,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<ControlOperationItem> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant acceptedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"})
        Instant completedAt) {
}
