package com.skillforge.institution.media;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorePort;
import com.skillforge.runtime.adapter.jdbc.session.SessionMediaRuntimeProtectionLifecycleFixture;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.SessionStorePort.*;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式typed Store与媒体回收的受限PG组合；合成结果不代表Provider或完整Host执行验收。 */
@EnabledIfEnvironmentVariable(named="SKILLFORGE_S03_EXTERNAL_FIXTURE_LANE", matches="media_protection")
class SessionMediaRuntimeProtectionDatabaseTest extends AbstractIntegrationTest {
    @Autowired private DataSource source;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private SessionMediaService media;
    @Autowired private SessionMediaMapper mediaMapper;
    @Autowired private SessionMediaReclamation reclamation;
    @Autowired private SessionAttachmentBodyStore bodies;
    @Autowired private SessionAttachmentBodyMapper bodyMapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private String tenant, session;
    private SessionOwner owner;
    private VerifiedIdentity verified;
    private RunIdentity identity;
    private JdbcSessionStorePort sessions;
    private JdbcExecutionStore runs;
    private ChangeSessionStatus activate, close;
    private Instant old;
    private byte[] png;
    private com.skillforge.runtime.api.media.MediaRef normalized;
    private SessionMediaMapper.UnusedUpload candidate;

    /** 历史ACTIVE由正式SessionStore原生首次写入；当前Run只走typed admit/claim。 */
    @BeforeEach void fixture() throws Exception {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='294' AND success)", Boolean.class));
        tenant = createTenant(tenants, plans, "media-protection-" + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        session = "protection-" + UUID.randomUUID();
        long user = SnowflakeIds.nextId(), agent = SnowflakeIds.nextId();
        transactions.executeWithoutResult(state -> {
            bind();
            jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?::bigint)", user, tenant);
            jdbc.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name) VALUES(?,?,?::bigint,?)", agent, "media-protection-agent-" + agent, tenant, "媒体保护测试助手");
            jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,team_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?::bigint,?,?,NULL,'native',?,?)", SnowflakeIds.nextId(), session, tenant, user, agent,"end_user:"+user,"tenant:"+tenant);
        });
        owner = SessionOwner.user(new SessionRef(new TenantId(tenant), session), new AgentId(Long.toString(agent)), new EndUserId(user));
        old = Instant.now().minus(Duration.ofDays(31));
        var input = new InputId("protection-create");
        var trace = new TraceEnvelope("protection-trace", "protection-span", input, Optional.empty());
        var create = new CreateSession(owner, "chat", "media-protection-scope", "media-protection-persona", input, trace, old);
        activate = new ChangeSessionStatus(owner, SessionStatus.CREATED, SessionStatus.ACTIVE, 1, new InputId("protection-active"), trace, old.plusSeconds(1));
        close = new ChangeSessionStatus(owner, SessionStatus.ACTIVE, SessionStatus.CLOSED, 2, new InputId("protection-close"), trace, old.plusSeconds(2));
        SessionMediaRuntimeProtectionLifecycleFixture.persistActive(source, create, activate, old);
        sessions = new JdbcSessionStorePort(source);
        // 授权hook仅隔离该存储组合；真实IAM与入口另由Host集成验收覆盖。
        runs = new JdbcExecutionStore(source, new JdbcIdentityPolicyPort(source, Map.of()), (sql, run, actor, prepared) -> true);
        var cause = new ResultRef("protection-verified");
        verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(), owner.requireUser()), owner, Set.of("sessions.write"), 1, cause);
        var runInput = new InputId("protection-run-input");
        var runTrace = new TraceEnvelope("protection-run-trace", "protection-run-span", runInput, Optional.empty());
        identity = new RunIdentity(owner, new AgentId((owner).target().id()), runInput, new RunRef(owner.sessionRef().tenantId(), "protection-run-" + UUID.randomUUID()), runTrace, cause, new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (runInput).value()));
        var capacity = new ExecutionBudgetPort.Resources(2, 1, 2, 1, 1_000, 100, 1_100);
        var budget = new ExecutionBudgetPort.Registration(identity.runRef(), identity.runRef(), ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 300, 300, "media-protection:v1")));
        assertTrue(runs.admit(new Admission(verified, identity, "media-protection-admission", new ResultRef("media-protection-profile"),
                material("input"), material("context"), budget, List.of(), List.of())).created());
        assertEquals(RunStatus.RUNNING, runs.claim(new Claim(identity.runRef(), "protection-worker", "protection-claim", Duration.ofMinutes(5))).orElseThrow().status());
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0x80ff0000);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        png = bytes.toByteArray();
        normalized = media.register(owner.sessionRef(), "protection-unused-upload", "图", png);
        transactions.executeWithoutResult(state -> {
            bind();
            jdbc.update("UPDATE nw_chat_session_attachments SET created_at=? WHERE tenant_id::text=? AND session_id=?", Timestamp.from(old), tenant, session);
        });
        candidate = reclamation.page(tenant, null).getFirst();
        assertEquals(2, count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND referenced_at IS NULL AND media_run_id IS NULL AND body_format IS NOT NULL", tenant, session));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void activeSessionWithTerminalRunRemainsProtected() {
        failRun();
        assertEquals(SessionStatus.ACTIVE, sessions.find(new SessionLookup(owner)).orElseThrow().status());
        assertProtected(2);
    }

    @Test void runningRunProtectsOldClosedSession() throws Exception {
        closeOwner();
        assertEquals(RunStatus.RUNNING, storedRun().status());
        assertProtected(2);
    }

    @Test void suspendedRunProtectsOldClosedSession() throws Exception {
        toolResult(ResultKind.INPUT_REQUIRED);
        closeOwner();
        assertEquals(RunStatus.SUSPENDED, storedRun().status());
        assertProtected(2);
    }

    /** 取消Run不伪造等待已消费，原WAITING_INPUT动作继续保护媒体。 */
    @Test void waitingActionProtectsOldClosedCancelledRun() throws Exception {
        toolResult(ResultKind.INPUT_REQUIRED);
        cancelRun(); closeOwner();
        assertEquals(1, count("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND status='WAITING_INPUT'", tenant, identity.runRef().runId()));
        assertProtected(2);
    }

    /** UNKNOWN保留真实原Attempt，不因父Run取消补成功/usage或重发调用。 */
    @Test void outcomeUnknownActionProtectsOldClosedCancelledRun() throws Exception {
        toolResult(ResultKind.UNKNOWN);
        cancelRun(); closeOwner();
        assertEquals(1, count("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND status='UNKNOWN'", tenant, identity.runRef().runId()));
        assertProtected(2);
    }

    @Test void runBoundDescendantCannotReturnToUnused() throws Exception {
        failRun(); closeOwner();
        String child = UUID.randomUUID().toString(), sha = SessionMediaImages.digest(png);
        transactions.executeWithoutResult(state -> {
            bind(); bodies.register(tenant, session, sha, png);
            assertEquals(1, mediaMapper.insert(new SessionMediaRow(child, tenant, session, session, identity.runRef().runId(), "运行图", png.length, sha, "image/png", 3, 2, png, candidate.id(), "normalize-v1", old)));
        });
        assertEquals(1, count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND runtime_session_id=? AND id=?::uuid AND media_run_id=? AND referenced_at IS NULL", tenant, session, child, identity.runRef().runId()));
        var original = mediaRows();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> transactions.executeWithoutResult(state -> {
            bind(); jdbc.update("UPDATE nw_chat_session_attachments SET media_run_id=NULL WHERE tenant_id::text=? AND id=?::uuid", tenant, child);
        }));
        assertEquals(original, mediaRows());
        assertProtected(3);
    }

    private void assertProtected(int rows) {
        var facts = runtimeFacts(); var metadata = mediaRows(); long bytes = bodyMapper.tenantBytes(tenant);
        var retired = reclamation.retire(tenant, candidate);
        assertEquals("runtime_protected", retired.result());
        assertEquals(0, retired.releasedRows()); assertEquals(0, retired.reclaimedBodies());
        assertEquals(facts, runtimeFacts()); assertEquals(metadata, mediaRows()); assertEquals(bytes, bodyMapper.tenantBytes(tenant));
        assertEquals(rows, count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND runtime_session_id=? AND body_format IS NOT NULL AND reclaimed_at IS NULL", tenant, session, session));
        assertArrayEquals(png, media.read(owner.sessionRef(), normalized));
        var status = sessions.find(new SessionLookup(owner)).orElseThrow().status();
        assertInstanceOf(SessionCasResult.Replayed.class, sessions.compareAndSet(status == SessionStatus.CLOSED ? close : activate));
    }

    private void closeOwner() throws Exception {
        SessionMediaRuntimeProtectionLifecycleFixture.close(source, close, old.plusSeconds(2));
        var record = sessions.find(new SessionLookup(owner)).orElseThrow();
        assertEquals(SessionStatus.CLOSED, record.status()); assertEquals(old.plusSeconds(2), record.updatedAt()); assertEquals(3, record.sessionRevision());
    }

    private void failRun() {
        var before = storedRun();
        assertEquals(RunStatus.FAILED, runs.finish(new Finish(identity.runRef(), before.revision(), before.lease().orElseThrow(), RunStatus.FAILED, List.of())).status());
    }

    private void cancelRun() {
        assertEquals(RunStatus.CANCELLED, runs.cancel(new Cancel(verified, identity.runRef(), new InputId("protection-cancel"), List.of())).status());
    }

    /** 真实dispatch登记/预算发送标记后合成结果，只证明Store状态与保护SQL组合。 */
    private void toolResult(ResultKind kind) {
        var before = storedRun(); var action = new ActionRef(identity.runRef().tenantId(), "protection-tool");
        var prepared = new Prepared(action, 1, Kind.TOOL, "fixture-tool", new ResultRef("tool-capability"), material("tool-request"), "protection-tool-request");
        String attempt = "protection-tool-attempt";
        var demand = new ExecutionBudgetPort.Demand(identity.runRef(), attempt, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false);
        var dispatched = assertInstanceOf(DispatchResult.Registered.class, runs.dispatch(new Dispatch(identity.runRef(), before.revision(), before.lease().orElseThrow(), prepared, attempt, demand, verified, Optional.empty(), List.of())));
        assertTrue(runs.authorizeSend(dispatched, prepared));
        var current = dispatched.run(); var context = material("tool-context");
        var wait = kind == ResultKind.INPUT_REQUIRED ? Optional.of(new WaitDeclaration(new WaitRef(action, 1), WaitKind.TOOL,
                Map.of("answer", ToolPortal.ToolInputMethod.ELICITATION_CREATE), Optional.empty())) : Optional.<WaitDeclaration>empty();
        var result = runs.complete(new Completion(identity.runRef(), attempt, kind, material("tool-result"), Optional.empty(), Optional.empty(),
                ExecutionBudgetPort.Usage.unreported(), current.revision(), current.lease().orElseThrow(), new Progress(1, 0, 0, context.reference()), context, List.of(), wait));
        assertTrue(result.newlySaved()); assertTrue(result.advanced());
    }

    private Material material(String label) {
        return new Material(new ResultRef(identity.runRef().runId() + ":" + label), new TypedPayload("fixture.media-protection.v1", "{}"));
    }
    private Run storedRun() { return runs.load(identity.runRef()).orElseThrow(); }
    private List<String> runtimeFacts() {
        var result = new ArrayList<String>();
        for (String table : List.of("agent_runtime_session_owners", "agent_runtime_sessions", "agent_runtime_session_mutations",
                "agent_runtime_execution_sessions", "agent_runtime_execution_runs", "agent_runtime_execution_actions", "agent_runtime_execution_attempts",
                "agent_runtime_execution_waits", "agent_runtime_execution_materials", "agent_runtime_budget_roots", "agent_runtime_budget_runs", "agent_runtime_budget_attempts")) {
            result.addAll(jsonRows("SELECT row_to_json(fact)::text FROM " + table + " fact WHERE fact.tenant_id=? ORDER BY row_to_json(fact)::text", tenant));
        }
        return result;
    }
    private List<String> mediaRows() { return jsonRows("SELECT row_to_json(media)::text FROM (SELECT * FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? ORDER BY id) media", tenant, session); }
    private List<String> jsonRows(String query, Object... args) { return transactions.execute(state -> { bind(); return jdbc.queryForList(query, String.class, args); }); }
    private long count(String query, Object... args) { return transactions.execute(state -> { bind(); return jdbc.queryForObject(query, Long.class, args); }); }
    private void bind() { jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant); }
}
