package com.skillforge.core.chat.trajectory;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionTerminationObservation;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 将已授权主 Run 的原终态投影为安全说明，不读取当前配置或重建历史预算。 */
final class TrajectoryTerminationProjection {
    private TrajectoryTerminationProjection() { }

    static TrajectoryView.Termination project(TrajectoryQuery.Resource resource,
            List<TrajectoryRepository.RunFact> runs, List<TrajectoryRepository.Edge> edges,
            List<TrajectoryRepository.EventFact> events, List<TrajectoryRepository.AttemptFact> attempts,
            List<TrajectoryRepository.AuxiliaryAttemptFact> auxiliary) {
        var children = edges.stream().map(TrajectoryRepository.Edge::childRunId).collect(Collectors.toSet());
        var main = runs.stream().filter(run -> resource.sessionId().equals(run.sessionId())
                && resource.rootInputId().equals(run.rootInputId()) && !children.contains(run.runId()))
                .max(Comparator.comparing(TrajectoryRepository.RunFact::createdAt).thenComparing(TrajectoryRepository.RunFact::runId));
        if (main.isEmpty()) return null;
        var run = main.orElseThrow();
        if (!"FINISHED".equals(run.phase()) || "COMPLETED".equals(run.status())) return null;
        var event = events.stream().filter(value -> "EXECUTION".equals(value.source())
                && run.runId().equals(value.runId()) && run.sessionId().equals(value.sessionId())
                && run.rootInputId().equals(value.rootInputId()) && run.inputId().equals(value.inputId())
                && value.sequence() <= run.executionUpper()
                && AgentExecutionProgram.COMPLETED_EVENT.equals(value.eventType()))
                .max(Comparator.comparingLong(TrajectoryRepository.EventFact::sequence));
        if (event.isEmpty()) return null;
        var fact = event.orElseThrow();
        var termination = ExecutionTerminationObservation.read(fact.eventType(), new TypedPayload(fact.schemaId(), fact.canonicalJson())).orElseThrow();
        if ("COMPLETED".equals(termination.status()) || !Objects.equals(run.status(), termination.status())) return null;
        var modelAttempts = attempts.stream().filter(value -> sameRun(run, value) && "MODEL".equals(value.kind()))
                .map(TrajectoryRepository.AttemptFact::attemptId).collect(Collectors.toSet());
        long toolAttempts = attempts.stream().filter(value -> sameRun(run, value) && !"MODEL".equals(value.kind()))
                .map(TrajectoryRepository.AttemptFact::attemptId).distinct().count();
        long auxiliaryAttempts = auxiliary.stream().filter(value -> run.runId().equals(value.runId())
                && run.sessionId().equals(value.sessionId()) && run.rootInputId().equals(value.rootInputId())
                && run.rootInputId().equals(value.sourceRootInputId()) && !modelAttempts.contains(value.attemptId()))
                .map(TrajectoryRepository.AuxiliaryAttemptFact::attemptId).distinct().count();
        return new TrajectoryView.Termination(run.runId(), termination.reason(), Integer.toString(modelAttempts.size()),
                Long.toString(toolAttempts), Long.toString(auxiliaryAttempts), termination.budgetFailure().map(TrajectoryTerminationProjection::budget).orElse(null));
    }

    private static boolean sameRun(TrajectoryRepository.RunFact run, TrajectoryRepository.AttemptFact attempt) {
        return run.runId().equals(attempt.runId()) && run.sessionId().equals(attempt.sessionId());
    }

    private static TrajectoryView.BudgetFailure budget(ExecutionBudgetPort.ClosingFailure failure) {
        var calculation = failure.timeCalculation();
        return new TrajectoryView.BudgetFailure(ExecutionTerminationObservation.BUDGET_FAILURE_SCHEMA,
                ExecutionTerminationObservation.BUDGET_FAILURE_CODE, failure.phase().name(), failure.shortfalls().stream()
                .map(value -> new TrajectoryView.BudgetShortfall(value.scope().name(), value.dimension(), value.unit(),
                        Long.toString(value.required()), Long.toString(value.available()), Long.toString(value.deficit()),
                        Long.toString(value.consumed()), Long.toString(value.reserved()), Long.toString(value.limit()))).toList(),
                new TrajectoryView.BudgetTimeCalculation(Integer.toString(calculation.modelCalls()), Long.toString(calculation.modelTimeoutMillis()),
                        Long.toString(calculation.modelWindowMillis()), Long.toString(calculation.localProcessingMillis()), Long.toString(calculation.requiredMillis())));
    }
}
