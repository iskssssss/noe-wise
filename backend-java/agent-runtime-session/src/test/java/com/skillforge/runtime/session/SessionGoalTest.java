package com.skillforge.runtime.session;

import static com.skillforge.runtime.session.SessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SessionGoalTest {
    @Test
    void goalHistoryBeyondTenThousandEventsRetainsOldReceiptsAndCurrentCas() {
        var log = new Log();
        var goals = new SessionGoal(log);
        var first = goals.create(IDENTITY, "create-0", "目标", 1, NOW);
        var firstCompleted = goals.update(IDENTITY, "complete-0", 1, SessionGoal.State.COMPLETE, null, NOW);
        var ownerValue = List.of(TENANT.value(), OWNER.sessionRef().sessionId(), OWNER.target().kind().name(),
                OWNER.target().id(), Long.toString(OWNER.requireUser().value()));
        String predecessor = first.goalId() + ":2";
        for (int index = 1; index < 5_000; index++) {
            String operation = "create-" + index;
            String goal = "goal-" + SessionEventValues.hash("skillforge.runtime.goal-id.v1",
                    com.skillforge.runtime.json.RuntimeJson.writeCanonical(List.of(ownerValue, operation)));
            seedGoalChange(log, ownerValue, "create:" + predecessor, operation, "create", 2,
                    goal, 1, "ACTIVE");
            seedGoalChange(log, ownerValue, goal + ":2", "complete-" + index, "update", 1,
                    goal, 2, "COMPLETE");
            predecessor = goal + ":2";
        }
        assertEquals(10_000, log.size());
        var next = goals.create(IDENTITY, "create-5000", "后续目标", 1, NOW);
        assertEquals(10_001, log.size());
        assertEquals(next, new SessionGoal(log).read(OWNER).orElseThrow());
        assertEquals(first, goals.create(IDENTITY, "create-0", "目标", 1, NOW.plusSeconds(1)));
        assertEquals(Optional.of(firstCompleted), goals.updateReceipt(IDENTITY, "complete-0", 1));
        assertEquals(SessionGoal.Reason.OPERATION_CONFLICT, assertThrows(SessionGoal.Rejected.class,
                () -> goals.create(IDENTITY, "create-0", "不同目标", 1, NOW)).reason());
        var paused = goals.pause(OWNER, IDENTITY.agentId(), next.revision(), "pause-new",
                new InputId("pause-new"), IDENTITY.trace(), "user_paused", NOW);
        assertEquals(paused, new SessionGoal(log).read(OWNER).orElseThrow());
        assertEquals(SessionGoal.Reason.REVISION_CONFLICT, assertThrows(SessionGoal.Rejected.class,
                () -> goals.reserveRound(OWNER, IDENTITY.agentId(), next.revision(), "stale",
                        new InputId("round"), IDENTITY.trace(), NOW)).reason());
        assertEquals(10_002, log.size());
        seedGoalChange(log, ownerValue, paused.goalId() + ":3", "invalid-after-limit", "resume", 1,
                paused.goalId(), 3, "ACTIVE");
        assertEquals(SessionGoal.Reason.EVENT_REJECTED,
                assertThrows(SessionGoal.Rejected.class, () -> new SessionGoal(log).read(OWNER)).reason());
    }

    private static void seedGoalChange(Log log, List<String> ownerValue, String revisionSlot,
            String operation, String command, long expected, String goal, long revision, String state) {
        var snapshot = new java.util.TreeMap<String, Object>();
        snapshot.put("goalId", goal); snapshot.put("revision", Long.toString(revision));
        snapshot.put("objective", "目标"); snapshot.put("state", state); snapshot.put("maxRounds", "1");
        snapshot.put("roundsStarted", "0"); snapshot.put("pendingRoundInputId", null);
        snapshot.put("lastRunId", IDENTITY.runRef().runId()); snapshot.put("lastRunTenantId", TENANT.value());
        snapshot.put("reason", null);
        var fingerprintInput = new java.util.TreeMap<String, Object>();
        fingerprintInput.put("command", command); fingerprintInput.put("operationId", operation);
        fingerprintInput.put("runId", IDENTITY.runRef().runId());
        if (command.equals("create")) { fingerprintInput.put("maxRounds", "1"); fingerprintInput.put("objective", "目标"); }
        else { fingerprintInput.put("expectedRevision", Long.toString(expected)); fingerprintInput.put("reason", null); fingerprintInput.put("state", state); }
        var payload = new com.skillforge.runtime.api.TypedPayload(SessionGoal.SCHEMA,
                com.skillforge.runtime.json.RuntimeJson.writeCanonical(Map.of("command", command,
                        "expectedRevision", Long.toString(expected), "operationFingerprint", SessionEventValues.hash(
                                "skillforge.runtime.goal-operation.v1", com.skillforge.runtime.json.RuntimeJson.writeCanonical(fingerprintInput)),
                        "operationId", operation, "snapshot", snapshot)));
        var eventId = new EventId("goal-slot:" + SessionEventValues.hash("skillforge.runtime.goal-slot.v1",
                com.skillforge.runtime.json.RuntimeJson.writeCanonical(List.of(ownerValue, revisionSlot))));
        var target = new com.skillforge.runtime.api.port.SessionProjectionTarget(OWNER, IDENTITY.agentId(),
                command.equals("create") ? new InputId(operation) : IDENTITY.causationInputId(),
                Optional.of(IDENTITY.runRef()), Optional.empty(), Optional.empty(), Optional.empty());
        var event = new com.skillforge.runtime.api.port.SessionEventDraft(eventId, SessionGoal.CHANGED, "1",
                SessionEventValues.hash(payload.schemaId(), payload.canonicalJson()), IDENTITY.trace(), payload, NOW);
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                log.append(new SessionLogPort.AppendSessionEvent(target, event)));
    }
    @Test
    void expandedRoundLimitIsPersistedAndItsUpperBoundaryStillApplies() {
        var log = new Log();
        var goals = new SessionGoal(log);
        var created = goals.create(IDENTITY, "expanded-rounds", "持续处理用户任务", SessionGoal.MAX_ROUNDS, NOW);
        assertEquals(1_024, created.maxRounds());
        assertEquals(created, new SessionGoal(log).read(OWNER).orElseThrow());
        assertEquals(SessionGoal.Reason.MAX_ROUNDS_INVALID, assertThrows(SessionGoal.Rejected.class,
                () -> new SessionGoal(new Log()).create(IDENTITY, "too-many-rounds", "目标",
                        SessionGoal.MAX_ROUNDS + 1, NOW)).reason());
    }

    @Test
    void terminalReceiptIsBoundToOriginalRunAndSurvivesNewGoal() {
        var log = new Log();
        var goals = new SessionGoal(log);
        goals.create(IDENTITY, "receipt-first", "发布成果", 2, NOW);
        assertTrue(goals.updateReceipt(IDENTITY, "complete-original", 1).isEmpty());
        var completed = goals.update(IDENTITY, "complete-original", 1, SessionGoal.State.COMPLETE, null, NOW);
        var next = goals.create(IDENTITY, "receipt-second", "新目标", 2, NOW.plusSeconds(1));
        assertEquals(Optional.of(completed), goals.updateReceipt(IDENTITY, "complete-original", 1));
        assertEquals(next, goals.read(OWNER).orElseThrow());
        assertEquals(SessionGoal.Reason.OPERATION_CONFLICT, assertThrows(SessionGoal.Rejected.class,
                () -> goals.updateReceipt(IDENTITY, "complete-original", 2)).reason());
        var otherRun = new com.skillforge.runtime.api.port.RunIdentity(OWNER, OWNER.target().requireAgent(), IDENTITY.causationInputId(), new RunRef(TENANT, "different-run"), IDENTITY.trace(), IDENTITY.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), IDENTITY.causationInputId().value()));
        assertEquals(SessionGoal.Reason.OPERATION_CONFLICT, assertThrows(SessionGoal.Rejected.class,
                () -> goals.updateReceipt(otherRun, "complete-original", 1)).reason());
    }

    @Test
    void initialGoalInputWaitsForCreationAndPausedInputsCannotExecute() {
        var goals = new SessionGoal(new Log());
        var input = new InputId("goal-start:create");
        var run = new com.skillforge.runtime.api.port.RunIdentity(OWNER, OWNER.target().requireAgent(), input, IDENTITY.runRef(), new com.skillforge.runtime.api.TraceEnvelope("trace", "span", input, Optional.empty()), IDENTITY.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), input.value()));
        assertEquals(SessionGoal.RoundAdmission.ORDINARY, goals.roundAdmission(IDENTITY));
        assertEquals(SessionGoal.RoundAdmission.PENDING, goals.roundAdmission(run));
        var created = goals.create(run, "create", "完成交付", 2, NOW);
        assertEquals(SessionGoal.RoundAdmission.ACCEPTED, goals.roundAdmission(run));
        goals.pause(OWNER, IDENTITY.agentId(), created.revision(), "pause", input, run.trace(), "user_paused", NOW);
        assertEquals(SessionGoal.RoundAdmission.REJECTED, goals.roundAdmission(run));
    }

    @Test
    void sameRevisionUsesOneCasSlotAndOperationReplayKeepsOriginalReceipt() throws Exception {
        var log = new Log();
        var first = new SessionGoal(log);
        var second = new SessionGoal(log);
        first.create(IDENTITY, "create", "完成交付", 2, NOW);
        log.raceNextPair();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pause = executor.submit(() -> capture(() -> first.pause(
                    OWNER, IDENTITY.agentId(), 1, "pause", new InputId("pause-input"), IDENTITY.trace(), "cancelled", NOW)));
            var reserve = executor.submit(() -> capture(() -> second.reserveRound(
                    OWNER, IDENTITY.agentId(), 1, "reserve", new InputId("round-input"), IDENTITY.trace(), NOW)));
            var results = List.of(pause.get(5, TimeUnit.SECONDS), reserve.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Result::success).count());
            assertEquals(1, results.stream().filter(result -> result.error() == SessionGoal.Reason.REVISION_CONFLICT).count());
        }
        assertEquals(2, log.size());

        SessionGoal.Snapshot winner = first.read(OWNER).orElseThrow();
        String operation = winner.state() == SessionGoal.State.PAUSED ? "pause" : "reserve";
        SessionGoal.Snapshot receipt = winner.state() == SessionGoal.State.PAUSED
                ? first.pause(OWNER, IDENTITY.agentId(), 1, operation, new InputId("pause-input"), IDENTITY.trace(), "cancelled", NOW.plusSeconds(1))
                : first.reserveRound(OWNER, IDENTITY.agentId(), 1, operation, new InputId("round-input"), IDENTITY.trace(), NOW.plusSeconds(1));
        assertEquals(winner, receipt);
        assertEquals(2, log.size(), "重试必须返回原事件结果，不能覆盖当前状态");
    }

    @Test
    void rebuildsFullSnapshotAndCountsOnlyAcceptedRounds() {
        var log = new Log();
        var goals = new SessionGoal(log);
        var created = goals.create(IDENTITY, "create", "持续修复直到完成", 1, NOW);
        var reserved = goals.reserveRound(OWNER, IDENTITY.agentId(), created.revision(), "reserve", new InputId("next"),
                IDENTITY.trace(), NOW.plusSeconds(1));
        assertEquals(0, reserved.roundsStarted());
        assertEquals(Optional.of(new InputId("next")), reserved.pendingRoundInputId());

        var nextRun = new RunRef(TENANT, "round-1");
        var accepted = goals.acceptRound(OWNER, IDENTITY.agentId(), reserved.revision(), "accept", new InputId("next"),
                nextRun, IDENTITY.trace(), NOW.plusSeconds(2));
        assertEquals(1, accepted.roundsStarted());
        assertEquals(Optional.empty(), accepted.pendingRoundInputId());
        assertEquals(Optional.of(nextRun), accepted.lastRunId());
        assertEquals(accepted, new SessionGoal(log).read(OWNER).orElseThrow());
        assertEquals(reserved, goals.reserveRound(OWNER, IDENTITY.agentId(), created.revision(), "reserve", new InputId("next"),
                IDENTITY.trace(), NOW.plusSeconds(20)), "旧 operation 必须回放 reservation 时的原快照");
        var operationConflict = assertThrows(SessionGoal.Rejected.class, () -> goals.reserveRound(
                OWNER, IDENTITY.agentId(), created.revision(), "reserve", new InputId("changed"), IDENTITY.trace(), NOW.plusSeconds(20)));
        assertEquals(SessionGoal.Reason.OPERATION_CONFLICT, operationConflict.reason());

        var limited = goals.reserveRound(OWNER, IDENTITY.agentId(), accepted.revision(), "limit", new InputId("too-many"),
                IDENTITY.trace(), NOW.plusSeconds(3));
        assertEquals(SessionGoal.State.BLOCKED, limited.state());
        assertEquals(Optional.of("round_limit"), limited.reason());
        assertEquals(1, limited.roundsStarted());
    }

    @Test
    void pauseInvalidatesReservationAndResumeRequiresANewOne() {
        var log = new Log();
        var goals = new SessionGoal(log);
        var created = goals.create(IDENTITY, "create", "等待显式恢复", 2, NOW);
        var reserved = goals.reserveRound(OWNER, IDENTITY.agentId(), created.revision(), "reserve", new InputId("reserved"),
                IDENTITY.trace(), NOW.plusSeconds(1));
        var paused = goals.pause(OWNER, IDENTITY.agentId(), reserved.revision(), "pause", new InputId("cancel"),
                IDENTITY.trace(), "cancelled", NOW.plusSeconds(2));
        assertEquals(SessionGoal.State.PAUSED, paused.state());
        assertTrue(paused.pendingRoundInputId().isEmpty());

        var rejected = assertThrows(SessionGoal.Rejected.class, () -> goals.acceptRound(
                OWNER, IDENTITY.agentId(), reserved.revision(), "late-accept", new InputId("reserved"),
                new RunRef(TENANT, "late"), IDENTITY.trace(), NOW.plusSeconds(3)));
        assertEquals(SessionGoal.Reason.REVISION_CONFLICT, rejected.reason());

        var resumed = goals.resume(OWNER, IDENTITY.agentId(), paused.revision(), "resume", new InputId("resume"),
                IDENTITY.trace(), "user_requested", NOW.plusSeconds(4));
        assertEquals(SessionGoal.State.ACTIVE, resumed.state());
        assertTrue(resumed.pendingRoundInputId().isEmpty());
        var noReservation = assertThrows(SessionGoal.Rejected.class, () -> goals.acceptRound(
                OWNER, IDENTITY.agentId(), resumed.revision(), "unreserved", new InputId("reserved"),
                new RunRef(TENANT, "unreserved"), IDENTITY.trace(), NOW.plusSeconds(5)));
        assertEquals(SessionGoal.Reason.ROUND_CONFLICT, noReservation.reason());
    }

    @Test
    void activeResumeClearsReservationWithOneRevisionAndReplaysSameOperation() {
        var log = new Log();
        var goals = new SessionGoal(log);
        var created = goals.create(IDENTITY, "create", "显式恢复活动目标", 2, NOW);
        var reserved = goals.reserveRound(OWNER, IDENTITY.agentId(), created.revision(), "reserve", new InputId("reserved"),
                IDENTITY.trace(), NOW.plusSeconds(1));

        var resumed = goals.resume(OWNER, IDENTITY.agentId(), reserved.revision(), "resume-active", new InputId("resume"),
                IDENTITY.trace(), "explicit_resume", NOW.plusSeconds(2));
        assertEquals(reserved.revision() + 1, resumed.revision());
        assertEquals(SessionGoal.State.ACTIVE, resumed.state());
        assertTrue(resumed.pendingRoundInputId().isEmpty());
        assertEquals(3, log.size());

        assertEquals(resumed, goals.resume(OWNER, IDENTITY.agentId(), reserved.revision(), "resume-active", new InputId("resume"),
                IDENTITY.trace(), "explicit_resume", NOW.plusSeconds(3)));
        assertEquals(3, log.size(), "ACTIVE resume 重试必须回放同一事件，不能再增加 revision");
    }

    private static Result capture(Action action) {
        try { return new Result(true, action.execute(), null); }
        catch (SessionGoal.Rejected failure) { return new Result(false, null, failure.reason()); }
    }

    @FunctionalInterface
    private interface Action { SessionGoal.Snapshot execute(); }

    private record Result(boolean success, SessionGoal.Snapshot snapshot, SessionGoal.Reason error) { }

    private static final class Log implements SessionLogPort {
        private final Map<Key, AppendSessionEvent> commands = new HashMap<>();
        private final List<CommittedSessionEvent> events = new ArrayList<>();
        private volatile CountDownLatch race;

        void raceNextPair() {
            race = new CountDownLatch(2);
        }

        int size() {
            synchronized (this) { return events.size(); }
        }

        @Override
        public AppendResult append(AppendSessionEvent command) {
            CountDownLatch barrier = race;
            if (barrier != null) {
                barrier.countDown();
                try {
                    if (!barrier.await(5, TimeUnit.SECONDS)) throw new AssertionError("CAS competitors did not meet");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            synchronized (this) {
                if (race == barrier) race = null;
                var key = new Key(command.target().owner(), command.event().eventId());
                var priorCommand = commands.get(key);
                if (priorCommand != null) {
                    var prior = events.stream().filter(event -> event.owner().equals(key.owner())
                            && event.eventId().equals(key.eventId())).findFirst().orElseThrow();
                    if (priorCommand.equals(command)) return new AppendResult.Replayed(prior);
                    return new AppendResult.Conflict(new PortConflict(
                            "session_event", command.event().eventId().value(), new ResultRef("audit")));
                }
                var target = command.target();
                var draft = command.event();
                var committed = new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(),
                        target.executionRef(), target.actionRef(), target.delegationRef(), draft.eventId(),
                        events.size() + 1L, draft.eventType(), draft.eventVersion(), draft.trace(),
                        draft.payload(), draft.occurredAt());
                commands.put(key, command);
                events.add(committed);
                return new AppendResult.Appended(committed);
            }
        }

        @Override
        public synchronized Optional<CommittedSessionEvent> findByEvent(SessionOwner owner, EventId eventId) {
            return events.stream().filter(event -> event.owner().equals(owner)
                    && event.eventId().equals(eventId)).findFirst();
        }

        @Override
        public synchronized List<CommittedSessionEvent> readAfter(ReadSessionLog query) {
            return events.stream().filter(event -> event.owner().equals(query.owner())
                    && event.sessionSequence() > query.afterSequence()).limit(query.limit()).toList();
        }

        private record Key(SessionOwner owner, EventId eventId) { }
    }
}
