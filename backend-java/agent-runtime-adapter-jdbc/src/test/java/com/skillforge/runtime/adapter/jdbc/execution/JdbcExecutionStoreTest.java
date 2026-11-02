package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.fixture.SessionSubjectJsonFixture;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 新目标 schema 与实际 PostgreSQL 事务、RLS、CAS、幂等和预算竞争验证。
 * 授权 hook 是存储边界夹具，完整 IAM/Provider/HTTP 链路由宿主验收独立证明。
 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcExecutionStoreTest {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();
    private static final TenantId TENANT = new TenantId("1");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final ExecutionBudgetPort.Resources CAPACITY = new ExecutionBudgetPort.Resources(8, 2, 8, 2, 1_000, 300, 1_300);
    private static HikariDataSource source;
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("typed_execution_store").withUsername("admin").withPassword("admin_password");

    /** 独立 SDK schema 保留 canonical owner 外键根；运行连接不能绕过 RLS。 */
    @BeforeAll static void schema() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE typed_runtime LOGIN PASSWORD 'typed_test_password' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO typed_runtime");
            statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO typed_runtime");
            statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO typed_runtime");
        }
        var config = new HikariConfig(); config.setJdbcUrl(POSTGRES.getJdbcUrl()); config.setUsername("typed_runtime");
        config.setPassword("typed_test_password"); config.setMaximumPoolSize(4); source = new HikariDataSource(config);
    }
    /** 释放测试连接池。 */
    @AfterAll static void close() { if (source != null) source.close(); }

    /** Session精确事件查询跨Run命中原事实；Run重载继续限制原Run，不读取全Session events页。 */
    @Test void exactSessionEventLookupKeepsRunSessionTenantAndUniqueIdentityBoundaries() {
        var store = store(); var session = new SessionRef(TENANT, "exact-event-session");
        var first = admission("exact-event-first", session.sessionId(), null, CAPACITY);
        var second = admission("exact-event-second", session.sessionId(), null, CAPACITY);
        store.admit(first); store.admit(second);
        var firstId = first.events().getFirst().id(); var secondId = second.events().getFirst().id();
        var firstSaved = store.event(first.run().runRef(), firstId).orElseThrow();
        var secondSaved = store.event(second.run().runRef(), secondId).orElseThrow();
        assertEquals(first.events().getFirst(), firstSaved.event()); assertEquals(first.run().runRef(), firstSaved.run());
        assertEquals(second.events().getFirst(), secondSaved.event()); assertEquals(second.run().runRef(), secondSaved.run());
        assertEquals(1, firstSaved.sequence()); assertEquals(2, secondSaved.sequence());
        // 事件窗口在 SQL 中同时限定上下界、顺序、数量及租户。
        assertEquals(List.of(firstSaved, secondSaved), store.eventsInRange(session, 0, 2, 100));
        assertEquals(List.of(firstSaved), store.eventsInRange(session, 0, 1, 100));
        assertEquals(List.of(secondSaved), store.eventsInRange(session, 1, 2, 100));
        assertEquals(List.of(firstSaved), store.eventsInRange(session, 0, 2, 1));
        assertTrue(store.eventsInRange(session, 2, 2, 100).isEmpty());
        assertTrue(store.eventsInRange(session, 2, 1, 100).isEmpty());
        assertTrue(store.eventsInRange(new SessionRef(new TenantId("2"), session.sessionId()), 0, 2, 100).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, -1, 2, 100));
        assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, 0, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> store.eventsInRange(session, 0, 2, 101));
        var rebuilt = store();
        assertEquals(Optional.of(firstSaved), rebuilt.event(session, firstId));
        assertEquals(Optional.of(secondSaved), rebuilt.event(session, secondId));
        assertTrue(rebuilt.event(first.run().runRef(), secondId).isEmpty());
        assertTrue(rebuilt.event(second.run().runRef(), firstId).isEmpty());
        assertTrue(rebuilt.event(session, new EventId("exact-event-missing")).isEmpty());
        assertTrue(rebuilt.event(first.run().runRef(), new EventId("exact-event-missing")).isEmpty());
        assertTrue(rebuilt.event(new SessionRef(new TenantId("2"), session.sessionId()), firstId).isEmpty());
        assertTrue(rebuilt.event(new RunRef(new TenantId("2"), first.run().runRef().runId()), firstId).isEmpty());
        var other = admission("exact-event-other", "exact-event-other-session", null, CAPACITY);
        store.admit(new Admission(other.identity(), other.run(), other.fingerprint(), other.configuration(), other.input(), other.context(),
                other.budget(), first.events(), other.configurationMaterials()));
        var otherSaved = rebuilt.event(other.run().owner().sessionRef(), firstId).orElseThrow();
        assertEquals(other.run().runRef(), otherSaved.run()); assertEquals(firstSaved.event(), otherSaved.event());
        assertEquals(List.of(otherSaved), rebuilt.eventsInRange(other.run().owner().sessionRef(), 0, 2, 100));
        assertEquals(List.of(firstSaved, secondSaved), rebuilt.eventsInRange(session, 0, 2, 100));
        assertEquals(Optional.of(firstSaved), rebuilt.event(session, firstId));
        assertTrue(rebuilt.event(other.run().owner().sessionRef(), secondId).isEmpty());
        // 两端schema均有(tenant_id,session_id,event_id)唯一约束；同Session另Run不能抢占既有事件身份。
        var duplicate = admission("exact-event-duplicate", session.sessionId(), null, CAPACITY);
        var conflict = new Admission(duplicate.identity(), duplicate.run(), duplicate.fingerprint(), duplicate.configuration(), duplicate.input(),
                duplicate.context(), duplicate.budget(), first.events(), duplicate.configurationMaterials());
        assertEquals("execution_event_conflict", assertThrows(Rejection.class, () -> store.admit(conflict)).reason());
        assertTrue(rebuilt.load(duplicate.run().runRef()).isEmpty());
        assertEquals(Optional.of(firstSaved), rebuilt.event(session, firstId));
        assertEquals(Optional.of(secondSaved), rebuilt.event(session, secondId));
    }

    @Test void requestRootsAndPartsCommitAtomicallyAndBatchReadAfterReconstruction() {
        var first = store(); var rebuilt = store();
        var a = claim(first, first.admit(admission("parts-first", "parts-first-session", null, CAPACITY)).run());
        var b = claim(first, first.admit(admission("parts-conflict", "parts-conflict-session", null, CAPACITY)).run());
        com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.requestPartsAreAtomicAndExact(first, rebuilt,
                dispatch(a, "parts-model", 1, 100), dispatch(b, "parts-conflict-model", 1, 100));
    }

    @Test void competingTerminalContextsCommitOnce() throws Exception {
        com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.competingTerminalContextsCommitOnce(store(), store(),
                admission("terminal-race", "terminal-race-session", null, CAPACITY));
    }

    @Test void terminalContextRollsBackWhenEventParticipantFails() {
        var fixture = store(); var admitted = admission("terminal-rollback", "terminal-rollback-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(admitted).run());
        var failing = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, state) -> {}, (session, state, actor, operation, input) -> true, session -> {},
                (session, owner, events, readAfter) -> { throw new IllegalStateException("participant-failure"); });
        var context = material("terminal-rollback-context"); var terminalEvent = event("terminal-rollback-event");
        var command = new Finish(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), RunStatus.TERMINATED, List.of(terminalEvent), Optional.of(context));
        assertThrows(IllegalStateException.class, () -> failing.finish(command));
        assertEquals(run, fixture.load(command.run()).orElseThrow());
        assertTrue(fixture.material(TENANT, context.reference()).isEmpty());
        assertTrue(fixture.event(command.run(), terminalEvent.id()).isEmpty());
        assertEquals(RunStatus.TERMINATED, fixture.finish(command).status());
    }

    @Test void terminalContextSharedContract() {
        com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.terminalContextIsAtomicAndReplayExact(store(), store(),
                admission("terminal-contract", "terminal-contract-session", null, CAPACITY));
    }

    @Test void currentExecutionStoreSharedContract() {
        var first = store(); var rebuilt = store();
        com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.admissionSurvivesReconstruction(first, rebuilt,
                admission("contract-rebuild", "contract-rebuild-session", null, CAPACITY));
        com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.materialsKeepRequestedOrder(first,
                admission("contract-materials", "contract-materials-session", null, CAPACITY));
        com.skillforge.runtime.adapter.testkit.ExecutionStoreContract.liveClaimIsExclusive(first, rebuilt,
                admission("contract-claim", "contract-claim-session", null, CAPACITY));
    }

    /** SDK 宿主身份在借用事务中保留精确原值，并独立核验当前权限与租户。 */
    @Test void hostIdentityPreservesOriginalFactsAndRejectsRevokedForgedOrForeignTransactions() throws Exception {
        var host = new TestJdbcIdentityTransactions();
        var identity = admission("host-identity", "host-identity-session", null, CAPACITY).identity();
        host.register(identity);
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "test-host-identity", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        host.configureTransaction(configuration);
        var factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        var request = new IdentityPolicyPort.AuthorizationRequest(identity, "sessions.write", payload("host-resource"), "trace", "span");
        try (var session = factory.openSession(false)) {
            try (var query = session.getConnection().prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                query.setString(1, TENANT.value()); query.execute();
            }
            assertEquals(identity, host.originalVerification(session, identity.owner(), identity.verificationRef()).orElseThrow());
            assertInstanceOf(IdentityPolicyPort.AuthorizationResult.Allowed.class, host.authorize(session, request));
            var forged = new VerifiedIdentity(identity.principal(), identity.owner(), Set.of("forged"),
                    identity.policyRevision(), identity.verificationRef());
            assertFalse(host.matchesOriginalCaller(session, identity.owner(), identity.verificationRef(), forged));
            assertInstanceOf(IdentityPolicyPort.AuthorizationResult.Denied.class, host.authorize(session,
                    new IdentityPolicyPort.AuthorizationRequest(forged, "sessions.write", request.resource(), "trace", "span")));
            host.revoke(TENANT, identity.verificationRef());
            assertInstanceOf(IdentityPolicyPort.AuthorizationResult.Denied.class, host.authorize(session, request));
            assertEquals(identity, host.originalVerification(session, identity.owner(), identity.verificationRef()).orElseThrow());
            try (var query = session.getConnection().prepareStatement("SELECT set_config('app.tenant_id','foreign-tenant',true)")) { query.execute(); }
            assertEquals("test_identity_transaction_scope_mismatch", assertThrows(IllegalStateException.class,
                    () -> host.originalVerification(session, identity.owner(), identity.verificationRef())).getMessage());
            session.rollback();
        }
        try (var session = factory.openSession(true)) {
            assertEquals("test_identity_transaction_scope_mismatch", assertThrows(IllegalStateException.class,
                    () -> host.authorize(session, request)).getMessage());
        }
    }

    /** 准入参与者与预算和群 Run 共用事务；重放只读取原身份，不重新运行领域变更。 */
    @Test void groupAdmissionFreezesActualRequesterAndRollsBackItsParticipant() {
        var store = store(); var base = admission("group-guard", "group-guard-session", null, CAPACITY);
        var binding = new GroupBindingRef(TENANT, "group-lifecycle");
        var owner = new SessionOwner(base.run().owner().sessionRef(), SessionSubject.group(binding), SessionTarget.group(binding));
        var requester = new RunRequester.EndUser(new EndUserId(17));
        var run = new RunIdentity(owner, new AgentId("actual-serving-agent"), base.run().causationInputId(), base.run().runRef(), base.run().trace(),
                base.run().causationIdentityRef(), requester, Optional.of(new SourceMessageRef(owner.sessionRef(), "committed-message")), new RunOriginRef(TENANT, "trigger-origin"));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, new EndUserId(17)), owner, Set.of("sessions.write"), 1, base.identity().verificationRef());
        var command = new Admission(actor, run, base.fingerprint(), base.configuration(), base.input(), base.context(), base.budget(), base.events(), base.configurationMaterials());
        var marker = new ExecutionMaterialRow(); marker.tenantId = TENANT.value(); marker.reference = "group-guard-marker"; marker.schemaId = "test.v1"; marker.canonicalJson = "{}";
        assertEquals("guard-denied", assertThrows(IllegalStateException.class, () -> store.admit(command, (session, proposed) -> {
            assertEquals(run, proposed.run()); assertEquals(1, session.getMapper(ExecutionStoreMapper.class).insertMaterial(marker));
            throw new IllegalStateException("guard-denied");
        })).getMessage());
        assertTrue(store.load(run.runRef()).isEmpty()); assertTrue(store.material(TENANT, new ResultRef(marker.reference)).isEmpty());
        assertTrue(new JdbcExecutionBudgets(source).find(run.runRef()).isEmpty());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(store.admit(command, (session, proposed) -> { calls.incrementAndGet(); session.getMapper(ExecutionStoreMapper.class).insertMaterial(marker); }).created());
        assertFalse(store.admit(command, (session, proposed) -> fail("replay must not run admission participant")).created());
        assertEquals(1, calls.get()); assertEquals(run, store.load(run.runRef()).orElseThrow().identity());
        var claimed = claim(store, store.load(run.runRef()).orElseThrow());
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch(claimed, "group-guard-model", 1, 100)));
        assertEquals(requester, registered.run().identity().requester());
        var saved = store.complete(completion(registered.run(), registered.attempt().id(), ResultKind.SUCCEEDED, "group-result", "group-context", "group-done"));
        assertEquals(run, saved.run().identity());
    }

    /** 真实业务提交只有一次发送权，结果重复不覆写用量，Session游标跨Run连续。 */
    @Test void repeatedInputDispatchAndResultPreserveOneSendAndOriginalUsage() {
        var store = store(); var admission = admission("normal", "shared-session", null, CAPACITY);
        var admitted = store.admit(admission); assertTrue(admitted.created());
        assertFalse(store.admit(admission).created());
        var claimed = claim(store, admitted.run()); var dispatch = dispatch(claimed, "normal-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(dispatch));
        var changedDemand = new ExecutionBudgetPort.Demand(dispatch.run(), dispatch.attemptId(), dispatch.budget().fingerprint(),
                dispatch.budget().category(), ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL, 101, 20), false);
        assertEquals("execution_dispatch_budget_conflict", assertThrows(IllegalStateException.class, () -> store.dispatch(new Dispatch(
                dispatch.run(), dispatch.revision(), dispatch.lease(), dispatch.action(), dispatch.attemptId(), changedDemand, dispatch.actor(), Optional.empty(), List.of()))).getMessage());
        var completion = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED, "normal-result", "normal-context", "normal-done");
        var saved = store.complete(completion); assertTrue(saved.newlySaved()); assertTrue(saved.advanced());
        assertEquals(Phase.READY, saved.run().phase());
        var before = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        var repeated = store.complete(new Completion(completion.run(), completion.attemptId(), completion.kind(), completion.result(),
                completion.feedback(), completion.externalOperation(), ExecutionBudgetPort.Usage.unreported(), completion.revision(),
                completion.lease(), completion.next(), completion.context(), completion.events(), completion.waitDeclaration()));
        assertFalse(repeated.newlySaved()); assertFalse(repeated.advanced());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertEquals(2, store.events(admission.run().owner().sessionRef(), 0, 100).size());
        store.admit(admission("same-session-next-run", "shared-session", null, CAPACITY));
        assertEquals(List.of(1L, 2L, 3L), store.events(admission.run().owner().sessionRef(), 0, 100).stream().map(SavedEvent::sequence).toList());
        var conflict = new Completion(completion.run(), completion.attemptId(), ResultKind.FAILED, completion.result(),
                completion.feedback(), completion.externalOperation(), completion.usage(), completion.revision(), completion.lease(),
                completion.next(), completion.context(), completion.events(), completion.waitDeclaration());
        assertEquals("execution_result_conflict", assertThrows(IllegalStateException.class, () -> store.complete(conflict)).getMessage());
    }

    /** 准入目录与Run原子冻结；重放只核原不可变材料，不允许补入另一份当前目录。 */
    @Test void admissionConfigurationMaterialsAreFrozenAtomicallyAndNeverAppendedByReplay() throws Exception {
        var store = store(); var base = admission("frozen-catalog", "frozen-catalog-session", null, CAPACITY);
        var catalog = material("execution-capabilities:frozen-catalog");
        var command = new Admission(base.identity(), base.run(), base.fingerprint(), base.configuration(), base.input(), base.context(),
                base.budget(), base.events(), List.of(catalog));
        assertTrue(store.admit(command).created()); assertFalse(store.admit(command).created());
        assertEquals(catalog, store.material(TENANT, catalog.reference()).orElseThrow());
        var replaced = new Admission(base.identity(), base.run(), base.fingerprint(), base.configuration(), base.input(), base.context(),
                base.budget(), base.events(), List.of(new Material(catalog.reference(), payload("new-current-catalog"))));
        assertEquals("execution_material_conflict", assertThrows(Rejection.class, () -> store.admit(replaced)).getMessage());
        var extra = material("execution-capabilities:unaccepted-extra");
        var appended = new Admission(base.identity(), base.run(), base.fingerprint(), base.configuration(), base.input(), base.context(),
                base.budget(), base.events(), List.of(catalog, extra));
        assertEquals("execution_material_missing", assertThrows(Rejection.class, () -> store.admit(appended)).getMessage());
        assertTrue(store.material(TENANT, extra.reference()).isEmpty());
        var bad = admission("frozen-catalog-rollback", "frozen-catalog-rollback-session", null, CAPACITY);
        var transientCatalog = material("execution-capabilities:frozen-catalog-rollback");
        var conflict = new Admission(bad.identity(), bad.run(), bad.fingerprint(), bad.configuration(), bad.input(), bad.context(), bad.budget(), bad.events(),
                List.of(transientCatalog, new Material(bad.input().reference(), payload("conflicting-catalog"))));
        assertEquals("execution_material_conflict", assertThrows(Rejection.class, () -> store.admit(conflict)).getMessage());
        assertTrue(store.load(bad.run().runRef()).isEmpty()); assertTrue(store.material(TENANT, transientCatalog.reference()).isEmpty());
        assertTrue(new JdbcExecutionBudgets(source).find(bad.run().runRef()).isEmpty());
        assertEquals(0,sessionCount("agent_runtime_session_owners",bad.run().owner().sessionRef().sessionId(),TENANT));
        assertEquals(0,sessionCount("agent_runtime_execution_sessions",bad.run().owner().sessionRef().sessionId(),TENANT));
        assertEquals(0,sessionCount("agent_runtime_execution_events",bad.run().owner().sessionRef().sessionId(),TENANT));
    }

    /** 新输入一次提交canonical归属与typed事实；保留领域的外键可消费它，但不恢复旧Session图或日志。 */
    @Test void admissionEstablishesCanonicalOwnerUnderRlsAndRejectsAnExistingOtherEndUser() throws Exception {
        var store=store();var command=admission("canonical-owner","canonical-owner-session",null,CAPACITY);
        assertTrue(store.admit(command).created());assertFalse(store.admit(command).created());
        String session=command.run().owner().sessionRef().sessionId();
        assertEquals(1,sessionCount("agent_runtime_session_owners",session,TENANT));
        assertEquals(1,sessionCount("agent_runtime_execution_sessions",session,TENANT));
        assertEquals(0,sessionCount("agent_runtime_session_events",session,TENANT));
        assertEquals(0,sessionCount("agent_runtime_session_streams",session,TENANT));
        assertEquals(0,sessionCount("agent_runtime_sessions",session,TENANT));
        assertEquals(0,sessionCount("agent_runtime_session_owners",session,new TenantId("2")));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) { assertTrue(rows.next());assertTrue(rows.getBoolean(1)); }
            try(var rows=statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='public.agent_runtime_session_owners'::regclass")) { assertTrue(rows.next());assertTrue(rows.getBoolean(1)); }
            try(var rows=statement.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname='agent_runtime_executions_target_owner_fkey' AND confrelid='public.agent_runtime_session_owners'::regclass AND contype='f'")) { assertTrue(rows.next());assertEquals(1,rows.getLong(1)); }
        }

        var rejected=admission("canonical-owner-conflict","canonical-owner-conflict-session",null,CAPACITY);
        var claimed=rejected.run().owner();var established=SessionOwner.user(claimed.sessionRef(),claimed.target(),new EndUserId(2));
        var retained=new com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorePort(source);
        assertInstanceOf(com.skillforge.runtime.api.port.SessionStorePort.SessionCreateResult.Created.class,retained.create(
                new com.skillforge.runtime.api.port.SessionStorePort.CreateSession(established,"fixture","fixture","fixture",rejected.run().causationInputId(),rejected.run().trace(),Instant.now())));
        assertEquals("execution_session_owner_mismatch",assertThrows(Rejection.class,()->store.admit(rejected)).reason());
        assertTrue(retained.find(new com.skillforge.runtime.api.port.SessionStorePort.SessionLookup(established)).isPresent());
        assertTrue(retained.find(new com.skillforge.runtime.api.port.SessionStorePort.SessionLookup(claimed)).isEmpty());
        assertTrue(store.load(rejected.run().runRef()).isEmpty());assertTrue(store.material(TENANT,rejected.input().reference()).isEmpty());
        assertTrue(new JdbcExecutionBudgets(source).find(rejected.run().runRef()).isEmpty());
        assertEquals(0,sessionCount("agent_runtime_execution_sessions",claimed.sessionRef().sessionId(),TENANT));
        assertEquals(0,sessionCount("agent_runtime_execution_events",claimed.sessionRef().sessionId(),TENANT));
    }

    /** 子准入与领域来源守卫只用一个事务；同root MEMBER登记与配置/关系共同回滚。 */
    @Test void childAdmissionSharesParentTransactionAndRejectsUnprovenRootsOrCancelledParents() {
        var fixture = store(); var base = admission("child-parent", "child-parent-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(base).run()); var dispatch = domainDispatch(run, "child-parent-action", Kind.DELEGATION);
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(dispatch));
        var child = childAdmission(registered.run(), "child-member");
        var command = new ChildAdmission(registered, dispatch.action(), new DelegatedChild("child-entry"), child);
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var store = new JdbcExecutionStore(observed, identities, (session, state, actor, prepared) -> true);
        var guard = new JdbcExecutionStore.ChildAdmissionGuard() {
            @Override public void require(org.apache.ibatis.session.SqlSession session, Run parent, ChildOrigin origin, Admission candidate) {
                assertEquals(registered.run().identity(), parent.identity()); assertEquals(command.origin(), origin); assertEquals(child, candidate);
                try (var query = session.getConnection().prepareStatement("SELECT current_setting('app.tenant_id',true)")) {
                    try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(TENANT.value(), rows.getString(1)); }
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            }
        };
        var accepted = store.admitChild(command, guard); calls.assertSingleTransaction(1, 0); assertTrue(accepted.created());
        assertEquals(base.run().runRef(), new JdbcExecutionBudgets(source).find(child.run().runRef()).orElseThrow().root());
        assertEquals(ExecutionBudgetPort.Role.MEMBER, new JdbcExecutionBudgets(source).find(child.run().runRef()).orElseThrow().role());
        assertFalse(store.admitChild(command, guard).created());
        var unaccepted = childAdmission(registered.run(), "child-rollback");
        var rollback = new ChildAdmission(registered, dispatch.action(), new DelegatedChild("child-rollback-entry"), unaccepted);
        calls.reset();
        assertEquals("source-revoked", assertThrows(Rejection.class, () -> store.admitChild(rollback, (session, parent, origin, candidate) -> {
            try (var query = session.getConnection().prepareStatement("INSERT INTO agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES('1','child-source-rollback','fixture.source','{}')")) {
                query.executeUpdate();
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            throw new Rejection("source-revoked");
        })).reason());
        assertEquals(1, calls.connections); assertEquals(0, calls.commits);
        assertTrue(fixture.material(TENANT, new ResultRef("child-source-rollback")).isEmpty());
        assertTrue(fixture.load(unaccepted.run().runRef()).isEmpty()); assertTrue(new JdbcExecutionBudgets(source).find(unaccepted.run().runRef()).isEmpty());
        var wrongBudget = new Admission(unaccepted.identity(), unaccepted.run(), unaccepted.fingerprint(), unaccepted.configuration(), unaccepted.input(), unaccepted.context(),
                registration(unaccepted.run().runRef(), unaccepted.run().runRef(), CAPACITY), unaccepted.events(), unaccepted.configurationMaterials());
        assertEquals("execution_child_budget_root_mismatch", assertThrows(Rejection.class, () -> store.admitChild(
                new ChildAdmission(registered, dispatch.action(), new DelegatedChild("wrong-root"), wrongBudget), guard)).reason());
        fixture.cancel(new Cancel(base.identity(), base.run().runRef(), new InputId("child-parent-cancel"), List.of()));
        assertEquals("execution_child_parent_changed", assertThrows(Rejection.class, () -> store.admitChild(rollback, guard)).reason());
        assertTrue(fixture.load(unaccepted.run().runRef()).isEmpty());
    }

    /** 委派依赖不能由普通payload解除；真实子Run事实证明后仅本地消费，不新增派发。 */
    @Test void delegationDependencyRequiresAuthoritativeChildrenAndConsumesLocally() {
        var store = store(); var base = admission("dependency-parent", "dependency-parent-session", null, CAPACITY);
        var run = claim(store, store.admit(base).run()); var dispatch = domainDispatch(run, "dependency-action", Kind.DELEGATION);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var child = childAdmission(registered.run(), "dependency-child");
        store.admitChild(new ChildAdmission(registered, dispatch.action(), new DelegatedChild("dependency-entry"), child), (session, parent, origin, candidate) -> {});
        var reference = new WaitRef(dispatch.action().action(), 1); var context = material("dependency-context");
        var declaration = new WaitDeclaration(reference, WaitKind.DELEGATION, Map.of(),
                Optional.of(new DelegationWait("dependency-business-wait", List.of("dependency-entry"), List.of(child.run().runRef()))));
        var waiting = store.complete(new Completion(dispatch.run(), registered.attempt().id(), ResultKind.SUCCEEDED, material("dependency-accepted"),
                Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported(), registered.run().revision(), registered.run().lease().orElseThrow(),
                new Progress(0, 0, 0, context.reference()), context, List.of(event("dependency-waiting")), Optional.of(declaration))).run();
        var response = new Resume(base.identity(), dispatch.run(), reference, waiting.revision(), new InputId("dependency-result"),
                material("dependency-result"), "dependency-result", Map.of(), List.of(event("dependency-ready")));
        assertEquals("execution_dependency_proof_required", assertThrows(Rejection.class, () -> store.resume(response)).reason());
        JdbcExecutionStore.DependencyGuard proof = (session, parent, exact, children, result) -> {
            assertEquals(declaration.internal(), exact.internal()); assertEquals(1, children.size()); assertEquals(child.run().runRef(), children.getFirst().identity().runRef());
            if (children.getFirst().phase() != Phase.FINISHED) throw new Rejection("child-not-finished");
        };
        assertEquals("child-not-finished", assertThrows(Rejection.class, () -> store.acceptDependency(response, proof)).reason());
        var claimedChild = claim(store, store.load(child.run().runRef()).orElseThrow());
        store.finish(new Finish(child.run().runRef(), claimedChild.revision(), claimedChild.lease().orElseThrow(), RunStatus.COMPLETED, List.of()));
        var resumed = store.acceptDependency(response, proof); assertTrue(resumed.accepted()); assertFalse(store.acceptDependency(response, proof).accepted());
        var current = store.claim(new Claim(dispatch.run(), "dependency-worker", "dependency-current", LEASE)).orElseThrow();
        var next = material("dependency-next-context");
        var adopted = store.acceptLocalResponse(new AcceptLocalResponse(dispatch.run(), reference, response.input(), current.revision(), current.lease().orElseThrow(),
                new Progress(0, 0, 0, next.reference()), next, List.of(event("dependency-done"))));
        assertTrue(adopted.advanced()); assertTrue(adopted.waiting().consumedRevision().isPresent()); assertTrue(adopted.waiting().continuationAttempt().isEmpty());
        assertTrue(adopted.run().currentAction().isEmpty()); assertEquals(1, store.actions(dispatch.run(), 0, 100).size());
        assertEquals(ResultKind.SUCCEEDED, store.attempt(dispatch.run(), registered.attempt().id()).orElseThrow().resultKind().orElseThrow());
    }

    /** 真实已报告下界与完整性分离，缺报不把预留当用量，后续只补缺报维度。 */
    @Test void tokenUsageAggregatesReportedFactsAndKeepsMissingDimensionsIncomplete() {
        var store = store(); var base = admission("usage-facts", "usage-facts-session", null, CAPACITY);
        var admitted = store.admit(base).run(); assertEquals(new TokenUsage(0, true), store.usage(base.run().runRef()));
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch(claim(store, admitted), "usage-facts-model", 1, 100)));
        assertEquals(new TokenUsage(0, false), store.usage(base.run().runRef()));
        var result = completion(registered.run(), registered.attempt().id(), ResultKind.SUCCEEDED, "usage-facts-result", "usage-facts-context", "usage-facts-done");
        var partial = new Completion(result.run(), result.attemptId(), result.kind(), result.result(), result.feedback(), result.externalOperation(),
                new ExecutionBudgetPort.Usage(java.util.OptionalLong.of(80), java.util.OptionalLong.empty(), java.util.OptionalLong.empty()),
                result.revision(), result.lease(), result.next(), result.context(), result.events(), result.waitDeclaration());
        store.complete(partial); assertEquals(new TokenUsage(80, false), store.usage(base.run().runRef()));
        store.complete(result); assertEquals(new TokenUsage(90, true), store.usage(base.run().runRef()));
        var unknownBase = admission("usage-unknown", "usage-unknown-session", null, CAPACITY);
        var unknown = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch(claim(store, store.admit(unknownBase).run()), "usage-unknown-model", 1, 100)));
        store.complete(completion(unknown.run(), unknown.attempt().id(), ResultKind.UNKNOWN, "usage-unknown-result", "usage-unknown-context", "usage-unknown-state"));
        assertEquals(new TokenUsage(0, false), store.usage(unknownBase.run().runRef()));
    }

    /** Workflow实际挂起来源按精确engine版本保存，恢复仍是原Action的新物理Attempt。 */
    @Test void workflowWaitRetainsExactEngineSourceAndResumesOriginalActionWithNewAttempt() {
        var store = store(); var base = admission("workflow-wait", "workflow-wait-session", null, CAPACITY);
        var dispatch = domainDispatch(claim(store, store.admit(base).run()), "workflow-action", Kind.WORKFLOW);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var reference = new WaitRef(registered.action().reference(), 1); var context = material("workflow-wait-context");
        var internal = new WorkflowWait(new ExecutionRef(TENANT, "workflow-execution"), "workflow-suspend", "workflow-resume-action", 7);
        var completion = new Completion(dispatch.run(), registered.attempt().id(), ResultKind.SUCCEEDED, material("workflow-suspended-result"), Optional.empty(),
                Optional.empty(), ExecutionBudgetPort.Usage.unreported(), registered.run().revision(), registered.run().lease().orElseThrow(),
                new Progress(0, 0, 0, context.reference()), context, List.of(event("workflow-waiting")),
                Optional.of(new WaitDeclaration(reference, WaitKind.WORKFLOW, Map.of(), Optional.of(internal))));
        var waiting = store.complete(completion).run(); assertEquals(Optional.of(internal), store.waiting(dispatch.run(), reference).orElseThrow().internal());
        assertEquals(store.waiting(dispatch.run(), reference), store.waiting(dispatch.run(), registered.attempt().id()));
        var response = new Resume(base.identity(), dispatch.run(), reference, waiting.revision(), new InputId("workflow-response"),
                material("workflow-response"), "workflow-response", Map.of(), List.of(event("workflow-resumed")));
        assertEquals("execution_dependency_proof_required", assertThrows(Rejection.class, () -> store.resume(response)).reason());
        var resumed = store.acceptDependency(response, (session, parent, exact, children, result) -> {
            assertEquals(Optional.of(internal), exact.internal()); assertTrue(children.isEmpty());
            assertEquals(response.response(), result);
        });
        var run = store.claim(new Claim(dispatch.run(), "workflow-worker", "workflow-resume-claim", LEASE)).orElseThrow();
        var request = material("workflow-resume-request"); var fingerprint = "workflow-resume-fingerprint";
        var prepared = new Prepared(dispatch.action().action(), dispatch.action().ordinal(), Kind.WORKFLOW, dispatch.action().target(), dispatch.action().capability(), request, fingerprint);
        var next = new Dispatch(dispatch.run(), run.revision(), run.lease().orElseThrow(), prepared, "workflow-resume-attempt",
                new ExecutionBudgetPort.Demand(dispatch.run(), "workflow-resume-attempt", fingerprint, ExecutionBudgetPort.Category.TOOL,
                        ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false), actor(run), Optional.of(reference), List.of());
        var paused = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (sql, current, actor, action) -> true,
                configuration -> {}, (sql, current) -> {}, (sql, current, actor, operation, input) -> true, session -> {},
                (sql, owner, events, readAfter) -> {}, (sql, current, action) -> false);
        var beforePause = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        assertEquals(new DispatchResult.Rejected("execution_dispatch_paused"), paused.dispatch(next));
        assertEquals(run, store.load(dispatch.run()).orElseThrow());
        assertTrue(store.waiting(dispatch.run(), reference).orElseThrow().continuationAttempt().isEmpty());
        assertTrue(store.attempt(dispatch.run(), next.attemptId()).isEmpty());
        assertEquals(beforePause, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        var continued = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(next));
        assertEquals(registered.action().reference(), continued.action().reference()); assertEquals(1, continued.action().ordinal());
        assertTrue(continued.action().selectedAttempt().isEmpty()); assertEquals(Optional.of(reference), continued.attempt().continuation());
        assertEquals(Optional.of(continued.attempt().id()), store.waiting(dispatch.run(), reference).orElseThrow().continuationAttempt());
        assertEquals(Optional.of(internal), resumed.waiting().internal());
        assertEquals(completion.result().reference(), store.attempt(dispatch.run(), registered.attempt().id()).orElseThrow().result().orElseThrow());
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(next));
    }

    /** 取消仅结束Run；迟到结果用量保存，但反馈上下文和完成事件不能提交。 */
    @Test void cancellationAllowsLateResultWithoutAdoptionContextOrCompletionEvent() {
        var store = store(); var command = admission("cancel", "cancel-session", null, CAPACITY);
        var claimed = claim(store, store.admit(command).run()); var dispatch = dispatch(claimed, "cancel-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var cancelled = store.cancel(new Cancel(command.identity(), dispatch.run(), new InputId("cancel-input"), List.of(event("cancelled"))));
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        var completion = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED, "cancel-result", "cancel-context", "late-done");
        var result = store.complete(completion); assertTrue(result.newlySaved()); assertFalse(result.advanced());
        assertFalse(result.attempt().adopted()); assertEquals(cancelled, result.run());
        assertTrue(store.material(TENANT, completion.result().reference()).isPresent());
        assertTrue(store.material(TENANT, completion.context().reference()).isEmpty());
        assertEquals(List.of(command.events().getFirst().id(), new EventId("cancelled")),
                store.events(command.run().owner().sessionRef(), 0, 100).stream().map(value -> value.event().id()).toList());
        assertTrue(new JdbcExecutionBudgets(source).snapshot(dispatch.run()).consumed().inputTokens() > 0);
    }

    /** 旧 fence 只能补结果，新持有者可使用同一已保存结果恢复采纳，不能再次派发。 */
    @Test void takeoverRejectsOldDispatchAndAdoptsSavedResultWithoutReplaying() throws Exception {
        var store = store(); var command = admission("takeover", "takeover-session", null, CAPACITY);
        var claimed = claim(store, store.admit(command).run()); var dispatch = dispatch(claimed, "takeover-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        expire(dispatch.run());
        var current = store.claim(new Claim(dispatch.run(), "worker-new", "claim-new", LEASE)).orElseThrow();
        assertTrue(current.fence() > registered.run().fence()); assertEquals(Phase.AWAITING_RESULT, current.phase());
        var newAction = dispatch(registered.run(), "old-lease-next", 2, 50);
        assertInstanceOf(DispatchResult.Rejected.class, store.dispatch(newAction));
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(dispatch));
        var completion = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED, "takeover-result", "takeover-context", "takeover-done");
        var late = store.complete(completion); assertTrue(late.newlySaved()); assertFalse(late.advanced());
        assertEquals(1, store.events(command.run().owner().sessionRef(), 0, 100).size());
        var resumed = store.complete(new Completion(completion.run(), completion.attemptId(), completion.kind(), completion.result(),
                completion.feedback(), completion.externalOperation(), ExecutionBudgetPort.Usage.unreported(), current.revision(),
                current.lease().orElseThrow(), completion.next(), completion.context(), completion.events(), completion.waitDeclaration()));
        assertFalse(resumed.newlySaved()); assertTrue(resumed.advanced()); assertEquals(Phase.READY, resumed.run().phase());
        assertEquals(2, store.events(command.run().owner().sessionRef(), 0, 100).size());
    }

    /** 派发不确定和结果UNKNOWN均不会恢复到READY或授予第二次发送权。 */
    @Test void programToolAndWorkflowKnowledgeKeepActualUnknownAcrossCoordinatorAndStoreReconstruction() {
        for (boolean programTool : List.of(true, false)) {
            String id = programTool ? "program-tool-actual-unknown" : "workflow-knowledge-actual-unknown";
            var store = store(); var original = admission(id, id + "-session", null, CAPACITY);
            var limits = new com.skillforge.runtime.json.CodecLimits(262_144, 32_768, 64);
            var codec = new com.skillforge.runtime.session.AgentExecutionCodec(limits);
            var payloads = new com.skillforge.runtime.session.SessionRuntimePayloads();
            var core = new com.skillforge.runtime.core.CoreRuntime(limits, payloads);
            var failure = new PortFailure("provider_result_unconfirmed", RetryMode.NONE, new ResultRef(id + "-provider-receipt"));
            var portals = core.portalResults(payloads, payloads, payloads);
            var outcome = programTool
                    ? portals.encodeTool(PortalCallKind.TOOL_INVOKE, new ToolPortal.ToolOutcome.OutcomeUnknown(failure))
                    : portals.encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, new KnowledgePortal.KnowledgeOutcome.OutcomeUnknown(failure));
            var actual = new Material(new ResultRef(id + "-actual-result"), codec.encodeOutcome(outcome));
            Material initial;
            com.skillforge.runtime.session.ExecutionCoordinator.Program<?> program;
            if (programTool) {
                var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:unknown"),
                        new CapabilityRef(CapabilityType.TOOL, "unknown", "1"), "unknown-deployment", 1,
                        new TypedPayload("json-schema", "{\"type\":\"object\"}"), new TypedPayload("json-schema", "{}"),
                        ToolEffect.READ_ONLY, true, Duration.ofSeconds(2), true, ToolPortal.ToolConfirmationPolicy.NEVER,
                        Set.of(), Set.of(), false);
                var request = new Material(new ResultRef(id + "-request"), codec.encodeTool(new com.skillforge.runtime.session.AgentExecutionCodec.ToolRequest(
                        target, new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM, "unknown", "unknown"),
                        new TypedPayload("application/json", "{}"), "actual-call", "unknown")));
                initial = com.skillforge.runtime.session.ProgramToolProgram.initial(original.run().runRef());
                program = new com.skillforge.runtime.session.ProgramToolProgram(
                        run -> new com.skillforge.runtime.session.ProgramToolProgram.Configuration(original.identity(), new ResultRef(id + "-capability"), request), limits);
            } else {
                initial = com.skillforge.runtime.session.WorkflowNodeProgram.initial(original.run().runRef());
                var configuration = new com.skillforge.runtime.session.WorkflowNodeProgram.Configuration(original.identity(), Kind.KNOWLEDGE,
                        "knowledge:unknown", new ResultRef(id + "-capability"), material(id + "-frozen-request"),
                        ExecutionBudgetPort.Category.KNOWLEDGE, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.KNOWLEDGE, 0, 0));
                program = new com.skillforge.runtime.session.WorkflowNodeProgram(new com.skillforge.runtime.session.WorkflowNodeProgram.Sources() {
                    @Override public com.skillforge.runtime.session.WorkflowNodeProgram.Configuration configuration(Run run) { return configuration; }
                    @Override public Material material(Run run, ResultRef reference) { return store.material(TENANT, reference).orElseThrow(); }
                    @Override public Optional<TypedPayload> scriptFeedback(TypedPayload request, ToolPortal.ToolOutcome result) { throw new AssertionError("no script invocation"); }
                }, core, limits);
            }
            store.admit(new Admission(original.identity(), original.run(), original.fingerprint(), original.configuration(), original.input(), initial,
                    original.budget(), original.events(), original.configurationMaterials()));
            var invocations = new java.util.concurrent.atomic.AtomicInteger();
            var reserved = new java.util.concurrent.atomic.AtomicReference<ExecutionBudgetPort.Snapshot>();
            var budgets = new JdbcExecutionBudgets(source);
            com.skillforge.runtime.session.ExecutionCoordinator.Capabilities capabilities = (registered, prepared) -> {
                invocations.incrementAndGet(); reserved.set(budgets.snapshot(original.run().runRef()));
                return new com.skillforge.runtime.session.ExecutionCoordinator.CapabilityResult(ResultKind.UNKNOWN, actual, Optional.empty(),
                        Optional.of(id + "-original-operation"), ExecutionBudgetPort.Usage.unreported());
            };
            com.skillforge.runtime.session.ExecutionCoordinator.Controls controls = run -> new com.skillforge.runtime.session.ExecutionCoordinator.Control() {
                @Override public boolean cancelled() { return false; }
                @Override public boolean ownershipLost() { return false; }
                @Override public void close() { }
            };
            var plans = new com.skillforge.runtime.session.ExecutionCoordinator.LocalPlans() {
                @Override public <T> com.skillforge.runtime.session.ExecutionCoordinator.LocalPlanResult<T> execute(Run run,
                        com.skillforge.runtime.session.ExecutionCoordinator.Step.PlanLocal command,
                        java.util.function.BiFunction<Run, Material, com.skillforge.runtime.session.ExecutionCoordinator.Adoption<T>> adopter) {
                    throw new AssertionError("no local plan invocation");
                }
            };
            var claim = new Claim(original.run().runRef(), "unknown-worker", id + "-claim", LEASE);
            var result = new com.skillforge.runtime.session.ExecutionCoordinator<>(store, program, capabilities, controls, plans).drive(claim);
            assertEquals(com.skillforge.runtime.session.ExecutionCoordinator.Stop.UNKNOWN, result.stop());
            assertEquals(Phase.UNCERTAIN, result.run().orElseThrow().phase());
            assertEquals(1, invocations.get());
            var rebuilt = store(); var savedRun = rebuilt.load(original.run().runRef()).orElseThrow();
            var action = rebuilt.action(savedRun.currentAction().orElseThrow()).orElseThrow();
            var attempt = rebuilt.attempt(original.run().runRef(), action.currentAttempt()).orElseThrow();
            assertEquals(Optional.of(ResultKind.UNKNOWN), attempt.resultKind());
            assertEquals(Optional.of(actual.reference()), attempt.result());
            assertEquals(Optional.of(actual.reference()), attempt.uncertainty());
            assertEquals(Optional.of(id + "-original-operation"), attempt.externalOperation());
            assertEquals(Optional.of(actual), rebuilt.material(TENANT, actual.reference()));
            assertEquals(outcome, codec.decodeOutcome(rebuilt.material(TENANT, actual.reference()).orElseThrow().content()));
            assertEquals(reserved.get(), new JdbcExecutionBudgets(source).snapshot(original.run().runRef()));
            var recovered = new com.skillforge.runtime.session.ExecutionCoordinator<>(rebuilt, program, capabilities, controls, plans).drive(claim);
            assertEquals(com.skillforge.runtime.session.ExecutionCoordinator.Stop.NOT_CLAIMED, recovered.stop());
            assertEquals(1, invocations.get());
        }
    }

    /** 派发不确定和结果UNKNOWN均不会恢复到READY或授予第二次发送权。 */
    @Test void unknownWaitsAndMissingResultCannotBeAutomaticallyReplayed() {
        var store = store(); var command = admission("unknown", "unknown-session", null, CAPACITY);
        var claimed = claim(store, store.admit(command).run()); var dispatch = dispatch(claimed, "unknown-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(dispatch));
        assertEquals(Optional.empty(), store.attempt(dispatch.run(), dispatch.attemptId()).orElseThrow().result());
        var unknown = completion(registered.run(), dispatch.attemptId(), ResultKind.UNKNOWN, "unknown-result", "unknown-context", "unknown-state");
        unknown = withUsage(unknown, 25, 5);
        var beforeUnknown = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        var completed = store.complete(unknown);
        assertEquals(beforeUnknown, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertFalse(store.complete(withUsage(unknown, 30, 6)).newlySaved());
        assertEquals(beforeUnknown, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertEquals(Phase.UNCERTAIN, completed.run().phase()); assertEquals(RunStatus.SUSPENDED, completed.run().status());
        assertTrue(completed.run().lease().isEmpty());
        assertTrue(store.claim(new Claim(dispatch.run(), "new-worker", "new-claim", LEASE)).isEmpty());
        assertInstanceOf(DispatchResult.Rejected.class, store.dispatch(dispatch(registered.run(), "unknown-next", 2, 50)));
        assertTrue(beforeUnknown.reserved().inputTokens() > 0);
        // 重建组件并重复恢复只处理持久事实，不得释放 UNKNOWN 的原预留。
        var rebuilt = store(); var recoveredBudgets = new JdbcExecutionBudgets(source);
        recoveredBudgets.recover(dispatch.run()); recoveredBudgets.recover(dispatch.run());
        assertEquals(beforeUnknown, recoveredBudgets.snapshot(dispatch.run()));
        assertEquals(Optional.of(ResultKind.UNKNOWN), rebuilt.attempt(dispatch.run(), dispatch.attemptId()).orElseThrow().resultKind());
        assertEquals(Optional.of(unknown.result().reference()), rebuilt.attempt(dispatch.run(), dispatch.attemptId()).orElseThrow().uncertainty());
        var known = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED, "unknown-known-result", "unknown-known-context", "unknown-known-done");
        var knownWithoutUsage = new Completion(known.run(), known.attemptId(), known.kind(), known.result(), known.feedback(),
                known.externalOperation(), ExecutionBudgetPort.Usage.unreported(), known.revision(), known.lease(), known.next(), known.context(), known.events(), known.waitDeclaration());
        var filled = rebuilt.complete(knownWithoutUsage);
        assertTrue(filled.newlySaved()); assertFalse(filled.advanced()); assertEquals(Phase.UNCERTAIN, filled.run().phase());
        assertEquals(Optional.of(completed.attempt().result().orElseThrow()), filled.attempt().uncertainty());
        assertTrue(rebuilt.material(TENANT, completed.attempt().result().orElseThrow()).isPresent());
        assertTrue(rebuilt.material(TENANT, known.context().reference()).isEmpty());
        assertEquals(ActionStatus.UNKNOWN, rebuilt.action(dispatch.action().action()).orElseThrow().status());
        var withUsage = rebuilt.complete(known); assertFalse(withUsage.newlySaved()); assertFalse(withUsage.advanced());
        var budget = recoveredBudgets.snapshot(dispatch.run());
        assertEquals(80, budget.consumed().inputTokens()); assertEquals(0, budget.reserved().inputTokens());
        assertFalse(rebuilt.complete(known).newlySaved());
        recoveredBudgets.recover(dispatch.run());
        assertEquals(budget, recoveredBudgets.snapshot(dispatch.run()));
        var conflict = new Completion(known.run(), known.attemptId(), known.kind(), known.result(), known.feedback(), known.externalOperation(),
                ExecutionBudgetPort.Usage.reported(java.util.OptionalLong.of(81), java.util.OptionalLong.of(10)), known.revision(), known.lease(),
                known.next(), known.context(), known.events(), known.waitDeclaration());
        assertEquals("budget_usage_conflict", assertThrows(IllegalStateException.class, () -> rebuilt.complete(conflict)).getMessage());
        assertEquals(budget, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertEquals(2, rebuilt.events(command.run().owner().sessionRef(), 0, 100).size());
    }

    /** 两轮输入等待消费各一次响应；始终只有原Action，最终结果才选定赢家。 */
    @Test void repeatedWaitResumeAndContinuationKeepOneLogicalActionAndExactReceipts() {
        var store = store(); var admission = admission("tool-wait", "tool-wait-session", null, CAPACITY);
        var claimed = claim(store, store.admit(admission).run());
        var initial = toolDispatch(claimed, new ActionRef(TENANT, "tool-wait-action"), "tool-wait-attempt-1", Optional.empty());
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(initial));
        var methods = Map.of("browser-connection", ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        var firstWait = new WaitRef(initial.action().action(), 1);
        var suspended = store.complete(inputRequired(registered.run(), registered.attempt().id(), firstWait, methods));
        assertTrue(suspended.advanced()); assertEquals(Phase.WAITING, suspended.run().phase());
        assertEquals(Optional.of(firstWait), suspended.run().waiting()); assertTrue(suspended.run().lease().isEmpty());
        assertTrue(store.action(initial.action().action()).orElseThrow().selectedAttempt().isEmpty());
        assertFalse(store.due(TENANT, 100).contains(initial.run()));
        assertTrue(store.claim(new Claim(initial.run(), "blocked", "blocked", LEASE)).isEmpty());
        var updatedActor = new VerifiedIdentity(admission.identity().principal(), admission.identity().owner(),
                admission.identity().scopes(), admission.identity().policyRevision(), new ResultRef("tool-wait-resume-actor"));
        var response = new Resume(updatedActor, initial.run(), firstWait, suspended.run().revision(), new InputId("browser-response-1"),
                material("browser-response-1"), "browser-response-1", methods, List.of(event("browser-response-accepted-1")));
        var wrongMethods = new Resume(response.actor(), response.run(), response.waiting(), response.revision(), response.input(),
                response.response(), response.fingerprint(), Map.of("browser-connection", ToolPortal.ToolInputMethod.ROOTS_LIST), response.events());
        assertEquals("execution_response_methods_mismatch", assertThrows(IllegalStateException.class, () -> store.resume(wrongMethods)).getMessage());
        assertTrue(store.material(TENANT, response.response().reference()).isEmpty());
        var beforeResume = new JdbcExecutionBudgets(source).snapshot(initial.run());
        var resumed = store.resume(response); assertTrue(resumed.accepted());
        assertEquals(Phase.READY, resumed.run().phase()); assertEquals(Optional.of(firstWait), resumed.run().waiting());
        assertEquals(Optional.of(firstWait.action()), resumed.run().currentAction());
        assertEquals(updatedActor.verificationRef(), resumed.run().actorVerificationRef());
        assertEquals(admission.run().causationIdentityRef(), resumed.run().identity().causationIdentityRef());
        assertEquals(new AcceptedResponse(response.input(), response.response().reference(), response.fingerprint(),
                updatedActor.verificationRef(), resumed.run().revision()), resumed.waiting().acceptedResponse().orElseThrow());
        assertEquals(beforeResume, new JdbcExecutionBudgets(source).snapshot(initial.run()));
        var repeated = store.resume(response); assertFalse(repeated.accepted()); assertEquals(resumed, new ResumeResult(repeated.run(), repeated.waiting(), true));
        var conflicting = new Resume(response.actor(), response.run(), response.waiting(), response.revision(), response.input(),
                new Material(response.response().reference(), payload("changed-response")), response.fingerprint(), methods, response.events());
        assertEquals("execution_response_conflict", assertThrows(IllegalStateException.class, () -> store.resume(conflicting)).getMessage());
        var continuationRun = store.claim(new Claim(initial.run(), "resume-worker", "resume-claim-1", LEASE)).orElseThrow();
        var continuation = toolDispatch(continuationRun, firstWait.action(), "tool-wait-attempt-2", Optional.of(firstWait));
        var continued = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(continuation));
        assertEquals(registered.action().reference(), continued.action().reference()); assertEquals(1, continued.action().ordinal());
        assertEquals(Optional.of(firstWait), continued.attempt().continuation());
        assertEquals(continuation.action().request().reference(), continued.attempt().request());
        assertNotEquals(registered.attempt().request(), continued.attempt().request());
        assertEquals(Optional.of(continued.attempt().id()), store.waiting(initial.run(), firstWait).orElseThrow().continuationAttempt());
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(continuation));
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(initial));
        assertTrue(store.authorizeSend(continued, continuation.action()));
        var secondWait = new WaitRef(firstWait.action(), 2);
        var again = store.complete(inputRequired(continued.run(), continued.attempt().id(), secondWait, methods));
        assertEquals(Optional.of(secondWait), again.run().waiting());
        assertTrue(store.action(firstWait.action()).orElseThrow().selectedAttempt().isEmpty());
        var response2 = new Resume(updatedActor, initial.run(), secondWait, again.run().revision(), new InputId("browser-response-2"),
                material("browser-response-2"), "browser-response-2", methods, List.of(event("browser-response-accepted-2")));
        store.resume(response2);
        var finalRun = store.claim(new Claim(initial.run(), "resume-worker", "resume-claim-2", LEASE)).orElseThrow();
        var finalDispatch = toolDispatch(finalRun, firstWait.action(), "tool-wait-attempt-3", Optional.of(secondWait));
        var finalRegistered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(finalDispatch));
        var context = material("tool-wait-final-context");
        var done = store.complete(new Completion(initial.run(), finalRegistered.attempt().id(), ResultKind.SUCCEEDED,
                material("tool-wait-final-result"), Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported(),
                finalRegistered.run().revision(), finalRegistered.run().lease().orElseThrow(), new Progress(1, 1, 0, context.reference()),
                context, List.of(event("tool-wait-final-done")), Optional.empty()));
        assertEquals(Phase.READY, done.run().phase()); assertTrue(done.run().waiting().isEmpty()); assertTrue(done.run().currentAction().isEmpty());
        assertEquals(Optional.of(finalRegistered.attempt().id()), store.action(firstWait.action()).orElseThrow().selectedAttempt());
        assertEquals(3, new JdbcExecutionBudgets(source).snapshot(initial.run()).consumed().tools());
        assertFalse(store.resume(response).accepted());
        assertEquals(store.waiting(initial.run(), firstWait), store.response(initial.run(), response.input()));
        assertTrue(store.waiting(new RunRef(new TenantId("2"), initial.run().runId()),
                new WaitRef(new ActionRef(new TenantId("2"), firstWait.action().actionId()), 1)).isEmpty());
    }

    /** 同一稳定输入并发接受一次；不同输入不能覆盖同一已接受等待。 */
    @Test void concurrentWaitResponsesAdvanceExactlyOnce() throws Exception {
        var store = store(); var admission = admission("wait-race", "wait-race-session", null, CAPACITY);
        var run = claim(store, store.admit(admission).run());
        var dispatched = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(toolDispatch(run,
                new ActionRef(TENANT, "wait-race-action"), "wait-race-attempt", Optional.empty())));
        var reference = new WaitRef(dispatched.action().reference(), 1);
        var methods = Map.of("request", ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        var waiting = store.complete(inputRequired(dispatched.run(), dispatched.attempt().id(), reference, methods)).run();
        var response = new Resume(admission.identity(), admission.run().runRef(), reference, waiting.revision(), new InputId("wait-race-response"),
                material("wait-race-response"), "wait-race-response", methods, List.of(event("wait-race-accepted")));
        var start = new CountDownLatch(1); List<ResumeResult> results;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return store.resume(response); });
            var b = workers.submit(() -> { start.await(); return store.resume(response); });
            start.countDown(); results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(ResumeResult::accepted).count());
        assertEquals(1, results.stream().filter(value -> !value.accepted()).count());
        assertEquals(waiting.revision() + 1, store.load(admission.run().runRef()).orElseThrow().revision());
        var other = new Resume(response.actor(), response.run(), reference, waiting.revision(), new InputId("wait-race-other"),
                material("wait-race-other"), "wait-race-other", methods, List.of());
        assertThrows(IllegalStateException.class, () -> store.resume(other));
        assertTrue(store.material(TENANT, other.response().reference()).isEmpty());
        assertEquals(3, store.events(admission.run().owner().sessionRef(), 0, 100).size());
    }

    /** 结果建等待和响应接受的最后事件冲突都回滚整个业务提交，不留下半个等待或回执。 */
    @Test void waitCreationAndResponseAcceptanceRollBackWithEventConflicts() {
        var fixture = store(); var admission = admission("wait-atomic", "wait-atomic-session", null, CAPACITY);
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(toolDispatch(
                claim(fixture, fixture.admit(admission).run()), new ActionRef(TENANT, "wait-atomic-action"), "wait-atomic-attempt", Optional.empty())));
        var counts = new TransactionCounts(); var observed = observedDataSource(counts);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {});
        var reference = new WaitRef(registered.action().reference(), 1);
        var methods = Map.of("request", ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        var completion = inputRequired(registered.run(), registered.attempt().id(), reference, methods);
        var conflictEvent = new Event(admission.events().getFirst().id(), "test", payload("conflicting-event"));
        var conflicting = new Completion(completion.run(), completion.attemptId(), completion.kind(), completion.result(), completion.feedback(),
                completion.externalOperation(), completion.usage(), completion.revision(), completion.lease(), completion.next(), completion.context(),
                List.of(conflictEvent), completion.waitDeclaration());
        var before = new JdbcExecutionBudgets(source).snapshot(admission.run().runRef());
        assertEquals("execution_event_conflict", assertThrows(IllegalStateException.class, () -> store.complete(conflicting)).getMessage());
        counts.assertSingleTransaction(0, 1);
        assertTrue(fixture.waiting(admission.run().runRef(), reference).isEmpty());
        assertTrue(fixture.attempt(admission.run().runRef(), registered.attempt().id()).orElseThrow().result().isEmpty());
        assertTrue(fixture.material(TENANT, completion.context().reference()).isEmpty());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(admission.run().runRef()));
        counts.reset(); var waiting = store.complete(completion).run(); counts.assertSingleTransaction(1, 0);
        var response = new Resume(admission.identity(), admission.run().runRef(), reference, waiting.revision(), new InputId("wait-atomic-response"),
                material("wait-atomic-response"), "wait-atomic-response", methods, List.of(conflictEvent));
        counts.reset();
        assertEquals("execution_event_conflict", assertThrows(IllegalStateException.class, () -> store.resume(response)).getMessage());
        counts.assertSingleTransaction(0, 1);
        assertTrue(fixture.waiting(admission.run().runRef(), reference).orElseThrow().acceptedResponse().isEmpty());
        assertTrue(fixture.response(admission.run().runRef(), response.input()).isEmpty());
        assertTrue(fixture.material(TENANT, response.response().reference()).isEmpty());
        assertEquals(waiting, fixture.load(admission.run().runRef()).orElseThrow());
    }

    /** AskUser保留真实模型SUCCEEDED结果；人类响应本地采纳一次，不新增Attempt或预算事务。 */
    @Test void humanWaitKeepsSucceededModelAndAcceptsResponseLocallyOnce() {
        var fixture = store(); var admission = admission("human-local", "human-local-session", null, CAPACITY);
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(dispatch(
                claim(fixture, fixture.admit(admission).run()), "human-local-model", 1, 100)));
        var waitRef = new WaitRef(registered.action().reference(), 1);
        var completion = humanRequired(registered.run(), registered.attempt().id(), waitRef);
        var waiting = fixture.complete(completion);
        assertEquals(ResultKind.SUCCEEDED, waiting.attempt().resultKind().orElseThrow()); assertTrue(waiting.attempt().adopted());
        assertEquals(Optional.of(registered.attempt().id()), fixture.action(waitRef.action()).orElseThrow().selectedAttempt());
        assertEquals(Phase.WAITING, waiting.run().phase()); assertTrue(waiting.run().lease().isEmpty()); assertTrue(waiting.run().nextDueAt().isEmpty());
        assertEquals(WaitKind.HUMAN, fixture.waiting(admission.run().runRef(), waitRef).orElseThrow().kind());
        assertTrue(fixture.waiting(admission.run().runRef(), waitRef).orElseThrow().requested().isEmpty());
        var actor = new VerifiedIdentity(admission.identity().principal(), admission.identity().owner(), admission.identity().scopes(),
                admission.identity().policyRevision(), new ResultRef("human-local-current-actor"));
        var response = new Resume(actor, admission.run().runRef(), waitRef, waiting.run().revision(), new InputId("human-local-response"),
                new Material(new ResultRef("human-local-response"), new TypedPayload("skillforge.runtime.execution.human-response.v1", "{\"response\":\"继续\"}")),
                "human-local-response", Map.of(), List.of(event("human-local-response-accepted")));
        var resumed = fixture.resume(response); assertTrue(resumed.accepted());
        var run = fixture.claim(new Claim(admission.run().runRef(), "human-worker", "human-claim", LEASE)).orElseThrow();
        var before = new JdbcExecutionBudgets(source).snapshot(admission.run().runRef());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, state, verified, prepared) -> true,
                configuration -> {}, (session, state) -> fail("local response must not confirm budget activity"),
                (session, state, verified, operation, input) -> true, session -> {});
        var context = material("human-local-next-context");
        var command = new AcceptLocalResponse(admission.run().runRef(), waitRef, response.input(), run.revision(), run.lease().orElseThrow(),
                new Progress(1, 0, 0, context.reference()), context, List.of(event("human-local-accepted")));
        var accepted = store.acceptLocalResponse(command); assertTrue(accepted.advanced()); calls.assertSingleTransaction(1, 0);
        assertTrue(calls.sql.stream().noneMatch(sql -> sql.contains("runtime_budget")));
        assertTrue(calls.sql.stream().noneMatch(sql -> sql.startsWith("INSERT INTO agent_runtime_execution_attempts")));
        assertEquals(run.revision() + 1, accepted.waiting().consumedRevision().orElseThrow());
        assertTrue(accepted.waiting().continuationAttempt().isEmpty());
        assertEquals(Phase.READY, accepted.run().phase()); assertTrue(accepted.run().waiting().isEmpty()); assertTrue(accepted.run().currentAction().isEmpty());
        assertEquals(context.reference(), accepted.run().progress().context()); assertEquals(actor.verificationRef(), accepted.run().actorVerificationRef());
        assertEquals(admission.run().causationIdentityRef(), accepted.run().identity().causationIdentityRef());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(admission.run().runRef()));
        assertEquals(waiting.attempt(), fixture.attempt(admission.run().runRef(), registered.attempt().id()).orElseThrow());
        assertEquals(Optional.of(registered.attempt().id()), fixture.action(waitRef.action()).orElseThrow().selectedAttempt());
        var eventCount = fixture.events(admission.run().owner().sessionRef(), 0, 100).size();
        var repeated = store.acceptLocalResponse(command); assertFalse(repeated.advanced());
        assertEquals(accepted.run(), repeated.run()); assertEquals(accepted.waiting(), repeated.waiting());
        assertEquals(eventCount, fixture.events(admission.run().owner().sessionRef(), 0, 100).size());
    }

    /** 人类响应的旧lease、事件冲突与取消均不能消费回执或留下新上下文。 */
    @Test void localHumanResponseRejectsStaleLeaseAndRollsBackBeforeCancellation() {
        var fixture = store(); var admission = admission("human-rollback", "human-rollback-session", null, CAPACITY);
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(dispatch(
                claim(fixture, fixture.admit(admission).run()), "human-rollback-model", 1, 100)));
        var waitRef = new WaitRef(registered.action().reference(), 1);
        var waiting = fixture.complete(humanRequired(registered.run(), registered.attempt().id(), waitRef)).run();
        var input = new InputId("human-rollback-response");
        fixture.resume(new Resume(admission.identity(), admission.run().runRef(), waitRef, waiting.revision(), input,
                material(input.value()), input.value(), Map.of(), List.of(event("human-rollback-resumed"))));
        var run = fixture.claim(new Claim(admission.run().runRef(), "human-worker", "human-rollback-claim", LEASE)).orElseThrow();
        var context = material("human-rollback-context"); var lease = run.lease().orElseThrow();
        var wrongLease = new LeaseFence(lease.tenantId(), lease.claimId(), lease.workerId(), lease.fencingToken() + 1, lease.leaseUntil());
        var stale = new AcceptLocalResponse(admission.run().runRef(), waitRef, input, run.revision(), wrongLease,
                new Progress(1, 0, 0, context.reference()), context, List.of());
        assertEquals("execution_lease_or_revision_changed", assertThrows(Rejection.class, () -> fixture.acceptLocalResponse(stale)).getMessage());
        assertTrue(fixture.material(TENANT, context.reference()).isEmpty());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, state) -> {}, (session, state, actor, operation, cause) -> true, session -> {});
        var badEvent = new Event(admission.events().getFirst().id(), "test", payload("conflicting-local-event"));
        var conflict = new AcceptLocalResponse(admission.run().runRef(), waitRef, input, run.revision(), lease,
                new Progress(1, 0, 0, context.reference()), context, List.of(badEvent));
        var before = new JdbcExecutionBudgets(source).snapshot(admission.run().runRef());
        assertEquals("execution_event_conflict", assertThrows(Rejection.class, () -> store.acceptLocalResponse(conflict)).getMessage());
        calls.assertSingleTransaction(0, 1); assertTrue(calls.sql.stream().noneMatch(sql -> sql.contains("runtime_budget")));
        assertTrue(fixture.waiting(admission.run().runRef(), waitRef).orElseThrow().consumedRevision().isEmpty());
        assertTrue(fixture.material(TENANT, context.reference()).isEmpty()); assertEquals(run, fixture.load(admission.run().runRef()).orElseThrow());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(admission.run().runRef()));
        fixture.cancel(new Cancel(admission.identity(), admission.run().runRef(), new InputId("human-rollback-cancel"), List.of(event("human-rollback-cancelled"))));
        var afterCancel = new AcceptLocalResponse(admission.run().runRef(), waitRef, input, run.revision(), lease,
                new Progress(1, 0, 0, context.reference()), context, List.of());
        assertEquals("execution_run_finished", assertThrows(Rejection.class, () -> fixture.acceptLocalResponse(afterCancel)).getMessage());
        assertTrue(fixture.waiting(admission.run().runRef(), waitRef).orElseThrow().consumedRevision().isEmpty());
    }

    /** 接管无结果派发只静止一次；迟到准确事实可结算但不能重开Run或自动再派发。 */
    @Test void missingResultQuiescesOnceWithoutInventedUsageAndLateFactCannotResume() throws Exception {
        var store = store(); var admission = admission("quiesce", "quiesce-session", null, CAPACITY);
        var run = claim(store, store.admit(admission).run()); var dispatch = dispatch(run, "quiesce-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        expire(dispatch.run());
        var takeover = store.claim(new Claim(dispatch.run(), "takeover", "takeover", LEASE)).orElseThrow();
        var before = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        var command = new QuiesceUnknown(dispatch.run(), dispatch.attemptId(), takeover.revision(), takeover.lease().orElseThrow(), List.of(event("quiescent")));
        var stopped = store.quiesceUnknown(command); assertTrue(stopped.changed());
        assertEquals(Phase.UNCERTAIN, stopped.run().phase()); assertEquals(RunStatus.SUSPENDED, stopped.run().status());
        assertTrue(stopped.attempt().result().isEmpty()); assertTrue(stopped.attempt().resultKind().isEmpty());
        assertTrue(stopped.run().lease().isEmpty()); assertTrue(stopped.run().nextDueAt().isEmpty());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertFalse(store.quiesceUnknown(command).changed());
        assertTrue(store.claim(new Claim(dispatch.run(), "repeat", "repeat", LEASE)).isEmpty());
        assertFalse(store.due(TENANT, 100).contains(dispatch.run()));
        var late = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED, "quiesce-late-result", "quiesce-late-context", "quiesce-late-event");
        var fact = store.complete(late); assertTrue(fact.newlySaved()); assertFalse(fact.advanced());
        assertEquals(stopped.run(), fact.run()); assertTrue(store.material(TENANT, late.context().reference()).isEmpty());
        assertEquals(80, new JdbcExecutionBudgets(source).snapshot(dispatch.run()).consumed().inputTokens());
        assertEquals(2, store.events(admission.run().owner().sessionRef(), 0, 100).size());
    }

    /** 准确结果先提交时静止操作返回事实，不把已保存结果改成UNKNOWN。 */
    @Test void savedAccurateResultWinsOverQuiescence() throws Exception {
        var store = store(); var admission = admission("known-before-quiesce", "known-before-quiesce-session", null, CAPACITY);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch(
                claim(store, store.admit(admission).run()), "known-before-quiesce-model", 1, 100)));
        expire(admission.run().runRef());
        var takeover = store.claim(new Claim(admission.run().runRef(), "new", "new", LEASE)).orElseThrow();
        var result = completion(registered.run(), registered.attempt().id(), ResultKind.SUCCEEDED,
                "known-before-quiesce-result", "known-before-quiesce-context", "known-before-quiesce-done");
        assertFalse(store.complete(result).advanced());
        var observed = store.quiesceUnknown(new QuiesceUnknown(admission.run().runRef(), registered.attempt().id(), takeover.revision(),
                takeover.lease().orElseThrow(), List.of(event("must-not-append"))));
        assertFalse(observed.changed()); assertEquals(Phase.AWAITING_RESULT, observed.run().phase());
        assertEquals(Optional.of(ResultKind.SUCCEEDED), observed.attempt().resultKind());
        assertEquals(1, store.events(admission.run().owner().sessionRef(), 0, 100).size());
    }

    /** 控制操作必须执行当前授权；拒绝恢复和取消不能仅凭owner相等而放行。 */
    @Test void resumeAndCancelRequireCurrentAuthorizationAndNeverConfirmActivity() {
        var fixture = store(); var admission = admission("control-authorization", "control-authorization-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(admission).run());
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(toolDispatch(run,
                new ActionRef(TENANT, "control-authorization-action"), "control-authorization-attempt", Optional.empty())));
        var reference = new WaitRef(registered.action().reference(), 1);
        var methods = Map.of("request", ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        var waiting = fixture.complete(inputRequired(registered.run(), registered.attempt().id(), reference, methods)).run();
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(); var activity = new java.util.concurrent.atomic.AtomicInteger();
        var controls = new java.util.ArrayList<JdbcExecutionStore.Control>();
        var store = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, state) -> activity.incrementAndGet(), (session, state, actor, operation, input) -> {
                    controls.add(operation);
                    assertEquals(operation == JdbcExecutionStore.Control.RESUME ? "control-response" : "control-cancel", input.value());
                    try (var query = session.getConnection().createStatement(); var rows = query.executeQuery("SELECT current_setting('app.tenant_id',true)")) {
                        assertTrue(rows.next()); assertEquals(TENANT.value(), rows.getString(1)); return allowed.get();
                    } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                }, session -> {});
        var response = new Resume(admission.identity(), admission.run().runRef(), reference, waiting.revision(), new InputId("control-response"),
                material("control-response"), "control-response", methods, List.of(event("control-resumed")));
        assertEquals("execution_resume_unauthorized", assertThrows(IllegalStateException.class, () -> store.resume(response)).getMessage());
        assertTrue(store.material(TENANT, response.response().reference()).isEmpty());
        var cancel = new Cancel(admission.identity(), admission.run().runRef(), new InputId("control-cancel"), List.of(event("control-cancelled")));
        assertEquals("execution_cancel_unauthorized", assertThrows(IllegalStateException.class, () -> store.cancel(cancel)).getMessage());
        assertEquals(waiting, fixture.load(admission.run().runRef()).orElseThrow());
        allowed.set(true); var cancelled = store.cancel(cancel); assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(0, activity.get());
        assertEquals(List.of(JdbcExecutionStore.Control.RESUME, JdbcExecutionStore.Control.CANCEL, JdbcExecutionStore.Control.CANCEL), controls);
        assertEquals("execution_run_finished", assertThrows(IllegalStateException.class, () -> store.resume(response)).getMessage());
        assertEquals(cancelled, store.renew(admission.run().runRef(), registered.run().lease().orElseThrow(), LEASE));
    }

    /** 已提交响应只读原回执；新输入仍须首次恢复授权，撤权或异参均不产生后继执行。 */
    @Test void acceptedResponseReplayUsesCurrentReadAuthorizationWithoutResumingFinishedRun() {
        var fixture=store();var admission=admission("response-replay-authority","response-replay-session",null,CAPACITY);
        var registered=assertInstanceOf(DispatchResult.Registered.class,fixture.dispatch(toolDispatch(
                claim(fixture,fixture.admit(admission).run()),new ActionRef(TENANT,"response-replay-action"),"response-replay-attempt",Optional.empty())));
        var reference=new WaitRef(registered.action().reference(),1);
        var methods=Map.of("request",ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        var waiting=fixture.complete(inputRequired(registered.run(),registered.attempt().id(),reference,methods)).run();
        var response=new Resume(admission.identity(),admission.run().runRef(),reference,waiting.revision(),new InputId("response-replay-input"),
                material("response-replay-input"),"response-replay-input",methods,List.of(event("response-replay-event")));
        var first=fixture.resume(response);assertTrue(first.accepted());
        var finished=fixture.cancel(new Cancel(admission.identity(),admission.run().runRef(),new InputId("response-replay-cancel"),List.of()));
        var actions=fixture.actions(admission.run().runRef(),0,100);
        var events=fixture.events(admission.run().owner().sessionRef(),0,100);
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        var operations=new java.util.ArrayList<JdbcExecutionStore.Control>();
        var replayStore=new JdbcExecutionStore(source, identities,new JdbcExecutionBudgets(source),(session,state,actor,prepared)->true,
                configuration->{},(session,state)->{},(session,state,actor,operation,input)->{
                    operations.add(operation);return operation==JdbcExecutionStore.Control.RESUME_REPLAY&&allowed.get();
                },session->{});
        var replay=replayStore.resume(response);
        assertFalse(replay.accepted());assertEquals(finished,replay.run());assertEquals(first.waiting(),replay.waiting());
        var different=new Resume(response.actor(),response.run(),reference,response.revision(),response.input(),
                material("response-replay-changed"),"response-replay-changed",methods,List.of());
        assertEquals("execution_response_conflict",assertThrows(IllegalStateException.class,()->replayStore.resume(different)).getMessage());
        var fresh=new Resume(response.actor(),response.run(),reference,response.revision(),new InputId("new-response-input"),
                material("new-response-input"),"new-response-input",methods,List.of());
        assertEquals("execution_resume_unauthorized",assertThrows(IllegalStateException.class,()->replayStore.resume(fresh)).getMessage());
        allowed.set(false);
        assertEquals("execution_resume_unauthorized",assertThrows(IllegalStateException.class,()->replayStore.resume(response)).getMessage());
        assertEquals(List.of(JdbcExecutionStore.Control.RESUME_REPLAY,JdbcExecutionStore.Control.RESUME_REPLAY,
                JdbcExecutionStore.Control.RESUME,JdbcExecutionStore.Control.RESUME_REPLAY),operations);
        assertEquals(finished,fixture.load(response.run()).orElseThrow());assertEquals(actions,fixture.actions(response.run(),0,100));
        assertEquals(events,fixture.events(admission.run().owner().sessionRef(),0,100));
        assertTrue(fixture.material(TENANT,fresh.response().reference()).isEmpty());
    }

    /** MCP握手后只复验原派发；当前撤权、正文替换、取消或过期均不授予发送。 */
    @Test void authorizeSendChecksOriginalAttemptAndCurrentAuthorityWithoutAnotherDispatch() throws Exception {
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, run, actor, prepared) -> allowed.get(),
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {});
        var admission = admission("send-authorization", "send-authorization-session", null, CAPACITY);
        var dispatch = toolDispatch(claim(store, store.admit(admission).run()), new ActionRef(TENANT, "send-authorization-action"),
                "send-authorization-attempt", Optional.empty());
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var before = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        assertTrue(store.authorizeSend(registered, dispatch.action()));
        var renewed = store.renew(dispatch.run(), registered.run().lease().orElseThrow(), LEASE.plusSeconds(10));
        assertTrue(store.authorizeSend(registered, dispatch.action())); assertEquals(registered.run().revision(), renewed.revision());
        var wrong = new Prepared(dispatch.action().action(), 1, Kind.TOOL, dispatch.action().target(), dispatch.action().capability(),
                new Material(dispatch.action().request().reference(), payload("wrong-request")), dispatch.action().fingerprint());
        assertFalse(store.authorizeSend(registered, wrong));
        allowed.set(false); assertFalse(store.authorizeSend(registered, dispatch.action()));
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        allowed.set(true); expire(dispatch.run()); assertFalse(store.authorizeSend(registered, dispatch.action()));
        store.cancel(new Cancel(admission.identity(), dispatch.run(), new InputId("send-authorization-cancel"), List.of()));
        assertFalse(store.authorizeSend(registered, dispatch.action()));
        assertEquals(registered.attempt(), store.attempt(dispatch.run(), dispatch.attemptId()).orElseThrow());
    }

    /** 暂停只阻止新资格；原Run/预算不变，恢复沿同一动作继续，已赢资格仍属在途。 */
    @Test void dispatchGatePausesNewAttemptsWithoutFinishingRunOrRecheckingRegisteredSend() {
        var open = new java.util.concurrent.atomic.AtomicBoolean(false);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var store = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {},
                (session, owner, events, readAfter) -> {}, (session, run, prepared) -> { checks.incrementAndGet(); return open.get(); });
        var admission = admission("dispatch-gate", "dispatch-gate-session", null, CAPACITY);
        var claimed = claim(store, store.admit(admission).run());
        var command = dispatch(claimed, "dispatch-gate-model", 1, 100);
        var before = new JdbcExecutionBudgets(source).snapshot(command.run());
        assertEquals(new DispatchResult.Rejected("execution_dispatch_paused"), store.dispatch(command));
        assertEquals(claimed, store.load(command.run()).orElseThrow());
        assertTrue(store.action(command.action().action()).isEmpty()); assertTrue(store.attempt(command.run(), command.attemptId()).isEmpty());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(command.run()));
        open.set(true);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(command));
        open.set(false);
        assertTrue(store.authorizeSend(registered, command.action()));
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(command));
        assertEquals(2, checks.get());
        var completed = store.complete(completion(registered.run(), command.attemptId(), ResultKind.SUCCEEDED,
                "dispatch-gate-result", "dispatch-gate-context", "dispatch-gate-done"));
        assertTrue(completed.advanced()); assertEquals(Phase.READY, completed.run().phase());
        var next = dispatch(completed.run(), "dispatch-gate-next", 2, 100);
        assertEquals(new DispatchResult.Rejected("execution_dispatch_paused"), store.dispatch(next));
        assertEquals(completed.run(), store.load(next.run()).orElseThrow());
    }

    /** 批次中后项被暂停时，先前项准备的预算、材料和Attempt全部回滚，恢复仍使用原批次。 */
    @Test void dispatchGateRollsBackAnEntireFreshBatchAndAllowsTheSameBatchAfterResume() {
        var fixture = store(); var ready = batchReady(fixture, "batch-dispatch-gate", CAPACITY);
        var command = batchDispatch(ready, "batch-dispatch-gate");
        var blocked = new java.util.concurrent.atomic.AtomicBoolean(true);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var store = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {},
                (session, owner, events, readAfter) -> {}, (session, run, prepared) -> {
                    checks.incrementAndGet(); return !blocked.get() || prepared.ordinal() == 2;
                });
        var before = new JdbcExecutionBudgets(source).snapshot(command.run());
        assertEquals(new BatchDispatchResult.Rejected("execution_dispatch_paused"), store.dispatchBatch(command));
        assertEquals(2, checks.get()); assertEquals(ready, store.load(command.run()).orElseThrow());
        for (var member : command.requests()) {
            var request = ((BatchMember.Fresh) member).request();
            assertTrue(store.action(request.action().action()).isEmpty());
            assertTrue(store.attempt(command.run(), request.attemptId()).isEmpty());
            assertTrue(store.material(TENANT, request.action().request().reference()).isEmpty());
        }
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(command.run()));
        blocked.set(false);
        assertEquals(2, assertInstanceOf(BatchDispatchResult.Registered.class, store.dispatchBatch(command)).permits().size());
        blocked.set(true); int acceptedChecks = checks.get();
        assertInstanceOf(BatchDispatchResult.Existing.class, store.dispatchBatch(command));
        assertEquals(acceptedChecks, checks.get());
    }

    /** 只有真实新增事件提交后通知；回滚与重复受理不通知，通知失败仍返回已提交事实。 */
    @Test void committedEventNotificationRunsAfterCommitAndNeverTurnsCommittedBusinessIntoFailure() {
        var fixture = store(); var notified = new java.util.ArrayList<SessionRef>();
        var store = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {
                    assertFalse(fixture.events(session, 0, 100).isEmpty()); notified.add(session);
                });
        var admission = admission("notify", "notify-session", null, CAPACITY);
        assertTrue(store.admit(admission).created()); assertEquals(List.of(admission.run().owner().sessionRef()), notified);
        assertFalse(store.admit(admission).created()); assertEquals(1, notified.size());
        var bad = admission("notify-rollback", "notify-rollback-session", null, CAPACITY);
        var event = event("notify-conflict");
        var failed = new Admission(bad.identity(), bad.run(), bad.fingerprint(), bad.configuration(), bad.input(), bad.context(), bad.budget(),
                List.of(event, new Event(event.id(), event.type(), payload("different-event"))), bad.configurationMaterials());
        assertEquals("execution_event_conflict", assertThrows(IllegalStateException.class, () -> store.admit(failed)).getMessage());
        assertTrue(fixture.load(bad.run().runRef()).isEmpty()); assertEquals(1, notified.size());
        var failingNotification = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true,
                session -> { throw new IllegalStateException("notification-test-failure"); });
        var committed = admission("notify-failure", "notify-failure-session", null, CAPACITY);
        assertTrue(failingNotification.admit(committed).created());
        assertEquals(1, fixture.events(committed.run().owner().sessionRef(), 0, 100).size());
    }

    /** 宿主取得的是本事务真实来源批次；来源和领域写入提交后才通知，重放不再次参与。 */
    @Test void eventParticipantReadsExactUncommittedSourcesAndNotifiesOnlyAfterItsWritesCommit() {
        var fixture = store(); var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var batches = new java.util.ArrayList<List<SavedEvent>>();
        var committed = new java.util.ArrayList<SavedEvent>();
        var notices = new java.util.ArrayList<SessionRef>();
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, owner -> {
                    var saved = fixture.events(owner, 0, 100);
                    assertEquals(batches.stream().flatMap(List::stream).toList(), saved);
                    for (var event : saved)
                        assertTrue(fixture.material(owner.tenantId(), participantEffect(event)).isPresent());
                    committed.clear(); committed.addAll(saved); notices.add(owner);
                }, (session, owner, events, readAfter) -> {
                    assertEquals(TENANT, owner.tenantId()); assertFalse(events.isEmpty());
                    // 独立连接仍只能看到上次提交；借用 reader 则已能读到本批未提交来源。
                    assertEquals(committed, fixture.events(owner, 0, 100));
                    assertEquals(events, readAfter.apply(events.getFirst().sequence() - 1));
                    assertThrows(IllegalArgumentException.class, () -> readAfter.apply(-1));
                    for (var event : events) {
                        assertEquals(owner.tenantId(), event.run().tenantId());
                        insertPlanBusinessEffect(session, participantEffect(event).value());
                        assertTrue(fixture.material(owner.tenantId(), participantEffect(event)).isEmpty());
                    }
                    assertEquals(batches.size(), notices.size()); batches.add(List.copyOf(events));
                });
        var base = admission("participant-sources", "participant-sources-session", null, CAPACITY);
        var admission = new Admission(base.identity(), base.run(), base.fingerprint(), base.configuration(), base.input(), base.context(),
                base.budget(), List.of(base.events().getFirst(), event("participant-second-source")), base.configurationMaterials());
        var admitted = store.admit(admission); calls.assertSingleTransaction(1, 0);
        assertEquals(List.of(1L, 2L), batches.getFirst().stream().map(SavedEvent::sequence).toList());
        for (var saved : batches.getFirst()) {
            assertEquals(admitted.run().identity().runRef(), saved.run());
            assertEquals(admitted.run().revision(), saved.revision()); assertEquals(admitted.run().updatedAt(), saved.occurredAt());
        }
        calls.reset(); assertFalse(store.admit(admission).created()); calls.assertSingleTransaction(1, 0);
        assertEquals(1, batches.size()); assertEquals(1, notices.size());
        var run = claim(store, admitted.run());
        var baseDispatch = dispatch(run, "participant-model", 1, 100);
        var dispatch = withParticipantEvent(baseDispatch, event("participant-model-started"));
        calls.reset(); var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        calls.assertSingleTransaction(1, 0);
        assertEquals(registered.run().revision(), batches.get(1).getFirst().revision());
        assertEquals(registered.run().updatedAt(), batches.get(1).getFirst().occurredAt());
        calls.reset(); assertInstanceOf(DispatchResult.Existing.class, store.dispatch(dispatch)); calls.assertSingleTransaction(1, 0);
        assertEquals(2, batches.size()); assertEquals(2, notices.size());
        var completion = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED,
                "participant-model-result", "participant-next-context", "participant-model-finished");
        calls.reset(); var completed = store.complete(completion); calls.assertSingleTransaction(1, 0);
        assertEquals(completed.run().revision(), batches.get(2).getFirst().revision());
        assertEquals(completed.run().updatedAt(), batches.get(2).getFirst().occurredAt());
        calls.reset(); assertFalse(store.complete(completion).newlySaved()); calls.assertSingleTransaction(1, 0);
        assertEquals(3, batches.size()); assertEquals(3, notices.size());
        calls.reset(); store.admit(admission("participant-next-run", "participant-sources-session", null, CAPACITY));
        calls.assertSingleTransaction(1, 0);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), committed.stream().map(SavedEvent::sequence).toList());
        assertEquals(new RunRef(TENANT, "participant-next-run"), committed.getLast().run());
        assertEquals(4, batches.size()); assertEquals(4, notices.size());
    }

    /** 参与者故障时，已预留/确认预算、Attempt、请求、来源及同连接领域写入一起回滚。 */
    @Test void eventParticipantFailureRollsBackDispatchBudgetAndDomainWritesWithoutNotification() {
        var fixture = store(); var admission = admission("participant-dispatch-rollback", "participant-dispatch-rollback-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(admission).run());
        var beforeEvents = fixture.events(admission.run().owner().sessionRef(), 0, 100);
        var beforeBudget = new JdbcExecutionBudgets(source).snapshot(run.identity().runRef());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var authorizedConnection = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
        var notices = new java.util.ArrayList<SessionRef>();
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, current, actor, prepared) -> {
            authorizedConnection.set(session.getConnection()); return true;
        }, configuration -> {}, (session, current) -> {}, (session, current, actor, operation, input) -> true, notices::add,
                (session, owner, events, readAfter) -> {
                    assertSame(authorizedConnection.get(), session.getConnection());
                    assertEquals(events, readAfter.apply(beforeEvents.getLast().sequence()));
                    insertPlanBusinessEffect(session, "participant-dispatch-rollback-effect");
                    throw new IllegalStateException("fixture_event_participant_failed");
                });
        var dispatch = withParticipantEvent(dispatch(run, "participant-dispatch-rollback-model", 1, 100), event("participant-dispatch-rollback-started"));
        assertEquals("fixture_event_participant_failed", assertThrows(IllegalStateException.class, () -> store.dispatch(dispatch)).getMessage());
        calls.assertSingleTransaction(0, 1); assertTrue(notices.isEmpty());
        assertEquals(run, fixture.load(dispatch.run()).orElseThrow());
        assertEquals(beforeBudget, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertTrue(fixture.attempt(dispatch.run(), dispatch.attemptId()).isEmpty());
        assertTrue(fixture.action(dispatch.action().action()).isEmpty());
        assertTrue(fixture.material(TENANT, dispatch.action().request().reference()).isEmpty());
        assertTrue(fixture.material(TENANT, new ResultRef("participant-dispatch-rollback-effect")).isEmpty());
        assertEquals(beforeEvents, fixture.events(admission.run().owner().sessionRef(), 0, 100));
    }

    /** 结果投影失败不留下半个结果或结算；同一准确完成命令之后可保存，且不重新取得发送权。 */
    @Test void eventParticipantFailureRollsBackResultAndCanSaveTheOriginalResultWithoutAnotherDispatch() {
        var fixture = store(); var admission = admission("participant-result-rollback", "participant-result-rollback-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(admission).run());
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(dispatch(run, "participant-result-model", 1, 100)));
        var beforeBudget = new JdbcExecutionBudgets(source).snapshot(run.identity().runRef());
        var beforeEvents = fixture.events(admission.run().owner().sessionRef(), 0, 100);
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var notices = new java.util.ArrayList<SessionRef>();
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed),
                (session, current, actor, prepared) -> fail("saving an original result must not authorize another dispatch"),
                configuration -> {}, (session, current) -> {}, (session, current, actor, operation, input) -> true, notices::add,
                (session, owner, events, readAfter) -> {
                    assertEquals(events, readAfter.apply(beforeEvents.getLast().sequence()));
                    insertPlanBusinessEffect(session, "participant-result-rollback-effect");
                    if (failing.get()) throw new IllegalStateException("fixture_result_participant_failed");
                });
        var completion = completion(registered.run(), registered.attempt().id(), ResultKind.SUCCEEDED,
                "participant-result", "participant-result-context", "participant-result-finished");
        assertEquals("fixture_result_participant_failed", assertThrows(IllegalStateException.class, () -> store.complete(completion)).getMessage());
        calls.assertSingleTransaction(0, 1); assertTrue(notices.isEmpty());
        assertEquals(registered.run(), fixture.load(completion.run()).orElseThrow());
        assertEquals(registered.attempt(), fixture.attempt(completion.run(), completion.attemptId()).orElseThrow());
        assertEquals(beforeBudget, new JdbcExecutionBudgets(source).snapshot(completion.run()));
        assertTrue(fixture.material(TENANT, completion.result().reference()).isEmpty());
        assertTrue(fixture.material(TENANT, completion.context().reference()).isEmpty());
        assertTrue(fixture.material(TENANT, new ResultRef("participant-result-rollback-effect")).isEmpty());
        assertEquals(beforeEvents, fixture.events(admission.run().owner().sessionRef(), 0, 100));
        failing.set(false); calls.reset(); var saved = store.complete(completion); calls.assertSingleTransaction(1, 0);
        assertTrue(saved.newlySaved()); assertTrue(saved.advanced()); assertEquals(1, notices.size());
        assertEquals(completion.result().reference(), saved.attempt().result().orElseThrow());
        assertTrue(fixture.material(TENANT, new ResultRef("participant-result-rollback-effect")).isPresent());
        assertEquals(List.of(1L, 2L), fixture.events(admission.run().owner().sessionRef(), 0, 100).stream().map(SavedEvent::sequence).toList());
    }

    /** 公开输出参与耗尽原lease时，最终DB时钟CAS连同参与者与预算写入一起拒绝。 */
    @Test void finalDispatchCasRejectsLeaseThatExpiresDuringEventParticipation() {
        var fixture = store(); var admission = admission("expires-in-event-participant", "expires-in-event-participant-session", null, CAPACITY);
        var admitted = fixture.admit(admission).run();
        var run = fixture.claim(new Claim(admitted.identity().runRef(), "participant-worker", "participant-claim", Duration.ofSeconds(2))).orElseThrow();
        var beforeEvents = fixture.events(admission.run().owner().sessionRef(), 0, 100);
        var beforeBudget = new JdbcExecutionBudgets(source).snapshot(run.identity().runRef());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var entered = new java.util.concurrent.atomic.AtomicBoolean(); var notices = new java.util.ArrayList<SessionRef>();
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, current, actor, prepared) -> true,
                configuration -> {}, (session, current) -> {}, (session, current, actor, operation, input) -> true, notices::add,
                (session, owner, events, readAfter) -> {
                    entered.set(true); assertEquals(events, readAfter.apply(beforeEvents.getLast().sequence()));
                    insertPlanBusinessEffect(session, "expired-participant-effect");
                    var until = run.lease().orElseThrow().leaseUntil();
                    var epoch = java.math.BigDecimal.valueOf(until.getEpochSecond()).add(java.math.BigDecimal.valueOf(until.getNano(), 9));
                    try (var query = session.getConnection().prepareStatement("SELECT pg_sleep(GREATEST(0,? - extract(epoch FROM clock_timestamp()))+0.025)")) {
                        query.setBigDecimal(1, epoch); query.execute();
                    } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                });
        var dispatch = withParticipantEvent(dispatch(run, "expires-participant-model", 1, 100), event("expires-participant-started"));
        assertEquals(new DispatchResult.Rejected("execution_lease_or_revision_changed"), store.dispatch(dispatch));
        calls.assertSingleTransaction(0, 1); assertTrue(entered.get()); assertTrue(notices.isEmpty());
        assertEquals(run, fixture.load(dispatch.run()).orElseThrow());
        assertEquals(beforeBudget, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertTrue(fixture.attempt(dispatch.run(), dispatch.attemptId()).isEmpty());
        assertTrue(fixture.action(dispatch.action().action()).isEmpty());
        assertTrue(fixture.material(TENANT, dispatch.action().request().reference()).isEmpty());
        assertTrue(fixture.material(TENANT, new ResultRef("expired-participant-effect")).isEmpty());
        assertEquals(beforeEvents, fixture.events(admission.run().owner().sessionRef(), 0, 100));
    }

    /** 补齐缺口的借用reader仍按正式端口一次最多一百条，不能另开事务才能看到新来源。 */
    @Test void eventParticipantReaderPagesNewSourcesInsideTheOwningTransaction() {
        var base = admission("participant-source-page", "participant-source-page-session", null, CAPACITY);
        var events = java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(index -> event("participant-source-page-" + index)).toList();
        var admission = new Admission(base.identity(), base.run(), base.fingerprint(), base.configuration(), base.input(), base.context(),
                base.budget(), events, base.configurationMaterials());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var participated = new java.util.concurrent.atomic.AtomicInteger();
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, owner -> {},
                (session, owner, saved, readAfter) -> {
                    participated.incrementAndGet(); assertEquals(101, saved.size());
                    assertEquals(saved.subList(0, 100), readAfter.apply(0));
                    assertEquals(saved.subList(100, 101), readAfter.apply(100)); assertTrue(readAfter.apply(101).isEmpty());
                });
        assertTrue(store.admit(admission).created()); calls.assertSingleTransaction(1, 0);
        calls.reset(); assertFalse(store.admit(admission).created()); calls.assertSingleTransaction(1, 0);
        assertEquals(1, participated.get());
    }

    /** 程序真实RPC拥有独立MEMBER child，不在在途父TOOL上登记第二Action；重复只读原准入。 */
    @Test void programChildAdmissionUsesExactParentRpcAndReplaysOriginalReceiptAfterCancellation() {
        var store=store();var initial=admission("program-parent","program-session",null,CAPACITY);
        var dispatch=domainDispatch(claim(store,store.admit(initial).run()),"program-parent-tool",Kind.TOOL);
        var parent=assertInstanceOf(DispatchResult.Registered.class,store.dispatch(dispatch));var child=childAdmission(parent.run(),"program-rpc-child");
        var source=new ProgramChildSource(parent.action().reference(),parent.attempt().id(),1);
        var command=new ProgramChildAdmission(parent,dispatch.action(),source,child);var seen=new java.util.ArrayList<Boolean>();
        var guard=(JdbcExecutionStore.ProgramChildGuard)(session,current,origin,candidate,existing)->{
            assertEquals(source,origin);assertEquals(child,candidate);seen.add(existing);
            assertEquals(parent.run(),store.participate(session,TENANT,()->store.load(parent.run().identity().runRef()).orElseThrow()));
            if(!existing) insertPlanBusinessEffect(session,"program-rpc-business-effect");
        };
        assertTrue(store.admitProgramChild(command,guard).created());assertEquals(List.of(false),seen);
        assertEquals(parent.run(),store.load(dispatch.run()).orElseThrow());assertEquals(parent.action(),store.action(parent.action().reference()).orElseThrow());
        assertTrue(store.originalInput(child.run().runRef()).orElseThrow().equals(child.input()));
        var before=new JdbcExecutionBudgets(JdbcExecutionStoreTest.source).snapshot(child.run().runRef());
        var cancelled=store.cancel(new Cancel(initial.identity(),dispatch.run(),new InputId("program-parent-cancel"),List.of()));
        assertEquals(Phase.FINISHED,cancelled.phase());
        assertFalse(store.admitProgramChild(command,(session,current,origin,candidate,existing)->{assertTrue(existing);}).created());
        assertEquals(before,new JdbcExecutionBudgets(JdbcExecutionStoreTest.source).snapshot(child.run().runRef()));
        var other=childAdmission(parent.run(),"program-rpc-other");
        assertEquals("execution_program_child_source_conflict",assertThrows(Rejection.class,()->store.admitProgramChild(new ProgramChildAdmission(parent,dispatch.action(),source,other),guard)).reason());
        assertTrue(store.load(other.run().runRef()).isEmpty());
        assertThrows(Rejection.class,()->store.admitProgramChild(new ProgramChildAdmission(parent,dispatch.action(),new ProgramChildSource(source.action(),source.attemptId(),2),childAdmission(parent.run(),"program-after-cancel")),guard));
    }

    /** Host guard或当前IAM拒绝时，child准入、预算、材料与真实领域副作用整体回滚。 */
    @Test void programChildFailureRollsBackJoinedBusinessFactsAndRejectsChangedParentRequest() {
        var store=store();var base=admission("program-rollback","program-rollback-session",null,CAPACITY);
        var dispatch=domainDispatch(claim(store,store.admit(base).run()),"program-rollback-tool",Kind.TOOL);
        var parent=assertInstanceOf(DispatchResult.Registered.class,store.dispatch(dispatch));var child=childAdmission(parent.run(),"program-rollback-child");
        var command=new ProgramChildAdmission(parent,dispatch.action(),new ProgramChildSource(parent.action().reference(),parent.attempt().id(),1),child);
        assertThrows(IllegalStateException.class,()->store.admitProgramChild(command,(session,current,origin,candidate,existing)->{insertPlanBusinessEffect(session,"program-rollback-effect");throw new IllegalStateException("program-host-rejected");}));
        assertTrue(store.load(child.run().runRef()).isEmpty());assertTrue(store.material(TENANT,new ResultRef("program-rollback-effect")).isEmpty());
        var prepared=dispatch.action();var changed=new Prepared(prepared.action(),prepared.ordinal(),prepared.kind(),prepared.target(),prepared.capability(),material("program-changed-request"),"changed");
        assertThrows(Rejection.class,()->store.admitProgramChild(new ProgramChildAdmission(parent,changed,command.source(),child),(session,current,origin,candidate,existing)->fail("invalid source must fail before Host")));
        var denied=new JdbcExecutionStore(source, identities,(session,current,actor,request)->false);
        assertEquals("execution_program_child_unauthorized",assertThrows(Rejection.class,()->denied.admitProgramChild(command,(session,current,origin,candidate,existing)->fail("IAM rejected before Host"))).reason());
        assertTrue(store.load(child.run().runRef()).isEmpty());
    }

    /** 并行组整批预算失败回滚全部槽位，不能留下第一个工具的发送权。 */
    @Test void batchBudgetFailureRollsBackEveryActionAttemptAndReservation() {
        var store = store(); var capacity = new ExecutionBudgetPort.Resources(8, 2, 1, 2, 1_000, 300, 1_300);
        var ready = batchReady(store, "batch-budget", capacity); var command = batchDispatch(ready, "batch-budget");
        var before = new JdbcExecutionBudgets(source).snapshot(command.run());
        assertInstanceOf(BatchDispatchResult.Rejected.class, store.dispatchBatch(command));
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(command.run()));
        assertEquals(ready, store.load(command.run()).orElseThrow());
        for (var member : command.requests()) {
            var request=((BatchMember.Fresh)member).request();
            assertTrue(store.action(request.action().action()).isEmpty()); assertTrue(store.attempt(command.run(), request.attemptId()).isEmpty());
            assertTrue(store.material(TENANT, request.action().request().reference()).isEmpty());
        }
    }

    /** 两个真实结果逆序返回，前一个只落事实，最后一个同事务按原槽位采纳完整上下文。 */
    @Test void lastAccurateBatchResultAdoptsOrderedContextInItsOwnCommit() {
        var fixture = store(); var ready = batchReady(fixture, "batch-ordered", CAPACITY); var command = batchDispatch(ready, "batch-ordered");
        var registered = assertInstanceOf(BatchDispatchResult.Registered.class, fixture.dispatchBatch(command));
        var reference = registered.run().currentBatch().orElseThrow();
        assertTrue(registered.run().currentAction().isEmpty()); assertEquals(2, registered.entries().size());
        assertInstanceOf(BatchDispatchResult.Existing.class, fixture.dispatchBatch(command));
        var first = registered.entries().get(0); var second = registered.entries().get(1);
        assertTrue(fixture.authorizeSend(registered.permits().get(0), ((BatchMember.Fresh)command.requests().get(0)).request().action()));
        assertTrue(fixture.authorizeSend(registered.permits().get(1), ((BatchMember.Fresh)command.requests().get(1)).request().action()));
        var secondResult = batchResult(registered.run(), second.attempt().id(), "batch-ordered-second");
        var saved = fixture.completeBatch(new BatchCompletion(reference, secondResult, Optional.empty()));
        assertTrue(saved.newlySaved()); assertFalse(saved.advanced()); assertEquals(registered.run(), saved.run());
        assertTrue(fixture.material(TENANT, secondResult.context().reference()).isEmpty());
        assertTrue(fixture.events(ready.identity().owner().sessionRef(), 0, 100).stream().anyMatch(value -> value.event().id().equals(secondResult.events().getFirst().id())));
        assertEquals(registered.run().revision(), fixture.events(ready.identity().owner().sessionRef(), 0, 100).getLast().revision());
        var firstResult = batchResult(registered.run(), first.attempt().id(), "batch-ordered-first");
        var adoption = batchAdoption(registered, firstResult.result().reference(), secondResult.result().reference(), "batch-ordered");
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {});
        var completed = store.completeBatch(new BatchCompletion(reference, firstResult, Optional.of(adoption)));
        calls.assertSingleTransaction(1, 0); assertTrue(completed.advanced()); assertTrue(completed.newlySaved());
        assertEquals(registered.run().revision() + 1, completed.run().revision()); assertEquals(Phase.READY, completed.run().phase());
        assertTrue(completed.run().currentBatch().isEmpty()); assertTrue(completed.run().currentAction().isEmpty());
        assertEquals(adoption.next(), completed.run().progress());
        var entries = fixture.batch(reference); assertEquals(List.of(1, 2), entries.stream().map(value -> value.action().batchSlot().orElseThrow().slot()).toList());
        assertTrue(entries.stream().allMatch(value -> value.attempt().adopted() && value.action().selectedAttempt().equals(Optional.of(value.attempt().id()))));
        var beforeRepeat = new JdbcExecutionBudgets(source).snapshot(command.run());
        assertFalse(store.completeBatch(new BatchCompletion(reference, firstResult, Optional.of(adoption))).advanced());
        assertEquals(beforeRepeat, new JdbcExecutionBudgets(source).snapshot(command.run()));
        assertEquals(5, fixture.events(ready.identity().owner().sessionRef(), 0, 100).size());
    }

    /** mixed新槽位只增加一个真实调用，原已采纳Action与安全结果始终属于原native组。 */
    @Test void mixedBatchReusesExactSelectedResultWithoutChangingOriginalActionOrBudget() {
        var store=store();var initial=batchReady(store,"mixed-original",CAPACITY);
        var registered=assertInstanceOf(BatchDispatchResult.Registered.class,store.dispatchBatch(batchDispatch(initial,"mixed-original")));
        var originalRef=registered.run().currentBatch().orElseThrow();
        var first=batchResult(registered.run(),registered.entries().get(0).attempt().id(),"mixed-original-first");
        var second=batchResult(registered.run(),registered.entries().get(1).attempt().id(),"mixed-original-second");
        store.completeBatch(new BatchCompletion(originalRef,first,Optional.empty()));
        var finished=store.completeBatch(new BatchCompletion(originalRef,second,Optional.of(batchAdoption(registered,first.result().reference(),second.result().reference(),"mixed-original")))).run();
        var old=store.batch(originalRef).get(0);var oldAction=old.action();var oldAttempt=old.attempt();
        var model=assertInstanceOf(DispatchResult.Registered.class,store.dispatch(dispatch(finished,"mixed-next-model",4,100)));
        var context=material("mixed-model-context");
        var ready=store.complete(new Completion(finished.identity().runRef(),model.attempt().id(),ResultKind.SUCCEEDED,material("mixed-model-result"),Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.unreported(),model.run().revision(),model.run().lease().orElseThrow(),new Progress(2,2,0,context.reference()),context,List.of(),Optional.empty())).run();
        var supplied=new BatchMember.Reused(1,oldAction.reference(),oldAttempt.id(),oldAttempt.request(),oldAttempt.result().orElseThrow(),oldAttempt.feedback().orElseThrow());
        var fresh=batchFresh(ready,"mixed-new",2,5);
        var command=new BatchDispatch(ready.identity().runRef(),ready.revision(),ready.lease().orElseThrow(),model.attempt().id(),List.of(supplied,fresh),actor(ready));
        var mixed=assertInstanceOf(BatchDispatchResult.Registered.class,store.dispatchBatch(command));
        assertEquals(1,mixed.permits().size());assertEquals(List.of(true,false),mixed.entries().stream().map(BatchEntry::reused).toList());
        assertEquals(oldAction,store.action(oldAction.reference()).orElseThrow());assertEquals(oldAttempt,store.attempt(command.run(),oldAttempt.id()).orElseThrow());
        var before=new JdbcExecutionBudgets(source).snapshot(command.run());
        assertInstanceOf(BatchDispatchResult.Existing.class,store.dispatchBatch(command));assertEquals(before,new JdbcExecutionBudgets(source).snapshot(command.run()));
        var actual=batchResult(mixed.run(),mixed.permits().getFirst().attempt().id(),"mixed-new-actual");var adopted=material("mixed-adopted");
        var adoption=new BatchAdoption(List.of(new BatchResultRef(oldAction.reference(),oldAttempt.id(),oldAttempt.result().orElseThrow()),new BatchResultRef(fresh.request().action().action(),actual.attemptId(),actual.result().reference())),new Progress(2,3,0,adopted.reference()),adopted,List.of(event("mixed-adopted")));
        assertTrue(store.completeBatch(new BatchCompletion(mixed.run().currentBatch().orElseThrow(),actual,Optional.of(adoption))).advanced());
        assertEquals(oldAction,store.action(oldAction.reference()).orElseThrow());assertEquals(oldAttempt,store.attempt(command.run(),oldAttempt.id()).orElseThrow());
        assertEquals(List.of(1,2),store.batch(mixed.run().currentBatch().orElseThrow()).stream().map(value->value.slot().slot()).toList());
    }

    /** 早响应不动原Run，第二card可用原声明版本；各响应者复验失败回滚整批continuation。 */
    @Test void multipleWaitsAcceptIndependentRespondersAndContinueAtomicallyWithoutReplayingSiblings() throws Exception {
        var store=store();var ready=batchReady(store,"multiwait",CAPACITY);var command=batchDispatch(ready,"multiwait");
        var registered=assertInstanceOf(BatchDispatchResult.Registered.class,store.dispatchBatch(command));var run=registered.run();var reference=run.currentBatch().orElseThrow();
        var first=registered.entries().get(0);var second=registered.entries().get(1);
        var firstWait=new WaitRef(first.action().reference(),1);var secondWait=new WaitRef(second.action().reference(),1);
        var methods=Map.of("answer",ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        assertEquals(run,store.completeBatch(new BatchCompletion(reference,inputRequired(run,first.attempt().id(),firstWait,methods),Optional.empty())).run());
        var one=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT,"responder-one"),run.identity().owner(),Set.of("sessions.write"),1,new ResultRef("multiwait-responder-one"));
        var two=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT,"responder-two"),run.identity().owner(),Set.of("sessions.write"),1,new ResultRef("multiwait-responder-two"));
        persistOriginalVerification(one);persistOriginalVerification(two);
        var early=store.resume(new Resume(one,command.run(),firstWait,run.revision(),new InputId("multiwait-response-one"),material("multiwait-response-one"),"multiwait-one",methods,List.of(event("multiwait-response-one"))));
        assertEquals(run,early.run());assertEquals(run.revision(),early.waiting().acceptedResponse().orElseThrow().acceptedRevision());
        assertTrue(store.authorizeSend(registered.permits().get(1),((BatchMember.Fresh)command.requests().get(1)).request().action()));
        var suspended=store.completeBatch(new BatchCompletion(reference,inputRequired(run,second.attempt().id(),secondWait,methods),Optional.empty())).run();
        assertEquals(Phase.WAITING,suspended.phase());assertTrue(suspended.lease().isEmpty());assertTrue(suspended.waiting().isEmpty());
        var accepted=store.resume(new Resume(two,command.run(),secondWait,run.revision(),new InputId("multiwait-response-two"),material("multiwait-response-two"),"multiwait-two",methods,List.of()));
        assertEquals(Phase.READY,accepted.run().phase());assertEquals(run.actorVerificationRef(),accepted.run().actorVerificationRef());
        var claimed=claim(store,accepted.run());var calls=List.of(batchContinuation(claimed,first,firstWait,"multiwait-next-one"),batchContinuation(claimed,second,secondWait,"multiwait-next-two"));
        var continuing=new BatchContinue(command.run(),claimed.revision(),claimed.lease().orElseThrow(),reference,calls,actor(claimed));
        var before=new JdbcExecutionBudgets(source).snapshot(command.run());
        var revoked=new JdbcExecutionStore(source, identities,new JdbcExecutionBudgets(source),(session,state,actor,prepared)->true,configuration->{},(session,state)->{},
                (session,state,actor,operation,input)->!actor.verificationRef().equals(two.verificationRef()),session->{});
        assertInstanceOf(BatchDispatchResult.Rejected.class,revoked.dispatchBatchContinuations(continuing));
        assertEquals(before,new JdbcExecutionBudgets(source).snapshot(command.run()));
        assertTrue(store.attempt(command.run(),"multiwait-next-one").isEmpty());assertTrue(store.waiting(command.run(),firstWait).orElseThrow().continuationAttempt().isEmpty());
        var paused = new JdbcExecutionStore(source, identities, new JdbcExecutionBudgets(source), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, state) -> {}, (session, state, actor, operation, input) -> true, session -> {},
                (session, owner, events, readAfter) -> {}, (session, state, prepared) -> false);
        assertEquals(new BatchDispatchResult.Rejected("execution_dispatch_paused"), paused.dispatchBatchContinuations(continuing));
        assertEquals(claimed, store.load(command.run()).orElseThrow());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(command.run()));
        assertTrue(store.attempt(command.run(), "multiwait-next-one").isEmpty());
        assertTrue(store.waiting(command.run(), firstWait).orElseThrow().continuationAttempt().isEmpty());
        var continued=assertInstanceOf(BatchDispatchResult.Registered.class,store.dispatchBatchContinuations(continuing));assertEquals(2,continued.permits().size());
        assertInstanceOf(BatchDispatchResult.Existing.class,store.dispatchBatchContinuations(continuing));
        var current=continued.run();var nextWait=new WaitRef(first.action().reference(),2);
        store.completeBatch(new BatchCompletion(reference,inputRequired(current,"multiwait-next-one",nextWait,methods),Optional.empty()));
        var secondDone=batchResult(current,"multiwait-next-two","multiwait-second-done");
        var again=store.completeBatch(new BatchCompletion(reference,secondDone,Optional.empty())).run();assertEquals(Phase.WAITING,again.phase());
        assertEquals(List.of(nextWait),store.batchWaits(reference).stream().map(Waiting::reference).toList());
        assertThrows(Rejection.class,()->store.resume(new Resume(one,command.run(),firstWait,current.revision(),new InputId("multiwait-old-reply"),material("multiwait-old-reply"),"old",methods,List.of())));
        var resumed=store.resume(new Resume(one,command.run(),nextWait,current.revision(),new InputId("multiwait-new-reply"),material("multiwait-new-reply"),"new",methods,List.of())).run();
        var stop=claim(store,resumed);var stopContext=material("multiwait-stop-context");var entries=store.batch(reference);
        var refs=entries.stream().map(value->new BatchResultRef(value.action().reference(),value.attempt().id(),value.attempt().result().orElseThrow())).toList();
        var stopped=store.stopBatch(new BatchStop(command.run(),reference,stop.revision(),stop.lease().orElseThrow(),refs,new Progress(1,4,0,stopContext.reference()),stopContext,RunStatus.TERMINATED,List.of(event("multiwait-stopped"))));
        assertEquals(Phase.FINISHED,stopped.phase());assertEquals(4,stopped.progress().toolCalls());assertTrue(stopped.currentBatch().isEmpty());
        assertEquals("multiwait-next-two",store.action(second.action().reference()).orElseThrow().currentAttempt());
        assertTrue(store.attempt(command.run(),"multiwait-new-reply").isEmpty());
    }

    /** 所有card在最后兄弟返回前已响应，统一READY仍保留原drive lease，不改响应者为发送主体。 */
    @Test void earlyResponsesForAllCardsKeepDriveLeaseUntilLastSiblingThenBecomeReady() {
        var store=store();var ready=batchReady(store,"batch-early-all",CAPACITY);
        var members=List.<BatchMember>of(batchFresh(ready,"early-one",1,2),batchFresh(ready,"early-two",2,3),batchFresh(ready,"early-three",3,4));
        var command=new BatchDispatch(ready.identity().runRef(),ready.revision(),ready.lease().orElseThrow(),"batch-early-all-model-attempt",members,actor(ready));
        var registered=assertInstanceOf(BatchDispatchResult.Registered.class,store.dispatchBatch(command));var run=registered.run();var ref=run.currentBatch().orElseThrow();
        var methods=Map.of("answer",ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        for(int index=0;index<2;index++) {
            var entry=registered.entries().get(index);var waiting=new WaitRef(entry.action().reference(),1);
            assertEquals(run,store.completeBatch(new BatchCompletion(ref,inputRequired(run,entry.attempt().id(),waiting,methods),Optional.empty())).run());
            var response=store.resume(new Resume(actor(run),command.run(),waiting,run.revision(),new InputId("early-answer-"+index),material("early-answer-"+index),"early-answer-"+index,methods,List.of()));
            assertEquals(run,response.run());assertEquals(run.revision(),response.waiting().acceptedResponse().orElseThrow().acceptedRevision());
        }
        assertTrue(store.authorizeSend(registered.permits().get(2),((BatchMember.Fresh)members.get(2)).request().action()));
        var last=batchResult(run,registered.entries().get(2).attempt().id(),"early-third-result");
        var state=store.completeBatch(new BatchCompletion(ref,last,Optional.empty())).run();
        assertEquals(Phase.READY,state.phase());assertEquals(run.revision()+1,state.revision());assertEquals(run.lease(),state.lease());assertEquals(run.actorVerificationRef(),state.actorVerificationRef());
        assertEquals(state,store.quiesceBatch(new BatchQuiesce(ref,state.revision(),state.lease().orElseThrow(),List.of())).run());
        assertEquals(2,store.batchWaits(ref).size());
    }

    /** UNKNOWN原事实不会提前剥夺兄弟发送权，最后原回调到齐后统一静止且不能重放。 */
    @Test void unknownBatchMemberPreservesInflightSiblingUntilAllCallbacksSettle() {
        var store=store();var ready=batchReady(store,"batch-unknown",CAPACITY);var original=batchDispatch(ready,"batch-unknown");
        var members = new java.util.ArrayList<BatchMember>();
        for (var value : original.requests()) {
            var fresh = (BatchMember.Fresh) value; var request = fresh.request();
            var demand = new ExecutionBudgetPort.Demand(original.run(), request.attemptId(), request.action().fingerprint(),
                    ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 100, 20), false);
            members.add(new BatchMember.Fresh(fresh.slot(), new BatchRequest(request.action(), request.attemptId(), demand)));
        }
        var command = new BatchDispatch(original.run(), original.revision(), original.lease(), original.sourceAttempt(), members, original.actor());
        var registered=assertInstanceOf(BatchDispatchResult.Registered.class,store.dispatchBatch(command));var run=registered.run();var ref=run.currentBatch().orElseThrow();
        var unknown=withUsage(completion(run,registered.permits().get(0).attempt().id(),ResultKind.UNKNOWN,"batch-unknown-fact","batch-unknown-unused","batch-unknown-event"),25,5);
        var budgets = new JdbcExecutionBudgets(source); var before = budgets.snapshot(command.run());
        assertEquals(run,store.completeBatch(new BatchCompletion(ref,unknown,Optional.empty())).run());
        assertEquals(before, budgets.snapshot(command.run()));
        assertFalse(store.completeBatch(new BatchCompletion(ref,withUsage(unknown,30,6),Optional.empty())).newlySaved());
        assertEquals(before, budgets.snapshot(command.run()));
        assertTrue(store.authorizeSend(registered.permits().get(1),((BatchMember.Fresh)command.requests().get(1)).request().action()));
        var exact=batchResult(run,registered.permits().get(1).attempt().id(),"batch-unknown-sibling");
        var stopped=store.completeBatch(new BatchCompletion(ref,exact,Optional.empty())).run();
        assertEquals(Phase.UNCERTAIN,stopped.phase());assertTrue(stopped.lease().isEmpty());
        assertInstanceOf(BatchDispatchResult.Existing.class,store.dispatchBatch(command));
        assertEquals(Optional.of(ResultKind.UNKNOWN),store.attempt(command.run(),unknown.attemptId()).orElseThrow().resultKind());
        var known = withUsage(batchResult(run, unknown.attemptId(), "batch-unknown-late"), 80, 10);
        var late = store.completeBatch(new BatchCompletion(ref, known, Optional.empty()));
        assertTrue(late.newlySaved()); assertFalse(late.advanced()); assertEquals(Phase.UNCERTAIN, late.run().phase());
        assertEquals(Optional.of(unknown.result().reference()), late.attempt().uncertainty());
        assertFalse(store.completeBatch(new BatchCompletion(ref, known, Optional.empty())).newlySaved());
        var settled = budgets.snapshot(command.run());
        assertEquals(before.consumed().inputTokens() + 80, settled.consumed().inputTokens());
        assertThrows(IllegalStateException.class, () -> store.completeBatch(new BatchCompletion(ref, withUsage(known, 81, 10), Optional.empty())));
        assertEquals(settled, budgets.snapshot(command.run()));
    }

    /** 重启时全部准确结果已存只做恢复采纳，缺失组只静止不重发且迟到结果不重开。 */
    @Test void batchRecoveryAdoptsOnlySavedAccurateResultsAndQuiescesMissingResults() throws Exception {
        var store = store(); var ready = batchReady(store, "batch-recovery", CAPACITY);
        var registered = assertInstanceOf(BatchDispatchResult.Registered.class, store.dispatchBatch(batchDispatch(ready, "batch-recovery")));
        var reference = registered.run().currentBatch().orElseThrow();
        var first = batchResult(registered.run(), registered.entries().get(0).attempt().id(), "batch-recovery-first");
        var second = batchResult(registered.run(), registered.entries().get(1).attempt().id(), "batch-recovery-second");
        store.completeBatch(new BatchCompletion(reference, first, Optional.empty())); store.completeBatch(new BatchCompletion(reference, second, Optional.empty()));
        expire(reference.run()); var takeover = store.claim(new Claim(reference.run(), "recovery", "batch-recovery", LEASE)).orElseThrow();
        assertFalse(store.quiesceBatch(new BatchQuiesce(reference, takeover.revision(), takeover.lease().orElseThrow(), List.of())).changed());
        var adopted = store.adoptBatch(new BatchAdopt(reference.run(), reference, takeover.revision(), takeover.lease().orElseThrow(),
                batchAdoption(registered, first.result().reference(), second.result().reference(), "batch-recovery")));
        assertTrue(adopted.advanced()); assertTrue(adopted.run().currentBatch().isEmpty());

        var incomplete = batchReady(store, "batch-missing", CAPACITY);
        var pending = assertInstanceOf(BatchDispatchResult.Registered.class, store.dispatchBatch(batchDispatch(incomplete, "batch-missing")));
        var missingRef = pending.run().currentBatch().orElseThrow();
        var known = batchResult(pending.run(), pending.entries().get(1).attempt().id(), "batch-missing-known");
        store.completeBatch(new BatchCompletion(missingRef, known, Optional.empty()));
        expire(missingRef.run()); var claimed = store.claim(new Claim(missingRef.run(), "recovery", "batch-missing", LEASE)).orElseThrow();
        var before = new JdbcExecutionBudgets(source).snapshot(missingRef.run());
        var quietCommand = new BatchQuiesce(missingRef, claimed.revision(), claimed.lease().orElseThrow(), List.of(event("batch-missing-unknown")));
        var quiet = store.quiesceBatch(quietCommand); assertTrue(quiet.changed()); assertEquals(Phase.UNCERTAIN, quiet.run().phase());
        assertTrue(quiet.run().lease().isEmpty()); assertTrue(quiet.entries().get(0).attempt().result().isEmpty());
        assertEquals(before, new JdbcExecutionBudgets(source).snapshot(missingRef.run()));
        assertFalse(store.quiesceBatch(quietCommand).changed()); assertTrue(store.claim(new Claim(missingRef.run(), "never", "never", LEASE)).isEmpty());
        var late = batchResult(pending.run(), pending.entries().get(0).attempt().id(), "batch-missing-late");
        var fact = store.completeBatch(new BatchCompletion(missingRef, late, Optional.of(batchAdoption(pending,
                late.result().reference(), known.result().reference(), "batch-missing"))));
        assertTrue(fact.newlySaved()); assertFalse(fact.advanced()); assertEquals(quiet.run(), fact.run());
    }

    /** 真实模型派发与开始事件只提交一次；重复命令不获得外调权，也不追加第二条开始。 */
    @Test void modelStartedEventSharesDispatchTransactionAndExistingDispatchDoesNotPublishAgain() {
        var fixture = store(); var admission = admission("model-events", "model-events-session", null, CAPACITY);
        var base = dispatch(claim(fixture, fixture.admit(admission).run()), "model-events-model", 1, 100);
        var started = event("model-started");
        var dispatch = new Dispatch(base.run(),base.revision(),base.lease(),base.action(),base.attemptId(),base.budget(),base.actor(),base.continuation(),List.of(started));
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> true, session -> {
                    assertEquals(Phase.AWAITING_RESULT,fixture.load(dispatch.run()).orElseThrow().phase());
                    assertEquals(started,fixture.events(session,0,100).getLast().event(),"提交后通知必须看见同事务开始事实");
                });
        var registered = assertInstanceOf(DispatchResult.Registered.class,store.dispatch(dispatch));
        calls.assertSingleTransaction(1,0);
        var events = fixture.events(admission.run().owner().sessionRef(),0,100);
        assertEquals(2,events.size()); assertEquals(started,events.getLast().event());
        assertEquals(registered.run().revision(),events.getLast().revision());
        var budget = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        assertInstanceOf(DispatchResult.Existing.class,store.dispatch(dispatch));
        assertEquals(events,fixture.events(admission.run().owner().sessionRef(),0,100));
        assertEquals(budget,new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
    }

    /** 开始事件冲突、当前撤权或取消均不能留下孤立开始、Attempt或预算占用。 */
    @Test void modelStartedEventFailureRollsBackEntireDispatchAndDeniedOrCancelledRunsPublishNothing() {
        var fixture = store(); var admission = admission("model-start-rollback","model-start-rollback-session",null,CAPACITY);
        var run = claim(fixture,fixture.admit(admission).run()); var base = dispatch(run,"model-start-rollback-model",1,100);
        var originalEvents = fixture.events(admission.run().owner().sessionRef(),0,100);
        var conflict = new Event(originalEvents.getFirst().event().id(),"test",payload("different"));
        var dispatch = new Dispatch(base.run(),base.revision(),base.lease(),base.action(),base.attemptId(),base.budget(),base.actor(),base.continuation(),List.of(conflict));
        var budget = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        assertEquals("execution_event_conflict",assertThrows(Rejection.class,()->fixture.dispatch(dispatch)).getMessage());
        assertEquals(run,fixture.load(dispatch.run()).orElseThrow()); assertTrue(fixture.attempt(dispatch.run(),dispatch.attemptId()).isEmpty());
        assertEquals(budget,new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertEquals(originalEvents,fixture.events(admission.run().owner().sessionRef(),0,100));
        var valid = new Dispatch(base.run(),base.revision(),base.lease(),base.action(),base.attemptId(),base.budget(),base.actor(),base.continuation(),List.of(event("start-denied")));
        var denied = new JdbcExecutionStore(source, identities,(session,current,actor,prepared)->false);
        assertEquals(new DispatchResult.Rejected("execution_dispatch_unauthorized"),denied.dispatch(valid));
        fixture.cancel(new Cancel(admission.identity(),base.run(),new InputId("start-cancel"),List.of()));
        assertEquals(new DispatchResult.Rejected("execution_run_finished"),fixture.dispatch(valid));
        assertTrue(fixture.attempt(base.run(),base.attemptId()).isEmpty());
        assertEquals(originalEvents,fixture.events(admission.run().owner().sessionRef(),0,100));
    }

    /** 多Run共享同根真实预算时，根锁保证竞争不超扣。 */
    @Test void parallelRunsCannotBothDispatchBeyondSharedBudget() throws Exception {
        var store = store(); var root = new RunRef(TENANT, "shared-budget-root");
        var capacity = new ExecutionBudgetPort.Resources(1, 1, 1, 1, 1_000, 300, 1_300);
        new JdbcExecutionBudgets(source).register(registration(root, root, capacity));
        var a = claim(store, store.admit(admission("budget-a", "budget-a-session", root, capacity)).run());
        var b = claim(store, store.admit(admission("budget-b", "budget-b-session", root, capacity)).run());
        var start = new CountDownLatch(1);
        List<DispatchResult> results;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return store.dispatch(dispatch(a, "budget-a-action", 1, 100)); });
            var second = workers.submit(() -> { start.await(); return store.dispatch(dispatch(b, "budget-b-action", 1, 100)); });
            start.countDown(); results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(DispatchResult.Registered.class::isInstance).count());
        assertEquals(1, results.stream().filter(DispatchResult.Rejected.class::isInstance).count());
        assertEquals("execution_budget_root_business_model_calls_limit", results.stream()
                .filter(DispatchResult.Rejected.class::isInstance).map(DispatchResult.Rejected.class::cast)
                .findFirst().orElseThrow().reason());
        assertEquals(1, new JdbcExecutionBudgets(source).snapshot(root).consumed().businessModels());
    }

    /** 当前派发撤权拒绝且不消耗预算，跨租户读取均被真实RLS隔离。 */
    @Test void deniedAuthorizationAndRlsLeaveNoDispatchOrCrossTenantVisibility() {
        var store = store(); var command = admission("rls", "rls-session", null, CAPACITY);
        var claimed = claim(store, store.admit(command).run()); var dispatch = dispatch(claimed, "rls-action", 1, 100);
        var denied = new JdbcExecutionStore(source, identities, (session, run, actor, prepared) -> false);
        assertEquals(new DispatchResult.Rejected("execution_dispatch_unauthorized"), denied.dispatch(dispatch));
        assertTrue(store.attempt(dispatch.run(), dispatch.attemptId()).isEmpty());
        assertEquals(ExecutionBudgetPort.Resources.ZERO, new JdbcExecutionBudgets(source).snapshot(dispatch.run()).reserved());
        var other = new TenantId("2");
        assertTrue(store.load(new RunRef(other, dispatch.run().runId())).isEmpty());
        assertTrue(store.material(other, command.input().reference()).isEmpty());
        assertTrue(store.events(new SessionRef(other, command.run().owner().sessionRef().sessionId()), 0, 100).isEmpty());
        var changed = new Admission(command.identity(), command.run(), command.fingerprint(), command.configuration(),
                new Material(command.input().reference(), payload("changed")), command.context(), command.budget(), command.events(), command.configurationMaterials());
        assertEquals("execution_material_conflict", assertThrows(IllegalStateException.class, () -> store.admit(changed)).getMessage());
        assertEquals(command.input(), store.material(TENANT, command.input().reference()).orElseThrow());
    }

    /** 授权hook中的真实Plan绑定写入只能随Registered提交，授权或预算拒绝均整体回滚。 */
    @Test void rejectedSingleDispatchRollsBackJoinedAuthorizationBusinessWrites() {
        var fixture = store(); var base = admission("dispatch-joined-auth", "dispatch-joined-auth-session", null, CAPACITY);
        var run = claim(fixture,fixture.admit(base).run()); var before = new JdbcExecutionBudgets(source).snapshot(base.run().runRef());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var store = new JdbcExecutionStore(observed, identities,(session,current,actor,prepared) -> {
            insertPlanBusinessEffect(session,"dispatch-auth-effect:"+prepared.action().actionId()); return allowed.get();
        });
        var denied = dispatch(run,"dispatch-joined-denied",1,100);
        assertEquals(new DispatchResult.Rejected("execution_dispatch_unauthorized"),store.dispatch(denied)); calls.assertSingleTransaction(0,1);
        assertTrue(fixture.material(TENANT,new ResultRef("dispatch-auth-effect:dispatch-joined-denied")).isEmpty());
        allowed.set(true); calls.reset(); var overBudget = dispatch(run,"dispatch-joined-budget-denied",1,2_000);
        var budgetRejected = assertInstanceOf(DispatchResult.Rejected.class,store.dispatch(overBudget));
        assertTrue(budgetRejected.reason().startsWith("execution_budget_")); calls.assertSingleTransaction(0,1);
        assertTrue(fixture.material(TENANT,new ResultRef("dispatch-auth-effect:dispatch-joined-budget-denied")).isEmpty());
        assertEquals(run,fixture.load(base.run().runRef()).orElseThrow()); assertTrue(fixture.actions(base.run().runRef(),0,100).isEmpty());
        assertTrue(fixture.attempt(base.run().runRef(),denied.attemptId()).isEmpty()); assertTrue(fixture.attempt(base.run().runRef(),overBudget.attemptId()).isEmpty());
        assertEquals(before,new JdbcExecutionBudgets(source).snapshot(base.run().runRef()));
        calls.reset(); assertInstanceOf(DispatchResult.Registered.class,store.dispatch(dispatch(run,"dispatch-joined-accepted",1,100))); calls.assertSingleTransaction(1,0);
        assertTrue(fixture.material(TENANT,new ResultRef("dispatch-auth-effect:dispatch-joined-accepted")).isPresent());
    }

    /** 活动确认与派发/结果共享一个连接；正文冲突时预算、活动区间与业务事实共同回滚。 */
    @Test void activityConfirmationSharesDispatchAndResultTransactionsAndRollsBackOnFailure() {
        var fixture = store(); var command = admission("activity-atomic", "activity-atomic-session", null, CAPACITY);
        var claimed = claim(fixture, fixture.admit(command).run());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var budgets = new JdbcExecutionBudgets(observed);
        var through = new java.util.concurrent.atomic.AtomicLong(1_000);
        var activityConnection = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
        var store = new JdbcExecutionStore(observed, identities, budgets, (session, run, actor, prepared) -> {
            assertSame(activityConnection.get(), session.getConnection()); return true;
        }, configuration -> {}, (session, run) -> {
            activityConnection.set(session.getConnection());
            assertEquals(through.get(), budgets.recordActivity(session, run, "drive", Instant.EPOCH,
                    Instant.EPOCH.plusMillis(through.get())).runActiveMillis());
        }, (session, run, actor, operation, input) -> true, session -> {});
        var dispatch = dispatch(claimed, "activity-atomic-model", 1, 100);
        var prepared = dispatch.action();
        var conflicting = new Prepared(prepared.action(), prepared.ordinal(), prepared.kind(), prepared.target(), prepared.capability(),
                new Material(command.input().reference(), payload("conflicting-request")), prepared.fingerprint());
        var failedDispatch = new Dispatch(dispatch.run(), dispatch.revision(), dispatch.lease(), conflicting, dispatch.attemptId(), dispatch.budget(), dispatch.actor(), Optional.empty(), List.of());
        assertEquals("execution_material_conflict", assertThrows(IllegalStateException.class, () -> store.dispatch(failedDispatch)).getMessage());
        calls.assertSingleTransaction(0, 1);
        assertEquals(0, new JdbcExecutionBudgets(source).snapshot(dispatch.run()).runActiveMillis());
        assertEquals(ExecutionBudgetPort.Resources.ZERO, new JdbcExecutionBudgets(source).snapshot(dispatch.run()).reserved());
        assertTrue(fixture.attempt(dispatch.run(), dispatch.attemptId()).isEmpty());
        assertTrue(fixture.action(prepared.action()).isEmpty());

        calls.reset();
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        calls.assertSingleTransaction(1, 0);
        var beforeResult = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        assertEquals(1_000, beforeResult.runActiveMillis());
        var completion = completion(registered.run(), dispatch.attemptId(), ResultKind.SUCCEEDED, "activity-result", "activity-next-context", "activity-done");
        var conflictingContext = new Material(command.context().reference(), payload("conflicting-context"));
        var failedResult = new Completion(completion.run(), completion.attemptId(), completion.kind(), completion.result(),
                completion.feedback(), completion.externalOperation(), completion.usage(), completion.revision(), completion.lease(),
                new Progress(1, 0, 0, conflictingContext.reference()), conflictingContext, completion.events(), completion.waitDeclaration());
        through.set(2_000); calls.reset();
        assertEquals("execution_material_conflict", assertThrows(IllegalStateException.class, () -> store.complete(failedResult)).getMessage());
        calls.assertSingleTransaction(0, 1);
        assertEquals(beforeResult, new JdbcExecutionBudgets(source).snapshot(dispatch.run()));
        assertTrue(fixture.attempt(dispatch.run(), dispatch.attemptId()).orElseThrow().result().isEmpty());
        assertTrue(fixture.material(TENANT, completion.result().reference()).isEmpty());
        assertTrue(fixture.material(TENANT, completion.feedback().orElseThrow().reference()).isEmpty());
        assertEquals(registered.run(), fixture.load(dispatch.run()).orElseThrow());

        calls.reset(); assertTrue(store.complete(completion).advanced());
        calls.assertSingleTransaction(1, 0);
        assertEquals(2_000, new JdbcExecutionBudgets(source).snapshot(dispatch.run()).runActiveMillis());
    }

    /** 续租在所属事务确认活动而不增加业务revision；旧fence失败时活动延长也回滚。 */
    @Test void renewalConfirmsActivityWithoutChangingRevisionOrAddingTransactions() {
        var fixture = store(); var command = admission("activity-renew", "activity-renew-session", null, CAPACITY);
        var claimed = claim(fixture, fixture.admit(command).run());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var budgets = new JdbcExecutionBudgets(observed);
        var through = new java.util.concurrent.atomic.AtomicLong(1_000);
        var store = new JdbcExecutionStore(observed, identities, budgets, (session, run, actor, prepared) -> true,
                configuration -> {}, (session, run) -> budgets.recordActivity(session, run, "drive", Instant.EPOCH,
                        Instant.EPOCH.plusMillis(through.get())), (session, run, actor, operation, input) -> true, session -> {});
        var renewed = store.renew(command.run().runRef(), claimed.lease().orElseThrow(), LEASE.plusSeconds(10));
        calls.assertSingleTransaction(1, 0);
        assertEquals(claimed.revision(), renewed.revision()); assertEquals(claimed.fence(), renewed.fence());
        assertTrue(renewed.lease().orElseThrow().leaseUntil().isAfter(claimed.lease().orElseThrow().leaseUntil()));
        assertEquals(1_000, new JdbcExecutionBudgets(source).snapshot(command.run().runRef()).runActiveMillis());
        var lease = renewed.lease().orElseThrow();
        var stale = new LeaseFence(lease.tenantId(), lease.claimId(), lease.workerId(), lease.fencingToken() + 1, lease.leaseUntil());
        through.set(2_000); calls.reset();
        assertEquals("execution_lease_changed", assertThrows(IllegalStateException.class,
                () -> store.renew(command.run().runRef(), stale, LEASE)).getMessage());
        calls.assertSingleTransaction(0, 1);
        assertEquals(1_000, new JdbcExecutionBudgets(source).snapshot(command.run().runRef()).runActiveMillis());
        assertEquals(renewed, fixture.load(command.run().runRef()).orElseThrow());
    }

    /** 最终CAS在授权检查跨过租约期限时拒绝，活动、预算与派发记录共同回滚。 */
    @Test void finalDispatchCasRejectsLeaseThatExpiresDuringAuthorization() {
        var store = store(); var command = admission("expires-in-authorization", "expires-session", null, CAPACITY);
        var admitted = store.admit(command).run();
        var claimed = store.claim(new Claim(admitted.identity().runRef(), "expiring-worker", "expiring-claim", Duration.ofSeconds(1))).orElseThrow();
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var budgets = new JdbcExecutionBudgets(observed);
        var slowAuthorization = new JdbcExecutionStore(observed, identities, budgets, (session, run, actor, prepared) -> {
            entered.set(true);
            var until = run.lease().orElseThrow().leaseUntil();
            var epoch = java.math.BigDecimal.valueOf(until.getEpochSecond()).add(java.math.BigDecimal.valueOf(until.getNano(), 9));
            // 由真实DB时钟等待原lease精确到期，避免靠客户端sleep猜测竞争时序。
            try (var query = session.getConnection().prepareStatement("SELECT pg_sleep(GREATEST(0,? - extract(epoch FROM clock_timestamp()))+0.025)")) {
                query.setBigDecimal(1, epoch); query.execute(); return true;
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        }, configuration -> {}, (session, run) -> assertEquals(1_000, budgets.recordActivity(session, run, "drive", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1)).runActiveMillis()), (session, run, actor, operation, input) -> true, session -> {});
        var dispatch = dispatch(claimed, "expires-action", 1, 100);
        assertEquals(new DispatchResult.Rejected("execution_lease_or_revision_changed"), slowAuthorization.dispatch(dispatch));
        calls.assertSingleTransaction(0, 1);
        assertTrue(entered.get()); assertTrue(store.attempt(dispatch.run(), dispatch.attemptId()).isEmpty());
        assertTrue(store.action(dispatch.action().action()).isEmpty());
        assertTrue(store.material(TENANT, dispatch.action().request().reference()).isEmpty());
        var budget = new JdbcExecutionBudgets(source).snapshot(dispatch.run());
        assertEquals(ExecutionBudgetPort.Resources.ZERO, budget.consumed());
        assertEquals(ExecutionBudgetPort.Resources.ZERO, budget.reserved());
        assertEquals(0, budget.runActiveMillis());
        assertEquals(claimed, store.load(dispatch.run()).orElseThrow());
    }

    /** 原根、补充与精确响应输入互斥；同类合法重投继续返回原回执，取消不重写原输入事实。 */
    @Test void controlInputIdsCannotChangeKindAndLegitimateReplaysSurviveCancellation() throws Exception {
        var store = store(); var base = admission("control-input-kind", "control-input-kind-session", null, CAPACITY);
        persistOriginalVerification(base.identity());
        var run = claim(store, store.admit(base).run());
        var action = new ActionRef(TENANT, "control-input-kind-tool");
        var dispatch = toolDispatch(run, action, "control-input-kind-attempt", Optional.empty());
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var waitRef = new WaitRef(action, 1); var methods = Map.of("answer", ToolPortal.ToolInputMethod.ELICITATION_CREATE);
        var waiting = store.complete(inputRequired(registered.run(), dispatch.attemptId(), waitRef, methods)).run();
        var rootInput = base.run().causationInputId();
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.cancel(
                new Cancel(base.identity(), run.identity().runRef(), rootInput, List.of()))).reason());
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.offerInput(
                new OfferInput(base.identity(), run.identity().runRef(), rootInput, material("kind-root-steer"), "root-steer"))).reason());
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.resume(
                new Resume(base.identity(), run.identity().runRef(), waitRef, waiting.revision(), rootInput,
                        material("kind-root-reply"), "root-reply", methods, List.of()))).reason());
        assertEquals(waiting, store.load(run.identity().runRef()).orElseThrow());

        var steer = new OfferInput(base.identity(), run.identity().runRef(), new InputId("kind-steer"), material("kind-steer-content"), "steer");
        assertTrue(store.offerInput(steer).created()); assertFalse(store.offerInput(steer).created());
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.cancel(
                new Cancel(base.identity(), steer.run(), steer.input(), List.of()))).reason());
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.resume(
                new Resume(base.identity(), steer.run(), waitRef, waiting.revision(), steer.input(), material("kind-steer-reply"), "steer-reply", methods, List.of()))).reason());
        assertEquals(waiting, store.load(run.identity().runRef()).orElseThrow());

        var reply = new Resume(base.identity(), steer.run(), waitRef, waiting.revision(), new InputId("kind-reply"),
                material("kind-reply-content"), "reply", methods, List.of());
        assertTrue(store.resume(reply).accepted()); assertFalse(store.resume(reply).accepted());
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.offerInput(
                new OfferInput(base.identity(), reply.run(), reply.input(), material("kind-reply-steer"), "reply-steer"))).reason());
        assertEquals("execution_control_input_conflict", assertThrows(Rejection.class, () -> store.cancel(
                new Cancel(base.identity(), reply.run(), reply.input(), List.of()))).reason());

        var cancel = new Cancel(base.identity(), reply.run(), new InputId("kind-cancel"), List.of(event("kind-cancelled")));
        var cancelled = store.cancel(cancel); assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled, store.cancel(cancel));
        assertFalse(store.offerInput(steer).created()); assertFalse(store.resume(reply).accepted());
        assertEquals("execution_run_finished", assertThrows(Rejection.class, () -> store.offerInput(
                new OfferInput(base.identity(), cancel.run(), cancel.input(), material("kind-cancel-steer"), "cancel-steer"))).reason());
        assertEquals("execution_run_finished", assertThrows(Rejection.class, () -> store.resume(
                new Resume(base.identity(), cancel.run(), waitRef, cancelled.revision(), cancel.input(), material("kind-cancel-reply"), "cancel-reply", methods, List.of()))).reason());
        assertEquals(cancelled, store.load(cancel.run()).orElseThrow());
        assertEquals(1, store.inputs(cancel.run(), 0, false, 10).size());
    }

    /** 两入口使用同一Run行锁竞争同一个input；只允许补充输入或取消中的一个产生新效果。 */
    @Test void concurrentSteerAndCancelCannotReuseOneInputForDifferentEffects() throws Exception {
        var store = store(); var base = admission("control-input-race", "control-input-race-session", null, CAPACITY);
        var original = store.admit(base).run(); persistOriginalVerification(base.identity());
        var shared = new InputId("control-input-race-shared");
        var steer = new OfferInput(base.identity(), base.run().runRef(), shared, material("control-input-race-steer"), "steer");
        var cancel = new Cancel(base.identity(), base.run().runRef(), shared, List.of(event("control-input-race-cancelled")));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var offered = workers.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                try { store.offerInput(steer); return "STEER"; }
                catch (Rejection denied) { return denied.reason(); }
            });
            var cancelled = workers.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                try { store.cancel(cancel); return "CANCEL"; }
                catch (Rejection denied) { return denied.reason(); }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            String offerResult = offered.get(20, TimeUnit.SECONDS), cancelResult = cancelled.get(20, TimeUnit.SECONDS);
            var saved = store.load(base.run().runRef()).orElseThrow();
            if (offerResult.equals("STEER")) {
                assertEquals("execution_control_input_conflict", cancelResult); assertEquals(original, saved);
                assertEquals(1, store.inputs(base.run().runRef(), 0, false, 10).size()); assertFalse(store.offerInput(steer).created());
            } else {
                assertEquals("CANCEL", cancelResult); assertEquals("execution_run_finished", offerResult);
                assertEquals(RunStatus.CANCELLED, saved.status()); assertTrue(store.inputs(base.run().runRef(), 0, false, 10).isEmpty());
                assertEquals(saved, store.cancel(cancel));
            }
        }
    }

    /** 外调中补充输入不失效原发送回执；稳定输入与当前权限分别核对。 */
    @Test void supplementalOfferPreservesInFlightRevisionLeaseAndOriginalAttempt() throws Exception {
        var fixture = store(); var base = admission("supplement-live", "supplement-live-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(base).run()); var command = dispatch(run, "supplement-live-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, fixture.dispatch(command));
        var budget = new JdbcExecutionBudgets(source).snapshot(command.run());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, reference) -> fail("supplement must not record activity"),
                (session, state, actor, operation, input) -> { assertEquals(JdbcExecutionStore.Control.STEER, operation); return allowed.get(); }, session -> {});
        var offer = new OfferInput(base.identity(), command.run(), new InputId("supplement-live-input"), material("supplement-live-content"), "supplement-live-fingerprint");
        var accepted = store.offerInput(offer); calls.assertSingleTransaction(1, 0); assertTrue(accepted.created());
        assertEquals(registered.run(), accepted.run()); assertEquals(1, accepted.input().ordinal()); assertTrue(accepted.input().appliedRevision().isEmpty());
        assertFalse(calls.sql.stream().anyMatch(sql -> sql.contains("runtime_budget_") || sql.startsWith("UPDATE agent_runtime_execution_runs")));
        assertEquals(registered.attempt(), fixture.attempt(command.run(), registered.attempt().id()).orElseThrow());
        assertEquals(budget, new JdbcExecutionBudgets(source).snapshot(command.run())); assertTrue(fixture.authorizeSend(registered, command.action()));
        persistOriginalVerification(base.identity());
        assertEquals("execution_supplement_not_ready", assertThrows(Rejection.class, () -> store.acceptInput(acceptInput(registered.run(),
                offer.input(), material("supplement-live-not-ready"), List.of()))).reason());
        assertFalse(store.offerInput(offer).created());
        assertEquals("execution_supplement_conflict", assertThrows(Rejection.class, () -> store.offerInput(new OfferInput(base.identity(), command.run(),
                offer.input(), new Material(offer.content().reference(), payload("changed")), offer.fingerprint()))).reason());
        var owner = SessionOwner.user(new SessionRef(TENANT, "supplement-wrong-owner"), new AgentId("agent"), new EndUserId(1));
        var stranger = new VerifiedIdentity(base.identity().principal(), owner, base.identity().scopes(), 1, new ResultRef("supplement-stranger"));
        assertEquals("execution_supplement_owner_mismatch", assertThrows(Rejection.class, () -> store.offerInput(new OfferInput(stranger, command.run(),
                new InputId("supplement-stranger"), material("supplement-stranger"), "supplement-stranger"))).reason());
        allowed.set(false);
        assertEquals("execution_supplement_unauthorized", assertThrows(Rejection.class, () -> store.offerInput(offer)).reason());
        allowed.set(true); fixture.cancel(new Cancel(base.identity(), command.run(), new InputId("supplement-live-cancel"), List.of()));
        assertEquals("execution_run_finished", assertThrows(Rejection.class, () -> store.offerInput(new OfferInput(base.identity(), command.run(),
                new InputId("supplement-after-cancel"), material("supplement-after-cancel"), "supplement-after-cancel"))).reason());
    }

    /** 新verification与凭据轮换仍是同一可信调用者；同集成的其他群操作员不能借原输入key。 */
    @Test void supplementalReplayUsesPersistedStableCallerAndRechecksCurrentAuthority() throws Exception {
        var base = admission("supplement-stable-caller", "supplement-stable-caller-session", null, CAPACITY);
        var group = new GroupBindingRef(TENANT, "supplement-stable-group");
        var owner = new SessionOwner(base.run().owner().sessionRef(), SessionSubject.group(group), SessionTarget.group(group));
        var firstActor = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(TENANT, "group-service", "integration", 1),
                owner, Set.of("groups.runs.control"), 1, new ResultRef("stable-caller-first"));
        var identity = new RunIdentity(owner, new AgentId("group-agent"), base.run().causationInputId(), base.run().runRef(), base.run().trace(),
                firstActor.verificationRef(), new RunRequester.EndUser(new EndUserId(501)),
                Optional.of(new SourceMessageRef(owner.sessionRef(), "committed-message")), new RunOriginRef(TENANT, "original-group-trigger"));
        var command = new Admission(firstActor, identity, base.fingerprint(), base.configuration(), base.input(), base.context(),
                base.budget(), base.events(), base.configurationMaterials());
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true); var authorizations = new java.util.concurrent.atomic.AtomicInteger();
        var counts = new TransactionCounts(); var observed = observedDataSource(counts);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, run, actor, prepared) -> false,
                configuration -> {}, (session, run) -> {}, (session, run, actor, operation, input) -> {
                    assertEquals(JdbcExecutionStore.Control.STEER, operation); assertEquals(owner, actor.owner());
                    authorizations.incrementAndGet();
                    // 当前验证身份的权限在原业务会话核验；原认证被撤销不改变历史来源。
                    return allowed.get() && identities.authorize(session, new IdentityPolicyPort.AuthorizationRequest(
                            actor, "groups.runs.control", payload("group-control-resource"), "trace", "span"))
                            instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
                }, session -> {});
        store.admit(command);
        persistOriginalVerification(firstActor, "open-platform", "group-control:101");
        var input = new InputId("stable-caller-input"); var content = material("stable-caller-content");
        var first = store.offerInput(new OfferInput(firstActor, identity.runRef(), input, content, "same-content")); assertTrue(first.created());
        var current = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(TENANT, "group-service", "integration", 2),
                owner, Set.of("groups.runs.control", "groups.runs.read"), 2, new ResultRef("stable-caller-renewed"));
        persistOriginalVerification(current, "open-platform", "group-control:101");
        // 原认证已撤销；重投只使用本次身份的当前授权，不重新授权旧凭据。
        identities.revoke(TENANT, firstActor.verificationRef());
        counts.reset(); int before = authorizations.get();
        var replay = store.offerInput(new OfferInput(current, identity.runRef(), input, content, "same-content"));
        counts.assertSingleTransaction(1, 0); assertEquals(before + 1, authorizations.get());
        assertFalse(replay.created()); assertEquals(first.input(), replay.input());
        assertEquals(firstActor.verificationRef(), replay.input().actorRef());
        assertEquals(identity, replay.run().identity());
        assertEquals("execution_supplement_conflict", assertThrows(Rejection.class, () -> store.offerInput(
                new OfferInput(current, identity.runRef(), input, material("stable-caller-other-content"), "other-content"))).reason());
        String[][] provenance = {{"other-operator", "open-platform", "group-control:202"},
                {"respond", "open-platform", "group-respond:101"}, {"channel", "internal", "group-control:101"}};
        for (var entry : provenance) {
            var other = new VerifiedIdentity(current.principal(), owner, current.scopes(), current.policyRevision(), new ResultRef("stable-caller-" + entry[0]));
            persistOriginalVerification(other, entry[1], entry[2]);
            assertEquals("execution_supplement_conflict", assertThrows(Rejection.class, () -> store.offerInput(
                    new OfferInput(other, identity.runRef(), input, content, "same-content"))).reason());
        }
        for (var principal : List.of(new PrincipalRef.ServicePrincipal(TENANT, "other-service", "integration", 2),
                new PrincipalRef.ServicePrincipal(TENANT, "group-service", "other-integration", 2))) {
            var other = new VerifiedIdentity(principal, owner, current.scopes(), current.policyRevision(), new ResultRef("stable-caller-" + principal.principalId() + principal.integrationId()));
            persistOriginalVerification(other, "open-platform", "group-control:101");
            assertEquals("execution_supplement_conflict", assertThrows(Rejection.class, () -> store.offerInput(
                    new OfferInput(other, identity.runRef(), input, content, "same-content"))).reason());
        }
        var forged = new VerifiedIdentity(current.principal(), owner, Set.of("forged-scope"), current.policyRevision(), current.verificationRef());
        assertEquals("execution_supplement_unauthorized", assertThrows(Rejection.class, () -> store.offerInput(
                new OfferInput(forged, identity.runRef(), input, content, "same-content"))).reason());
        allowed.set(false);
        assertEquals("execution_supplement_unauthorized", assertThrows(Rejection.class, () -> store.offerInput(
                new OfferInput(current, identity.runRef(), input, content, "same-content"))).reason());
        assertEquals(1, store.inputs(identity.runRef(), 0, false, 10).size());
        assertEquals(firstActor.verificationRef(), store.inputs(identity.runRef(), 0, false, 10).getFirst().actorRef());
    }

    /** 最早未采纳输入在一个连接内更新context；权限撤销仍阻止已登记输入及重复采用。 */
    @Test void supplementalApplicationIsOrderedLocalAndRechecksOriginalSubmitterAuthority() throws Exception {
        var fixture = store(); var base = admission("supplement-order", "supplement-order-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(base).run()); persistOriginalVerification(base.identity());
        var first = fixture.offerInput(new OfferInput(base.identity(), base.run().runRef(), new InputId("supplement-order-first"), material("supplement-order-first"), "first"));
        var second = fixture.offerInput(new OfferInput(base.identity(), base.run().runRef(), new InputId("supplement-order-second"), material("supplement-order-second"), "second"));
        assertEquals(List.of(first.input()), fixture.inputs(base.run().runRef(), 0, true, 1));
        assertEquals(List.of(second.input()), fixture.inputs(base.run().runRef(), first.input().ordinal(), false, 100));
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, reference) -> fail("supplement must not record activity"),
                (session, state, actor, operation, input) -> {
                    assertEquals(base.identity(), actor); assertEquals(JdbcExecutionStore.Control.STEER, operation); return allowed.get();
                }, session -> {});
        var context = material("supplement-order-context"); var acceptSecond = acceptInput(run, second.input().input(), context, List.of());
        assertEquals("execution_supplement_order_changed", assertThrows(Rejection.class, () -> store.acceptInput(acceptSecond)).reason());
        assertTrue(fixture.inputs(base.run().runRef(), 0, true, 100).stream().allMatch(value -> value.appliedRevision().isEmpty()));
        calls.reset(); var acceptFirst = acceptInput(run, first.input().input(), context, List.of(event("supplement-order-applied")));
        var applied = store.acceptInput(acceptFirst); calls.assertSingleTransaction(1, 0); assertTrue(applied.advanced());
        assertEquals(base.identity(), applied.actor()); assertEquals(applied.run().actorVerificationRef(), applied.actor().verificationRef());
        assertEquals(run.revision() + 1, applied.input().appliedRevision().orElseThrow()); assertEquals(context.reference(), applied.run().progress().context());
        assertEquals(run.identity(), applied.run().identity()); assertEquals(run.progress().decisions(), applied.run().progress().decisions());
        assertTrue(fixture.actions(base.run().runRef(), 0, 100).isEmpty());
        assertFalse(calls.sql.stream().anyMatch(sql -> sql.contains("runtime_budget_") || sql.startsWith("INSERT INTO agent_runtime_execution_attempts")));
        assertEquals(1, calls.sql.stream().filter(sql -> sql.contains("set_config('app.tenant_id'")).count());
        assertEquals(List.of(second.input()), fixture.inputs(base.run().runRef(), 0, true, 100));
        assertFalse(store.acceptInput(acceptFirst).advanced());
        allowed.set(false);
        assertEquals("execution_supplement_unauthorized", assertThrows(Rejection.class, () -> store.acceptInput(acceptFirst)).reason());
        assertEquals("execution_supplement_unauthorized", assertThrows(Rejection.class, () -> store.acceptInput(acceptInput(applied.run(), second.input().input(), material("supplement-order-next"), List.of()))).reason());
        allowed.set(true); assertTrue(store.acceptInput(acceptInput(applied.run(), second.input().input(), material("supplement-order-next"), List.of())).advanced());
        assertTrue(fixture.inputs(base.run().runRef(), 0, true, 100).isEmpty()); assertEquals(2, fixture.inputs(base.run().runRef(), 0, false, 100).size());
    }

    /** lease、context或公开事件冲突必须回滚输入采纳版本和全部本地写入。 */
    @Test void supplementalApplicationRollsBackContextReceiptAndEventsOnAnyFailure() throws Exception {
        var fixture = store(); var base = admission("supplement-rollback", "supplement-rollback-session", null, CAPACITY);
        var run = claim(fixture, fixture.admit(base).run()); persistOriginalVerification(base.identity());
        var input = fixture.offerInput(new OfferInput(base.identity(), base.run().runRef(), new InputId("supplement-rollback-input"), material("supplement-rollback-input"), "rollback")).input();
        var context = material("supplement-rollback-context"); var command = acceptInput(run, input.input(), context, List.of());
        var stale = new AcceptInput(command.run(), command.input(), command.revision() + 1, command.lease(), command.next(), command.context(), command.events());
        assertEquals("execution_lease_or_revision_changed", assertThrows(Rejection.class, () -> fixture.acceptInput(stale)).reason());
        var conflict = new Material(base.context().reference(), payload("changed-context"));
        assertEquals("execution_material_conflict", assertThrows(Rejection.class, () -> fixture.acceptInput(acceptInput(run, input.input(), conflict, List.of()))).reason());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls);
        var store = new JdbcExecutionStore(observed, identities, (session, state, actor, prepared) -> true);
        var badEvent = new Event(base.events().getFirst().id(), "changed", payload("changed-event"));
        assertEquals("execution_event_conflict", assertThrows(Rejection.class, () -> store.acceptInput(acceptInput(run, input.input(), context, List.of(badEvent)))).reason());
        calls.assertSingleTransaction(0, 1);
        assertEquals(run, fixture.load(base.run().runRef()).orElseThrow()); assertTrue(fixture.material(TENANT, context.reference()).isEmpty());
        assertTrue(fixture.inputs(base.run().runRef(), 0, true, 100).getFirst().appliedRevision().isEmpty());
        assertEquals(1, fixture.events(base.run().owner().sessionRef(), 0, 100).size());
        expire(base.run().runRef());
        assertEquals("execution_lease_or_revision_changed", assertThrows(Rejection.class, () -> fixture.acceptInput(command)).reason());
        assertTrue(fixture.inputs(base.run().runRef(), 0, true, 100).getFirst().appliedRevision().isEmpty());
    }

    /** 本地Plan业务、原命令、真实回执和context原子提交；重放只读取原业务回执。 */
    @Test void planAdoptionSharesOneTransactionAndReusesOnlyTheOriginalBusinessReceipt() throws Exception {
        var fixture = store(); var run = planReady(fixture, "plan-local"); persistOriginalVerification(actor(run));
        var budget = new JdbcExecutionBudgets(source).snapshot(run.identity().runRef());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store = new JdbcExecutionStore(observed, identities, new JdbcExecutionBudgets(observed), (session, state, actor, prepared) -> true,
                configuration -> {}, (session, reference) -> fail("Plan must not record activity"),
                (session, state, actor, operation, input) -> { assertEquals(JdbcExecutionStore.Control.STEER, operation); return allowed.get(); }, session -> {});
        var request = material("plan-local-request"); var result = material("plan-local-result"); var context = material("plan-local-context");
        var command = new PlanTransition(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), new ActionRef(TENANT, "plan-local-model"), request);
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        JdbcExecutionStore.PlanGuard guard = (session, current, saved) -> {
            assertEquals(run.identity(), current.identity());
            if (saved.isEmpty()) { executions.incrementAndGet(); insertPlanBusinessEffect(session, "plan-local-effect"); }
            else assertEquals(result, saved.orElseThrow());
            assertTrue(store.material(TENANT, new ResultRef("plan-local-effect")).isPresent());
            assertEquals(1, store.actions(command.run(), 0, 100).size()); assertTrue(store.attempt(command.run(), "plan-local-model-attempt").orElseThrow().adopted());
            assertEquals(current, store.load(command.run()).orElseThrow());
            assertThrows(IllegalStateException.class, () -> store.load(new RunRef(new TenantId("2"), command.run().runId())));
            return new PlanChange(saved.orElse(result), new Progress(current.progress().decisions(), current.progress().toolCalls(), current.progress().knowledgeCalls(), context.reference()), context,
                    List.of(event("plan-local-applied")), Optional.empty());
        };
        var adopted = store.adoptPlan(command, guard); calls.assertSingleTransaction(1, 0); assertTrue(adopted.advanced()); assertEquals(result, adopted.actualResult());
        assertEquals(run.revision()+1, adopted.run().revision()); assertEquals(context.reference(), adopted.run().progress().context());
        assertEquals(run.identity(), adopted.run().identity()); assertEquals(1, executions.get());
        assertFalse(calls.sql.stream().anyMatch(sql -> sql.contains("runtime_budget_") || sql.startsWith("INSERT INTO agent_runtime_execution_attempts")));
        assertEquals(1, calls.sql.stream().filter(sql -> sql.contains("set_config('app.tenant_id'")).count());
        assertEquals(budget, new JdbcExecutionBudgets(source).snapshot(command.run()));
        calls.reset(); assertFalse(store.adoptPlan(command, guard).advanced()); calls.assertSingleTransaction(1, 0); assertEquals(1, executions.get());
        assertEquals("execution_plan_request_conflict", assertThrows(Rejection.class, () -> store.adoptPlan(new PlanTransition(command.run(), command.revision(), command.lease(), command.sourceModelAction(),
                new Material(request.reference(), payload("changed-command"))), guard)).reason());
        allowed.set(false); assertEquals("execution_plan_unauthorized", assertThrows(Rejection.class, () -> store.adoptPlan(command, guard)).reason());
        assertEquals(1, executions.get());
    }

    /** Plan业务执行后的context或事件失败整体回滚，后续仍须用同原命令执行业务。 */
    @Test void planAdoptionRollsBackBusinessReceiptContextAndEventsTogether() throws Exception {
        var fixture = store(); var run = planReady(fixture, "plan-rollback"); persistOriginalVerification(actor(run));
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var store = new JdbcExecutionStore(observed, identities, (session, state, actor, prepared) -> true);
        var request = material("plan-rollback-request"); var result = material("plan-rollback-result"); var context = material("plan-rollback-context");
        var command = new PlanTransition(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), new ActionRef(TENANT, "plan-rollback-model"), request);
        var conflict = new Material(run.progress().context(), payload("conflicting-context"));
        JdbcExecutionStore.PlanGuard conflictingContext = (session, current, saved) -> {
            assertTrue(saved.isEmpty()); insertPlanBusinessEffect(session, "plan-rollback-effect");
            return new PlanChange(result, new Progress(current.progress().decisions(), 0, 0, conflict.reference()), conflict, List.of(), Optional.empty());
        };
        assertEquals("execution_material_conflict", assertThrows(Rejection.class, () -> store.adoptPlan(command, conflictingContext)).reason());
        calls.assertSingleTransaction(0, 1); assertEquals(run, fixture.load(command.run()).orElseThrow());
        for (var value : List.of(request,result,context,material("plan-rollback-effect"))) assertTrue(fixture.material(TENANT,value.reference()).isEmpty());
        var badEvent = new Event(new EventId("accepted-plan-rollback"), "conflicting", payload("conflicting-event")); calls.reset();
        assertEquals("execution_event_conflict", assertThrows(Rejection.class, () -> store.adoptPlan(command, (session,current,saved) -> {
            assertTrue(saved.isEmpty()); insertPlanBusinessEffect(session, "plan-rollback-effect");
            return new PlanChange(result,new Progress(current.progress().decisions(),0,0,context.reference()),context,List.of(badEvent),Optional.empty());
        })).reason());
        calls.assertSingleTransaction(0,1); assertEquals(run,fixture.load(command.run()).orElseThrow());
        for (var value : List.of(request,result,context,material("plan-rollback-effect"))) assertTrue(fixture.material(TENANT,value.reference()).isEmpty());
        assertEquals("execution_lease_or_revision_changed", assertThrows(Rejection.class, () -> store.adoptPlan(new PlanTransition(command.run(),command.revision()+1,command.lease(),command.sourceModelAction(),request),
                (session,current,saved) -> { fail("stale Plan must not execute business"); return null; })).reason());
        var adopted = store.adoptPlan(command,(session,current,saved) -> {
            assertTrue(saved.isEmpty()); insertPlanBusinessEffect(session,"plan-rollback-effect");
            return new PlanChange(result,new Progress(current.progress().decisions(),0,0,context.reference()),context,List.of(event("plan-rollback-applied")),Optional.empty());
        });
        assertTrue(adopted.advanced()); assertEquals(1,fixture.actions(command.run(),0,100).size());
    }

    /** Plan等待与本地业务同提交；原成功MODEL事实保持准确，只有真实领域proof可解除。 */
    @Test void planAdoptionDeclaresExactDependencyWaitWithoutAnotherAttemptOrBudget() throws Exception {
        var store = store(); var run = planReady(store,"plan-wait"); persistOriginalVerification(actor(run));
        var action = new ActionRef(TENANT,"plan-wait-model"); var reference = new WaitRef(action,1);
        var dependency = new PlanWait("plan-work",7,"plan-business-wait"); var context = material("plan-wait-context");
        var declaration = new WaitDeclaration(reference,WaitKind.PLAN,Map.of(),Optional.of(dependency));
        var command = new PlanTransition(run.identity().runRef(),run.revision(),run.lease().orElseThrow(),action,material("plan-wait-request"));
        var adopted = store.adoptPlan(command,(session,current,saved) -> new PlanChange(material("plan-wait-result"),
                new Progress(current.progress().decisions(),0,0,context.reference()),context,List.of(event("plan-waiting")),Optional.of(declaration)));
        assertEquals(Phase.WAITING,adopted.run().phase()); assertTrue(adopted.run().lease().isEmpty());
        var waiting = store.waiting(command.run(),"plan-wait-model-attempt").orElseThrow(); assertEquals(Optional.of(dependency),waiting.internal());
        var attempt = store.attempt(command.run(),waiting.sourceAttempt()).orElseThrow(); assertTrue(attempt.adopted()); assertEquals(Optional.of(ResultKind.SUCCEEDED),attempt.resultKind());
        assertEquals(Optional.of(attempt.id()),store.action(action).orElseThrow().selectedAttempt()); assertEquals(1,store.actions(command.run(),0,100).size());
        var response = new Resume(actor(run),command.run(),reference,adopted.run().revision(),new InputId("plan-wait-response"),material("plan-wait-response"),"plan-wait-response",Map.of(),List.of(event("plan-wait-ready")));
        assertEquals("execution_dependency_proof_required",assertThrows(Rejection.class,() -> store.resume(response)).reason());
        var resumed = store.acceptDependency(response,(session,parent,exact,children,result) -> { assertEquals(waiting,exact); assertTrue(children.isEmpty()); });
        var claimed = claim(store,resumed.run()); var next = material("plan-wait-next-context");
        assertTrue(store.acceptLocalResponse(new AcceptLocalResponse(command.run(),reference,response.input(),claimed.revision(),claimed.lease().orElseThrow(),
                new Progress(claimed.progress().decisions(),0,0,next.reference()),next,List.of())).advanced());
        assertEquals(1,store.actions(command.run(),0,100).size());
    }

    /** Plan成员直接复用真实业务准入，不造父Provider调用；重放只读原受理且不受后来许可/取消影响。 */
    @Test void planChildAdmissionSharesOriginalRootAndReplaysWithoutNewWritesOrProviderFacts() throws Exception {
        var fixture = store(); var base = admission("plan-child-parent","plan-child-parent-session",null,CAPACITY);
        var parent = fixture.admit(base).run(); persistOriginalVerification(base.identity()); var child = childAdmission(parent,"plan-child-member");
        var source = persistPlanChildSource(parent,child,7,Instant.now().plusSeconds(120)); var command = new PlanChildAdmission(base.run().runRef(),source,child);
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store = new JdbcExecutionStore(observed, identities,new JdbcExecutionBudgets(observed),(session,current,actor,prepared) -> true,configuration -> {},
                (session,run) -> fail("Plan child admission must not record activity"),
                (session,current,actor,operation,input) -> { assertEquals(JdbcExecutionStore.Control.STEER,operation); return allowed.get(); },session -> {});
        var proofCalls = new java.util.ArrayList<Boolean>();
        JdbcExecutionStore.PlanChildGuard proof = (session,current,origin,candidate,existing) -> {
            proofCalls.add(existing); assertEquals(parent.identity(),current.identity()); assertEquals(source.work(),origin.work());
            assertEquals(source.binding(),origin.binding()); assertEquals(child,candidate);
            assertTrue(store.load(base.run().runRef()).isPresent());
            if (!existing) insertPlanBusinessEffect(session,"plan-child-business-effect");
        };
        var accepted = store.admitPlanChild(command,proof); calls.assertSingleTransaction(1,0); assertTrue(accepted.created());
        assertEquals(parent,fixture.load(base.run().runRef()).orElseThrow()); assertTrue(fixture.actions(base.run().runRef(),0,100).isEmpty());
        assertEquals(ExecutionBudgetPort.Role.MEMBER,new JdbcExecutionBudgets(JdbcExecutionStoreTest.source).find(child.run().runRef()).orElseThrow().role());
        assertEquals(base.run().runRef(),new JdbcExecutionBudgets(JdbcExecutionStoreTest.source).find(child.run().runRef()).orElseThrow().root());
        assertEquals(child.configurationMaterials().getFirst(),fixture.material(TENANT,child.configurationMaterials().getFirst().reference()).orElseThrow());
        fixture.cancel(new Cancel(base.identity(),base.run().runRef(),new InputId("plan-child-parent-cancel"),List.of())); allowed.set(false);
        var changedPermit = new PlanChildSource(source.work(),source.binding(),new com.skillforge.runtime.api.dag.DagWork.Permit(8,Instant.EPOCH));
        calls.reset(); var replay = store.admitPlanChild(new PlanChildAdmission(command.parent(),changedPermit,child),proof);
        calls.assertSingleTransaction(1,0); assertFalse(replay.created()); assertEquals(accepted.run(),replay.run()); assertEquals(List.of(false,true),proofCalls);
        assertFalse(calls.sql.stream().anyMatch(sql -> sql.startsWith("INSERT") || sql.startsWith("UPDATE") || sql.startsWith("DELETE")));
        var another = childAdmission(parent,"plan-child-after-cancel");
        assertEquals("execution_run_finished",assertThrows(Rejection.class,() -> store.admitPlanChild(new PlanChildAdmission(command.parent(),planChildSource(parent,another,9,Instant.now().plusSeconds(120)),another),proof)).reason());
        assertTrue(fixture.load(another.run().runRef()).isEmpty());
    }

    /** 新成员准入的当前IAM、同root预算、领域proof和最后许可检查任一失败都不能留下child或关联。 */
    @Test void planChildAdmissionRejectsUnauthorizedBudgetSourceAndExpiredPermitAtomically() throws Exception {
        var fixture = store(); var base = admission("plan-child-reject","plan-child-reject-session",null,CAPACITY);
        var parent = fixture.admit(base).run(); persistOriginalVerification(base.identity()); var child = childAdmission(parent,"plan-child-rejected-member");
        var origin = persistPlanChildSource(parent,child,3,Instant.now().plusSeconds(120)); var command = new PlanChildAdmission(base.run().runRef(),origin,child);
        var denied = new JdbcExecutionStore(source, identities,new JdbcExecutionBudgets(source),(session,current,actor,prepared) -> true,configuration -> {},
                (session,run) -> {},(session,current,actor,operation,input) -> false,session -> {});
        assertEquals("execution_plan_child_unauthorized",assertThrows(Rejection.class,() -> denied.admitPlanChild(command,(session,current,source,candidate,existing) -> fail("unauthorized child must not call source guard"))).reason());
        var wrong = new Admission(child.identity(),child.run(),child.fingerprint(),child.configuration(),child.input(),child.context(),
                registration(child.run().runRef(),child.run().runRef(),CAPACITY),child.events(),child.configurationMaterials());
        assertEquals("execution_child_budget_root_mismatch",assertThrows(Rejection.class,() -> fixture.admitPlanChild(new PlanChildAdmission(command.parent(),origin,wrong),(session,current,source,candidate,existing) -> fail("wrong root must not call source guard"))).reason());
        var calls = new TransactionCounts(); var observed = observedDataSource(calls); var store = new JdbcExecutionStore(observed, identities,(session,current,actor,prepared) -> true);
        // 故意绕过夹具领域hook提交不存在的admission；正式FK仍必须拒绝，不能靠最小schema漏装而通过。
        var missingBinding = new DagWork.Binding("missing-admission:"+child.run().runRef().runId(),origin.binding().planRevision(),origin.binding().task(),origin.binding().runRef(),origin.binding().inputs());
        var unproven = new PlanChildSource(origin.work(),missingBinding,origin.permit());
        var missing = assertThrows(IllegalStateException.class,() -> store.admitPlanChild(new PlanChildAdmission(command.parent(),unproven,child),
                (session,current,source,candidate,existing) -> assertFalse(existing)));
        calls.assertSingleTransaction(0,1); assertForeignKeyViolation(missing,"execution_plan_child_runs_admission_fk");
        assertTrue(fixture.load(child.run().runRef()).isEmpty()); assertTrue(new JdbcExecutionBudgets(source).find(child.run().runRef()).isEmpty());
        assertTrue(fixture.material(TENANT,child.input().reference()).isEmpty());
        assertEquals(0,sessionCount("agent_runtime_session_owners",child.run().owner().sessionRef().sessionId(),TENANT));
        assertEquals(0,sessionCount("agent_runtime_execution_sessions",child.run().owner().sessionRef().sessionId(),TENANT));
        assertEquals(0,sessionCount("agent_runtime_execution_events",child.run().owner().sessionRef().sessionId(),TENANT));
        calls.reset();
        assertEquals("plan-source-revoked",assertThrows(Rejection.class,() -> store.admitPlanChild(command,(session,current,source,candidate,existing) -> {
            assertFalse(existing); insertPlanBusinessEffect(session,"plan-child-rejected-effect"); throw new Rejection("plan-source-revoked");
        })).reason()); calls.assertSingleTransaction(0,1);
        assertTrue(fixture.material(TENANT,new ResultRef("plan-child-rejected-effect")).isEmpty());
        var expired = new PlanChildSource(origin.work(),origin.binding(),new com.skillforge.runtime.api.dag.DagWork.Permit(3,Instant.EPOCH)); calls.reset();
        assertEquals("execution_plan_child_permit_expired",assertThrows(Rejection.class,() -> store.admitPlanChild(new PlanChildAdmission(command.parent(),expired,child),
                (session,current,source,candidate,existing) -> insertPlanBusinessEffect(session,"plan-child-rejected-effect"))).reason()); calls.assertSingleTransaction(0,1);
        assertTrue(fixture.load(child.run().runRef()).isEmpty()); assertTrue(new JdbcExecutionBudgets(source).find(child.run().runRef()).isEmpty());
        assertTrue(fixture.material(TENANT,child.input().reference()).isEmpty()); assertTrue(fixture.material(TENANT,child.configurationMaterials().getFirst().reference()).isEmpty());
        assertTrue(fixture.material(TENANT,new ResultRef("plan-child-rejected-effect")).isEmpty()); assertEquals(parent,fixture.load(command.parent()).orElseThrow());
        assertTrue(store.admitPlanChild(command,(session,current,source,candidate,existing) -> assertFalse(existing)).created());
    }

    /** 父Plan真实WAITING时没有活动lease也能按原成员准入受理；错owner或另一个来源不能占用已有child。 */
    @Test void planChildAdmissionAllowsWaitingParentAndRejectsConflictingOriginalSources() throws Exception {
        var fixture = store(); var run = planReady(fixture,"plan-child-waiting"); persistOriginalVerification(actor(run));
        var model = new ActionRef(TENANT,"plan-child-waiting-model"); var context = material("plan-child-waiting-context");
        var waiting = fixture.adoptPlan(new PlanTransition(run.identity().runRef(),run.revision(),run.lease().orElseThrow(),model,material("plan-child-waiting-request")),
                (session,current,saved) -> new PlanChange(material("plan-child-waiting-result"),new Progress(1,0,0,context.reference()),context,List.of(),
                        Optional.of(new WaitDeclaration(new WaitRef(model,1),WaitKind.PLAN,Map.of(),Optional.of(new PlanWait("plan-child-work",1,"plan-child-business-wait")))))).run();
        assertEquals(Phase.WAITING,waiting.phase()); assertTrue(waiting.lease().isEmpty());
        var child = childAdmission(waiting,"plan-child-waiting-member"); var origin = persistPlanChildSource(waiting,child,1,Instant.now().plusSeconds(120));
        var accepted = fixture.admitPlanChild(new PlanChildAdmission(waiting.identity().runRef(),origin,child),(session,current,source,candidate,existing) -> {
            assertEquals(waiting,current); assertFalse(existing);
        }); assertTrue(accepted.created()); assertEquals(waiting,fixture.load(waiting.identity().runRef()).orElseThrow());
        var changedBinding = new com.skillforge.runtime.api.dag.DagWork.Binding("different-admission",origin.binding().planRevision(),origin.binding().task(),origin.binding().runRef(),origin.binding().inputs());
        assertEquals("execution_plan_child_source_conflict",assertThrows(Rejection.class,() -> fixture.admitPlanChild(new PlanChildAdmission(waiting.identity().runRef(),
                new PlanChildSource(origin.work(),changedBinding,origin.permit()),child),(session,current,source,candidate,existing) -> fail("conflicting source must not invoke domain guard"))).reason());
        var stranger = SessionOwner.user(new SessionRef(TENANT,"other-plan-session"),new AgentId("agent"),new EndUserId(1));
        var wrongOwner = new PlanChildSource(new com.skillforge.runtime.api.dag.DagWork.Key(stranger,origin.work().rootInputId()),origin.binding(),origin.permit());
        assertEquals("execution_plan_child_owner_mismatch",assertThrows(Rejection.class,() -> fixture.admitPlanChild(new PlanChildAdmission(waiting.identity().runRef(),wrongOwner,child),
                (session,current,source,candidate,existing) -> fail("wrong owner must not invoke domain guard"))).reason());
    }

    /** 只观察真实连接的获取、提交和回滚，不替代SQL、RLS或事务实现。 */
    private static javax.sql.DataSource observedDataSource(TransactionCounts calls) {
        var loader = JdbcExecutionStoreTest.class.getClassLoader();
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, arguments) -> {
                    try {
                        var result = method.invoke(source, arguments);
                        if (!method.getName().equals("getConnection")) return result;
                        calls.connections++;
                        var connection = (java.sql.Connection) result;
                        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{java.sql.Connection.class},
                                (ignored, operation, parameters) -> {
                                    if (operation.getName().equals("commit")) calls.commits++;
                                    if (operation.getName().equals("rollback")) calls.rollbacks++;
                                    if (operation.getName().equals("prepareStatement"))
                                        calls.sql.add(((String) parameters[0]).replaceAll("\\s+", " ").trim());
                                    try { return operation.invoke(connection, parameters); }
                                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    /** 每次业务操作独立核对连接数与真实提交/回滚次数。 */
    private static final class TransactionCounts {
        int connections; int commits; int rollbacks;
        final List<String> sql = new java.util.ArrayList<>();
        void reset() { connections = 0; commits = 0; rollbacks = 0; sql.clear(); }
        void assertSingleTransaction(int expectedCommits, int expectedRollbacks) {
            assertEquals(1, connections); assertEquals(expectedCommits, commits); assertEquals(expectedRollbacks, rollbacks);
        }
    }

    /** 授权夹具核对实际事务租户；该放行只证明存储功能，不证明业务IAM。 */
    private static JdbcExecutionStore store() {
        return new JdbcExecutionStore(source, identities, (session, run, actor, prepared) -> {
            try (var query = session.getConnection().createStatement(); var rows = query.executeQuery("SELECT current_setting('app.tenant_id',true)")) {
                assertTrue(rows.next()); assertEquals(run.identity().runRef().tenantId().value(), rows.getString(1)); return true;
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        });
    }
    private static Dispatch withParticipantEvent(Dispatch command, Event event) {
        return new Dispatch(command.run(), command.revision(), command.lease(), command.action(), command.attemptId(),
                command.budget(), command.actor(), command.continuation(), List.of(event));
    }
    /** 借用事务的真实领域写入；引用随原来源身份稳定，不模拟产品Outbox或Webhook。 */
    private static ResultRef participantEffect(SavedEvent event) {
        return new ResultRef("fixture-event-participant:" + event.event().id().value());
    }
    private static AcceptInput acceptInput(Run run, InputId input, Material context, List<Event> events) {
        var progress = run.progress();
        return new AcceptInput(run.identity().runRef(), input, run.revision(), run.lease().orElseThrow(),
                new Progress(progress.decisions(), progress.toolCalls(), progress.knowledgeCalls(), context.reference()), context, events);
    }
    private static Run planReady(JdbcExecutionStore store, String id) {
        var base = admission(id,id+"-session",null,CAPACITY); var run = claim(store,store.admit(base).run());
        var registered = assertInstanceOf(DispatchResult.Registered.class,store.dispatch(dispatch(run,id+"-model",1,100)));
        return store.complete(completion(registered.run(),registered.attempt().id(),ResultKind.SUCCEEDED,id+"-model-result",id+"-model-context",id+"-model-done")).run();
    }
    private static PlanChildSource planChildSource(Run parent,Admission child,long fence,Instant expires) {
        var task = new com.skillforge.runtime.api.dag.DagPlan.Task("task:"+child.run().runRef().runId(),"成员任务",child.run().agentId(),
                com.skillforge.runtime.api.dag.DagPlan.ExecutorType.MEMBER,"完成获准任务",List.of(),Set.of("result"),Set.of());
        var binding = new com.skillforge.runtime.api.dag.DagWork.Binding("admission:"+child.run().runRef().runId(),1,task,child.run().runRef(),Map.of());
        return new PlanChildSource(new com.skillforge.runtime.api.dag.DagWork.Key(parent.identity().owner(),parent.identity().causationInputId()),binding,
                new com.skillforge.runtime.api.dag.DagWork.Permit(fence,expires));
    }
    /** 用保留DAG正式端口先保存获批work、不可变admission和RESERVED许可，再进入typed child受理。 */
    private static PlanChildSource persistPlanChildSource(Run parent,Admission child,long fence,Instant expires) {
        var origin = planChildSource(parent,child,fence,expires.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        var plan = new DagPlan(origin.binding().planRevision(),true,List.of(origin.binding().task()));
        var request = new DagWork.Request("执行已批准成员任务",Optional.of(parent.identity().agentId()),parent.identity().runRef(),"");
        var initial = new DagWork(origin.work(),parent.identity().agentId(),1,DagWork.Status.ACTIVE,plan,0,0,Map.of(),Map.of(),request);
        var codec = new PlanWorkCodec(); var port = new JdbcDagWorkPort(source,codec);
        String command = "fixture-plan:"+child.run().runRef().runId();
        assertTrue(port.create(initial,command+":create",command+":create"));
        var reserved = new DagWork.Execution(origin.binding(),DagWork.TaskStatus.RESERVED,Optional.of(origin.permit()),false,0,Map.of(),"",Optional.empty());
        var admitted = new DagWork(initial.key(),initial.coordinator(),2,DagWork.Status.ACTIVE,plan,plan.revision(),0,
                Map.of(origin.binding().task().id(),reserved),Map.of(),request);
        assertEquals(DagWorkPort.Commit.APPLIED,port.commit(initial.key(),initial.revision(),command+":admit",command+":admit",admitted,
                List.of(new DagWorkPort.Event("task.admitted",origin.binding().task().id(),"成员已获准，等待typed Run受理"))));
        assertEquals(admitted,new JdbcDagWorkPort(source,codec).read(origin.work()).orElseThrow());
        return origin;
    }
    /** 与宿主一致的JSON值编解码；重建DAG适配器不依赖进程内工作快照。 */
    private static final class PlanWorkCodec implements JdbcDagWorkPort.Codec {
        private final com.fasterxml.jackson.databind.ObjectMapper json = SessionSubjectJsonFixture.configure(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        @Override public String encode(DagWork work) { return encodeValue(work); }
        @Override public DagWork decode(String payload) {
            try { return json.readValue(payload,DagWork.class); }
            catch(java.io.IOException failure) { throw new IllegalArgumentException(failure); }
        }
        @Override public String encodeBinding(DagWork.Binding binding) { return encodeValue(binding); }
        private String encodeValue(Object value) {
            try { return json.writeValueAsString(value); }
            catch(java.io.IOException failure) { throw new IllegalArgumentException(failure); }
        }
    }
    /** 缺原准入负控必须命中正式外键，其他SQL/权限错误不能冒充有效拒绝。 */
    private static void assertForeignKeyViolation(Throwable failure,String constraint) {
        Throwable cause=failure;
        while(cause!=null && !(cause instanceof java.sql.SQLException))cause=cause.getCause();
        var sql=assertInstanceOf(org.postgresql.util.PSQLException.class,cause);
        assertEquals("23503",sql.getSQLState());
        assertNotNull(sql.getServerErrorMessage()); assertEquals(constraint,sql.getServerErrorMessage().getConstraint());
    }
    /** 用同session的可观察领域副作用证明Store拥有提交和回滚，不替代生产DAG/SessionLog端口验收。 */
    private static void insertPlanBusinessEffect(org.apache.ibatis.session.SqlSession session, String reference) {
        try (var insert = session.getConnection().prepareStatement("INSERT INTO agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES(?,?,'fixture.plan','{}')")) {
            insert.setString(1,TENANT.value()); insert.setString(2,reference); assertEquals(1,insert.executeUpdate());
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }
    /** 故意允许绑定其他租户而仍查询tenant 1，证明缺行来自真实RLS，不是WHERE条件排除了目标。 */
    private static long sessionCount(String table,String session,TenantId scope) throws Exception {
        if(!Set.of("agent_runtime_session_owners","agent_runtime_execution_sessions","agent_runtime_execution_events",
                "agent_runtime_session_events","agent_runtime_session_streams","agent_runtime_sessions").contains(table))throw new IllegalArgumentException("fixture_table_invalid");
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) { bind.setString(1,scope.value());bind.execute(); }
            try(var query=connection.prepareStatement("SELECT count(*) FROM public."+table+" WHERE tenant_id=? AND session_id=?")) {
                query.setString(1,TENANT.value());query.setString(2,session);
                try(var rows=query.executeQuery()) { assertTrue(rows.next());return rows.getLong(1); }
            } finally { connection.rollback(); }
        }
    }
    /** 测试宿主保存不可变原验证全值，当前权限由同事务授权入口单独核验。 */
    private static void persistOriginalVerification(VerifiedIdentity identity) {
        identities.register(identity);
    }

    /** 测试宿主拥有来源定义，不依赖平台认证表或平台快照编码。 */
    private static void persistOriginalVerification(VerifiedIdentity identity, String channel, String sourceName) {
        identities.register(identity, channel, sourceName);
    }
    /** 构造入口已核实的固定身份与不可变输入材料。 */
    private static Admission admission(String id, String session, RunRef budgetRoot, ExecutionBudgetPort.Resources capacity) {
        var owner = SessionOwner.user(new SessionRef(TENANT, session), new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input-" + id); var run = new RunRef(TENANT, id);
        var verified = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT, "test-worker"), owner, Set.of("sessions.write"), 1, new ResultRef("verified-" + id));
        identities.register(verified);
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, run, new TraceEnvelope("trace-" + id, "span-" + id, input, Optional.empty()), verified.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        return new Admission(verified, identity, "fingerprint-" + id, new ResultRef("config-" + id), material("input-" + id),
                material("context-" + id), registration(run, budgetRoot == null ? run : budgetRoot, capacity), List.of(event("accepted-" + id)), List.of());
    }
    /** 固定同根预算配置，不另建余额。 */
    private static ExecutionBudgetPort.Registration registration(RunRef run, RunRef root, ExecutionBudgetPort.Resources capacity) {
        return new ExecutionBudgetPort.Registration(run, root, run.equals(root) ? ExecutionBudgetPort.Role.PRIMARY : ExecutionBudgetPort.Role.MEMBER, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.TEAM, capacity, capacity, capacity, 3_600, 3_600, "typed-test:v1")));
    }
    /** 已有槽位后领取唯一Run。 */
    private static Run claim(ExecutionStore store, Run run) {
        return store.claim(new Claim(run.identity().runRef(), "worker", "claim-" + run.identity().runRef().runId(), LEASE)).orElseThrow();
    }
    /** 准备请求与预算需求使用同一个Attempt身份和指纹。 */
    private static Dispatch dispatch(Run run, String id, long ordinal, long inputTokens) {
        var reference = run.identity().runRef(); var fingerprint = "request-" + id;
        var prepared = new Prepared(new ActionRef(reference.tenantId(), id), ordinal, Kind.MODEL, "frozen-model", new ResultRef("capability"), material(id + "-request"), fingerprint);
        return new Dispatch(reference, run.revision(), run.lease().orElseThrow(), prepared, id + "-attempt", new ExecutionBudgetPort.Demand(reference,
                id + "-attempt", fingerprint, ExecutionBudgetPort.Category.BUSINESS_MODEL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL, inputTokens, 20), false), actor(run), Optional.empty(), List.of());
    }
    /** 存储夹具的已加载主体；正式IAM由独立事务授权测试覆盖。 */
    private static VerifiedIdentity actor(Run run) {
        return new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT, "test-worker"), run.identity().owner(),
                Set.of("sessions.write"), 1, run.actorVerificationRef());
    }
    /** 生成安全反馈与下一上下文；UNKNOWN不报告推测用量。 */
    private static Completion completion(Run run, String attempt, ResultKind kind, String result, String context, String event) {
        var nextContext = material(context);
        return new Completion(run.identity().runRef(), attempt, kind, material(result), Optional.of(material(result + "-feedback")), Optional.empty(),
                kind == ResultKind.UNKNOWN ? ExecutionBudgetPort.Usage.unreported() : ExecutionBudgetPort.Usage.reported(java.util.OptionalLong.of(80), java.util.OptionalLong.of(10)),
                run.revision(), run.lease().orElseThrow(), new Progress(1, 0, 0, nextContext.reference()), nextContext, List.of(event(event)), Optional.empty());
    }
    /** 原逻辑工具动作的每次物理调用独立请求与预算身份。 */
    private static Dispatch toolDispatch(Run run, ActionRef action, String attempt, Optional<WaitRef> continuation) {
        var request = material(attempt + "-request"); var fingerprint = "fingerprint-" + attempt;
        var prepared = new Prepared(action, 1, Kind.TOOL, "frozen-tool", new ResultRef("tool-capability"), request, fingerprint);
        return new Dispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), prepared, attempt,
                new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt, fingerprint, ExecutionBudgetPort.Category.TOOL,
                        ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false), actor(run), continuation, List.of());
    }
    /** 原INPUT_REQUIRED结果与精确等待声明共同提交；不提前计逻辑工具完成。 */
    private static Completion inputRequired(Run run, String attempt, WaitRef waiting, Map<String, ToolPortal.ToolInputMethod> methods) {
        var context = material(attempt + "-wait-context");
        return new Completion(run.identity().runRef(), attempt, ResultKind.INPUT_REQUIRED, material(attempt + "-input-required"),
                Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported(), run.revision(), run.lease().orElseThrow(),
                new Progress(1, 0, 0, context.reference()), context, List.of(event(attempt + "-waiting")),
                Optional.of(new WaitDeclaration(waiting, WaitKind.TOOL, methods, Optional.empty())));
    }
    /** AskUser等待属于真实成功模型结果，结果分类与原selected Attempt不改为工具等待。 */
    private static Completion humanRequired(Run run, String attempt, WaitRef waiting) {
        var context = material(attempt + "-ask-user-context");
        return new Completion(run.identity().runRef(), attempt, ResultKind.SUCCEEDED, material(attempt + "-model-succeeded"),
                Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.reported(java.util.OptionalLong.of(80), java.util.OptionalLong.of(10)),
                run.revision(), run.lease().orElseThrow(), new Progress(1, 0, 0, context.reference()), context,
                List.of(event(attempt + "-ask-user")), Optional.of(new WaitDeclaration(waiting, WaitKind.HUMAN, Map.of(), Optional.empty())));
    }
    private static Dispatch domainDispatch(Run run, String id, Kind kind) {
        var fingerprint = "fingerprint-" + id;
        var prepared = new Prepared(new ActionRef(TENANT, id), 1, kind, "frozen-" + kind.name().toLowerCase(), new ResultRef("domain-capability"), material(id + "-request"), fingerprint);
        return new Dispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), prepared, id + "-attempt",
                new ExecutionBudgetPort.Demand(run.identity().runRef(), id + "-attempt", fingerprint, ExecutionBudgetPort.Category.TOOL,
                        ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false), actor(run), Optional.empty(), List.of());
    }
    private static Admission childAdmission(Run parent, String id) {
        var owner = SessionOwner.user(new SessionRef(TENANT, "child-session:" + id), new AgentId("child-agent"), parent.identity().owner().requireUser());
        var run = new RunRef(TENANT, id); var input = new InputId("input-" + id);
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT, "child-fixture"), owner, Set.of("sessions.write"), 1, new ResultRef("verified-" + id));
        var child = new RunIdentity(owner, new AgentId("child-agent"), input, run, new TraceEnvelope(parent.identity().trace().traceId(), "child-span-" + id, parent.identity().trace().rootInputId(), Optional.empty()), identity.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        return new Admission(identity, child, "fingerprint-" + id, new ResultRef("configuration-" + id), material("input-" + id), material("context-" + id),
                registration(run, parent.identity().runRef(), CAPACITY), List.of(event("accepted-" + id)), List.of(material("execution-capabilities:" + id)));
    }
    /** 原模型结果已保存并采纳后，才可用其Attempt锚定并行tool_calls。 */
    private static Run batchReady(JdbcExecutionStore store, String id, ExecutionBudgetPort.Resources capacity) {
        var admission = admission(id, id + "-session", null, capacity); var run = claim(store, store.admit(admission).run());
        var dispatch = dispatch(run, id + "-model", 1, 100);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        return store.complete(completion(registered.run(), registered.attempt().id(), ResultKind.SUCCEEDED,
                id + "-model-result", id + "-model-context", id + "-model-done")).run();
    }
    private static BatchDispatch batchDispatch(Run run, String id) {
        var requests = new java.util.ArrayList<BatchMember>();
        for (int index = 1; index <= 2; index++) {
            var key = id + "-tool-" + index; var fingerprint = "fingerprint-" + key;
            var prepared = new Prepared(new ActionRef(TENANT, key), index + 1, Kind.TOOL, "frozen-tool-" + index,
                    new ResultRef("tool-capability"), material(key + "-request"), fingerprint);
            requests.add(new BatchMember.Fresh(index,new BatchRequest(prepared, key + "-attempt", new ExecutionBudgetPort.Demand(run.identity().runRef(),
                    key + "-attempt", fingerprint, ExecutionBudgetPort.Category.TOOL,
                    ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false))));
        }
        return new BatchDispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), id + "-model-attempt", requests, actor(run));
    }
    /** 指定native槽位与fresh连续Action序号，不按复用槽位留下逻辑序号洞。 */
    private static BatchMember.Fresh batchFresh(Run run,String id,int slot,long ordinal) {
        var fingerprint="fingerprint-"+id;var prepared=new Prepared(new ActionRef(TENANT,id),ordinal,Kind.TOOL,"tool-"+id,new ResultRef("tool-capability"),material(id+"-request"),fingerprint);
        return new BatchMember.Fresh(slot,new BatchRequest(prepared,id+"-attempt",new ExecutionBudgetPort.Demand(run.identity().runRef(),id+"-attempt",fingerprint,ExecutionBudgetPort.Category.TOOL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0),false)));
    }
    private static BatchContinuation batchContinuation(Run run,BatchEntry entry,WaitRef waiting,String id) {
        var action=entry.action();var fingerprint="fingerprint-"+id;
        var prepared=new Prepared(action.reference(),action.ordinal(),action.kind(),action.target(),action.capability(),material(id+"-request"),fingerprint);
        return new BatchContinuation(entry.slot().slot(),waiting,prepared,id,new ExecutionBudgetPort.Demand(run.identity().runRef(),id,fingerprint,ExecutionBudgetPort.Category.TOOL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0),false));
    }
    private static Completion withUsage(Completion value, long input, long output) {
        return new Completion(value.run(), value.attemptId(), value.kind(), value.result(), value.feedback(), value.externalOperation(),
                ExecutionBudgetPort.Usage.reported(java.util.OptionalLong.of(input), java.util.OptionalLong.of(output)),
                value.revision(), value.lease(), value.next(), value.context(), value.events(), value.waitDeclaration());
    }
    private static Completion batchResult(Run run, String attempt, String id) {
        var context = material(id + "-unused-context");
        return new Completion(run.identity().runRef(), attempt, ResultKind.SUCCEEDED, material(id + "-result"), Optional.of(material(id + "-feedback")),
                Optional.empty(), ExecutionBudgetPort.Usage.unreported(), run.revision(), run.lease().orElseThrow(),
                new Progress(run.progress().decisions(), run.progress().toolCalls(), run.progress().knowledgeCalls(), context.reference()),
                context, List.of(event(id + "-actual-result")), Optional.empty());
    }
    private static BatchAdoption batchAdoption(BatchDispatchResult.Registered registered, ResultRef first, ResultRef second, String id) {
        var context = material(id + "-batch-context");
        return new BatchAdoption(List.of(new BatchResultRef(registered.entries().get(0).action().reference(), registered.entries().get(0).attempt().id(), first),
                new BatchResultRef(registered.entries().get(1).action().reference(), registered.entries().get(1).attempt().id(), second)),
                new Progress(1, 2, 0, context.reference()), context, List.of(event(id + "-batch-done")));
    }
    /** 固定小正文材料。 */
    private static Material material(String id) { return new Material(new ResultRef(id), payload(id)); }
    /** 协议正文由调用方提供，存储不反射编码运行对象。 */
    private static TypedPayload payload(String value) { return new TypedPayload("test.execution.v1", "{\"value\":\"" + value + "\"}"); }
    /** 已转换为安全展示内容的事件。 */
    private static Event event(String id) { return new Event(new EventId(id), "test", payload(id)); }
    /** 隔离测试中直接制造旧租约到期，避免依赖sleep构造竞争时序。 */
    private static void expire(RunRef run) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("UPDATE agent_runtime_execution_runs SET lease_until_second=0,lease_until_nano=0 WHERE tenant_id=? AND run_id=?")) {
            statement.setString(1, run.tenantId().value()); statement.setString(2, run.runId()); assertEquals(1, statement.executeUpdate());
        }
    }
}
