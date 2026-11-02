package com.skillforge.core.chat.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 创建/续接代理会话请求；tenant/integration 由凭证确定，endUserId 必须显式传入。 */
public record OpenPlatformCreateSessionRequest(
        @Schema(types={"string","null"}) String sessionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String externalUserRef,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String externalConversationRef,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[1-9][0-9]{0,18}",maxLength=19,
                description="宿主Long范围内的正整数十进制字符串") String agentId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[1-9][0-9]{0,18}",maxLength=19,
                description="ensure返回的精确十进制EndUser身份") String endUserId,
        String businessScopeType,
        String businessScopeRef) {
    /** 无业务范围的机构级会话请求。 */
    public OpenPlatformCreateSessionRequest(String sessionId, String externalUserRef, String externalConversationRef,
            String agentId, String endUserId) {
        this(sessionId, externalUserRef, externalConversationRef, agentId, endUserId, null, null);
    }
}
