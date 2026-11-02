package example.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.schema.RuntimeJdbcSchema;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.SessionStorePort.ChangeSessionStatus;
import com.skillforge.runtime.api.port.SessionStorePort.CreateSession;
import com.skillforge.runtime.api.port.SessionStorePort.SessionLookup;
import com.skillforge.runtime.api.port.SessionStorePort.SessionStatus;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import com.skillforge.runtime.session.spi.ExecutionStore;

/** 独立宿主在空库安装 SDK schema，并消费发布构造入口、Session 与宿主身份事务。 */
final class PublishedDatabaseTest {

    /** SDK 独立安装与受限角色证明消费路径不需要任何平台生产模块或测试表。 */
    @Test
    void migratesAndConsumesProductionSessionAcrossHostRestart() throws Exception {
        var admin = PublishedHost.dataSource(PublishedHost.environment("SDK_CONSUMER_JDBC_USER"),
                PublishedHost.environment("SDK_CONSUMER_JDBC_PASSWORD"));
        String role = "sdk_pc_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"),
                    "Consumer migration requires a new empty database; never migrate an existing environment");
            statement.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD '" + password + "'");
        }
        RuntimeJdbcSchema.install(admin);
        RuntimeJdbcSchema.validate(admin);
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM agent_runtime_schema_history WHERE NOT success"));
            statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + role);
        }

        var consumer = PublishedHost.dataSource(role, password);
        try (var connection = consumer.getConnection(); var statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM pg_class WHERE oid='public.agent_runtime_sessions'::regclass AND relrowsecurity AND relforcerowsecurity"));
        }
        var owner = SessionOwner.user(new SessionRef(new TenantId("sdk-consumer-a"), "published-session"),
                new AgentId("published-agent"), new EndUserId(900001L));
        var input = new InputId("published-create");
        var trace = new TraceEnvelope("published-trace", "published-span", input, Optional.empty());
        var command = new CreateSession(owner, "sdk-consumer", "consumer-scope", "consumer-persona-v1",
                input, trace, Instant.parse("2026-10-13T00:00:00.123456789Z"));
        SessionStorePort.SessionRecord active;
        try (var context = PublishedHost.start(consumer)) {
            var store = context.getBean(SessionStorePort.class);
            var created=assertInstanceOf(SessionStorePort.SessionCreateResult.Created.class,store.create(command)).session();
            assertEquals(SessionStatus.CREATED,created.status());
            assertEquals(1L,created.sessionRevision());
            var activation=new ChangeSessionStatus(owner,SessionStatus.CREATED,SessionStatus.ACTIVE,created.sessionRevision(),
                    new InputId("published-activate"),trace,command.requestedAt());
            active=assertInstanceOf(SessionStorePort.SessionCasResult.Applied.class,store.compareAndSet(activation)).session();
            assertEquals(SessionStatus.ACTIVE, active.status());
            assertEquals(2L, active.sessionRevision());
            assertEquals(active,assertInstanceOf(SessionStorePort.SessionCasResult.Replayed.class,store.compareAndSet(activation)).session());
            assertEquals(active,assertInstanceOf(SessionStorePort.SessionCreateResult.Replayed.class,store.create(command)).session());
            assertEquals(active, store.find(new SessionLookup(owner)).orElseThrow());
            var changedEndUser = SessionOwner.user(owner.sessionRef(), owner.target(), new EndUserId(900002L));
            assertTrue(store.find(new SessionLookup(changedEndUser)).isEmpty());
            // 只读 tenant B 的连接上下文不能读取 tenant A 已提交的完整事实。
            try (var connection = consumer.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.tenant_id','sdk-consumer-b',true)");
                    assertEquals(0, scalar(statement, "SELECT count(*) FROM public.agent_runtime_sessions"));
                } finally { connection.rollback(); }
            }
        }
        // 全新容器上下文只读取已经持久化的状态；没有重放输入、模型或外部调用。
        try (var restarted = PublishedHost.start(consumer)) {
            var store = restarted.getBean(SessionStorePort.class);
            assertEquals(active, store.find(new SessionLookup(owner)).orElseThrow());
            var stale = new ChangeSessionStatus(owner, SessionStatus.ACTIVE, SessionStatus.CLOSED, 1L,
                    new InputId("published-stale-cas"), trace, command.requestedAt());
            assertInstanceOf(SessionStorePort.SessionCasResult.Conflict.class, store.compareAndSet(stale));
            assertEquals(active, store.find(new SessionLookup(owner)).orElseThrow());
            var close=new ChangeSessionStatus(owner,SessionStatus.ACTIVE,SessionStatus.CLOSED,active.sessionRevision(),
                    new InputId("published-close"),trace,command.requestedAt());
            var closed=assertInstanceOf(SessionStorePort.SessionCasResult.Applied.class,store.compareAndSet(close)).session();
            assertEquals(SessionStatus.CLOSED,closed.status());assertEquals(3L,closed.sessionRevision());
            assertEquals(closed,assertInstanceOf(SessionStorePort.SessionCasResult.Replayed.class,store.compareAndSet(close)).session());
            assertEquals(closed,store.find(new SessionLookup(owner)).orElseThrow());
        }
        consumesExecutionStoreWithHostOwnedIdentity(consumer);
        RuntimeJdbcSchema.validate(admin);
        System.out.println("SDK_PUBLISHED_DATABASE_CONSUMPTION_COMPLETE restarted=1 rls=1 stale_cas_rejected=1 host_identity=1");
    }

    /** 宿主拥有认证及当前权限；Runtime 仅借用正式 SqlSession 恢复和核对原身份。 */
    private static void consumesExecutionStoreWithHostOwnedIdentity(DataSource consumer) throws Exception {
        var tenant = new TenantId("published-execution-tenant");
        var owner = SessionOwner.user(new SessionRef(tenant, "published-execution-session"), new AgentId("published-agent"), new EndUserId(900003));
        var input = new InputId("published-execution-input");
        var trace = new TraceEnvelope("published-execution-trace", "published-execution-span", input, Optional.empty());
        var original = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant, "consumer-host"), owner,
                Set.of("sessions.write"), 1, new ResultRef("published-host-verification"));
        var identities = new ConsumerHostIdentity();
        identities.register(original);
        var run = new RunRef(tenant, "published-execution-run");
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, run, trace, original.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, input.value()));
        var capacity = new ExecutionBudgetPort.Resources(1, 0, 0, 0, 100, 100, 200);
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                        capacity, capacity, capacity, 60, 60, "published-host-v1")));
        var store = executionStore(consumer, identities);
        var admission = new ExecutionStore.Admission(original, identity, "published-admission", new ResultRef("published-configuration"),
                material("published-input"), material("published-context"), budget, List.of(), List.of());
        assertTrue(store.admit(admission, (session, command) -> assertTrue(identities.allowed(session, command.identity(), "sessions.write"))).created());
        assertFalse(store.admit(admission).created());
        var claimed = store.claim(new ExecutionStore.Claim(run, "published-worker", "published-claim", Duration.ofMinutes(1))).orElseThrow();
        var offer = new ExecutionStore.OfferInput(original, run, new InputId("published-supplement"), material("published-supplement"), "published-supplement");
        var saved = store.offerInput(offer);
        assertTrue(saved.created());
        var rebuilt = executionStore(consumer, identities);
        assertEquals(List.of(Optional.of(admission.context()), Optional.empty(), Optional.of(admission.input()), Optional.of(admission.context())),
                rebuilt.materials(tenant, List.of(admission.context().reference(), new ResultRef("missing"),
                        admission.input().reference(), admission.context().reference())));
        assertTrue(rebuilt.materials(new TenantId("other-tenant"), List.of(admission.input().reference())).getFirst().isEmpty());
        assertEquals(saved.input(), rebuilt.offerInput(offer).input());
        var context = material("published-supplement-context");
        var accepted = rebuilt.acceptInput(new ExecutionStore.AcceptInput(run, offer.input(), claimed.revision(), claimed.lease().orElseThrow(),
                new ExecutionStore.Progress(0, 0, 0, context.reference()), context, List.of()));
        assertEquals(original, accepted.actor());
        assertEquals(identity, rebuilt.load(run).orElseThrow().identity());
        verifiesTypedHostLifecycle(consumer, identities);
        PublishedAgentScenario.verify(consumer, identities, () -> executionStore(consumer, identities));
        identities.revoke(tenant, original.verificationRef());
        assertEquals("execution_supplement_unauthorized", assertThrows(ExecutionStore.Rejection.class,
                () -> rebuilt.offerInput(offer)).reason());
        assertEquals(1, rebuilt.inputs(run, 0, false, 10).size());
    }

    /** 与仅会话 Spring 图分开，实际注册 typed Host、执行并关闭后重建。 */
    private static void verifiesTypedHostLifecycle(DataSource consumer, ConsumerHostIdentity identities) {
        var tenant = new TenantId("published-host-tenant");
        var owner = SessionOwner.user(new SessionRef(tenant, "published-host-session"),
                new AgentId("published-host-agent"), new EndUserId(900004));
        var input = new InputId("published-host-input"); var run = new RunRef(tenant, "published-host-run");
        var actor = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant, "consumer-host"), owner,
                Set.of("sessions.write"), 1, new ResultRef("published-host-identity"));
        identities.register(actor);
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, run,
                new TraceEnvelope("published-host-trace", "published-host-span", input, Optional.empty()),
                actor.verificationRef(), new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, input.value()));
        var capacity = new ExecutionBudgetPort.Resources(1, 0, 0, 0, 100, 100, 200);
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                        capacity, capacity, capacity, 60, 60, "published-local-v1")));
        var store = executionStore(consumer, identities);
        var configuration = material("published-local-config");
        store.admit(new ExecutionStore.Admission(actor, identity, "published-local", configuration.reference(),
                material("published-local-input"), material("published-local-context"), budget, List.of(), List.of(configuration)));
        ExecutionStore.Run finished;
        try (var host = new PublishedExecutionHost(store, run)) {
            finished = host.execute();
            assertEquals(com.skillforge.runtime.api.RunStatus.COMPLETED, finished.status());
        } catch (Exception failure) { throw new IllegalStateException("published_execution_host_failed", failure); }
        try (var restarted = new PublishedExecutionHost(executionStore(consumer, identities), run)) {
            assertEquals(finished, restarted.persisted());
            assertEquals(identity, restarted.persisted().identity());
            assertEquals(configuration.reference(), restarted.persisted().configuration());
            assertEquals(configuration, restarted.store().material(tenant, configuration.reference()).orElseThrow());
        }
    }

    /** 所有宿主参与者均通过公开注入入口进入原业务事务，不使用平台装配或测试构造分支。 */
    private static JdbcExecutionStore executionStore(DataSource consumer, ConsumerHostIdentity identities) {
        return new JdbcExecutionStore(consumer, identities, new JdbcExecutionBudgets(consumer),
                (session, run, actor, prepared) -> identities.allowed(session, actor, "sessions.write"), configuration -> {},
                (session, run) -> identities.requireScope(session, run.tenantId()),
                (session, run, actor, operation, input) -> identities.allowed(session, actor, "sessions.write"), session -> {},
                (session, owner, events, readAfter) -> identities.requireScope(session, owner.tenantId()),
                (session, run, prepared) -> {
                    identities.requireScope(session, run.identity().runRef().tenantId());
                    return true;
                });
    }

    private static ExecutionStore.Material material(String reference) {
        return new ExecutionStore.Material(new ResultRef(reference), new TypedPayload("published-host.v1", "{}"));
    }

    /** 每个 SQL 断言读取唯一标量，异常和无返回行都使实际数据库验收失败。 */
    private static long scalar(Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
