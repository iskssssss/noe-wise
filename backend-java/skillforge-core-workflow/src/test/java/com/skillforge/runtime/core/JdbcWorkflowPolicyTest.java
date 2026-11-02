package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.runtime.adapter.jdbc.execution.ExecutionCommands;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式schema、typed Store及Workflow Execution原准入共同证明当前IAM和完整owner控制。 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcWorkflowPolicyTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final ExecutionBudgetPort.Resources CAPACITY = new ExecutionBudgetPort.Resources(0, 0, 4, 0, 0, 0, 0);
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("typed_workflow_policy").withUsername("admin").withPassword("admin_password");
    private static HikariDataSource source;
    private JdbcIdentityPolicyPort identities;
    private JdbcWorkflowPolicy policy;
    private JdbcExecutionStore store;
    private JdbcExecutionLedgerPort ledger;

    @BeforeAll static void schema() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement()) {
            CurrentHostFixtures.agent(connection, 1, 10); CurrentHostFixtures.agent(connection, 1, 11);
            CurrentHostFixtures.endUser(connection, 1, 1); CurrentHostFixtures.endUser(connection, 1, 2);
            CurrentHostFixtures.agent(connection, 2, 20); CurrentHostFixtures.endUser(connection, 2, 3);
            CurrentHostFixtures.servicePrincipal(connection, 7001, 1, "workflow-policy-principal", "workflow-policy", 1,
                    "[\"sessions.write\",\"sessions.read\"]", "fixture-encrypted");
            CurrentHostFixtures.servicePrincipal(connection, 7002, 2, "workflow-policy-other", "workflow-policy-other", 1,
                    "[\"sessions.write\",\"sessions.read\"]", "fixture-encrypted");
            statement.execute("CREATE ROLE workflow_policy LOGIN PASSWORD 'workflow_policy_test_password' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO workflow_policy");
            statement.execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO workflow_policy");
            statement.execute("GRANT DELETE ON public.agent_runtime_dag_permits TO workflow_policy");
            statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO workflow_policy");
            statement.execute("GRANT EXECUTE ON FUNCTION resolve_runtime_authentication_tenant(text) TO workflow_policy");
        }
        var config = new HikariConfig(); config.setJdbcUrl(DATABASE.getJdbcUrl()); config.setUsername("workflow_policy");
        config.setPassword("workflow_policy_test_password"); config.setMaximumPoolSize(4); source = new HikariDataSource(config);
    }
    @AfterAll static void close() { if (source != null) source.close(); }
    @BeforeEach void prepare() throws Exception {
        execute("UPDATE nw_service_principals SET status='active',credential_version=1,scopes='[\"sessions.write\",\"sessions.read\"]'::jsonb WHERE id IN (7001,7002)");
        execute("UPDATE nw_agents SET status='published' WHERE id IN (10,11,20)");
        identities = new JdbcIdentityPolicyPort(source, Map.of());
        policy = new JdbcWorkflowPolicy(source, identities);
        ledger = new JdbcExecutionLedgerPort(source);
        // 本suite验证Policy当前IAM；Store派发夹具只确认真实事务租户，不替代宿主的能力挂载授权。
        store = new JdbcExecutionStore(source, identities, (session, run, actor, prepared) -> {
            try (var query = session.getConnection().createStatement(); var rows = query.executeQuery("SELECT current_setting('app.tenant_id',true)")) {
                return rows.next() && run.identity().runRef().tenantId().value().equals(rows.getString(1));
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        });
    }

    @Test void queryAndExecutionRequireRealExecutionAndEveryOwnerDimension() {
        // 身份在Session归属落定前由真实IAM合法签发；建立原owner后，漂移身份必须被当前事实拒绝。
        var owners = List.of(owner(TENANT, "another-session", "10", 1), owner(TENANT, "policy-owner", "11", 1),
                owner(TENANT, "policy-owner", "10", 2));
        var authenticatedOthers = owners.stream().map(this::current).toList();
        var f = fixture("owner", owner(TENANT, "policy-owner", "10", 1));
        var query = new WorkflowQuery(f.current().identity(), f.execution());
        assertFalse(policy.authorizeQuery(query)); assertFalse(policy.authorizeExecution(query));
        createExecution(f);
        assertTrue(policy.authorizeQuery(query)); assertTrue(policy.authorizeExecution(query));
        for (var other : authenticatedOthers) {
            assertFalse(policy.authorizeQuery(new WorkflowQuery(other.identity(), f.execution())));
            assertFalse(policy.authorizeExecution(new WorkflowQuery(other.identity(), f.execution())));
        }
        var foreign = current(owner(new TenantId("2"), "policy-owner", "20", 3));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowQuery(foreign.identity(), f.execution()));
        var original = f.current().identity();
        var forged = new VerifiedIdentity(original.principal(), original.owner(), Set.of("admin"), original.policyRevision(), original.verificationRef());
        assertFalse(policy.authorizeQuery(new WorkflowQuery(forged, f.execution())));
        assertFalse(policy.authorizeExecution(new WorkflowQuery(forged, f.execution())));
    }

    @Test void queryAndExecutionRecheckCurrentScopesCredentialAndAuthentication() throws Exception {
        var f = fixture("current-iam", owner(TENANT, "policy-current-iam", "10", 1)); createExecution(f);
        var query = new WorkflowQuery(f.current().identity(), f.execution());
        assertTrue(policy.authorizeQuery(query)); assertTrue(policy.authorizeExecution(query));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=7001");
        assertTrue(policy.authorizeQuery(query)); assertFalse(policy.authorizeExecution(query));
        execute("UPDATE nw_service_principals SET credential_version=2 WHERE id=7001");
        assertFalse(policy.authorizeQuery(query)); assertFalse(policy.authorizeExecution(query));
        execute("UPDATE nw_service_principals SET credential_version=1,scopes='[\"sessions.write\",\"sessions.read\"]'::jsonb WHERE id=7001");
        assertTrue(policy.authorizeQuery(query));
        identities.revoke(f.current().authentication());
        assertFalse(policy.authorizeQuery(query)); assertFalse(policy.authorizeExecution(query));
    }

    @Test void committedParentCancellationRequiresActualCancelledParentExactRunAndCurrentWriteIam() throws Exception {
        var differentOwner = current(owner(TENANT, "policy-committed-cancel", "10", 2));
        var f = fixture("committed-cancel", owner(TENANT, "policy-committed-cancel", "10", 1)); createExecution(f);
        var resource = cancelResource(f, f.registered().run().identity());
        assertFalse(policy.authorizeCommittedCancel(f.current().identity(), f.registered().run(), resource));
        var cancelled = store.cancel(new Cancel(f.current().identity(), f.registered().run().identity().runRef(), new InputId("parent-cancel"), List.of()));
        assertEquals(RunStatus.CANCELLED, cancelled.status()); assertEquals(Phase.FINISHED, cancelled.phase());
        assertTrue(policy.authorizeCommittedCancel(f.current().identity(), cancelled, resource));
        var identity = cancelled.identity();
        var otherRun = new RunIdentity(identity.owner(), identity.agentId(), identity.causationInputId(), new RunRef(TENANT, "other-parent-run"), identity.trace(), identity.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(identity.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(identity.owner().sessionRef().tenantId(), identity.causationInputId().value()));
        assertFalse(policy.authorizeCommittedCancel(f.current().identity(), cancelled, cancelResource(f, otherRun)));
        var otherInput = new RunIdentity(identity.owner(), identity.agentId(), new InputId("other-input"), identity.runRef(), identity.trace(), identity.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(identity.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(identity.owner().sessionRef().tenantId(), new InputId("other-input").value()));
        assertFalse(policy.authorizeCommittedCancel(f.current().identity(), cancelled, cancelResource(f, otherInput)));
        assertFalse(policy.authorizeCommittedCancel(differentOwner.identity(), cancelled, resource));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=7001");
        assertFalse(policy.authorizeCommittedCancel(f.current().identity(), cancelled, resource));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.write\",\"sessions.read\"]'::jsonb WHERE id=7001");
        identities.revoke(f.current().authentication());
        assertFalse(policy.authorizeCommittedCancel(f.current().identity(), cancelled, resource));
    }

    @Test void currentAgentRevocationBlocksSavedExecutionWithoutChangingItsOwner() throws Exception {
        var f = fixture("agent-revoked", owner(TENANT, "policy-agent-revoked", "10", 1)); createExecution(f);
        var query = new WorkflowQuery(f.current().identity(), f.execution());
        assertTrue(policy.authorizeExecution(query));
        execute("UPDATE nw_agents SET status='draft' WHERE id=10");
        assertFalse(policy.authorizeQuery(query)); assertFalse(policy.authorizeExecution(query));
        assertEquals(f.registered().run().identity(), ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.current().identity(), f.execution())).orElseThrow().owner());
    }

    @Test void borrowedExecutionAuthorizationUsesCallerTenantTransactionAndSeesCurrentRevocation() throws Exception {
        var f = fixture("borrowed", owner(TENANT, "policy-borrowed", "10", 1)); createExecution(f);
        var record = ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.current().identity(), f.execution())).orElseThrow();
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "workflow-policy-borrowed-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        // 与生产Store相同的映射配置；缺少camel映射会把真实identity_snapshot等列读成null并正确拒绝。
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        JdbcIdentityPolicyPort.configure(configuration);
        var sessions = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        try (var session = sessions.openSession(false)) {
            try (var bind = session.getConnection().prepareStatement("SELECT set_config('app.tenant_id','1',true)")) { bind.execute(); }
            assertTrue(policy.authorizeExecutionBorrowed(session, f.current().identity(), record));
            var wrong = current(owner(TENANT, "policy-borrowed-other", "10", 1));
            assertFalse(policy.authorizeExecutionBorrowed(session, wrong.identity(), record));
            try (var revokeWrite = session.getConnection().createStatement()) {
                assertEquals(1, revokeWrite.executeUpdate("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=7001"));
            }
            assertFalse(policy.authorizeExecutionBorrowed(session, f.current().identity(), record),
                    "借用授权必须看到调用方事务内尚未提交的撤权");
            assertTrue(policy.authorizeExecution(new WorkflowQuery(f.current().identity(), f.execution())),
                    "借用授权不得提交调用方事务，独立连接仍只看到原已提交权限");
            try (var restoreWrite = session.getConnection().createStatement()) {
                assertEquals(1, restoreWrite.executeUpdate("UPDATE nw_service_principals SET scopes='[\"sessions.write\",\"sessions.read\"]'::jsonb WHERE id=7001"));
            }
            assertTrue(policy.authorizeExecutionBorrowed(session, f.current().identity(), record));
            identities.revoke(f.current().authentication());
            assertFalse(policy.authorizeExecutionBorrowed(session, f.current().identity(), record));
            try (var query = session.getConnection().createStatement(); var rows = query.executeQuery("SELECT current_setting('app.tenant_id',true)")) {
                assertTrue(rows.next()); assertEquals("1", rows.getString(1));
            }
            session.commit();
        }
    }

    private Fixture fixture(String label, SessionOwner owner) {
        var current = current(owner); var input = new InputId("input:" + label); var run = new RunRef(owner.sessionRef().tenantId(), "run:" + label);
        var identity = new RunIdentity(owner, new AgentId("10"), input, run, new TraceEnvelope("trace:" + label, "span:" + label, input, Optional.empty()), current.identity().verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        // 正式入口先建立真实Session owner；typed Run受理不代替独立Session生命周期。
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, new JdbcSessionLogPort(source).append(
                new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner, input,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        new SessionEventDraft(new EventId("session-opened:" + label), "session.opened", "1", "session-opened:" + label,
                                identity.trace(), new TypedPayload("test.session.v1", "{}"), Instant.now()))));
        var capacity = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 3600, 3600, "workflow-policy:v2");
        var admitted = store.admit(new Admission(current.identity(), identity, "admission:" + label, new ResultRef("profile:" + label),
                material("input:" + label), material("context:" + label),
                new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(capacity)), List.of(), List.of())).run();
        var claimed = store.claim(new Claim(run, "workflow-policy-worker", "claim:" + label, Duration.ofMinutes(5))).orElseThrow();
        var prepared = new Prepared(new ActionRef(run.tenantId(), "workflow-action:" + label), 1, Kind.WORKFLOW, "workflow:fixture",
                new ResultRef("capability:" + label), material("request:" + label), "a".repeat(64));
        var demand = new ExecutionBudgetPort.Demand(run, "attempt:" + label, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(new Dispatch(run, claimed.revision(), claimed.lease().orElseThrow(), prepared,
                demand.attemptId(), demand, current.identity(), Optional.empty(), List.of())));
        var snapshot = new CapabilitySnapshotRef(owner.sessionRef(), input, "snapshot:" + label, 1);
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:fixture"), "200", 1,
                new CapabilityRef(CapabilityType.WORKFLOW, "50", "100"), Duration.ofSeconds(30), 1L);
        return new Fixture(current, registered, prepared, snapshot, target, new ExecutionRef(run.tenantId(), "execution:" + label));
    }
    private void createExecution(Fixture f) {
        assertInstanceOf(ExecutionLedgerPort.ExecutionWriteResult.Applied.class, ledger.create(ExecutionCommands.create(f.execution(), f.registered().run().identity(),
                f.target().instanceId(), "1", ExecutionMode.BACKGROUND, Optional.of(f.prepared().action()), Optional.empty(),
                new TypedPayload("application/json", "{}"), f.prepared().fingerprint(), new EventId("execution-create:" + f.execution().executionId()), Instant.now())));
    }
    private TypedPayload cancelResource(Fixture f, RunIdentity identity) {
        var snapshot = new CapabilitySnapshotRef(identity.owner().sessionRef(), identity.causationInputId(), f.snapshot().snapshotId(), f.snapshot().generation());
        return WorkflowPolicyResource.cancelResource(identity, snapshot, f.target(), f.execution(), "original-action-key", 1, "parent_cancelled",
                f.prepared().action(), f.prepared().request().reference(), f.prepared().fingerprint());
    }
    private Current current(SessionOwner owner) {
        boolean main = owner.sessionRef().tenantId().equals(TENANT);
        var principal = new PrincipalRef.ServicePrincipal(owner.sessionRef().tenantId(), main ? "workflow-policy-principal" : "workflow-policy-other",
                main ? "workflow-policy" : "workflow-policy-other", 1);
        var auth = identities.registerTrusted(principal, owner, "worker", "queue", Duration.ofMinutes(5));
        var verified = assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class, identities.verify(new IdentityPolicyPort.IdentityRequest(
                auth, owner, "worker", "queue", new RequestId("verify:" + UUID.randomUUID()), Optional.empty(), "trace", "span"))).identity();
        return new Current(auth, verified);
    }
    private static SessionOwner owner(TenantId tenant, String session, String agent, long endUser) {
        return SessionOwner.user(new SessionRef(tenant, session), new AgentId(agent), new EndUserId(endUser));
    }
    private static Material material(String id) { return new Material(new ResultRef(id), new TypedPayload("application/json", "{}")); }
    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private record Current(AuthenticationRef authentication, VerifiedIdentity identity) { }
    private record Fixture(Current current, DispatchResult.Registered registered, Prepared prepared, CapabilitySnapshotRef snapshot,
                           FrozenWorkflowTarget target, ExecutionRef execution) { }
}
