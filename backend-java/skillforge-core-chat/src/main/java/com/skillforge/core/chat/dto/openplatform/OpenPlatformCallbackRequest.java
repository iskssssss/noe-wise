package com.skillforge.core.chat.dto.openplatform;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/** 外部业务事件作为一条新输入受理；不能声明内部执行完成或恢复准确等待。 */
public record OpenPlatformCallbackRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description = "外部业务事件的稳定标识")
        String callbackId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description = "固定为 business_event；执行完成使用已注册的专用回调交付入口", allowableValues = "business_event")
        String eventType,
        @Schema(types={"string","null"}, description = "可选的外部执行标识，仅作为来源上下文，不定位或完成内部执行")
        String executionId,
        @Schema(types={"string","null"}, description = "可选的外部工作流标识，仅作为来源上下文，不定位或完成内部工作流")
        String workflowId,
        @Schema(description = "业务上下文；禁止身份、凭据及 workflow_input、tool_input、human_input 回复协议")
        Map<String, Object> payload) {
}
