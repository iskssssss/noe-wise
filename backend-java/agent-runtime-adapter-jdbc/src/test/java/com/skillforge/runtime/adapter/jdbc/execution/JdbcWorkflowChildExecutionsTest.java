package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseRequest;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLookup;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWriteResult;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 当前 typed Run 正式准入后的个人 Workflow 子 Execution 原子创建、重放与来源验收。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcWorkflowChildExecutionsTest {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();

    private static final String APP_ROLE = "workflow_child_executions_test";
    private static final String APP_PASSWORD = "workflow_child_executions_password";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge")
                    .withUsername("skillforge_admin")
                    .withPassword("skillforge_admin_password");

    @BeforeAll
    static void initializeDatabase() throws Exception {
        CurrentRuntimeDatabase.migrate(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
            statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);
        }
        try (Connection connection = applicationDataSource().getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(result.next()); assertTrue(result.getBoolean(1));
        }
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        public.agent_runtime_workflow_child_execution_sources,
                        public.agent_runtime_workflow_child_preparations,
                        public.agent_runtime_execution_publication_mutations,
                        public.agent_runtime_execution_state_publications,
                        public.agent_runtime_execution_mutations,
                        public.agent_runtime_executions,
                        public.agent_runtime_execution_runs,
                        public.agent_runtime_execution_sessions,
                        public.agent_runtime_budget_roots,
                        public.agent_runtime_session_owners
                    CASCADE
                    """);
        }
    }

    @Test
    void atomicallyCreatesChildrenAndReplaysOnlyTheOriginalPreparation() {
        Fixture fixture = runningFixture("create");
        AtomicInteger sourceChecks = new AtomicInteger();
        var store = new JdbcWorkflowChildExecutions(
                applicationDataSource(), (identity, parent, preparation) -> {
                    assertEquals(fixture.identity(), identity);
                    assertEquals(fixture.running(), parent);
                    assertEquals(fixture.preparation(), preparation);
                    sourceChecks.incrementAndGet();
                });

        assertEquals(fixture.preparation(), store.prepare(
                fixture.identity(), fixture.lease(),
                fixture.running().executionRevision(), fixture.preparation()));
        assertEquals(Optional.of(fixture.preparation()), store.find(
                fixture.identity(), fixture.preparation().parent()));
        assertEquals(1, count("agent_runtime_workflow_child_preparations"));
        assertEquals(2, count("agent_runtime_workflow_child_execution_sources"));

        JdbcExecutionLedgerPort ledger = new JdbcExecutionLedgerPort(
                applicationDataSource());
        JdbcWorkflowChildExecutions.Child first =
                fixture.preparation().children().getFirst();
        ExecutionRecord originallyQueued = ledger.find(new ExecutionLookup(
                fixture.identity(), first.command().executionRef())).orElseThrow();
        assertEquals(WorkflowExecutionState.QUEUED, originallyQueued.state());
        ExecutionLeaseResult.Acquired acquired = assertInstanceOf(
                ExecutionLeaseResult.Acquired.class,
                ledger.acquireLease(new ExecutionLeaseRequest(
                        fixture.identity(), first.command().executionRef(),
                        "child-worker", Duration.ofMinutes(5), Instant.now())));
        TypedPayload progressed = new TypedPayload(
                "workflow.child.checkpoint.v1", "{\"cursor\":1}");
        assertInstanceOf(ExecutionWriteResult.Applied.class,
                ledger.transition(ExecutionCommands.transition(
                        acquired.execution(), acquired.lease(),
                        WorkflowExecutionState.RUNNING, progressed,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        new EventId("child-started-create"), Instant.now())));

        var expectedSource = new JdbcWorkflowChildExecutions.ChildSource(
                fixture.preparation(), first);
        assertEquals(Optional.of(expectedSource), store.source(
                fixture.identity(), first.command().executionRef()));
        // 传入的是旧 QUEUED 快照；来源核验只比较固定身份，不比较已推进的状态字段。
        assertEquals(Optional.of(expectedSource), store.source(originallyQueued));

        assertEquals(fixture.preparation(), store.prepare(
                fixture.identity(), fixture.lease(),
                fixture.running().executionRevision(), fixture.preparation()));
        assertEquals(2, sourceChecks.get());
        assertEquals(2, createMutationCount(fixture.tenant()));

        var changed = new JdbcWorkflowChildExecutions.Preparation(
                fixture.preparation().parent(),
                fixture.preparation().definitionRevision() + 1,
                fixture.preparation().installationRevision(),
                fixture.preparation().sourceCheckpoint(),
                fixture.preparation().children());
        assertThrows(IllegalStateException.class, () -> store.prepare(
                fixture.identity(), fixture.lease(),
                fixture.running().executionRevision(), changed));

        VerifiedIdentity otherIdentity = otherIdentity(fixture);
        assertTrue(store.find(otherIdentity, fixture.preparation().parent()).isEmpty());
        assertTrue(store.source(
                otherIdentity, first.command().executionRef()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.prepare(
                otherIdentity, fixture.lease(),
                fixture.running().executionRevision(), fixture.preparation()));

        ExecutionRecord forged = withExecutionUnit(
                originallyQueued, originallyQueued.executionUnitId() + "-forged");
        assertTrue(store.source(forged).isEmpty());
    }

    @Test
    void rejectsAnOldFenceBeforeCreatingAnyChild() {
        Fixture fixture = runningFixture("old-fence");
        var store = new JdbcWorkflowChildExecutions(
                applicationDataSource(), (identity, parent, preparation) -> {});
        LeaseFence current = fixture.lease().fence();
        ExecutionLease oldFence = new ExecutionLease(
                fixture.lease().executionRef(),
                new LeaseFence(
                        current.tenantId(), current.claimId(), current.workerId(),
                        current.fencingToken() + 1, current.leaseUntil()),
                fixture.lease().executionRevision());

        assertThrows(IllegalStateException.class, () -> store.prepare(
                fixture.identity(), oldFence,
                fixture.running().executionRevision(), fixture.preparation()));
        assertEquals(0, count("agent_runtime_workflow_child_preparations"));
        assertEquals(0, count("agent_runtime_workflow_child_execution_sources"));
        assertEquals(1, executionCount(fixture.tenant()));
    }

    @Test
    void rollsBackEarlierChildrenWhenAnyChildWasAlreadyCreatedElsewhere() {
        Fixture fixture = runningFixture("rollback");
        JdbcWorkflowChildExecutions.Child first =
                fixture.preparation().children().getFirst();
        JdbcWorkflowChildExecutions.Child second =
                fixture.preparation().children().get(1);
        JdbcExecutionLedgerPort ledger = new JdbcExecutionLedgerPort(
                applicationDataSource());
        assertInstanceOf(ExecutionWriteResult.Applied.class,
                ledger.create(second.command()));

        var store = new JdbcWorkflowChildExecutions(
                applicationDataSource(), (identity, parent, preparation) -> {});
        assertThrows(IllegalStateException.class, () -> store.prepare(
                fixture.identity(), fixture.lease(),
                fixture.running().executionRevision(), fixture.preparation()));

        assertTrue(ledger.find(new ExecutionLookup(
                fixture.identity(), first.command().executionRef())).isEmpty());
        assertTrue(ledger.find(new ExecutionLookup(
                fixture.identity(), second.command().executionRef())).isPresent());
        assertTrue(store.find(
                fixture.identity(), fixture.preparation().parent()).isEmpty());
        assertTrue(store.source(
                fixture.identity(), first.command().executionRef()).isEmpty());
        assertEquals(0, count("agent_runtime_workflow_child_preparations"));
        assertEquals(0, count("agent_runtime_workflow_child_execution_sources"));
        assertEquals(2, executionCount(fixture.tenant()));
    }

    /** 真实typed Run取消直接阻止新的子Execution，不依靠旧Run表状态或假fixture字段。 */
    @Test
    void cancelledTypedRunRejectsNewChildPreparation() {
        Fixture fixture = runningFixture("cancelled");
        var cancelled = typedStore().cancel(new ExecutionStore.Cancel(fixture.identity(), fixture.running().owner().runRef(),
                new InputId("cancel-workflow-children"), List.of()));
        assertTrue(cancelled.cancelled());
        var store = new JdbcWorkflowChildExecutions(applicationDataSource(), (identity, parent, preparation) -> {});
        assertThrows(IllegalStateException.class, () -> store.prepare(fixture.identity(), fixture.lease(),
                fixture.running().executionRevision(), fixture.preparation()));
        assertEquals(0, count("agent_runtime_workflow_child_preparations"));
        assertEquals(0, count("agent_runtime_workflow_child_execution_sources"));
        assertEquals(1, executionCount(fixture.tenant()));
    }

    private static Fixture runningFixture(String suffix) {
        TenantId tenant = new TenantId("child-executions-" + suffix);
        AgentId agent = new AgentId("agent-" + suffix);
        SessionOwner session = SessionOwner.user(
                new SessionRef(tenant, "session-" + suffix), agent,
                new EndUserId(1001));
        InputId input = new InputId("input-" + suffix);
        RunIdentity owner = new RunIdentity(session, session.target().requireAgent(), input, new RunRef(tenant, "run-" + suffix), new TraceEnvelope("trace-" + suffix, "span-" + suffix,
                        input, Optional.empty()), new ResultRef("identity-" + suffix), new RunRequester.EndUser(session.requireUser()), Optional.empty(), new RunOriginRef(session.sessionRef().tenantId(), input.value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(tenant, session.requireUser()),
                session, Set.of("execution.read", "execution.write"), 1,
                new ResultRef("verification-" + suffix));
        establishRunRoot(owner, identity);

        TypedPayload checkpoint = new TypedPayload(
                "workflow.checkpoint.v1", "{\"cursor\":0}");
        ExecutionRef parentRef = new ExecutionRef(
                tenant, "parent-execution-" + suffix);
        Instant requestedAt = Instant.now();
        JdbcExecutionLedgerPort ledger = new JdbcExecutionLedgerPort(
                applicationDataSource());
        CreateExecution parentCreate = ExecutionCommands.create(
                parentRef, owner, "parent-workflow-unit", "definition:parent:v7",
                ExecutionMode.FOREGROUND, Optional.empty(), Optional.empty(),
                checkpoint, "parent-create-" + suffix,
                new EventId("parent-queued-" + suffix), requestedAt);
        assertInstanceOf(ExecutionWriteResult.Applied.class,
                ledger.create(parentCreate));
        ExecutionLeaseResult.Acquired acquired = assertInstanceOf(
                ExecutionLeaseResult.Acquired.class,
                ledger.acquireLease(new ExecutionLeaseRequest(
                        identity, parentRef, "parent-worker-" + suffix,
                        Duration.ofMinutes(5), requestedAt)));
        ExecutionRecord running = assertInstanceOf(
                ExecutionWriteResult.Applied.class,
                ledger.transition(ExecutionCommands.transition(
                        acquired.execution(), acquired.lease(),
                        WorkflowExecutionState.RUNNING, checkpoint,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        new EventId("parent-started-" + suffix), requestedAt)))
                .execution();

        WorkflowNodeCallIdentity parent = new WorkflowNodeCallIdentity(
                owner, parentRef, "parallel-node", 1, 1);
        List<JdbcWorkflowChildExecutions.Child> children = List.of(
                child(owner, tenant, suffix, "left", 1, requestedAt),
                child(owner, tenant, suffix, "right", 2, requestedAt));
        var preparation = new JdbcWorkflowChildExecutions.Preparation(
                parent, 7, 9, checkpoint, children);
        return new Fixture(
                tenant, identity, running, acquired.lease(), preparation);
    }

    private static JdbcWorkflowChildExecutions.Child child(
            RunIdentity owner,
            TenantId tenant,
            String suffix,
            String branch,
            int ordinal,
            Instant requestedAt) {
        TypedPayload initial = new TypedPayload(
                "workflow.child.checkpoint.v1",
                "{\"branch\":\"" + branch + "\"}");
        CreateExecution command = ExecutionCommands.create(
                new ExecutionRef(tenant,
                        "child-execution-" + suffix + "-" + ordinal),
                owner, "child-unit-" + ordinal,
                "definition:child:v" + ordinal, ExecutionMode.BACKGROUND,
                Optional.empty(), Optional.empty(), initial,
                "child-create-" + suffix + "-" + ordinal,
                new EventId("child-queued-" + suffix + "-" + ordinal),
                requestedAt);
        return new JdbcWorkflowChildExecutions.Child(branch, command);
    }

    private static VerifiedIdentity otherIdentity(Fixture fixture) {
        SessionOwner owner = SessionOwner.user(
                new SessionRef(fixture.tenant(), "another-session"),
                fixture.running().owner().agentId(), new EndUserId(2002));
        return new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(
                        fixture.tenant(), owner.requireUser()),
                owner, Set.of("execution.read", "execution.write"), 1,
                new ResultRef("another-verification"));
    }

    private static ExecutionRecord withExecutionUnit(
            ExecutionRecord source, String executionUnitId) {
        return new ExecutionRecord(
                source.executionRef(), source.owner(), executionUnitId,
                source.definitionVersion(), source.mode(),
                source.causativeActionRef(), source.delegationRef(), source.state(),
                source.executionRevision(), source.checkpoint(), source.waiting(),
                source.resultRef(), source.reasonCode(), source.lease(),
                source.cancelRequestId(), source.cancelRequestedAt(),
                source.createdAt(), source.updatedAt());
    }

    private static int count(String table) {
        if (!Set.of(
                "agent_runtime_workflow_child_preparations",
                "agent_runtime_workflow_child_execution_sources").contains(table)) {
            throw new IllegalArgumentException("unknown table");
        }
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM public." + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int executionCount(TenantId tenant) {
        try (Connection connection = adminConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM public.agent_runtime_executions "
                                + "WHERE tenant_id = ?")) {
            statement.setString(1, tenant.value());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int createMutationCount(TenantId tenant) {
        try (Connection connection = adminConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*)
                          FROM public.agent_runtime_execution_mutations
                         WHERE tenant_id = ?
                           AND execution_id LIKE 'child-execution-%'
                           AND mutation_kind = 'CREATE'
                        """)) {
            statement.setString(1, tenant.value());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 原Run通过唯一typed Store准入；没有旧Run/Inbox/input ledger占位记录。 */
    private static void establishRunRoot(RunIdentity owner, VerifiedIdentity actor) {
        identities.register(actor);
        var capacity = new ExecutionBudgetPort.Resources(1, 0, 0, 0, 1_000, 500, 1_500);
        var budget = new ExecutionBudgetPort.Registration(owner.runRef(), owner.runRef(), ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity,
                        60, 60, "workflow-user-child-fixture-v1")));
        var admission = new ExecutionStore.Admission(actor, owner, "admission:" + owner.runRef().runId(),
                new ResultRef("config:" + owner.runRef().runId()),
                new ExecutionStore.Material(new ResultRef("input:" + owner.causationInputId().value()), new TypedPayload("test.input.v1", "{}")),
                new ExecutionStore.Material(new ResultRef("context:" + owner.runRef().runId()), new TypedPayload("test.context.v1", "{}")),
                budget, List.of(), List.of());
        var accepted = typedStore().admit(admission);
        assertTrue(accepted.created()); assertEquals(owner, accepted.run().identity());
        assertEquals(owner, typedStore().load(owner.runRef()).orElseThrow().identity());
        try (Connection connection = adminConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.agent_runtime_runs WHERE tenant_id=? AND run_id=?")) {
            query.setString(1, owner.runRef().tenantId().value()); query.setString(2, owner.runRef().runId());
            try (ResultSet result = query.executeQuery()) { assertTrue(result.next()); assertEquals(0, result.getInt(1)); }
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private static JdbcExecutionStore typedStore() {
        return new JdbcExecutionStore(applicationDataSource(), identities, (session, run, actor, prepared) -> false);
    }

    private static DataSource applicationDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(APP_ROLE);
        dataSource.setPassword(APP_PASSWORD);
        return dataSource;
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private record Fixture(
            TenantId tenant,
            VerifiedIdentity identity,
            ExecutionRecord running,
            ExecutionLease lease,
            JdbcWorkflowChildExecutions.Preparation preparation) {}
}
