package com.skillforge.runtime.adapter.jdbc.callback;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import static org.junit.jupiter.api.Assertions.*;
import static com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 PostgreSQL 的 callback 注册、投递状态、租约与原准入事实验收。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcCallbackRegistrationPortTest {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();
    private static final String ROLE = "callback_registration_test";
    private static final String PASSWORD = "callback_registration_test_password";
    private static final TenantId TENANT = new TenantId("callback-tenant");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(1));
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("callback_admin").withPassword("callback_admin_password");
    private static final List<String> tables = List.of("agent_runtime_callback_registrations", "agent_runtime_callback_registration_operations",
            "agent_runtime_callback_deliveries", "agent_runtime_callback_delivery_operations", "agent_runtime_callback_audits");
    private Fixture fixture;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        execute("GRANT DELETE ON public.agent_runtime_dag_permits TO " + ROLE);
        execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + ROLE);
    }

    @BeforeEach
    void reset() {
        // 多态scope余额没有根表FK；同一Run夹具重建前必须显式清空，保留生产七维完整校验。
        execute("TRUNCATE " + String.join(",", tables)
                + ",agent_runtime_execution_sessions,agent_runtime_execution_materials,agent_runtime_budget_roots,agent_runtime_budget_balances CASCADE");
        fixture = new Fixture();
    }

    @Test
    void registrationValueReplayAndCurrentAuthorizationSurviveRebuild() {
        var f = fixture;
        assertInstanceOf(CallbackRegisterResult.Registered.class, f.port.register(f.registration));
        assertInstanceOf(CallbackRegisterResult.Replayed.class, f.port().register(f.registration));
        var command = f.registration;
        var changed = new RegisterCallback(command.registration(), command.requester(), command.owningRun(), command.causationInputId(),
                command.trace(), command.causationIdentityRef(), new IdempotencyKey("other-key"), command.requestFingerprint());
        var conflict = assertInstanceOf(CallbackRegisterResult.Conflict.class, f.port.register(changed));
        assertTrue(auditExists(conflict.auditRef()));
        f.allowRegister = false;
        assertInstanceOf(CallbackRegisterResult.Rejected.class, f.port().register(command));
    }

    @Test
    void exactDeliveryReadPreservesVerifiedOriginalEnvelopeAndTenant() {
        var f=fixture;f.register();assertInstanceOf(CallbackDeliveryResult.Ready.class,f.port.acceptVerified(f.delivery));
        var receipt=f.port().delivery(f.delivery.deliveryRef()).orElseThrow();assertEquals(Optional.of(f.envelope),receipt.envelope());
        assertTrue(f.port.delivery(new CallbackDeliveryRef(new TenantId("foreign-callback-tenant"),f.delivery.deliveryRef().deliveryId())).isEmpty());
        assertEquals(CallbackDeliveryStatus.READY,receipt.status());assertEquals(1,receipt.ledgerRevision());
    }

    @Test
    void suspendedAndRevokedRegistrationsRejectIngressWithoutDeletingEarlierReadyDelivery() {
        var f = fixture; f.register();
        var ready = assertInstanceOf(CallbackDeliveryResult.Ready.class, f.port.acceptVerified(f.delivery)).receipt();
        var suspend = f.status(1, CallbackRegistrationStatus.SUSPENDED, "suspend");
        assertInstanceOf(CallbackStatusResult.Applied.class, f.port.changeStatus(suspend));
        assertInstanceOf(CallbackStatusResult.Replayed.class, f.port().changeStatus(suspend));
        f.allowStatus = false;
        assertInstanceOf(CallbackStatusResult.Rejected.class, f.port.changeStatus(suspend));
        f.allowStatus = true;
        var incoming = new VerifiedCallbackDelivery(f.delivery.callbackRef(), 2, new CallbackDeliveryRef(TENANT, "rejected"),
                "rejected-fp", f.envelope, f.time);
        var rejected = assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(incoming)).receipt();
        assertTrue(rejected.envelope().isEmpty()); assertTrue(auditExists(rejected.auditRef().orElseThrow()));
        assertEquals(rejected, assertInstanceOf(CallbackDeliveryResult.Replayed.class, f.port().acceptVerified(incoming)).receipt());
        assertEquals(ready, assertInstanceOf(CallbackDeliveryResult.Replayed.class, f.port.acceptVerified(f.delivery)).receipt());
        assertInstanceOf(CallbackStatusResult.Applied.class, f.port.changeStatus(f.status(2, CallbackRegistrationStatus.REVOKED, "revoke")));
        assertInstanceOf(CallbackStatusResult.Rejected.class, f.port.changeStatus(f.status(3, CallbackRegistrationStatus.ACTIVE, "resurrect")));
        assertEquals(1, f.port.scanReady(TENANT, 10).size());
        assertEquals(f.envelope, f.claim().receipt().envelope().orElseThrow());
    }

    @Test
    void rejectedIngressCannotReachWorkerAndFullCommandDriftConflicts() {
        var f = fixture; f.register(); f.allowIngress = false;
        var rejected = assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(f.delivery)).receipt();
        assertTrue(rejected.envelope().isEmpty());
        assertInstanceOf(CallbackDeliveryResult.Conflict.class, f.port.acceptVerified(new VerifiedCallbackDelivery(
                f.delivery.callbackRef(), 1, f.delivery.deliveryRef(), "changed", f.envelope, f.time)));
        assertInstanceOf(CallbackClaimResult.Empty.class, f.port().claimReady(f.request()));
        assertTrue(f.port.scanReady(TENANT, 10).isEmpty());
        assertEquals(1, scalar("SELECT count(*) FROM agent_runtime_callback_deliveries WHERE status='REJECTED' "
                + "AND envelope_data IS NULL AND length(command_digest)=64"));
    }

    @Test
    void retryPreservesOriginalEnvelopeAndAReclaimedLeaseFencesThePreviousWorker() {
        var f = fixture; f.register(); f.port.acceptVerified(f.delivery); var first = f.claim();
        Instant next = now().plusMillis(500);
        var retry = new CallbackRetry(first.lease(), next, new PortFailure("retry", RetryMode.IDEMPOTENT_BY_STABLE_KEY, new ResultRef("diagnostic")),
                new RequestId("retry"), "retry-fp");
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port.retry(retry));
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port().retry(retry));
        assertInstanceOf(CallbackClaimResult.Empty.class, f.port.claimReady(f.request()));
        awaitTime(next.plusMillis(10)); var second = f.claim();
        assertTrue(second.lease().fence().fencingToken() > first.lease().fence().fencingToken());
        assertEquals(f.envelope, second.receipt().envelope().orElseThrow());
        assertInstanceOf(CallbackMutationResult.Conflict.class, f.port.retry(retry));
        assertInstanceOf(CallbackMutationResult.StaleLease.class, f.port.renew(
                new CallbackRenew(first.lease(), Duration.ofSeconds(1), new RequestId("stale-renew"), "stale-renew-fp")));
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port.renew(
                new CallbackRenew(second.lease(), Duration.ofSeconds(1), new RequestId("current-renew"), "current-renew-fp")));
        assertEquals(CallbackDeliveryStatus.PROCESSING,
                f.port.delivery(f.delivery.deliveryRef()).orElseThrow().status());
    }

    @Test
    void expiredRenewalCannotRestoreLeaseAndDeadLetterKeepsOriginalEnvelope() {
        var f = fixture; f.register(); f.port.acceptVerified(f.delivery);
        var first = assertInstanceOf(CallbackClaimResult.Claimed.class, f.port.claimReady(new CallbackClaimRequest(TENANT, "worker", 1, Duration.ofMillis(500)))).claims().getFirst();
        var renew = new CallbackRenew(first.lease(), Duration.ofMillis(500), new RequestId("renew"), "renew-fp");
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port.renew(renew));
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port().renew(renew));
        awaitTime(first.lease().fence().leaseUntil().plusMillis(600));
        var second = f.claim();
        assertInstanceOf(CallbackMutationResult.Conflict.class, f.port.renew(renew));
        var diagnostic = new ResultRef("permanent-audit");
        var dead = new CallbackDeadLetter(second.lease(), new PortFailure("permanent", RetryMode.NONE, diagnostic), new RequestId("dead"), "dead-fp");
        var receipt = assertInstanceOf(CallbackMutationResult.Applied.class, f.port.deadLetter(dead)).current();
        assertEquals(CallbackDeliveryStatus.DEAD_LETTER, receipt.status());
        assertEquals(Optional.of(f.envelope), receipt.envelope()); assertEquals(Optional.of(diagnostic), receipt.auditRef());
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port().deadLetter(dead));
    }

    @Test
    void concurrentAdaptersHaveSingleClaimWinner() throws Exception {
        var f = fixture; f.register(); f.port.acceptVerified(f.delivery); var other = f.port();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return f.port.claimReady(f.request()); });
            var second = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return other.claimReady(f.request()); });
            try {
                start.countDown();
                List<CallbackClaimResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(CallbackClaimResult.Claimed.class::isInstance).count());
                assertEquals(1, results.stream().filter(CallbackClaimResult.Empty.class::isInstance).count());
            } finally { start.countDown(); executor.shutdownNow(); }
        }
    }

    @Test
    void operationInsertFailureRollsBackDeliveryAndOriginalLeaseRemainsUsable() {
        var f = fixture; f.register(); f.port.acceptVerified(f.delivery); var claim = f.claim();
        var command = new CallbackDeadLetter(claim.lease(),
                new PortFailure("permanent", RetryMode.NONE, new ResultRef("operation-rollback-audit")),
                new RequestId("dead-operation"), "dead-operation-fp");
        execute("CREATE FUNCTION callback_test_fail_operation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected_operation_failure'; END $$");
        execute("CREATE TRIGGER callback_test_fail_operation BEFORE INSERT ON agent_runtime_callback_delivery_operations FOR EACH ROW EXECUTE FUNCTION callback_test_fail_operation()");
        try {
            assertThrows(RuntimeException.class, () -> f.port.deadLetter(command));
            assertEquals(0, scalar("SELECT count(*) FROM agent_runtime_callback_delivery_operations"));
            assertEquals(2, scalar("SELECT revision FROM agent_runtime_callback_deliveries"));
            assertEquals(1, scalar("SELECT count(*) FROM agent_runtime_callback_deliveries WHERE status='PROCESSING' AND lease_token IS NOT NULL"));
        } finally {
            execute("DROP TRIGGER callback_test_fail_operation ON agent_runtime_callback_delivery_operations");
            execute("DROP FUNCTION callback_test_fail_operation()");
        }
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port().deadLetter(command));
    }

    @Test
    void restrictedRoleSeparatesCallbackFactsAndAuditsByTenant() throws Exception {
        var f = fixture; f.register(); f.port.acceptVerified(f.delivery);
        f.port.deadLetter(new CallbackDeadLetter(f.claim().lease(),
                new PortFailure("permanent", RetryMode.NONE, new ResultRef("rls-audit")),
                new RequestId("dead-rls"), "dead-rls-fp"));
        try (var connection = dataSource().getConnection(); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(row.next()); assertFalse(row.getBoolean(1)); assertFalse(row.getBoolean(2));
            }
            for (String tenant : List.of("", "foreign", TENANT.value())) {
                try (var setting = connection.prepareStatement("SELECT set_config('app.tenant_id',?,false)")) { setting.setString(1, tenant); setting.execute(); }
                for (String table : tables) {
                    long expected = tenant.equals(TENANT.value()) ? scalar("SELECT count(*) FROM " + table) : 0;
                    try (var row = statement.executeQuery("SELECT count(*) FROM " + table)) { assertTrue(row.next()); assertEquals(expected, row.getLong(1), table); }
                }
            }
        }
        assertEquals(tables.size(), scalar("SELECT count(*) FROM pg_class WHERE relname IN ("
                + tables.stream().map(table -> "'" + table + "'").collect(java.util.stream.Collectors.joining(",")) + ") AND relrowsecurity AND relforcerowsecurity"));
    }

    /** 借用事务的确认不自行提交；caller回滚后仍可按原真实事实再次确认。 */
    @Test void workflowConfirmationUsesCallerTransactionAndPreservesExactDeliveryOnRollback() throws Exception {
        var f = new WorkflowFixture(); f.accept(); persistWorkflowProof(f.delivery);
        var expected = f.port.find(f.draft.callbackRef()).orElseThrow();
        var original = f.port.delivery(f.delivery.deliveryRef()).orElseThrow();
        assertEquals(CallbackDeliveryStatus.READY,original.status()); assertEquals(Optional.of(f.envelope),original.envelope());
        assertEquals(original,f.port.delivery(f.delivery.deliveryRef()).orElseThrow());
        assertTrue(f.port.delivery(new CallbackDeliveryRef(new TenantId("foreign-callback-tenant"),f.delivery.deliveryRef().deliveryId())).isEmpty());
        try (var session = workflowSession(TENANT)) {
            f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,f.envelope);
            var row = session.getMapper(CallbackMapper.class).workflowDelivery(f.delivery.deliveryRef());
            assertEquals("DELIVERED",row.status); assertEquals(2,row.revision); assertEquals(1,row.attempt); assertEquals(f.envelope,row.restore().envelope().orElseThrow());
            assertEquals(1,f.port.scanReady(TENANT,10).size(),"uncommitted delivery must remain READY to another transaction");
            session.rollback(true);
        }
        assertEquals(1,f.port.scanReady(TENANT,10).size());
        assertTrue(f.confirm().accepted());
        assertTrue(f.port.scanReady(TENANT,10).isEmpty());
        assertEquals(CallbackDeliveryStatus.DELIVERED,f.port.delivery(f.delivery.deliveryRef()).orElseThrow().status());
        try (var session = workflowSession(TENANT)) {
            f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,f.envelope);
            var row = session.getMapper(CallbackMapper.class).workflowDelivery(f.delivery.deliveryRef());
            assertEquals(2,row.revision); assertEquals("DELIVERED",row.status); assertEquals(f.envelope,row.restore().envelope().orElseThrow());
            session.commit(true);
        }
        assertEquals(0,scalar("SELECT count(*) FROM agent_runtime_callback_delivery_operations"));
    }

    /** 已收录PROCESSING投递可由真正Workflow业务守卫原子确认，不依赖旧InputReceipt。 */
    @Test void workflowConfirmationClosesProcessingLeaseWithoutAnotherInboxReceipt() throws Exception {
        var f = new WorkflowFixture(); f.accept(); persistWorkflowProof(f.delivery);
        var expected = f.port.find(f.draft.callbackRef()).orElseThrow();
        var claim = assertInstanceOf(CallbackClaimResult.Claimed.class,f.port.claimReady(new CallbackClaimRequest(TENANT,"workflow-worker",1,Duration.ofSeconds(30)))).claims().getFirst();
        assertEquals(2,claim.receipt().ledgerRevision());
        assertTrue(f.confirm().accepted());
        try(var session=workflowSession(TENANT)) {
            var row=session.getMapper(CallbackMapper.class).workflowDelivery(f.delivery.deliveryRef());
            assertEquals("DELIVERED",row.status);assertEquals(3,row.revision);assertEquals(1,row.attempt);
            assertNull(row.leaseClaim);assertNull(row.leaseWorker);assertNull(row.leaseToken);assertNull(row.leaseUntil);
            assertEquals(f.envelope,row.restore().envelope().orElseThrow());session.rollback(true);
        }
        assertTrue(f.port.scanReady(TENANT,10).isEmpty());
        assertEquals(0,scalar("SELECT count(*) FROM agent_runtime_callback_delivery_operations"));
    }

    /** 仅标DELIVERED却没有真实typed接受响应，延迟约束必须拒绝整个提交。 */
    @Test void workflowConfirmationCannotCommitWithoutAcceptedTypedResponse() throws Exception {
        var f=new WorkflowFixture();f.accept();persistWorkflowProof(f.delivery);
        var expected=f.port.find(f.draft.callbackRef()).orElseThrow();
        try(var session=workflowSession(TENANT)) {
            f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,f.envelope);
            assertThrows(RuntimeException.class,()->session.commit(true));session.rollback(true);
        }
        assertEquals(CallbackDeliveryStatus.READY,f.port.delivery(f.delivery.deliveryRef()).orElseThrow().status());
        assertEquals(0,scalar("SELECT count(*) FROM agent_runtime_execution_workflow_callback_deliveries"));
        assertTrue(f.store.waiting(f.run,f.wait).orElseThrow().acceptedResponse().isEmpty());
    }

    /** 只有原始已验签proof与准确登记/envelope均匹配才能确认；拒绝不能改变投递事实。 */
    @Test void workflowConfirmationRejectsMissingProofAlteredEnvelopeTenantAndRevokedRegistration() throws Exception {
        var f = new WorkflowFixture(); f.accept(); var expected = f.port.find(f.draft.callbackRef()).orElseThrow();
        try (var session = workflowSession(TENANT)) {
            assertEquals("callback_workflow_proof_mismatch",assertThrows(Rejection.class,
                    () -> f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,f.envelope)).reason()); session.rollback(true);
        }
        persistWorkflowProof(f.delivery);
        var original = f.envelope;
        var changed = new InboundEnvelope(original.authenticationRef(), original.causationIdentityRef(), original.sessionRef(), original.sessionTarget(), original.agentId(), original.subject(), original.requester(), original.inputId(), original.eventId(), original.kind(), original.runTarget(), original.channel(), original.source(), original.trace(), new TypedPayload(original.payload().schemaId(),"{\"value\":\"changed\"}"), original.receivedAt(), original.sourceMessageRef(), original.originRef());
        try (var session = workflowSession(TENANT)) {
            assertEquals("callback_workflow_delivery_mismatch",assertThrows(Rejection.class,
                    () -> f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,changed)).reason()); session.rollback(true);
        }
        try (var session = workflowSession(new TenantId("other-callback-tenant"))) {
            assertEquals("callback_workflow_tenant_mismatch",assertThrows(Rejection.class,
                    () -> f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,original)).reason()); session.rollback(true);
        }
        var revoke = new ChangeCallbackStatus(f.draft.callbackRef(),f.requester,expected.revision(),CallbackRegistrationStatus.REVOKED,new InputId("workflow-revoke"),f.trace,new RequestId("workflow-revoke"),"workflow-revoke");
        assertInstanceOf(CallbackStatusResult.Applied.class,f.port.changeStatus(revoke));
        try (var session = workflowSession(TENANT)) {
            assertEquals("callback_workflow_registration_changed",assertThrows(Rejection.class,
                    () -> f.port.confirmWorkflowDelivery(session,f.delivery.deliveryRef(),expected,original)).reason()); session.rollback(true);
        }
        assertEquals(1,f.port.scanReady(TENANT,10).size()); assertEquals(1,scalar("SELECT revision FROM agent_runtime_callback_deliveries"));
    }

    /** 键集按实际扫描页尾推进，前页其他callback不会永久挡住后页Workflow，扫描本身不领取。 */
    @Test void callbackReadyKeysetAdvancesPastOtherTargetsAndIncludesOnlyExpiredProcessing() throws Exception {
        var f = fixture; f.register(); f.port.acceptVerified(f.delivery); var workflow = new WorkflowFixture(); workflow.accept();
        var first = f.port.scanReadyAfter(TENANT,"",1); assertEquals(1,first.size()); assertEquals("delivery",first.getFirst().deliveryRef().deliveryId());
        assertEquals(new ExecutionRef(TENANT,"callback-execution"),assertInstanceOf(CallbackTarget.ExecutionTarget.class,
                f.port.find(first.getFirst().callbackRef()).orElseThrow().draft().target()).executionRef());
        var next = f.port.scanReadyAfter(TENANT,first.getLast().deliveryRef().deliveryId(),1);
        assertEquals(1,next.size()); assertEquals(workflow.delivery.deliveryRef(),next.getFirst().deliveryRef());
        assertInstanceOf(CallbackTarget.ExecutionTarget.class,f.port.find(next.getFirst().callbackRef()).orElseThrow().draft().target());
        assertTrue(f.port.scanReadyAfter(TENANT,next.getLast().deliveryRef().deliveryId(),1).isEmpty());
        assertTrue(f.port.scanReadyAfter(new TenantId("other-callback-tenant"),"",100).isEmpty());
        assertEquals(1,f.port.delivery(f.delivery.deliveryRef()).orElseThrow().ledgerRevision());
        assertEquals(1,f.port.delivery(workflow.delivery.deliveryRef()).orElseThrow().ledgerRevision());
        assertThrows(IllegalArgumentException.class,() -> f.port.scanReadyAfter(TENANT,"",101));
        assertInstanceOf(CallbackClaimResult.Claimed.class,f.port.claimReady(new CallbackClaimRequest(TENANT,"scan-test-worker",10,Duration.ofMillis(200))));
        assertTrue(f.port.scanReadyAfter(TENANT,"",100).isEmpty());
        awaitTime(now().plusMillis(250));
        var expired = f.port.scanReadyAfter(TENANT,"",100); assertEquals(2,expired.size()); assertEquals(workflow.delivery.deliveryRef(),expired.getLast().deliveryRef());
        var original = f.port.delivery(workflow.delivery.deliveryRef()).orElseThrow(); assertEquals(CallbackDeliveryStatus.PROCESSING,original.status()); assertEquals(2,original.ledgerRevision());
        assertEquals(original,f.port.delivery(workflow.delivery.deliveryRef()).orElseThrow());
    }

    /** 同生产共享配置的默认Instant映射保持原样，只有callback专属resultMap解析numeric时间。 */
    private static org.apache.ibatis.session.SqlSession workflowSession(TenantId tenant) throws SQLException {
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("joined-workflow-test",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),dataSource()));
        configuration.setMapUnderscoreToCamelCase(true);
        var original = configuration.getTypeHandlerRegistry().getTypeHandler(Instant.class);
        JdbcCallbackRegistrationPort.configure(configuration); JdbcCallbackRegistrationPort.configure(configuration);
        assertSame(original,configuration.getTypeHandlerRegistry().getTypeHandler(Instant.class));
        var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession(false);
        try (var statement = session.getConnection().prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
            statement.setString(1,tenant.value()); statement.execute(); return session;
        } catch (SQLException failure) { session.close(); throw failure; }
    }

    /** 夹具只建立已经受信入口证明的持久摘要；真实HMAC/当前IAM由入口集成测试拥有。 */
    private static void persistWorkflowProof(VerifiedCallbackDelivery delivery) throws SQLException {
        try (var connection = admin(); var insert = connection.prepareStatement("INSERT INTO agent_runtime_callback_ingress_proofs(tenant_id,callback_id,delivery_id,fingerprint,authentication_ref,command_digest,registration_revision,verified_at) VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1,delivery.callbackRef().tenantId().value()); insert.setString(2,delivery.callbackRef().callbackId()); insert.setString(3,delivery.deliveryRef().deliveryId());
            insert.setString(4,delivery.requestFingerprint()); insert.setString(5,delivery.envelope().authenticationRef().value()); insert.setString(6,JdbcCallbackRegistrationPort.digest(CallbackCodec.encode(delivery)));
            insert.setLong(7,delivery.expectedRegistrationRevision()); insert.setBigDecimal(8,java.math.BigDecimal.valueOf(delivery.verifiedAt().getEpochSecond()).add(java.math.BigDecimal.valueOf(delivery.verifiedAt().getNano(),9)));
            assertEquals(1,insert.executeUpdate());
        }
    }

    /** 真实typed Workflow调用已保存挂起结果；callback仅能关闭原HMAC输入对应的实际接受响应。 */
    private static final class WorkflowFixture {
        final Instant time=now().minusSeconds(1); final String id=UUID.randomUUID().toString();
        final String fingerprint=UUID.randomUUID().toString().replace("-","").repeat(2);
        final VerifiedIdentity requester=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT,OWNER.requireUser()),OWNER,Set.of("sessions.write"),2,new ResultRef("workflow-verified:"+id));
        final TraceEnvelope trace=new TraceEnvelope("callback:"+fingerprint,"callback:"+fingerprint,new InputId("callback:"+fingerprint),Optional.empty());
        final RunRef run=new RunRef(TENANT,"workflow-parent:"+id);
        final RunIdentity identity=new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("workflow-input:"+id), run, trace, requester.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("workflow-input:"+id).value()));
        final CallbackRegistrationDraft draft=new CallbackRegistrationDraft(new CallbackRef(TENANT,"workflow-callback"),OWNER,
                new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"workflow-execution:"+id),run,"workflow-action",7),"workflow-source","callback.v1","signature-v1",new CredentialRef(TENANT,"credential"));
        final RegisterCallback registration=new RegisterCallback(draft,requester,Optional.of(identity),identity.causationInputId(),trace,identity.causationIdentityRef(),new IdempotencyKey("workflow-callback-key"),"workflow-register");
        final InboundEnvelope envelope=new InboundEnvelope(new AuthenticationRef("workflow-system"), Optional.of(requester.verificationRef()), OWNER.sessionRef(), OWNER.target(), new AgentId("agent"), OWNER.subject(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), new InputId("callback:"+fingerprint), new EventId("callback:"+fingerprint), InboundKind.CALLBACK, new RunTarget.ExistingRun(run), "callback", "workflow-source", trace, new TypedPayload("callback.v1","{\"value\":\"original\"}"), time, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("callback:"+fingerprint).value()));
        final VerifiedCallbackDelivery delivery=new VerifiedCallbackDelivery(draft.callbackRef(),1,new CallbackDeliveryRef(TENANT,"workflow-delivery"),fingerprint,envelope,time);
        final JdbcCallbackRegistrationPort port=new JdbcCallbackRegistrationPort(dataSource(),command->true,(record,command)->true,(record,command)->true);
        final JdbcExecutionStore store=new JdbcExecutionStore(dataSource(), identities,
                new JdbcExecutionBudgets(dataSource()),(session,state,actor,prepared)->true,JdbcCallbackRegistrationPort::configure);
        Run suspended;
        WaitRef wait;
        /** 受理、真实派发和挂起全部经Store，不能通过SQL伪造typed响应闭包。 */
        void accept() {
            identities.register(requester);
            var capacity=new ExecutionBudgetPort.Resources(4,4,4,4,1_000,100,1_100);
            var budget=new ExecutionBudgetPort.Registration(run,run,ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,60,60,"callback:v1")));
            var initial=store.admit(new Admission(requester,identity,"workflow-admission:"+id,new ResultRef("workflow-profile:"+id),material("input"),material("context"),budget,List.of(),List.of())).run();
            var claimed=store.claim(new Claim(run,"callback-worker","callback-claim:"+id,Duration.ofMinutes(2))).orElseThrow();
            var prepared=new Prepared(new ActionRef(TENANT,"workflow-call:"+id),1,Kind.WORKFLOW,"workflow",new ResultRef("workflow-capability"),material("request"),"workflow-request:"+id);
            var demand=new ExecutionBudgetPort.Demand(run,"workflow-attempt:"+id,prepared.fingerprint(),ExecutionBudgetPort.Category.TOOL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0),false);
            var dispatched=assertInstanceOf(DispatchResult.Registered.class,store.dispatch(new Dispatch(run,claimed.revision(),claimed.lease().orElseThrow(),prepared,demand.attemptId(),demand,requester,Optional.empty(), List.of())));
            wait=new WaitRef(prepared.action(),1);var context=material("waiting-context");
            var source=(CallbackTarget.ExecutionTarget)draft.target();
            suspended=store.complete(new Completion(run,dispatched.attempt().id(),ResultKind.SUCCEEDED,material("result"),Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.unreported(),dispatched.run().revision(),dispatched.run().lease().orElseThrow(),new Progress(0,0,0,context.reference()),context,List.of(),
                    Optional.of(new WaitDeclaration(wait,WaitKind.WORKFLOW,Map.of(),Optional.of(new WorkflowWait(source.executionRef(),"workflow-suspend:"+id,source.actionKey(),source.expectedExecutionRevision())))))).run();
            assertInstanceOf(CallbackRegisterResult.Registered.class,port.register(registration));assertInstanceOf(CallbackDeliveryResult.Ready.class,port.acceptVerified(delivery));
        }
        Material material(String label) {return new Material(new ResultRef("callback-material:"+id+":"+label),new TypedPayload("fixture.callback","{}"));}
        /** callback确认与接受原稳定输入在同一Store事务内闭合；无旧InputReceipt或operation。 */
        ResumeResult confirm() {
            var response=new Resume(requester,run,wait,suspended.revision(),envelope.inputId(),material("callback-response"),fingerprint,Map.of(),List.of());
            var expected=port.find(draft.callbackRef()).orElseThrow();
            return store.acceptDependency(response,(session,parent,exact,children,content)->port.confirmWorkflowDelivery(session,delivery.deliveryRef(),expected,envelope));
        }
    }

    private static final class Fixture {
        final Instant time = now().minusSeconds(1);
        boolean allowRegister = true, allowStatus = true, allowIngress = true;
        final VerifiedIdentity requester = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()), OWNER, Set.of("sessions.write"), 2, new ResultRef("verified"));
        final TraceEnvelope trace = new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty());
        final RunRef run = new RunRef(TENANT,"callback-parent-run");
        final RunIdentity identity = new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("cause"), run, trace, requester.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("cause").value()));
        final CallbackRegistrationDraft draft = new CallbackRegistrationDraft(new CallbackRef(TENANT, "callback"), OWNER,
                new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"callback-execution"),run,"callback-action",7),
                "source", "callback.v1", "signature-v1", new CredentialRef(TENANT, "credential"));
        final RegisterCallback registration = new RegisterCallback(draft, requester, Optional.of(identity), new InputId("cause"), trace,
                requester.verificationRef(), new IdempotencyKey("callback-key"), "register-fp");
        final InboundEnvelope envelope = new InboundEnvelope(new AuthenticationRef("system"), Optional.of(requester.verificationRef()), OWNER.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(OWNER.target().requireAgent()), OWNER.target().requireAgent(), OWNER.subject(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), new InputId("input"), new EventId("event"), InboundKind.CALLBACK, new RunTarget.ExistingRun(run), "callback", "source", trace, new TypedPayload("callback.v1", "{\"value\":\"sensitive-callback\"}"), time, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("input").value()));
        final VerifiedCallbackDelivery delivery = new VerifiedCallbackDelivery(draft.callbackRef(), 1, new CallbackDeliveryRef(TENANT, "delivery"), "delivery-fp", envelope, time);
        final JdbcCallbackRegistrationPort port = port();
        /** 在真实新Store受理父Run；这里的显式授权器仅用于隔离registry状态机。 */
        Fixture() {
            identities.register(requester);
            var input = new ExecutionStore.Material(new ResultRef("callback-fixture-input"),new TypedPayload("fixture.callback-input","{}"));
            var context = new ExecutionStore.Material(new ResultRef("callback-fixture-context"),new TypedPayload("fixture.callback-context","{}"));
            var capacity = new ExecutionBudgetPort.Resources(1,1,1,1,1_000,100,1_100);
            var budget = new ExecutionBudgetPort.Registration(run,run,ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,60,60,"callback-fixture:v1")));
            assertTrue(new JdbcExecutionStore(dataSource(), identities,(session,state,actor,prepared) -> true).admit(new ExecutionStore.Admission(
                    requester,identity,"callback-fixture",new ResultRef("callback-fixture-profile"),input,context,budget,List.of(),List.of())).created());
        }
        JdbcCallbackRegistrationPort port() { return new JdbcCallbackRegistrationPort(dataSource(), command -> allowRegister,
                (record, command) -> allowStatus, (record, command) -> allowIngress); }
        void register() { assertInstanceOf(CallbackRegisterResult.Registered.class, port.register(registration)); }
        CallbackClaimRequest request() { return new CallbackClaimRequest(TENANT, "worker", 10, Duration.ofSeconds(10).plusNanos(123)); }
        CallbackClaim claim() { return assertInstanceOf(CallbackClaimResult.Claimed.class, port.claimReady(request())).claims().getFirst(); }
        ChangeCallbackStatus status(long revision, CallbackRegistrationStatus status, String operation) {
            return new ChangeCallbackStatus(draft.callbackRef(), requester, revision, status, registration.causationInputId(), trace, new RequestId(operation), "status-fp"); }
    }
    private static DataSource dataSource() { var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl()); source.setUser(ROLE); source.setPassword(PASSWORD); return source; }
    private static boolean auditExists(ResultRef reference) {
        for (String table : tables) {
            if (!table.contains("audit")) continue;
            try (var connection = admin(); var query = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE audit_ref=?")) {
                query.setString(1, reference.value());
                try (var row = query.executeQuery()) { assertTrue(row.next()); if (row.getLong(1) > 0) return true; }
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }
        return false;
    }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static void execute(String sql) { try (var connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private static long scalar(String sql) { try (var connection = admin(); var statement = connection.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private static Instant now() { try (var connection = admin(); var statement = connection.createStatement(); var row = statement.executeQuery("SELECT clock_timestamp()")) { assertTrue(row.next()); return row.getTimestamp(1).toInstant(); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private static void awaitTime(Instant target) { while (now().isBefore(target)) { try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); } } }
}
