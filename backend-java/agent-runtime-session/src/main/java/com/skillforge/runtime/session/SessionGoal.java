package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 会话当前 Goal 的权威日志投影。
 *
 * <p>每次变更写入完整快照。同一领域 revision 的所有竞争命令共享一个稳定事件槽，
 * 因而 SessionLog 的 eventId/fingerprint 语义就是该 Goal 的 CAS；operationId 只负责
 * 回放原命令结果，不能在后来的快照上重新执行。</p>
 */
public final class SessionGoal {
    public static final String CHANGED = "goal/change";
    public static final int MIN_ROUNDS = 1;
    public static final int MAX_ROUNDS = 1_024;
    public static final int MAX_OBJECTIVE_LENGTH = 8_000;
    public static final int MAX_OPERATION_ID_LENGTH = 200;
    private static final int MAX_GOAL_ID_LENGTH = 200;
    private static final int MAX_REASON_LENGTH = 512;
    static final String SCHEMA = "skillforge.runtime.goal-change.v1";
    private static final Set<String> EVENTS = Set.of(CHANGED);
    private static final int PAGE_SIZE = 100;
    private final SessionLogPort log;

    public SessionGoal(SessionLogPort log) {
        this.log = Objects.requireNonNull(log);
    }

    /** 从完整 owner 的当前格式事件重建现行 Goal；没有创建记录时返回空。 */
    public Optional<Snapshot> read(SessionOwner owner) {
        return Optional.ofNullable(history(owner).current());
    }

    /**
     * 按原始可信 Run、revision 与幂等键读取已提交的终结回执。恢复调用必须先查回执，
     * 再判断当前 Goal；否则已成功的旧操作会被后续 Goal 错当为 CAS 失败。
     */
    public Optional<Snapshot> updateReceipt(RunIdentity run, String operationId, long expectedRevision) {
        Objects.requireNonNull(run);
        operation(operationId);
        revision(expectedRevision);
        Change prior = history(run.owner()).receipts().get(operationId);
        if (prior == null) return Optional.empty();
        Snapshot snapshot = prior.snapshot();
        if (!"update".equals(prior.command()) || prior.expectedRevision() != expectedRevision
                || !snapshot.lastRunId().equals(Optional.of(run.runRef()))
                || !prior.operationFingerprint().equals(fingerprint(map(
                        "command", "update", "expectedRevision", Long.toString(expectedRevision),
                        "operationId", operationId, "reason", snapshot.reason().orElse(null),
                        "runId", run.runRef().runId(), "state", snapshot.state().name()))))
            throw invalid(Reason.OPERATION_CONFLICT);
        return Optional.of(snapshot);
    }

    /** 目标输入的来源栅栏；首轮等待 create，续行等待 reserve/accept，普通对话不读取 Goal 历史。 */
    public RoundAdmission roundAdmission(RunIdentity run) {
        boolean initial = run.causationInputId().value().startsWith("goal-start:");
        if (!initial && !run.causationInputId().value().startsWith("goal-round:")) return RoundAdmission.ORDINARY;
        var history = history(run.owner());
        if (initial) {
            var creation = history.receipts().values().stream().filter(value -> value.command().equals("create")
                    && value.snapshot().lastRunId().equals(Optional.of(run.runRef()))).findFirst();
            // 输入准入先于创建日志提交；此窗口不向模型或工具派发。
            if (creation.isEmpty()) return RoundAdmission.PENDING;
            var current = history.current();
            return current != null && current.goalId().equals(creation.orElseThrow().snapshot().goalId())
                    && current.state() != State.PAUSED && current.lastRunId().equals(Optional.of(run.runRef()))
                    ? RoundAdmission.ACCEPTED : RoundAdmission.REJECTED;
        }
        var reservation = history.receipts().values().stream()
                .filter(value -> value.command().equals("reserve_round")
                        && value.snapshot().pendingRoundInputId().equals(Optional.of(run.causationInputId())))
                .findFirst();
        if (reservation.isEmpty()) return RoundAdmission.REJECTED;
        var current = history.current();
        if (current == null || !current.goalId().equals(reservation.orElseThrow().snapshot().goalId())
                || current.state() == State.PAUSED) return RoundAdmission.REJECTED;
        if (current.pendingRoundInputId().equals(Optional.of(run.causationInputId()))) return RoundAdmission.PENDING;
        // 完成或阻塞工具提交后，本轮仍可正常生成最终回答；暂停与取消不能继续。
        return current.lastRunId().equals(Optional.of(run.runRef()))
                && history.receipts().values().stream().anyMatch(value -> value.command().equals("accept_round")
                    && value.snapshot().lastRunId().equals(Optional.of(run.runRef())))
                ? RoundAdmission.ACCEPTED : RoundAdmission.REJECTED;
    }

    public enum RoundAdmission { ORDINARY, PENDING, ACCEPTED, REJECTED }

    /** 仅在会话没有 Goal 或旧 Goal 已终结时创建新 Goal。 */
    public Snapshot create(RunIdentity run, String operationId, String objective, int maxRounds, Instant at) {
        Objects.requireNonNull(run);
        operation(operationId);
        objective(objective);
        if (maxRounds < MIN_ROUNDS || maxRounds > MAX_ROUNDS) throw invalid(Reason.MAX_ROUNDS_INVALID);
        Objects.requireNonNull(at);
        String goalId = goalId(run.owner(), operationId);
        var requested = new Snapshot(goalId, 1, objective, State.ACTIVE, maxRounds, 0,
                Optional.empty(), Optional.of(run.runRef()), Optional.empty());
        String fingerprint = fingerprint(Map.of(
                "command", "create",
                "maxRounds", Integer.toString(maxRounds),
                "objective", objective,
                "operationId", operationId,
                "runId", run.runRef().runId()));
        var prior = receipt(run.owner(), operationId, fingerprint);
        if (prior.isPresent()) return prior.orElseThrow();

        var history = history(run.owner());
        Snapshot current = history.current();
        if (current != null && !current.terminal()) throw invalid(Reason.ALREADY_ACTIVE);
        long expected = current == null ? 0 : current.revision();
        String predecessor = current == null ? "initial" : current.goalId() + ":" + current.revision();
        return append(run.owner(), run.agentId(), new InputId(operationId), Optional.of(run.runRef()), run.trace(), at,
                slot(run.owner(), "create:" + predecessor), "create", operationId, fingerprint,
                expected, requested);
    }

    /** 模型只能把自己所属的当前 ACTIVE Goal 标记为完成或阻塞。 */
    public Snapshot update(RunIdentity run, String operationId, long expectedRevision,
                           State state, String reason, Instant at) {
        Objects.requireNonNull(run);
        operation(operationId);
        revision(expectedRevision);
        Objects.requireNonNull(state);
        Objects.requireNonNull(at);
        Optional<String> normalizedReason = switch (state) {
            case COMPLETE -> optionalReason(reason);
            case BLOCKED -> Optional.of(requiredReason(reason));
            default -> throw invalid(Reason.UPDATE_STATE_INVALID);
        };
        String fingerprint = fingerprint(map(
                "command", "update",
                "expectedRevision", Long.toString(expectedRevision),
                "operationId", operationId,
                "reason", normalizedReason.orElse(null),
                "runId", run.runRef().runId(),
                "state", state.name()));
        var prior = receipt(run.owner(), operationId, fingerprint);
        if (prior.isPresent()) return prior.orElseThrow();

        Snapshot current = current(run.owner(), expectedRevision);
        if (current.state() != State.ACTIVE || !current.lastRunId().equals(Optional.of(run.runRef())))
            throw invalid(Reason.STATE_CONFLICT);
        var next = new Snapshot(current.goalId(), next(current.revision()), current.objective(), state,
                current.maxRounds(), current.roundsStarted(), Optional.empty(), current.lastRunId(), normalizedReason);
        return append(run.owner(), run.agentId(), run.causationInputId(), Optional.of(run.runRef()), run.trace(), at,
                slot(run.owner(), current.goalId() + ":" + next.revision()), "update", operationId,
                fingerprint, expectedRevision, next);
    }

    /** 宿主显式暂停自动续行；清除 reservation，使旧 accept 必然失去 revision 栅栏。 */
    public Snapshot pause(SessionOwner owner, com.skillforge.runtime.api.AgentId agentId,
                          long expectedRevision, String operationId,
                          InputId inputId, TraceEnvelope trace, String reason, Instant at) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(inputId);
        Objects.requireNonNull(trace);
        Objects.requireNonNull(at);
        operation(operationId);
        revision(expectedRevision);
        String pauseReason = requiredReason(reason);
        String fingerprint = fingerprint(map(
                "command", "pause",
                "expectedRevision", Long.toString(expectedRevision),
                "inputId", inputId.value(),
                "operationId", operationId,
                "reason", pauseReason));
        var prior = receipt(owner, operationId, fingerprint);
        if (prior.isPresent()) return prior.orElseThrow();

        Snapshot current = current(owner, expectedRevision);
        if (current.state() != State.ACTIVE) throw invalid(Reason.STATE_CONFLICT);
        var next = new Snapshot(current.goalId(), next(current.revision()), current.objective(), State.PAUSED,
                current.maxRounds(), current.roundsStarted(), Optional.empty(), current.lastRunId(),
                Optional.of(pauseReason));
        return append(owner, agentId, inputId, Optional.empty(), trace, at,
                slot(owner, current.goalId() + ":" + next.revision()), "pause", operationId,
                fingerprint, expectedRevision, next);
    }

    /** 宿主显式恢复 PAUSED/ACTIVE Goal；ACTIVE 恢复以单次 CAS 清除旧 reservation。 */
    public Snapshot resume(SessionOwner owner, com.skillforge.runtime.api.AgentId agentId,
                           long expectedRevision, String operationId,
                           InputId inputId, TraceEnvelope trace, String reason, Instant at) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(inputId);
        Objects.requireNonNull(trace);
        Objects.requireNonNull(at);
        operation(operationId);
        revision(expectedRevision);
        Optional<String> resumeReason = optionalReason(reason);
        String fingerprint = fingerprint(map(
                "command", "resume",
                "expectedRevision", Long.toString(expectedRevision),
                "inputId", inputId.value(),
                "operationId", operationId,
                "reason", resumeReason.orElse(null)));
        var prior = receipt(owner, operationId, fingerprint);
        if (prior.isPresent()) return prior.orElseThrow();

        Snapshot current = current(owner, expectedRevision);
        if (current.state() != State.PAUSED && current.state() != State.ACTIVE)
            throw invalid(Reason.STATE_CONFLICT);
        var next = new Snapshot(current.goalId(), next(current.revision()), current.objective(), State.ACTIVE,
                current.maxRounds(), current.roundsStarted(), Optional.empty(), current.lastRunId(), Optional.empty());
        return append(owner, agentId, inputId, Optional.empty(), trace, at,
                slot(owner, current.goalId() + ":" + next.revision()), "resume", operationId,
                fingerprint, expectedRevision, next);
    }

    /**
     * 为下一轮输入建立 reservation，但不消耗轮数。轮数已满时以同一 CAS 槽落盘 BLOCKED，
     * 让 round_limit 也成为可恢复、可幂等读取的终态事实。
     */
    public Snapshot reserveRound(SessionOwner owner, com.skillforge.runtime.api.AgentId agentId,
                                 long expectedRevision, String operationId,
                                 InputId inputId, TraceEnvelope trace, Instant at) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(inputId);
        Objects.requireNonNull(trace);
        Objects.requireNonNull(at);
        operation(operationId);
        revision(expectedRevision);
        String fingerprint = fingerprint(Map.of(
                "command", "reserve_round",
                "expectedRevision", Long.toString(expectedRevision),
                "inputId", inputId.value(),
                "operationId", operationId));
        var prior = receipt(owner, operationId, fingerprint);
        if (prior.isPresent()) return prior.orElseThrow();

        Snapshot current = current(owner, expectedRevision);
        if (current.state() != State.ACTIVE || current.pendingRoundInputId().isPresent())
            throw invalid(Reason.ROUND_CONFLICT);
        long revision = next(current.revision());
        Snapshot next = current.roundsStarted() >= current.maxRounds()
                ? new Snapshot(current.goalId(), revision, current.objective(), State.BLOCKED,
                        current.maxRounds(), current.roundsStarted(), Optional.empty(), current.lastRunId(),
                        Optional.of("round_limit"))
                : new Snapshot(current.goalId(), revision, current.objective(), State.ACTIVE,
                        current.maxRounds(), current.roundsStarted(), Optional.of(inputId), current.lastRunId(),
                        Optional.empty());
        return append(owner, agentId, inputId, Optional.empty(), trace, at,
                slot(owner, current.goalId() + ":" + revision), "reserve_round", operationId,
                fingerprint, expectedRevision, next);
    }

    /** 接受匹配的 reservation 后才消耗一轮，并把本轮 Run 固定为 Goal 的最新 Run。 */
    public Snapshot acceptRound(SessionOwner owner, com.skillforge.runtime.api.AgentId agentId,
                                long expectedRevision, String operationId,
                                InputId inputId, RunRef runId, TraceEnvelope trace, Instant at) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(inputId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(trace);
        Objects.requireNonNull(at);
        operation(operationId);
        revision(expectedRevision);
        if (!owner.sessionRef().tenantId().equals(runId.tenantId())) throw invalid(Reason.RUN_OWNER_MISMATCH);
        String fingerprint = fingerprint(Map.of(
                "command", "accept_round",
                "expectedRevision", Long.toString(expectedRevision),
                "inputId", inputId.value(),
                "operationId", operationId,
                "runId", runId.runId()));
        var prior = receipt(owner, operationId, fingerprint);
        if (prior.isPresent()) return prior.orElseThrow();

        Snapshot current = current(owner, expectedRevision);
        if (current.state() != State.ACTIVE
                || !current.pendingRoundInputId().equals(Optional.of(inputId))
                || current.roundsStarted() >= current.maxRounds()) throw invalid(Reason.ROUND_CONFLICT);
        var next = new Snapshot(current.goalId(), next(current.revision()), current.objective(), State.ACTIVE,
                current.maxRounds(), Math.incrementExact(current.roundsStarted()), Optional.empty(),
                Optional.of(runId), Optional.empty());
        return append(owner, agentId, inputId, Optional.of(runId), trace, at,
                slot(owner, current.goalId() + ":" + next.revision()), "accept_round", operationId,
                fingerprint, expectedRevision, next);
    }

    private Snapshot append(SessionOwner owner, com.skillforge.runtime.api.AgentId agentId,
                            InputId inputId, Optional<RunRef> runId,
                            TraceEnvelope trace, Instant at, EventId eventId, String command,
                            String operationId, String operationFingerprint, long expectedRevision,
                            Snapshot snapshot) {
        var payload = new TypedPayload(SCHEMA, RuntimeJson.writeCanonical(map(
                "command", command,
                "expectedRevision", Long.toString(expectedRevision),
                "operationFingerprint", operationFingerprint,
                "operationId", operationId,
                "snapshot", value(snapshot))));
        var target = new SessionProjectionTarget(owner, agentId, inputId, runId,
                Optional.empty(), Optional.empty(), Optional.empty());
        var draft = new SessionEventDraft(eventId, CHANGED, "1",
                SessionEventValues.hash(payload.schemaId(), payload.canonicalJson()), trace, payload, at);
        var request = new SessionLogPort.AppendSessionEvent(target, draft);
        var result = log.append(request);
        if (result instanceof SessionLogPort.AppendResult.Conflict) {
            var raced = receipt(owner, operationId, operationFingerprint);
            if (raced.isPresent()) return raced.orElseThrow();
            throw invalid(Reason.REVISION_CONFLICT);
        }
        CommittedSessionEvent committed = switch (result) {
            case SessionLogPort.AppendResult.Appended value -> value.event();
            case SessionLogPort.AppendResult.Replayed value -> value.event();
            case SessionLogPort.AppendResult.Conflict ignored -> throw new AssertionError();
        };
        SessionEventValues.requireAppend(request, committed, 0);
        Change change = change(committed);
        if (!change.operationId().equals(operationId)
                || !change.operationFingerprint().equals(operationFingerprint)
                || !change.snapshot().equals(snapshot)) throw invalid(Reason.EVENT_REJECTED);
        return change.snapshot();
    }

    private Snapshot current(SessionOwner owner, long expectedRevision) {
        Snapshot current = history(owner).current();
        if (current == null) throw invalid(Reason.NOT_FOUND);
        if (current.revision() != expectedRevision) throw invalid(Reason.REVISION_CONFLICT);
        return current;
    }

    private Optional<Snapshot> receipt(SessionOwner owner, String operationId, String fingerprint) {
        Change receipt = history(owner).receipts().get(operationId);
        if (receipt == null) return Optional.empty();
        if (!receipt.operationFingerprint().equals(fingerprint)) throw invalid(Reason.OPERATION_CONFLICT);
        return Optional.of(receipt.snapshot());
    }

    private History history(SessionOwner owner) {
        Objects.requireNonNull(owner);
        var receipts = new HashMap<String, Change>();
        Snapshot current = null;
        long cursor = 0;
        while (true) {
            var page = log.readAfter(new SessionLogPort.ReadSessionLog(owner, cursor, PAGE_SIZE), EVENTS);
            if (page == null || page.size() > PAGE_SIZE) throw invalid(Reason.EVENT_REJECTED);
            for (var event : page) {
                if (!event.owner().equals(owner) || event.sessionSequence() <= cursor)
                    throw invalid(Reason.EVENT_REJECTED);
                cursor = event.sessionSequence();
                Change change = change(event);
                if (receipts.putIfAbsent(change.operationId(), change) != null)
                    throw invalid(Reason.EVENT_REJECTED);
                current = apply(owner, current, event, change);
            }
            if (page.size() < PAGE_SIZE) return new History(current, Map.copyOf(receipts));
        }
    }

    private static Snapshot apply(SessionOwner owner, Snapshot before,
                                  CommittedSessionEvent event, Change change) {
        Snapshot after = change.snapshot();
        if (!after.lastRunId().orElseThrow().tenantId().equals(owner.sessionRef().tenantId()))
            throw invalid(Reason.EVENT_REJECTED);
        if ("create".equals(change.command())) {
            if (after.revision() != 1 || before != null && !before.terminal()
                    || change.expectedRevision() != (before == null ? 0 : before.revision())
                    || !after.goalId().equals(goalId(owner, change.operationId()))
                    || after.state() != State.ACTIVE || after.roundsStarted() != 0
                    || after.pendingRoundInputId().isPresent() || after.reason().isPresent()
                    || !event.inputId().value().equals(change.operationId())
                    || !event.runRef().equals(after.lastRunId()))
                throw invalid(Reason.EVENT_REJECTED);
            String predecessor = before == null ? "initial" : before.goalId() + ":" + before.revision();
            if (!event.eventId().equals(slot(owner, "create:" + predecessor)))
                throw invalid(Reason.EVENT_REJECTED);
        } else {
            if (before == null || after.revision() != next(before.revision())
                    || change.expectedRevision() != before.revision()
                    || !after.goalId().equals(before.goalId())
                    || !event.eventId().equals(slot(owner, before.goalId() + ":" + after.revision())))
                throw invalid(Reason.EVENT_REJECTED);
            requireTransition(before, event, change.command(), after);
        }
        return after;
    }

    private static void requireTransition(Snapshot before, CommittedSessionEvent event,
                                          String command, Snapshot after) {
        if (!after.objective().equals(before.objective()) || after.maxRounds() != before.maxRounds())
            throw invalid(Reason.EVENT_REJECTED);
        boolean stableProgress = after.roundsStarted() == before.roundsStarted()
                && after.lastRunId().equals(before.lastRunId());
        switch (command) {
            case "update" -> {
                if (before.state() != State.ACTIVE || !after.terminal() || !stableProgress
                        || after.pendingRoundInputId().isPresent()
                        || !event.runRef().equals(before.lastRunId())) throw invalid(Reason.EVENT_REJECTED);
            }
            case "pause" -> {
                if (before.state() != State.ACTIVE || after.state() != State.PAUSED || !stableProgress
                        || after.pendingRoundInputId().isPresent() || event.runRef().isPresent())
                    throw invalid(Reason.EVENT_REJECTED);
            }
            case "resume" -> {
                if ((before.state() != State.PAUSED && before.state() != State.ACTIVE)
                        || after.state() != State.ACTIVE || !stableProgress
                        || after.pendingRoundInputId().isPresent() || after.reason().isPresent()
                        || event.runRef().isPresent()) throw invalid(Reason.EVENT_REJECTED);
            }
            case "reserve_round" -> {
                if (before.state() != State.ACTIVE || before.pendingRoundInputId().isPresent()
                        || !stableProgress || event.runRef().isPresent()) throw invalid(Reason.EVENT_REJECTED);
                if (before.roundsStarted() >= before.maxRounds()) {
                    if (after.state() != State.BLOCKED || after.pendingRoundInputId().isPresent()
                            || !after.reason().equals(Optional.of("round_limit")))
                        throw invalid(Reason.EVENT_REJECTED);
                } else if (after.state() != State.ACTIVE
                        || !after.pendingRoundInputId().equals(Optional.of(event.inputId()))
                        || after.reason().isPresent()) throw invalid(Reason.EVENT_REJECTED);
            }
            case "accept_round" -> {
                if (before.state() != State.ACTIVE
                        || !before.pendingRoundInputId().equals(Optional.of(event.inputId()))
                        || before.roundsStarted() >= before.maxRounds()
                        || after.state() != State.ACTIVE
                        || after.roundsStarted() != Math.incrementExact(before.roundsStarted())
                        || after.pendingRoundInputId().isPresent() || after.reason().isPresent()
                        || event.runRef().isEmpty() || !after.lastRunId().equals(event.runRef()))
                    throw invalid(Reason.EVENT_REJECTED);
            }
            default -> throw invalid(Reason.EVENT_REJECTED);
        }
    }

    private static Change change(CommittedSessionEvent event) {
        if (!CHANGED.equals(event.eventType()) || !"1".equals(event.eventVersion())
                || !SCHEMA.equals(event.payload().schemaId())) throw invalid(Reason.EVENT_REJECTED);
        Map<?, ?> root = SessionNativeConversation.object(event.payload().canonicalJson());
        if (!root.keySet().equals(Set.of("command", "expectedRevision", "operationFingerprint",
                "operationId", "snapshot"))
                || !(root.get("command") instanceof String command)
                || !Set.of("create", "update", "pause", "resume", "reserve_round", "accept_round").contains(command)
                || !(root.get("expectedRevision") instanceof String expectedText)
                || !(root.get("operationFingerprint") instanceof String fingerprint)
                || !(root.get("operationId") instanceof String operationId)
                || !(root.get("snapshot") instanceof Map<?, ?> snapshotValue))
            throw invalid(Reason.EVENT_REJECTED);
        operation(operationId);
        if (!fingerprint.matches("[0-9a-f]{64}")) throw invalid(Reason.EVENT_REJECTED);
        long expected;
        try { expected = Long.parseLong(expectedText); }
        catch (NumberFormatException rejected) { throw invalid(Reason.EVENT_REJECTED); }
        if (expected < 0) throw invalid(Reason.EVENT_REJECTED);
        return new Change(command, operationId, fingerprint, expected, snapshot(snapshotValue));
    }

    private static Snapshot snapshot(Map<?, ?> value) {
        if (!value.keySet().equals(Set.of("goalId", "revision", "objective", "state", "maxRounds",
                "roundsStarted", "pendingRoundInputId", "lastRunId", "lastRunTenantId", "reason"))
                || !(value.get("goalId") instanceof String goalId)
                || !(value.get("revision") instanceof String revisionText)
                || !(value.get("objective") instanceof String objective)
                || !(value.get("state") instanceof String stateText)
                || !(value.get("maxRounds") instanceof String maxText)
                || !(value.get("roundsStarted") instanceof String roundsText))
            throw invalid(Reason.EVENT_REJECTED);
        try {
            State state = State.valueOf(stateText);
            Optional<InputId> pending = value.get("pendingRoundInputId") == null
                    ? Optional.empty() : Optional.of(new InputId((String) value.get("pendingRoundInputId")));
            Optional<RunRef> lastRun = value.get("lastRunId") == null
                    ? Optional.empty() : Optional.of(new RunRef(
                            new com.skillforge.runtime.api.TenantId((String) value.get("lastRunTenantId")),
                            (String) value.get("lastRunId")));
            Optional<String> reason = value.get("reason") == null
                    ? Optional.empty() : Optional.of((String) value.get("reason"));
            return new Snapshot(goalId, Long.parseLong(revisionText), objective, state,
                    Integer.parseInt(maxText), Integer.parseInt(roundsText), pending, lastRun, reason);
        } catch (ClassCastException | IllegalArgumentException rejected) {
            throw invalid(Reason.EVENT_REJECTED);
        }
    }

    private static Map<String, Object> value(Snapshot snapshot) {
        return map(
                "goalId", snapshot.goalId(),
                "lastRunId", snapshot.lastRunId().map(RunRef::runId).orElse(null),
                "lastRunTenantId", snapshot.lastRunId().map(value -> value.tenantId().value()).orElse(null),
                "maxRounds", Integer.toString(snapshot.maxRounds()),
                "objective", snapshot.objective(),
                "pendingRoundInputId", snapshot.pendingRoundInputId().map(InputId::value).orElse(null),
                "reason", snapshot.reason().orElse(null),
                "revision", Long.toString(snapshot.revision()),
                "roundsStarted", Integer.toString(snapshot.roundsStarted()),
                "state", snapshot.state().name());
    }

    private static String goalId(SessionOwner owner, String operationId) {
        return "goal-" + SessionEventValues.hash("skillforge.runtime.goal-id.v1",
                RuntimeJson.writeCanonical(List.of(ownerValue(owner), operationId)));
    }

    private static EventId slot(SessionOwner owner, String revisionSlot) {
        return new EventId("goal-slot:" + SessionEventValues.hash("skillforge.runtime.goal-slot.v1",
                RuntimeJson.writeCanonical(List.of(ownerValue(owner), revisionSlot))));
    }

    private static List<String> ownerValue(SessionOwner owner) {
        return List.of(owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), Long.toString(owner.requireUser().value()));
    }

    private static String fingerprint(Map<String, Object> command) {
        return SessionEventValues.hash("skillforge.runtime.goal-operation.v1", RuntimeJson.writeCanonical(command));
    }

    private static Map<String, Object> map(Object... pairs) {
        var result = new TreeMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2)
            result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static void operation(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_OPERATION_ID_LENGTH)
            throw invalid(Reason.OPERATION_INVALID);
    }

    private static void objective(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_OBJECTIVE_LENGTH)
            throw invalid(Reason.OBJECTIVE_INVALID);
    }

    private static Optional<String> optionalReason(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        if (value.length() > MAX_REASON_LENGTH) throw invalid(Reason.REASON_INVALID);
        return Optional.of(value);
    }

    private static String requiredReason(String value) {
        return optionalReason(value).orElseThrow(() -> invalid(Reason.REASON_INVALID));
    }

    private static void revision(long value) {
        if (value <= 0) throw invalid(Reason.REVISION_INVALID);
    }

    private static long next(long value) {
        try { return Math.incrementExact(value); }
        catch (ArithmeticException overflow) { throw invalid(Reason.REVISION_INVALID); }
    }

    private static Rejected invalid(Reason reason) {
        return new Rejected(reason);
    }

    /** Goal 规则的稳定拒绝原因；调用方按类型分支，文案不承担恢复协议。 */
    public enum Reason {
        ALREADY_ACTIVE("goal_already_active"),
        EVENT_REJECTED("goal_event_rejected"),
        MAX_ROUNDS_INVALID("goal_max_rounds_invalid"),
        NOT_FOUND("goal_not_found"),
        OBJECTIVE_INVALID("goal_objective_invalid"),
        OPERATION_CONFLICT("goal_operation_conflict"),
        OPERATION_INVALID("goal_operation_invalid"),
        REASON_INVALID("goal_reason_invalid"),
        REVISION_CONFLICT("goal_revision_conflict"),
        REVISION_INVALID("goal_revision_invalid"),
        ROUND_CONFLICT("goal_round_conflict"),
        RUN_OWNER_MISMATCH("goal_run_owner_mismatch"),
        SNAPSHOT_INVALID("goal_snapshot_invalid"),
        STATE_CONFLICT("goal_state_conflict"),
        UPDATE_STATE_INVALID("goal_update_state_invalid");

        private final String code;
        Reason(String code) { this.code = code; }
        public String code() { return code; }
    }

    /** 已确定的 Goal 规则拒绝；技术异常保留原始异常类型与原因链。 */
    public static final class Rejected extends IllegalStateException {
        private final Reason reason;

        public Rejected(Reason reason) {
            super(Objects.requireNonNull(reason).code());
            this.reason = reason;
        }

        public Reason reason() { return reason; }
        public String code() { return reason.code(); }
    }

    public enum State { ACTIVE, PAUSED, COMPLETE, BLOCKED }

    /** 面向驱动和模型工具的公开快照；不暴露 trace、事件身份或宿主归属。 */
    public record Snapshot(
            String goalId,
            long revision,
            String objective,
            State state,
            int maxRounds,
            int roundsStarted,
            Optional<InputId> pendingRoundInputId,
            Optional<RunRef> lastRunId,
            Optional<String> reason) {
        public Snapshot {
            if (goalId == null || goalId.isBlank() || goalId.length() > MAX_GOAL_ID_LENGTH) throw invalid(Reason.SNAPSHOT_INVALID);
            SessionGoal.revision(revision);
            SessionGoal.objective(objective);
            Objects.requireNonNull(state);
            if (maxRounds < MIN_ROUNDS || maxRounds > MAX_ROUNDS || roundsStarted < 0 || roundsStarted > maxRounds)
                throw invalid(Reason.SNAPSHOT_INVALID);
            pendingRoundInputId = Objects.requireNonNull(pendingRoundInputId);
            lastRunId = Objects.requireNonNull(lastRunId);
            reason = Objects.requireNonNull(reason);
            if (lastRunId.isEmpty() || pendingRoundInputId.isPresent() && state != State.ACTIVE
                    || state == State.ACTIVE && reason.isPresent()
                    || state == State.PAUSED && reason.isEmpty()
                    || state == State.BLOCKED && reason.isEmpty()
                    || reason.filter(value -> value.isBlank() || value.length() > MAX_REASON_LENGTH).isPresent())
                throw invalid(Reason.SNAPSHOT_INVALID);
        }

        public boolean terminal() {
            return state == State.COMPLETE || state == State.BLOCKED;
        }
    }

    private record Change(String command, String operationId, String operationFingerprint,
                          long expectedRevision, Snapshot snapshot) { }

    private record History(Snapshot current, Map<String, Change> receipts) { }
}
