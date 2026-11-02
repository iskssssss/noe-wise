package com.skillforge.core.foundation.integration.runtime.session;


import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 新typed Run与原真实认证因果的生产授权闭环；ExecutionPort返回显式可控领域快照。 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcCallbackPolicyTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final PrincipalRef.ServicePrincipal SERVICE = new PrincipalRef.ServicePrincipal(TENANT, "service", "integration", 7);
    private static final PrincipalRef.ServicePrincipal OTHER = new PrincipalRef.ServicePrincipal(TENANT, "other", "integration", 7);
    private static final PrincipalRef.SystemPrincipal SYSTEM = new PrincipalRef.SystemPrincipal(TENANT, "callback-system");
    private static final Instant TIME = Instant.parse("2020-01-01T00:00:00Z");
    private static final String ROLE = "callback_policy_test";
    private static final String PASSWORD = "callback_policy_test_password";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("admin").withPassword("admin_password");

    @BeforeAll
    static void initializeDatabase() throws Exception {
        com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase.migrate(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.servicePrincipal(connection,1,1,"service","integration",7,
                    "[\"sessions.write\"]","fixture-unused-signing-key");
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.servicePrincipal(connection,2,1,"other","integration",7,
                    "[\"sessions.write\"]","fixture-unused-signing-key");
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.agent(connection,1,10);
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.endUser(connection,1,20);
        }
        execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        execute("GRANT DELETE ON public.agent_runtime_dag_permits TO " + ROLE);
        execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + ROLE);
        execute("GRANT EXECUTE ON FUNCTION resolve_runtime_authentication_tenant(text) TO " + ROLE);
    }

    @Test
    void systemCanUseOnlyRealRunsOriginalServiceCredential() {
        var fixture = fixture("system");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var query = connection.prepareStatement("SELECT (SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?),(SELECT count(*) FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?)")) {
            query.setString(1,TENANT.value()); query.setString(2,fixture.run().runRef().runId()); query.setString(3,TENANT.value()); query.setString(4,fixture.run().runRef().runId());
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(1,rows.getLong(1)); assertEquals(0,rows.getLong(2),"typed callback Run must not create an old Run shadow"); }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        var execution = new ExecutionRef(TENANT,"system-execution");
        var target = new CallbackTarget.ExecutionTarget(execution,fixture.run().runRef(),"confirm",4);
        var command = registration(fixture, target, SERVICE, fixture.run());
        var policy = policy(fixture.identities(), new AtomicReference<>(waiting(fixture.run(),execution,4,"confirm")));
        assertTrue(policy.authorizeRegister(command));
        assertFalse(policy.authorizeRegister(registration(fixture, target, OTHER, fixture.run())));
        var invented = new RunIdentity(fixture.run().owner(), fixture.run().owner().target().requireAgent(), fixture.run().causationInputId(), new RunRef(TENANT, "not-persisted"), fixture.run().trace(), fixture.run().causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(fixture.run().owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(fixture.run().owner().sessionRef().tenantId(), fixture.run().causationInputId().value()));
        assertFalse(policy.authorizeRegister(registration(fixture, new CallbackTarget.ExecutionTarget(execution,invented.runRef(),"confirm",4), SERVICE, invented)));
        var drift = new RunIdentity(fixture.run().owner(), fixture.run().owner().target().requireAgent(), new InputId("different-input"), fixture.run().runRef(), fixture.run().trace(), fixture.run().causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(fixture.run().owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(fixture.run().owner().sessionRef().tenantId(), new InputId("different-input").value()));
        assertFalse(policy.authorizeRegister(registration(fixture, target, SERVICE, drift)));
        // 原外部认证失效不抹去因果证明；当前 System 授权和原凭据状态仍分别核验。
        fixture.identities().revoke(fixture.serviceAuthentication());
        assertTrue(policy.authorizeRegister(command));
    }

    @Test
    void executionTargetMustMatchExactWaitingRevisionActionAndOwningRun() {
        var fixture = fixture("execution");
        var execution = new ExecutionRef(TENANT, "execution");
        var current = new AtomicReference<>(waiting(fixture.run(), execution, 4, "confirm"));
        var policy = policy(fixture.identities(), current);
        var target = new CallbackTarget.ExecutionTarget(execution, fixture.run().runRef(), "confirm", 4);
        var command = registration(fixture, target, SERVICE, fixture.run());
        assertTrue(policy.authorizeRegister(command));
        current.set(waiting(fixture.run(), execution, 5, "confirm"));
        assertFalse(policy.authorizeRegister(command));
        current.set(waiting(fixture.run(), execution, 4, "other-action"));
        assertFalse(policy.authorizeRegister(command));
        current.set(new ExecutionLedgerPort.ExecutionRecord(execution, fixture.run(), "workflow", "1", ExecutionMode.BACKGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.COMPLETED, 4,
                new TypedPayload("checkpoint.v1", "{}"), Optional.empty(), Optional.of(new ResultRef("result")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), TIME, TIME));
        assertFalse(policy.authorizeRegister(command));
        current.set(null);
        assertFalse(policy.authorizeRegister(command));
        var alien = new RunIdentity(fixture.run().owner(), fixture.run().owner().target().requireAgent(), fixture.run().causationInputId(), new RunRef(TENANT, "another-run"), fixture.run().trace(), fixture.run().causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(fixture.run().owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(fixture.run().owner().sessionRef().tenantId(), fixture.run().causationInputId().value()));
        current.set(waiting(alien, execution, 4, "confirm"));
        assertFalse(policy.authorizeRegister(command));
        current.set(waiting(fixture.run(), execution, 4, "confirm"));
        fixture.identities().revoke(fixture.systemAuthentication());
        assertFalse(policy.authorizeRegister(command));
    }

    private static ExecutionLedgerPort.ExecutionRecord waiting(RunIdentity owner, ExecutionRef execution, long revision, String action) {
        return new ExecutionLedgerPort.ExecutionRecord(execution, owner, "workflow", "1", ExecutionMode.BACKGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.WAITING_HITL, revision,
                new TypedPayload("checkpoint.v1", "{}"), Optional.of(new ExecutionLedgerPort.ExecutionWait("suspend", action)),
                Optional.of(new ResultRef("result")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), TIME, TIME);
    }

    private static JdbcCallbackPolicy policy(JdbcIdentityPolicyPort identities, AtomicReference<ExecutionLedgerPort.ExecutionRecord> current) {
        ExecutionLedgerPort executions = (ExecutionLedgerPort) Proxy.newProxyInstance(ExecutionLedgerPort.class.getClassLoader(),
                new Class<?>[]{ExecutionLedgerPort.class}, (proxy, method, arguments) -> {
                    assertEquals("find", method.getName());
                    var query = (ExecutionLedgerPort.ExecutionLookup) arguments[0];
                    assertEquals(SYSTEM, query.identity().principal());
                    return Optional.ofNullable(current.get());
                });
        return new JdbcCallbackPolicy(dataSource(), identities, executions);
    }
    private static RegisterCallback registration(Fixture fixture, CallbackTarget target, PrincipalRef.ServicePrincipal key, RunIdentity run) {
        var draft = new CallbackRegistrationDraft(new CallbackRef(TENANT, "callback"), run.owner(), target, "callback-source",
                CallbackPayloads.EXECUTION_SCHEMA,
                CallbackPayloads.SIGNATURE_POLICY, JdbcCallbackSigningKeys.reference(key));
        return new RegisterCallback(draft, fixture.system(), Optional.of(run), run.causationInputId(), run.trace(),
                run.causationIdentityRef(), new IdempotencyKey("key"), "fingerprint");
    }
    private static Fixture fixture(String label) {
        var owner = SessionOwner.user(new SessionRef(TENANT, label), new AgentId("10"), new EndUserId(20));
        var identities = new JdbcIdentityPolicyPort(dataSource(), Map.of(SYSTEM, Set.of("sessions.write")));
        var serviceAuth = identities.registerTrusted(SERVICE, owner, "channel", "source", Duration.ofMinutes(20));
        var service = verified(identities, serviceAuth, owner);
        var input = new InputId("input:"+label); var reference = new RunRef(TENANT,"callback-policy:"+label);
        var trace = new TraceEnvelope("trace:"+label,"span:"+label,input,Optional.empty());
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, reference, trace, service.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var content = new ExecutionStore.Material(new ResultRef("callback-input:"+label),new TypedPayload("fixture.callback-input","{}"));
        var context = new ExecutionStore.Material(new ResultRef("callback-context:"+label),new TypedPayload("fixture.callback-context","{}"));
        var capacity = new ExecutionBudgetPort.Resources(1,1,1,1,1_000,100,1_100);
        var budget = new ExecutionBudgetPort.Registration(reference,reference,ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,60,60,"callback-policy:v1")));
        assertTrue(new JdbcExecutionStore(dataSource(),identities,(session,state,actor,prepared) -> true).admit(new ExecutionStore.Admission(service,run,"callback-policy:"+label,
                new ResultRef("callback-profile:"+label),content,context,budget,List.of(),List.of())).created());
        var systemAuth = identities.registerTrusted(SYSTEM, owner, "channel", "source", Duration.ofMinutes(20));
        return new Fixture(identities, verified(identities, systemAuth, owner), run, serviceAuth, systemAuth);
    }
    private static VerifiedIdentity verified(JdbcIdentityPolicyPort identities, AuthenticationRef auth, SessionOwner owner) {
        return assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class, identities.verify(new IdentityPolicyPort.IdentityRequest(
                auth, owner, "channel", "source", new RequestId("request"), Optional.empty(), "trace", "span"))).identity();
    }
    private static DataSource dataSource() {
        var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl()); source.setUser(ROLE); source.setPassword(PASSWORD); return source;
    }
    private static void execute(String sql) throws java.sql.SQLException {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private record Fixture(JdbcIdentityPolicyPort identities, VerifiedIdentity system, RunIdentity run,
            AuthenticationRef serviceAuthentication, AuthenticationRef systemAuthentication) { }
}
