package com.skillforge.core.chat.dto.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/** 读取 Session Outbox 的只读 cursor 响应；不领取、不确认也不执行会话输入。 */
public record SessionOutboxCursorResponse(
        @Schema(description = "会话稳定标识", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "ses_01J...")
        String sessionId,
        @Schema(description = "本页最后一个持久事件的游标；没有事件时可为 null",
                requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                example = "output:evt_01J...")
        String nextCursor,
        @Schema(description = "按 session_sequence 升序排列的持久事件",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<Event> events) {

    /** 与 SSE/Webhook 共用的稳定事件投影。 */
    @Schema(name="SessionOutboxEvent")
    public record Event(
            @Schema(description = "稳定事件名称", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "execution.completed")
            String event,
            @Schema(description = "Session Outbox 分配的稳定不透明事件标识；同时作为 cursor",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "output:evt_01J...")
            String eventId,
            @Schema(description = "事件所属会话", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "ses_01J...")
            String sessionId,
            @Schema(description = "权威 Session Outbox 分配的单调递增序号",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "17")
            Long sessionSequence,
            @Schema(description = "产生事件的输入标识；无 Runtime 输入关联的主动消息可为 null",
                    requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                    example = "inp_01J...")
            String inputId,
            @Schema(description = "根输入标识；无 Runtime 输入关联的主动消息可为 null",
                    requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                    example = "inp_01J...")
            String rootInputId,
            @Schema(description = "直接因果事件标识；没有直接因果事件时为 null",
                    requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"})
            String causedByEventId,
            @Schema(description = "事件投影版本", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "1")
            int eventVersion,
            @Schema(description = "事件产生时间", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "2026-09-27T10:00:00+08:00")
            Instant occurredAt,
            @Schema(description = "事件业务数据；Runtime 来源可能包含 runtime_event_id 和 sequence",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> data) {
    }
}
