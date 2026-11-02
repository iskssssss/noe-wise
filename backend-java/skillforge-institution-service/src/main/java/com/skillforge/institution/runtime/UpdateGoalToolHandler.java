package com.skillforge.institution.runtime;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.runtime.session.SessionGoal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 模型仅能以 invocationId 和预期 revision 将自己的当前 Goal 标记为完成或阻塞。 */
@Component
public final class UpdateGoalToolHandler implements PlatformToolHandler {
    private static final Set<String> PARAMETERS = Set.of("expected_revision", "state", "reason");
    private final AgentRuntimeGoalDriver goals;
    private final DeliverableService deliverables;

    public UpdateGoalToolHandler(AgentRuntimeGoalDriver goals, DeliverableService deliverables) {
        this.goals = goals;
        this.deliverables = deliverables;
    }

    @Override public String toolName() { return "update_goal"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (arguments == null || !PARAMETERS.containsAll(arguments.keySet())) return failure("invalid_arguments");
        if (context.cancellationSignal().isCancellationRequested()) return failure("cancelled");
        if (!Instant.now().isBefore(context.deadlineAt())) return failure("deadline_exceeded");
        long revision;
        SessionGoal.State state;
        String reason;
        try {
            if (!arguments.containsKey("expected_revision") || !arguments.containsKey("state")) throw rejected();
            revision = positiveLong(arguments.get("expected_revision"));
            if (!(arguments.get("state") instanceof String value)) throw rejected();
            state = switch (value) {
                case "COMPLETE" -> SessionGoal.State.COMPLETE;
                case "BLOCKED" -> SessionGoal.State.BLOCKED;
                default -> throw rejected();
            };
            if (arguments.containsKey("reason")) {
                if (!(arguments.get("reason") instanceof String reasonValue) || reasonValue.length() > 512)
                    throw rejected();
                reason = reasonValue;
            } else {
                reason = null;
            }
            if (state == SessionGoal.State.BLOCKED && (reason == null || reason.isBlank())) throw rejected();
        } catch (IllegalArgumentException invalid) {
            return failure("invalid_arguments");
        }

        AgentRuntimeGoalDriver.Status status;
        try {
            if (state == SessionGoal.State.COMPLETE && deliverables.hasOutputs(context.run())) {
                return Map.of("success", false, "error", "goal_completion_requires_finalized_outputs",
                        "instruction", "请使用 finalize_task_outputs 的 goal_completion.expected_revision 提交完成意图，等待当前 Run 与成果发布成功后由宿主完成 Goal。");
            }
            status = goals.update(context.run(), context.invocationId(), revision, state, reason);
        } catch (SessionGoal.Rejected rejected) {
            String error = switch (rejected.reason()) {
                case NOT_FOUND -> "goal_not_found";
                case REVISION_CONFLICT -> "revision_conflict";
                case OPERATION_CONFLICT -> "idempotency_conflict";
                case STATE_CONFLICT -> "state_conflict";
                default -> null;
            };
            if (error == null) throw rejected;
            return failure(error);
        }
        if (context.cancellationSignal().isCancellationRequested()
                || !Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("goal_update_result_cancelled");
        return GetGoalToolHandler.response(status, deliverables.completionView(context.run().owner(), status.goal()));
    }

    private static long positiveLong(Object raw) {
        if (!(raw instanceof Number number)) throw rejected();
        try {
            long value = new BigDecimal(number.toString()).longValueExact();
            if (value <= 0) throw rejected();
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw rejected();
        }
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("update_goal_arguments_rejected");
    }

    private static Map<String, Object> failure(String error) {
        return Map.of("success", false, "error", error);
    }
}
