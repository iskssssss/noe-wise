package com.skillforge.runtime.core;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowParser;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实typed Registered、当前IAM、不可变Workflow安装和Engine持久执行的代表性集成合同。 */
@Testcontainers(disabledWithoutDocker = false)
class SdkWorkflowPortalTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final TypedPayload INPUT = new TypedPayload("application/json", "{\"user_input\":\"核验已登记执行\"}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionBudgetPort.Resources CAPACITY = new ExecutionBudgetPort.Resources(0, 0, 4, 0, 0, 0, 0);
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("typed_registered_workflow").withUsername("admin").withPassword("admin_password");
    private static PGSimpleDataSource source;
    private JdbcIdentityPolicyPort identities;
    private JdbcWorkflowBindings bindings;
    private JdbcWorkflowPolicy policy;
    private JdbcExecutionStore store;
    private JdbcExecutionLedgerPort ledger;
    private final Map<ActionRef, Expected> originals = new HashMap<>();
    private final AtomicInteger nodes = new AtomicInteger();

    @BeforeAll static void schema() throws Exception {
        execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge') THEN CREATE ROLE skillforge NOLOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()).locations("classpath:db/migration").load().migrate();
        execute("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(800000000000000309,'typed-workflow-portal','Typed Workflow Portal',0.999)");
        execute("INSERT INTO nw_tenants(id,name,slug,plan_id,status) VALUES(1,'Workflow tenant','typed-workflow-portal',800000000000000309,'active')");
        execute("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status) VALUES(10,'typed-workflow-agent',1,'Workflow Agent','published')");
        execute("INSERT INTO nw_end_users(id,tenant_id,status) VALUES(1,1,'active')");
        install(0, false); install(1, true);
        execute("CREATE ROLE workflow_registered LOGIN PASSWORD 'workflow_registered_test_password' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO workflow_registered");
        execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO workflow_registered");
        // 取消父Run会在同一受限事务触发容量释放；只补齐该单表DELETE，继续受当前租户RLS约束。
        execute("GRANT DELETE ON public.agent_runtime_dag_permits TO workflow_registered");
        execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO workflow_registered");
        execute("GRANT EXECUTE ON FUNCTION resolve_runtime_authentication_tenant(text) TO workflow_registered");
        source = new PGSimpleDataSource(); source.setURL(DATABASE.getJdbcUrl()); source.setUser("workflow_registered"); source.setPassword("workflow_registered_test_password");
    }
    @BeforeEach void prepare() {
        identities = new JdbcIdentityPolicyPort(source, Map.of(new PrincipalRef.SystemPrincipal(TENANT, "workflow-worker"), Set.of("sessions.write", "sessions.read")));
        bindings = new JdbcWorkflowBindings(source); policy = new JdbcWorkflowPolicy(source, identities); ledger = new JdbcExecutionLedgerPort(source);
        store = new JdbcExecutionStore(source, identities, (session, run, actor, prepared) -> {
            var expected = originals.get(prepared.action());
            if (expected == null || !expected.run().equals(run.identity()) || !expected.request().equals(prepared.request())) return false;
            var resource = WorkflowPolicyResource.dispatchResource(run.identity(), expected.snapshot(), expected.target(), expected.execution(),
                    INPUT, List.of(), prepared.action(), prepared.request().reference(), prepared.fingerprint());
            return identities.authorize(session, new IdentityPolicyPort.AuthorizationRequest(actor, "workflow.dispatch", resource,
                    run.identity().trace().traceId(), run.identity().trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
        });
    }

    @Test void realRegisteredDispatchCompletesOnceAndRebuiltPortalReadsOriginalTerminal() throws Exception {
        var f = fixture("completed", false); var portal = portal();
        var done = assertInstanceOf(WorkflowResult.Completed.class, portal.dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals("skillforge.runtime.workflow.engine-result.v1", done.output().schemaId());
        assertEquals(JSON.valueToTree(Map.of("messages", List.of(Map.of("role", "assistant", "content", "registered-ready")))),
                JSON.readTree(done.output().canonicalJson()), "结果只能包含冻结白名单显式导出的实际消息");
        assertEquals(3, nodes.get());
        var saved = record(f); assertEquals(WorkflowExecutionState.COMPLETED, saved.state());
        var originalAttempt = store.attempt(f.registered().run().identity().runRef(), f.registered().attempt().id()).orElseThrow();
        assertEquals(done, portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals(saved, record(f)); assertEquals(3, nodes.get());
        assertEquals(originalAttempt, store.attempt(f.registered().run().identity().runRef(), f.registered().attempt().id()).orElseThrow());
        assertEquals(WorkflowExecutionState.COMPLETED, portal().query(new WorkflowQuery(f.identity(), f.request().executionRef())).orElseThrow().state());
        assertEquals(3, nodes.get());
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(f.dispatch()));
        assertEquals(1, store.actions(f.registered().run().identity().runRef(), 0, 10).size());
        // 原终态可读不等于获得新的发送权；当前撤权后原Registered也不能再走重复派发入口。
        identities.revoke(f.authentication());
        assertThrows(IllegalArgumentException.class, () -> portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals(saved, record(f)); assertEquals(3, nodes.get());
        assertEquals(originalAttempt, store.attempt(f.registered().run().identity().runRef(), f.registered().attempt().id()).orElseThrow());
    }

    @Test void substitutedAttemptRequestOrFingerprintCannotReachEngineOrCreateExecution() {
        var f = fixture("substitution", false);
        for (var changed : List.of(new ProviderDispatch(f.dispatch().run(), f.prepared().action(), "invocation", "wrong-attempt", PortalCallKind.WORKFLOW_DISPATCH,
                        f.prepared().request().reference(), f.prepared().capability(), f.prepared().fingerprint(), f.registered().run().fence()),
                new ProviderDispatch(f.dispatch().run(), f.prepared().action(), "invocation", f.registered().attempt().id(), PortalCallKind.WORKFLOW_DISPATCH,
                        new ResultRef("wrong-request"), f.prepared().capability(), f.prepared().fingerprint(), f.registered().run().fence()),
                new ProviderDispatch(f.dispatch().run(), f.prepared().action(), "invocation", f.registered().attempt().id(), PortalCallKind.WORKFLOW_DISPATCH,
                        f.prepared().request().reference(), f.prepared().capability(), "b".repeat(64), f.registered().run().fence()))) {
            var context = ProviderCallContext.dispatched(f.request().context().callContext(), changed);
            var substituted = new WorkflowDispatch(context, f.request().snapshot(), f.request().target(), f.request().executionRef(), INPUT, List.of(), f.request().idempotencyKey());
            assertThrows(IllegalArgumentException.class, () -> portal().dispatchRegistered(f.registered(), f.prepared(), substituted));
        }
        assertTrue(ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.identity(), f.request().executionRef())).isEmpty());
        assertEquals(0, nodes.get());
    }

    @Test void currentAuthenticationRevocationAfterRegistrationPreventsExecutionCreation() {
        var f = fixture("revoked", false); identities.revoke(f.authentication());
        assertThrows(IllegalArgumentException.class, () -> portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertTrue(ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.identity(), f.request().executionRef())).isEmpty());
        assertEquals(0, nodes.get());
    }

    @Test void committedParentCancellationClosesOriginalHumanWaitWithoutExecutingNextNodeOrNewAttempt() {
        var f = fixture("parent-cancel", true);
        assertInstanceOf(WorkflowResult.Suspended.class, portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals(WorkflowExecutionState.WAITING_HITL, record(f).state());
        int executed = nodes.get();
        var parent = store.cancel(new Cancel(f.identity(), f.registered().run().identity().runRef(), new InputId("cancel-parent"), List.of()));
        assertEquals(RunStatus.CANCELLED, parent.status());
        assertTrue(portal().cancelCommittedParent(f.identity(), f.request().executionRef(), new InputId("cancel-workflow"), f.request().context().callContext().control()));
        assertEquals(WorkflowExecutionState.CANCELLED, record(f).state()); assertEquals(executed, nodes.get());
        assertTrue(portal().cancelCommittedParent(f.identity(), f.request().executionRef(), new InputId("cancel-workflow-repeat"), f.request().context().callContext().control()));
        assertEquals(executed, nodes.get()); assertEquals(1, store.actions(parent.identity().runRef(), 0, 10).size());
        assertTrue(store.attempt(parent.identity().runRef(), f.registered().attempt().id()).orElseThrow().result().isEmpty());
        assertFalse(store.authorizeSend(f.registered(), f.prepared()));
    }

    private Fixture fixture(String label, boolean human) {
        var owner = SessionOwner.user(new SessionRef(TENANT, "session:" + label), new AgentId("10"), new EndUserId(1));
        var authentication = identities.registerTrusted(new PrincipalRef.SystemPrincipal(TENANT, "workflow-worker"), owner, "worker", "queue", Duration.ofMinutes(5));
        var identity = assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class, identities.verify(new IdentityPolicyPort.IdentityRequest(authentication,
                owner, "worker", "queue", new RequestId("verify:" + label), Optional.empty(), "trace:" + label, "span:" + label))).identity();
        var input = new InputId("input:" + label); var runRef = new RunRef(TENANT, "run:" + label);
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, runRef, new TraceEnvelope("trace:" + label, "span:" + label, input, Optional.empty()), identity.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        // 与生产入口相同，先由正式SessionLog建立owner，不补造旧Run或绕过Execution外键。
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, new JdbcSessionLogPort(source).append(
                new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner, input,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        new SessionEventDraft(new EventId("session-opened:" + label), "session.opened", "1", "session-opened:" + label,
                                run.trace(), new TypedPayload("test.session.v1", "{}"), Instant.now()))));
        var limits = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 3600, 3600, "sdk-registered:v2");
        store.admit(new Admission(identity, run, "input:" + label, new ResultRef("profile:" + label), material("input:" + label), material("context:" + label),
                new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY,
                        new ExecutionBudgetPort.Policy.Enforced(limits)), List.of(), List.of()));
        var claimed = store.claim(new Claim(runRef, "sdk-test-worker", "claim:" + label, Duration.ofMinutes(5))).orElseThrow();
        int offset = human ? 1 : 0;
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:" + offset), Integer.toString(200 + offset), 1L + offset,
                new CapabilityRef(CapabilityType.WORKFLOW, Integer.toString(50 + offset), Integer.toString(100 + offset)), Duration.ofSeconds(30), 1L);
        bindings.publish(TENANT, target);
        var snapshot = new CapabilitySnapshotRef(owner.sessionRef(), input, "snapshot:" + label, 1);
        var execution = new ExecutionRef(TENANT, "execution:" + label);
        var requestMaterial = new Material(new ResultRef("workflow-request:" + label), new TypedPayload("test.workflow.request.v1", json(Map.of(
                "workflow", target.instanceId(), "version", target.instanceRevision(), "input", INPUT.canonicalJson(), "snapshot", snapshot.snapshotId(), "execution", execution.executionId()))));
        var prepared = new Prepared(new ActionRef(TENANT, "workflow-action:" + label), 1, Kind.WORKFLOW, target.handle().value(),
                new ResultRef("workflow-capability:" + label), requestMaterial, digest(requestMaterial.content().schemaId() + "\0" + requestMaterial.content().canonicalJson()));
        originals.put(prepared.action(), new Expected(run, target, snapshot, execution, requestMaterial));
        var demand = new ExecutionBudgetPort.Demand(runRef, "attempt:" + label, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false);
        var dispatch = new Dispatch(runRef, claimed.revision(), claimed.lease().orElseThrow(), prepared, demand.attemptId(), demand, identity, Optional.empty(), List.of());
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var direct = new ProviderDispatch(runRef, prepared.action(), "invocation:" + label, registered.attempt().id(), PortalCallKind.WORKFLOW_DISPATCH,
                prepared.request().reference(), prepared.capability(), prepared.fingerprint(), registered.run().fence());
        var context = ProviderCallContext.dispatched(new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(60), () -> false)), direct);
        return new Fixture(authentication, identity, registered, prepared, dispatch, new WorkflowDispatch(context, snapshot, target, execution, INPUT, List.of(), new IdempotencyKey("workflow:" + label)));
    }

    private SdkWorkflowPortal portal() {
        var engine = new StateMachineEngine() {
            @Override public Map<String,Object> runControlled(ControlledWorkflowExecution.Request request, ControlledWorkflowExecution.Hooks hooks) {
                return super.runControlled(request, new ControlledWorkflowExecution.Hooks() {
                    public void beforeNode(String id) { hooks.beforeNode(id); nodes.incrementAndGet(); }
                    public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(com.skillforge.core.workflow.execution.node.NodeExecutionContext node) { return hooks.executeExternalNode(node); }
                    public void checkpoint(ControlledWorkflowExecution.Checkpoint value) { hooks.checkpoint(value); }
                });
            }
        };
        return new SdkWorkflowPortal(source, bindings, policy, engine, new WorkflowParser(),
                (identity, lease, execution, control, inputs, node) -> { throw new IllegalStateException("external_node_not_expected"); },
                call -> INPUT.equals(call.parameters()), call -> "application/json".equals(call.response().schemaId()),
                (binding, output) -> "skillforge.runtime.workflow.engine-result.v1".equals(output.schemaId()), Clock.systemUTC(), store,
                (session, execution) -> {
                    var action = store.action(execution.causativeActionRef().orElseThrow()).orElseThrow();
                    var expected = Objects.requireNonNull(originals.get(action.reference()), "original workflow source");
                    var parent = store.load(action.run()).orElseThrow(); var original = store.material(TENANT, action.request()).orElseThrow();
                    if (!parent.identity().equals(execution.owner()) || !expected.run().equals(execution.owner()) || !expected.execution().equals(execution.executionRef())
                            || action.kind() != Kind.WORKFLOW || !original.equals(expected.request()) || !action.fingerprint().equals(digest(original.content().schemaId() + "\0" + original.content().canonicalJson())))
                        throw new IllegalArgumentException("workflow_original_registered_source_rejected");
                    SdkWorkflowPortal.requireInitialSource(execution, expected.target(), expected.snapshot(), INPUT, List.of());
                });
    }
    private ExecutionLedgerPort.ExecutionRecord record(Fixture f) { return ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.identity(), f.request().executionRef())).orElseThrow(); }
    private static Material material(String id) { return new Material(new ResultRef(id), new TypedPayload("application/json", "{}")); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static String json(Map<String,?> value) {
        try { return JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private static void install(int offset, boolean human) throws Exception {
        String definition = human
                ? "{\"entry\":\"start\",\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"human\",\"type\":\"human_input\",\"prompt\":\"请确认\",\"result_to\":\"state.confirmation\"},{\"id\":\"done\",\"type\":\"output\",\"message\":\"must-not-run-after-cancel\"}],\"edges\":[{\"from\":\"start\",\"to\":\"human\"},{\"from\":\"human\",\"to\":\"done\"}]}"
                : "{\"entry\":\"start\",\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"done\",\"type\":\"output\",\"message\":\"registered-ready\"},{\"id\":\"end\",\"type\":\"end\"}],\"edges\":[{\"from\":\"start\",\"to\":\"done\"},{\"from\":\"done\",\"to\":\"end\"}]}";
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            try (var statement = connection.prepareStatement("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(?,1,'WORKFLOW',?,'Workflow','TENANT','PUBLISHED',now(),now())")) {
                statement.setLong(1,50 + offset); statement.setString(2,"typed-workflow-" + offset); statement.executeUpdate();
            }
            // 本夹具验证实际消息结果与终态重放，须显式声明messages；空白名单按契约只能导出{}。
            try (var statement = connection.prepareStatement("INSERT INTO nw_capability_versions(id,tenant_id,definition_id,capability_type,status,descriptor,content_hash,version_key,name,trust_level,created_at,published_at) VALUES(?,1,?,'WORKFLOW','PUBLISHED','{\"output_schema\":{},\"export_fields\":[\"messages\"]}',?,'v1','Workflow','TENANT',now(),now())")) {
                statement.setLong(1,100 + offset); statement.setLong(2,50 + offset); statement.setString(3,"typed-workflow-hash-" + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE nw_capability_definitions SET default_version_id=? WHERE id=?")) {
                statement.setLong(1,100 + offset); statement.setLong(2,50 + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_containers(id,tenant_id,workflow_id) VALUES(?,1,?)")) {
                statement.setLong(1,300 + offset); statement.setString(2,"typed-workflow-" + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_draft_revisions(id,container_id,tenant_id,revision,definition) VALUES(?,?,1,1,?::jsonb)")) {
                statement.setLong(1,400 + offset); statement.setLong(2,300 + offset); statement.setString(3,definition); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_definition_versions(id,tenant_id,container_id,definition_version,source_draft_id,capability_version_id,definition) VALUES(?,1,?,1,?,?,?::jsonb)")) {
                statement.setLong(1,1 + offset); statement.setLong(2,300 + offset); statement.setLong(3,400 + offset); statement.setLong(4,100 + offset); statement.setString(5,definition); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_instances(id,tenant_id,instance_id,definition_version_id,status) VALUES(?,1,?,?,'draft')")) {
                statement.setLong(1,200 + offset); statement.setString(2,"typed-instance-" + offset); statement.setLong(3,1 + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_installation_revisions(tenant_id,instance_id,installation_revision,definition_version_id,config,custom_prompt,bindings,created_at) VALUES(1,?,1,?,'{}','','{}',clock_timestamp())")) {
                statement.setLong(1,200 + offset); statement.setLong(2,1 + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE nw_workflow_instances SET installation_revision=1,status='published' WHERE id=?")) { statement.setLong(1,200 + offset); statement.executeUpdate(); }
        }
    }
    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private record Expected(RunIdentity run, FrozenWorkflowTarget target, CapabilitySnapshotRef snapshot, ExecutionRef execution, Material request) { }
    private record Fixture(AuthenticationRef authentication, VerifiedIdentity identity, DispatchResult.Registered registered, Prepared prepared,
                           Dispatch dispatch, WorkflowDispatch request) { }
}
