package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 完整迁移、受限角色、真实受理事务；当前IAM使用显式允许夹具，不调用模型。 */
class AgentRuntimeMemoryInputsPostgresTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("memory_admission").withUsername("skillforge").withPassword("synthetic-memory-admin");
    private static final PGSimpleDataSource SOURCE = new PGSimpleDataSource();
    private static final AgentExecutionCodec CODEC = new AgentExecutionCodec(new CodecLimits(1_000_000, 100_000, 128));
    private static final TenantId TENANT = new TenantId("901");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "memory-input-audit"), new AgentId("902"), new EndUserId(903));
    private static final TypedPayload INPUT = new TypedPayload("input.v1", "{\"content\":[{\"type\":\"text\",\"text\":\"先列结论，再列证据🙂\"}],\"extraParameters\":{}}");

    @BeforeAll static void start() throws Exception {
        if (Boolean.parseBoolean(System.getenv("SKILLFORGE_TEST_RETAIN_MEMORY_INPUT_DB")))
            DATABASE.withReuse(true).withLabel("skillforge.memory-input-audit", UUID.randomUUID().toString());
        DATABASE.start();
        System.out.println("memory-input isolated database container=" + DATABASE.getContainerId());
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var admin = new PGSimpleDataSource(); admin.setURL(DATABASE.getJdbcUrl());
        admin.setUser(DATABASE.getUsername()); admin.setPassword(DATABASE.getPassword());
        try (var connection = admin.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE ROLE memory_input_fixture LOGIN PASSWORD 'synthetic-memory-reader' NOSUPERUSER NOBYPASSRLS");
            sql.execute("GRANT USAGE ON SCHEMA public TO memory_input_fixture");
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO memory_input_fixture");
            sql.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO memory_input_fixture");
            sql.execute("INSERT INTO nw_tenants(id,name,slug,plan_id) SELECT 901,'记忆输入隔离测试','memory-input-audit',min(id) FROM nw_billing_plans");
            sql.execute("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status) VALUES(902,'memory-input-agent',901,'记忆输入助手','draft')");
            sql.execute("INSERT INTO nw_agent_publications(id,tenant_id,agent_id,revision,snapshot,published_at) VALUES(904,901,902,1,'{\"agent\":{\"config\":{\"memory\":{\"automatic_extraction\":true}}}}',now())");
            sql.execute("UPDATE nw_agents SET status='published',published_revision_id=904,revision=1 WHERE id=902");
        }
        SOURCE.setURL(DATABASE.getJdbcUrl()); SOURCE.setUser("memory_input_fixture"); SOURCE.setPassword("synthetic-memory-reader");
    }

    @AfterAll static void stop() {
        if (!Boolean.parseBoolean(System.getenv("SKILLFORGE_TEST_RETAIN_MEMORY_INPUT_DB"))) DATABASE.stop();
    }

    @Test void admissionCommitsPrivateSourceAndJobOnceAndRollsBackTogether() throws Exception {
        var identities = mock(JdbcIdentityTransactions.class);
        when(identities.authorize(any(org.apache.ibatis.session.SqlSession.class), any(IdentityPolicyPort.AuthorizationRequest.class)))
                .thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1, new ResultRef("fixture-allow")));
        when(identities.matchesOriginalCaller(any(), eq(OWNER), any(), any())).thenReturn(true);
        var sources = new AtomicReference<AgentRuntimeMemoryInputs>();
        var reject = new AtomicBoolean();
        var tests = mock(WorkflowTestRegistry.class);
        var log = new JdbcSessionLogPort(SOURCE);
        var store = new JdbcExecutionStore(SOURCE, identities, new JdbcExecutionBudgets(SOURCE),
                (sql, run, actor, prepared) -> true, JdbcSessionLogPort::configure,
                (sql, run) -> {}, (sql, run, actor, operation, input) -> true, session -> {},
                (sql, session, events, reader) -> {
                    sources.get().appendBorrowed(sql, session, events);
                    if (reject.get()) throw new IllegalStateException("synthetic-projection-failure");
                });
        sources.set(new AgentRuntimeMemoryInputs(store, log, CODEC, tests));
        var command = admission("accepted", true);
        when(identities.originalVerification(any(), eq(OWNER), eq(command.identity().verificationRef())))
                .thenReturn(Optional.of(command.identity()));
        assertTrue(store.admit(command).created());
        assertFalse(store.admit(command).created());
        var sourceId = new EventId("memory-input:" + command.run().runRef().runId());
        var saved = log.findByEvent(OWNER, sourceId).orElseThrow();
        assertEquals(INPUT, saved.payload());
        assertEquals(command.run().runRef(), saved.runRef().orElseThrow());
        assertEquals(command.run().causationInputId(), saved.inputId());
        assertEquals(1, count("agent_runtime_memory_extraction_jobs", "901"));
        assertEquals(0, count("agent_runtime_memory_extraction_jobs", "905"));
        assertEquals("{\"text\":\"受保护的公开消息\"}", store.events(OWNER.sessionRef(), 0, 10).getFirst().event().data().canonicalJson());
        assertTrue(log.findByEvent(SessionOwner.user(OWNER.sessionRef(), new AgentId("999"), OWNER.requireUser()), sourceId).isEmpty());

        reject.set(true);
        assertThrows(IllegalStateException.class, () -> store.admit(admission("rolled-back", true)));
        assertTrue(store.load(new RunRef(TENANT, "rolled-back")).isEmpty());
        assertTrue(log.findByEvent(OWNER, new EventId("memory-input:rolled-back")).isEmpty());
        assertEquals(1, count("agent_runtime_memory_extraction_jobs", "901"));
        reject.set(false);
        store.admit(admission("goal-round", false));
        assertTrue(log.findByEvent(OWNER, new EventId("memory-input:goal-round")).isEmpty());

        when(tests.session(any(), eq(901L), eq(OWNER.sessionRef().sessionId())))
                .thenReturn(mock(WorkflowTestRegistry.Snapshot.class));
        store.admit(admission("workflow-test", true));
        assertTrue(log.findByEvent(OWNER, new EventId("memory-input:workflow-test")).isEmpty());
        when(tests.session(any(), eq(901L), eq(OWNER.sessionRef().sessionId()))).thenReturn(null);
        try (var connection = connection("901"); var sql = connection.createStatement()) {
            sql.execute("UPDATE nw_agents SET status='disabled' WHERE id=902"); connection.commit();
        }
        store.admit(admission("disabled-extraction", true));
        assertEquals(1, count("agent_runtime_memory_extraction_jobs", "901"));
        assertTrue(log.findByEvent(OWNER, new EventId("memory-input:disabled-extraction")).isPresent());
    }

    private static Admission admission(String id, boolean userMessage) {
        var input = new InputId("input-" + id); var run = new RunRef(TENANT, id); var verification = new ResultRef("verified-" + id);
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()), OWNER, Set.of(), 1, verification);
        var identity = new RunIdentity(OWNER, new AgentId("902"), input, run,
                new TraceEnvelope("trace-" + id, "span-" + id, input, Optional.empty()), verification,
                new RunRequester.EndUser(OWNER.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "origin-" + id));
        var original = new Material(new ResultRef("execution-input:" + id), CODEC.encodeInput(
                List.of(new ModelMessage("input:" + id, ModelRole.USER, "先列结论，再列证据🙂")), INPUT));
        var context = new Material(new ResultRef("context-" + id), new TypedPayload("fixture-context", "{}"));
        var capacity = new ExecutionBudgetPort.Resources(8, 2, 8, 2, 100_000, 100_000, 200_000);
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                        capacity, capacity, capacity, 600, 600, "memory-fixture-v1")));
        var events = userMessage ? List.of(new Event(new EventId("execution-user-message:" + id + ":" + input.value()),
                AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT, new TypedPayload(AgentRuntimeExecutionResponses.USER_MESSAGE_SCHEMA,
                        "{\"text\":\"受保护的公开消息\"}"))) : List.<Event>of();
        return new Admission(actor, identity, "fingerprint-" + id, new ResultRef("configuration-" + id), original, context, budget, events, List.of());
    }

    private static Connection connection(String tenant) throws Exception {
        var connection = SOURCE.getConnection(); connection.setAutoCommit(false);
        try (var sql = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
            sql.setString(1, tenant); sql.execute();
        }
        return connection;
    }

    private static int count(String table, String tenant) throws Exception {
        try (var connection = connection(tenant); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
}
