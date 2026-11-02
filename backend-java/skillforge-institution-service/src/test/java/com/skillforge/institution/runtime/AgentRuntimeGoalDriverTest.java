package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.institution.artifact.DeliverableContract.GoalCompletion;
import com.skillforge.institution.artifact.DeliverableContract.GoalCompletionIntent;
import com.skillforge.institution.artifact.DeliverableContract.Publication;
import com.skillforge.institution.runtime.AgentRuntimeGoalDriver.GoalRun;
import com.skillforge.institution.runtime.AgentRuntimeGoalDriver.RoundAdmission;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.SessionGoal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AgentRuntimeGoalDriverTest {
    @Test void initialGoalControlsWaitForCommittedCreationAndOnlyThenWake() throws Exception {
        var log = new Log(); var driver = driver(log); var callback = new Callback();
        var input = new InputId("goal-start:create-controlled"); var run = identity(input, new RunRef(TENANT,"run:"+input.value()));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var control = new java.util.concurrent.FutureTask<Void>(() -> { entered.countDown(); driver.requireExecution(run); return null; });
        var woken = new ArrayList<RunRef>();
        callback.wake = value -> { assertTrue(driver.dispatchAllowed(run)); woken.add(value); };
        assertFalse(driver.dispatchAllowed(run));
        driver.createFromAdmission(OWNER,AGENT,"create-controlled","完成目标",2,callback,()->{
            Thread.ofPlatform().daemon(true).name("goal-control-fixture").start(control);
            try { assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            assertFalse(control.isDone()); assertTrue(woken.isEmpty());
            return run;
        });
        assertNull(control.get(5,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(List.of(run.runRef()),woken);
    }

    @Test void currentGoalGatePreservesYieldAndFinalResponseButRejectsRestartOrPause() {
        var log = new Log(); var driver = driver(log); var callback = new Callback();
        var input = new InputId("goal-start:create-gate"); var run = identity(input,new RunRef(TENANT,"run:"+input.value()));
        var created = driver.create(run,"create-gate","完成目标",2,callback);
        assertTrue(driver.dispatchAllowed(INITIAL)); assertTrue(driver.dispatchAllowed(run));
        driver.yieldToUser(OWNER); assertTrue(driver.dispatchAllowed(run));
        assertFalse(driver(log).dispatchAllowed(run));
        assertThrows(com.skillforge.runtime.session.spi.ExecutionStore.Rejection.class,()->driver(log).requireExecution(run));
        driver.update(run,"complete-gate",created.goal().revision(),SessionGoal.State.COMPLETE,null);
        assertTrue(driver.dispatchAllowed(run));
        var pausedLog = new Log(); var pausedDriver = driver(pausedLog); var pausedCallback = new Callback();
        var active = pausedDriver.create(run,"create-gate","完成目标",2,pausedCallback);
        pausedDriver.pause(OWNER,AGENT,active.goal().revision(),"pause-gate",new InputId("pause-gate"),trace(new InputId("pause-gate")),"user_paused",pausedCallback);
        assertFalse(pausedDriver.dispatchAllowed(run));
    }

    @Test void roundGuardRejectsReservationUntilAcceptCommitsBeforeWake() {
        var log = new Log(); var driver = driver(log); var callback = new Callback();
        callback.latest = value -> Optional.of(completed(value)); var woken = new ArrayList<RunRef>();
        driver.create(INITIAL,"create-round-gate","完成目标",2,callback);
        callback.submit = input -> {
            var run = new RunRef(TENANT,"run:"+input.value()); assertFalse(driver.dispatchAllowed(identity(input,run)));
            return accepted(input,run);
        };
        callback.wake = value -> {
            var accepted = driver.read(OWNER).orElseThrow().goal();
            assertEquals(Optional.of(value),accepted.lastRunId());assertTrue(accepted.pendingRoundInputId().isEmpty());
            assertTrue(driver.dispatchAllowed(identity(new InputId(value.runId().substring("run:".length())),value)));woken.add(value);
        };
        driver.driveEnabledGoals();assertEquals(1,woken.size());
    }

    @Test void explicitResumeAfterRestartRestoresOnlyOriginalNonterminalRunWithoutAnotherAdmission() {
        var log=new Log();var initialDriver=driver(log);var input=new InputId("goal-start:resume-existing");
        var run=identity(input,new RunRef(TENANT,"run:"+input.value()));var callback=new Callback();
        var created=initialDriver.create(run,"resume-existing","完成目标",2,callback);
        var restarted=driver(log);var woken=new ArrayList<RunRef>();callback.latest=value->Optional.of(running(value));
        callback.wake=value->{assertTrue(restarted.dispatchAllowed(run));woken.add(value);};
        assertFalse(restarted.dispatchAllowed(run));
        var resumeInput=new InputId("explicit-restart-resume");
        restarted.resume(OWNER,AGENT,created.goal().revision(),resumeInput.value(),resumeInput,trace(resumeInput),callback);
        assertTrue(restarted.dispatchAllowed(run));assertEquals(List.of(run.runRef()),woken);assertEquals(0,callback.submissions);
        restarted.driveEnabledGoals();assertEquals(0,callback.submissions);
    }

    @Test
    void lastAllowedRoundPublishesAndCompletesWithoutRoundLimitOrAnotherSubmission() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.latest = run -> Optional.of(completed(run));
        var created = driver.create(INITIAL, "last-allowed-round", "在最后一轮交付成果", 1, callback);
        driver.driveEnabledGoals(); // 通过真实 reserve/accept 日志推进到唯一允许的续行轮。
        var last = driver.read(OWNER).orElseThrow().goal();
        assertEquals(1, last.maxRounds());
        assertEquals(1, last.roundsStarted());
        assertEquals(3, last.revision());
        assertEquals(1, callback.submissions);
        var lastInput = new InputId("goal-round:" + created.goal().goalId() + ":1:1");
        var lastRun = identity(lastInput, last.lastRunId().orElseThrow());
        var sourceBudget = log.budgets.find(INITIAL.runRef()).orElseThrow();
        var continuedBudget = log.budgets.find(lastRun.runRef()).orElseThrow();
        assertEquals(sourceBudget.root(), continuedBudget.root(), "Goal 续轮沿用真实根账");
        assertEquals(sourceBudget.policy(), continuedBudget.policy(), "续轮不能重置冻结预算");
        assertTrue(driver.executionAllowed(lastRun));
        var intent = driver.captureCompletion(lastRun, last.revision());
        callback.completionPending = true;
        driver.driveEnabledGoals();
        driver.driveEnabledGoals();
        assertEquals(last, driver.read(OWNER).orElseThrow().goal());
        assertEquals(3, log.size(), "最后一轮 pending 不能调用 reserve 写入 round_limit");
        assertEquals(1, callback.submissions);
        var publication = new Publication(lastRun.runRef().runId(), "task-result:" + lastRun.runRef().runId(),
                "published", null, List.of("deliverable:last-revision"));
        var completion = new GoalCompletion(lastRun, intent, RunStatus.COMPLETED, publication);
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.SETTLED, driver.settleCompletion(completion));
        driver.driveEnabledGoals();
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.SETTLED, driver.settleCompletion(completion));
        var completed = driver.read(OWNER).orElseThrow();
        assertEquals(SessionGoal.State.COMPLETE, completed.goal().state());
        assertEquals(1, completed.goal().roundsStarted());
        assertEquals(4, completed.goal().revision());
        assertFalse(completed.continuationEnabled());
        assertEquals(1, callback.submissions, "发布与 Goal 完成不能再调模型做确认");
        assertEquals(4, log.size());
    }

    @Test
    void finalizationCommittedWhileReadingTerminalRunCannotLaunchOrPauseTheGoal() {
        for (RunStatus terminal : List.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TERMINATED)) {
            var log = new Log();
            var driver = driver(log);
            var callback = new Callback();
            var created = driver.create(INITIAL, "read-race-" + terminal, "交付文件", 1, callback);
            var accepted = new GoalCompletionIntent[1];
            callback.latest = run -> {
                // 确定性重现：驱动开始读取 Run 后，冻结事务先提交，随后本轮成为终态。
                accepted[0] = driver.captureCompletion(INITIAL, 1);
                callback.completionPending = true;
                return Optional.of(switch (terminal) {
                    case COMPLETED -> completed(run);
                    case FAILED -> failed(run);
                    case CANCELLED -> cancelled(run);
                    case TERMINATED -> new GoalRun(OWNER, run, RunStatus.TERMINATED);
                    default -> throw new AssertionError(terminal);
                });
            };
            driver.driveEnabledGoals();
            assertNotNull(accepted[0]);
            assertEquals(1, accepted[0].expectedRevision());
            assertEquals(created.goal(), driver.read(OWNER).orElseThrow().goal(), terminal.name());
            assertEquals(0, callback.submissions, terminal.name());
            assertTrue(callback.cancelled.isEmpty(), terminal.name());
            assertEquals(1, log.size(), "不能写 pause/reserve 或 round_limit 事件");
        }
    }

    @Test
    void pendingOutputIntentStopsBothSuccessfulContinuationAndFailurePause() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.completionPending = true;
        callback.latest = run -> Optional.of(completed(run));
        var created = driver.create(INITIAL, "pending-output", "发布文件", 2, callback);
        driver.driveEnabledGoals();
        callback.latest = run -> Optional.of(failed(run));
        driver.driveEnabledGoals();
        assertEquals(0, callback.submissions);
        assertEquals(created.goal(), driver.read(OWNER).orElseThrow().goal());
        assertEquals(1, log.size());
    }

    @Test
    void transientCompletionLookupFailureWaitsWithoutPausingOrLaunchingGoal() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.latest = run -> Optional.of(completed(run));
        callback.completionReadFailure = new IllegalStateException("temporary storage failure");
        var created = driver.create(INITIAL, "unavailable-output-intent", "发布文件", 2, callback);
        driver.driveEnabledGoals();
        driver.driveEnabledGoals();
        assertEquals(created.goal(), driver.read(OWNER).orElseThrow().goal());
        assertTrue(driver.read(OWNER).orElseThrow().continuationEnabled());
        assertEquals(0, callback.submissions);
        assertEquals(1, log.size());
        callback.completionReadFailure = null;
        callback.completionPending = true;
        driver.driveEnabledGoals();
        assertEquals(0, callback.submissions);
    }

    @Test
    void publishedIntentCompletesWithoutRegistrationAndReplaysAfterNewGoal() {
        var log = new Log();
        var original = driver(log);
        var callback = new Callback();
        original.create(INITIAL, "publish-intent", "发布文件", 2, callback);
        var intent = original.captureCompletion(INITIAL, 1);
        var completion = new GoalCompletion(INITIAL, intent, RunStatus.COMPLETED,
                new Publication(INITIAL.runRef().runId(), "task-result:" + INITIAL.runRef().runId(),
                        "published", null, List.of("deliverable:revision")));
        var restarted = driver(log);
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.SETTLED, restarted.settleCompletion(completion));
        assertEquals(SessionGoal.State.COMPLETE, restarted.read(OWNER).orElseThrow().goal().state());
        assertFalse(restarted.read(OWNER).orElseThrow().continuationEnabled());
        var replacement = restarted.create(INITIAL, "replacement-goal", "下一目标", 2, callback);
        restarted.yieldToUser(OWNER);
        int beforeReplay = log.size();
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.SETTLED, restarted.settleCompletion(completion));
        assertEquals(replacement.goal(), restarted.read(OWNER).orElseThrow().goal());
        assertFalse(restarted.read(OWNER).orElseThrow().continuationEnabled());
        assertEquals(beforeReplay, log.size());
        assertEquals(0, callback.submissions);
    }

    @Test
    void explicitPauseSupersedesIntentAndStablePublicationFailureBlocksGoal() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        driver.create(INITIAL, "pause-intent", "发布文件", 2, callback);
        var intent = driver.captureCompletion(INITIAL, 1);
        callback.latest = run -> Optional.of(completed(run));
        var pauseInput = new InputId("explicit-output-pause");
        var paused = driver.pause(OWNER, AGENT, 1, pauseInput.value(), pauseInput,
                trace(pauseInput), "user_paused", callback);
        var failedPublication = new Publication(INITIAL.runRef().runId(), "task-result:initial",
                "failed", "publication_validation_failed", List.of());
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.SUPERSEDED,
                driver.settleCompletion(new GoalCompletion(INITIAL, intent, RunStatus.COMPLETED, failedPublication)));
        assertEquals(paused.goal(), driver.read(OWNER).orElseThrow().goal());

        var secondLog = new Log();
        var second = driver(secondLog);
        second.create(INITIAL, "failed-intent", "发布文件", 2, new Callback());
        var secondIntent = second.captureCompletion(INITIAL, 1);
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.SETTLED,
                second.settleCompletion(new GoalCompletion(INITIAL, secondIntent, RunStatus.COMPLETED, failedPublication)));
        var blocked = second.read(OWNER).orElseThrow();
        assertEquals(SessionGoal.State.BLOCKED, blocked.goal().state());
        assertEquals(Optional.of("publication_validation_failed"), blocked.goal().reason());
        assertFalse(blocked.continuationEnabled());
    }

    @Test
    void completionWaitsForBothRunAndPublicationAndRejectsStaleCapture() {
        var log = new Log();
        var driver = driver(log);
        driver.create(INITIAL, "await-facts", "发布文件", 2, new Callback());
        var intent = driver.captureCompletion(INITIAL, 1);
        var pending = new Publication(INITIAL.runRef().runId(), "task-result:initial", "pending", null, List.of());
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.WAITING,
                driver.settleCompletion(new GoalCompletion(INITIAL, intent, RunStatus.RUNNING, pending)));
        assertEquals(AgentRuntimeGoalDriver.CompletionSettlement.WAITING,
                driver.settleCompletion(new GoalCompletion(INITIAL, intent, RunStatus.COMPLETED, pending)));
        assertEquals(SessionGoal.Reason.REVISION_CONFLICT, assertThrows(SessionGoal.Rejected.class,
                () -> driver.captureCompletion(INITIAL, 2)).reason());
        assertEquals(SessionGoal.State.ACTIVE, driver.read(OWNER).orElseThrow().goal().state());
        assertEquals(1, log.size());
    }
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant");
    private static final AgentId AGENT = new AgentId("agent");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"),
            AGENT, new EndUserId(7));
    private static final InputId INPUT = new InputId("initial");
    private static final RunIdentity INITIAL = identity(INPUT, new RunRef(TENANT, "initial-run"));

    @Test
    void activeGoalDoesNotResumeAfterDriverRestartWithoutRegistration() {
        var log = new Log();
        new SessionGoal(log).create(INITIAL, "create", "完成目标", 2, NOW);
        var callback = new Callback();
        callback.latest = ignored -> Optional.of(completed(INITIAL.runRef()));
        var restarted = driver(log);

        restarted.driveEnabledGoals();

        assertEquals(0, callback.submissions);
        assertFalse(restarted.read(OWNER).orElseThrow().continuationEnabled());
        assertEquals(1, restarted.read(OWNER).orElseThrow().goal().revision());
    }

    @Test
    void failedLatestRunPausesAndDisablesContinuation() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        driver.create(INITIAL, "create", "完成目标", 2, callback);

        driver.driveEnabledGoals();
        assertEquals(SessionGoal.State.ACTIVE, driver.read(OWNER).orElseThrow().goal().state(),
                "ACCEPTED Run 暂不可见时只等待，不得误判失败");
        assertTrue(driver.read(OWNER).orElseThrow().continuationEnabled());

        callback.latest = ignored -> Optional.of(failed(INITIAL.runRef()));
        driver.driveEnabledGoals();

        var status = driver.read(OWNER).orElseThrow();
        assertEquals(SessionGoal.State.PAUSED, status.goal().state());
        assertEquals(Optional.of("run_failed"), status.goal().reason());
        assertFalse(status.continuationEnabled());
        assertEquals(0, callback.submissions);
    }

    @Test
    void rejectedSubmissionNeverAcceptsOrConsumesReservedRound() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.latest = ignored -> Optional.of(completed(INITIAL.runRef()));
        callback.submit = ignored -> { throw new IllegalStateException("submission_failed"); };
        driver.create(INITIAL, "create", "完成目标", 2, callback);

        driver.driveEnabledGoals();

        var status = driver.read(OWNER).orElseThrow();
        assertEquals(SessionGoal.State.PAUSED, status.goal().state());
        assertEquals(0, status.goal().roundsStarted());
        assertTrue(status.goal().pendingRoundInputId().isEmpty());
        assertFalse(status.continuationEnabled());
        assertEquals(1, callback.submissions);
    }

    /** 同Session的其他用户、其他Run均不能作为Goal前轮事实驱动续轮。 */
    @Test
    void latestRunRequiresFullOwnerAndExactRunIdentity() {
        var foreignOwner = SessionOwner.user(OWNER.sessionRef(), AGENT, new EndUserId(8));
        for (var observed : List.of(new GoalRun(foreignOwner, INITIAL.runRef(), RunStatus.COMPLETED),
                new GoalRun(OWNER, new RunRef(TENANT,"other-run"), RunStatus.COMPLETED))) {
            var log = new Log();
            var driver = driver(log);
            var callback = new Callback();
            callback.latest = ignored -> Optional.of(observed);
            driver.create(INITIAL,"strict-owner","完成目标",2,callback);

            driver.driveEnabledGoals();

            var status = driver.read(OWNER).orElseThrow();
            assertEquals(SessionGoal.State.PAUSED,status.goal().state());
            assertEquals(Optional.of("driver_error"),status.goal().reason());
            assertFalse(status.continuationEnabled());
            assertEquals(0,callback.submissions);
        }
    }

    /** 只接受当前续轮的精确准入；拒绝或串线回执不能使预留轮次变成已启动。 */
    @Test
    void rejectedOrMismatchedRoundAdmissionCannotConsumeReservation() {
        var submissions = List.<Function<InputId,RoundAdmission>>of(
                input -> new RoundAdmission(OWNER.sessionRef(),input,Optional.empty(),false),
                input -> new RoundAdmission(new SessionRef(TENANT,"other-session"),input,
                        Optional.of(new RunRef(TENANT,"run:"+input.value())),true),
                input -> new RoundAdmission(OWNER.sessionRef(),new InputId("other-input"),
                        Optional.of(new RunRef(TENANT,"run:"+input.value())),true),
                input -> new RoundAdmission(OWNER.sessionRef(),input,Optional.of(new RunRef(TENANT,"other-run")),true));
        for (var submission : submissions) {
            var log = new Log();
            var driver = driver(log);
            var callback = new Callback();
            callback.latest = ignored -> Optional.of(completed(INITIAL.runRef()));
            callback.submit = submission;
            driver.create(INITIAL,"strict-admission","完成目标",2,callback);

            driver.driveEnabledGoals();

            var status = driver.read(OWNER).orElseThrow();
            assertEquals(SessionGoal.State.PAUSED,status.goal().state());
            assertEquals(0,status.goal().roundsStarted());
            assertTrue(status.goal().pendingRoundInputId().isEmpty());
            assertFalse(status.continuationEnabled());
            assertEquals(1,callback.submissions);
        }
    }

    @Test
    void reserveCasLossOnlyDisablesThisProcessWithoutPausingWinner() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.latest = ignored -> Optional.of(completed(INITIAL.runRef()));
        driver.create(INITIAL, "create", "完成目标", 2, callback);
        log.conflictNextAppend();

        driver.driveEnabledGoals();

        var status = driver.read(OWNER).orElseThrow();
        assertEquals(SessionGoal.State.ACTIVE, status.goal().state());
        assertEquals(1, status.goal().revision());
        assertFalse(status.continuationEnabled());
        assertEquals(0, callback.submissions);
    }

    @Test
    void userPauseDisablesAndCancelsCurrentNonTerminalRun() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.latest = run -> Optional.of(running(run));
        var created = driver.create(INITIAL, "create", "完成目标", 2, callback);

        var paused = driver.pause(OWNER, AGENT, created.goal().revision(), "pause", new InputId("pause"),
                trace(new InputId("pause")), "user_cancelled", callback);
        driver.driveEnabledGoals();

        assertEquals(SessionGoal.State.PAUSED, paused.goal().state());
        assertFalse(paused.continuationEnabled());
        assertEquals(List.of(INITIAL.runRef()), callback.cancelled);
        assertEquals(0, callback.submissions);
    }

    @Test
    void yieldingStopsSchedulingButKeepsAcceptedRoundExecutionPermitUntilPause() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        var snapshots = new HashMap<RunRef, GoalRun>();
        snapshots.put(INITIAL.runRef(), completed(INITIAL.runRef()));
        callback.latest = run -> Optional.ofNullable(snapshots.get(run));
        callback.submit = input -> {
            var run = new RunRef(TENANT, "run:" + input.value());
            snapshots.put(run, running(run));
            return accepted(input, run);
        };
        driver.create(INITIAL, "create", "完成目标", 2, callback);
        driver.driveEnabledGoals();
        var acceptedGoal = driver.read(OWNER).orElseThrow().goal();
        InputId roundInput = new InputId("goal-round:" + acceptedGoal.goalId() + ":1:1");
        RunRef roundRun = new RunRef(TENANT, "run:" + roundInput.value());
        RunIdentity round = identity(roundInput, roundRun);
        assertTrue(driver.executionAllowed(round));

        driver.yieldToUser(OWNER);
        assertFalse(driver.read(OWNER).orElseThrow().continuationEnabled());
        assertTrue(driver.executionAllowed(round));

        driver.pause(OWNER, AGENT, acceptedGoal.revision(), "pause", new InputId("pause"),
                trace(new InputId("pause")), "user_cancelled", callback);
        assertFalse(driver.executionAllowed(round));
        assertEquals(List.of(roundRun), callback.cancelled);
    }

    @Test
    void activeResumeUsesOneDomainChangeAndReplaysOriginalExpectedRevision() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        var created = driver.create(INITIAL, "create", "恢复活动目标", 2, callback);
        var reservation = new SessionGoal(log).reserveRound(OWNER, AGENT, created.goal().revision(), "reserve",
                new InputId("pending"), trace(new InputId("pending")), NOW);

        var resumed = driver.resume(OWNER, AGENT, reservation.revision(), "resume-active", new InputId("resume"),
                trace(new InputId("resume")), callback);
        assertEquals(reservation.revision() + 1, resumed.goal().revision());
        assertTrue(resumed.goal().pendingRoundInputId().isEmpty());
        assertEquals(3, log.size());

        var replayed = driver.resume(OWNER, AGENT, reservation.revision(), "resume-active", new InputId("resume"),
                trace(new InputId("resume")), callback);
        assertEquals(resumed.goal(), replayed.goal());
        assertEquals(3, log.size());
    }

    @Test
    void explicitResumeWaitsForCancelledPredecessorThenLaunchesNextRound() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        callback.latest = run -> Optional.of(running(run));
        var created = driver.create(INITIAL, "create-cancelled", "恢复已取消轮次", 2, callback);
        var paused = driver.pause(OWNER, AGENT, created.goal().revision(), "pause-cancelled",
                new InputId("pause-cancelled"), trace(new InputId("pause-cancelled")),
                "user_cancelled", callback);
        callback.latest = run -> Optional.of(cancelled(run));
        callback.idle = false;

        var resumed = driver.resume(OWNER, AGENT, paused.goal().revision(), "resume-cancelled",
                new InputId("resume-cancelled"), trace(new InputId("resume-cancelled")), callback);
        driver.driveEnabledGoals();

        assertEquals(SessionGoal.State.ACTIVE, resumed.goal().state());
        assertTrue(driver.read(OWNER).orElseThrow().continuationEnabled());
        assertEquals(0, callback.submissions, "取消尚未完全空闲时必须等待");

        callback.idle = true;
        driver.driveEnabledGoals();

        assertEquals(1, callback.submissions);
        assertEquals(1, driver.read(OWNER).orElseThrow().goal().roundsStarted());
    }

    @Test
    void explicitResumeBypassesFailedPredecessorOnlyOnce() {
        var log = new Log();
        new SessionGoal(log).create(INITIAL, "create-failed-resume", "恢复失败轮次", 2, NOW);
        var driver = driver(log);
        var callback = new Callback();
        var snapshots = new HashMap<RunRef, GoalRun>();
        snapshots.put(INITIAL.runRef(), failed(INITIAL.runRef()));
        callback.latest = run -> Optional.ofNullable(snapshots.get(run));
        callback.submit = input -> {
            var run = new RunRef(TENANT, "run:" + input.value());
            snapshots.put(run, running(run));
            return accepted(input, run);
        };

        var resumed = driver.resume(OWNER, AGENT, 1, "resume-failed", new InputId("resume-failed"),
                trace(new InputId("resume-failed")), callback);
        driver.driveEnabledGoals();

        InputId roundInput = new InputId("goal-round:" + resumed.goal().goalId() + ":1:2");
        RunRef roundRun = new RunRef(TENANT, "run:" + roundInput.value());
        assertEquals(1, callback.submissions);
        assertTrue(driver.executionAllowed(identity(roundInput, roundRun)));

        snapshots.put(roundRun, failed(roundRun));
        driver.driveEnabledGoals();

        var stopped = driver.read(OWNER).orElseThrow();
        assertEquals(SessionGoal.State.PAUSED, stopped.goal().state());
        assertEquals(Optional.of("run_failed"), stopped.goal().reason());
        assertFalse(stopped.continuationEnabled());
        assertFalse(driver.executionAllowed(identity(roundInput, roundRun)));
    }

    @Test
    void sameResumeCommandReplayPreservesExecutionPermitAndYield() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        var snapshots = new HashMap<RunRef, GoalRun>();
        snapshots.put(INITIAL.runRef(), completed(INITIAL.runRef()));
        callback.latest = run -> Optional.ofNullable(snapshots.get(run));
        callback.submit = input -> {
            var run = new RunRef(TENANT, "run:" + input.value());
            snapshots.put(run, running(run));
            return accepted(input, run);
        };
        var created = driver.create(INITIAL, "create-resume-replay", "恢复命令幂等", 2, callback);
        var pauseInput = new InputId("pause-resume-replay");
        var paused = driver.pause(OWNER, AGENT, created.goal().revision(), "pause-resume-replay",
                pauseInput, trace(pauseInput), "user_paused", callback);
        var resumeInput = new InputId("resume-replay");
        var resumed = driver.resume(OWNER, AGENT, paused.goal().revision(), "resume-replay",
                resumeInput, trace(resumeInput), callback);
        driver.driveEnabledGoals();

        InputId roundInput = new InputId("goal-round:" + resumed.goal().goalId() + ":1:3");
        RunRef roundRun = new RunRef(TENANT, "run:" + roundInput.value());
        RunIdentity round = identity(roundInput, roundRun);
        assertTrue(driver.executionAllowed(round));

        var replayed = driver.resume(OWNER, AGENT, paused.goal().revision(), "resume-replay",
                resumeInput, trace(resumeInput), callback);
        assertTrue(replayed.continuationEnabled());
        assertTrue(driver.executionAllowed(round));

        driver.yieldToUser(OWNER);
        var replayedAfterYield = driver.resume(OWNER, AGENT, paused.goal().revision(), "resume-replay",
                resumeInput, trace(resumeInput), callback);
        assertFalse(replayedAfterYield.continuationEnabled());
        assertTrue(driver.executionAllowed(round));
        assertEquals(1, callback.submissions);
    }

    @Test
    void sameCreateCommandReplayPreservesRegistrationAndDoesNotRestoreAfterRestart() {
        var log = new Log();
        var driver = driver(log);
        var callback = new Callback();
        var snapshots = new HashMap<RunRef, GoalRun>();
        snapshots.put(INITIAL.runRef(), completed(INITIAL.runRef()));
        callback.latest = run -> Optional.ofNullable(snapshots.get(run));
        callback.submit = input -> {
            var run = new RunRef(TENANT, "run:" + input.value());
            snapshots.put(run, running(run));
            return accepted(input, run);
        };
        var created = driver.create(INITIAL, "create-replay", "创建命令幂等", 2, callback);
        driver.driveEnabledGoals();

        InputId roundInput = new InputId("goal-round:" + created.goal().goalId() + ":1:1");
        RunRef roundRun = new RunRef(TENANT, "run:" + roundInput.value());
        RunIdentity round = identity(roundInput, roundRun);
        assertTrue(driver.executionAllowed(round));

        var replayed = driver.create(INITIAL, "create-replay", "创建命令幂等", 2, callback);
        assertTrue(replayed.continuationEnabled());
        assertTrue(driver.executionAllowed(round));

        driver.yieldToUser(OWNER);
        var replayedAfterYield = driver.create(INITIAL, "create-replay", "创建命令幂等", 2, callback);
        assertFalse(replayedAfterYield.continuationEnabled());
        assertTrue(driver.executionAllowed(round));

        var restarted = driver(log);
        var replayedAfterRestart = restarted.create(
                INITIAL, "create-replay", "创建命令幂等", 2, callback);
        assertFalse(replayedAfterRestart.continuationEnabled());
        assertFalse(restarted.executionAllowed(round));
        assertEquals(1, callback.submissions);
    }

    private static AgentRuntimeGoalDriver driver(Log log) {
        return new AgentRuntimeGoalDriver(log, Clock.fixed(NOW, ZoneOffset.UTC),
                (goal, input) -> "<goal_round>{\"goal_id\":\"" + goal.goalId()
                        + "\",\"input_id\":\"" + input.value() + "\"}</goal_round>", log.budgets);
    }

    private static RunIdentity identity(InputId input, RunRef run) {
        return new RunIdentity(OWNER, new AgentId((OWNER).target().id()), input, run, trace(input), new ResultRef("verified"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (input).value()));
    }

    private static TraceEnvelope trace(InputId input) {
        return new TraceEnvelope("trace:" + input.value(), "span:" + input.value(), input, Optional.empty());
    }

    private static GoalRun completed(RunRef run) {
        return new GoalRun(OWNER, run, RunStatus.COMPLETED);
    }

    private static GoalRun failed(RunRef run) {
        return new GoalRun(OWNER, run, RunStatus.FAILED);
    }

    private static GoalRun cancelled(RunRef run) {
        return new GoalRun(OWNER, run, RunStatus.CANCELLED);
    }

    private static GoalRun running(RunRef run) {
        return new GoalRun(OWNER, run, RunStatus.RUNNING);
    }

    private static RoundAdmission accepted(InputId input, RunRef run) {
        return new RoundAdmission(OWNER.sessionRef(), input, Optional.of(run), true);
    }

    private static final class Callback implements AgentRuntimeGoalDriver.Callback {
        private java.util.function.Consumer<RunRef> wake = ignored -> { };
        private Function<RunRef, Optional<GoalRun>> latest = ignored -> Optional.empty();
        private Function<InputId, RoundAdmission> submit = input -> accepted(input,
                new RunRef(TENANT, "run:" + input.value()));
        private boolean idle = true;
        private boolean completionPending;
        private RuntimeException completionReadFailure;
        private int submissions;
        private final List<RunRef> cancelled = new ArrayList<>();

        @Override public Optional<GoalRun> latestRun(RunRef runRef) { return latest.apply(runRef); }
        @Override public boolean idle() { return idle; }
        @Override public boolean completionPending(RunRef run) {
            if (completionReadFailure != null) throw completionReadFailure;
            return completionPending;
        }
        @Override public RoundAdmission submitRound(InputId inputId, String prompt) {
            submissions++;
            return submit.apply(inputId);
        }
        @Override public void wake(RunRef runRef) { wake.accept(runRef); }
        @Override public void cancel(RunRef runRef) { cancelled.add(runRef); }
    }

    private static final class Log implements SessionLogPort {
        private final com.skillforge.runtime.api.port.ExecutionBudgetPort budgets = BudgetTestFacts.ledger(
                BudgetTestFacts.registration(INITIAL.runRef()));
        private final Map<Key, AppendSessionEvent> commands = new HashMap<>();
        private final List<CommittedSessionEvent> events = new ArrayList<>();
        private boolean conflictNext;

        synchronized int size() { return events.size(); }
        synchronized void conflictNextAppend() { conflictNext = true; }

        @Override
        public synchronized AppendResult append(AppendSessionEvent command) {
            if (conflictNext) {
                conflictNext = false;
                return new AppendResult.Conflict(new PortConflict(
                        "session_event", command.event().eventId().value(), new ResultRef("audit")));
            }
            var key = new Key(command.target().owner(), command.event().eventId());
            AppendSessionEvent priorCommand = commands.get(key);
            if (priorCommand != null) {
                CommittedSessionEvent prior = events.stream().filter(event -> event.owner().equals(key.owner())
                        && event.eventId().equals(key.eventId())).findFirst().orElseThrow();
                if (priorCommand.equals(command)) return new AppendResult.Replayed(prior);
                return new AppendResult.Conflict(new PortConflict(
                        "session_event", command.event().eventId().value(), new ResultRef("audit")));
            }
            var target = command.target(); var draft = command.event();
            var committed = new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(),
                    target.executionRef(), target.actionRef(), target.delegationRef(), draft.eventId(),
                    events.size() + 1L, draft.eventType(), draft.eventVersion(), draft.trace(),
                    draft.payload(), draft.occurredAt());
            commands.put(key, command); events.add(committed);
            return new AppendResult.Appended(committed);
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
