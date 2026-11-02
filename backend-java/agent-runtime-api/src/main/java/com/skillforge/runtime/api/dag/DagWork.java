package com.skillforge.runtime.api.dag;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Session 中一个活动工作的协调投影；任务生命周期必须引用真实 Run 事件。 */
public record DagWork(Key key, AgentId coordinator, long revision, Status status, DagPlan plan,
                      long approvedRevision, long spentTokens,
                      Map<String, Execution> executions, Map<String, String> consumedEvents, Request request) {
    public enum Status { QUEUED, ACTIVE, CANCELLING, COMPLETED, CANCELLED }
    public enum TaskStatus { RESERVED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, OUTCOME_UNKNOWN }

    public DagWork {
        Objects.requireNonNull(key); Objects.requireNonNull(coordinator); Objects.requireNonNull(status);
        Objects.requireNonNull(request);
        executions = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(executions));
        consumedEvents = Map.copyOf(consumedEvents);
        if (revision < 1 || spentTokens < 0
                || approvedRevision < 0 || approvedRevision > (plan == null ? 0 : plan.revision())) throw new IllegalArgumentException("invalid work limits");
        if (plan == null && !executions.isEmpty()) throw new IllegalArgumentException("unplanned work cannot admit tasks");
        if (plan != null) for (var task : plan.tasks()) {
            if (task.executorType() == DagPlan.ExecutorType.PRIMARY && !task.agentId().equals(coordinator))
                throw new IllegalArgumentException("primary task must use coordinator identity");
        }
        long activePrimary = executions.values().stream().filter(e -> !e.terminal()
                && e.binding().task().executorType() == DagPlan.ExecutorType.PRIMARY).count();
        if (activePrimary > 1) throw new IllegalArgumentException("only one primary step may be active");
        for (var entry : executions.entrySet()) {
            var binding = entry.getValue().binding();
            boolean primary = binding.task().executorType() == DagPlan.ExecutorType.PRIMARY;
            if (primary != binding.runRef().equals(request.controlRun()))
                throw new IllegalArgumentException("task executor Run binding mismatch");
            if (!entry.getKey().equals(entry.getValue().binding().task().id())) throw new IllegalArgumentException("task binding mismatch");
        }
    }

    public record Key(SessionOwner owner, InputId rootInputId) {
        public Key { Objects.requireNonNull(owner); Objects.requireNonNull(rootInputId); }
    }

    /** 原请求随工作保存；controlRun 引用冻结 Agent 范围，工作室不是必填身份。 */
    public record Request(String text, Optional<AgentId> selectedAgent,
                          RunRef controlRun, String handoffBrief, Stage stage,
                          Instant receivedAt, boolean planMode,
                          String requesterId, String channelId) {
        public enum Stage { ROUTING, WORKING, ROUTING_FAILED }
        public Request {
            text = DagPlan.text(text); Objects.requireNonNull(selectedAgent); Objects.requireNonNull(controlRun);
            Objects.requireNonNull(stage); Objects.requireNonNull(receivedAt);
            requesterId = requesterId == null ? "" : requesterId;
            channelId = channelId == null ? "" : channelId;
            handoffBrief = handoffBrief == null ? "" : handoffBrief;
        }
        public Request(String text, Optional<AgentId> selectedAgent, RunRef controlRun, String handoffBrief) {
            this(text, selectedAgent, controlRun, handoffBrief, Stage.WORKING);
        }
        public Request(String text, Optional<AgentId> selectedAgent, RunRef controlRun,
                String handoffBrief, Stage stage) {
            this(text, selectedAgent, controlRun, handoffBrief, stage, Instant.EPOCH, false, "", "");
        }
    }

    /** 一次业务准入永久固定；等待后只更换执行许可，不替换该绑定。 */
    public record Binding(String admissionId, long planRevision, DagPlan.Task task, RunRef runRef,
                          Map<String, BoundInput> inputs) {
        public Binding {
            admissionId = DagPlan.text(admissionId); Objects.requireNonNull(task); Objects.requireNonNull(runRef);
            if (planRevision < 1) throw new IllegalArgumentException("invalid plan revision");
            inputs = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(inputs));
        }
    }

    public record BoundInput(String sourceTask, String output, ResultRef resultRef, String digest) {
        public BoundInput {
            sourceTask = DagPlan.text(sourceTask); output = DagPlan.text(output);
            Objects.requireNonNull(resultRef); digest = DagPlan.text(digest);
        }
    }

    public record Output(ResultRef resultRef, String digest, boolean fulfilled) {
        public Output { Objects.requireNonNull(resultRef); digest = DagPlan.text(digest); }
    }

    /** 执行许可标识已准入的派发权；fence 防止旧派发者在重新领取后继续执行。 */
    public record Permit(long fence, Instant expiresAt) {
        public Permit { if (fence < 1) throw new IllegalArgumentException("invalid permit fence"); Objects.requireNonNull(expiresAt); }
    }

    public record Execution(Binding binding, TaskStatus status, Optional<Permit> permit,
                            boolean resumeRequested, long usedTokens, Map<String, Output> outputs,
                            String phase, Optional<ResultRef> sourceEvent) {
        public Execution {
            Objects.requireNonNull(binding); Objects.requireNonNull(status); Objects.requireNonNull(permit);
            outputs = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(outputs));
            phase = phase == null ? "" : phase;
            Objects.requireNonNull(sourceEvent);
            if (usedTokens < 0) throw new IllegalArgumentException("invalid usage");
            if ((status == TaskStatus.RESERVED || status == TaskStatus.RUNNING) != permit.isPresent()) {
                throw new IllegalArgumentException("running and reserved tasks must hold execution permits");
            }
        }
        public boolean terminal() {
            return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                    || status == TaskStatus.CANCELLED || status == TaskStatus.OUTCOME_UNKNOWN;
        }
    }
}
