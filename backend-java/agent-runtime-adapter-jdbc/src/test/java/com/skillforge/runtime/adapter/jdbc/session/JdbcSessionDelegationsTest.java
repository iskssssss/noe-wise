package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.fixture.SessionSubjectJsonFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionDelegations.Capability;
import com.skillforge.runtime.session.spi.SessionDelegations.Entry;
import com.skillforge.runtime.session.spi.SessionDelegations.Result;
import com.skillforge.runtime.session.spi.SessionDelegations.State;
import com.skillforge.runtime.session.spi.SessionDelegations.Target;
import com.skillforge.runtime.session.spi.SessionDelegations.Task;
import com.skillforge.runtime.session.spi.SessionDelegations.Wait;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V225 的真实 PostgreSQL 幂等、CAS、身份隔离与等待投递契约。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcSessionDelegationsTest {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();

    private static final String APP_ROLE = "session_delegation_test";
    private static final String APP_PASSWORD = "session_delegation_test_password";
    private static final TenantId TENANT = new TenantId("tenant-delegation");
    private static final TenantId OTHER_TENANT =
            new TenantId("tenant-delegation-other");
    private static final Instant NOW =
            Instant.ofEpochSecond(1_800_000_000L, 123_456_789);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge")
                    .withUsername("skillforge_admin")
                    .withPassword("skillforge_admin_password");

    @BeforeAll
    static void initializeDatabase() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeTestDatabase.migrate(POSTGRES, APP_ROLE, APP_PASSWORD);
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            // 预算余额以多态scope保存，不随根表CASCADE清空。
            statement.execute("TRUNCATE agent_runtime_delegation_waits,agent_runtime_delegations,"
                    + "agent_runtime_execution_sessions,agent_runtime_execution_materials,agent_runtime_budget_roots,agent_runtime_budget_balances CASCADE");
        }
    }
    /** 两租户复用六个逻辑委派 ID，混合终态和大任务正文仍由完整父身份隔离。 */
    @Test
    void complexTwoTenantSixChildLifecyclesRebuildWithoutLosingFrozenFacts() {
        for (TenantId tenant : List.of(TENANT, OTHER_TENANT)) {
            var parent = parent(tenant, "complex-shared-parent", "complex-shared-trace");
            var repository = store(tenant);
            for (int index = 0; index < 6; index++) {
                var queued = queued(parent, "complex-shared-child-" + index, "complex-operation-" + index);
                var task = new Task("核验项目-" + index, "中文🧭上下文".repeat(256),
                        List.of("保留来源", "限制副作用", "租户隔离"), "结构化结果-" + index);
                queued = copy(queued, queued.id(), queued.operationKey(), queued.target(), task,
                        queued.childRun(), queued.childInput(), Optional.empty());
                assertEquals(queued, repository.create(queued));
                var running = transition(queued, State.RUNNING, Optional.empty(), Optional.empty(), 2, NOW.plusSeconds(1));
                assertTrue(repository.update(running, 1));
                var waiting = transition(running, State.WAITING, Optional.empty(), Optional.empty(), 3, NOW.plusSeconds(2));
                assertTrue(repository.update(waiting, 2));
                var state = index % 3 == 0 ? State.COMPLETED : index % 3 == 1 ? State.FAILED : State.CANCELLED;
                var result = new Result(state, tenant.value() + "-结果-" + index,
                        state == State.COMPLETED ? "" : "terminal-" + index, 200 + index,
                        List.of("event-" + index, "artifact-revision-" + index));
                var terminal = transition(waiting, state, Optional.of(result), Optional.empty(), 4, NOW.plusSeconds(3));
                assertTrue(repository.update(terminal, 3));
                assertEquals(terminal, store(tenant).read(parent, queued.id()).orElseThrow());
                assertEquals(terminal, store(tenant).create(queued));
                assertFalse(repository.update(running, 1));
            }
            assertEquals(6, repository.list(parent).size());
            assertTrue(repository.active(tenant, 100).isEmpty());
        }
        var local = parent(TENANT, "complex-shared-parent", "complex-shared-trace");
        var foreign = parent(OTHER_TENANT, "complex-shared-parent", "complex-shared-trace");
        for (int index = 0; index < 6; index++) {
            String id = "complex-shared-child-" + index;
            assertTrue(store().read(local, id).orElseThrow().result().orElseThrow().summary().startsWith(TENANT.value()));
            assertTrue(store().read(foreign, id).orElseThrow().result().orElseThrow().summary().startsWith(OTHER_TENANT.value()));
        }
        assertTrue(store().read(parent(TENANT, "complex-shared-parent", "wrong-trace"),
                "complex-shared-child-0").isEmpty());
    }

    /** 八个竞争者在同一 barrier 起跑，四个独立 Entry 的状态/取消 CAS 各仅一个胜者。 */
    @RepeatedTest(20)
    void complexConcurrentStateAndCancellationCasPreserveFourIndependentWinners() throws Exception {
        var parent = parent(TENANT, "complex-cas-parent", "complex-cas-trace");
        var entries = new java.util.ArrayList<Entry>();
        for (int index = 0; index < 4; index++)
            entries.add(store().create(queued(parent, "complex-cas-child-" + index, "complex-cas-op-" + index)));
        var barrier = new java.util.concurrent.CyclicBarrier(8);
        var executor = Executors.newFixedThreadPool(8);
        var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for (var entry : entries) {
                attempts.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return store().update(transition(entry, State.RUNNING, Optional.empty(), Optional.empty(),
                            2, NOW.plusSeconds(1)), 1);
                }));
                attempts.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return store().update(transition(entry, State.QUEUED, Optional.empty(),
                            Optional.of("cancel-" + entry.id()), 2, NOW.plusSeconds(1)), 1);
                }));
            }
            for (int index = 0; index < entries.size(); index++) {
                int wins = (attempts.get(index * 2).get(15, TimeUnit.SECONDS) ? 1 : 0)
                        + (attempts.get(index * 2 + 1).get(15, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, wins);
                var winner = store().read(parent, entries.get(index).id()).orElseThrow();
                assertEquals(2, winner.revision());
                var state = winner.cancelKey().isPresent() ? State.CANCELLED : State.COMPLETED;
                var terminal = transition(winner, state, Optional.of(new Result(state, "结果-" + index,
                        state == State.CANCELLED ? "cancelled" : "", index, List.of("cas-event-" + index))),
                        winner.cancelKey(), 3, NOW.plusSeconds(2));
                assertTrue(store().update(terminal, 2));
                assertEquals(terminal, store().read(parent, winner.id()).orElseThrow());
                assertThrows(IllegalStateException.class, () -> store().update(
                        transition(terminal, State.RUNNING, Optional.empty(), terminal.cancelKey(), 4,
                                NOW.plusSeconds(3)), 3));
            }
            assertTrue(store().active(TENANT, 100).isEmpty());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 四子任务的原终态与领域Wait确认可跨重建和独立JVM恢复，不创建旧RECOVERY输入。 */
    @Test
    void complexGroupedWaitFactsAndConfirmationSurviveIndependentJvm() throws Exception {
        var parent = parent(TENANT, "complex-process-parent", "complex-process-trace");
        var repository = store();
        var entries = new java.util.ArrayList<Entry>();
        for (int index = 0; index < 4; index++)
            entries.add(repository.create(queued(parent, "complex-process-child-" + index, "complex-process-op-" + index)));
        var wait = repository.register(new Wait("complex-process-wait", parent,
                entries.stream().map(Entry::id).toList(), false, 1));

        for (int index = 0; index < entries.size(); index++) {
            var original = entries.get(index);
            var state = index == 1 ? State.FAILED : index == 2 ? State.CANCELLED : State.COMPLETED;
            assertTrue(repository.update(transition(original, state, Optional.of(new Result(state,
                    "persisted-result-" + index, state == State.COMPLETED ? "" : "terminal-" + index,
                    200 + index, List.of("persisted-evidence-" + index))), Optional.empty(), 2,
                    NOW.plusSeconds(index + 1)), 1));

        }
        confirmWait(wait, true);
        var delivered = new Wait(wait.id(), parent, wait.delegationIds(), true, 2);
        assertTrue(store().delivered(delivered));
        assertTrue(store().delivered(delivered));
        assertEquals(delivered, store().wait(parent, wait.id()).orElseThrow());
        assertTrue(store().pendingWaits(TENANT, 100).isEmpty());
        assertTrue(store().wait(parent(OTHER_TENANT, "complex-process-parent", "complex-process-trace"), wait.id()).isEmpty());
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = new ProcessBuilder(executable, "-cp", probeClasspath(), DelegationRecoveryProbe.class.getName(),
                POSTGRES.getJdbcUrl()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "independent JVM must terminate");
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("DELEGATION_PROBE_OK:4:delivered"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private static String probeClasspath() throws Exception {
        var entries = new java.util.LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls)
                for (var url : urls.getURLs()) if ("file".equals(url.getProtocol()))
                    entries.add(java.nio.file.Path.of(url.toURI()).toString());
        }
        entries.add(java.nio.file.Path.of(JdbcSessionDelegationsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(java.io.File.pathSeparator, entries);
    }

    /** 独立进程只读取父进程的独立容器，不创建业务事实或复用内存 Store。 */
    public static final class DelegationRecoveryProbe {
        public static void main(String[] arguments) {
            var source = new PGSimpleDataSource();
            source.setURL(arguments[0]);
            source.setUser(APP_ROLE);
            source.setPassword(APP_PASSWORD);
            var repository = new JdbcSessionDelegations(source, new TestCodec());
            var parent = parent(TENANT, "complex-process-parent", "complex-process-trace");
            var entries = repository.list(parent);
            if (entries.size() != 4) throw new AssertionError("missing persisted children");
            for (int index = 0; index < 4; index++) {
                var entry = repository.read(parent, "complex-process-child-" + index).orElseThrow();
                var result = entry.result().orElseThrow();
                if (!result.summary().equals("persisted-result-" + index) || result.usedTokens() != 200 + index
                        || !result.evidence().equals(List.of("persisted-evidence-" + index)))
                    throw new AssertionError("persisted child result changed");
            }
            if (!repository.wait(parent, "complex-process-wait").orElseThrow().delivered())
                throw new AssertionError("wait delivery was lost");
            System.out.println("DELEGATION_PROBE_OK:4:delivered");
        }
    }

    @Test
    void createReplaysCurrentEntryAndRejectsEveryFrozenBindingChange() {
        var parent = parent(TENANT, "parent-create", "trace-create");
        var original = queued(parent, "delegation-create", "operation-create");
        var store = store();

        assertEquals(original, store.create(original));
        assertEquals(original, store.create(original));
        var running = transition(original, State.RUNNING, Optional.empty(),
                Optional.empty(), 2, NOW.plusSeconds(1));
        assertTrue(store.update(running, 1));
        assertEquals(running, store.create(original));
        assertEquals(List.of(running), store.list(parent));

        var changedTarget = new Target("changed-handle",
                new AgentId("changed-target"), "Changed", "changed",
                List.of(), new TypedPayload("delegation.target.v1", "{}"));
        var changedTask = new Task("changed objective", "context",
                List.of("constraint"), "expected");
        for (Entry changed : List.of(
                copy(original, "different-id", original.operationKey(),
                        original.target(), original.task(), original.childRun(),
                        original.childInput(), original.previousId()),
                copy(original, original.id(), "different-operation",
                        original.target(), original.task(), original.childRun(),
                        original.childInput(), original.previousId()),
                copy(original, original.id(), original.operationKey(),
                        changedTarget, original.task(), original.childRun(),
                        original.childInput(), original.previousId()),
                copy(original, original.id(), original.operationKey(),
                        original.target(), changedTask, original.childRun(),
                        original.childInput(), original.previousId()),
                copy(original, original.id(), original.operationKey(),
                        original.target(), original.task(),
                        new RunRef(TENANT, "changed-child"),
                        original.childInput(), original.previousId()),
                copy(original, original.id(), original.operationKey(),
                        original.target(), original.task(), original.childRun(),
                        new InputId("changed-input"), original.previousId()),
                copy(original, original.id(), original.operationKey(),
                        original.target(), original.task(), original.childRun(),
                        original.childInput(), Optional.of("changed-previous")))) {
            assertThrows(IllegalStateException.class, () -> store.create(changed));
        }
        assertEquals(List.of(running), store.list(parent));
    }

    @Test
    void updateUsesRevisionCasAndEnforcesStateAndCancellationMonotonicity() {
        var parent = parent(TENANT, "parent-cas", "trace-cas");
        var store = store();
        var original = store.create(queued(parent, "delegation-cas", "operation-cas"));

        // 扫描读取权威 child Run 时允许跳过未观察到的 RUNNING 瞬间。
        var waiting = transition(original, State.WAITING,
                Optional.empty(), Optional.empty(), 2, NOW.plusSeconds(1));
        assertTrue(store.update(waiting, 1));
        var staleRunning = transition(original, State.RUNNING, Optional.empty(),
                Optional.empty(), 2, NOW.plusSeconds(2));
        assertFalse(store.update(staleRunning, 1));

        var completed = transition(waiting, State.COMPLETED,
                Optional.of(new Result(State.COMPLETED, "done", "", 7,
                        List.of("evidence"))), Optional.empty(), 3,
                NOW.plusSeconds(2));
        assertTrue(store.update(completed, 2));
        var reversed = transition(completed, State.RUNNING, Optional.empty(),
                Optional.empty(), 4, NOW.plusSeconds(3));
        assertThrows(IllegalStateException.class,
                () -> store.update(reversed, 3));
        assertEquals(completed, store.read(parent, original.id()).orElseThrow());

        var cancellable = store.create(
                queued(parent, "delegation-cancel", "operation-cancel"));
        var cancelling = transition(cancellable, State.QUEUED,
                Optional.empty(), Optional.of("cancel-a"), 2,
                NOW.plusSeconds(1));
        assertTrue(store.update(cancelling, 1));
        var changedCancel = transition(cancelling, State.WAITING,
                Optional.empty(), Optional.of("cancel-b"), 3,
                NOW.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> store.update(changedCancel, 2));

        var queuedCancel = store.create(
                queued(parent, "delegation-queued-cancel",
                        "operation-queued-cancel"));
        var cancelled = transition(queuedCancel, State.CANCELLED,
                Optional.of(new Result(State.CANCELLED, "", "cancelled", 0,
                        List.of())), Optional.of("cancel-before-admission"), 2,
                NOW.plusSeconds(1));
        assertTrue(store.update(cancelled, 1));
    }

    @Test
    void admissionAndCancellationUseTheSamePersistentEntryLock() throws Exception {
        var parent = parent(TENANT, "parent-admit-lock", "trace-admit-lock");
        var store = store();
        var cancellingStore = store();
        var entry = store.create(queued(parent, "delegation-admit-lock",
                "operation-admit-lock"));
        var cancelling = transition(entry, State.QUEUED, Optional.empty(),
                Optional.of("cancel-admit-lock"), 2, NOW.plusSeconds(1));
        var admissionEntered = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        var cancelStarted = new CountDownLatch(1);
        var secondAdmissionRan = new AtomicBoolean();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var admitted = executor.submit(() -> store.admit(entry, () -> {
                admissionEntered.countDown();
                await(releaseAdmission);
            }));
            assertTrue(admissionEntered.await(5, TimeUnit.SECONDS));

            var cancelled = executor.submit(() -> {
                cancelStarted.countDown();
                return cancellingStore.update(cancelling, 1);
            });
            assertTrue(cancelStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> cancelled.get(300, TimeUnit.MILLISECONDS));
            assertEquals(entry, store.read(parent, entry.id()).orElseThrow());

            releaseAdmission.countDown();
            assertTrue(admitted.get(5, TimeUnit.SECONDS));
            assertTrue(cancelled.get(5, TimeUnit.SECONDS));
            assertEquals(cancelling,
                    store.read(parent, entry.id()).orElseThrow());
            assertFalse(store.admit(entry,
                    () -> secondAdmissionRan.set(true)));
            assertFalse(secondAdmissionRan.get());
        } finally {
            releaseAdmission.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void readsAndRecoveryScansUseCompleteParentIdentityTenantAndValidatedLimits()
            throws Exception {
        var parent = parent(TENANT, "parent-isolation", "trace-isolation");
        var otherParent = parent(TENANT, "parent-second", "trace-second");
        var foreignParent = parent(OTHER_TENANT, "parent-foreign", "trace-foreign");
        var store = store();
        var first = store.create(
                queued(parent, "delegation-isolation", "operation-isolation"));
        var second = store.create(
                queued(otherParent, "delegation-second", "operation-second"));
        store(OTHER_TENANT).create(
                queued(foreignParent, "delegation-foreign", "operation-foreign"));

        var driftedParent = parent(TENANT, "parent-isolation", "different-trace");
        assertTrue(store.read(driftedParent, first.id()).isEmpty());
        assertEquals(Optional.of(first), store.read(parent, first.id()));
        assertEquals(2, store.active(TENANT, 10).size());
        assertEquals(1, store.active(TENANT, 1).size());
        assertEquals(List.of(second), store.list(otherParent));
        assertThrows(IllegalArgumentException.class, () -> store.active(TENANT, 0));
        assertThrows(IllegalArgumentException.class, () -> store.active(TENANT, 101));
        assertThrows(IllegalArgumentException.class, () -> store.active(TENANT, 1001));

        try (Connection connection = applicationConnection(TENANT);
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id','"
                    + TENANT.value() + "',false)");
            try (var rows = statement.executeQuery(
                    "SELECT count(*) FROM public.agent_runtime_delegations "
                            + "WHERE tenant_id='" + OTHER_TENANT.value() + "'")) {
                rows.next();
                assertEquals(0, rows.getLong(1));
            }
        }
    }

    @Test
    void sessionHistoryIncludesTerminalEntriesAcrossRunsAndIsolatesFullOwner() {
        var owner = SessionOwner.user(new SessionRef(TENANT, "session-history"),
                SessionTarget.team("team-history"), new EndUserId(41));
        var parentA = parent(owner, "parent-history-a", "trace-history-a");
        var parentB = parent(owner, "parent-history-b", "trace-history-b");
        var store = store();

        var oldest = queuedAt(parentB, "delegation-z", "operation-oldest",
                "child-oldest", "input-oldest", NOW.minusSeconds(1));
        var sameIdParentA = queuedAt(parentA, "delegation-a", "operation-a",
                "child-a", "input-a", NOW);
        var sameIdParentB = queuedAt(parentB, "delegation-a", "operation-b",
                "child-b", "input-b", NOW);
        var terminalQueued = queuedAt(parentA, "delegation-b", "operation-terminal",
                "child-terminal", "input-terminal", NOW);

        store.create(sameIdParentB);
        store.create(terminalQueued);
        store.create(oldest);
        store.create(sameIdParentA);
        var terminal = transition(terminalQueued, State.COMPLETED,
                Optional.of(new Result(State.COMPLETED, "done", "", 3,
                        List.of("run-state:terminal"))), Optional.empty(), 2,
                NOW.plusSeconds(1));
        assertTrue(store.update(terminal, 1));

        assertEquals(List.of(oldest, sameIdParentA, sameIdParentB, terminal),
                store.forSession(owner, 10));
        assertEquals(List.of(oldest, sameIdParentA), store.forSession(owner, 2));

        var otherSession = SessionOwner.user(
                new SessionRef(TENANT, "session-history-other"), owner.target(),
                owner.requireUser());
        var otherTarget = SessionOwner.user(owner.sessionRef(),
                SessionTarget.team("team-history-other"), owner.requireUser());
        var otherUser = SessionOwner.user(owner.sessionRef(), owner.target(),
                new EndUserId(42));
        store.create(queued(parent(otherSession, "parent-other-session",
                "trace-other-session"), "delegation-other-session",
                "operation-other-session"));
        store.create(queued(parent(otherTarget, "parent-other-target",
                "trace-other-target"), "delegation-other-target",
                "operation-other-target"));
        store.create(queued(parent(otherUser, "parent-other-user",
                "trace-other-user"), "delegation-other-user",
                "operation-other-user"));

        assertEquals(List.of(oldest, sameIdParentA, sameIdParentB, terminal),
                store.forSession(owner, 10));
        assertEquals(List.of(oldest, sameIdParentA, sameIdParentB, terminal),
                store.forSession(owner, 1000), "产品历史读取上限须合法，仍保持完整owner隔离与稳定顺序");
        assertThrows(IllegalArgumentException.class,
                () -> store.forSession(owner, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.forSession(owner, 1001));
    }

    @Test
    void recoveryRotatesPastUnchangedHeadBatchAndRestartsWithoutMutatingBusinessState() throws Exception {
        var parent = parent(TENANT, "parent-fair", "trace-fair");
        var repository = store();
        var first = repository.create(queued(parent, "a", "op-a"));
        var second = repository.create(queued(parent, "b", "op-b"));
        var last = repository.create(queued(parent, "c", "op-c"));
        // limit+1负控：原固定头批即使重复读取，也不包含末项。
        try (var connection = applicationConnection(TENANT); var statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id','" + TENANT.value() + "',false)");
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var rows = statement.executeQuery("SELECT delegation_id FROM public.agent_runtime_delegations WHERE tenant_id=current_setting('app.tenant_id',true) AND state IN ('QUEUED','RUNNING','WAITING') ORDER BY updated_epoch_second,updated_nano,parent_run_id,delegation_id LIMIT 2")) {
                    var ids = new java.util.ArrayList<String>(); while (rows.next()) ids.add(rows.getString(1));
                    assertEquals(List.of("a", "b"), ids);
                }
            }
        }
        assertEquals(List.of(first, second), repository.active(TENANT, 2));
        assertEquals(List.of(last, first), repository.active(TENANT, 2));
        assertEquals(Optional.of(first), repository.read(parent, "a"), "扫描位置不能伪造更新时间或业务状态变化");
        var waits = List.of(new Wait("a",parent,List.of("a"),false,1),
                new Wait("b",parent,List.of("b"),false,1), new Wait("c",parent,List.of("c"),false,1));
        waits.forEach(repository::register);
        assertTrue(repository.update(transition(last, State.COMPLETED,
                Optional.of(new Result(State.COMPLETED,"ready","",1,List.of())),Optional.empty(),2,NOW.plusSeconds(1)),1));
        assertEquals(waits.subList(0,2), repository.pendingWaits(TENANT,2));
        assertEquals(List.of(waits.get(2),waits.get(0)), repository.pendingWaits(TENANT,2));
        var restarted = store();
        assertEquals(waits.subList(0,2), restarted.pendingWaits(TENANT,2));
        assertEquals(List.of(waits.get(2),waits.get(0)), restarted.pendingWaits(TENANT,2));
        assertEquals(waits.subList(0,2), store().pendingWaits(TENANT,2), "独立扫描者从自己的位置开始，交付仍由既有CAS保护");
    }

    @Test
    void waitsReplayOnlyTheSameBindingAndDeliveryIsIdempotentRevisionCas()
            throws Exception {
        var parent = parent(TENANT, "parent-wait", "trace-wait");
        var store = store();
        var first = store.create(queued(parent, "delegation-wait-a", "operation-wait-a"));
        var second = store.create(queued(parent, "delegation-wait-b", "operation-wait-b"));
        var registered = new Wait("wait-a", parent, List.of(first.id()), false, 1);

        assertEquals(registered, store.register(registered));
        assertEquals(registered, store.register(registered));
        assertEquals(Optional.of(registered), store.wait(parent, registered.id()));
        assertEquals(List.of(registered), store.pendingWaits(TENANT, 10));
        assertThrows(IllegalStateException.class, () -> store.register(
                new Wait(registered.id(), parent, List.of(second.id()), false, 1)));
        assertThrows(IllegalStateException.class, () -> store.register(
                new Wait("missing-wait", parent, List.of("missing"), false, 1)));

        assertThrows(IllegalStateException.class, () -> store.delivered(
                new Wait(registered.id(), parent, registered.delegationIds(), true, 2)));
        var firstCompleted = transition(first, State.COMPLETED,
                Optional.of(new Result(State.COMPLETED, "done", "", 1, List.of("run-state:first"))),
                Optional.empty(), 2, NOW.plusSeconds(1));
        assertTrue(store.update(firstCompleted, 1));
        confirmWait(registered, false);
        assertEquals(Optional.of(registered), store.wait(parent, registered.id()), "caller rollback keeps domain wait pending");
        confirmWait(registered, true);
        var secondCompleted = transition(second, State.COMPLETED,
                Optional.of(new Result(State.COMPLETED, "done", "", 1,
                        List.of("run-state:second"))), Optional.empty(), 2,
                NOW.plusSeconds(1));
        assertTrue(store.update(secondCompleted, 1));
        var secondWait = store.register(new Wait("wait-b", parent,
                List.of(second.id()), false, 1));
        var delivered = new Wait(registered.id(), parent,
                registered.delegationIds(), true, 2);
        assertTrue(store.delivered(delivered));
        assertTrue(store.delivered(delivered));
        assertEquals(delivered, store.register(registered));
        assertEquals(Optional.of(delivered), store.wait(parent, registered.id()));
        var secondDelivered = new Wait(secondWait.id(), secondWait.parent(),
                secondWait.delegationIds(), true, 2);
        assertThrows(IllegalStateException.class,
                () -> store.delivered(secondDelivered));
        // 只有完整身份匹配的真实typed父Run终态，才可关闭已失去用途的领域等待。
        finishParentRun(parent);
        assertTrue(store.delivered(new Wait(secondWait.id(), secondWait.parent(),
                secondWait.delegationIds(), true, 2)));
        assertTrue(store.pendingWaits(TENANT, 10).isEmpty());
        assertFalse(store.delivered(new Wait(registered.id(), parent,
                registered.delegationIds(), true, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> store.pendingWaits(TENANT, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.pendingWaits(TENANT, 101));
    }

    private static JdbcSessionDelegations store() {
        return store(TENANT);
    }

    private static JdbcSessionDelegations store(TenantId ignored) {
        return new JdbcSessionDelegations(applicationDataSource(), new TestCodec());
    }

    private static Entry queued(RunIdentity parent, String id, String operation) {
        return queuedAt(parent, id, operation, "child-" + id, "input-" + id,
                NOW);
    }

    private static Entry queuedAt(RunIdentity parent, String id, String operation,
            String childRunId, String childInputId, Instant createdAt) {
        var target = new Target("target-handle", new AgentId("target-agent"),
                "Target", "specialist", List.of(new Capability(
                        "skill", "research", "Research facts")),
                new TypedPayload("delegation.target.v1", "{\"revision\":1}"));
        var task = new Task("answer the delegated question", "context",
                List.of("cite evidence"), "concise answer");
        return new Entry(id, parent, operation, target, task,
                new RunRef(parent.runRef().tenantId(), childRunId),
                new InputId(childInputId), Optional.empty(), State.QUEUED,
                Optional.empty(), Optional.empty(), 1, createdAt, createdAt);
    }

    private static Entry copy(Entry source, String id, String operation,
            Target target, Task task, RunRef childRun, InputId childInput,
            Optional<String> previousId) {
        return new Entry(id, source.parent(), operation, target, task, childRun,
                childInput, previousId, source.state(), source.result(),
                source.cancelKey(), source.revision(), source.createdAt(),
                source.updatedAt());
    }

    private static Entry transition(Entry source, State state,
            Optional<Result> result, Optional<String> cancelKey, long revision,
            Instant updatedAt) {
        return new Entry(source.id(), source.parent(), source.operationKey(),
                source.target(), source.task(), source.childRun(),
                source.childInput(), source.previousId(), state, result, cancelKey,
                revision, source.createdAt(), updatedAt);
    }

    private static RunIdentity parent(TenantId tenant, String runId,
            String traceId) {
        var owner = SessionOwner.user(new SessionRef(tenant, "session-" + runId),
                SessionTarget.team("team-" + runId), new EndUserId(41));
        return parent(owner, runId, traceId);
    }

    private static RunIdentity parent(SessionOwner owner, String runId,
            String traceId) {
        var tenant = owner.sessionRef().tenantId();
        return new RunIdentity(owner, new AgentId("parent-agent"), new InputId("causation-" + runId), new RunRef(tenant, runId), new TraceEnvelope(traceId, "span-" + runId,
                        new InputId("root-" + runId),
                        Optional.of(new EventId("cause-" + runId))), new ResultRef("identity-" + runId), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId("causation-" + runId).value()));
    }

    /** 正式typed Store准入/claim/finish，不能直接UPDATE父Run伪造终态。 */
    private static void finishParentRun(RunIdentity parent) {
        var identity = new com.skillforge.runtime.api.port.VerifiedIdentity(
                new com.skillforge.runtime.api.PrincipalRef.SystemPrincipal(parent.runRef().tenantId(), "delegation-test"),
                parent.owner(), java.util.Set.of("sessions.write"), 1, parent.causationIdentityRef());
        identities.register(identity);
        var store = new com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore(applicationDataSource(), identities,
                (sql, run, actor, prepared) -> true);
        var capacity = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources(1, 1, 1, 1, 100, 100, 200);
        var budget = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(parent.runRef(), parent.runRef(),
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Limits(
                        com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.TEAM, capacity, capacity, capacity, 60, 60, "delegation-parent-fixture:v1")));
        var input = new com.skillforge.runtime.session.spi.ExecutionStore.Material(new ResultRef(parent.runRef().runId() + ":input"), new TypedPayload("fixture.input.v1", "{}"));
        var context = new com.skillforge.runtime.session.spi.ExecutionStore.Material(new ResultRef(parent.runRef().runId() + ":context"), new TypedPayload("fixture.context.v1", "{}"));
        assertTrue(store.admit(new com.skillforge.runtime.session.spi.ExecutionStore.Admission(identity, parent,
                "delegation-parent-fingerprint", new ResultRef("fixture-profile"), input, context, budget, List.of(), List.of())).created());
        var run = store.claim(new com.skillforge.runtime.session.spi.ExecutionStore.Claim(parent.runRef(), "worker", "claim", java.time.Duration.ofMinutes(1))).orElseThrow();
        store.finish(new com.skillforge.runtime.session.spi.ExecutionStore.Finish(parent.runRef(), run.revision(), run.lease().orElseThrow(), com.skillforge.runtime.api.RunStatus.COMPLETED, List.of()));
    }

    /** 独立验证借用事务的真实领域确认；typed依赖接受的完整组合另由Store/Host用例覆盖。 */
    private static void confirmWait(Wait expected, boolean commit) {
        var config = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "delegation-confirm-fixture", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), applicationDataSource()));
        config.setMapUnderscoreToCamelCase(true);
        JdbcSessionDelegations.configure(config);
        var factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        try (var sql = factory.openSession(false)) {
            sql.getMapper(SessionDelegationsMapper.class).bindTenant(expected.parent().runRef().tenantId().value());
            var repository = store();
            assertEquals(expected.delegationIds().size(), repository.requireWait(sql, expected).size());
            repository.confirmDelivery(sql, expected);
            if (commit) sql.commit(true); else sql.rollback(true);
        }
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("delegation_test_latch_timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("delegation_test_interrupted",
                    interrupted);
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection applicationConnection(TenantId ignored)
            throws Exception {
        return applicationDataSource().getConnection();
    }

    private static DataSource applicationDataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {POSTGRES.getHost()});
        dataSource.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
        dataSource.setDatabaseName(POSTGRES.getDatabaseName());
        dataSource.setUser(APP_ROLE);
        dataSource.setPassword(APP_PASSWORD);
        return dataSource;
    }

    /** 与宿主 Runtime 相同的 Jdk8 Optional 和 Java Time JSON 模块组合。 */
    private static final class TestCodec implements JdbcSessionDelegations.Codec {
        private final ObjectMapper json = SessionSubjectJsonFixture.configure(new ObjectMapper())
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        @Override
        public String encodeEntry(Entry entry) {
            return write(entry);
        }

        @Override
        public Entry decodeEntry(String snapshot) {
            return read(snapshot, Entry.class);
        }

        @Override
        public String encodeWait(Wait wait) {
            return write(wait);
        }

        @Override
        public Wait decodeWait(String snapshot) {
            return read(snapshot, Wait.class);
        }

        private String write(Object value) {
            try {
                return json.writeValueAsString(value);
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException(invalid);
            }
        }

        private <T> T read(String value, Class<T> type) {
            try {
                return json.readValue(value, type);
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException(invalid);
            }
        }

    }
}
