package com.skillforge.core.chat.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Open Platform 结构化恢复动作；只描述目标执行和受控动作上下文。 */
public record OpenPlatformActionRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String executionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String actionKey,
        Map<String, Object> context) {
}
