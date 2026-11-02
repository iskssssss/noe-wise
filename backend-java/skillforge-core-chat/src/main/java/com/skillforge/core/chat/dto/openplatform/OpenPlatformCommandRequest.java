package com.skillforge.core.chat.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 平台声明命令；不接受任意内部服务参数 Map。 */
public record OpenPlatformCommandRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"cancel_execution","close_session"}) String command,
        @Schema(types={"string","null"}) String executionId,
        @Schema(description="原因记录最多保留256字符，超出部分截断") String reason) {
}
