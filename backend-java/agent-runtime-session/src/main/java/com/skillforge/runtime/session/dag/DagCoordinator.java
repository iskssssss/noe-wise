package com.skillforge.runtime.session.dag;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import com.skillforge.runtime.session.SessionDelegationEngine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 只推进已获准的分工，不执行模型、不保存成员内部状态、不按展示顺序等待整批完成。 */
public final class DagCoordinator {
    private final DagWorkPort store;
    private final Access access;
    private final Duration permitDuration;

    /** 授权及结果真实性由宿主/执行适配器提供，模型和客户端不能提供实现。 */
    public interface Access {
        void requireExecution(DagWork work, DagPlan.Task task, Map<String, DagWork.BoundInput> inputs, boolean resume);
        void requireResult(DagWork work, DagWork.Execution execution, Completion completion);
        void requireResume(DagWork work, DagWork.Execution execution, ResultRef controlResult);
        void requireControl(DagWork work, Control control, String commandId);
        void requireClosure(DagWork work, ResultRef answerOrClosure);
    }

    public enum Control { REVISE, APPROVE, CANCEL }

    /** 准入被当前权限拒绝只阻塞相应分支，不能阻断其他就绪任务。 */
    public static final class AdmissionDenied extends RuntimeException {
        public AdmissionDenied(String reason) { super(reason); }
    }

    public record Completion(String eventId, String taskId, RunRef runRef, long fence,
                             DagWork.TaskStatus status, long usedTokens, Map<String, DagWork.Output> outputs,
                             String phase, ResultRef sourceEvent) {
        public Completion {
            Objects.requireNonNull(eventId); Objects.requireNonNull(taskId); Objects.requireNonNull(runRef);
            Objects.requireNonNull(status); outputs = Map.copyOf(outputs); Objects.requireNonNull(sourceEvent);
            if (usedTokens < 0 || fence < 1 || status == DagWork.TaskStatus.RESERVED) throw new IllegalArgumentException("invalid task observation");
        }
    }

    public DagCoordinator(DagWorkPort store, Access access, Duration permitDuration) {
        this.store = Objects.requireNonNull(store); this.access = Objects.requireNonNull(access);
        this.permitDuration = Objects.requireNonNull(permitDuration);
        if (permitDuration.isNegative() || permitDuration.isZero()) throw new IllegalArgumentException("positive permit duration required");
    }

    /** 领取所有满足依赖、授权和资源互斥的就绪任务；只有 CAS 成功后派发器才可读取 RESERVED 记录。 */
    public DagWorkPort.Commit advance(DagWork.Key key, String commandId, Instant now) {
        DagWork work = require(key);
        if (work.status() != DagWork.Status.ACTIVE || work.plan() == null) return DagWorkPort.Commit.CONFLICT;
        Map<String, DagWork.Execution> executions = new LinkedHashMap<>(work.executions());
        List<DagWorkPort.Event> events = new ArrayList<>();
        Set<String> resources = heldResources(executions);
        // 已受理任务恢复使用原绑定，不能让新的分工审批阻止旧执行恢复。
        for (var execution : work.executions().values().stream().sorted(java.util.Comparator.comparing(e -> e.binding().admissionId())).toList()) {
            if (execution.binding().task().executorType() == DagPlan.ExecutorType.PRIMARY) continue;
            if (execution.status() != DagWork.TaskStatus.WAITING || !execution.resumeRequested()) continue;
            var binding = execution.binding();
            if (!java.util.Collections.disjoint(resources, binding.task().exclusiveResources())) continue;
            try { access.requireExecution(work, binding.task(), binding.inputs(), true); }
            catch (AdmissionDenied denied) { continue; }
            executions.put(binding.task().id(), new DagWork.Execution(binding, DagWork.TaskStatus.RESERVED,
                    Optional.of(new DagWork.Permit(work.revision() + 1, now.plus(permitDuration))), false,
                    execution.usedTokens(), execution.outputs(), "等待恢复执行", execution.sourceEvent()));
            resources.addAll(binding.task().exclusiveResources());
            events.add(new DagWorkPort.Event("task.resume_reserved", binding.task().id(), "原执行重新取得执行许可"));
        }

        boolean approved = !work.plan().approvalRequired() || work.approvedRevision() == work.plan().revision();
        if (approved) for (var task : work.plan().effectiveTasks().stream().sorted(java.util.Comparator.comparing(DagPlan.Task::id)).toList()) {
            if (task.executorType() == DagPlan.ExecutorType.PRIMARY) continue;
            if (executions.containsKey(task.id())) continue;
            if (task.cancelled()) continue;
            if (!java.util.Collections.disjoint(resources, task.exclusiveResources())) continue;
            Optional<Map<String, DagWork.BoundInput>> inputs = bind(task, executions);
            if (inputs.isEmpty()) continue;
            try { access.requireExecution(work, task, inputs.orElseThrow(), false); }
            catch (AdmissionDenied denied) { continue; }
            String admissionId = "task:" + UUID.nameUUIDFromBytes((key.owner().sessionRef().tenantId().value()
                    + "/" + key.owner().sessionRef().sessionId() + "/" + key.rootInputId().value() + "/" + task.id()).getBytes(StandardCharsets.UTF_8));
            var binding = new DagWork.Binding(admissionId, work.plan().revision(), task,
                    SessionDelegationEngine.childRun(work.request().controlRun(), "dag:" + admissionId), inputs.orElseThrow());
            executions.put(task.id(), new DagWork.Execution(binding, DagWork.TaskStatus.RESERVED,
                    Optional.of(new DagWork.Permit(work.revision() + 1, now.plus(permitDuration))), false,
                    0, Map.of(), "等待执行资源派发", Optional.empty()));
            resources.addAll(task.exclusiveResources());
            events.add(new DagWorkPort.Event("task.admitted", task.id(), "输入已绑定，执行许可已取得"));
        }
        if (events.isEmpty()) return DagWorkPort.Commit.CONFLICT;
        return store.commit(key, work.revision(), commandId, fingerprint("advance", work.revision()),
                copy(work, work.plan(), work.approvedRevision(), executions, work.spentTokens(), work.consumedEvents(), work.status()), events);
    }

    /** 原 Run 内开始主步骤；审批、冻结输入及单活动步骤与工作版本 CAS 原子受理。 */
    public DagWorkPort.Commit startPrimary(DagWork.Key key, String taskId, String commandId, Instant now) {
        DagWork work = require(key);
        if (work.status() != DagWork.Status.ACTIVE || work.plan() == null) return DagWorkPort.Commit.CONFLICT;
        var task = work.plan().task(taskId);
        if (task.executorType() != DagPlan.ExecutorType.PRIMARY || !task.agentId().equals(work.coordinator()))
            throw new IllegalArgumentException("primary step requires coordinator executor");
        String commandFingerprint = fingerprint("start_primary", taskId);
        if (work.consumedEvents().containsKey(commandId)) {
            if (!commandFingerprint.equals(work.consumedEvents().get(commandId)))
                throw new IllegalArgumentException("primary start command conflict");
            return DagWorkPort.Commit.REPLAYED;
        }
        if (work.executions().containsKey(taskId) || task.cancelled()
                || !work.plan().effectiveTasks().contains(task)
                || work.plan().approvalRequired() && work.approvedRevision() != work.plan().revision()
                || work.executions().values().stream().anyMatch(e -> !e.terminal()
                    && e.binding().task().executorType() == DagPlan.ExecutorType.PRIMARY)
                || !java.util.Collections.disjoint(heldResources(work.executions()), task.exclusiveResources()))
            return DagWorkPort.Commit.CONFLICT;
        var inputs = bind(task, work.executions());
        if (inputs.isEmpty()) return DagWorkPort.Commit.CONFLICT;
        access.requireExecution(work, task, inputs.orElseThrow(), false);
        String admissionId = "task:" + UUID.nameUUIDFromBytes((key.owner().sessionRef().tenantId().value()
                + "/" + key.owner().sessionRef().sessionId() + "/" + key.rootInputId().value() + "/" + taskId)
                .getBytes(StandardCharsets.UTF_8));
        var binding = new DagWork.Binding(admissionId, work.plan().revision(), task,
                work.request().controlRun(), inputs.orElseThrow());
        var executions = new LinkedHashMap<>(work.executions());
        executions.put(taskId, new DagWork.Execution(binding, DagWork.TaskStatus.RUNNING,
                Optional.of(new DagWork.Permit(work.revision() + 1, now.plus(permitDuration))), false,
                0, Map.of(), "主 Agent 正在执行", Optional.empty()));
        var consumed = new LinkedHashMap<>(work.consumedEvents());
        consumed.put(commandId, commandFingerprint);
        return store.commit(key, work.revision(), commandId, commandFingerprint,
                copy(work, work.plan(), work.approvedRevision(), executions, work.spentTokens(), consumed, work.status()),
                List.of(new DagWorkPort.Event("task.primary_started", taskId, "主步骤输入与原 Run 已冻结")));
    }

    /** 主步骤结果仍须经宿主真实引用校验；成员终态不能由本入口更改。 */
    public DagWorkPort.Commit finishPrimary(DagWork.Key key, Completion completion) {
        var work = require(key);
        if (execution(work, completion.taskId()).binding().task().executorType() != DagPlan.ExecutorType.PRIMARY)
            throw new IllegalArgumentException("finish primary cannot complete a member step");
        if (completion.status() == DagWork.TaskStatus.RUNNING || completion.status() == DagWork.TaskStatus.WAITING)
            throw new IllegalArgumentException("finish primary requires a terminal outcome");
        return applyObservation(work, completion);
    }

    /** 多成员完成可并发提交，CAS 只串行化协调投影；失败调用者重新读取原事件后重试。 */
    public DagWorkPort.Commit observe(DagWork.Key key, Completion completion) {
        DagWork work = require(key);
        if (execution(work, completion.taskId()).binding().task().executorType() != DagPlan.ExecutorType.MEMBER)
            throw new IllegalArgumentException("primary observations require finishPrimary");
        return applyObservation(work, completion);
    }

    private DagWorkPort.Commit applyObservation(DagWork work, Completion completion) {
        var key = work.key();
        var execution = execution(work, completion.taskId());
        if (!execution.binding().runRef().equals(completion.runRef())) throw new IllegalArgumentException("completion run mismatch");
        access.requireResult(work, execution, completion);
        String observedFingerprint = fingerprint(completion);
        String consumedFingerprint = work.consumedEvents().get(completion.eventId());
        if (consumedFingerprint != null) {
            if (!consumedFingerprint.equals(observedFingerprint)) throw new IllegalArgumentException("completion event conflict");
            return DagWorkPort.Commit.REPLAYED;
        }
        // 已终态的迟到事件可由执行账本登记，但不能重新激活或覆盖原工作投影。
        if (execution.terminal()) return DagWorkPort.Commit.CONFLICT;
        boolean terminalObservation = completion.status() != DagWork.TaskStatus.RUNNING
                && completion.status() != DagWork.TaskStatus.WAITING;
        if (execution.permit().isPresent() ? execution.permit().orElseThrow().fence() != completion.fence()
                : execution.status() != DagWork.TaskStatus.WAITING || !terminalObservation) {
            throw new IllegalStateException("stale task execution fence");
        }
        if (completion.usedTokens() < execution.usedTokens()) throw new IllegalArgumentException("usage must be cumulative");
        if (!execution.binding().task().outputs().containsAll(completion.outputs().keySet())) throw new IllegalArgumentException("undeclared task output");
        if (completion.status() == DagWork.TaskStatus.COMPLETED
                && !completion.outputs().keySet().containsAll(execution.binding().task().outputs())) {
            throw new IllegalArgumentException("completed task has missing declared outputs");
        }
        var permit = completion.status() == DagWork.TaskStatus.RUNNING ? execution.permit() : Optional.<DagWork.Permit>empty();
        var nextExecution = new DagWork.Execution(execution.binding(), completion.status(), permit, false,
                completion.usedTokens(), completion.outputs(), completion.phase(), Optional.of(completion.sourceEvent()));
        Map<String, DagWork.Execution> executions = new LinkedHashMap<>(work.executions());
        executions.put(completion.taskId(), nextExecution);
        var consumed = new LinkedHashMap<>(work.consumedEvents());
        consumed.put(completion.eventId(), observedFingerprint);
        long spent = Math.addExact(work.spentTokens(), completion.usedTokens() - execution.usedTokens());
        return store.commit(key, work.revision(), completion.eventId(), observedFingerprint,
                copy(work, work.plan(), work.approvedRevision(), executions, spent, consumed, work.status()),
                List.of(new DagWorkPort.Event("task.observed", completion.taskId(), completion.status().name())));
    }

    public DagWorkPort.Commit requestResume(DagWork.Key key, String taskId, String commandId, ResultRef controlResult) {
        DagWork work = require(key); var execution = execution(work, taskId);
        if (work.status() != DagWork.Status.ACTIVE || execution.status() != DagWork.TaskStatus.WAITING) return DagWorkPort.Commit.CONFLICT;
        access.requireResume(work, execution, controlResult);
        var executions = new LinkedHashMap<>(work.executions());
        executions.put(taskId, new DagWork.Execution(execution.binding(), execution.status(), Optional.empty(), true,
                execution.usedTokens(), execution.outputs(), "等待恢复容量", execution.sourceEvent()));
        return store.commit(key, work.revision(), commandId, fingerprint("resume", taskId, controlResult),
                copy(work, work.plan(), work.approvedRevision(), executions, work.spentTokens(), work.consumedEvents(), work.status()),
                List.of(new DagWorkPort.Event("task.resume_requested", taskId, "精确控制响应已受理")));
    }

    /** 撤回尚未创建真实 Run 的预留；持久层在受理共用的锁内验证尚未派发。 */
    public DagWorkPort.Commit stopBeforeDispatch(DagWork.Key key, String taskId, String commandId,
            DagWork.TaskStatus terminal, String reason) {
        if (terminal != DagWork.TaskStatus.CANCELLED && terminal != DagWork.TaskStatus.FAILED)
            throw new IllegalArgumentException("invalid undispatched terminal state");
        DagWork work = require(key);
        var execution = execution(work, taskId);
        if (execution.terminal()) return DagWorkPort.Commit.REPLAYED;
        if (execution.status() != DagWork.TaskStatus.RESERVED || execution.sourceEvent().isPresent())
            return DagWorkPort.Commit.CONFLICT;
        var executions = new LinkedHashMap<>(work.executions());
        executions.put(taskId, new DagWork.Execution(execution.binding(), terminal, Optional.empty(), false,
                0, Map.of(), reason, Optional.of(new ResultRef("undispatched:" + execution.binding().admissionId()))));
        return store.commit(key, work.revision(), commandId, fingerprint("not_dispatched", taskId, terminal),
                copy(work, work.plan(), work.approvedRevision(), executions, work.spentTokens(), work.consumedEvents(), work.status()),
                List.of(new DagWorkPort.Event("task.not_dispatched", taskId, reason)));
    }

    /** 新分工只能调整未准入任务，保持已启动定义及其结果引用。 */
    public DagWorkPort.Commit revise(DagWork.Key key, String commandId, String requestFingerprint, long expectedPlanRevision, DagPlan plan) {
        DagWork work = require(key);
        access.requireControl(work, Control.REVISE, commandId);
        var prior = work.consumedEvents().get(commandId);
        if (prior != null) {
            if (!prior.equals(requestFingerprint)) throw new IllegalArgumentException("plan_command_conflict");
            return DagWorkPort.Commit.REPLAYED;
        }
        if (work.status() != DagWork.Status.ACTIVE || (work.plan() == null ? 0 : work.plan().revision()) != expectedPlanRevision
                || plan.revision() != expectedPlanRevision + 1) return DagWorkPort.Commit.CONFLICT;
        if (work.plan() != null && work.plan().approvalRequired() && !plan.approvalRequired())
            throw new IllegalArgumentException("plan approval requirement cannot be removed");
        for (var execution : work.executions().values()) {
            if (!plan.task(execution.binding().task().id()).equals(execution.binding().task())) {
                throw new IllegalArgumentException("admitted task definition is immutable");
            }
        }
        for (var task : plan.tasks()) {
            if (task.replacesTaskId().isEmpty()) continue;
            var source = work.executions().get(task.replacesTaskId());
            if (source != null && (!source.terminal() || source.status() == DagWork.TaskStatus.OUTCOME_UNKNOWN))
                throw new IllegalArgumentException("replacement requires a settled source execution");
        }
        var consumed = new HashMap<>(work.consumedEvents());
        consumed.put(commandId, requestFingerprint);
        return store.commit(key, work.revision(), commandId, requestFingerprint,
                copy(work, plan, work.approvedRevision(), work.executions(), work.spentTokens(), consumed, work.status()),
                List.of(new DagWorkPort.Event("work.plan_revised", "", "分工版本 " + plan.revision())));
    }

    public DagWorkPort.Commit approve(DagWork.Key key, String commandId, long planRevision) {
        DagWork work = require(key);
        access.requireControl(work, Control.APPROVE, commandId);
        if (work.status() != DagWork.Status.ACTIVE || work.plan() == null || work.plan().revision() != planRevision) return DagWorkPort.Commit.CONFLICT;
        return store.commit(key, work.revision(), commandId, fingerprint("approve", planRevision),
                copy(work, work.plan(), planRevision, work.executions(), work.spentTokens(), work.consumedEvents(), work.status()),
                List.of(new DagWorkPort.Event("work.plan_approved", "", "批准具体分工版本")));
    }

    /** 请求取消仅关闭新准入；执行的取消完成由真实任务终态事件收敛。 */
    public DagWorkPort.Commit cancel(DagWork.Key key, String commandId) {
        DagWork work = require(key);
        access.requireControl(work, Control.CANCEL, commandId);
        if (work.status() != DagWork.Status.ACTIVE) return DagWorkPort.Commit.CONFLICT;
        return store.commit(key, work.revision(), commandId, fingerprint("cancel"),
                copy(work, work.plan(), work.approvedRevision(), work.executions(), work.spentTokens(), work.consumedEvents(), DagWork.Status.CANCELLING),
                List.of(new DagWorkPort.Event("work.cancel_requested", "", "取消传播待执行收敛")));
    }

    /** 显式结案要求执行全部收尾；没有 running 行不能代替此事件。 */
    public DagWorkPort.Commit close(DagWork.Key key, String commandId, long expectedRevision, ResultRef answerOrClosure) {
        DagWork work = require(key); Objects.requireNonNull(answerOrClosure);
        if (work.revision() != expectedRevision) return DagWorkPort.Commit.CONFLICT;
        access.requireClosure(work, answerOrClosure);
        if (work.status() == DagWork.Status.COMPLETED || work.status() == DagWork.Status.CANCELLED
                || work.executions().values().stream().anyMatch(e -> !e.terminal())) return DagWorkPort.Commit.CONFLICT;
        DagWork.Status status = work.status() == DagWork.Status.CANCELLING ? DagWork.Status.CANCELLED : DagWork.Status.COMPLETED;
        return store.commit(key, work.revision(), commandId, fingerprint("close", answerOrClosure),
                copy(work, work.plan(), work.approvedRevision(), work.executions(), work.spentTokens(), work.consumedEvents(), status),
                List.of(new DagWorkPort.Event("work.closed", "", answerOrClosure.value())));
    }

    private Optional<Map<String, DagWork.BoundInput>> bind(DagPlan.Task task, Map<String, DagWork.Execution> executions) {
        Map<String, DagWork.BoundInput> inputs = new HashMap<>();
        for (var input : task.inputs()) {
            var source = executions.get(input.sourceTask());
            if (source == null || source.status() != DagWork.TaskStatus.COMPLETED) return Optional.empty();
            var output = source.outputs().get(input.output());
            if (output == null || !output.fulfilled()) return Optional.empty();
            inputs.put(input.name(), new DagWork.BoundInput(input.sourceTask(), input.output(), output.resultRef(), output.digest()));
        }
        return Optional.of(Map.copyOf(inputs));
    }

    private Set<String> heldResources(Map<String, DagWork.Execution> executions) {
        Set<String> held = new HashSet<>();
        for (var execution : executions.values()) {
            if (execution.permit().isPresent() || execution.status() == DagWork.TaskStatus.OUTCOME_UNKNOWN) {
                held.addAll(execution.binding().task().exclusiveResources());
            }
        }
        return held;
    }

    private DagWork require(DagWork.Key key) { return store.read(key).orElseThrow(() -> new IllegalArgumentException("work not found")); }
    private DagWork.Execution execution(DagWork work, String id) {
        var value = work.executions().get(id); if (value == null) throw new IllegalArgumentException("task not admitted"); return value;
    }
    private DagWork copy(DagWork work, DagPlan plan, long approval, Map<String, DagWork.Execution> executions,
                         long spent, Map<String, String> events, DagWork.Status status) {
        return new DagWork(work.key(), work.coordinator(), work.revision() + 1, status, plan, approval, spent, executions, events, work.request());
    }
    private String fingerprint(Object... values) {
        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(values);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("cannot fingerprint DAG command", invalid);
        }
    }
}
