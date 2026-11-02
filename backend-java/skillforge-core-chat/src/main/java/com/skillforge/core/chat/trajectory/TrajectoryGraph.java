package com.skillforge.core.chat.trajectory;

import com.skillforge.core.chat.observation.SessionObservationView;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.ExecutionObservation;
import com.skillforge.runtime.memory.MemoryObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** 仅从显式观测身份和持久父边建图；事件因果不充当调用父子关系。 */
final class TrajectoryGraph {
    private TrajectoryGraph() { }

    record Result(List<TrajectoryView.ObservationNode> nodes, String coverage, Map<String, String> eventNodes) { }

    static Result build(List<TrajectoryRepository.RunFact> runs, List<TrajectoryRepository.Edge> edges,
                        List<TrajectoryRepository.AttemptFact> attempts, List<TrajectoryRepository.AuxiliaryAttemptFact> auxiliary,
                        List<TrajectoryRepository.EventFact> events,
                        int maxCodeUnits, int maxNodes, int maxDepth,
                        BiFunction<TrajectoryRepository.AttemptFact, String, TrajectoryView.ContentReference> content,
                        BiFunction<TrajectoryRepository.AuxiliaryAttemptFact, String, TrajectoryView.ContentReference> auxiliaryContent) {
        Map<String, TrajectoryRepository.RunFact> byRun = runs.stream().collect(Collectors.toMap(
                TrajectoryRepository.RunFact::runId, value -> value));
        Map<String, Builder> nodes = new LinkedHashMap<>();
        Map<String, ExecutionObservation.Fact> facts = new LinkedHashMap<>();
        Map<String, String> eventNodes = new LinkedHashMap<>();
        Map<String, String> attemptNode = new LinkedHashMap<>();
        Map<String, String> attemptStatuses = new LinkedHashMap<>();
        Set<String> childRuns = edges.stream().map(TrajectoryRepository.Edge::childRunId).collect(Collectors.toSet());
        boolean complete = true;
        for (var run : runs) {
            if (!childRuns.contains(run.runId())) entry(nodes, run, run.inputId(), run.traceId(), run.spanId());
            var agent = new Builder(ExecutionObservation.agentId(run.runId()), null, TrajectoryView.Kind.AGENT,
                    "Agent " + run.agentId(), run, run.inputId(), run.traceId(), run.spanId());
            agent.status = "UNCERTAIN".equals(run.phase()) ? "OUTCOME_UNKNOWN" : run.status();
            agent.startedAt = run.createdAt();
            agent.finishedAt = terminal(run.status()) ? run.updatedAt() : null;
            agent.durationMs = duration(agent.startedAt, agent.finishedAt);
            agent.summary = "Agent 运行跨度（包含排队与等待）";
            nodes.put(agent.id, agent);
        }
        for (var event : events) {
            if (!ExecutionObservation.EVENT_TYPE.equals(event.eventType())) continue;
            var fact = ExecutionObservation.decode(new TypedPayload(event.schemaId(), event.canonicalJson()), maxCodeUnits, maxNodes, maxDepth);
            var run = byRun.get(event.runId());
            if (run == null || !run.runId().equals(fact.runId()) || !run.rootInputId().equals(fact.rootInputId()))
                throw new IllegalArgumentException("trajectory_observation_owner_mismatch");
            String agentId = segment(nodes, run, fact.inputId(), fact.traceId(), fact.spanId(), childRuns.contains(run.runId()));
            if (fact.stepId() != null) {
                String stepId = scoped(fact.stepId(), run, fact.inputId());
                var step = nodes.computeIfAbsent(stepId, ignored -> new Builder(stepId, agentId, TrajectoryView.Kind.STEP,
                        fact.iteration() == null ? "ReAct 执行轮" : "ReAct 第 " + fact.iteration() + " 轮", run,
                        fact.inputId(), fact.traceId(), fact.spanId()));
                step.stepId = fact.stepId(); step.iteration = fact.iteration();
                step.record(event); step.summary = "由实际模型决策身份关联的执行轮";
            }
            String id = scoped(fact.operationId(), run, fact.inputId());
            String parent = fact.parentOperationId().equals(ExecutionObservation.agentId(run.runId()))
                    ? agentId : scoped(fact.parentOperationId(), run, fact.inputId());
            var node = nodes.computeIfAbsent(id, ignored -> new Builder(id, parent,
                    TrajectoryView.Kind.valueOf(fact.kind().name()), fact.name(), run,
                    fact.inputId(), fact.traceId(), fact.spanId()));
            if (!Objects.equals(node.parent, parent) || node.kind != TrajectoryView.Kind.valueOf(fact.kind().name()))
                throw new IllegalArgumentException("trajectory_operation_identity_conflict");
            node.actionId = fact.actionId(); node.stepId = fact.stepId(); node.iteration = fact.iteration();
            node.status = fact.status().name(); node.summary = fact.reasonCode();
            node.record(event);
            eventNodes.put(event.eventId(), id);
            if (fact.status() != ExecutionObservation.Status.REGISTERED) node.finishedAt = event.occurredAt();
            if (fact.elapsedNanos() != null) node.durationMs = Long.toString(fact.elapsedNanos() / 1_000_000L);
            if (fact.attemptId() != null && fact.operationId().equals(fact.actionId())
                    && Set.of(ExecutionObservation.Kind.LLM, ExecutionObservation.Kind.TOOL, ExecutionObservation.Kind.WORKFLOW).contains(fact.kind())) {
                attemptNode.put(fact.attemptId(), id); attemptStatuses.put(fact.attemptId(), fact.status().name());
            }
            facts.put(id, fact);
        }
        for (var actual : attempts) {
            var run = byRun.get(actual.runId());
            if (run == null) throw new IllegalArgumentException("trajectory_attempt_owner_mismatch");
            String id = attemptNode.get(actual.attemptId());
            if (id == null) {
                complete = false;
                id = actual.actionId();
                String parent = ExecutionObservation.agentId(run.runId());
                if (actual.batchModelActionId() != null) {
                    parent = ExecutionObservation.stepId(actual.batchModelActionId());
                    String stepId = parent;
                    nodes.computeIfAbsent(stepId, ignored -> new Builder(stepId, ExecutionObservation.agentId(run.runId()),
                            TrajectoryView.Kind.STEP, "ReAct 执行轮", run, run.inputId(), run.traceId(), run.spanId()));
                }
                String operationParent = parent;
                nodes.computeIfAbsent(id, ignored -> new Builder(actual.actionId(), operationParent,
                        kind(actual.kind()), actual.target(), run, run.inputId(), run.traceId(), run.spanId()));
            }
            var node = nodes.get(id); node.actionId = actual.actionId();
            String status = attemptStatuses.getOrDefault(actual.attemptId(), Objects.requireNonNullElse(actual.resultKind(), "REGISTERED"));
            var totals = totals(actual);
            var input = content.apply(actual, "input");
            var output = actual.resultRef() == null ? TrajectoryView.ContentReference.missing() : content.apply(actual, "output");
            node.attempts.add(new TrajectoryView.Attempt(actual.attemptId(), status, actual.provider(), actual.model(),
                    actual.startedAt(), actual.finishedAt(), duration(actual.startedAt(), actual.finishedAt()),
                    report(actual), actual.diagnosticAvailable(), totals, input, output));
            if (!facts.containsKey(id)) { node.status = status; node.summary = "操作父级或阶段观测未完整记录"; }
            node.input = input; node.output = output;
            if (node.attempts.size() == 1) {
                node.startedAt = actual.startedAt(); node.finishedAt = actual.finishedAt();
                node.durationMs = duration(actual.startedAt(), actual.finishedAt());
            } else {
                node.startedAt = earlier(node.startedAt, actual.startedAt());
                node.finishedAt = later(node.finishedAt, actual.finishedAt());
                node.durationMs = null;
            }
        }
        Set<String> primaryAttempts = attempts.stream().map(TrajectoryRepository.AttemptFact::attemptId).collect(Collectors.toSet());
        Set<String> auxiliaryAttempts = new LinkedHashSet<>();
        for (var actual : auxiliary) {
            if (primaryAttempts.contains(actual.attemptId()) || !auxiliaryAttempts.add(actual.attemptId())) continue;
            var run = byRun.get(actual.runId());
            if (run == null || !run.sessionId().equals(actual.sessionId()) || !run.rootInputId().equals(actual.rootInputId()))
                throw new IllegalArgumentException("trajectory_auxiliary_owner_mismatch");
            String id = auxiliaryNodeId(actual);
            var node = nodes.computeIfAbsent(id, ignored -> new Builder(id, ExecutionObservation.agentId(run.runId()),
                    TrajectoryView.Kind.LLM, actual.callPoint() == null ? "辅助模型调用" : actual.callPoint(), run,
                    actual.inputId(), actual.traceId(), actual.spanId()));
            node.status = "UNRESOLVED".equals(actual.status()) ? "OUTCOME_UNKNOWN" : actual.status();
            boolean sourceComplete = Objects.equals(run.rootInputId(), actual.sourceRootInputId());
            if (!sourceComplete) complete = false;
            node.exactSource &= sourceComplete;
            var input = node.exactSource ? auxiliaryContent.apply(actual, "input") : TrajectoryView.ContentReference.missing();
            var output = node.exactSource ? auxiliaryContent.apply(actual, "output") : TrajectoryView.ContentReference.missing();
            node.summary = node.exactSource ? "固定流程辅助模型；父运行由持久预算与真实外调尝试关联，原始材料按调用尝试读取"
                    : "辅助模型父运行已由持久预算验证；外调根输入关联不完整，用量与正文未记录";
            node.attempts.add(new TrajectoryView.Attempt(actual.attemptId(), node.status, actual.provider(), actual.model(),
                    actual.startedAt(), actual.finishedAt(), duration(actual.startedAt(), actual.finishedAt()), report(actual), false, totals(actual), input, output));
            node.input = input; node.output = output;
            node.startedAt = earlier(node.startedAt, actual.startedAt());
            node.finishedAt = node.attempts.stream().anyMatch(attempt -> attempt.finishedAt() == null) ? null
                    : node.attempts.stream().map(TrajectoryView.Attempt::finishedAt).max(Comparator.naturalOrder()).orElse(null);
            node.durationMs = node.attempts.size() == 1 ? duration(actual.startedAt(), actual.finishedAt()) : null;
        }
        for (var event : events) {
            if (!"SESSION".equals(event.source())) continue;
            var run = byRun.get(event.runId());
            if (run == null) continue;
            var metadata = MemoryObservation.read(event.eventType(), new TypedPayload(event.schemaId(), event.canonicalJson()));
            var owners = event.actionId() == null ? List.<Builder>of() : nodes.values().stream()
                    .filter(value -> run.runId().equals(value.run.runId()) && event.actionId().equals(value.actionId)
                            && Set.of(TrajectoryView.Kind.TOOL, TrajectoryView.Kind.WORKFLOW).contains(value.kind)).toList();
            String parent = event.actionId() == null ? ExecutionObservation.agentId(run.runId()) : owners.size() == 1 ? owners.getFirst().id : null;
            if (event.actionId() != null && parent == null) complete = false;
            String id = "memory:" + run.runId() + ":" + metadata.operationId();
            var node = nodes.computeIfAbsent(id, ignored -> new Builder(id, parent, TrajectoryView.Kind.valueOf(metadata.kind().name()),
                    "retrieval.performed".equals(event.eventType()) ? "记忆检索" : "记忆写入", run,
                    event.inputId(), event.traceId(), event.spanId()));
            node.status = metadata.status(); node.record(event);
            eventNodes.put(event.eventId(), id);
            node.summary = "已提交记忆事实 · " + metadata.operation() + " · " + metadata.phase();
        }
        Map<String, TrajectoryRepository.Edge> primary = new LinkedHashMap<>();
        for (var edge : edges) {
            var run = byRun.get(edge.childRunId());
            if (run == null || !byRun.containsKey(edge.parentRunId())) continue;
            String parent = edge.parentAttemptId() == null ? null : attemptNode.get(edge.parentAttemptId());
            if (parent == null) parent = edge.parentActionId() == null ? ExecutionObservation.agentId(edge.parentRunId()) : edge.parentActionId();
            String child = ExecutionObservation.agentId(edge.childRunId());
            if (primary.putIfAbsent(edge.childRunId(), edge) == null && !wouldCycle(nodes, child, parent)) nodes.get(child).parent = parent;
            else {
                var reference = new Builder("agent-reference:" + parent + ":" + edge.childRunId(), parent,
                        TrajectoryView.Kind.AGENT, "关联 Agent " + run.agentId(), run, run.inputId(), run.traceId(), run.spanId());
                reference.status = nodes.get(child).status;
                reference.summary = "关联同一子运行 " + edge.childRunId() + "；调用与用量仅在主节点计入一次";
                nodes.put(reference.id, reference);
            }
        }
        for (var run : runs) {
            var agent = nodes.get(ExecutionObservation.agentId(run.runId()));
            if (agent.parent == null) agent.parent = entryId(run.sessionId(), run.inputId());
            if (!nodes.containsKey(agent.parent)) { agent.parent = null; complete = false; }
        }
        for (var node : nodes.values()) {
            if (node.parent != null && !nodes.containsKey(node.parent)) { node.parent = null; complete = false; }
            if (node.kind == TrajectoryView.Kind.STEP) completeStep(nodes, node);
            if (node.kind == TrajectoryView.Kind.ENTRY) completeEntry(runs, childRuns, node);
        }
        var result = nodes.values().stream().sorted(Comparator.comparing((Builder value) -> value.startedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(value -> value.id)).map(Builder::view).toList();
        return new Result(result, complete ? "COMPLETE" : "PARTIAL", Map.copyOf(eventNodes));
    }

    /** ENTRY 使用明确关联的主 Run 跨度；续接输入没有独立边界时不能借用原 Run 的受理时间。 */
    private static void completeEntry(List<TrajectoryRepository.RunFact> runs, Set<String> childRuns, Builder entry) {
        var associated = runs.stream().filter(run -> !childRuns.contains(run.runId())
                && run.sessionId().equals(entry.run.sessionId()) && run.inputId().equals(entry.inputId)).toList();
        entry.finishedAt = null; entry.durationMs = null;
        if (associated.isEmpty()) {
            entry.status = "RECORDED"; entry.startedAt = null;
            entry.summary = "续接输入已关联，未记录独立受理与完成边界";
            return;
        }
        entry.startedAt = associated.stream().map(TrajectoryRepository.RunFact::createdAt).min(Comparator.naturalOrder()).orElseThrow();
        if (associated.stream().anyMatch(run -> "UNCERTAIN".equals(run.phase()) || unknown(run.status()))) {
            entry.status = "OUTCOME_UNKNOWN";
            entry.summary = "外部请求关联 Run 结果未知，结束时间和跨度未确认";
            return;
        }
        var statuses = associated.stream().map(TrajectoryRepository.RunFact::status).toList();
        if (statuses.stream().anyMatch(status -> Set.of("RUNNING", "RESUMING").contains(status))) entry.status = "RUNNING";
        else if (statuses.contains("QUEUED")) entry.status = "QUEUED";
        else if (statuses.contains("SUSPENDED")) entry.status = "SUSPENDED";
        else if (statuses.stream().allMatch(TrajectoryGraph::terminal)
                && associated.stream().noneMatch(run -> run.updatedAt().isBefore(run.createdAt()))) {
            entry.status = terminalStatus(statuses, "COMPLETED");
            entry.finishedAt = associated.stream().map(TrajectoryRepository.RunFact::updatedAt).max(Comparator.naturalOrder()).orElseThrow();
            entry.durationMs = duration(entry.startedAt, entry.finishedAt);
            entry.summary = "外部请求关联 Run 的跨度（包含排队与等待）";
            return;
        } else {
            entry.status = "RECORDED";
            entry.summary = "外部请求关联 Run 的终态或时间边界不完整，未推断完成";
            return;
        }
        entry.summary = "外部请求关联 Run 尚未终结，结束时间和跨度未确认";
    }

    /** STEP 是显式关联子操作的墙钟跨度；并行耗时不累加，尚可能追加操作时不补造结束。 */
    private static void completeStep(Map<String, Builder> nodes, Builder step) {
        var children = nodes.values().stream().filter(child -> step.id.equals(child.parent)).toList();
        step.startedAt = children.stream().map(child -> child.startedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        step.finishedAt = null; step.durationMs = null;
        if (children.isEmpty()) {
            step.status = "RECORDED";
            step.summary = "执行轮已关联，子操作及起止边界未完整记录";
            return;
        }
        var statuses = children.stream().map(child -> child.status).toList();
        if (statuses.stream().anyMatch(TrajectoryGraph::unknown)) {
            step.status = "OUTCOME_UNKNOWN";
            step.summary = "执行轮存在结果未知的子操作，结束边界未确认";
            return;
        }
        if (statuses.stream().anyMatch(status -> Set.of("REGISTERED", "QUEUED", "RUNNING", "RESUMING").contains(status))) {
            step.status = "RUNNING";
            step.summary = "执行轮仍有子操作未终结，结束边界未确认";
            return;
        }
        if (statuses.stream().anyMatch(status -> Set.of("WAITING", "INPUT_REQUIRED", "SUSPENDED").contains(status))) {
            step.status = "WAITING";
            step.summary = "执行轮子操作正在等待，结束边界未确认";
            return;
        }
        if (statuses.stream().anyMatch(status -> !operationTerminal(status))
                || children.stream().anyMatch(child -> child.startedAt == null
                        || child.finishedAt == null || child.finishedAt.isBefore(child.startedAt))) {
            step.status = "RECORDED";
            step.summary = "执行轮子操作起止或终态记录不完整，未推断执行轮完成";
            return;
        }
        boolean laterStep = step.iteration != null && nodes.values().stream().anyMatch(other -> other.kind == TrajectoryView.Kind.STEP
                && other.run.runId().equals(step.run.runId()) && other.inputId.equals(step.inputId) && other.iteration != null
                && new java.math.BigInteger(other.iteration).compareTo(new java.math.BigInteger(step.iteration)) > 0);
        if (!(terminal(step.run.status()) && !"UNCERTAIN".equals(step.run.phase())) && !laterStep) {
            step.status = "RECORDED";
            step.summary = "已记录子操作终态，但执行轮的结束边界尚未确认";
            return;
        }
        step.status = terminalStatus(statuses, "SUCCEEDED");
        step.finishedAt = children.stream().map(child -> child.finishedAt).max(Comparator.naturalOrder()).orElseThrow();
        step.durationMs = duration(step.startedAt, step.finishedAt);
        step.summary = "执行轮已记录子操作跨度（最早开始至最晚结束，包含等待，并行耗时不累加）";
    }

    private static boolean unknown(String status) { return Set.of("UNKNOWN", "OUTCOME_UNKNOWN", "UNRESOLVED").contains(status); }
    private static boolean operationTerminal(String status) { return terminal(status) || Set.of("SUCCEEDED", "REJECTED").contains(status); }
    private static String terminalStatus(List<String> statuses, String success) {
        if (statuses.stream().anyMatch(status -> Set.of("FAILED", "REJECTED").contains(status))) return "FAILED";
        if (statuses.contains("TERMINATED")) return "TERMINATED";
        if (statuses.contains("CANCELLED")) return "CANCELLED";
        return success;
    }

    private static String segment(Map<String, Builder> nodes, TrajectoryRepository.RunFact run, String input,
                                  String trace, String span, boolean child) {
        if (run.inputId().equals(input)) return ExecutionObservation.agentId(run.runId());
        entry(nodes, run, input, trace, span);
        String id = scoped(ExecutionObservation.agentId(run.runId()), run, input);
        var value = nodes.computeIfAbsent(id, ignored -> new Builder(id, entryId(run.sessionId(), input), TrajectoryView.Kind.AGENT,
                "Agent 续接执行", run, input, trace, span));
        value.summary = "续接同一 Run " + run.runId() + "；未记录的技术 Trace 不补造";
        return id;
    }
    private static void entry(Map<String, Builder> nodes, TrajectoryRepository.RunFact run, String input, String trace, String span) {
        String id = entryId(run.sessionId(), input);
        nodes.computeIfAbsent(id, ignored -> {
            var value = new Builder(id, null, TrajectoryView.Kind.ENTRY,
                    run.inputId().equals(input) ? "外部请求" : "恢复或后续输入", run, input, trace, span);
            value.startedAt = run.inputId().equals(input) ? run.createdAt() : null;
            value.status = "RECORDED"; return value;
        });
    }
    private static String entryId(String session, String input) { return "entry:" + session.length() + ":" + session + ":" + input; }
    private static String scoped(String id, TrajectoryRepository.RunFact run, String input) {
        return run.inputId().equals(input) ? id : id + ":input:" + input;
    }
    private static boolean wouldCycle(Map<String, Builder> nodes, String child, String parent) {
        Set<String> visited = new LinkedHashSet<>();
        for (String id = parent; id != null && visited.add(id); ) {
            if (child.equals(id)) return true;
            var node = nodes.get(id); id = node == null ? null : node.parent;
        }
        return false;
    }
    private static TrajectoryView.Kind kind(String kind) {
        return switch (kind) { case "MODEL" -> TrajectoryView.Kind.LLM; case "WORKFLOW" -> TrajectoryView.Kind.WORKFLOW; default -> TrajectoryView.Kind.TOOL; };
    }
    static boolean terminal(String status) { return Set.of("COMPLETED", "FAILED", "TERMINATED", "CANCELLED").contains(status); }
    static String duration(Instant from, Instant to) {
        return from == null || to == null || to.isBefore(from) ? null : Long.toString(Duration.between(from, to).toMillis());
    }
    private static Instant earlier(Instant a, Instant b) { return a == null ? b : b == null || a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a == null ? b : b == null || a.isAfter(b) ? a : b; }
    static String auxiliaryNodeId(TrajectoryRepository.AuxiliaryAttemptFact value) {
        return "auxiliary:" + value.runId().length() + ":" + value.runId() + ":" + value.invocationId();
    }
    static SessionObservationView.TokenTotals totals(TrajectoryRepository.ModelUsageFact value) {
        return new SessionObservationView.TokenTotals(text(value.promptTokens()), text(value.completionTokens()), text(value.cachedInputTokens()), text(value.cacheWriteInputTokens()), text(value.reasoningOutputTokens()));
    }
    static String report(TrajectoryRepository.ModelUsageFact value) {
        if (value.promptTokens() == null && value.completionTokens() == null && value.cachedInputTokens() == null
                && value.cacheWriteInputTokens() == null && value.reasoningOutputTokens() == null) return "UNKNOWN";
        if (value.promptTokens() == null || value.completionTokens() == null) return "PARTIAL";
        return value.promptTokens() == 0 && value.completionTokens() == 0 ? "ZERO" : "REPORTED";
    }
    private static String text(Long value) { return value == null ? null : value.toString(); }

    private static final class Builder {
        final String id; String parent; final TrajectoryView.Kind kind; final String name;
        final TrajectoryRepository.RunFact run; final String inputId; final String traceId; final String spanId;
        String status = "RECORDED"; String actionId; String stepId; String iteration; String summary; String durationMs;
        boolean exactSource = true;
        Instant startedAt; Instant finishedAt; long sequence;
        TrajectoryView.ContentReference input = TrajectoryView.ContentReference.missing();
        TrajectoryView.ContentReference output = TrajectoryView.ContentReference.missing();
        final List<String> sources = new ArrayList<>(); final List<TrajectoryView.Attempt> attempts = new ArrayList<>();
        Builder(String id, String parent, TrajectoryView.Kind kind, String name, TrajectoryRepository.RunFact run,
                String inputId, String traceId, String spanId) {
            this.id = id; this.parent = parent; this.kind = kind; this.name = name; this.run = run;
            this.inputId = inputId; this.traceId = traceId; this.spanId = spanId;
        }
        void record(TrajectoryRepository.EventFact event) {
            if (startedAt == null) startedAt = event.occurredAt();
            if (sequence == 0 || event.sequence() < sequence) sequence = event.sequence();
            if (!sources.contains(event.eventId())) sources.add(event.eventId());
        }
        TrajectoryView.ObservationNode view() {
            return new TrajectoryView.ObservationNode(id, parent, kind, name, status, run.sessionId(), run.rootInputId(),
                    inputId, run.runId(), actionId, stepId, iteration, traceId, spanId, startedAt, finishedAt,
                    durationMs, Long.toString(sequence), summary, input, output, sources, attempts);
        }
    }
}
