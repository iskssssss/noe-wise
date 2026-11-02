package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionTerminationObservation;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrajectoryTerminationProjectionTest {
    private static final TrajectoryQuery.Resource RESOURCE = new TrajectoryQuery.Resource(7, "session", "root");
    private static final String FAILURE = """
            {"schema_id":"skillforge.runtime.execution.closing-budget-failure.v1",
             "code":"execution_output_closing_budget_insufficient","phase":"BUSINESS",
             "shortfalls":[{"scope":"RUN","dimension":"ACTIVE_MILLIS","unit":"ms",
               "required":"240000","available":"180000","deficit":"60000",
               "consumed":"0","reserved":"0","limit":"180000"},
              {"scope":"ROOT","dimension":"TOTAL_TOKENS","unit":"tokens",
               "required":"9007199254740993","available":"9007199254740992","deficit":"1",
               "consumed":"0","reserved":"0","limit":"9007199254740992"}],
             "time_calculation":{"model_calls":"2","model_timeout_ms":"120000",
               "model_window_ms":"120000","local_processing_ms":"120000","required_ms":"240000"}}
            """;

    @Test
    void projectsPersistedFailureAndExactCountsWithoutBorrowingChildAttempts() throws Exception {
        var main = run("main", "TERMINATED", "FINISHED", 0);
        var child = run("child", "TERMINATED", "FINISHED", 1);
        var model = TrajectoryGraphTest.attempt(main, "model", "m", "MODEL", "SUCCEEDED");
        var tool = TrajectoryGraphTest.attempt(main, "tool", "t", "TOOL", "FAILED");
        var aux = TrajectoryGraphTest.auxiliary(main, "a", "aux", "SUCCEEDED", 1L, 1L);
        var result = TrajectoryTerminationProjection.project(RESOURCE, List.of(main, child),
                List.of(new TrajectoryRepository.Edge("main", "tool", "t", "child", "DELEGATION")),
                List.of(event(main, 11, FAILURE), event(child, 20, null)),
                List.of(model, model, tool, TrajectoryGraphTest.attempt(main, "knowledge", "k", "KNOWLEDGE", "SUCCEEDED"),
                        TrajectoryGraphTest.attempt(main, "workflow", "w", "WORKFLOW", "SUCCEEDED"),
                        TrajectoryGraphTest.attempt(main, "delegation", "d", "DELEGATION", "SUCCEEDED"),
                        TrajectoryGraphTest.attempt(child, "child-model", "cm", "MODEL", "SUCCEEDED"),
                        TrajectoryGraphTest.attempt(child, "child-tool", "ct", "TOOL", "SUCCEEDED")),
                List.of(aux, aux, TrajectoryGraphTest.auxiliary(main, "m", "duplicate-core", "SUCCEEDED", 1L, 1L),
                        TrajectoryGraphTest.auxiliary(child, "ca", "child-aux", "SUCCEEDED", 1L, 1L)));
        assertEquals("main", result.runId());
        assertEquals("1", result.businessModelAttempts()); assertEquals("4", result.toolAttempts()); assertEquals("1", result.auxiliaryModelAttempts());
        assertEquals("BUSINESS", result.budgetFailure().phase());
        assertEquals("9007199254740993", result.budgetFailure().shortfalls().getLast().required());
        assertEquals("tokens", result.budgetFailure().shortfalls().getLast().unit());
        assertEquals("240000", result.budgetFailure().timeCalculation().requiredMillis());
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
        assertEquals("9007199254740993", wire.path("budget_failure").path("shortfalls").get(1).path("required").textValue());
        assertEquals("240000", wire.path("budget_failure").path("time_calculation").path("required_ms").textValue());
    }

    @Test
    void oldFailureKeepsReasonAndMissingBudgetWhileLatestSuccessfulOrRunningRunHidesIt() {
        var failed = run("old", "TERMINATED", "FINISHED", 0);
        var fact = event(failed, 11, null);
        var result = TrajectoryTerminationProjection.project(RESOURCE, List.of(failed), List.of(), List.of(fact), List.of(), List.of());
        assertEquals(ExecutionTerminationObservation.BUDGET_FAILURE_CODE, result.reasonCode());
        assertNull(result.budgetFailure()); assertEquals("0", result.businessModelAttempts());
        var completed = run("new", "COMPLETED", "FINISHED", 1);
        assertNull(TrajectoryTerminationProjection.project(RESOURCE, List.of(failed, completed), List.of(), List.of(fact), List.of(), List.of()));
        var running = run("new", "RUNNING", "MODEL", 1);
        assertNull(TrajectoryTerminationProjection.project(RESOURCE, List.of(failed, running), List.of(), List.of(fact), List.of(), List.of()));
    }

    @Test
    void doesNotBorrowAnotherRootOrSessionOrEventsPastSnapshot() {
        var main = run("main", "TERMINATED", "FINISHED", 0);
        var other = new TrajectoryRepository.RunFact("other", "other-session", "other-root", "other-input", "t", "s", "9",
                "TERMINATED", "FINISHED", main.createdSecond(), 0, main.updatedSecond(), 0, 100, 100);
        assertNull(TrajectoryTerminationProjection.project(RESOURCE, List.of(main, other), List.of(), List.of(event(other, 11, FAILURE)), List.of(), List.of()));
        assertNull(TrajectoryTerminationProjection.project(RESOURCE, List.of(main), List.of(), List.of(event(main, 101, FAILURE)), List.of(), List.of()));
        assertNull(TrajectoryTerminationProjection.project(RESOURCE, List.of(other), List.of(), List.of(event(other, 11, FAILURE)), List.of(), List.of()));
    }

    @Test
    void latestCompletedEventAndMalformedNewBudgetAreNeverSilentlyReplacedByOlderData() {
        var main = run("main", "TERMINATED", "FINISHED", 0);
        var latest = event(main, 12, "{}");
        assertThrows(IllegalArgumentException.class, () -> TrajectoryTerminationProjection.project(RESOURCE, List.of(main), List.of(),
                List.of(event(main, 11, FAILURE), latest), List.of(), List.of()));
    }

    private static TrajectoryRepository.RunFact run(String id, String status, String phase, int offset) {
        var value = TrajectoryGraphTest.run(id, "session", "input");
        return new TrajectoryRepository.RunFact(value.runId(), value.sessionId(), value.rootInputId(), value.inputId(), value.traceId(), value.spanId(), value.agentId(),
                status, phase, value.createdSecond() + offset, 0, value.updatedSecond() + offset, 0, 100, 100);
    }

    private static TrajectoryRepository.EventFact event(TrajectoryRepository.RunFact run, long sequence, String failure) {
        String json = "{\"text\":\"\",\"status\":\"" + run.status() + "\",\"reason\":\"" + ExecutionTerminationObservation.BUDGET_FAILURE_CODE
                + "\"" + (failure == null ? "" : ",\"budget_failure\":" + failure) + "}";
        return new TrajectoryRepository.EventFact("EXECUTION", run.sessionId(), run.runId(), null, "completed:" + run.runId() + ":" + sequence,
                AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA, json, run.inputId(), run.rootInputId(), run.traceId(), run.spanId(), sequence,
                run.updatedSecond(), run.updatedNano());
    }
}
