package com.skillforge.core.chat.integration.publicread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** 平台公开回答只消费新执行事务已保存的完成事件；不重建旧RunOutcome或执行状态。 */
@Component
public final class RuntimeCompletedAnswerReader {
    private final ObjectMapper json;
    public RuntimeCompletedAnswerReader(ObjectMapper json) { this.json = Objects.requireNonNull(json); }
    public enum Reason { UNSUPPORTED_RUNTIME_EVENT, UNREADABLE_RUNTIME_EVENT }
    public static final class FormatException extends IllegalArgumentException {
        private final Reason reason;
        private FormatException(Reason reason, Throwable cause) { super("public_answer_source_invalid", cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    public record Answer(String messageId, String runId, String rootInputId, long messageVersion,
                         String content, String phase, String state, long sequence, long updatedSequence,
                         String runtimeEventId, Instant createdAt) {
        public ChatMessageRecord message() {
            return new ChatMessageRecord(messageId, "assistant", content, createdAt, metadata(), messageVersion, List.of());
        }
        public Map<String,Object> metadata() {
            return Map.of("run_id", runId, "root_run_id", runId, "root_input_id", rootInputId,
                    "phase", phase, "state", state, "source_sequence", sequence,
                    "updated_sequence", updatedSequence, "runtime_event_id", runtimeEventId);
        }
        public Map<String,Object> wire(String sessionId) {
            var wire = new LinkedHashMap<String,Object>(metadata());
            wire.put("session_id", sessionId); wire.put("message_id", messageId); wire.put("message_version", messageVersion);
            wire.put("content", content); wire.put("created_at", BusinessTime.format(createdAt)); wire.put("references", List.of());
            return Map.copyOf(wire);
        }
    }

    /** 调用者先验证候选会话归属；同批仍必须来自同租户的严格单调、唯一来源页。 */
    public List<Answer> read(List<ExecutionStore.SavedEvent> events, PublicOutputGuard.Scope guard) {
        Objects.requireNonNull(guard);
        var messages = new LinkedHashMap<String,Answer>(); var ids = new HashSet<EventId>();
        TenantId tenant = null; long previous = 0;
        for (var saved : events.stream().sorted(Comparator.comparingLong(ExecutionStore.SavedEvent::sequence)).toList()) {
            if (tenant == null) tenant = saved.run().tenantId();
            if (!tenant.equals(saved.run().tenantId()) || saved.sequence() <= previous || !ids.add(saved.event().id())) throw invalid(null);
            previous = saved.sequence();
            if (!AgentExecutionProgram.COMPLETED_EVENT.equals(saved.event().type())) continue;
            if (!AgentExecutionProgram.COMPLETED_SCHEMA.equals(saved.event().data().schemaId()))
                throw new FormatException(Reason.UNSUPPORTED_RUNTIME_EVENT, null);
            var body = body(saved);
            String status = text(body, "status");
            if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "TERMINATED").contains(status)) throw invalid(null);
            String output = completionText(string(body, "text"), status, body.path("reason").asText());
            if (output.isBlank()) continue;
            boolean rejected = guard.rejects(output);
            String id = saved.event().id().value();
            messages.put(id, new Answer(id, saved.run().runId(), text(body, "rootInputId"), ChatMessageRecord.INITIAL_VERSION,
                    rejected ? PublicOutputGuard.SAFE_MESSAGE : output, "final",
                    rejected ? "failed" : "CANCELLED".equals(status) ? "cancelled" : "COMPLETED".equals(status) ? "completed" : "failed",
                    saved.sequence(), saved.sequence(), id, saved.occurredAt()));
        }
        return List.copyOf(messages.values());
    }

    /** 空正文的失败仍须可见；只解释已保存的终态，不改写执行结果或触发重试。 */
    public static String completionText(String output, String status, String reason) {
        if (!output.isBlank()) return output;
        return switch (status) {
            case "FAILED" -> switch (reason) {
                case "tool_batch_ineligible" -> "本次请求未能完成：智能体同时发起了不能一起执行的工具操作，这组操作未执行。请将任务拆成单步后重新发送；若仍失败，请联系管理员查看运行过程。";
                case "model_rate_limited" -> "模型服务当前限流，本次任务未完成。请联系管理员核查模型额度或限流状态，恢复后再尝试。重新发送前，请在运行过程中核对已执行的操作。";
                case "workflow_output_schema_rejected" -> "工作流输出与声明的格式不一致，本次任务未完成。请联系工作流维护者检查输出 Schema、导出字段及节点输出；修正后，先核对已执行的操作，再决定是否重新运行。";
                default -> "本次请求未能完成。请查看运行过程核对已执行的操作，确认后再决定是否重新发送，或联系管理员处理。";
            };
            case "TERMINATED" -> "本次请求已停止，尚未完成。请查看运行过程核对已执行的操作，再决定是否继续。";
            case "CANCELLED" -> "本次请求已取消。已执行的操作不会自动撤销，可在运行过程中核对。";
            default -> output;
        };
    }

    private JsonNode body(ExecutionStore.SavedEvent saved) {
        try {
            var body = json.readTree(saved.event().data().canonicalJson());
            if (body == null || !body.isObject()) throw invalid(null);
            for (var field : List.of("inputId", "rootInputId", "traceId", "spanId", "agentId")) text(body, field);
            return body;
        } catch (java.io.IOException malformed) { throw invalid(malformed); }
    }
    private static String string(JsonNode body, String field) {
        var value = body.get(field); if (value == null || !value.isTextual()) throw invalid(null);
        return value.textValue();
    }
    private static String text(JsonNode body, String field) {
        var value = string(body, field); if (value.isBlank()) throw invalid(null); return value;
    }
    private static FormatException invalid(Throwable cause) { return new FormatException(Reason.UNREADABLE_RUNTIME_EVENT, cause); }
}
