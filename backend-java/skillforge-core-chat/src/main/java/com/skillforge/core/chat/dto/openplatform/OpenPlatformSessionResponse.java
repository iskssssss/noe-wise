package com.skillforge.core.chat.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 安全会话响应。 */
public record OpenPlatformSessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String integrationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String externalConversationRef,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,description="精确十进制EndUser身份")
        String endUserId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},description="机构Agent精确十进制身份")
        String agentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UserSubject subject) {

    /** 个人入口只返回真实用户归属；群归属由 groups 专属契约返回。 */
    public record UserSubject(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"user"}) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endUserId) {
        public UserSubject {
            if (!"user".equals(kind) || endUserId == null || endUserId.isBlank())
                throw new IllegalArgumentException("personal session requires user subject");
        }
    }
}
