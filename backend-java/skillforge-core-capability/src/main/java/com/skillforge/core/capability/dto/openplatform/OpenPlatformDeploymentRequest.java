package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform Deployment 请求；目标必须是已发布 revision。 */
public record OpenPlatformDeploymentRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"agent","workflow","skill","persona","policy","tool","connector","channel","knowledge_base"}) String resourceType,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String resourceId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") Long resourceRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1) String environment) {
}
