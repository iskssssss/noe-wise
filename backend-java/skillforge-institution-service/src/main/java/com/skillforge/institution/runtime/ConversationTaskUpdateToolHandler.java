package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.institution.tasks.ConversationTaskCoordinator;
import com.skillforge.runtime.api.port.ConversationTask;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** 以 invocationId 作为真实操作身份执行任务增量更新；attempt 重试不会产生第二个任务或覆盖新版本。 */
@Component
public final class ConversationTaskUpdateToolHandler implements PlatformToolHandler {
    private static final Set<String> PARAMETERS = Set.of(
            "task_id", "expected_revision", "title", "summary", "facts", "pending", "status");
    private static final Set<String> FACT_PARAMETERS = Set.of("key", "value", "source_event_id", "confirmed");
    private final ConversationTaskCoordinator tasks;
    private final ObjectMapper json;

    public ConversationTaskUpdateToolHandler(ConversationTaskCoordinator tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @Override public String toolName() { return "update_task"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (arguments == null) return failure("invalid_arguments");
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        ConversationTaskCoordinator.ToolUpdate update;
        try {
            if (!PARAMETERS.containsAll(arguments.keySet())) throw rejected();
            boolean existing = arguments.containsKey("task_id");
            if (arguments.containsKey("facts") && arguments.get("facts") == null
                    || arguments.containsKey("pending") && arguments.get("pending") == null
                    || arguments.containsKey("status") && arguments.get("status") == null) throw rejected();
            String taskId = text(arguments, "task_id", false, 200);
            Long revision = positiveLong(arguments, "expected_revision");
            String title = text(arguments, "title", false, 200);
            String summary = text(arguments, "summary", true, 8_000);
            var facts = facts(arguments.get("facts"));
            var pending = pending(arguments.get("pending"));
            var status = status(arguments.get("status"));
            boolean changes = title != null || summary != null || !facts.isEmpty()
                    || arguments.containsKey("pending") || status != null;
            if (existing && (taskId == null || revision == null || !changes)
                    || !existing && (revision != null || title == null)) throw rejected();
            update = new ConversationTaskCoordinator.ToolUpdate(taskId, revision, title, summary,
                    facts, pending, status);
        } catch (IllegalArgumentException rejected) {
            return failure("invalid_arguments");
        }
        ConversationTask task;
        try {
            task = tasks.update(context.run(), context.invocationId(), update);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            String message = rejected.getMessage();
            if (message == null) throw rejected;
            String error = switch (message) {
                case "task_not_found" -> "task_not_found";
                case "task_revision_conflict", "revision", "task_lease_lost", "task_lease_required",
                     "task_maintenance_cursor_changed", "task_maintenance_foreign_cursor_changed",
                     "task_maintenance_receipt_invalid" -> "revision_conflict";
                case "task_operation_conflict", "task_event_conflict", "task_id_conflict" -> "idempotency_conflict";
                case "task_fact_source_missing", "task_fact_source_ambiguous", "task_source_missing" -> "fact_source_invalid";
                case "task_owner_mismatch" -> "task_not_found";
                case "task_fact_limit", "task_evidence_limit" -> "task_limit_exceeded";
                default -> null;
            };
            if (error == null) throw rejected;
            return failure(error);
        }
        if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("task_update_result_cancelled");
        return Map.of("success", true, "task", view(task));
    }

    private List<ConversationTaskCoordinator.ToolFact> facts(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values) || values.size() > 64) throw rejected();
        var result = new ArrayList<ConversationTaskCoordinator.ToolFact>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> rawFact)) throw rejected();
            var fact = new LinkedHashMap<String, Object>();
            rawFact.forEach((key, item) -> {
                if (!(key instanceof String name)) throw rejected();
                fact.put(name, item);
            });
            if (!FACT_PARAMETERS.containsAll(fact.keySet())) throw rejected();
            String key = requiredText(fact, "key", 160);
            String item = requiredValue(fact, "value", 4_000);
            String source = requiredText(fact, "source_event_id", 1_024);
            boolean confirmed = false;
            if (fact.containsKey("confirmed")) {
                if (!(fact.get("confirmed") instanceof Boolean valueConfirmed)) throw rejected();
                confirmed = valueConfirmed;
            }
            result.add(new ConversationTaskCoordinator.ToolFact(key, item, source, confirmed));
        }
        return List.copyOf(result);
    }

    private static List<String> pending(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> values) || values.size() > 32) throw rejected();
        var result = new ArrayList<String>();
        for (var item : values) {
            if (!(item instanceof String text) || text.length() > 1_000) throw rejected();
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static ConversationTask.Status status(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String value)) throw rejected();
        try { return ConversationTask.Status.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw rejected(); }
    }

    private static String text(Map<String, Object> arguments, String field, boolean empty, int maximum) {
        if (!arguments.containsKey(field)) return null;
        Object raw = arguments.get(field);
        if (!(raw instanceof String value) || !empty && value.isBlank() || value.length() > maximum) throw rejected();
        return value;
    }

    private static String requiredText(Map<String, Object> arguments, String field, int maximum) {
        String value = arguments.get(field) instanceof String text ? text : null;
        if (value == null || value.isBlank() || value.length() > maximum) throw rejected();
        return value;
    }

    private static String requiredValue(Map<String, Object> arguments, String field, int maximum) {
        String value = arguments.get(field) instanceof String text ? text : null;
        if (value == null || value.length() > maximum) throw rejected();
        return value;
    }

    private static Long positiveLong(Map<String, Object> arguments, String field) {
        if (!arguments.containsKey(field)) return null;
        Object raw = arguments.get(field);
        if (!(raw instanceof Number number)) throw rejected();
        try {
            long value = new BigDecimal(number.toString()).longValueExact();
            if (value <= 0) throw rejected();
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw rejected();
        }
    }

    private Map<String, Object> view(ConversationTask task) {
        Object state = json.convertValue(ConversationTaskCoordinator.safeState(json, task, true), Object.class);
        return Map.of("task_id", task.taskId(), "revision", task.revision(), "status", task.status().name(),
                "title", task.title(), "summary", task.summary(), "state", state,
                "updated_at", task.updatedAt().toString());
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("update_task_arguments_rejected");
    }

    private static Map<String, Object> failure(String error) {
        return Map.of("success", false, "error", error);
    }
}
