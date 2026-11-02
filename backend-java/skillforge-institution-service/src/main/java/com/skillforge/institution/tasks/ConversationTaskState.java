package com.skillforge.institution.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.institution.runtime.AgentRuntimeHostContracts;
import java.util.List;
import java.util.Set;

/** 通用任务增量合并；模型不能写确认凭证、业务完成回执或抹除旧事实来源。 */
final class ConversationTaskState {
    private static final Set<String> PATCH_FIELDS = Set.of("facts", "pending", "summary", "title", "action", "taskId", "query", "offset");
    private ConversationTaskState() { }

    static ObjectNode merge(ObjectMapper json, JsonNode previous, JsonNode patch,
                            List<CommittedSessionEvent> sources) {
        if (!patch.isObject()) throw new IllegalArgumentException("task_patch_invalid");
        patch.fieldNames().forEachRemaining(field -> {
            if (!PATCH_FIELDS.contains(field)) throw new IllegalArgumentException("task_patch_field_denied");
        });
        ObjectNode result = previous != null && previous.isObject() ? (ObjectNode) previous.deepCopy() : json.createObjectNode();
        ObjectNode facts = result.has("facts") ? (ObjectNode) result.get("facts") : result.putObject("facts");
        if (patch.has("facts")) {
            if (!patch.get("facts").isArray() || patch.get("facts").size() > 64) throw new IllegalArgumentException("task_facts_invalid");
            for (JsonNode fact : patch.get("facts")) {
                String key = required(fact, "key", 160);
                String eventId = required(fact, "sourceEventId", 1024);
                var source = sources.stream().filter(event -> event.eventId().value().equals(eventId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("task_fact_source_missing"));
                // 空字符串可表示已观察到的空 stderr 等精确值；它不等于字段缺失或 null。
                var valueNode = fact.path("value");
                if (!valueNode.isTextual() || valueNode.textValue().length() > 4000)
                    throw new IllegalArgumentException("task_value_invalid");
                String value = valueNode.textValue();
                // confirmed 仅能声明直接来自用户输入的内容；工具结果保持 observed，由业务适配校验。
                boolean confirmed = fact.path("confirmed").asBoolean(false);
                if (confirmed && !userSource(source)) confirmed = false;
                var item = json.createObjectNode().put("value", value).put("sourceEventId", eventId)
                        .put("sourceSessionId", source.owner().sessionRef().sessionId()).put("confirmed", confirmed);
                facts.set(key, item);
            }
        }
        if (facts.size() > 128) throw new IllegalArgumentException("task_fact_limit");
        if (patch.has("pending")) {
            JsonNode pending = patch.get("pending");
            if (!pending.isArray() || pending.size() > 32) throw new IllegalArgumentException("task_pending_invalid");
            for (var item : pending) if (!item.isTextual() || item.asText().length() > 1000)
                throw new IllegalArgumentException("task_pending_invalid");
            result.set("pending", pending.deepCopy());
        }
        return result;
    }

    /** 续答先由入口校验挂起引用和 checkpoint；仅该用户文本协议与普通消息同等可信。 */
    static boolean userSource(CommittedSessionEvent source) {
        return Set.of("inbound.message", "agent.preparation-input", "task.user-correction").contains(source.eventType())
                || "inbound.action".equals(source.eventType())
                && AgentRuntimeHostContracts.AGENT_HITL_RESPONSE_SCHEMA.equals(source.payload().schemaId());
    }

    static TypedPayload payload(ObjectNode value) { return new TypedPayload(ConversationTask.STATE_SCHEMA, value.toString()); }
    static String required(JsonNode value, String field, int max) {
        var node = value.path(field);
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > max)
            throw new IllegalArgumentException("task_" + field + "_invalid");
        return node.asText();
    }
}
