package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse.ActivityItem;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse.ProcessItem;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单次公开历史读取的内存折叠；输入只来自已提交 Outbox，不拥有独立状态或执行写权。 */
final class AgentRuntimeExecutionHistory {
    private final ObjectMapper json;
    private final PublicOutputGuard.Scope guard;
    private final Map<String, ChatMessageRecord> messages = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
    private final Map<String, ActivityItem> activities = new LinkedHashMap<>();
    private final Map<String, ProcessItem> processes = new LinkedHashMap<>();
    private ChatHistoryResponse.Collaboration collaboration = ChatHistoryResponse.Collaboration.empty();

    /** 当前公开政策在单次读取中固定，原消息与过程仍由持久事件身份限定。 */
    AgentRuntimeExecutionHistory(ObjectMapper json, PublicOutputGuard.Scope guard) {
        this.json = json; this.guard = guard;
    }

    /** 按会话持久顺序消费与 SSE 相同的公开信封，不解释私有材料。 */
    void accept(SessionOutboxEntry output) {
        var event = SessionOutboxEventProjector.project(output);
        var data = event.data();
        if (Set.of("message.delta", "message.card", "message.card.update").contains(event.event())) {
            var incoming = message(output, json, guard);
            var previous = messages.get(incoming.messageId());
            if (previous == null || incoming.messageVersion() > previous.messageVersion()) {
                if (previous != null) incoming = new ChatMessageRecord(incoming.messageId(), incoming.role(), incoming.content(),
                        previous.createdAt(), incoming.metadata(), incoming.messageVersion(), incoming.references());
                messages.put(incoming.messageId(), incoming);
            }
            String run = optionalText(data, "run_id");
            if (run != null && activities.containsKey(run)) {
                var before = activities.get(run);
                activities.put(run, new ActivityItem(run, before.nodeType(), before.status(), before.card(), before.render(),
                        incoming.messageId(), before.startedAt()));
            }
        } else if (event.event().startsWith("process.")) {
            var next = process(data, event.occurredAt());
            var before = processes.get(next.processId());
            if (before == null || next.updatedSequence() > before.updatedSequence()) {
                if (before != null && Set.of("failed", "outcome_unknown").contains(before.status())
                        && Set.of("succeeded", "cancelled").contains(next.status())) return;
                if (before != null) next = new ProcessItem(next.processId(), next.runId(), next.inputId(), next.referenceId(),
                        next.type(), before.sequence(), next.status(), next.presentation(), before.startedAt(), next.finishedAt(),
                        next.rootRunId(), next.rootInputId(), next.ordinal(), next.updatedSequence(), next.actorAgentId(), next.actorName(), next.stage());
                processes.put(next.processId(), next);
            }
        } else if ("hitl.required".equals(event.event())) {
            String run = text(data, "execution_id");
            if (AgentRuntimeExecutionPublicEvents.rejectsPresentation(json.valueToTree(data), guard, json)) {
                var safe = new LinkedHashMap<>(data); safe.remove("card"); safe.remove("question");
                safe.put("reason", PublicOutputGuard.REJECTION_CODE);
                activity(safe, run, "suspended", event.occurredAt());
                return;
            }
            pending.put(run, new LinkedHashMap<>(data));
            activity(data, run, "waiting_hitl", event.occurredAt());
        } else if ("interaction.resolved".equals(event.event())) {
            String run = text(data, "execution_id");
            var previous = pending.get(run);
            if (previous != null && !sameWait(previous.get("tool_wait"), data.get("tool_wait"))) return;
            if (previous != null) pending.remove(run);
            activity(data, run, "running", event.occurredAt());
        } else if ("execution.accepted".equals(event.event())) {
            activity(data, text(data, "run_id"), "running", event.occurredAt());
        } else if ("execution.waiting".equals(event.event())) {
            activity(data, text(data, "run_id"), "waiting_dependency", event.occurredAt());
        } else if ("execution.completed".equals(event.event())) {
            String run = text(data, "run_id");
            pending.remove(run);
            activity(data, run, text(data, "status"), event.occurredAt());
        } else if ("interaction.rejected".equals(event.event())) {
            activity(data, text(data, "run_id"), "suspended", event.occurredAt());
        } else if ("collaboration.updated".equals(event.event())) {
            var tree = json.valueToTree(data);
            collaboration = new ChatHistoryResponse.Collaboration(tree.path("active").booleanValue(), tree.path("pending").booleanValue(),
                    tree.path("selected_active").booleanValue());
        }
    }

    /** 结果保留原公开媒体元数据和引用；分页由产品消息边界负责。 */
    ChatHistoryResponse snapshot(String sessionId, String cursor) {
        return new ChatHistoryResponse(sessionId,
                messages.values().stream().sorted(Comparator.comparing(ChatMessageRecord::createdAt).thenComparing(ChatMessageRecord::messageId)).toList(),
                pending.values().stream().map(value -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value))).toList(), List.copyOf(activities.values()),
                processes.values().stream().sorted(Comparator.comparingLong(ProcessItem::sequence).thenComparingInt(ProcessItem::ordinal)).toList(),
                collaboration, false, null, cursor);
    }

    /** 同一冻结消息快照供完整历史与有界模型历史共用，不丢弃图片选择器或内容分段。 */
    static ChatMessageRecord message(SessionOutboxEntry output, ObjectMapper json, PublicOutputGuard.Scope guard) {
        var data = SessionOutboxEventProjector.project(output).data();
        var node = json.valueToTree(data);
        String id = text(data, "message_id");
        String role = optionalText(data, "role");
        if (role == null) role = "assistant";
        if (!Set.of("user", "assistant").contains(role)) throw new IllegalArgumentException("execution_history_message_role_invalid");
        var version = node.get("message_version");
        long revision = version == null ? 1 : exactLong(version);
        if (revision < 1) throw new IllegalArgumentException("execution_history_message_version_invalid");
        String content = optionalText(data, "content");
        var metadata = data.get("metadata") == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(object(data.get("metadata")));
        metadata.putAll(data);
        for (var key : List.of("message_id", "message_version", "role", "content", "created_at", "references", "metadata")) metadata.remove(key);
        metadata.put("source_sequence", output.getFirstMessageSequence() == null
                ? data.getOrDefault("sequence", output.getSessionSequence()) : output.getFirstMessageSequence());
        Instant created = instant(optionalText(data, "created_at"), output.getFirstMessageCreatedAt() == null
                ? output.getCreatedAt() : output.getFirstMessageCreatedAt());
        List<ChatReferenceView> references = data.get("references") == null ? List.of() : json.convertValue(data.get("references"),
                json.getTypeFactory().constructCollectionType(List.class, ChatReferenceView.class));
        return guard.protect(new ChatMessageRecord(id, role, content == null ? "" : content, created,
                java.util.Collections.unmodifiableMap(metadata), revision, references));
    }

    /** 活动只由执行公开事实推进，迟到旧事件不改写更新来源或确定终态。 */
    private void activity(Map<String, Object> data, String run, String status, Instant occurredAt) {
        var before = activities.get(run);
        long sequence = sourceSequence(data);
        if (before != null && before.render() != null
                && before.render().get("updated_sequence") instanceof Number saved && saved.longValue() > sequence) return;
        if (before != null && Set.of("completed", "failed", "cancelled", "outcome_unknown").contains(before.status())
                && !Set.of("completed", "failed", "cancelled", "outcome_unknown").contains(status)) return;
        var render = new LinkedHashMap<String, Object>();
        render.put("root_run_id", data.getOrDefault("root_run_id", run));
        if (data.get("root_input_id") != null) render.put("root_input_id", data.get("root_input_id"));
        render.put("updated_sequence", sequence);
        if (data.get("reason") != null) render.put("reason", data.get("reason"));
        Map<String, Object> card = data.get("card") == null ? null : object(data.get("card"));
        String messageId = optionalText(data, "message_id");
        if (messageId == null && before != null) messageId = before.messageId();
        activities.put(run, new ActivityItem(run, "agent_runtime", status, card, Map.copyOf(render), messageId,
                before == null ? occurredAt : before.startedAt()));
    }

    /** 过程字段来自与实时流相同的公开呈现，不读取工具参数或结果。 */
    private ProcessItem process(Map<String, Object> data, Instant occurredAt) {
        var presentation = json.convertValue(data.get("presentation"), ProcessPresentation.class);
        if (PublicPresentationTexts.read(json.valueToTree(presentation)).stream().anyMatch(guard::rejects))
            presentation = ProcessPresentation.of("处理任务", PublicOutputGuard.SAFE_MESSAGE);
        var node = json.valueToTree(data);
        var stage = node.path("stage");
        var stageValue = stage.isMissingNode() ? null : new ChatHistoryResponse.ProcessStage(text(stage, "id"), Math.toIntExact(exactLong(stage.get("index"))));
        return new ProcessItem(text(data, "process_id"), text(data, "run_id"), text(data, "input_id"), text(data, "reference_id"),
                text(data, "type"), exactLong(node.get("sequence")), text(data, "status"), presentation,
                instant(optionalText(data, "started_at"), occurredAt), instant(optionalText(data, "finished_at"), null),
                text(data, "root_run_id"), text(data, "root_input_id"), Math.toIntExact(exactLong(node.get("ordinal"))),
                exactLong(node.get("updated_sequence")), optionalText(data, "actor_agent_id"), optionalText(data, "actor_name"), stageValue);
    }

    /** Wait只按准确Action和代次匹配，不能用Run相同消耗后来的新等待。 */
    private static boolean sameWait(Object left, Object right) {
        if (!(left instanceof Map<?, ?> first) || !(right instanceof Map<?, ?> second)) return false;
        return first.get("action_id") != null && first.get("action_id").equals(second.get("action_id"))
                && first.get("generation") != null && first.get("generation").equals(second.get("generation"));
    }

    /** 持久公开源序号仍是执行事实序号，不解释事件ID中的编码。 */
    private long sourceSequence(Map<String, Object> data) { return exactLong(json.valueToTree(data.get("updated_sequence"))); }

    /** 冻结公开卡片必须保持对象结构。 */
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("execution_history_object_invalid");
        return (Map<String, Object>) value;
    }

    /** 不将浮点或缺失序号转换为合法版本。 */
    private static long exactLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("execution_history_number_invalid");
        return value.longValue();
    }

    /** 公共时间保留源时刻；未声明时只可使用对应持久输出的提交时刻。 */
    private static Instant instant(String value, Instant fallback) { return value == null ? fallback : OffsetDateTime.parse(value).toInstant(); }

    /** 必填公开身份不接受缺失或空文本。 */
    private static String text(Map<String, Object> data, String field) {
        var value = optionalText(data, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("execution_history_text_invalid");
        return value;
    }

    /** 有限结构字段维持原文本类型。 */
    private static String text(JsonNode data, String field) {
        var value = data.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("execution_history_text_invalid");
        return value.textValue();
    }

    /** 显式null与缺失可用调用方声明的默认值，其他类型拒绝。 */
    private static String optionalText(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("execution_history_text_invalid");
        return text;
    }
}
