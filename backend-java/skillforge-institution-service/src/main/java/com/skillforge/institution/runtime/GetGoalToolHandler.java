package com.skillforge.institution.runtime;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.artifact.DeliverableContract.CompletionView;
import com.skillforge.runtime.session.SessionGoal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 仅按可信 Run owner 读取公开 Goal 快照，不读取或暴露 trace 与宿主身份。 */
@Component
public final class GetGoalToolHandler implements PlatformToolHandler {
    private final AgentRuntimeGoalDriver goals;
    private final DeliverableService deliverables;

    public GetGoalToolHandler(AgentRuntimeGoalDriver goals, DeliverableService deliverables) {
        this.goals = goals;
        this.deliverables = deliverables;
    }

    @Override public String toolName() { return "get_goal"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (arguments == null || !arguments.isEmpty()) return failure("invalid_arguments");
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        var found = goals.read(context.run().owner());
        if (found.isEmpty()) {
            var result = new LinkedHashMap<String, Object>();
            result.put("success", true);
            result.put("goal", null);
            result.put("continuation_enabled", false);
            result.put("completion_pending", false);
            result.put("publication", null);
            return result;
        }
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        var status = found.orElseThrow();
        return response(status, deliverables.completionView(context.run().owner(), status.goal()));
    }

    static Map<String,Object> response(AgentRuntimeGoalDriver.Status status, CompletionView completion) {
        var result = new LinkedHashMap<String,Object>();
        result.put("success", true);
        result.put("goal", view(status.goal()));
        result.put("continuation_enabled", status.continuationEnabled());
        result.put("completion_pending", completion.pending());
        result.put("publication", completion.publication());
        return result;
    }

    static Map<String, Object> view(SessionGoal.Snapshot goal) {
        var result = new LinkedHashMap<String, Object>();
        result.put("goal_id", goal.goalId());
        result.put("revision", goal.revision());
        result.put("objective", goal.objective());
        result.put("state", goal.state().name());
        result.put("max_rounds", goal.maxRounds());
        result.put("rounds_started", goal.roundsStarted());
        result.put("pending_round_input_id", goal.pendingRoundInputId().map(value -> value.value()).orElse(null));
        result.put("last_run_id", goal.lastRunId().map(value -> value.runId()).orElse(null));
        result.put("reason", goal.reason().orElse(null));
        return result;
    }

    private static Map<String, Object> failure(String error) {
        return Map.of("success", false, "error", error);
    }
}
