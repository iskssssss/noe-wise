package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.institution.tasks.ConversationTaskCoordinator;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.port.ConversationTaskPort;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 按可信工具上下文读取单个任务安全视图；归属不匹配与不存在使用同一拒绝结果。 */
@Component
public final class ConversationTaskReadToolHandler implements PlatformToolHandler {
    private static final Set<String> PARAMETERS = Set.of("task_id");
    private final ConversationTaskPort tasks;
    private final ObjectMapper json;

    public ConversationTaskReadToolHandler(ConversationTaskPort tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @Override public String toolName() { return "read_task"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (arguments == null) return failure("invalid_arguments");
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        if (!PARAMETERS.equals(arguments.keySet()) || !(arguments.get("task_id") instanceof String taskId)
                || taskId.isBlank() || taskId.length() > 200) return failure("invalid_arguments");
        var found = tasks.find(context.run().owner(), context.run().agentId(), taskId);
        if (found.isEmpty()) return failure("task_not_found");
        ConversationTask task = found.orElseThrow();
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        return Map.of("success", true, "task", view(task));
    }

    private Map<String, Object> view(ConversationTask task) {
        Object state = json.convertValue(ConversationTaskCoordinator.safeState(json, task, true), Object.class);
        return Map.of("task_id", task.taskId(), "revision", task.revision(), "status", task.status().name(),
                "title", task.title(), "summary", task.summary(), "state", state,
                "updated_at", task.updatedAt().toString());
    }

    private static Map<String, Object> failure(String error) {
        return Map.of("success", false, "error", error);
    }
}
