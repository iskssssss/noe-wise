package com.skillforge.institution.runtime;

import java.util.Locale;

import com.skillforge.institution.artifact.DeliverableContract.GoalCompletion;
import com.skillforge.institution.artifact.DeliverableContract.GoalCompletionIntent;
import com.skillforge.institution.artifact.DeliverableContract.Publication;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.SessionGoal;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 宿主 Goal 续行驱动。
 *
 * <p>Goal 快照由 SessionLog 持久化；本类只在用户 create/resume 后保存进程内启用登记。
 * 进程重启后登记为空，因此不会凭持久 ACTIVE 状态自行恢复。每个 owner 的登记同时充当本机
 * pause/launch 同步槽，跨进程竞争仍由 {@link SessionGoal} 的 revision CAS 裁决。</p>
 */
@Component
public final class AgentRuntimeGoalDriver {
    private static final String EXPLICIT_RESUME = "explicit_resume";
    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntimeGoalDriver.class);
    private final SessionGoal goals;
    private final SessionLogPort log;
    private final Clock clock;
    private final RoundPrompt prompt;
    private final com.skillforge.runtime.api.port.ExecutionBudgetPort budgets;
    private final ConcurrentMap<SessionOwner, Registration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentMap<SessionOwner, Long> completionWarnings = new ConcurrentHashMap<>();

    public AgentRuntimeGoalDriver(SessionLogPort log, Clock clock, RoundPrompt prompt,
            com.skillforge.runtime.api.port.ExecutionBudgetPort budgets) {
        this.log = Objects.requireNonNull(log);
        this.goals = new SessionGoal(log);
        this.clock = Objects.requireNonNull(clock);
        this.prompt = Objects.requireNonNull(prompt);
        this.budgets = Objects.requireNonNull(budgets);
    }

    /** 读取持久快照和本进程续行开关；读取不会恢复登记。 */
    public Optional<Status> read(SessionOwner owner) {
        Objects.requireNonNull(owner);
        return goals.read(owner).map(goal -> new Status(goal, enabled(owner, goal), false, null));
    }

    /** 冻结时只验证并捕获原 revision，不提前改变 Goal 权威状态。 */
    public GoalCompletionIntent captureCompletion(RunIdentity run, long expectedRevision) {
        var goal = goals.read(run.owner()).orElseThrow(() -> new SessionGoal.Rejected(SessionGoal.Reason.NOT_FOUND));
        if (expectedRevision < 1 || goal.revision() != expectedRevision)
            throw new SessionGoal.Rejected(SessionGoal.Reason.REVISION_CONFLICT);
        if (goal.state() != SessionGoal.State.ACTIVE || goal.pendingRoundInputId().isPresent()
                || !goal.lastRunId().equals(Optional.of(run.runRef())))
            throw new SessionGoal.Rejected(SessionGoal.Reason.STATE_CONFLICT);
        String operationId;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            operationId = "goal-output-completion:" + HexFormat.of().formatHex(digest.digest(
                    (goal.goalId() + "\n" + run.runRef().runId()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new GoalCompletionIntent(goal.goalId(), expectedRevision, operationId);
    }

    /** 发布事务已提交后才调用。恢复结算从不创建登记或提交新的模型轮次。 */
    public CompletionSettlement settleCompletion(GoalCompletion completion) {
        Objects.requireNonNull(completion);
        Registration slot = registrations.get(completion.run().owner());
        if (slot == null) return settle(completion, null);
        synchronized (slot) { return settle(completion, slot); }
    }

    private CompletionSettlement settle(GoalCompletion completion, Registration slot) {
        var run = completion.run();
        var intent = completion.intent();
        var receipt = goals.updateReceipt(run, intent.operationId(), intent.expectedRevision());
        if (receipt.isPresent()) {
            if (!receipt.orElseThrow().goalId().equals(intent.goalId())) throw new SessionGoal.Rejected(SessionGoal.Reason.OPERATION_CONFLICT);
            disableSettledRegistration(run.owner(), intent, slot);
            return CompletionSettlement.SETTLED;
        }
        var current = goals.read(run.owner());
        if (current.isEmpty() || !matchesIntent(current.orElseThrow(), run, intent))
            return CompletionSettlement.SUPERSEDED;
        if (!terminal(completion.runStatus())) return CompletionSettlement.WAITING;
        String publication = completion.publication().status();
        SessionGoal.State state;
        String reason;
        if (completion.runStatus() == RunStatus.COMPLETED && "published".equals(publication)) {
            state = SessionGoal.State.COMPLETE;
            reason = null;
        } else if (Set.of("failed", "cancelled").contains(publication)) {
            state = SessionGoal.State.BLOCKED;
            reason = completion.publication().reason();
            if (reason == null || reason.isBlank())
                reason = "run_" + completion.runStatus().name().toLowerCase(java.util.Locale.ROOT);
            if (reason.length() > 512) reason = reason.substring(0, 512);
        } else return CompletionSettlement.WAITING;
        try {
            goals.update(run, intent.operationId(), intent.expectedRevision(), state, reason, clock.instant());
            disableSettledRegistration(run.owner(), intent, slot);
            return CompletionSettlement.SETTLED;
        } catch (SessionGoal.Rejected rejected) {
            if (!Set.of(SessionGoal.Reason.REVISION_CONFLICT, SessionGoal.Reason.STATE_CONFLICT, SessionGoal.Reason.NOT_FOUND)
                    .contains(rejected.reason())) throw rejected;
            // CAS 竞争赢家可能就是同一持久命令，仍应先重放回执再判作失效。
            if (goals.updateReceipt(run, intent.operationId(), intent.expectedRevision()).isPresent()) {
                disableSettledRegistration(run.owner(), intent, slot);
                return CompletionSettlement.SETTLED;
            }
            return CompletionSettlement.SUPERSEDED;
        }
    }

    private static boolean matchesIntent(SessionGoal.Snapshot goal, RunIdentity run, GoalCompletionIntent intent) {
        return goal.goalId().equals(intent.goalId()) && goal.revision() == intent.expectedRevision()
                && goal.state() == SessionGoal.State.ACTIVE && goal.pendingRoundInputId().isEmpty()
                && goal.lastRunId().equals(Optional.of(run.runRef()));
    }

    private void disableSettledRegistration(SessionOwner owner, GoalCompletionIntent intent, Registration slot) {
        if (slot != null && goals.read(owner).filter(goal -> goal.goalId().equals(intent.goalId())).isPresent())
            disableScheduling(slot);
    }

    public enum CompletionSettlement { WAITING, SETTLED, SUPERSEDED }

    /** 初始用户 Run 已由 Gateway 接受后，在同一 owner 槽创建持久 Goal 并启用后续轮次。 */
    public Status create(RunIdentity run, String commandId, String objective, int maxRounds,
                         Callback callback) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(callback);
        var candidate = new Registration(callback, run.agentId(), false);
        Registration slot = registrations.putIfAbsent(run.owner(), candidate);
        boolean installed = slot == null;
        if (installed) slot = candidate;
        synchronized (slot) {
            try {
                Optional<SessionGoal.Snapshot> before = goals.read(run.owner());
                SessionGoal.Snapshot created = goals.create(run, commandId, objective, maxRounds, clock.instant());
                SessionGoal.Snapshot live = goals.read(run.owner()).orElse(created);
                boolean replayed = before.filter(value -> value.goalId().equals(created.goalId())).isPresent();
                if (replayed || !live.equals(created)) {
                    if (installed) registrations.remove(run.owner(), slot);
                    return new Status(live, enabled(run.owner(), live), false, null);
                }
                if (live.state() == SessionGoal.State.ACTIVE) {
                    slot.callback = callback;
                    slot.executionRun = run.runRef();
                    slot.resumedPredecessor = null;
                    slot.enabled = true;
                    callback.wake(run.runRef());
                    return new Status(live, true, false, null);
                }
                if (installed) registrations.remove(run.owner(), slot);
                return new Status(live, enabled(run.owner(), live), false, null);
            } catch (RuntimeException failure) {
                if (installed) registrations.remove(run.owner(), slot);
                throw failure;
            }
        }
    }

    /** 初轮准入与create共用原owner同步槽；Worker在create提交前不会越过Controls边界。 */
    public Status createFromAdmission(SessionOwner owner, AgentId agentId, String commandId, String objective,
            int maxRounds, Callback callback, java.util.function.Supplier<RunIdentity> admission) {
        Objects.requireNonNull(owner); Objects.requireNonNull(agentId); Objects.requireNonNull(admission);
        var candidate = new Registration(callback, agentId, false);
        Registration prior = registrations.putIfAbsent(owner, candidate);
        Registration slot = prior == null ? candidate : prior;
        if (!slot.agentId.equals(agentId)) throw failure("goal_agent_mismatch");
        synchronized (slot) {
            try {
                var run = Objects.requireNonNull(admission.get());
                if (!run.owner().equals(owner) || !run.agentId().equals(agentId)) throw failure("goal_run_owner_mismatch");
                return create(run, commandId, objective, maxRounds, callback);
            } finally {
                if (prior == null && slot.executionRun == null) registrations.remove(owner, slot);
            }
        }
    }

    /** 用户暂停先关闭本机续行，再持久暂停并取消当前非终态 Run。 */
    public Status pause(SessionOwner owner, AgentId agentId, long expectedRevision, String commandId,
                        InputId inputId, TraceEnvelope trace, String reason, Callback callback) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(callback);
        Registration current = registrations.get(owner);
        Registration slot = current == null ? new Registration(callback, agentId, false) : current;
        if (!slot.agentId.equals(agentId)) throw failure("goal_agent_mismatch");
        boolean restore = current != null && current.enabled;
        slot.enabled = false;
        synchronized (slot) {
            try {
                SessionGoal.Snapshot paused = goals.pause(owner, agentId, expectedRevision, commandId,
                        inputId, trace, reason, clock.instant());
                SessionGoal.Snapshot live = goals.read(owner).orElse(paused);
                if (!live.goalId().equals(paused.goalId()) || live.revision() != paused.revision()) {
                    if (restore && registrations.get(owner) == slot) slot.enabled = true;
                    return new Status(live, enabled(owner, live), false, null);
                }
                slot.executionRun = null;
                registrations.remove(owner, slot);
                cancelNonTerminal(callback, paused.lastRunId().orElseThrow(), owner);
                return new Status(paused, false, false, null);
            } catch (RuntimeException failure) {
                if (restore && registrations.get(owner) == slot) slot.enabled = true;
                throw failure;
            }
        }
    }

    /** 用户显式恢复；PAUSED/ACTIVE 都由领域以一次 revision CAS 恢复并清除旧 reservation。 */
    public Status resume(SessionOwner owner, AgentId agentId, long expectedRevision, String commandId,
                         InputId inputId, TraceEnvelope trace, Callback callback) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(callback);
        var candidate = new Registration(callback, agentId, false);
        Registration slot = registrations.putIfAbsent(owner, candidate);
        boolean installed = slot == null;
        if (installed) slot = candidate;
        synchronized (slot) {
            try {
                SessionGoal.Snapshot before = goals.read(owner)
                        .orElseThrow(() -> new SessionGoal.Rejected(SessionGoal.Reason.NOT_FOUND));
                if (!slot.agentId.equals(agentId)) throw failure("goal_agent_mismatch");
                SessionGoal.Snapshot resumed = goals.resume(owner, agentId, expectedRevision, commandId, inputId, trace,
                        EXPLICIT_RESUME, clock.instant());
                SessionGoal.Snapshot live = goals.read(owner).orElse(resumed);
                boolean replayed = before.goalId().equals(resumed.goalId())
                        && resumed.revision() <= before.revision();
                boolean fresh = before.goalId().equals(resumed.goalId())
                        && resumed.revision() > before.revision()
                        && resumed.revision() - before.revision() == 1
                        && live.equals(resumed)
                        && live.state() == SessionGoal.State.ACTIVE;
                if (replayed || !fresh) {
                    if (installed) registrations.remove(owner, slot);
                    return new Status(live, enabled(owner, live), false, null);
                }
                slot.callback = callback;
                slot.executionRun = null;
                slot.resumedPredecessor = live.lastRunId().orElseThrow();
                slot.enabled = true;
                var existing = Objects.requireNonNull(callback.latestRun(slot.resumedPredecessor));
                if (existing.isPresent() && !terminal(existing.orElseThrow().status())) {
                    requireRun(owner, slot.resumedPredecessor, existing.orElseThrow());
                    slot.executionRun = slot.resumedPredecessor;
                    slot.resumedPredecessor = null;
                    callback.wake(slot.executionRun);
                }
                return new Status(live, true, false, null);
            } catch (RuntimeException failure) {
                if (installed) registrations.remove(owner, slot);
                throw failure;
            }
        }
    }

    /** update_goal 的同包入口；终态写入与本机 launch 使用同一登记锁，且不会启用续行。 */
    Status update(RunIdentity run, String operationId, long expectedRevision,
                  SessionGoal.State state, String reason) {
        Objects.requireNonNull(run);
        Registration slot = registrations.get(run.owner());
        if (slot == null) {
            var updated = goals.update(run, operationId, expectedRevision, state, reason, clock.instant());
            return new Status(updated, false, false, null);
        }
        synchronized (slot) {
            var updated = goals.update(run, operationId, expectedRevision, state, reason, clock.instant());
            var live = goals.read(run.owner()).orElse(updated);
            if (live.goalId().equals(updated.goalId())) {
                slot.enabled = false;
                // 当前已接受 Goal Round 仍可完成最后响应；这里只关闭后续排队。
            }
            return new Status(updated, false, false, null);
        }
    }

    /** 普通用户输入到达时只让出后续排队；当前已接受 Goal Round 仍可收尾。 */
    public void yieldToUser(SessionOwner owner) {
        Objects.requireNonNull(owner);
        Registration registration = registrations.get(owner);
        if (registration == null) return;
        synchronized (registration) {
            if (registrations.get(owner) == registration) registration.enabled = false;
        }
    }

    /** Runtime pre-step 只放行本进程实际提交的 Goal Round；重启不会从日志恢复许可。 */
    public boolean executionAllowed(RunIdentity run) {
        Objects.requireNonNull(run);
        Registration registration = registrations.get(run.owner());
        if (registration == null) return false;
        synchronized (registration) {
            return registrations.get(run.owner()) == registration
                    && run.runRef().equals(registration.executionRun);
        }
    }

    /** 仅Controls建立时可等待原owner准入槽；此处不持ExecutionStore事务或Run行锁。 */
    void requireExecution(RunIdentity run) {
        if (!goalInput(run)) return;
        Registration slot = registrations.get(run.owner());
        if (slot == null) throw new com.skillforge.runtime.session.spi.ExecutionStore.Rejection("goal_round_execution_disabled");
        synchronized (slot) {
            if (!dispatchAllowed(run))
                throw new com.skillforge.runtime.session.spi.ExecutionStore.Rejection("goal_round_execution_disabled");
        }
    }

    /** 当前派发事务的真实业务守卫；不在持Run锁时等待pause/launch同步槽，避免锁顺序反转。 */
    boolean dispatchAllowed(RunIdentity run) {
        Objects.requireNonNull(run);
        if (!goalInput(run)) return true;
        Registration slot = registrations.get(run.owner());
        if (slot == null || !run.runRef().equals(slot.executionRun) || registrations.get(run.owner()) != slot) return false;
        return goals.roundAdmission(run) == SessionGoal.RoundAdmission.ACCEPTED
                && run.runRef().equals(slot.executionRun) && registrations.get(run.owner()) == slot;
    }

    /** 当前Goal事实复用Store借用的同一SQL事务，普通输入不参与日志读取。 */
    boolean dispatchAllowed(org.apache.ibatis.session.SqlSession session, RunIdentity run) {
        if (!goalInput(run)) return true;
        if (!(log instanceof com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort jdbc))
            throw failure("goal_transactional_log_required");
        return jdbc.participate(session, run.runRef().tenantId(), () -> dispatchAllowed(run));
    }

    private static boolean goalInput(RunIdentity run) {
        String input = run.causationInputId().value();
        return input.startsWith("goal-start:") || input.startsWith("goal-round:");
    }

    /** Spring 调度每秒检查一次；单个 owner 失败只关闭该登记，不中断其他 Goal。 */
    @Scheduled(fixedDelay = 1_000L)
    void driveEnabledGoals() {
        for (Map.Entry<SessionOwner, Registration> entry : registrations.entrySet()) {
            try {
                drive(entry.getKey(), entry.getValue());
            } catch (RuntimeException ignored) {
                // 单 owner 的存储或回调异常不能停止调度线程，也不能影响其他 Goal。
                synchronized (entry.getValue()) {
                    disableHard(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void drive(SessionOwner owner, Registration registration) {
        synchronized (registration) {
            if (!current(owner, registration)) return;
            SessionGoal.Snapshot goal = null;
            try {
                goal = goals.read(owner).orElseThrow(() -> new SessionGoal.Rejected(SessionGoal.Reason.NOT_FOUND));
                if (goal.state() != SessionGoal.State.ACTIVE) {
                    disableScheduling(registration);
                    return;
                }
                Optional<GoalRun> found = Objects.requireNonNull(
                        registration.callback.latestRun(goal.lastRunId().orElseThrow()));
                // ACCEPTED 与 Run 投影可见之间存在正常窗口；不可见只等待下一次检查。
                if (found.isEmpty()) return;
                GoalRun run = found.orElseThrow();
                requireRun(owner, goal.lastRunId().orElseThrow(), run);
                try {
                    // 先观察真实 Run，再判定完成意图。否则冻结与 Run 收尾可以穿过旧查询窗口，
                    // 导致这里隐式暂停或排入新轮次，把已经接受的完成 revision 作废。
                    if (registration.callback.completionPending(run.runRef())) return;
                } catch (RuntimeException unavailable) {
                    // 无法读完成意图时既不能启动下一轮，也不能因临时存储错误暂停原 Goal。
                    warnCompletionRead(owner, run.runRef(), unavailable);
                    return;
                }
                boolean resumedPredecessor = run.runRef().equals(registration.resumedPredecessor);
                switch (run.status()) {
                    case QUEUED, RUNNING, RESUMING, SUSPENDED -> {
                        if (resumedPredecessor && registration.executionRun == null) {
                            registration.executionRun = run.runRef();
                            registration.resumedPredecessor = null;
                            registration.callback.wake(run.runRef());
                        }
                        return;
                    }
                    case FAILED, CANCELLED, TERMINATED -> {
                        if (!resumedPredecessor) {
                            stop(owner, registration, goal,
                                    "run_" + run.status().name().toLowerCase(Locale.ROOT), Optional.empty());
                            return;
                        }
                    }
                    case COMPLETED -> { }
                }
                if (!registration.callback.idle()) return;
                var resources=budgets.find(run.runRef());
                if(resources.isPresent() && budgets.snapshot(run.runRef()).phase()==com.skillforge.runtime.api.port.ExecutionBudgetPort.Phase.CLOSING) {
                    stop(owner,registration,goal,"budget_closing",Optional.empty());
                    return;
                }
                if (resumedPredecessor) registration.resumedPredecessor = null;
                launch(owner, registration, goal);
            } catch (RuntimeException rejected) {
                if (goal == null) disableHard(owner, registration);
                else stop(owner, registration, goal, "driver_error", Optional.empty());
            }
        }
    }

    private void launch(SessionOwner owner, Registration registration, SessionGoal.Snapshot goal) {
        int round = Math.incrementExact(goal.roundsStarted());
        var inputId = new InputId("goal-round:" + goal.goalId() + ":" + round + ":" + goal.revision());
        var trace = trace(inputId);
        SessionGoal.Snapshot reserved;
        try {
            reserved = goals.reserveRound(owner, registration.agentId, goal.revision(), inputId.value() + ":reserve",
                    inputId, trace, clock.instant());
        } catch (RuntimeException rejected) {
            // reserve 尚未建立本进程所有权；CAS 竞争或不确定失败都只关闭本机，不能暂停赢家。
            disableHard(owner, registration);
            return;
        }
        if (reserved.state() == SessionGoal.State.BLOCKED) {
            disableScheduling(registration);
            return;
        }

        Optional<RunRef> submitted = Optional.empty();
        try {
            String body = prompt.render(reserved, inputId);
            if (body == null || body.isBlank()) throw failure("goal_round_prompt_invalid");
            RunRef expectedRun = new RunRef(owner.sessionRef().tenantId(), "run:" + inputId.value());
            var predecessor=budgets.find(goal.lastRunId().orElseThrow())
                    .orElseThrow(() -> failure("goal_budget_source_missing"));
            budgets.register(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(expectedRun,predecessor.root(),
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY,new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(predecessor.policy().requireEnforced())));
            // submit 返回前 Runtime 可能已经进入 pre-step；先登记确定性 RunRef，再核对 receipt。
            registration.executionRun = expectedRun;
            submitted = Optional.of(expectedRun);
            RoundAdmission receipt = Objects.requireNonNull(registration.callback.submitRound(inputId, body));
            if (!receipt.sessionRef().equals(owner.sessionRef()) || !receipt.inputId().equals(inputId)
                    || !receipt.accepted()
                    || !receipt.runRef().equals(Optional.of(expectedRun)))
                throw failure("goal_round_submission_rejected");
            goals.acceptRound(owner, registration.agentId, reserved.revision(), inputId.value() + ":accept", inputId,
                    submitted.orElseThrow(), trace, clock.instant());
            registration.callback.wake(submitted.orElseThrow());
        } catch (RuntimeException rejected) {
            if (rejected instanceof SessionGoal.Rejected conflict
                    && conflict.reason() == SessionGoal.Reason.REVISION_CONFLICT) {
                disableHard(owner, registration);
                submitted.ifPresent(run -> {
                    try { registration.callback.cancel(run); }
                    catch (RuntimeException ignored) { /* 竞争失败后只尽力取消本机提交。 */ }
                });
            } else {
                stop(owner, registration, reserved, "round_launch_failed", submitted);
            }
        }
    }

    private void warnCompletionRead(SessionOwner owner, RunRef run, RuntimeException failure) {
        long now = clock.millis();
        Long previous = completionWarnings.putIfAbsent(owner, now);
        if (previous == null || now - previous >= 60_000 && completionWarnings.replace(owner, previous, now)) {
            var safe = new RuntimeException(failure.getClass().getName());
            safe.setStackTrace(failure.getStackTrace());
            LOG.warn("Goal 完成意图读取异常 tenant={} executionTag={} failureType={}",
                    run.tenantId().value(), run.runId().hashCode(), failure.getClass().getSimpleName(), safe);
        }
    }

    private void stop(SessionOwner owner, Registration registration, SessionGoal.Snapshot observed,
                      String reason, Optional<RunRef> cancel) {
        disableHard(owner, registration);
        SessionGoal.Snapshot current = goals.read(owner).orElse(observed);
        if (current.state() == SessionGoal.State.ACTIVE
                && current.goalId().equals(observed.goalId())
                && current.revision() == observed.revision()) {
            var input = new InputId("goal-control:" + current.goalId() + ":" + current.revision() + ":" + reason);
            try {
                goals.pause(owner, registration.agentId, current.revision(), input.value(), input,
                        trace(input), reason, clock.instant());
            } catch (RuntimeException ignored) {
                // 另一进程已推进或终结时保持本机停用；不以新 revision 隐式重试。
            }
        }
        cancel.ifPresent(run -> {
            try { registration.callback.cancel(run); }
            catch (RuntimeException ignored) { /* Goal 已停用并尽力持久暂停。 */ }
        });
    }

    private static void cancelNonTerminal(Callback callback, RunRef runRef, SessionOwner owner) {
        Optional<GoalRun> found = Objects.requireNonNull(callback.latestRun(runRef));
        if (found.isEmpty()) return;
        GoalRun run = found.orElseThrow();
        requireRun(owner, runRef, run);
        if (!terminal(run.status())) callback.cancel(runRef);
    }

    private boolean enabled(SessionOwner owner, SessionGoal.Snapshot goal) {
        Registration registration = registrations.get(owner);
        return goal.state() == SessionGoal.State.ACTIVE && registration != null && registration.enabled;
    }

    private boolean current(SessionOwner owner, Registration registration) {
        return registration.enabled && registrations.get(owner) == registration;
    }

    private static void disableScheduling(Registration registration) {
        registration.enabled = false;
    }

    private void disableHard(SessionOwner owner, Registration registration) {
        registration.enabled = false;
        registration.executionRun = null;
        registrations.remove(owner, registration);
    }

    private static void requireRun(SessionOwner owner, RunRef expected, GoalRun run) {
        if (!run.runRef().equals(expected) || !run.owner().equals(owner))
            throw failure("goal_run_owner_mismatch");
    }

    private static boolean terminal(RunStatus state) {
        return state == RunStatus.COMPLETED || state == RunStatus.FAILED
                || state == RunStatus.CANCELLED || state == RunStatus.TERMINATED;
    }

    private static TraceEnvelope trace(InputId inputId) {
        return new TraceEnvelope(inputId.value(), inputId.value() + ":span", inputId, Optional.empty());
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    /** Gateway 持有当前授权与新执行入口，本驱动只消费真实Run状态和准入结果。 */
    public interface Callback {
        Optional<GoalRun> latestRun(RunRef runRef);
        boolean completionPending(RunRef runRef);
        boolean idle();
        RoundAdmission submitRound(InputId inputId, String prompt);
        /** 原create/accept日志提交后才通知唯一Worker，不另建执行队列。 */
        void wake(RunRef runRef);
        void cancel(RunRef runRef);
    }

    /**
     * Goal编排所需的当前执行事实；不复制执行checkpoint或构造旧Outcome。
     * @param owner Store中真实Run的完整Session归属
     * @param runRef 原Run身份，必须与归属同租户
     * @param status 原Run当前状态，不推测尚不可见的执行结果
     */
    public record GoalRun(SessionOwner owner, RunRef runRef, RunStatus status) {
        public GoalRun {
            Objects.requireNonNull(owner); Objects.requireNonNull(runRef); Objects.requireNonNull(status);
            if (!owner.sessionRef().tenantId().equals(runRef.tenantId())) throw failure("goal_run_tenant_mismatch");
        }
    }

    /**
     * 新执行入口对一次Goal续轮的明确准入结果；拒绝不能消耗已预留轮次。
     * @param sessionRef 实际受理入口的Session
     * @param inputId 本次稳定续轮输入身份
     * @param runRef 成功受理的真实Run，拒绝时为空
     * @param accepted 入口已成功受理该Run，而非仅收到提交请求
     */
    public record RoundAdmission(SessionRef sessionRef, InputId inputId, Optional<RunRef> runRef, boolean accepted) {
        public RoundAdmission {
            Objects.requireNonNull(sessionRef); Objects.requireNonNull(inputId); Objects.requireNonNull(runRef);
            if (accepted != runRef.isPresent()) throw failure("goal_round_admission_invalid");
            runRef.ifPresent(value -> {
                if (!value.tenantId().equals(sessionRef.tenantId())) throw failure("goal_round_admission_tenant_mismatch");
            });
        }
    }

    /** Prompt 模块负责指令；驱动只传递持久 Goal 数据和稳定输入身份。 */
    @FunctionalInterface
    public interface RoundPrompt {
        String render(SessionGoal.Snapshot goal, InputId inputId);
    }

    public record Status(SessionGoal.Snapshot goal, boolean continuationEnabled,
                         boolean completionPending, Publication publication) {
        public Status { Objects.requireNonNull(goal); }
    }

    private static final class Registration {
        private Callback callback;
        private final AgentId agentId;
        private volatile boolean enabled;
        private volatile RunRef executionRun;
        private volatile RunRef resumedPredecessor;

        private Registration(Callback callback, AgentId agentId, boolean enabled) {
            this.callback = Objects.requireNonNull(callback);
            this.agentId = Objects.requireNonNull(agentId);
            this.enabled = enabled;
        }
    }
}
