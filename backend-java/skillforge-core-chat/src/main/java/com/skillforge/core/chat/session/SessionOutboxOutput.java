package com.skillforge.core.chat.session;

import com.skillforge.core.shared.tracing.TraceContext;
import java.util.Map;

/**
 * 同一会话事务内尚未写入的公开输出命令；没有序号、持久时间或投递回执。
 * @param eventId 已提交来源派生的稳定输出身份
 * @param eventType 公开事件类型
 * @param payload 既有公开事件信封，事件自身的动态正文保持原格式
 * @param trace 原来源因果信息，普通主动消息允许为空
 * @param runtimeRunId 已核验的 Runtime Run，普通主动消息为空
 */
public record SessionOutboxOutput(String eventId, String eventType, Map<String, Object> payload,
        TraceContext trace, String runtimeRunId) {
    public SessionOutboxOutput {
        if (eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank())
            throw new IllegalArgumentException("outbox_output_identity_required");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        runtimeRunId = runtimeRunId == null || runtimeRunId.isBlank() ? null : runtimeRunId;
    }
}
