package com.skillforge.core.chat.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * 将持久 Session Outbox 条目投影为所有传输共享的事件形状。
 *
 * <p>SSE、cursor pull 和后续 Webhook 只允许消费此投影；它们不能从消息历史重建内容，
 * 也不能改变 session sequence、因果字段或 eventId。</p>
 */
public final class SessionOutboxEventProjector {

    public static final int EVENT_VERSION = 1;

    private SessionOutboxEventProjector() {
    }

    /** 将冻结输出校验并投影为可传输事件。 */
    public static Event project(SessionOutboxEntry output) {
        if (output == null || output.getSessionId() == null || output.getSessionId().isBlank()) {
            throw new IllegalArgumentException("Session Outbox output must include sessionId");
        }
        if (output.getEventId() == null || output.getEventId().isBlank()) {
            throw new IllegalArgumentException("Session Outbox output must include eventId");
        }
        Map<String, Object> payload = output.getPayload();
        if (payload == null) {
            throw new IllegalArgumentException("Session Outbox output must include payload");
        }
        Object eventValue = payload.get("event");
        String event = eventValue == null ? null : String.valueOf(eventValue);
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Session Outbox output must include event");
        }
        Map<String, Object> data = data(payload.get("data"));
        if (event.startsWith("process.")) {
            // 所有传输只消费明确业务呈现；冻结并不授予原始调用 JSON 公开权限。
            data.keySet().retainAll(Set.of("process_id", "run_id", "input_id", "reference_id", "type",
                    "sequence", "status", "presentation", "started_at", "finished_at", "runtime_event_id",
                    "root_run_id", "root_input_id", "ordinal", "updated_sequence", "actor_agent_id", "actor_name", "stage"));
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            // 阶段属于公开执行事实。所有传输均保留相同身份，只允许公开 id/index，避免嵌套字段绕过白名单。
            if (data.get("stage") != null) {
                var stage = json.valueToTree(data.get("stage"));
                if (!stage.path("id").isTextual() || stage.path("id").asText().isBlank()
                        || !stage.path("index").isIntegralNumber() || !stage.path("index").canConvertToInt()
                        || stage.path("index").asInt() < 1) {
                    throw new IllegalArgumentException("Process stage must include a non-blank id and positive index");
                }
                data.put("stage", Map.of("id", stage.path("id").asText(), "index", stage.path("index").asInt()));
            } else {
                data.remove("stage");
            }
            try {
                var presentation=json.convertValue(data.get("presentation"),com.skillforge.core.chat.contract.ProcessPresentation.class);
                if(presentation==null)throw new IllegalArgumentException();
                data.put("presentation",json.convertValue(presentation,Map.class));
            } catch(IllegalArgumentException rejected) {
                data.put("presentation",Map.of("title","处理任务","summary","过程记录当前不可用",
                        "results",List.of(),"references",List.of()));
            }
        }
        data.put("session_id", output.getSessionId());
        data.put("event_id", output.getEventId());
        data.put("session_sequence", output.getSessionSequence());
        putIfPresent(data, "input_id", output.getInputId());
        putIfPresent(data, "root_input_id", output.getRootInputId());
        putIfPresent(data, "caused_by_event_id", output.getCausedByEventId());
        data.put("event_version", EVENT_VERSION);
        putIfPresent(data, "trace_id", output.getTraceId());
        data.put("occurred_at", com.skillforge.common.time.BusinessTime.format(output.getCreatedAt()));
        data.put("durability", "persistent");
        return new Event(
                event,
                output.getEventId(),
                output.getSessionId(),
                output.getSessionSequence(),
                output.getInputId(),
                output.getRootInputId(),
                output.getCausedByEventId(),
                EVENT_VERSION,
                output.getCreatedAt(),
                Collections.unmodifiableMap(new LinkedHashMap<>(data)));
    }

    private static Map<String, Object> data(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
        }
        return result;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        target.remove(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /** 不可变传输投影；嵌套 data 已复制，调用方不能回写冻结事件。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            String event,
            String eventId,
            String sessionId,
            Long sessionSequence,
            String inputId,
            String rootInputId,
            String causedByEventId,
            int eventVersion,
            Instant occurredAt,
            Map<String, Object> data) {
        public Event {
            data = SessionOutboxEntry.immutableMap(data);
        }
    }
}
