package com.skillforge.institution.runtime;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.port.ConversationTaskPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 按当前可信用户与助手归属搜索跨会话任务；列表不暴露来源、维护游标或原会话身份。 */
@Component
public final class ConversationTaskSearchToolHandler implements PlatformToolHandler {
    private static final Set<String> PARAMETERS = Set.of("query", "offset", "limit");
    private final ConversationTaskPort tasks;

    public ConversationTaskSearchToolHandler(ConversationTaskPort tasks) { this.tasks = tasks; }

    @Override public String toolName() { return "search_tasks"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (arguments == null) return failure("invalid_arguments");
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        String query;
        int offset;
        int limit;
        try {
            if (!PARAMETERS.containsAll(arguments.keySet())) throw rejected();
            query = arguments.getOrDefault("query", "") instanceof String value ? value : null;
            offset = integer(arguments, "offset", 0, 0, 10_000);
            limit = integer(arguments, "limit", 30, 1, 100);
            if (query == null || query.length() > 1_000) throw rejected();
        } catch (IllegalArgumentException rejected) {
            return failure("invalid_arguments");
        }
        List<Map<String, Object>> items = tasks.search(context.run().owner(), context.run().agentId(),
                query, true, offset, limit).stream()
                .map(ConversationTaskSearchToolHandler::view).toList();
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        return Map.of("success", true, "tasks", items, "offset", offset,
                "next_offset", offset + items.size(), "page_full", items.size() == limit);
    }

    private static Map<String, Object> view(ConversationTask task) {
        return Map.of("task_id", task.taskId(), "revision", task.revision(), "status", task.status().name(),
                "title", task.title(), "summary", task.summary(), "updated_at", task.updatedAt().toString());
    }

    private static int integer(Map<String, Object> arguments, String name, int fallback, int minimum, int maximum) {
        if (!arguments.containsKey(name)) return fallback;
        Object raw = arguments.get(name);
        if (!(raw instanceof Number number)) throw rejected();
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value < minimum || value > maximum) throw rejected();
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw rejected();
        }
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("search_tasks_arguments_rejected");
    }

    private static Map<String, Object> failure(String error) {
        return Map.of("success", false, "error", error);
    }
}
