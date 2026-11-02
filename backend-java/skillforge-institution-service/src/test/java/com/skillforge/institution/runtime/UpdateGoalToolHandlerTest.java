package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.SessionGoal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 只有 Goal 的明确规则拒绝可转为模型反馈，同文案的存储失败仍须向上传播。 */
class UpdateGoalToolHandlerTest {
    private final AgentRuntimeGoalDriver goals = mock(AgentRuntimeGoalDriver.class);
    private final DeliverableService deliverables = mock(DeliverableService.class);
    private final UpdateGoalToolHandler handler = new UpdateGoalToolHandler(goals, deliverables);
    private final PlatformToolExecutionContext context = mock(PlatformToolExecutionContext.class);
    private final RunIdentity run = mock(RunIdentity.class);
    private final Map<String, Object> arguments = Map.of("expected_revision", 1, "state", "BLOCKED", "reason", "等待资料");

    @ParameterizedTest
    @EnumSource(value = SessionGoal.Reason.class, names = {"NOT_FOUND", "REVISION_CONFLICT", "OPERATION_CONFLICT", "STATE_CONFLICT"})
    void mapsOnlyTypedGoalRejectionsToTheExistingPublicError(SessionGoal.Reason reason) {
        prepare();
        when(goals.update(run, "operation", 1, SessionGoal.State.BLOCKED, "等待资料"))
                .thenThrow(new SessionGoal.Rejected(reason));
        String error = switch (reason) {
            case NOT_FOUND -> "goal_not_found";
            case REVISION_CONFLICT -> "revision_conflict";
            case OPERATION_CONFLICT -> "idempotency_conflict";
            case STATE_CONFLICT -> "state_conflict";
            default -> throw new AssertionError(reason);
        };

        assertEquals(Map.of("success", false, "error", error), handler.execute(arguments, context));
    }

    @Test
    void technicalFailureCannotImpersonateARevisionConflictByMessage() {
        prepare();
        var failure = new IllegalStateException("goal_revision_conflict");
        when(goals.update(run, "operation", 1, SessionGoal.State.BLOCKED, "等待资料")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> handler.execute(arguments, context)));
    }

    @Test void actualFinalizationStateKeepsGoalCompletionBehindPublicationSettlement() {
        prepare();
        when(deliverables.hasOutputs(run)).thenReturn(true);
        var response = handler.execute(Map.of("expected_revision", 1, "state", "COMPLETE"), context);
        assertEquals(false, response.get("success"));
        assertEquals("goal_completion_requires_finalized_outputs", response.get("error"));
        verify(goals, never()).update(run, "operation", 1, SessionGoal.State.COMPLETE, null);
    }

    @Test void noFinalizationStateAllowsGoalRulesToDecideCompletionDirectly() {
        prepare();
        when(deliverables.hasOutputs(run)).thenReturn(false);
        when(goals.update(run, "operation", 1, SessionGoal.State.COMPLETE, null))
                .thenThrow(new SessionGoal.Rejected(SessionGoal.Reason.STATE_CONFLICT));
        assertEquals(Map.of("success", false, "error", "state_conflict"),
                handler.execute(Map.of("expected_revision", 1, "state", "COMPLETE"), context));
        verify(goals).update(run, "operation", 1, SessionGoal.State.COMPLETE, null);
    }

    private void prepare() {
        when(context.run()).thenReturn(run);
        when(context.invocationId()).thenReturn("operation");
        when(context.cancellationSignal()).thenReturn(() -> false);
        when(context.deadlineAt()).thenReturn(Instant.now().plusSeconds(60));
    }
}
