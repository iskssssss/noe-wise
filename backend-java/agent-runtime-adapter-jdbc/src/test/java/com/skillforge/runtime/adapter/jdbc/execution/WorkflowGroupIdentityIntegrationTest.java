package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 正式 typed Run 准入之后的 Workflow 群归属持久化、重建与组合外键验收。
 * 上游已核实的身份和来源是准入夹具值；不声称验证宿主凭据、群消息授权或 Provider。
 */
@Testcontainers(disabledWithoutDocker = false)
final class WorkflowGroupIdentityIntegrationTest {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();
    private static final String ROLE = "workflow_group_identity";
    private static final String PASSWORD = "isolated-workflow-group-password";
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("workflow_group_identity").withUsername("workflow_group_admin")
            .withPassword("isolated-workflow-owner-password");
    private static DataSource source;

    @BeforeAll
    static void database() throws Exception {
        CurrentRuntimeDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
            statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE);
            statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + ROLE);
        }
        var connections = new PGSimpleDataSource();
        connections.setURL(DATABASE.getJdbcUrl()); connections.setUser(ROLE); connections.setPassword(PASSWORD);
        source = connections;
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
            }
            for (String table : List.of("agent_runtime_execution_runs", "agent_runtime_executions",
                    "agent_runtime_workflow_child_preparations", "agent_runtime_workflow_child_execution_sources")) {
                try (var rows = statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='public." + table + "'::regclass")) {
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                }
            }
        }
    }

    @Test
    void groupWorkflowRetainsCompleteTypedRunIdentityAcrossLedgerRestartAndProgress() throws Exception {
        var fixture = admitted("ledger", new TenantId("workflow-group-" + UUID.randomUUID()));
        var ledger = new JdbcExecutionLedgerPort(source);
        var create = create(fixture.run(), "parent-" + fixture.suffix(), ExecutionMode.FOREGROUND);
        var initial = assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.create(create)).execution();
        assertEquals(fixture.run(), initial.owner());
        assertEquals(initial, assertInstanceOf(ExecutionWriteResult.Replayed.class, ledger.create(create)).execution());
        var acquired = assertInstanceOf(ExecutionLeaseResult.Acquired.class, ledger.acquireLease(new ExecutionLeaseRequest(
                fixture.actor(), create.executionRef(), "workflow-group-worker", Duration.ofMinutes(2), Instant.now())));
        var running = assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.transition(ExecutionCommands.transition(
                acquired.execution(), acquired.lease(), WorkflowExecutionState.RUNNING, checkpoint("running"),
                Optional.empty(), Optional.empty(), Optional.empty(), new EventId("started-" + fixture.suffix()), Instant.now()))).execution();
        var rebuilt = new JdbcExecutionLedgerPort(source);
        var reloaded = rebuilt.find(new ExecutionLookup(fixture.actor(), create.executionRef())).orElseThrow();
        assertEquals(running, reloaded);
        assertEquals(fixture.run(), reloaded.owner());
        assertInstanceOf(SessionSubject.Group.class, reloaded.owner().owner().subject());
        assertEquals(new RunRequester.EndUser(new EndUserId(501)), reloaded.owner().requester());
        assertEquals(fixture.run().sourceMessageRef(), reloaded.owner().sourceMessageRef());
        assertEquals(fixture.run().originRef(), reloaded.owner().originRef());
        assertEquals(fixture.run(), typedStore().load(fixture.run().runRef()).orElseThrow().identity());
        try (var connection = tenantConnection(fixture.run().runRef().tenantId());
                var query = connection.prepareStatement("SELECT end_user_id,subject_kind,subject_id,target_kind FROM agent_runtime_executions WHERE execution_id=?")) {
            query.setString(1, create.executionRef().executionId());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertNull(rows.getObject("end_user_id"));
                assertEquals("GROUP", rows.getString("subject_kind"));
                assertEquals(fixture.run().owner().target().id(), rows.getString("subject_id"));
                assertEquals("GROUP", rows.getString("target_kind"));
            }
        }
        var foreign = new TenantId("workflow-group-other-" + UUID.randomUUID());
        try (var connection = tenantConnection(foreign); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM agent_runtime_executions")) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
    }

    @Test
    void groupWorkflowChildPreparationAndSourceRebuildPreserveOriginalRequesterAndSource() {
        var fixture = admitted("children", new TenantId("workflow-group-" + UUID.randomUUID()));
        var ledger = new JdbcExecutionLedgerPort(source);
        var parent = assertInstanceOf(ExecutionWriteResult.Applied.class,
                ledger.create(create(fixture.run(), "parent-" + fixture.suffix(), ExecutionMode.FOREGROUND))).execution();
        var acquired = assertInstanceOf(ExecutionLeaseResult.Acquired.class, ledger.acquireLease(new ExecutionLeaseRequest(
                fixture.actor(), parent.executionRef(), "group-parent-worker", Duration.ofMinutes(2), Instant.now())));
        var running = assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.transition(ExecutionCommands.transition(
                acquired.execution(), acquired.lease(), WorkflowExecutionState.RUNNING, parent.checkpoint(),
                Optional.empty(), Optional.empty(), Optional.empty(), new EventId("parent-running-" + fixture.suffix()), Instant.now()))).execution();
        var node = new WorkflowNodeCallIdentity(fixture.run(), running.executionRef(), "parallel", 1, 1);
        var first = new JdbcWorkflowChildExecutions.Child("left",
                create(fixture.run(), "left-" + fixture.suffix(), ExecutionMode.BACKGROUND));
        var second = new JdbcWorkflowChildExecutions.Child("right",
                create(fixture.run(), "right-" + fixture.suffix(), ExecutionMode.BACKGROUND));
        var preparation = new JdbcWorkflowChildExecutions.Preparation(node, 3, 5, running.checkpoint(), List.of(first, second));
        var checks = new AtomicInteger();
        var children = new JdbcWorkflowChildExecutions(source, (actor, current, prepared) -> {
            assertEquals(fixture.actor(), actor); assertEquals(fixture.run(), current.owner());
            assertEquals(preparation, prepared); checks.incrementAndGet();
        });
        assertEquals(preparation, children.prepare(fixture.actor(), acquired.lease(), running.executionRevision(), preparation));
        assertEquals(preparation, children.prepare(fixture.actor(), acquired.lease(), running.executionRevision(), preparation));
        assertTrue(checks.get() > 0);
        var rebuilt = new JdbcWorkflowChildExecutions(source, (actor, current, prepared) -> {
            assertEquals(fixture.run(), current.owner());
        });
        assertEquals(preparation, rebuilt.find(fixture.actor(), node).orElseThrow());
        var rebuiltLedger = new JdbcExecutionLedgerPort(source);
        for (var child : List.of(first, second)) {
            var record = rebuiltLedger.find(new ExecutionLookup(fixture.actor(), child.command().executionRef())).orElseThrow();
            assertEquals(fixture.run(), record.owner());
            assertEquals(new JdbcWorkflowChildExecutions.ChildSource(preparation, child), rebuilt.source(record).orElseThrow());
            assertEquals(fixture.run().requester(), record.owner().requester());
            assertEquals(fixture.run().sourceMessageRef(), record.owner().sourceMessageRef());
            assertEquals(fixture.run().originRef(), record.owner().originRef());
        }
        assertEquals(fixture.run(), typedStore().load(fixture.run().runRef()).orElseThrow().identity());
    }

    @Test
    void nonNullGroupCompositeForeignKeyRejectsAnotherGroupOrActualAgentForAnExistingRun() throws Exception {
        var tenant = new TenantId("workflow-group-" + UUID.randomUUID());
        var original = admitted("original", tenant);
        var otherGroup = admitted("other", tenant);
        var ledger = new JdbcExecutionLedgerPort(source);
        var wrongGroup = new RunIdentity(otherGroup.run().owner(), original.run().agentId(), original.run().causationInputId(),
                original.run().runRef(), original.run().trace(), original.run().causationIdentityRef(), original.run().requester(),
                original.run().sourceMessageRef(), original.run().originRef());
        var wrongAgent = new RunIdentity(original.run().owner(), new AgentId("unrelated-agent"), original.run().causationInputId(),
                original.run().runRef(), original.run().trace(), original.run().causationIdentityRef(), original.run().requester(),
                original.run().sourceMessageRef(), original.run().originRef());
        for (var wrong : List.of(wrongGroup, wrongAgent)) {
            var command = create(wrong, "forged-" + UUID.randomUUID(), ExecutionMode.FOREGROUND);
            assertSqlState(assertThrows(RuntimeException.class, () -> ledger.create(command)), "23503");
            assertTrue(ledger.find(new ExecutionLookup(original.actor(), command.executionRef())).isEmpty());
        }
        try (var connection = tenantConnection(tenant); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM agent_runtime_executions")) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
        assertEquals(original.run(), typedStore().load(original.run().runRef()).orElseThrow().identity());
        assertEquals(otherGroup.run(), typedStore().load(otherGroup.run().runRef()).orElseThrow().identity());
    }

    /** 所有 Workflow 创建均在正式 typed Store 已成功受理之后，未写入旧 Run 或伪造个人 owner。 */
    private Fixture admitted(String label, TenantId tenant) {
        String suffix = label + "-" + UUID.randomUUID();
        var session = new SessionRef(tenant, "session-" + suffix);
        var group = new GroupBindingRef(tenant, "group-" + suffix);
        var owner = new SessionOwner(session, SessionSubject.group(group), SessionTarget.group(group));
        var input = new InputId("input-" + suffix);
        var run = new RunRef(tenant, "run-" + suffix);
        var verification = new ResultRef("verification-" + suffix);
        var actor = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(tenant, "group-sp", "group-integration", 1),
                owner, Set.of("execution.read", "execution.write"), 1, verification);
        var identity = new RunIdentity(owner, new AgentId("agent-" + suffix), input, run,
                new TraceEnvelope("trace-" + suffix, "span-" + suffix, input, Optional.empty()), verification,
                new RunRequester.EndUser(new EndUserId(501)), Optional.of(new SourceMessageRef(session, "source-" + suffix)),
                new RunOriginRef(tenant, "group-trigger:trigger-" + suffix));
        var capacity = new ExecutionBudgetPort.Resources(1, 0, 0, 0, 1_000, 500, 1_500);
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity,
                        60, 60, "workflow-group-persistence-fixture-v1")));
        var admission = new ExecutionStore.Admission(actor, identity, "admission-" + suffix, new ResultRef("config-" + suffix),
                new ExecutionStore.Material(new ResultRef("input-" + suffix), checkpoint("input")),
                new ExecutionStore.Material(new ResultRef("context-" + suffix), checkpoint("context")), budget, List.of(), List.of());
        identities.register(actor);
        assertTrue(typedStore().admit(admission).created());
        return new Fixture(suffix, identity, actor);
    }

    private JdbcExecutionStore typedStore() {
        return new JdbcExecutionStore(source, identities, (session, run, actor, prepared) -> false);
    }

    private CreateExecution create(RunIdentity owner, String executionId, ExecutionMode mode) {
        return ExecutionCommands.create(new ExecutionRef(owner.runRef().tenantId(), executionId), owner,
                "group-workflow", "group-workflow:v1", mode, Optional.empty(), Optional.empty(), checkpoint("initial"),
                "create-" + executionId, new EventId("queued-" + executionId), Instant.now());
    }

    private TypedPayload checkpoint(String stage) {
        return new TypedPayload("test.workflow-group.v1", "{\"stage\":\"" + stage + "\"}");
    }

    /** 只在受限角色当前事务内设置 RLS 机构；关闭连接时未提交的只读事务自然回滚。 */
    private Connection tenantConnection(TenantId tenant) throws SQLException {
        var connection = source.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                query.setString(1, tenant.value()); query.execute();
            }
            return connection;
        } catch (SQLException failure) {
            connection.close(); throw failure;
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    private void assertSqlState(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && expected.equals(sql.getSQLState())) return;
        }
        fail("Expected PostgreSQL SQLSTATE " + expected, failure);
    }

    private record Fixture(String suffix, RunIdentity run, VerifiedIdentity actor) { }
}
