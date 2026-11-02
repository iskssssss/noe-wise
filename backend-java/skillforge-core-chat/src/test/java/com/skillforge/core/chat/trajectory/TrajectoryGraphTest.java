package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.session.ExecutionObservation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrajectoryGraphTest {
    private static final Instant NOW = Instant.parse("2026-10-25T01:00:00Z");

    @Test
    void entryUsesCommittedRunSpanAndResumeEntryDoesNotBorrowItsBoundary() throws Exception {
        var run = run("main", "session", "input");
        var graph = graph(List.of(run), List.of(), List.of(), List.of(
                event(run, "resumed-model", "step:resumed-model", "resume-attempt", "resume-input", ExecutionObservation.Status.SUCCEEDED, 1)));
        var entry = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.ENTRY && "input".equals(node.inputId())).findFirst().orElseThrow();
        assertEquals("COMPLETED", entry.status());
        assertEquals(NOW, entry.startedAt());
        assertEquals(NOW.plusSeconds(1), entry.finishedAt());
        assertEquals("1000", entry.durationMs());
        assertTrue(entry.summary().contains("包含排队与等待"));
        var resumed = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.ENTRY && "resume-input".equals(node.inputId())).findFirst().orElseThrow();
        assertEquals("RECORDED", resumed.status());
        assertNull(resumed.startedAt()); assertNull(resumed.finishedAt()); assertNull(resumed.durationMs());
        assertTrue(resumed.summary().contains("未记录独立受理与完成边界"));
    }

    @Test
    void completedStepUsesChildMinMaxSpanRatherThanAddingParallelDurations() throws Exception {
        var run = timedRun("COMPLETED", "FINISHED");
        var graph = graph(List.of(run), List.of(), List.of(
                timedAttempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED", NOW.plusSeconds(1), NOW.plusSeconds(2)),
                timedAttempt(run, "tool-a", "a-attempt", "TOOL", "SUCCEEDED", NOW.plusSeconds(2), NOW.plusSeconds(7)),
                timedAttempt(run, "tool-b", "b-attempt", "TOOL", "SUCCEEDED", NOW.plusSeconds(3), NOW.plusSeconds(6))),
                List.of(event(run, "model", "step:model", "model-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1),
                        event(run, "tool-a", "step:model", "a-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 2),
                        event(run, "tool-b", "step:model", "b-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 3)));
        var step = node(graph, "step:model");
        assertEquals("SUCCEEDED", step.status());
        assertEquals(NOW.plusSeconds(1), step.startedAt());
        assertEquals(NOW.plusSeconds(7), step.finishedAt());
        assertEquals("6000", step.durationMs());
        assertTrue(step.summary().contains("并行耗时不累加"));
    }

    @Test
    void completedStepIncludesPointGuardrailsWithoutInventingTheirElapsedDuration() throws Exception {
        var run = timedRun("COMPLETED", "FINISHED");
        var events = new java.util.ArrayList<TrajectoryRepository.EventFact>();
        events.add(event(run, "model", "step:model", "model-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 4));
        var stages = List.of(ExecutionObservation.Stage.MODEL_INPUT, ExecutionObservation.Stage.MODEL_OUTPUT, ExecutionObservation.Stage.AGENT_OUTPUT);
        long[] sequences = {1, 5, 6};
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            var guard = new ExecutionObservation.Fact("guard:" + stage.name(), "step:model", ExecutionObservation.Kind.GUARDRAIL,
                    stage.name(), ExecutionObservation.Status.SUCCEEDED, run.runId(), "model", "model-attempt", "step:model", "1",
                    "input", "root", run.traceId(), run.spanId(), stage, null, null);
            events.add(encoded(run, guard, sequences[index]));
        }
        var graph = graph(List.of(run), List.of(), List.of(
                timedAttempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED", NOW.plusSeconds(2), NOW.plusSeconds(4))), events);
        var step = node(graph, "step:model");
        assertEquals("SUCCEEDED", step.status());
        assertEquals(NOW.plusSeconds(1), step.startedAt());
        assertEquals(NOW.plusSeconds(6), step.finishedAt());
        assertEquals("5000", step.durationMs());
        var guards = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.GUARDRAIL).toList();
        assertEquals(3, guards.size());
        assertTrue(guards.stream().allMatch(guard -> guard.startedAt().equals(guard.finishedAt())));
        assertTrue(guards.stream().allMatch(guard -> guard.durationMs() == null));
    }

    @Test
    void unknownActiveOrIncompleteChildrenNeverInventStepCompletion() throws Exception {
        var unknownRun = timedRun("RUNNING", "UNCERTAIN");
        var unknown = graph(List.of(unknownRun), List.of(), List.of(
                timedAttempt(unknownRun, "model", "unknown-attempt", "MODEL", "UNRESOLVED", NOW.plusSeconds(1), NOW.plusSeconds(2))),
                List.of(event(unknownRun, "model", "step:model", "unknown-attempt", "input", ExecutionObservation.Status.UNKNOWN, 1)));
        assertEquals("OUTCOME_UNKNOWN", node(unknown, "step:model").status());
        assertNull(node(unknown, "step:model").finishedAt()); assertNull(node(unknown, "step:model").durationMs());
        var unknownEntry = unknown.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.ENTRY).findFirst().orElseThrow();
        assertEquals("OUTCOME_UNKNOWN", unknownEntry.status()); assertNull(unknownEntry.finishedAt());

        var activeRun = timedRun("RUNNING", "COMPUTING");
        var running = graph(List.of(activeRun), List.of(), List.of(
                timedAttempt(activeRun, "model", "running-attempt", "MODEL", null, NOW.plusSeconds(1), null)),
                List.of(event(activeRun, "model", "step:model", "running-attempt", "input", ExecutionObservation.Status.REGISTERED, 1)));
        assertEquals("RUNNING", node(running, "step:model").status());
        assertNull(node(running, "step:model").finishedAt());
        assertNull(running.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.ENTRY).findFirst().orElseThrow().finishedAt());

        var completedRun = timedRun("COMPLETED", "FINISHED");
        var incomplete = graph(List.of(completedRun), List.of(), List.of(
                timedAttempt(completedRun, "model", "partial-attempt", "MODEL", "SUCCEEDED", NOW.plusSeconds(1), null)),
                List.of(event(completedRun, "model", "step:model", "partial-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1)));
        var step = node(incomplete, "step:model");
        assertEquals("RECORDED", step.status()); assertNull(step.finishedAt()); assertNull(step.durationMs());
        assertTrue(step.summary().contains("记录不完整"));
    }

    @Test
    void completedChildAloneDoesNotCloseALiveRunsLastStep() throws Exception {
        var run = timedRun("RUNNING", "COMPUTING");
        var graph = graph(List.of(run), List.of(), List.of(
                timedAttempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED", NOW.plusSeconds(1), NOW.plusSeconds(2))),
                List.of(event(run, "model", "step:model", "model-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1)));
        var step = node(graph, "step:model");
        assertEquals("RECORDED", step.status());
        assertNull(step.finishedAt()); assertNull(step.durationMs());
        assertTrue(step.summary().contains("结束边界尚未确认"));

        var next = new ExecutionObservation.Fact("next-model", "step:next-model", ExecutionObservation.Kind.LLM,
                "next-model", ExecutionObservation.Status.REGISTERED, run.runId(), "next-model", "next-attempt", "step:next-model", "2",
                "input", "root", run.traceId(), run.spanId(), null, null, null);
        var advanced = graph(List.of(run), List.of(), List.of(
                timedAttempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED", NOW.plusSeconds(1), NOW.plusSeconds(2)),
                timedAttempt(run, "next-model", "next-attempt", "MODEL", null, NOW.plusSeconds(3), null)),
                List.of(event(run, "model", "step:model", "model-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1),
                        encoded(run, next, 2)));
        assertEquals("SUCCEEDED", node(advanced, "step:model").status());
        assertEquals(NOW.plusSeconds(2), node(advanced, "step:model").finishedAt());
        assertEquals("RUNNING", node(advanced, "step:next-model").status());
        assertNull(node(advanced, "step:next-model").finishedAt());
    }

    @Test
    void keepsEachFailedAndSuccessfulAttemptAndDoesNotInventProviderDuration() throws Exception {
        var run = run("main", "session", "input");
        var events = List.of(event(run, "model", "step:model", "attempt-failed", "input", ExecutionObservation.Status.FAILED, 1),
                event(run, "model", "step:model", "attempt-ok", "input", ExecutionObservation.Status.SUCCEEDED, 2));
        var graph = graph(List.of(run), List.of(), List.of(
                attempt(run, "model", "attempt-failed", "MODEL", "FAILED"),
                attempt(run, "model", "attempt-ok", "MODEL", "SUCCEEDED")), events);
        var model = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.LLM).findFirst().orElseThrow();
        assertEquals(List.of("FAILED", "SUCCEEDED"), model.attempts().stream().map(TrajectoryView.Attempt::status).toList());
        assertEquals("SUCCEEDED", model.status());
        assertEquals("step:model", model.parentNodeId());
        assertNull(model.durationMs());
        assertTrue(model.attempts().stream().allMatch(value -> value.durationMs() == null));
        assertEquals("AVAILABLE", model.input().availability());
        assertEquals("ref:attempt-ok:input", model.input().ref());
        assertEquals(List.of("ref:attempt-failed:input", "ref:attempt-ok:input"), model.attempts().stream()
                .map(value -> value.input().ref()).toList());
        assertEquals(List.of("ref:attempt-failed:output", "ref:attempt-ok:output"), model.attempts().stream()
                .map(value -> value.output().ref()).toList());
    }

    @Test
    void preservesParallelSiblingsAndReferencesTheSameChildWithoutDuplicatingItsExecution() throws Exception {
        var parent = run("main", "session", "input"); var child = run("child", "child-session", "child-input");
        var events = List.of(event(parent, "tool-a", "step:model", "attempt-a", "input", ExecutionObservation.Status.SUCCEEDED, 1),
                event(parent, "tool-b", "step:model", "attempt-b", "input", ExecutionObservation.Status.SUCCEEDED, 2));
        var edges = List.of(new TrajectoryRepository.Edge("main", "tool-a", "attempt-a", "child", "DELEGATION"),
                new TrajectoryRepository.Edge("main", "tool-b", "attempt-b", "child", "DELEGATION"));
        var graph = graph(List.of(parent, child), edges, List.of(attempt(parent, "tool-a", "attempt-a", "TOOL", "SUCCEEDED"),
                attempt(parent, "tool-b", "attempt-b", "TOOL", "SUCCEEDED")), events);
        assertEquals("step:model", node(graph, "tool-a").parentNodeId());
        assertEquals("step:model", node(graph, "tool-b").parentNodeId());
        assertEquals("tool-a", node(graph, "agent:child").parentNodeId());
        var references = graph.nodes().stream().filter(node -> node.nodeId().startsWith("agent-reference:")).toList();
        assertEquals(1, references.size());
        assertEquals("tool-b", references.getFirst().parentNodeId());
        assertTrue(references.getFirst().attempts().isEmpty());
    }

    @Test
    void separatesEqualResumeInputIdsFromDifferentSourceSessions() throws Exception {
        var left = run("left", "left-session", "left-input"); var right = run("right", "right-session", "right-input");
        var events = List.of(event(left, "left-model", "step:left-model", "left-attempt", "resume", ExecutionObservation.Status.SUCCEEDED, 1),
                event(right, "right-model", "step:right-model", "right-attempt", "resume", ExecutionObservation.Status.SUCCEEDED, 1));
        var graph = graph(List.of(left, right), List.of(), List.of(), events);
        var entries = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.ENTRY && "resume".equals(node.inputId())).toList();
        assertEquals(2, entries.size());
        assertEquals(2, entries.stream().map(TrajectoryView.ObservationNode::nodeId).distinct().count());
        assertTrue(entries.stream().allMatch(node -> node.traceId() == null));
        var segments = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.AGENT && "resume".equals(node.inputId())).toList();
        assertEquals(2, segments.size());
        assertEquals(2, segments.stream().map(TrajectoryView.ObservationNode::parentNodeId).distinct().count());
    }

    @Test
    void keepsMissingStepAssociationVisibleWithoutGuessingTheLatestModel() {
        var run = run("main", "session", "input");
        var graph = graph(List.of(run), List.of(), List.of(attempt(run, "unassociated-tool", "attempt", "TOOL", null)), List.of());
        assertEquals("PARTIAL", graph.coverage());
        assertEquals("agent:main", node(graph, "unassociated-tool").parentNodeId());
        assertNull(node(graph, "unassociated-tool").stepId());
    }

    @Test
    void nativeProgramToolWithRecordedOperationIsCompleteWithoutInventingAReactStep() throws Exception {
        var run=run("program-tool:child","session","program-input");
        var fact=new ExecutionObservation.Fact("program-effect",ExecutionObservation.agentId(run.runId()),
                ExecutionObservation.Kind.TOOL,"list_artifacts",ExecutionObservation.Status.SUCCEEDED,
                run.runId(),"program-effect","program-attempt",null,null,run.inputId(),run.rootInputId(),
                run.traceId(),run.spanId(),null,null,null);
        var graph=graph(List.of(run),List.of(),List.of(attempt(run,"program-effect","program-attempt","TOOL","SUCCEEDED")),
                List.of(encoded(run,fact,1)));
        assertEquals("COMPLETE",graph.coverage());
        var tool=node(graph,"program-effect");
        assertEquals("list_artifacts",tool.name());
        assertEquals(ExecutionObservation.agentId(run.runId()),tool.parentNodeId());
        assertNull(tool.stepId());assertNull(tool.iteration());
        assertEquals("program-attempt",tool.attempts().getFirst().attemptId());
        assertTrue(graph.nodes().stream().noneMatch(value->value.kind()==TrajectoryView.Kind.STEP));
    }

    @Test
    void guardrailAndRetrievalFactsNeverStealTheMainInvocationAttempt() throws Exception {
        var run = run("main", "session", "input");
        var main = event(run, "knowledge-tool", "step:model", "attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1);
        var guard = new ExecutionObservation.Fact("guard:attempt:TOOL_OUTPUT", "step:model", ExecutionObservation.Kind.GUARDRAIL,
                "TOOL_OUTPUT", ExecutionObservation.Status.SUCCEEDED, run.runId(), "knowledge-tool", "attempt", "step:model", "1",
                "input", "root", run.traceId(), run.spanId(), ExecutionObservation.Stage.TOOL_OUTPUT, null, null);
        var retrieval = new ExecutionObservation.Fact("knowledge-tool:retrieval", "knowledge-tool", ExecutionObservation.Kind.RETRIEVAL,
                "检索", ExecutionObservation.Status.SUCCEEDED, run.runId(), "knowledge-tool", "attempt", "step:model", "1",
                "input", "root", run.traceId(), run.spanId(), null, null, null);
        var graph = graph(List.of(run), List.of(), List.of(attempt(run, "knowledge-tool", "attempt", "KNOWLEDGE", "SUCCEEDED")),
                List.of(main, encoded(run, retrieval, 2), encoded(run, guard, 3)));
        assertEquals(1, node(graph, "knowledge-tool").attempts().size());
        assertTrue(node(graph, "knowledge-tool:retrieval").attempts().isEmpty());
        assertTrue(node(graph, "guard:attempt:TOOL_OUTPUT").attempts().isEmpty());
        assertTrue(node(graph, "knowledge-tool").input().ref() != null);
    }

    @Test
    void resumedMemoryActionUsesItsExactInvocationEvenWhenSessionLogKeepsOriginalInput() throws Exception {
        var run = run("main", "session-一", "original-input");
        var actual = event(run, "memory-tool", "step:model", "attempt", "resume-input", ExecutionObservation.Status.SUCCEEDED, 1);
        // 使用Memory所属codec独立黄金样本，宿主测试不重建或解析其私有字段。
        String golden;
        try (var source = getClass().getResourceAsStream("/fixtures/trajectory/memory-retrieval-v3.json")) {
            golden = new String(java.util.Objects.requireNonNull(source).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var memory = new TrajectoryRepository.EventFact("SESSION", run.sessionId(), run.runId(), "memory-tool", "memory-event",
                "retrieval.performed", "skillforge.runtime.memory.retrieval-performed.v3", golden, "original-input", "root",
                run.traceId(), run.spanId(), 1, NOW.getEpochSecond() + 2, 0);
        var graph = graph(List.of(run), List.of(), List.of(attempt(run, "memory-tool", "attempt", "TOOL", "SUCCEEDED")), List.of(actual, memory));
        var retrieval = graph.nodes().stream().filter(value -> value.kind() == TrajectoryView.Kind.RETRIEVAL).findFirst().orElseThrow();
        assertEquals("memory-tool:input:resume-input", retrieval.parentNodeId());
        assertEquals("RECORDED", retrieval.status());
        assertTrue(retrieval.summary().contains("CANDIDATE"));
        assertTrue(!retrieval.summary().contains("记住"));
    }

    @Test
    void auxiliaryRetriesKeepTheirRealAttemptsUnderTheAgentWithoutInventingCoreMaterials() {
        var run = run("main", "session", "input");
        var graph = TrajectoryGraph.build(List.of(run), List.of(), List.of(attempt(run, "model", "core", "MODEL", "SUCCEEDED")),
                List.of(auxiliary(run, "core", "duplicate", "SUCCEEDED", 99L, 99L),
                        auxiliary(run, "aux-failed", "aux-call", "FAILED", 7L, 2L),
                        auxiliary(run, "aux-ok", "aux-call", "SUCCEEDED", 5L, 3L),
                        auxiliary(run, "aux-unknown", "unknown-call", "UNRESOLVED", null, null)),
                List.of(), 1_000_000, 10_000, 128, (attempt, side) -> TrajectoryView.ContentReference.missing(), (attempt, side) -> TrajectoryView.ContentReference.missing());
        var auxiliary = graph.nodes().stream().filter(node -> node.nodeId().startsWith("auxiliary:")).toList();
        assertEquals(2, auxiliary.size());
        var retries = auxiliary.stream().filter(node -> node.attempts().size() == 2).findFirst().orElseThrow();
        assertEquals("agent:main", retries.parentNodeId());
        assertNull(retries.actionId()); assertNull(retries.stepId());
        assertEquals("NOT_RECORDED", retries.input().availability());
        assertEquals("NOT_RECORDED", retries.output().availability());
        assertTrue(retries.attempts().stream().noneMatch(TrajectoryView.Attempt::diagnosticAvailable));
        assertEquals(List.of("aux-failed", "aux-ok"), retries.attempts().stream().map(TrajectoryView.Attempt::attemptId).toList());
        var unknown = auxiliary.stream().filter(node -> "OUTCOME_UNKNOWN".equals(node.status())).findFirst().orElseThrow();
        assertEquals("UNKNOWN", unknown.attempts().getFirst().reportStatus());
        assertNull(unknown.attempts().getFirst().totals().promptTokens());
        assertTrue(node(graph, "model").attempts().getFirst().diagnosticAvailable());
    }

    static TrajectoryRepository.AuxiliaryAttemptFact auxiliary(TrajectoryRepository.RunFact run, String attempt,
            String invocation, String status, Long input, Long output) {
        return new TrajectoryRepository.AuxiliaryAttemptFact(run.runId(), run.sessionId(), run.rootInputId(), run.inputId(),
                attempt, invocation, status, "aux-provider", "aux-model", "aux-trace", "aux-span", NOW, "STARTED".equals(status) ? null : NOW.plusMillis(125),
                input, output, null, null, null, input == null ? "UNKNOWN" : "PROVIDER_REPORTED", "system-agent.output-requirements", NOW, run.rootInputId());
    }

    @Test
    void runningAuxiliaryRetryDoesNotInheritThePreviousFailedAttemptEndTime() {
        var run = run("main", "session", "input");
        var graph = TrajectoryGraph.build(List.of(run), List.of(), List.of(),
                List.of(auxiliary(run, "failed", "same-call", "FAILED", 3L, 1L),
                        auxiliary(run, "running", "same-call", "STARTED", null, null)),
                List.of(), 1_000_000, 10_000, 128, (attempt, side) -> TrajectoryView.ContentReference.missing(), (attempt, side) -> TrajectoryView.ContentReference.missing());
        var model = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.LLM).findFirst().orElseThrow();
        assertEquals("STARTED", model.status()); assertNull(model.finishedAt()); assertNull(model.durationMs());
        assertEquals(NOW.plusMillis(125), model.attempts().getFirst().finishedAt());
        assertNull(model.attempts().getLast().finishedAt());
    }

    static TrajectoryRepository.RunFact run(String id, String session, String input) {
        return new TrajectoryRepository.RunFact(id, session, "root", input, "trace-" + id, "span-" + id, "9", "COMPLETED", "FINISHED",
                NOW.getEpochSecond(), 0, NOW.plusSeconds(1).getEpochSecond(), 0, 100, 100);
    }
    private static TrajectoryRepository.RunFact timedRun(String status, String phase) {
        return new TrajectoryRepository.RunFact("main", "session", "root", "input", "trace-main", "span-main", "9", status, phase,
                NOW.getEpochSecond(), 0, NOW.plusSeconds(10).getEpochSecond(), 0, 100, 100);
    }
    private static TrajectoryRepository.AttemptFact timedAttempt(TrajectoryRepository.RunFact run, String action, String id,
            String kind, String result, Instant started, Instant finished) {
        return new TrajectoryRepository.AttemptFact(run.runId(), run.sessionId(), action, kind, "tool.handle", id, 1,
                "request:" + id, result == null ? null : "result:" + id, result, null, "provider", "model", "invocation:" + id,
                started, finished, null, null, null, null, null, "UNKNOWN", "model.chat", NOW, "MODEL".equals(kind));
    }
    static TrajectoryRepository.AttemptFact attempt(TrajectoryRepository.RunFact run, String action, String id, String kind, String result) {
        return new TrajectoryRepository.AttemptFact(run.runId(), run.sessionId(), action, kind, "tool.handle", id, 1,
                "request:" + id, result == null ? null : "result:" + id, result, null, "provider", "model", "invocation:" + id,
                null, null, null, null, null, null, null, "UNKNOWN", "model.chat", NOW, "MODEL".equals(kind));
    }
    static TrajectoryRepository.EventFact event(TrajectoryRepository.RunFact run, String action, String step, String attempt,
            String input, ExecutionObservation.Status status, long sequence) throws Exception {
        var fact = new ExecutionObservation.Fact(action, step, action.contains("model") ? ExecutionObservation.Kind.LLM : ExecutionObservation.Kind.TOOL,
                action, status, run.runId(), action, attempt, step, "1", input, "root",
                input.equals(run.inputId()) ? run.traceId() : null, input.equals(run.inputId()) ? run.spanId() : null, null, null, null);
        return encoded(run, fact, sequence);
    }
    private static TrajectoryRepository.EventFact encoded(TrajectoryRepository.RunFact run, ExecutionObservation.Fact fact, long sequence) throws Exception {
        return new TrajectoryRepository.EventFact("EXECUTION", run.sessionId(), run.runId(), fact.actionId(), "event:" + run.runId() + ":" + sequence,
                ExecutionObservation.EVENT_TYPE, ExecutionObservation.SCHEMA, new ObjectMapper().writeValueAsString(fact), fact.inputId(),
                "root", run.traceId(), run.spanId(), sequence, NOW.getEpochSecond() + sequence, 0);
    }
    private static TrajectoryGraph.Result graph(List<TrajectoryRepository.RunFact> runs, List<TrajectoryRepository.Edge> edges,
            List<TrajectoryRepository.AttemptFact> attempts, List<TrajectoryRepository.EventFact> events) {
        return TrajectoryGraph.build(runs, edges, attempts, List.of(), events, 1_000_000, 10_000, 128,
                (attempt, side) -> new TrajectoryView.ContentReference("ref:" + attempt.attemptId() + ":" + side, "AVAILABLE"),
                (attempt, side) -> TrajectoryView.ContentReference.missing());
    }
    private static TrajectoryView.ObservationNode node(TrajectoryGraph.Result graph, String id) {
        return graph.nodes().stream().filter(node -> node.nodeId().equals(id)).findFirst().orElseThrow();
    }
}
