package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.runtime.AgentRuntimeGoalDriver;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.institution.artifact.DeliverableContract.Publication;
import com.skillforge.runtime.session.SessionGoal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 用户显式开启、暂停和恢复持续目标；任何读取和写入都先验证当前租户会话归属。 */
@RestController
@RequestMapping("/api/v1/chat/goals")
public final class ChatGoalController {
    private final SessionService sessions;
    private final AgentRuntimeInboundGateway runtime;
    public ChatGoalController(SessionService sessions, AgentRuntimeInboundGateway runtime) {
        this.sessions = sessions; this.runtime = runtime;
    }
    @GetMapping
@RequirePermission("foundation.sessions.read")
    public ApiResponse<GoalStatus> read(@RequestParam("session_id") String sessionId) {
        return ApiResponses.wrap(response(runtime.goal(sessions.require(TenantIds.requireTenantId(), sessionId))));
    }
    @PostMapping("/create")
@RequirePermission("foundation.sessions.run")
    public ApiResponse<GoalStatus> create(@Valid @RequestBody Create request) {
        return ApiResponses.wrap(response(Optional.of(runtime.createGoal(sessions.requireWritable(TenantIds.requireTenantId(), request.sessionId()),
                request.commandId(), request.objective(), request.maxRounds()))));
    }
    @PostMapping("/pause")
@RequirePermission("foundation.sessions.cancel")
    public ApiResponse<GoalStatus> pause(@Valid @RequestBody Control request) { return ApiResponses.wrap(control(request, false)); }
    @PostMapping("/resume")
@RequirePermission("foundation.sessions.run")
    public ApiResponse<GoalStatus> resume(@Valid @RequestBody Control request) { return ApiResponses.wrap(control(request, true)); }
    private GoalStatus control(Control request, boolean resume) {
        return response(Optional.of(runtime.controlGoal(sessions.requireWritable(TenantIds.requireTenantId(), request.sessionId()),
                request.commandId(), request.expectedRevision(), resume)));
    }
    private static GoalStatus response(Optional<AgentRuntimeGoalDriver.Status> status) {
        return status.map(value -> {
            var goal = value.goal();
            return new GoalStatus(new Goal(goal.goalId(), goal.revision(), goal.objective(), goal.state(),
                    goal.maxRounds(), goal.roundsStarted(), goal.pendingRoundInputId().map(input -> input.value()).orElse(null),
                    goal.lastRunId().map(run -> run.runId()).orElse(null), goal.reason().orElse(null)),
                    value.continuationEnabled(), value.completionPending(), value.publication());
        }).orElseGet(() -> new GoalStatus(null, false, false, null));
    }
    /** 固定公开查询协议；关联标识只供程序控制，界面只使用业务正文与可读状态。 */
    public record GoalStatus(Goal goal,
            @JsonProperty("continuation_enabled") boolean continuationEnabled,
            @JsonProperty("completion_pending") boolean completionPending, Publication publication) { }
    public record Goal(@JsonProperty("goal_id") String goalId, long revision, String objective, SessionGoal.State state,
            @JsonProperty("max_rounds") int maxRounds, @JsonProperty("rounds_started") int roundsStarted,
            @JsonProperty("pending_round_input_id") String pendingRoundInputId,
            @JsonProperty("last_run_id") String lastRunId, String reason) { }
    public record Create(@JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("command_id") @NotBlank @Size(max=SessionGoal.MAX_OPERATION_ID_LENGTH) String commandId,
            @NotBlank @Size(max=SessionGoal.MAX_OBJECTIVE_LENGTH) String objective,
            @JsonProperty("max_rounds") @Min(SessionGoal.MIN_ROUNDS) @Max(SessionGoal.MAX_ROUNDS) int maxRounds) { }
    public record Control(@JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("command_id") @NotBlank @Size(max=SessionGoal.MAX_OPERATION_ID_LENGTH) String commandId,
            @JsonProperty("expected_revision") @Min(1) long expectedRevision) { }
}
