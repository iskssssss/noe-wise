package com.skillforge.runtime.session.dag;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.*;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 通过真实执行屏障证明任务重叠、及时推进和重复完成事件下的稳定准入。 */
class DagCoordinatorTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final AgentId AGENT = new AgentId("7");
    private static final DagWork.Key KEY = new DagWork.Key(SessionOwner.user(new SessionRef(TENANT, "team-session"),
            SessionTarget.team("team"), new EndUserId(8L)), new InputId("work-input"));
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void driverDefersStaleClosureUntilNextTickWithoutRepeatingMemberExecution() {
        var store = new Store(work(new DagPlan(1, false, List.of(task("T1", false)))));
        var access = new Access() {
            @Override public void requireClosure(DagWork work, ResultRef answer) {
                assertEquals(new ResultRef("work-results:" + work.revision()), answer);
            }
        };
        var coordinator = new DagCoordinator(store, access, Duration.ofMinutes(1));
        coordinator.advance(KEY, "admit", NOW);
        complete(coordinator, store, "T1", "done", DagWork.TaskStatus.COMPLETED);
        var finishes = new AtomicInteger();
        var runs = new DagDriver.Runs() {
            public void ensureAccepted(DagWork work, DagWork.Execution execution) { fail("成员已完成"); }
            public Optional<DagCoordinator.Completion> observation(DagWork work, DagWork.Execution execution) {
                fail("成员已完成"); return Optional.empty();
            }
            public void requestCancel(DagWork work, DagWork.Execution execution) { fail("成员已完成"); }
            public Optional<ResultRef> finish(DagWork work) {
                if (finishes.getAndIncrement() == 0) coordinator.cancel(KEY, "concurrent-cancel");
                return Optional.of(new ResultRef("work-results:" + work.revision()));
            }
        };
        var driver = new DagDriver(store, coordinator, runs, CLOCK);
        assertDoesNotThrow(() -> driver.tick(KEY));
        assertEquals(DagWork.Status.CANCELLING, store.value.status());
        driver.tick(KEY);
        assertEquals(DagWork.Status.CANCELLED, store.value.status());
        assertEquals(2, finishes.get());
    }

    @Test void closureFromOlderSnapshotConflictsBeforeStrictClosureValidation() {
        var store = new Store(work(new DagPlan(1, false, List.of(task("T1", false)))));
        var validations = new AtomicInteger();
        var access = new Access() {
            @Override public void requireClosure(DagWork work, ResultRef answer) {
                validations.incrementAndGet();
                if (!answer.equals(new ResultRef("work-results:" + work.revision()))) {
                    throw new IllegalArgumentException("closure_revision_mismatch");
                }
            }
        };
        var coordinator = new DagCoordinator(store, access, Duration.ofMinutes(1));
        long snapshotRevision = store.value.revision();
        var closure = new ResultRef("work-results:" + snapshotRevision);
        coordinator.cancel(KEY, "concurrent-cancel");
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.close(KEY, "close", snapshotRevision, closure));
        assertEquals(0, validations.get(), "过期快照不进入新版本闭包校验");
        assertEquals(DagWork.Status.CANCELLING, store.value.status());
        assertThrows(IllegalArgumentException.class, () -> coordinator.close(KEY, "invalid-current",
                store.value.revision(), closure));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.close(KEY, "close-current", store.value.revision(),
                new ResultRef("work-results:" + store.value.revision())));
        assertEquals(DagWork.Status.CANCELLED, store.value.status());
    }

    @Test void revisionPersistsOriginalCommandFingerprintAndReplaysAfterAdmissionBeforeGraphValidation() {
        var task = new DagPlan.Task("P1", "整理", AGENT, DagPlan.ExecutorType.PRIMARY,
                "整理资料", List.of(), Set.of("result"), Set.of());
        var store = new Store(work(new DagPlan(1, false, List.of(task))));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        var revised = new DagPlan(2, false, List.of(task));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.revise(KEY, "original", "request-1", 1, revised));
        assertEquals("request-1", store.value.consumedEvents().get("original"));
        coordinator.startPrimary(KEY, "P1", "start", NOW);
        assertEquals(DagWorkPort.Commit.REPLAYED, coordinator.revise(KEY, "original", "request-1", 1, revised));
        assertEquals("plan_command_conflict", assertThrows(IllegalArgumentException.class,
                () -> coordinator.revise(KEY, "original", "changed", 1, revised)).getMessage());
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.revise(KEY, "new", "request-1", 1, revised));
    }

    @Test void primaryStepsShareControlRunButKeepIndependentAdmissionAndFrozenInputs() {
        var first = new DagPlan.Task("P1", "整理", AGENT, DagPlan.ExecutorType.PRIMARY,
                "整理资料", List.of(), Set.of("result"), Set.of());
        var second = new DagPlan.Task("P2", "汇总", AGENT, DagPlan.ExecutorType.PRIMARY,
                "核查资料", List.of(new DagPlan.Input("source", "P1", "result")), Set.of("result"), Set.of());
        var store = new Store(work(new DagPlan(1, true, List.of(first, second))));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.startPrimary(KEY, "P1", "start-p1", NOW));
        coordinator.approve(KEY, "approve", 1);
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.advance(KEY, "advance", NOW));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.startPrimary(KEY, "P1", "start-p1", NOW));
        assertEquals(DagWorkPort.Commit.REPLAYED, coordinator.startPrimary(KEY, "P1", "start-p1", NOW));
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.startPrimary(KEY, "P2", "early-p2", NOW));
        var p1 = store.value.executions().get("P1");
        assertEquals(store.value.request().controlRun(), p1.binding().runRef());
        var done = completion(p1, "p1-done", DagWork.TaskStatus.COMPLETED);
        assertThrows(IllegalArgumentException.class, () -> coordinator.observe(KEY, done));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.finishPrimary(KEY, done));
        assertEquals(DagWorkPort.Commit.REPLAYED, coordinator.finishPrimary(KEY, done));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.startPrimary(KEY, "P2", "start-p2", NOW));
        var p2 = store.value.executions().get("P2");
        assertEquals(p1.binding().runRef(), p2.binding().runRef());
        assertNotEquals(p1.binding().admissionId(), p2.binding().admissionId());
        assertEquals(done.outputs().get("result").resultRef(), p2.binding().inputs().get("source").resultRef());
        assertThrows(IllegalArgumentException.class, () -> coordinator.revise(KEY, "remove-approval", "request", 1,
                new DagPlan(2, false, List.of(first, second))));
    }

    @Test void independentPrimaryStepsCannotHoldTwoActiveScopes() {
        var first = new DagPlan.Task("P1", "分析", AGENT, DagPlan.ExecutorType.PRIMARY,
                "分析资料", List.of(), Set.of("result"), Set.of());
        var second = new DagPlan.Task("P2", "核查", AGENT, DagPlan.ExecutorType.PRIMARY,
                "核查资料", List.of(), Set.of("result"), Set.of());
        var store = new Store(work(new DagPlan(1, false, List.of(first, second))));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.startPrimary(KEY, "P1", "first", NOW));
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.startPrimary(KEY, "P2", "second", NOW));
        assertThrows(IllegalArgumentException.class, () -> coordinator.startPrimary(KEY, "P2", "first", NOW));
    }

    @Test void driverNeverDispatchesOrObservesPrimaryAsAChildRun() {
        var primary = new DagPlan.Task("P1", "主步骤", AGENT, DagPlan.ExecutorType.PRIMARY,
                "整理资料", List.of(), Set.of("result"), Set.of());
        var store = new Store(work(new DagPlan(1, false, List.of(primary))));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        coordinator.startPrimary(KEY, "P1", "start", NOW);
        var calls = new AtomicInteger();
        var runs = new DagDriver.Runs() {
            public void ensureAccepted(DagWork w, DagWork.Execution e) { calls.incrementAndGet(); }
            public Optional<DagCoordinator.Completion> observation(DagWork w, DagWork.Execution e) {
                calls.incrementAndGet(); return Optional.empty();
            }
            public void requestCancel(DagWork w, DagWork.Execution e) { calls.incrementAndGet(); }
            public Optional<ResultRef> finish(DagWork w) { return Optional.empty(); }
        };
        new DagDriver(store, coordinator, runs, CLOCK).tick(KEY);
        assertEquals(0, calls.get());
        assertEquals(DagWork.TaskStatus.RUNNING, store.value.executions().get("P1").status());
    }

    @Test void unplannedWorkStillReachesHostClosureGateAndUnapprovedPlanDoesNotSettle() {
        assertTrue(DagSettlement.settled(work(null)));
        var task = new DagPlan.Task("T1", "取消", AGENT, "取消", "", List.of(), "产出",
                List.of(), Set.of("result"), Set.of(), true, "");
        assertFalse(DagSettlement.settled(work(new DagPlan(1, true, List.of(task)))));
        var store = new Store(work(null));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        var closures = new AtomicInteger();
        var runs = new DagDriver.Runs() {
            public void ensureAccepted(DagWork w, DagWork.Execution e) { fail(); }
            public Optional<DagCoordinator.Completion> observation(DagWork w, DagWork.Execution e) { fail(); return Optional.empty(); }
            public void requestCancel(DagWork w, DagWork.Execution e) { fail(); }
            public Optional<ResultRef> finish(DagWork w) { closures.incrementAndGet(); return Optional.empty(); }
        };
        new DagDriver(store, coordinator, runs, CLOCK).tick(KEY);
        assertEquals(1, closures.get());
    }

    @Test void driverClosesBlockedUnadmittedDescendantsAfterParentFinishesWithoutRedispatching() {
        for (var outcome : List.of(DagWork.TaskStatus.FAILED, DagWork.TaskStatus.CANCELLED,
                DagWork.TaskStatus.OUTCOME_UNKNOWN)) {
            var store = new Store(work(new DagPlan(1, false, List.of(
                    task("T1", false), task("T2", false, "T1"), task("T3", false, "T2")))));
            var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
            coordinator.advance(KEY, "start", NOW);
            complete(coordinator, store, "T1", "t1-terminal", outcome);
            var parentFinished = new java.util.concurrent.atomic.AtomicBoolean();
            var finished = new AtomicInteger();
            var runs = new DagDriver.Runs() {
                public void ensureAccepted(DagWork w, DagWork.Execution e) { fail("blocked work cannot redispatch"); }
                public Optional<DagCoordinator.Completion> observation(DagWork w, DagWork.Execution e) {
                    fail("terminal work cannot be observed again"); return Optional.empty();
                }
                public void requestCancel(DagWork w, DagWork.Execution e) { fail("no cancellation requested"); }
                public Optional<ResultRef> finish(DagWork w) {
                    finished.incrementAndGet();
                    return parentFinished.get() ? Optional.of(new ResultRef("answer")) : Optional.empty();
                }
            };
            var driver = new DagDriver(store, coordinator, runs, CLOCK);
            driver.tick(KEY);
            assertEquals(1, finished.get(), "blocked tasks must reach the host closure gate");
            assertEquals(DagWork.Status.ACTIVE, store.value.status(), "parent still running");
            parentFinished.set(true);
            driver.tick(KEY);
            assertEquals(DagWork.Status.COMPLETED, store.value.status());
            assertEquals(Set.of("T1"), store.value.executions().keySet(), "never invent blocked executions");
            assertEquals(outcome, store.value.executions().get("T1").status());
        }
    }

    @Test void actualBranchesOverlapAndDownstreamStartsWithoutBatchBarrier() throws Exception {
        var store = new Store(work(plan(1, false)));
        var access = new Access();
        var coordinator = new DagCoordinator(store, access, Duration.ofMinutes(1));
        try (var runs = new ControlledRuns()) {
            var driver = new DagDriver(store, coordinator, runs, CLOCK);
            driver.tick(KEY);
            runs.awaitStarted("T1"); runs.finish("T1"); driver.tick(KEY);
            runs.awaitStarted("T2"); runs.awaitStarted("T3");
            assertEquals(2, runs.executing.get(), "两条线程都已进入执行屏障");
            runs.finish("T2"); driver.tick(KEY); runs.awaitStarted("T4");
            assertFalse(runs.done("T3"));
            assertEquals(2, runs.executing.get());
            runs.finish("T4"); driver.tick(KEY);
            assertFalse(runs.started.containsKey("T5"));
            runs.finish("T3");
            try (var completions = Executors.newFixedThreadPool(2)) {
                var first = completions.submit(() -> driver.tick(KEY));
                var second = completions.submit(() -> driver.tick(KEY));
                first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            }
            runs.awaitStarted("T5");
            assertEquals(1, runs.admissions.get("T5").get());
            var original = store.value.executions().get("T5").binding();
            // 重建协调器和派发器，沿用原存储；不能形成第二个有效业务准入。
            new DagDriver(store, new DagCoordinator(store, access, Duration.ofMinutes(1)), runs, CLOCK).tick(KEY);
            assertEquals(original, store.value.executions().get("T5").binding());
            assertEquals(1, runs.admissions.get("T5").get());
            assertEquals(Set.of("T3", "T4"), original.inputs().values().stream()
                    .map(DagWork.BoundInput::sourceTask).collect(java.util.stream.Collectors.toSet()));
            runs.finish("T5"); driver.tick(KEY);
            assertEquals(DagWork.Status.COMPLETED, store.value.status());
        }
    }

    @Test void waitingReleasesPermitAndOldRunResumesWhileNewPlanIsUnapproved() {
        var store = new Store(work(plan(1, true)));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        coordinator.approve(KEY, "approve-1", 1);
        coordinator.advance(KEY, "start-1", NOW);
        complete(coordinator, store, "T1", "t1-done", DagWork.TaskStatus.COMPLETED);
        coordinator.advance(KEY, "start-2", NOW);
        var original = store.value.executions().get("T2").binding();
        complete(coordinator, store, "T2", "t2-wait", DagWork.TaskStatus.WAITING);
        assertTrue(store.value.executions().get("T2").permit().isEmpty());
        // T3 的结果及时登记，新分工修改未准入的 T4；在途 T2 保持 v1 身份。
        complete(coordinator, store, "T3", "t3-done", DagWork.TaskStatus.COMPLETED);
        var changed = new ArrayList<>(plan(2, true).tasks());
        var t4 = changed.get(3);
        changed.set(3, new DagPlan.Task(t4.id(), t4.title(), AGENT, "修订建议", t4.inputs(), t4.outputs(), Set.of()));
        coordinator.revise(KEY, "revision-2", "request", 1, new DagPlan(2, true, changed));
        coordinator.requestResume(KEY, "T2", "resume-2", new ResultRef("control:operation-approved"));
        coordinator.advance(KEY, "resume-permit-2", NOW);
        assertEquals(original, store.value.executions().get("T2").binding());
        assertTrue(store.value.executions().get("T2").permit().isPresent());
        complete(coordinator, store, "T2", "t2-done", DagWork.TaskStatus.COMPLETED);
        coordinator.advance(KEY, "unapproved", NOW);
        assertFalse(store.value.executions().containsKey("T4"));
        coordinator.approve(KEY, "approve-2", 2);
        coordinator.advance(KEY, "start-4", NOW);
        assertEquals(2, store.value.executions().get("T4").binding().planRevision());
        assertEquals(1, original.planRevision());
    }

    @Test void duplicateEventMustMatchFingerprintAndOneDeniedBranchDoesNotBlockOtherBranch() {
        var store = new Store(work(plan(1, false)));
        var access = new Access();
        var coordinator = new DagCoordinator(store, access, Duration.ofMinutes(1));
        coordinator.advance(KEY, "first", NOW);
        var done = completion(store.value.executions().get("T1"), "event", DagWork.TaskStatus.COMPLETED);
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.observe(KEY, done));
        assertEquals(DagWorkPort.Commit.REPLAYED, coordinator.observe(KEY, done));
        assertThrows(IllegalArgumentException.class, () -> coordinator.observe(KEY,
                new DagCoordinator.Completion(done.eventId(), done.taskId(), done.runRef(), done.fence(), done.status(),
                        99, done.outputs(), done.phase(), done.sourceEvent())));
        access.denied = "T2";
        coordinator.advance(KEY, "branches", NOW);
        assertFalse(store.value.executions().containsKey("T2"));
        assertTrue(store.value.executions().containsKey("T3"));
    }

    @Test void replacementKeepsCancelledBindingAndRequiresNewPlanApproval() {
        var first=task("T1",true);
        var store=new Store(work(new DagPlan(1,true,List.of(first))));
        var coordinator=new DagCoordinator(store,new Access(),Duration.ofMinutes(1));
        coordinator.approve(KEY,"approve-original",1);
        coordinator.advance(KEY,"admit-original",NOW);
        var original=store.value.executions().get("T1").binding();
        var replacement=new DagPlan.Task("T1-edit","修订结果",AGENT,"按新要求答复","",List.of(),"完成声明的产出",List.of(),Set.of("result"),Set.of(),false,"T1");
        var revised=new DagPlan(2,true,List.of(first,replacement));
        assertThrows(IllegalArgumentException.class,()->coordinator.revise(KEY,"too-early", "request",1,revised));
        complete(coordinator,store,"T1","cancelled",DagWork.TaskStatus.CANCELLED);
        assertEquals(DagWorkPort.Commit.APPLIED,coordinator.revise(KEY,"replace", "request",1,revised));
        coordinator.advance(KEY,"not-approved",NOW);
        assertFalse(store.value.executions().containsKey("T1-edit"));
        coordinator.approve(KEY,"approve-replacement",2);
        coordinator.advance(KEY,"admit-replacement",NOW);
        assertEquals(original,store.value.executions().get("T1").binding());
        assertEquals(List.of("T1-edit"), store.value.plan().effectiveTasks().stream().map(DagPlan.Task::id).toList());
        assertTrue(store.value.executions().containsKey("T1-edit"));
    }

    @Test void allReadyBranchesAreAdmittedWithoutTeamCapacityOrTokenCeiling() {
        var tasks = new ArrayList<DagPlan.Task>();
        for (int i = 0; i < 20; i++) tasks.add(task("branch-" + i, false));
        tasks.add(task("answer", true, tasks.stream().map(DagPlan.Task::id).toArray(String[]::new)));
        var initial = work(new DagPlan(1, false, tasks));
        // 已用量超过旧默认总额度仍能推进；统计值继续如实累计。
        var store = new Store(new DagWork(initial.key(), initial.coordinator(), initial.revision(), initial.status(),
                initial.plan(), initial.approvedRevision(), 1_000_000, Map.of(), initial.consumedEvents(), initial.request()));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.advance(KEY, "all-branches", NOW));
        assertEquals(20, store.value.executions().size());
        assertFalse(store.value.executions().containsKey("answer"));
        for (int i = 0; i < 20; i++) complete(coordinator, store, "branch-" + i, "done-" + i, DagWork.TaskStatus.COMPLETED);
        coordinator.advance(KEY, "final-answer", NOW);
        assertTrue(store.value.executions().containsKey("answer"));
        assertEquals(1_000_200, store.value.spentTokens());
    }

    @Test void removingTeamCapacityKeepsExclusiveResourcesAndCancellation() {
        var first = new DagPlan.Task("A", "A", AGENT, "更新资源", List.of(), Set.of("result"), Set.of("shared"));
        var second = new DagPlan.Task("B", "B", AGENT, "读取资源", List.of(), Set.of("result"), Set.of("shared"));
        var store = new Store(work(new DagPlan(1, false, List.of(first, second))));
        var coordinator = new DagCoordinator(store, new Access(), Duration.ofMinutes(1));
        coordinator.advance(KEY, "reserve-first", NOW);
        assertEquals(Set.of("A"), store.value.executions().keySet());
        complete(coordinator, store, "A", "a-done", DagWork.TaskStatus.COMPLETED);
        coordinator.advance(KEY, "reserve-second", NOW);
        assertTrue(store.value.executions().containsKey("B"));
        assertTrue(store.value.executions().get("B").permit().isPresent());
        coordinator.cancel(KEY, "cancel-work");
        assertEquals(DagWork.Status.CANCELLING, store.value.status());
        assertEquals(DagWorkPort.Commit.CONFLICT, coordinator.advance(KEY, "no-new-admission", NOW));
        complete(coordinator, store, "B", "b-cancelled", DagWork.TaskStatus.CANCELLED);
        assertTrue(store.value.executions().get("B").permit().isEmpty());
    }

    private static DagPlan plan(long revision, boolean approval) {
        return new DagPlan(revision, approval, List.of(task("T1", false), task("T2", false, "T1"),
                task("T3", false, "T1"), task("T4", false, "T2"), task("T5", true, "T3", "T4")));
    }
    private static DagPlan.Task task(String id, boolean finalAnswer, String... inputs) {
        return new DagPlan.Task(id, id, AGENT, "完成" + id,
                Arrays.stream(inputs).map(source -> new DagPlan.Input(source, source, "result")).toList(),
                Set.of("result"), Set.of());
    }
    private static DagWork work(DagPlan plan) {
        return new DagWork(KEY, AGENT, 1, DagWork.Status.ACTIVE, plan, 0, 0, Map.of(), Map.of("control-usage:control", "control-finished"),
                new DagWork.Request("完成分析", Optional.empty(), new RunRef(TENANT, "control"), ""));
    }
    private static void complete(DagCoordinator coordinator, Store store, String id, String event, DagWork.TaskStatus status) {
        assertEquals(DagWorkPort.Commit.APPLIED, coordinator.observe(KEY, completion(store.value.executions().get(id), event, status)));
    }
    private static DagCoordinator.Completion completion(DagWork.Execution execution, String event, DagWork.TaskStatus status) {
        String id = execution.binding().task().id();
        return new DagCoordinator.Completion(event, id, execution.binding().runRef(), execution.permit().orElseThrow().fence(),
                status, 10, status == DagWork.TaskStatus.COMPLETED
                ? Map.of("result", new DagWork.Output(new ResultRef("result:" + id), "digest:" + id, true)) : Map.of(),
                status.name(), new ResultRef("run-event:" + event));
    }
    private static class Access implements DagCoordinator.Access {
        String denied;
        public void requireExecution(DagWork work, DagPlan.Task task, Map<String,DagWork.BoundInput> inputs, boolean resume) {
            if (task.id().equals(denied)) throw new DagCoordinator.AdmissionDenied("member_disabled");
        }
        public void requireResult(DagWork work, DagWork.Execution execution, DagCoordinator.Completion completion) { }
        public void requireResume(DagWork work, DagWork.Execution execution, ResultRef control) { }
        public void requireControl(DagWork work, DagCoordinator.Control control, String command) { }
        public void requireClosure(DagWork work, ResultRef answer) { }
    }
    private static class Store implements DagWorkPort {
        volatile DagWork value;
        final Map<String,String> commands = new HashMap<>();
        Store(DagWork work) { value = work; }
        public Optional<DagWork> read(DagWork.Key key) { return Optional.of(value); }
        public Optional<DagWork> active(SessionOwner owner) { return Optional.of(value); }
        public List<DagWork.Key> recoverable(TenantId tenant, int limit) { return List.of(KEY); }
        public boolean create(DagWork work, String id, String fingerprint) { throw new UnsupportedOperationException(); }
        public synchronized Commit commit(DagWork.Key key, long revision, String id, String fingerprint, DagWork next, List<Event> events) {
            if (commands.containsKey(id)) return commands.get(id).equals(fingerprint) ? Commit.REPLAYED : Commit.CONFLICT;
            if (value.revision() != revision) return Commit.CONFLICT;
            value = next; commands.put(id, fingerprint); return Commit.APPLIED;
        }
    }
    private static class ControlledRuns implements DagDriver.Runs, AutoCloseable {
        final ExecutorService pool = Executors.newFixedThreadPool(5);
        final Map<String,Future<?>> tasks = new ConcurrentHashMap<>();
        final Map<String,CountDownLatch> started = new ConcurrentHashMap<>();
        final Map<String,CountDownLatch> gates = new ConcurrentHashMap<>();
        final Map<String,AtomicInteger> admissions = new ConcurrentHashMap<>();
        final Map<String,DagCoordinator.Completion> results = new ConcurrentHashMap<>();
        final AtomicInteger executing = new AtomicInteger();
        public void ensureAccepted(DagWork work, DagWork.Execution execution) {
            String id = execution.binding().task().id();
            tasks.computeIfAbsent(id, ignored -> {
                admissions.computeIfAbsent(id, unused -> new AtomicInteger()).incrementAndGet();
                started.put(id, new CountDownLatch(1)); gates.put(id, new CountDownLatch(1));
                return pool.submit(() -> {
                    executing.incrementAndGet(); started.get(id).countDown();
                    try { gates.get(id).await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    finally { executing.decrementAndGet(); }
                    results.put(id, completion(execution, "completed:" + id, DagWork.TaskStatus.COMPLETED));
                });
            });
        }
        void awaitStarted(String id) throws InterruptedException { assertTrue(started.get(id).await(5, TimeUnit.SECONDS)); }
        void finish(String id) throws Exception { gates.get(id).countDown(); tasks.get(id).get(5, TimeUnit.SECONDS); }
        boolean done(String id) { return results.containsKey(id); }
        public Optional<DagCoordinator.Completion> observation(DagWork work, DagWork.Execution execution) {
            return Optional.ofNullable(results.get(execution.binding().task().id()));
        }
        public void requestCancel(DagWork work, DagWork.Execution execution) { tasks.get(execution.binding().task().id()).cancel(true); }
        public Optional<ResultRef> finish(DagWork work) { return work.executions().size() == 5
                && work.executions().values().stream().allMatch(DagWork.Execution::terminal) ? Optional.of(new ResultRef("answer")) : Optional.empty(); }
        public void close() { pool.shutdownNow(); }
    }
}
