package com.skillforge.core.chat.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform 入站受理响应；只暴露稳定输入身份，不暴露内部数据库行。 */
public record OpenPlatformInboundReceiptResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength=128, description="不透明的内部输入身份；不能按外部幂等键拼接")
        String inputId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength=128)
        String rootInputId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "已受理的 Runtime Run 标识；纯会话命令可空",maxLength=240) String runId,
        @Schema(description = "同幂等请求是否复用首次受理结果") boolean replayed) {
    /** 无 Runtime Run 的控制受理结果。 */
    public OpenPlatformInboundReceiptResponse(String sessionId, String inputId, String rootInputId,
                                              String eventId, String status) {
        this(sessionId,inputId,rootInputId,eventId,status,null,false);
    }
}
