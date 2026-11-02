package com.skillforge.runtime.adapter.sqlite;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实文件 SQLite 验证原子派发、恢复和客户端任务竞争；不使用替代内存数据库。 */
class SqliteExecutionStoreTest {
    private static final TenantId TENANT = new TenantId("desktop-local");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final ExecutionBudgetPort.Resources LIMIT = new ExecutionBudgetPort.Resources(20, 1, 20, 0, 100_000, 100_000, 200_000);
    @TempDir Path directory;

    /** 真实SQLite文件按Session事件身份点查，保留旧Run重载边界及跨Session可复用事件名。 */
    @Test void exactSessionEventLookupKeepsRunSessionTenantAndUniqueIdentityBoundaries() {
        var file = directory.resolve("exact-session-event.db"); var clock = clock();
        var session = new SessionRef(TENANT, "exact-event-session");
        try (var store = new SqliteExecutionStore(file, clock); var rebuilt = new SqliteExecutionStore(file, clock)) {
            var first = admission("exact-event-first", false, session); var second = admission("exact-event-second", false, session);
            store.admit(first); store.admit(second);
            var firstId = first.events().getFirst().id(); var secondId = second.events().getFirst().id();
            var firstSaved = store.event(first.run().runRef(), firstId).orElseThrow();
            var secondSaved = store.event(second.run().runRef(), secondId).orElseThrow();
            assertEquals(first.events().getFirst(), firstSaved.event()); assertEquals(first.run().runRef(), firstSaved.run());
            assertEquals(second.events().getFirst(), secondSaved.event()); assertEquals(second.run().runRef(), secondSaved.run());
            assertEquals(1, firstSaved.sequence()); assertEquals(2, secondSaved.sequence());
            // 事件窗口在 SQL 中同时限定上下界、顺序、数量及租户。
            assertEquals(List.of(firstSaved, secondSaved), rebuilt.eventsInRange(session, 0, 2, 100));
            assertEquals(List.of(firstSaved), store.eventsInRange(session, 0, 1, 100));
            assertEquals(List.of(secondSaved), store.eventsInRange(session, 1, 2, 100));
            assertEquals(List.of(firstSaved), store.eventsInRange(session, 0, 2, 1));
            assertTrue(store.eventsInRange(session, 2, 2, 100).isEmpty());
            assertTrue(store.eventsInRange(session, 2, 1, 100).isEmpty());
            assertTrue(store.eventsInRange(new SessionRef(new TenantId("other-tenant"), session.sessionId()), 0, 2, 100).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, -1, 2, 100));
            assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, 0, -1, 100));
            assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, 0, 2, 0));
            assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, 0, 2, 101));
            assertEquals(Optional.of(firstSaved), rebuilt.event(session, firstId));
            assertEquals(Optional.of(secondSaved), rebuilt.event(session, secondId));
            assertTrue(rebuilt.event(first.run().runRef(), secondId).isEmpty());
            assertTrue(rebuilt.event(second.run().runRef(), firstId).isEmpty());
            assertTrue(rebuilt.event(session, new EventId("exact-event-missing")).isEmpty());
            assertTrue(rebuilt.event(first.run().runRef(), new EventId("exact-event-missing")).isEmpty());
            assertTrue(rebuilt.event(new SessionRef(new TenantId("other-tenant"), session.sessionId()), firstId).isEmpty());
            assertTrue(rebuilt.event(new RunRef(new TenantId("other-tenant"), first.run().runRef().runId()), firstId).isEmpty());
            var other = admission("exact-event-other", false, new SessionRef(TENANT, "exact-event-other-session"));
            store.admit(new Admission(other.identity(), other.run(), other.fingerprint(), other.configuration(), other.input(), other.context(),
                    other.budget(), first.events(), other.configurationMaterials()));
            var otherSaved = rebuilt.event(other.run().owner().sessionRef(), firstId).orElseThrow();
            assertEquals(other.run().runRef(), otherSaved.run()); assertEquals(firstSaved.event(), otherSaved.event());
            assertEquals(List.of(otherSaved), rebuilt.eventsInRange(other.run().owner().sessionRef(), 0, 2, 100));
            assertEquals(List.of(firstSaved, secondSaved), rebuilt.eventsInRange(session, 0, 2, 100));
            assertEquals(Optional.of(firstSaved), rebuilt.event(session, firstId));
            assertTrue(rebuilt.event(other.run().owner().sessionRef(), secondId).isEmpty());
            // 复用原唯一约束与appendEvents冲突契约，不允许同Session不同Run出现第二个同名事件。
            var duplicate = admission("exact-event-duplicate", false, session);
            var conflict = new Admission(duplicate.identity(), duplicate.run(), duplicate.fingerprint(), duplicate.configuration(), duplicate.input(),
                    duplicate.context(), duplicate.budget(), first.events(), duplicate.configurationMaterials());
            assertEquals("execution_event_conflict", assertThrows(Rejection.class, () -> store.admit(conflict)).reason());
            assertTrue(rebuilt.load(duplicate.run().runRef()).isEmpty());
            assertEquals(Optional.of(firstSaved), rebuilt.event(session, firstId));
            assertEquals(Optional.of(secondSaved), rebuilt.event(session, secondId));
        }
    }

    @Test void desktopProjectionFillsUnusedActionSlotsWithSavedToolEventsAndCountsExplicitNull() {
        var owner = new SessionRef(TENANT, "saved-tool-session");
        java.util.function.Function<RunRef, EventId> finishedId = run -> new EventId("owned-finished:" + run.runId());
        try (var store = new SqliteExecutionStore(directory.resolve("saved-tool-projection.db"), clock())) {
            var original = admission("saved-tool", false, owner); var config = material("saved-tool-config");
            var source = new ArrayList<Event>();
            for (int index = 0; index < 110; index++) source.add(new Event(new EventId("saved-" + index), index % 2 == 0 ? "tool" : "process", payload("saved-" + index)));
            source.add(new Event(finishedId.apply(original.run().runRef()), "owned-terminal", new TypedPayload("test-event", "{\"text\":null}")));
            store.admit(new Admission(original.identity(), original.run(), original.fingerprint(), config.reference(), original.input(), original.context(), original.budget(), source, List.of(config)));
            var snapshot = store.desktopProjection(owner, 100, 40, 80, 40, finishedId);
            var all = new ArrayList<SavedEvent>(); long after = 0;
            while (true) {
                var page = store.events(owner, after, 100); all.addAll(page);
                if (page.size() < 100) break;
                after = page.getLast().sequence();
            }
            var expected = new ArrayList<>(all.stream().filter(event -> !event.event().type().equals("tool")).toList());
            var tools = all.stream().filter(event -> event.event().type().equals("tool")).toList();
            expected.addAll(tools.subList(tools.size() - 40, tools.size()));
            assertEquals(expected, snapshot.events()); assertEquals(96, snapshot.events().size());
            assertEquals(111, snapshot.totalEvents()); assertEquals(2, snapshot.totalMessages());
            assertEquals(1, snapshot.runs().size()); assertTrue(snapshot.tools().isEmpty());
            assertEquals(finishedId.apply(original.run().runRef()), snapshot.messageCandidates().getFirst().finished().orElseThrow().event().id());
        }
    }

    @Test void desktopProjectionBoundsFactsAndMaterialsWithExactCountsAndOldWaiting() {
        var file = directory.resolve("projection.db"); var clock = clock();
        var owner = new SessionRef(TENANT, "projection-session");
        var shared = material("projection-shared-config");
        var hiddenConfig = new Material(new ResultRef("hidden-config"), new TypedPayload("test-config", "{\"large\":\"" + "hidden-config-body".repeat(12000) + "\"}"));
        var hiddenInput = new Material(new ResultRef("hidden-input"), new TypedPayload("test-input", "{\"large\":\"" + "hidden-input-body".repeat(12000) + "\"}"));
        var hiddenRequest = new Material(new ResultRef("hidden-tool-request"), new TypedPayload("test-request", "{\"large\":\"" + "hidden-tool-body".repeat(12000) + "\"}"));
        java.util.function.Function<RunRef, EventId> finishedId = run -> new EventId("exact-finished:" + run.runId());
        String[] texts = {"\"\"", "\"\\u2003\"", "\"\\u00a0\"", "\"\\u2007\"", "\"\\u202f\"", "null", "42", "[]"};
        try (var store = new SqliteExecutionStore(file, clock)) {
            Run waiting = null; long expectedMessages = 200;
            for (int index = 0; index < 200; index++) {
                var id = "projection-" + String.format(java.util.Locale.ROOT, "%03d", index);
                var original = admission(id, false, owner);
                var configuration = index == 10 ? hiddenConfig : shared;
                var events = new ArrayList<>(original.events());
                events.add(new Event(new EventId("stored-tool-" + id), "tool", payload(id)));
                events.add(new Event(new EventId("wrong-finished-" + id), "execution.completed", new TypedPayload("test-event", "{\"text\":\"wrong identity must not count\"}")));
                var admitted = store.admit(new Admission(original.identity(), original.run(), original.fingerprint(), configuration.reference(),
                        index == 10 ? hiddenInput : original.input(), original.context(), original.budget(), events, List.of(configuration))).run();
                if (index == 0 || index >= 150) {
                    var run = claim(store, admitted, "projection-worker-" + index);
                    var prepared = tool(run, "projection-tool-" + index, 1);
                    if (index == 150) prepared = new Prepared(prepared.action(), prepared.ordinal(), prepared.kind(), prepared.target(), prepared.capability(), hiddenRequest, prepared.fingerprint());
                    var demand = demand(run, prepared, "projection-attempt-" + index);
                    var command = new Dispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), prepared, demand.attemptId(), demand, actor(run), Optional.empty(), List.of());
                    var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(command));
                    if (index == 0) {
                        var context = material("projection-wait-context");
                        waiting = store.complete(new Completion(command.run(), command.attemptId(), ResultKind.INPUT_REQUIRED, material("projection-wait-result"), Optional.empty(), Optional.empty(),
                                ExecutionBudgetPort.Usage.unreported(), registered.run().revision(), registered.run().lease().orElseThrow(), new Progress(0, 0, 0, context.reference()), context, List.of(),
                                Optional.of(new WaitDeclaration(new WaitRef(prepared.action(), 1), WaitKind.TOOL, Map.of("confirm", ToolPortal.ToolInputMethod.ELICITATION_CREATE), Optional.empty())))).run();
                        continue;
                    }
                    admitted = store.complete(toolResult(registered.run(), command.attemptId(), "projection-tool-" + index)).run();
                }
                int variant = index % texts.length;
                if (variant >= 2 && variant <= 6) expectedMessages++;
                var completion = new Event(finishedId.apply(admitted.identity().runRef()), "execution.completed", new TypedPayload("test-event", "{\"text\":" + texts[variant] + "}"));
                store.cancel(new Cancel(actor(admitted), admitted.identity().runRef(), new InputId("finish-" + id), List.of(completion, event("process-" + id))));
            }
            var before = store.runsForSession(owner);
            var allEvents = new ArrayList<SavedEvent>(); long after = 0;
            while (true) {
                var page = store.events(owner, after, 100); allEvents.addAll(page);
                if (page.size() < 100) break;
                after = page.getLast().sequence();
            }
            var allTools = new ArrayList<Action>();
            for (var run : before) allTools.addAll(store.actions(run.identity().runRef(), 0, 100).stream().filter(action -> action.kind() == Kind.TOOL).toList());
            var snapshot = store.desktopProjection(owner, 100, 40, 80, 40, finishedId);
            assertEquals(200, snapshot.totalRuns()); assertEquals(expectedMessages, snapshot.totalMessages());
            assertEquals(allEvents.size() + allTools.size(), snapshot.totalEvents());
            assertEquals(before.subList(100, 200), snapshot.runs());
            assertEquals(before.subList(160, 200), snapshot.messageCandidates().stream().map(SqliteExecutionStore.DesktopMessage::run).toList());
            var process = allEvents.stream().filter(event -> !event.event().type().equals("tool")).toList();
            assertEquals(process.subList(process.size() - 80, process.size()), snapshot.events());
            assertEquals(allTools.subList(allTools.size() - 40, allTools.size()), snapshot.tools().stream().map(SqliteExecutionStore.DesktopTool::action).toList());
            assertEquals(1, snapshot.waiting().size()); assertEquals(waiting, snapshot.waiting().getFirst().run());
            assertEquals(Map.of("confirm", ToolPortal.ToolInputMethod.ELICITATION_CREATE), snapshot.waiting().getFirst().waiting().requested());
            assertTrue(snapshot.waiting().getFirst().waiting().acceptedResponse().isEmpty());
            assertEquals(122, snapshot.materials().size()); // 40 input、41 request、40 feedback、1 shared configuration。
            assertFalse(snapshot.materials().containsKey(hiddenConfig.reference()));
            assertFalse(snapshot.materials().containsKey(hiddenInput.reference()));
            assertFalse(snapshot.materials().containsKey(hiddenRequest.reference()));
            assertFalse(snapshot.materials().keySet().stream().anyMatch(reference -> reference.value().endsWith("-result")));
            assertTrue(snapshot.messageCandidates().stream().allMatch(message -> message.finished().orElseThrow().event().id().equals(finishedId.apply(message.run().identity().runRef()))));
            var empty = store.desktopProjection(new SessionRef(new TenantId("other-tenant"), owner.sessionId()), 100, 40, 80, 40, finishedId);
            assertEquals(0, empty.totalRuns()); assertEquals(0, empty.totalMessages()); assertEquals(0, empty.totalEvents());
            assertTrue(empty.runs().isEmpty()); assertTrue(empty.events().isEmpty()); assertTrue(empty.tools().isEmpty()); assertTrue(empty.waiting().isEmpty()); assertTrue(empty.materials().isEmpty());
            System.out.printf("desktop-projection same-file: oldRuns=%d oldEvents=%d oldTools=%d -> runs=%d messageRuns=%d events=%d tools=%d waiting=%d materials=%d totals=%d/%d/%d%n",
                    before.size(), allEvents.size(), allTools.size(), snapshot.runs().size(), snapshot.messageCandidates().size(), snapshot.events().size(), snapshot.tools().size(), snapshot.waiting().size(), snapshot.materials().size(), snapshot.totalRuns(), snapshot.totalMessages(), snapshot.totalEvents());
        }
    }

    @Test void narrowDesktopSchedulingQueriesSkipFinishedHistoryAndKeepWaitingHead() {
        var file = directory.resolve("scheduling.db"); var clock = clock();
        var owner = new SessionRef(TENANT, "scheduling-session");
        try (var store = new SqliteExecutionStore(file, clock); var navigation = new SqliteDesktopSessions(directory.resolve("navigation.db"), clock)) {
            navigation.create(owner.sessionId(), "Scheduling", directory.toString());
            assertEquals(0, countQueued(store, navigation, TENANT));
            assertEquals(0, store.queuedTaskCount(TENANT, List.of()));
            assertTrue(store.firstUnfinishedRun(owner).isEmpty()); assertTrue(store.latestRun(owner).isEmpty());
            for (int index = 0; index < 24; index++) {
                var run = store.admit(admission("000-history-" + index, false, owner)).run();
                store.cancel(new Cancel(actor(run), run.identity().runRef(), new InputId("cancel-" + index), List.of()));
            }
            var waiting = claim(store, store.admit(admission("100-waiting", false, owner)).run(), "waiting-worker");
            var command = dispatch(waiting, "scheduling-question", 1);
            var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(command));
            var reference = new WaitRef(command.action().action(), 1);
            var blocked = store.complete(complete(registered.run(), command.attemptId(), "scheduling-question-result",
                    Optional.of(new WaitDeclaration(reference, WaitKind.HUMAN, Map.of(), Optional.empty())))).run();
            var following = store.admit(admission("200-ready", false, owner)).run();
            var other = new SessionRef(TENANT, "other-scheduling-session");
            navigation.create(other.sessionId(), "Other", directory.toString());
            store.admit(admission("other-ready", false, other));
            store.admit(admission("orphan-ready")); // 不属于本机导航的 Session 不计入桌面队列。
            // 宿主导航与执行分属两个真实文件；查错导航文件会把已有队首误判为空。
            try (var metadataOnly = new SqliteExecutionStore(directory.resolve("navigation.db"), clock)) {
                assertTrue(metadataOnly.firstUnfinishedRun(owner).isEmpty());
                assertTrue(metadataOnly.latestRun(owner).isEmpty());
                assertEquals(0, metadataOnly.queuedTaskCount(TENANT, List.of(owner.sessionId())));
            }
            var oldHistory = store.runsForSession(owner);
            long oldQueueRows = navigation.list().stream().mapToLong(session -> store.runsForSession(new SessionRef(TENANT, session.id())).size()).sum();
            long oldQueueCount = navigation.list().stream().flatMap(session -> store.runsForSession(new SessionRef(TENANT, session.id())).stream())
                    .filter(run -> run.phase() != Phase.FINISHED).count();
            var first = store.firstUnfinishedRun(owner); var latest = store.latestRun(owner);
            assertEquals(26, oldHistory.size()); assertEquals(27, oldQueueRows);
            assertEquals(oldHistory.stream().filter(run -> run.phase() != Phase.FINISHED).findFirst(), first);
            assertEquals(Optional.of(oldHistory.getLast()), latest);
            assertEquals(oldQueueCount, countQueued(store, navigation, TENANT));
            System.out.printf("desktop-scheduling same-fixture: first oldRows=%d newRows=%d; latest oldRows=%d newRows=%d; queue oldRows=%d newScalarRows=1 count=%d%n",
                    oldHistory.size(), first.stream().count(), oldHistory.size(), latest.stream().count(), oldQueueRows, countQueued(store, navigation, TENANT));
            try (var database = new SqliteDatabase(file, clock)) {
                var configuration = SqliteDatabase.configuration(database.readSource());
                SqliteDatabase.load(configuration, "mapper/SqliteExecutionStoreMapper.xml");
                var parameters = Map.<String, Object>of("tenant", TENANT.value(), "session", owner.sessionId());
                for (var query : List.of("firstUnfinishedRun", "latestRun")) {
                    var bound = configuration.getMappedStatement(ExecutionStoreMapper.class.getName() + "." + query).getBoundSql(parameters);
                    var source = new org.apache.ibatis.builder.StaticSqlSource(configuration, "EXPLAIN QUERY PLAN " + bound.getSql(), bound.getParameterMappings());
                    var result = new org.apache.ibatis.mapping.ResultMap.Builder(configuration, "plan-" + query, Map.class, List.of()).build();
                    configuration.addMappedStatement(new org.apache.ibatis.mapping.MappedStatement.Builder(configuration, "plan." + query, source,
                            org.apache.ibatis.mapping.SqlCommandType.SELECT).resultMaps(List.of(result)).build());
                }
                try (var sql = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession()) {
                    for (var query : List.of("firstUnfinishedRun", "latestRun")) {
                        List<Map<String, Object>> plan = sql.selectList("plan." + query, parameters);
                        System.out.println("desktop-scheduling " + query + " plan=" + plan);
                        assertTrue(plan.stream().anyMatch(row -> row.get("detail").toString().contains("execution_runs_session_idx")), query + " should use the existing session index");
                    }
                }
            }
            assertEquals(3, countQueued(store, navigation, TENANT));
            assertEquals(Optional.of(blocked), store.firstUnfinishedRun(owner));
            assertEquals(Optional.of(following), store.latestRun(owner));
            assertEquals(0, countQueued(store, navigation, new TenantId("another-tenant")));
            assertTrue(store.firstUnfinishedRun(new SessionRef(new TenantId("another-tenant"), owner.sessionId())).isEmpty());
            assertEquals(blocked, store.load(blocked.identity().runRef()).orElseThrow());
            store.cancel(new Cancel(actor(blocked), blocked.identity().runRef(), new InputId("release-waiting"), List.of()));
            assertEquals(Optional.of(store.load(following.identity().runRef()).orElseThrow()), store.firstUnfinishedRun(owner));
            assertEquals(2, countQueued(store, navigation, TENANT));
        }
    }

    @Test void narrowDesktopSchedulingQueriesRetainUnknownHeadAfterReopenWithoutChangingFacts() {
        var file = directory.resolve("unknown-scheduling.db"); var clock = clock();
        var owner = new SessionRef(TENANT, "unknown-scheduling-session"); Run blocked; Run following;
        try (var store = new SqliteExecutionStore(file, clock); var navigation = new SqliteDesktopSessions(directory.resolve("navigation.db"), clock)) {
            navigation.create(owner.sessionId(), "Unknown", directory.toString());
            var run = claim(store, store.admit(admission("100-unknown", false, owner)).run(), "unknown-worker");
            var command = dispatch(run, "scheduling-unknown", 1);
            var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(command));
            blocked = store.quiesceUnknown(new QuiesceUnknown(command.run(), command.attemptId(), registered.run().revision(),
                    registered.run().lease().orElseThrow(), List.of())).run();
            following = store.admit(admission("200-following", false, owner)).run();
        }
        try (var store = new SqliteExecutionStore(file, clock); var navigation = new SqliteDesktopSessions(directory.resolve("navigation.db"), clock)) {
            var budget = store.budgets().snapshot(blocked.identity().runRef());
            assertEquals(Phase.UNCERTAIN, blocked.phase());
            assertEquals(Optional.of(blocked), store.firstUnfinishedRun(owner));
            assertEquals(Optional.of(following), store.latestRun(owner));
            assertEquals(2, countQueued(store, navigation, TENANT));
            assertEquals(blocked, store.load(blocked.identity().runRef()).orElseThrow());
            assertEquals(budget, store.budgets().snapshot(blocked.identity().runRef()));
        }
    }

    @Test void requestRootsAndPartsCommitAtomicallyAndBatchReadAfterReopen() {
        var file = directory.resolve("request-parts.db"); var clock = clock();
        try (var first = new SqliteExecutionStore(file, clock); var rebuilt = new SqliteExecutionStore(file, clock)) {
            var a = claim(first, first.admit(admission("parts-first")).run(), "parts-worker");
            var b = claim(first, first.admit(admission("parts-conflict")).run(), "parts-conflict-worker");
            com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.requestPartsAreAtomicAndExact(first, rebuilt,
                    dispatch(a, "parts-model", 1), dispatch(b, "parts-conflict-model", 1));
        }
    }

    @Test void competingTerminalContextsCommitOnce() throws Exception {
        var file = directory.resolve("terminal-race.db"); var clock = clock();
        try (var first = new SqliteExecutionStore(file, clock); var second = new SqliteExecutionStore(file, clock)) {
            com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.competingTerminalContextsCommitOnce(first, second, admission("terminal-race"));
        }
    }

    @Test void terminalContextRollsBackWhenEventInsertFails() throws Exception {
        var file = directory.resolve("terminal-rollback.db");
        try (var store = new SqliteExecutionStore(file, clock())) {
            var run = claim(store, store.admit(admission("terminal-rollback")).run(), "worker");
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER terminal_failure BEFORE INSERT ON agent_runtime_execution_events BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            var context = material("terminal-rollback-context"); var terminalEvent = event("terminal-rollback-event");
            var command = new Finish(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), RunStatus.TERMINATED, List.of(terminalEvent), Optional.of(context));
            assertThrows(RuntimeException.class, () -> store.finish(command));
            assertEquals(run, store.load(command.run()).orElseThrow());
            assertTrue(store.material(TENANT, context.reference()).isEmpty());
            assertTrue(store.event(command.run(), terminalEvent.id()).isEmpty());
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER terminal_failure");
            }
            assertEquals(RunStatus.TERMINATED, store.finish(command).status());
        }
    }

    @Test void terminalContextSharedContract() {
        var file = directory.resolve("terminal-contract.db"); var clock = clock();
        try (var first = new SqliteExecutionStore(file, clock); var rebuilt = new SqliteExecutionStore(file, clock)) {
            com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.terminalContextIsAtomicAndReplayExact(first, rebuilt, admission("terminal-contract"));
        }
    }

    @Test void currentExecutionStoreSharedContract() {
        var file = directory.resolve("shared-contract.db"); var clock = clock();
        try (var first = new SqliteExecutionStore(file, clock); var rebuilt = new SqliteExecutionStore(file, clock)) {
            com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.admissionSurvivesReconstruction(first, rebuilt, admission("contract-rebuild"));
            com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.materialsKeepRequestedOrder(first, admission("contract-materials"));
            com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.liveClaimIsExclusive(first, rebuilt, admission("contract-claim"));
        }
    }

    @Test void admissionAndSessionMetadataSurviveReopenWithNanosecondsAndRejectChangedContent() {
        var clock = clock(); var file = directory.resolve("desktop.db"); var admission = admission("one");
        try (var store = new SqliteExecutionStore(file, clock); var sessions = new SqliteDesktopSessions(file, clock)) {
            var created = store.admit(admission);
            assertTrue(created.created()); assertFalse(store.admit(admission).created());
            assertEquals(clock.instant(), created.run().createdAt());
            var metadata = sessions.create("session-one", "Title", directory.toString());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(metadata, sessions.create("session-one", "Title", directory.toString()));
            assertThrows(IllegalStateException.class, () -> sessions.create("session-one", "Changed", directory.toString()));
            var changed = new Admission(admission.identity(), admission.run(), "different", admission.configuration(), admission.input(), admission.context(), admission.budget(), admission.events(), List.of());
            assertThrows(Rejection.class, () -> store.admit(changed));
        }
        try (var reopened = new SqliteExecutionStore(file, clock); var sessions = new SqliteDesktopSessions(file, clock)) {
            assertEquals(admission.run(), reopened.load(admission.run().runRef()).orElseThrow().identity());
            assertEquals(1, reopened.runsForSession(admission.run().owner().sessionRef()).size());
            assertEquals(1, sessions.list().size());
            assertEquals(admission.input(), reopened.originalInput(admission.run().runRef()).orElseThrow());
        }
    }

    @Test void duplicateDispatchDoesNotGrantAnotherSendAndResultsAreIdempotent() {
        try (var store = new SqliteExecutionStore(directory.resolve("dispatch.db"), clock())) {
            var run = claim(store, store.admit(admission("dispatch")).run(), "worker");
            var dispatch = dispatch(run, "model", 1);
            var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
            assertInstanceOf(DispatchResult.Existing.class, store.dispatch(dispatch));
            assertTrue(store.authorizeSend(registered, dispatch.action()));
            assertFalse(store.authorizeSend(registered, dispatch.action()));
            var completion = complete(registered.run(), registered.attempt().id(), "answer", Optional.empty());
            var saved = store.complete(completion);
            assertTrue(saved.newlySaved()); assertTrue(saved.advanced());
            assertFalse(store.complete(completion).newlySaved());
            assertEquals(90, store.usage(run.identity().runRef()).reportedTokens());
            assertEquals(1, store.budgets().snapshot(run.identity().runRef()).consumed().businessModels());
            assertEquals(Phase.READY, saved.run().phase());
        }
    }

    @Test void zeroBusinessBudgetRejectsDispatchWithPreciseCodeAndNoAttempt() {
        try (var store = new SqliteExecutionStore(directory.resolve("zero-business.db"), clock())) {
            var original = admission("zero-business");
            var zero = new ExecutionBudgetPort.Resources(0, 1, 20, 0, 100_000, 100_000, 200_000);
            var registration = new ExecutionBudgetPort.Registration(original.run().runRef(), original.run().runRef(),
                    ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                            zero, zero, zero, 3600, 3600, "zero-business-v1")));
            var admitted = new Admission(original.identity(), original.run(), original.fingerprint(), original.configuration(),
                    original.input(), original.context(), registration, original.events(), original.configurationMaterials());
            var run = claim(store, store.admit(admitted).run(), "zero-worker");
            var request = dispatch(run, "zero-request", 1);
            var rejected = assertInstanceOf(DispatchResult.Rejected.class, store.dispatch(request));
            assertEquals("execution_budget_root_business_model_calls_limit", rejected.reason());
            assertTrue(store.attempt(request.run(), request.attemptId()).isEmpty());
            assertEquals(ExecutionBudgetPort.Resources.ZERO, store.budgets().snapshot(request.run()).reserved());
        }
    }

    @Test void conflictingEventRollsBackAttemptAndBudgetTogether() {
        try (var store = new SqliteExecutionStore(directory.resolve("rollback.db"), clock())) {
            var admitted = admission("rollback"); var run = claim(store, store.admit(admitted).run(), "worker");
            var original = dispatch(run, "conflict", 1);
            var conflict = new Event(admitted.events().getFirst().id(), "different", payload("changed"));
            var rejected = new Dispatch(original.run(), original.revision(), original.lease(), original.action(), original.attemptId(), original.budget(), original.actor(), original.continuation(), List.of(conflict));
            assertThrows(Rejection.class, () -> store.dispatch(rejected));
            assertTrue(store.attempt(original.run(), original.attemptId()).isEmpty());
            assertEquals(ExecutionBudgetPort.Resources.ZERO, store.budgets().snapshot(original.run()).consumed());
            assertEquals(ExecutionBudgetPort.Resources.ZERO, store.budgets().snapshot(original.run()).reserved());
            assertEquals(run, store.load(original.run()).orElseThrow());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancelPreventsSendAndLateResultCannotReopenRun(boolean observeOnly) {
        try (var store = new SqliteExecutionStore(directory.resolve("cancel.db"), clock())) {
            var run = claim(store, store.admit(admission("cancel", observeOnly)).run(), "worker");
            var dispatch = dispatch(run, "cancel-model", 1);
            var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
            var cancelled = store.cancel(new Cancel(actor(run), dispatch.run(), new InputId("cancel-input"), List.of()));
            assertEquals(RunStatus.CANCELLED, cancelled.status());
            assertFalse(store.authorizeSend(registered, dispatch.action()));
            var late = store.complete(complete(registered.run(), registered.attempt().id(), "late", Optional.empty()));
            assertTrue(late.newlySaved()); assertFalse(late.advanced()); assertEquals(RunStatus.CANCELLED, late.run().status());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredDispatchedAttemptBecomesUnknownAfterRealReopenWithoutReplay(boolean observeOnly) {
        var file = directory.resolve("recovery.db"); var clock = clock(); Dispatch dispatched;
        try (var store = new SqliteExecutionStore(file, clock)) {
            var run = claim(store, store.admit(admission("recovery", observeOnly)).run(), "old-worker");
            dispatched = dispatch(run, "lost-model", 1);
            assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatched));
        }
        clock.advance(Duration.ofMinutes(3));
        try (var store = new SqliteExecutionStore(file, clock)) {
            var takeover = claim(store, store.load(dispatched.run()).orElseThrow(), "new-worker");
            var result = store.quiesceUnknown(new QuiesceUnknown(dispatched.run(), dispatched.attemptId(), takeover.revision(), takeover.lease().orElseThrow(), List.of()));
            assertTrue(result.changed()); assertEquals(Phase.UNCERTAIN, result.run().phase());
            assertFalse(store.usage(dispatched.run()).complete());
            assertInstanceOf(DispatchResult.Existing.class, store.dispatch(dispatched));
            assertTrue(store.claim(new Claim(dispatched.run(), "other", "other-claim", LEASE)).isEmpty());
            assertTrue(store.budgets().snapshot(dispatched.run()).reserved().totalTokens() > 0);
        }
    }

    @Test void humanWaitResponseIsAcceptedOnceAndLocallyAdopted() {
        try (var store = new SqliteExecutionStore(directory.resolve("wait.db"), clock())) {
            var run = claim(store, store.admit(admission("wait")).run(), "worker");
            var dispatch = dispatch(run, "question", 1);
            var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
            var wait = new WaitRef(dispatch.action().action(), 1);
            var waiting = store.complete(complete(registered.run(), registered.attempt().id(), "question-result", Optional.of(new WaitDeclaration(wait, WaitKind.HUMAN, Map.of(), Optional.empty()))));
            assertEquals(Phase.WAITING, waiting.run().phase());
            var input = new InputId("human-response");
            var resume = new Resume(actor(run), dispatch.run(), wait, waiting.run().revision(), input, material("response"), "response-fingerprint", Map.of(), List.of());
            assertTrue(store.resume(resume).accepted()); assertFalse(store.resume(resume).accepted());
            var resumed = claim(store, store.load(dispatch.run()).orElseThrow(), "resumer"); var context = material("human-context");
            var adopted = store.acceptLocalResponse(new AcceptLocalResponse(dispatch.run(), wait, input, resumed.revision(), resumed.lease().orElseThrow(), new Progress(1, 0, 0, context.reference()), context, List.of()));
            assertTrue(adopted.advanced()); assertEquals(Phase.READY, adopted.run().phase());
            assertTrue(adopted.waiting().consumedRevision().isPresent());
            assertEquals(1, store.actions(dispatch.run(), 0, 100).size());
        }
    }

    @Test void twoStoreInstancesCannotClaimSameRunConcurrently() throws Exception {
        var file = directory.resolve("competition.db"); var clock = clock();
        try (var first = new SqliteExecutionStore(file, clock); var second = new SqliteExecutionStore(file, clock); var workers = Executors.newFixedThreadPool(2)) {
            var run = first.admit(admission("competition")).run(); var start = new CountDownLatch(1);
            var a = workers.submit(() -> { start.await(); return first.claim(new Claim(run.identity().runRef(), "a", "a", LEASE)); });
            var b = workers.submit(() -> { start.await(); return second.claim(new Claim(run.identity().runRef(), "b", "b", LEASE)); });
            start.countDown();
            assertEquals(1, (a.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0) + (b.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0));
        }
    }

    @Test void toolConfirmationContinuesOriginalActionOnceWithAcceptedResponse() {
        try (var store = new SqliteExecutionStore(directory.resolve("tool-wait.db"), clock())) {
            var run = modelReady(store, "tool-wait"); var prepared = tool(run, "confirm-command", 2);
            var demand = demand(run, prepared, "confirm-command-attempt");
            var dispatch = new Dispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), prepared, demand.attemptId(), demand, actor(run), Optional.empty(), List.of());
            var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
            var reference = new WaitRef(prepared.action(), 1);
            var requested = Map.of("command-confirmation", ToolPortal.ToolInputMethod.ELICITATION_CREATE);
            var context = material("tool-wait-context");
            var waiting = store.complete(new Completion(dispatch.run(), demand.attemptId(), ResultKind.INPUT_REQUIRED, material("confirmation-request"), Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported(), registered.run().revision(), registered.run().lease().orElseThrow(), new Progress(1, 0, 0, context.reference()), context, List.of(), Optional.of(new WaitDeclaration(reference, WaitKind.TOOL, requested, Optional.empty()))));
            var input = new InputId("confirmed-input");
            var resume = new Resume(actor(run), dispatch.run(), reference, waiting.run().revision(), input, material("confirmation-response"), "confirmed", requested, List.of());
            assertTrue(store.resume(resume).accepted());
            var resumed = claim(store, store.load(dispatch.run()).orElseThrow(), "confirmation-worker");
            var continuedPrepared = new Prepared(prepared.action(), prepared.ordinal(), Kind.TOOL, prepared.target(), prepared.capability(), material("continued-request"), "continued-fingerprint");
            var continuedDemand = demand(resumed, continuedPrepared, "continued-attempt");
            var continuation = new Dispatch(dispatch.run(), resumed.revision(), resumed.lease().orElseThrow(), continuedPrepared, continuedDemand.attemptId(), continuedDemand, actor(resumed), Optional.of(reference), List.of());
            var continued = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(continuation));
            assertInstanceOf(DispatchResult.Existing.class, store.dispatch(continuation));
            assertEquals(Optional.of("continued-attempt"), store.waiting(dispatch.run(), reference).orElseThrow().continuationAttempt());
            assertTrue(store.authorizeSend(continued, continuedPrepared)); assertFalse(store.authorizeSend(continued, continuedPrepared));
            var completed = store.complete(toolResult(continued.run(), "continued-attempt", "command-done"));
            assertTrue(completed.advanced()); assertEquals(Phase.READY, completed.run().phase());
            assertEquals(2, store.actions(dispatch.run(), 0, 100).size());
        }
    }

    @Test void toolBatchSavesEachFactAndAdoptsCompleteOrderedResultsOnce() {
        try (var store = new SqliteExecutionStore(directory.resolve("batch.db"), clock())) {
            var run = modelReady(store, "batch"); var requests = new ArrayList<BatchMember>();
            for (int slot = 1; slot <= 2; slot++) {
                var prepared = tool(run, "batch-tool-" + slot, slot + 1);
                var demand = demand(run, prepared, "batch-attempt-" + slot);
                requests.add(new BatchMember.Fresh(slot, new BatchRequest(prepared, demand.attemptId(), demand)));
            }
            var command = new BatchDispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), "batch-model-attempt", requests, actor(run));
            var registered = assertInstanceOf(BatchDispatchResult.Registered.class, store.dispatchBatch(command));
            assertInstanceOf(BatchDispatchResult.Existing.class, store.dispatchBatch(command));
            var batch = registered.run().currentBatch().orElseThrow();
            var first = toolResult(registered.run(), "batch-attempt-1", "first");
            var second = toolResult(registered.run(), "batch-attempt-2", "second");
            assertFalse(store.completeBatch(new BatchCompletion(batch, first, Optional.empty())).advanced());
            var context = material("ordered-batch-context");
            var results = List.of(new BatchResultRef(registered.entries().get(0).action().reference(), "batch-attempt-1", first.result().reference()), new BatchResultRef(registered.entries().get(1).action().reference(), "batch-attempt-2", second.result().reference()));
            var adoption = new BatchAdoption(results, new Progress(1, 2, 0, context.reference()), context, List.of(event("batch-done")));
            var completed = store.completeBatch(new BatchCompletion(batch, second, Optional.of(adoption)));
            assertTrue(completed.advanced()); assertEquals(Phase.READY, completed.run().phase());
            assertTrue(completed.run().currentBatch().isEmpty());
            assertEquals(2, completed.run().progress().toolCalls());
            assertFalse(store.adoptBatch(new BatchAdopt(command.run(), batch, registered.run().revision(), registered.run().lease().orElseThrow(), adoption)).advanced());
            assertEquals(2, store.batch(batch).size());
            assertEquals(2, store.budgets().snapshot(command.run()).consumed().tools());
        }
    }

    /** UNKNOWN携带的流式用量不结算；原Attempt的迟到确定回执才补齐用量。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownUsageKeepsReservationUntilOriginalAttemptBecomesKnown(boolean batchMode) {
        var id = batchMode ? "unknown-batch-usage" : "unknown-single-usage";
        try (var store = new SqliteExecutionStore(directory.resolve(id + ".db"), clock())) {
            Run run; String attempt; BatchRef batch = null;
            if (batchMode) {
                var ready = modelReady(store, id); var requests = new ArrayList<BatchMember>();
                for (int slot = 1; slot <= 2; slot++) {
                    var prepared = tool(ready, id + "-tool-" + slot, slot + 1);
                    var key = id + "-attempt-" + slot;
                    var demand = new ExecutionBudgetPort.Demand(ready.identity().runRef(), key, prepared.fingerprint(),
                            ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 100, 20), false);
                    requests.add(new BatchMember.Fresh(slot, new BatchRequest(prepared, key, demand)));
                }
                var registered = assertInstanceOf(BatchDispatchResult.Registered.class, store.dispatchBatch(new BatchDispatch(
                        ready.identity().runRef(), ready.revision(), ready.lease().orElseThrow(), id + "-model-attempt", requests, actor(ready))));
                run = registered.run(); batch = run.currentBatch().orElseThrow(); attempt = registered.entries().getFirst().attempt().id();
            } else {
                var ready = claim(store, store.admit(admission(id)).run(), "unknown-worker");
                var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch(ready, id + "-model", 1)));
                run = registered.run(); attempt = registered.attempt().id();
            }
            var template = complete(run, attempt, id + "-unknown", Optional.empty());
            var unknown = new Completion(template.run(), template.attemptId(), ResultKind.UNKNOWN, template.result(), template.feedback(),
                    template.externalOperation(), ExecutionBudgetPort.Usage.reported(OptionalLong.of(25), OptionalLong.of(5)),
                    template.revision(), template.lease(), template.next(), template.context(), template.events(), template.waitDeclaration());
            var before = store.budgets().snapshot(run.identity().runRef());
            var first = batchMode ? store.completeBatch(new BatchCompletion(batch, unknown, Optional.empty())) : store.complete(unknown);
            assertTrue(first.newlySaved()); assertEquals(before, store.budgets().snapshot(run.identity().runRef()));
            var repeated = withUsage(unknown, 30, 6);
            assertFalse((batchMode ? store.completeBatch(new BatchCompletion(batch, repeated, Optional.empty())) : store.complete(repeated)).newlySaved());
            assertEquals(before, store.budgets().snapshot(run.identity().runRef()));
            if (batchMode) {
                var other = toolResult(run, id + "-attempt-2", id + "-sibling");
                assertEquals(Phase.UNCERTAIN, store.completeBatch(new BatchCompletion(batch, other, Optional.empty())).run().phase());
            }
            var known = complete(run, attempt, id + "-known", Optional.empty());
            var late = batchMode ? store.completeBatch(new BatchCompletion(batch, known, Optional.empty())) : store.complete(known);
            assertTrue(late.newlySaved()); assertFalse(late.advanced()); assertEquals(Phase.UNCERTAIN, late.run().phase());
            assertEquals(Optional.of(unknown.result().reference()), late.attempt().uncertainty());
            assertFalse((batchMode ? store.completeBatch(new BatchCompletion(batch, known, Optional.empty())) : store.complete(known)).newlySaved());
            var settled = store.budgets().snapshot(run.identity().runRef());
            assertEquals(before.consumed().inputTokens() + 80, settled.consumed().inputTokens());
            var conflict = withUsage(known, 81, 10); var savedBatch = batch;
            assertThrows(IllegalStateException.class, () -> {
                if (batchMode) store.completeBatch(new BatchCompletion(savedBatch, conflict, Optional.empty()));
                else store.complete(conflict);
            });
            assertEquals(settled, store.budgets().snapshot(run.identity().runRef()));
        }
    }

    private static Completion withUsage(Completion value, long input, long output) {
        return new Completion(value.run(), value.attemptId(), value.kind(), value.result(), value.feedback(), value.externalOperation(),
                ExecutionBudgetPort.Usage.reported(OptionalLong.of(input), OptionalLong.of(output)), value.revision(), value.lease(),
                value.next(), value.context(), value.events(), value.waitDeclaration());
    }

    private static Run modelReady(SqliteExecutionStore store, String id) {
        var run = claim(store, store.admit(admission(id)).run(), "model-worker"); var dispatch = dispatch(run, id + "-model", 1);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        return store.complete(complete(registered.run(), registered.attempt().id(), id + "-model-result", Optional.empty())).run();
    }
    private static Prepared tool(Run run, String id, long ordinal) { return new Prepared(new ActionRef(TENANT, id), ordinal, Kind.TOOL, id, new ResultRef("tool-capability"), material(id + "-request"), id + "-fingerprint"); }
    private static ExecutionBudgetPort.Demand demand(Run run, Prepared prepared, String attempt) { return new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false); }
    private static Completion toolResult(Run run, String attempt, String id) {
        var context = material(id + "-tool-context");
        return new Completion(run.identity().runRef(), attempt, ResultKind.SUCCEEDED, material(id + "-result"), Optional.of(material(id + "-feedback")), Optional.empty(), ExecutionBudgetPort.Usage.reported(OptionalLong.of(0), OptionalLong.of(0)), run.revision(), run.lease().orElseThrow(), new Progress(run.progress().decisions(), run.progress().toolCalls() + 1, 0, context.reference()), context, List.of(), Optional.empty());
    }

    private static long countQueued(SqliteExecutionStore store, SqliteDesktopSessions navigation, TenantId tenant) {
        return store.queuedTaskCount(tenant, navigation.list().stream().map(SqliteDesktopSessions.Session::id).toList());
    }
    private static Admission admission(String id) { return admission(id, false); }
    private static Admission admission(String id, boolean observeOnly) {
        return admission(id, observeOnly, new SessionRef(TENANT, "session-" + id));
    }
    private static Admission admission(String id, boolean observeOnly, SessionRef session) {
        var owner = SessionOwner.user(session, new AgentId("desktop-agent"), new EndUserId(1));
        var input = new InputId("input-" + id); var run = new RunRef(TENANT, id);
        var verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner, Set.of("sessions.write"), 1, new ResultRef("identity-" + id));
        var identity = new RunIdentity(owner, new AgentId("desktop-agent"), input, run, new TraceEnvelope("trace-" + id, "span-" + id, input, Optional.empty()), verified.verificationRef(), new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "origin-" + id));
        ExecutionBudgetPort.Policy policy = observeOnly
                ? new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, "desktop-test-v4")
                : new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                        LIMIT, LIMIT, LIMIT, 3_600, 3_600, "desktop-test-v1"));
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY, policy);
        return new Admission(verified, identity, "fingerprint-" + id, new ResultRef("configuration-" + id), material("input-" + id), material("context-" + id), budget, List.of(event("admitted-" + id)), List.of());
    }
    private static Run claim(ExecutionStore store, Run run, String worker) { return store.claim(new Claim(run.identity().runRef(), worker, worker + "-claim", LEASE)).orElseThrow(); }
    private static VerifiedIdentity actor(Run run) { return new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, run.identity().owner().requireUser()), run.identity().owner(), Set.of("sessions.write"), 1, run.actorVerificationRef()); }
    private static Dispatch dispatch(Run run, String id, long ordinal) {
        var prepared = new Prepared(new ActionRef(TENANT, id), ordinal, Kind.MODEL, "model", new ResultRef("capability"), material(id + "-request"), id + "-fingerprint");
        return new Dispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), prepared, id + "-attempt", new ExecutionBudgetPort.Demand(run.identity().runRef(), id + "-attempt", prepared.fingerprint(), ExecutionBudgetPort.Category.BUSINESS_MODEL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 20), false), actor(run), Optional.empty(), List.of());
    }
    private static Completion complete(Run run, String attempt, String id, Optional<WaitDeclaration> waiting) {
        var context = material(id + "-context");
        return new Completion(run.identity().runRef(), attempt, ResultKind.SUCCEEDED, material(id), Optional.of(material(id + "-feedback")), Optional.empty(), ExecutionBudgetPort.Usage.reported(OptionalLong.of(80), OptionalLong.of(10)), run.revision(), run.lease().orElseThrow(), new Progress(1, 0, 0, context.reference()), context, List.of(event(id)), waiting);
    }
    private static Material material(String id) { return new Material(new ResultRef(id), payload(id)); }
    private static TypedPayload payload(String id) { return new TypedPayload("desktop-test-v1", "{\"value\":\"" + id + "\"}"); }
    private static Event event(String id) { return new Event(new EventId(id), "desktop-test", payload(id)); }
    private static MutableClock clock() { return new MutableClock(Instant.parse("2026-10-22T01:02:03.123456789Z")); }
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
