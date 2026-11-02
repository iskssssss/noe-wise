package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.json.RuntimeJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.testkit.SessionLogPortContract;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionLogPort.AppendResult;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@link JdbcSessionLogPort} 在真实 PostgreSQL 与强制 RLS 下的契约和存储验收。 */
class JdbcSessionLogPortTest extends SessionLogPortContract {
    /** S03任一参数出现即只使用父新库；缺参数直接拒绝，不能fallback容器。 */
    private static final boolean EXTERNAL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"));
    private static final String APP_ROLE = "agent_runtime_session_log_test";
    private static final String APP_PASSWORD = "session_log_test_password";
    private static final String INSERT_FAILURE_TRIGGER =
            "agent_runtime_session_event_forced_failure";
    private static final String INSERT_FAILURE_FUNCTION =
            "public.fail_agent_runtime_session_event_insert";
    private static final TenantId TENANT = new TenantId("tenant-jdbc");
    private static final SessionOwner OWNER = owner("session-jdbc");
    private static final Instant NANO_INSTANT =
            Instant.ofEpochSecond(1_788_430_530L, 123_456_789);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge")
                    .withUsername("skillforge_admin")
                    .withPassword("skillforge_admin_password");

    private SessionLogPort port;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        if(!EXTERNAL) POSTGRES.start();
        if (EXTERNAL) {
            try (Connection connection = adminConnection()) { verifyExternalFixture(connection); }
        }
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(jdbcUrl(), adminUser(), adminPassword());
        try(Connection connection=adminConnection();Statement statement=connection.createStatement()) {
            if(!EXTERNAL) {
                statement.execute("CREATE ROLE " + APP_ROLE
                    + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
                statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
                statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
                statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);
            }
        }
        try(Connection connection=applicationDataSource().getConnection();Statement statement=connection.createStatement();ResultSet role=statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(role.next());assertTrue(role.getBoolean(1),"被测连接必须是真实受限RLS角色");
        }
    }

    /** 只释放正常CI拥有的容器；S03借用资源由父单writer回收。 */
    @AfterAll static void releaseOwnedContainer(){if(!EXTERNAL)POSTGRES.stop();}

    @Override
    protected SessionLogPort createPort() {
        resetDatabase();
        port = new JdbcSessionLogPort(applicationDataSource());
        return port;
    }

    @Test
    void durableUserSourceReturnsOriginalTargetAndRejectsWrongSubjectAndGroup() {
        var sourceOwner = owner("durable-user-source");
        var source = userSourceEvent(sourceOwner, "durable-source");
        var committed = assertInstanceOf(AppendResult.Appended.class, port.append(source)).event();
        var reviewer = SessionOwner.user(sourceOwner.sessionRef(), new AgentId("different-reviewer-agent"), sourceOwner.requireUser());
        assertTrue(port.findByEvent(reviewer, committed.eventId()).isEmpty());
        assertEquals(Optional.of(committed), port.findUserSource(sourceOwner.sessionRef(), sourceOwner.requireUser(), committed.eventId()));
        assertEquals(sourceOwner, port.findUserSource(sourceOwner.sessionRef(), sourceOwner.requireUser(), committed.eventId()).orElseThrow().owner());
        assertTrue(port.findUserSource(sourceOwner.sessionRef(), new EndUserId(708), committed.eventId()).isEmpty());
        assertTrue(port.findUserSource(new SessionRef(TENANT, "wrong-source-session"), sourceOwner.requireUser(), committed.eventId()).isEmpty());
        assertTrue(port.findUserSource(new SessionRef(new TenantId("wrong-source-tenant"), sourceOwner.sessionRef().sessionId()), sourceOwner.requireUser(), committed.eventId()).isEmpty());
        assertTrue(port.findUserSource(sourceOwner.sessionRef(), sourceOwner.requireUser(), new EventId("missing-source-event")).isEmpty());
        var binding = new com.skillforge.runtime.api.GroupBindingRef(TENANT, "durable-source-group");
        var groupOwner = new SessionOwner(new SessionRef(TENANT, "durable-source-group-session"),
                com.skillforge.runtime.api.SessionSubject.group(binding), com.skillforge.runtime.api.SessionTarget.group(binding));
        var groupEvent = groupMemoryEvent(groupOwner, "durable-group", "group.memory.source", "v1", "skillforge.group.memory-source.v1");
        assertInstanceOf(AppendResult.Appended.class, port.append(groupEvent));
        assertTrue(port.findUserSource(groupOwner.sessionRef(), sourceOwner.requireUser(), groupEvent.event().eventId()).isEmpty());
    }

    @Test
    void userSourceLookupFailsClosedForSameSessionUserEventAcrossTwoTargets() {
        var firstOwner = owner("ambiguous-user-source");
        var secondOwner = SessionOwner.user(firstOwner.sessionRef(), new AgentId("another-source-agent"), firstOwner.requireUser());
        var first = userSourceEvent(firstOwner, "ambiguous-source");
        var second = userSourceEvent(secondOwner, "ambiguous-source");
        assertInstanceOf(AppendResult.Appended.class, port.append(first));
        assertInstanceOf(AppendResult.Appended.class, port.append(second));
        assertTrue(port.findUserSource(firstOwner.sessionRef(), firstOwner.requireUser(), first.event().eventId()).isEmpty());
        assertTrue(port.findByEvent(firstOwner, first.event().eventId()).isPresent());
        assertTrue(port.findByEvent(secondOwner, second.event().eventId()).isPresent());
    }

    private static AppendSessionEvent userSourceEvent(SessionOwner owner, String id) {
        var input = new InputId("source-input-" + id);
        return new AppendSessionEvent(new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                new SessionEventDraft(new EventId("source-event-" + id), "session.jdbc-test", "1", "source-fingerprint-" + id,
                        new TraceEnvelope("source-trace", "source-span", input, Optional.empty()),
                        new TypedPayload("skillforge.runtime.jdbc-test.v1", "{\"text\":\"durable user source\"}"), NANO_INSTANT));
    }

    @Test
    void groupMemoryLogAcceptsOnlyExactFormalEventSchemaAndVersion() {
        var binding = new com.skillforge.runtime.api.GroupBindingRef(TENANT, "group-memory-log");
        var owner = new SessionOwner(new SessionRef(TENANT, "group-memory-session"),
                com.skillforge.runtime.api.SessionSubject.group(binding),
                com.skillforge.runtime.api.SessionTarget.group(binding));
        var source = groupMemoryEvent(owner, "source", "group.memory.source", "v1",
                "skillforge.group.memory-source.v1");
        var written = groupMemoryEvent(owner, "written", "memory.written", "2",
                "skillforge.runtime.memory.written.v2");
        var retrieved = groupMemoryEvent(owner, "retrieved", "retrieval.performed", "2",
                "skillforge.runtime.memory.retrieval-performed.v3");
        assertInstanceOf(AppendResult.Appended.class, port.append(source));
        assertInstanceOf(AppendResult.Appended.class, port.append(written));
        assertInstanceOf(AppendResult.Appended.class, port.append(retrieved));
        assertInstanceOf(AppendResult.Replayed.class, port.append(written));
        assertEquals(List.of("group.memory.source", "memory.written", "retrieval.performed"),
                port.readAfter(new ReadSessionLog(owner, 0, 10)).stream()
                        .map(CommittedSessionEvent::eventType).toList());
        for (var rejected : List.of(
                groupMemoryEvent(owner, "arbitrary", "session.untrusted", "2",
                        "skillforge.runtime.memory.written.v2"),
                groupMemoryEvent(owner, "wrong-schema", "memory.written", "2", "untrusted.v2"),
                groupMemoryEvent(owner, "old-schema", "memory.written", "2",
                        "skillforge.runtime.memory.written.v1"),
                groupMemoryEvent(owner, "old-retrieval-schema", "retrieval.performed", "2",
                        "skillforge.runtime.memory.retrieval-performed.v2"),
                groupMemoryEvent(owner, "wrong-version", "retrieval.performed", "1",
                        "skillforge.runtime.memory.retrieval-performed.v3"),
                groupMemoryEvent(owner, "source-version", "group.memory.source", "1",
                        "skillforge.group.memory-source.v1"))) {
            var failure = assertThrows(IllegalStateException.class, () -> port.append(rejected));
            assertEquals("session_log_storage_failure", failure.getMessage());
            var cause = assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals("group_domain_log_event_required", cause.getMessage());
            assertTrue(port.findByEvent(owner, rejected.event().eventId()).isEmpty());
        }
        assertEquals(3, port.readAfter(new ReadSessionLog(owner, 0, 10)).size());
    }

    private static AppendSessionEvent groupMemoryEvent(SessionOwner owner, String id,
            String eventType, String eventVersion, String schema) {
        var input = new InputId("memory-input-" + id);
        var target = new SessionProjectionTarget(owner, new AgentId("agent-memory-log"), input,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new AppendSessionEvent(target, new SessionEventDraft(new EventId("memory-event-" + id),
                eventType, eventVersion, "memory-fingerprint-" + id,
                new TraceEnvelope("memory-trace", "memory-span", input, Optional.empty()),
                new TypedPayload(schema, "{}"), NANO_INSTANT));
    }

    @Test
    void diagnosticBatchPreservesOrderAndRollsBackTheWholeBatchOnConflict() {
        var jdbc=(JdbcSessionLogPort)port;
        var first=diagnostic("batch-first","one");
        var second=diagnostic("batch-second","two");
        var committed=jdbc.appendDiagnosticBatch(List.of(first,second));
        assertEquals(List.of(1L,2L),committed.stream().map(CommittedSessionEvent::sessionSequence).toList());
        assertEquals(committed,jdbc.appendDiagnosticBatch(List.of(first,second)));
        var fresh=diagnostic("batch-rolled-back","fresh");
        assertThrows(IllegalStateException.class,()->jdbc.appendDiagnosticBatch(List.of(fresh,diagnostic("batch-second","different"))));
        assertTrue(jdbc.findByEvent(OWNER,fresh.event().eventId()).isEmpty());
        assertEquals(2,jdbc.readAfter(new ReadSessionLog(OWNER,0,10)).size());
    }

    @Test
    void fixedThreeEventWorkloadUsesOneConnectionAndFourteenStatementsInsteadOfThreeAndThirty() {
        var counter = new StatementCounter(applicationDataSource());
        var jdbc = new JdbcSessionLogPort(counter.source());
        var scalarOwner = owner("count-scalar");
        var batchOwner = owner("count-batch");
        // 相同正文工作量属于不同owner；Run及其全局引用必须拥有各自合法身份。
        java.util.function.BiFunction<SessionOwner,Integer,AppendSessionEvent> counterCommand=(owner,index)->{
            var original=command(owner,"count-"+index,NANO_INSTANT.plusNanos(index));
            var target=original.target();var tenant=owner.sessionRef().tenantId();
            String scoped=owner.sessionRef().sessionId()+"-"+index;
            return new AppendSessionEvent(new SessionProjectionTarget(owner,target.agentId(),target.inputId(),
                    Optional.of(new RunRef(tenant,"run-"+scoped)),
                    Optional.of(new ExecutionRef(tenant,"execution-"+scoped)),
                    Optional.of(new ActionRef(tenant,"action-"+scoped)),
                    Optional.of(new DelegationRef(tenant,"delegation-"+scoped))),original.event());
        };
        var scalar = java.util.stream.IntStream.range(0, 3).mapToObj(index -> counterCommand.apply(scalarOwner,index)).toList();
        var batch = java.util.stream.IntStream.range(0, 3).mapToObj(index -> counterCommand.apply(batchOwner,index)).toList();

        var scalarEvents = scalar.stream().map(command -> assertInstanceOf(AppendResult.Appended.class, jdbc.append(command)).event()).toList();
        int scalarConnections=counter.connections,scalarCommits=counter.commits,
                scalarExecutions=counter.executions,scalarLocks=counter.locks;
        counter.reset();
        var batchEvents = jdbc.appendBatch(batch).stream().map(result -> assertInstanceOf(AppendResult.Appended.class, result).event()).toList();
        // 两段真实写入均完成后发布固定字段测量，再断言；不让旧scalar计数遮蔽batch实际值。
        System.out.println("S03_LOG_STATEMENT_MEASURE "+RuntimeJson.writeCanonical(Map.of(
                "scalar",Map.of("connections",scalarConnections,"commits",scalarCommits,
                        "executions",scalarExecutions,"locks",scalarLocks,"events",scalarEvents.size()),
                "batch",Map.of("connections",counter.connections,"commits",counter.commits,
                        "executions",counter.executions,"locks",counter.locks,"events",batchEvents.size()))));
        assertEquals(3, scalarConnections);
        assertEquals(3, scalarCommits);
        assertEquals(30, scalarExecutions);
        assertEquals(6, scalarLocks);
        assertEquals(1, counter.connections);
        assertEquals(1, counter.commits);
        assertEquals(14, counter.executions);
        assertEquals(2, counter.locks);
        assertEquals(scalarEvents.stream().map(CommittedSessionEvent::sessionSequence).toList(),
                batchEvents.stream().map(CommittedSessionEvent::sessionSequence).toList());
        assertEquals(scalarEvents.stream().map(CommittedSessionEvent::payload).toList(),
                batchEvents.stream().map(CommittedSessionEvent::payload).toList());
        assertEquals(List.of(1L, 2L, 3L), batchEvents.stream().map(CommittedSessionEvent::sessionSequence).toList());
    }

    @Test
    void batchTechnicalFailureRollsBackEveryEventAndSequence() throws Exception {
        var first = command(OWNER, "rollback-batch-first", NANO_INSTANT);
        var second = command(OWNER, "rollback-batch-second", NANO_INSTANT.plusNanos(1));
        installEventInsertFailure();
        try {
            var failure = assertThrows(IllegalStateException.class, () -> port.appendBatch(List.of(first, second)));
            assertEquals("session_log_storage_failure", failure.getMessage());
            assertTrue(hasCauseMessage(failure, "forced session event insert failure"));
            assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_session_streams"));
            assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_session_events"));
        } finally { removeEventInsertFailure(); }
        assertEquals(List.of(1L, 2L), port.appendBatch(List.of(first, second)).stream()
                .map(result -> assertInstanceOf(AppendResult.Appended.class, result).event().sessionSequence()).toList());
    }

    /** 只统计应用显式 JDBC 执行，不把 BEGIN/commit 或服务器锁等待推算为耗时。 */
    private static final class StatementCounter {
        private final DataSource delegate;
        int connections;
        int commits;
        int executions;
        int locks;
        StatementCounter(DataSource delegate) { this.delegate = delegate; }
        void reset() { connections = 0; commits = 0; executions = 0; locks = 0; }
        DataSource source() {
            return (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                var result = invoke(delegate, method, args);
                if (!method.getName().equals("getConnection")) return result;
                connections++;
                return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (connectionProxy, connectionMethod, connectionArgs) -> {
                    if (connectionMethod.getName().equals("commit")) commits++;
                    var statement = invoke(result, connectionMethod, connectionArgs);
                    if (!connectionMethod.getName().equals("prepareStatement")) return statement;
                    String sql = (String) connectionArgs[0];
                    return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (statementProxy, statementMethod, statementArgs) -> {
                        if (statementMethod.getName().startsWith("execute")) {
                            executions++;
                            if (sql.contains("FOR UPDATE")) locks++;
                        }
                        return invoke(statement, statementMethod, statementArgs);
                    });
                });
            });
        }
        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    private static AppendSessionEvent diagnostic(String id,String content) {
        var original=command(OWNER,id,NANO_INSTANT);
        var event=original.event();
        return new AppendSessionEvent(original.target(),new SessionEventDraft(event.eventId(),"agent.runtime-diagnostic","1",
                "diagnostic:"+id+":"+content,event.trace(),new TypedPayload("application/json","\""+content+"\""),event.occurredAt()));
    }

    @Test
    void eventInsertFailureRollsBackStreamSequenceAndEventAtomically() throws Exception {
        AppendSessionEvent command = command(OWNER, "rollback", NANO_INSTANT);
        installEventInsertFailure();
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> port.append(command));
            assertEquals("session_log_storage_failure", failure.getMessage());
            assertNotNull(failure.getCause(), "storage failure must retain its technical cause");
            assertTrue(hasCauseMessage(failure, "forced session event insert failure"),
                    "storage failure cause chain must retain the triggering database error");
            assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_session_streams"));
            assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_session_events"));
        } finally {
            removeEventInsertFailure();
        }

        CommittedSessionEvent appended = assertInstanceOf(
                AppendResult.Appended.class, port.append(command)).event();
        assertEquals(1, appended.sessionSequence());
        assertEquals(2, adminCount("SELECT next_sequence"
                + " FROM public.agent_runtime_session_streams"
                + " WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-jdbc'"));
    }

    @Test
    void latestAndRunFilteredReadsKeepFullOwnerBoundaryAndOriginalSequence() {
        var first = assertInstanceOf(AppendResult.Appended.class, port.append(command(OWNER, "filtered-first", NANO_INSTANT))).event();
        var second = assertInstanceOf(AppendResult.Appended.class, port.append(command(OWNER, "filtered-second", NANO_INSTANT.plusNanos(1)))).event();
        assertEquals(Optional.of(second), port.latest(OWNER));
        assertEquals(List.of(first), port.readAfter(new ReadSessionLog(OWNER, 0, 10), first.runRef().orElseThrow(), java.util.Set.of(first.eventType())));
        assertEquals(List.of(), port.readAfter(new ReadSessionLog(OWNER, first.sessionSequence(), 10), first.runRef().orElseThrow(), java.util.Set.of(first.eventType())));
        assertEquals(List.of(), port.readAfter(new ReadSessionLog(OWNER, 0, 10), second.runRef().orElseThrow(), java.util.Set.of("other-type")));
        var wrongUser = SessionOwner.user(OWNER.sessionRef(), OWNER.target(), new EndUserId(OWNER.requireUser().value() + 1));
        assertTrue(port.latest(wrongUser).isEmpty());
        assertTrue(port.readAfter(new ReadSessionLog(wrongUser, 0, 10), first.runRef().orElseThrow(), java.util.Set.of(first.eventType())).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> port.readAfter(new ReadSessionLog(OWNER, 0, 10),
                new RunRef(new TenantId("another-tenant"), first.runRef().orElseThrow().runId()), java.util.Set.of(first.eventType())));
    }

    @Test
    void selectionFilterPagesMixedEventsAndKeepsFullOwnerBoundary() {
        var selectionTypes = java.util.Set.of("plan/selection");
        var otherAgent = SessionOwner.user(OWNER.sessionRef(), new AgentId("another-agent"), OWNER.requireUser());
        var otherSession = owner("another-filtered-session");
        var otherTenant = SessionOwner.user(new SessionRef(new TenantId("tenant-other"), OWNER.sessionRef().sessionId()),
                OWNER.target(), OWNER.requireUser());
        var wrongUser = SessionOwner.user(OWNER.sessionRef(), OWNER.target(), new EndUserId(708));

        port.append(filteredEvent(OWNER, "mixed-accepted", "execution.accepted"));
        var first = assertInstanceOf(AppendResult.Appended.class,
                port.append(filteredEvent(OWNER, "selection-first", "plan/selection"))).event();
        port.append(filteredEvent(OWNER, "mixed-mode", "plan/mode"));
        var second = assertInstanceOf(AppendResult.Appended.class,
                port.append(filteredEvent(OWNER, "selection-second", "plan/selection"))).event();
        port.append(filteredEvent(OWNER, "mixed-completed", "execution.completed"));
        var third = assertInstanceOf(AppendResult.Appended.class,
                port.append(filteredEvent(OWNER, "selection-third", "plan/selection"))).event();
        var otherAgentEvent = assertInstanceOf(AppendResult.Appended.class,
                port.append(filteredEvent(otherAgent, "selection-other-agent", "plan/selection"))).event();
        var otherSessionEvent = assertInstanceOf(AppendResult.Appended.class,
                port.append(filteredEvent(otherSession, "selection-other-session", "plan/selection"))).event();
        var otherTenantEvent = assertInstanceOf(AppendResult.Appended.class,
                port.append(filteredEvent(otherTenant, "selection-other-tenant", "plan/selection"))).event();

        var firstPage = port.readAfter(new ReadSessionLog(OWNER, 0, 2), selectionTypes);
        assertEquals(List.of(first, second), firstPage);
        assertEquals(List.of(2L, 4L), firstPage.stream().map(CommittedSessionEvent::sessionSequence).toList());
        var secondPage = port.readAfter(new ReadSessionLog(OWNER, second.sessionSequence(), 2), selectionTypes);
        assertEquals(List.of(third), secondPage);
        assertEquals(6L, third.sessionSequence());
        assertTrue(port.readAfter(new ReadSessionLog(OWNER, third.sessionSequence(), 2), selectionTypes).isEmpty());
        assertEquals(List.of(otherAgentEvent), port.readAfter(new ReadSessionLog(otherAgent, 0, 2), selectionTypes));
        assertEquals(List.of(otherSessionEvent), port.readAfter(new ReadSessionLog(otherSession, 0, 2), selectionTypes));
        assertEquals(List.of(otherTenantEvent), port.readAfter(new ReadSessionLog(otherTenant, 0, 2), selectionTypes));
        assertTrue(port.readAfter(new ReadSessionLog(wrongUser, 0, 2), selectionTypes).isEmpty());
    }

    private static AppendSessionEvent filteredEvent(SessionOwner owner, String id, String type) {
        var source = userSourceEvent(owner, id);
        var event = source.event();
        return new AppendSessionEvent(source.target(), new SessionEventDraft(event.eventId(), type, event.eventVersion(),
                event.eventFingerprint(), event.trace(), new TypedPayload("plan/selection".equals(type)
                        ? "skillforge.runtime.plan-selection.v1" : "skillforge.runtime.jdbc-test.v1",
                        "plan/selection".equals(type) ? "{\"active\":true}" : "{}"), event.occurredAt()));
    }

    @Test
    void firstInputSourceUsesOneIndexedQueryAndRetainsOriginalEnvelope() throws Exception {
        var first = assertInstanceOf(AppendResult.Appended.class,
                port.append(command(OWNER, "first-source", NANO_INSTANT))).event();
        port.append(command(OWNER, "another-run", NANO_INSTANT.plusNanos(1)));
        var counter = new StatementCounter(applicationDataSource());
        var jdbc = new JdbcSessionLogPort(counter.source());
        assertEquals(Optional.of(first), jdbc.findFirst(OWNER, first.inputId(), first.runRef().orElseThrow()));
        assertEquals(1, counter.connections);
        assertEquals(2, counter.executions); // 一次租户 GUC 绑定与一次首来源 SELECT。
        try (Connection connection = applicationDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET app.tenant_id = 'tenant-jdbc'");
            statement.execute("SET enable_seqscan = off");
            try (var query = connection.prepareStatement("""
                    EXPLAIN SELECT * FROM public.agent_runtime_session_events
                    WHERE tenant_id=? AND session_id=? AND target_kind=? AND target_id=? AND end_user_id=?
                      AND input_id=? AND run_id=? ORDER BY session_sequence ASC LIMIT 1
                    """)) {
                query.setString(1, TENANT.value()); query.setString(2, OWNER.sessionRef().sessionId());
                query.setString(3, OWNER.target().kind().name()); query.setString(4, OWNER.target().id());
                query.setLong(5, OWNER.requireUser().value()); query.setString(6, first.inputId().value());
                query.setString(7, first.runRef().orElseThrow().runId());
                var plan = new StringBuilder();
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) plan.append(rows.getString(1)); }
                assertTrue(plan.toString().contains("idx_session_events_input_first_source"), plan.toString());
                assertFalse(plan.toString().contains("Sort"), plan.toString());
            }
        }
    }

    @Test
    void newDataSourceAndAdapterPreciselyRecoverReplayAndContinueAppend() throws Exception {
        AppendSessionEvent original = command(OWNER, "restart-original", NANO_INSTANT);
        CommittedSessionEvent first = assertInstanceOf(
                AppendResult.Appended.class, port.append(original)).event();
        assertEquals(NANO_INSTANT, first.occurredAt());

        SessionLogPort restarted = new JdbcSessionLogPort(applicationDataSource());
        assertEquals(Optional.of(first), restarted.findByEvent(OWNER, original.event().eventId()));
        assertEquals(List.of(first), restarted.readAfter(new ReadSessionLog(OWNER, 0, 10)));
        assertEquals(first, assertInstanceOf(
                AppendResult.Replayed.class, restarted.append(original)).event());

        AppendSessionEvent continuation = command(
                OWNER, "restart-continuation", NANO_INSTANT.plusNanos(2));
        CommittedSessionEvent second = assertInstanceOf(
                AppendResult.Appended.class, restarted.append(continuation)).event();
        assertEquals(2, second.sessionSequence());
        assertEquals(continuation.event().occurredAt(), second.occurredAt());
        assertEquals(List.of(first, second),
                restarted.readAfter(new ReadSessionLog(OWNER, 0, 10)));

        try (Connection connection = adminConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT occurred_at_epoch_second, occurred_at_nano
                          FROM public.agent_runtime_session_events
                         WHERE tenant_id = ? AND session_id = ? AND event_id = ?
                           AND target_kind = 'AGENT' AND target_id = 'agent-jdbc'
                        """)) {
            statement.setString(1, TENANT.value());
            statement.setString(2, OWNER.sessionRef().sessionId());
            statement.setString(3, original.event().eventId().value());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(NANO_INSTANT.getEpochSecond(), rows.getLong(1));
                assertEquals(NANO_INSTANT.getNano(), rows.getInt(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void directRoleConnectionRequiresTransactionLocalTenantAndDoesNotLeakIt() throws Exception {
        port.append(command(OWNER, "rls-seed", NANO_INSTANT));

        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);

            assertEquals(0, visibleCount(connection, "agent_runtime_session_streams"));
            assertEquals(0, visibleCount(connection, "agent_runtime_session_events"));
            SQLException noTenant = assertThrows(SQLException.class,
                    () -> insertStream(connection, TENANT.value(), "session-no-tenant"));
            assertEquals("42501", noTenant.getSQLState());
            assertTrue(noTenant.getMessage().contains("row-level security policy"));
            connection.rollback();

            bindTenant(connection, "tenant-wrong");
            assertEquals(0, visibleCount(connection, "agent_runtime_session_streams"));
            assertEquals(0, visibleCount(connection, "agent_runtime_session_events"));
            SQLException wrongTenant = assertThrows(SQLException.class,
                    () -> insertStream(connection, TENANT.value(), "session-wrong-tenant"));
            assertEquals("42501", wrongTenant.getSQLState());
            assertTrue(wrongTenant.getMessage().contains("row-level security policy"));
            connection.rollback();

            bindTenant(connection, TENANT.value());
            assertEquals(1, visibleCount(connection, "agent_runtime_session_streams"));
            assertEquals(1, visibleCount(connection, "agent_runtime_session_events"));
            insertStream(connection, TENANT.value(), "session-correct-tenant");
            assertEquals(2, visibleCount(connection, "agent_runtime_session_streams"));
            connection.commit();

            String settingAfterCommit = currentTenantSetting(connection);
            assertTrue(settingAfterCommit == null || settingAfterCommit.isEmpty(),
                    "transaction-local tenant must not survive commit");
            assertEquals(0, visibleCount(connection, "agent_runtime_session_streams"));
            assertEquals(0, visibleCount(connection, "agent_runtime_session_events"));
            connection.rollback();
        }
    }

    @Test
    void schemaRejectsEventMutationAndStreamOwnerOrSequenceJumps() throws Exception {
        port.append(command(OWNER, "guard-seed", NANO_INSTANT));

        assertTriggerRejected("""
                UPDATE public.agent_runtime_session_events
                   SET event_type = 'tampered'
                 WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-jdbc'
                """, "agent runtime session event is append-only");
        assertTriggerRejected("""
                DELETE FROM public.agent_runtime_session_events
                 WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-jdbc'
                """, "session_history_event_type_forbidden");

        assertTenantUpdateRejected("""
                UPDATE public.agent_runtime_session_streams
                   SET target_id = 'agent-tampered'
                 WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-jdbc'
                """, "agent runtime session owner is immutable");
        assertTenantUpdateRejected("""
                UPDATE public.agent_runtime_session_streams
                   SET next_sequence = next_sequence + 2
                 WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-jdbc'
                """, "agent runtime session sequence must advance by one");

        CommittedSessionEvent next = assertInstanceOf(
                AppendResult.Appended.class,
                port.append(command(OWNER, "after-guard-rejections", NANO_INSTANT.plusSeconds(1))))
                .event();
        assertEquals(2, next.sessionSequence());
    }

    @Test
    void schemaRejectsSequenceGapBeforeItCanReachTheAllocator() throws Exception {
        String sessionId = "session-gap";
        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            bindTenant(connection, TENANT.value());
            try {
                insertStream(connection, TENANT.value(), sessionId);
                SQLException failure = assertThrows(SQLException.class,
                        () -> insertDirectEvent(connection, sessionId, 3));
                assertEquals("P0001", failure.getSQLState());
                assertTrue(failure.getMessage().contains(
                        "agent runtime session event must use next sequence"));
            } finally {
                connection.rollback();
            }
        }

        assertEquals(0, adminCount("SELECT count(*)"
                + " FROM public.agent_runtime_session_streams"
                + " WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-gap'"));
        assertEquals(0, adminCount("SELECT count(*)"
                + " FROM public.agent_runtime_session_events"
                + " WHERE tenant_id = 'tenant-jdbc' AND session_id = 'session-gap'"));
    }

    @Test
    void schemaRequiresMatchingEventAndAdvanceInTheSameTransaction() throws Exception {
        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            bindTenant(connection, TENANT.value());
            try {
                insertStream(connection, TENANT.value(), "session-advance-without-event");
                SQLException failure = assertThrows(SQLException.class,
                        () -> advanceDirectStream(connection, "session-advance-without-event"));
                assertEquals("P0001", failure.getSQLState());
                assertTrue(failure.getMessage().contains(
                        "agent runtime session advance requires its event"));
            } finally {
                connection.rollback();
            }
        }

        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            bindTenant(connection, TENANT.value());
            try {
                insertStream(connection, TENANT.value(), "session-event-without-advance");
                insertDirectEvent(connection, "session-event-without-advance", 1);
                SQLException failure = assertThrows(SQLException.class, connection::commit);
                assertEquals("P0001", failure.getSQLState());
                assertTrue(failure.getMessage().contains(
                        "agent runtime session event was not advanced"));
            } finally {
                connection.rollback();
            }
        }

        assertEquals(0, adminCount("SELECT count(*)"
                + " FROM public.agent_runtime_session_streams"
                + " WHERE session_id IN ('session-advance-without-event',"
                + " 'session-event-without-advance')"));
        assertEquals(0, adminCount("SELECT count(*)"
                + " FROM public.agent_runtime_session_events"
                + " WHERE session_id IN ('session-advance-without-event',"
                + " 'session-event-without-advance')"));
    }

    @Test
    void schemaRejectsOccurredAtOutsideJavaInstantRange() throws Exception {
        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            bindTenant(connection, TENANT.value());
            try {
                insertStream(connection, TENANT.value(), "session-invalid-time");
                SQLException failure = assertThrows(SQLException.class,
                        () -> insertDirectEvent(
                                connection, "session-invalid-time", 1, Long.MAX_VALUE));
                assertEquals("23514", failure.getSQLState());
                assertTrue(failure.getMessage().contains(
                        "ck_agent_runtime_session_event_occurred_at_epoch"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void noteAcceptanceQueriesReturnNoAnomalies() throws Exception {
        port.append(command(OWNER, "acceptance-first", NANO_INSTANT));
        port.append(command(OWNER, "acceptance-second", NANO_INSTANT.plusNanos(1)));
        port.append(command(owner("session-acceptance-other"),
                "acceptance-other", NANO_INSTANT.plusNanos(2)));

        List<String> acceptanceQueries = List.of(
                """
                SELECT tenant_id, target_kind, target_id, session_id, event_id
                  FROM agent_runtime_session_events
                 GROUP BY tenant_id, target_kind, target_id, session_id, event_id
                HAVING count(*) <> 1
                """,
                """
                SELECT tenant_id, target_kind, target_id, session_id, session_sequence
                  FROM agent_runtime_session_events
                 GROUP BY tenant_id, target_kind, target_id, session_id, session_sequence
                HAVING count(*) <> 1
                """,
                """
                SELECT s.tenant_id, s.target_kind, s.target_id, s.session_id
                  FROM agent_runtime_session_streams s
                  LEFT JOIN agent_runtime_session_events e USING (tenant_id, target_kind, target_id, session_id)
                 GROUP BY s.tenant_id, s.target_kind, s.target_id, s.session_id, s.next_sequence
                HAVING s.next_sequence <> COALESCE(max(e.session_sequence), 0) + 1
                    OR COALESCE(max(e.session_sequence), 0) <> count(e.session_sequence)
                """);

        try (Connection connection = adminConnection()) {
            for (String query : acceptanceQueries) {
                try (Statement statement = connection.createStatement();
                        ResultSet anomalies = statement.executeQuery(query)) {
                    assertFalse(anomalies.next(), "acceptance query returned an anomalous row: " + query);
                }
            }
        }
    }

    private static SessionOwner owner(String sessionId) {
        return SessionOwner.user(
                new SessionRef(TENANT, sessionId),
                new AgentId("agent-jdbc"),
                new EndUserId(707));
    }

    /** 使用原Run codec和正式owner/input/run FK创建合法来源；append/read仍由受限业务连接执行。 */
    /** 两个同owner/operation/attempt快照拥有独立版本/游标，完整累计正文由正式canonical函数恢复。 */
    private static AppendSessionEvent command(
            SessionOwner owner, String suffix, Instant occurredAt) {
        TenantId tenant = owner.sessionRef().tenantId();
        SessionProjectionTarget target = new SessionProjectionTarget(
                owner,
                new InputId("input-" + suffix),
                Optional.of(new RunRef(tenant, "run-" + suffix)),
                Optional.of(new ExecutionRef(tenant, "execution-" + suffix)),
                Optional.of(new ActionRef(tenant, "action-" + suffix)),
                Optional.of(new DelegationRef(tenant, "delegation-" + suffix)));
        SessionEventDraft event = new SessionEventDraft(
                new EventId("event-" + suffix),
                "session.jdbc-test",
                "1",
                "fingerprint-" + suffix,
                new TraceEnvelope(
                        "trace-" + suffix,
                        "span-" + suffix,
                        new InputId("root-input-" + suffix),
                        Optional.of(new EventId("cause-" + suffix))),
                new TypedPayload(
                        "skillforge.runtime.jdbc-test.v1",
                        "{\"emoji\":\"🧭\",\"event\":\"" + suffix + "\"}"),
                occurredAt);
        return new AppendSessionEvent(target, event);
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(),adminUser(),adminPassword());
    }

    private static DataSource applicationDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(jdbcUrl());
        dataSource.setUser(EXTERNAL?external("DB_USER"):APP_ROLE);
        dataSource.setPassword(EXTERNAL?external("DB_PASSWORD"):APP_PASSWORD);
        return dataSource;
    }

    /** 每例清空log/input/body及Run后代；保留不可变owner，避免owner根全schema扇出。 */
    private static void resetDatabase() {
        try {
            removeEventInsertFailure();
            try (Connection connection = adminConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE public.agent_runtime_session_events,"
                        + " public.agent_runtime_session_streams,"
                        + " public.agent_runtime_input_ledger,"
                        + " public.agent_runtime_session_event_bodies CASCADE");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("session_log_test_reset_failure", failure);
        }
    }

    /** 仅父新建runtime_log lane可消费完整契约套件，guard阻止旧库或已运行fixture被重置。 */
    private static void verifyExternalFixture(Connection connection) throws SQLException {
        assertEquals("runtime_log",external("FIXTURE_LANE"));
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_database()")){assertTrue(result.next());assertTrue(result.getString(1).matches("sf_s03_runtime_log_[a-z0-9_]+"));}
        try(var statement=connection.prepareStatement("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane='runtime_log' AND token=?")){statement.setString(1,external("FIXTURE_TOKEN"));try(var result=statement.executeQuery()){assertTrue(result.next());assertEquals(1,result.getLong(1));}}
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT count(*) FROM public.agent_runtime_session_events")){assertTrue(result.next());assertEquals(0,result.getLong(1),"只能首次消费新lane，不自动重置旧runtime事件");}
    }
    /** 任一外部参数存在后必须完整，缺值异常只输出键名。 */
    private static String external(String suffix){String name="SKILLFORGE_S03_EXTERNAL_"+suffix;String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少外部fixture参数 "+name);return value;}
    /** 当前lane连接，不从旧candidate或默认生产配置取值。 */
    private static String jdbcUrl(){return EXTERNAL?external("JDBC_URL"):POSTGRES.getJdbcUrl();}
    /** 受控fixture owner，不能用于被测append/read。 */
    private static String adminUser(){return EXTERNAL?external("ADMIN_USER"):POSTGRES.getUsername();}
    /** 受控环境密码，不打印或写入材料。 */
    private static String adminPassword(){return EXTERNAL?external("ADMIN_PASSWORD"):POSTGRES.getPassword();}

    private static void installEventInsertFailure() throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION public.fail_agent_runtime_session_event_insert()
                    RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'forced session event insert failure';
                    END;
                    $$
                    """);
            statement.execute("CREATE TRIGGER " + INSERT_FAILURE_TRIGGER
                    + " AFTER INSERT ON public.agent_runtime_session_events"
                    + " FOR EACH ROW EXECUTE FUNCTION " + INSERT_FAILURE_FUNCTION + "()");
        }
    }

    private static void removeEventInsertFailure() throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS " + INSERT_FAILURE_TRIGGER
                    + " ON public.agent_runtime_session_events");
            statement.execute("DROP FUNCTION IF EXISTS " + INSERT_FAILURE_FUNCTION + "()");
        }
    }

    private static long adminCount(String sql) throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void bindTenant(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(tenantId, result.getString(1));
            }
        }
    }

    private static long visibleCount(Connection connection, String table) throws SQLException {
        if (!table.equals("agent_runtime_session_streams")
                && !table.equals("agent_runtime_session_events")) {
            throw new IllegalArgumentException("unexpected table: " + table);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM public." + table + " WHERE tenant_id = ?")) {
            statement.setString(1, TENANT.value());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static void insertStream(
            Connection connection, String tenantId, String sessionId) throws SQLException {
        try (PreparedStatement owner = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_owners
                    (tenant_id, session_id, target_kind, target_id, agent_id, end_user_id)
                VALUES (?, ?, 'AGENT', 'agent-direct', 'agent-direct', 808)
                ON CONFLICT DO NOTHING
                """)) {
            owner.setString(1, tenantId);
            owner.setString(2, sessionId);
            owner.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_streams
                    (tenant_id, session_id, target_kind, target_id, agent_id, end_user_id, next_sequence)
                VALUES (?, ?, 'AGENT', 'agent-direct', 'agent-direct', 808, 1)
                """)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
    }

    private static void insertDirectEvent(
            Connection connection, String sessionId, long sequence) throws SQLException {
        insertDirectEvent(connection, sessionId, sequence, NANO_INSTANT.getEpochSecond());
    }

    private static void insertDirectEvent(
            Connection connection, String sessionId, long sequence, long epochSecond)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_events (
                    tenant_id, session_id, target_kind, target_id, agent_id, end_user_id, session_sequence,
                    input_id, event_id, event_fingerprint, event_type, event_version,
                    trace_id, span_id, root_input_id,
                    payload_schema_id, payload_canonical_json,
                    occurred_at_epoch_second, occurred_at_nano)
                VALUES (?, ?, 'AGENT', 'agent-direct', 'agent-direct', 808, ?, ?, ?, ?, 'session.direct-test', '1',
                        ?, ?, ?, 'skillforge.runtime.direct-test.v1', '{}', ?, ?)
                """)) {
            statement.setString(1, TENANT.value());
            statement.setString(2, sessionId);
            statement.setLong(3, sequence);
            statement.setString(4, "input-gap-" + sequence);
            statement.setString(5, "event-gap-" + sequence);
            statement.setString(6, "fingerprint-gap-" + sequence);
            statement.setString(7, "trace-gap-" + sequence);
            statement.setString(8, "span-gap-" + sequence);
            statement.setString(9, "root-input-gap-" + sequence);
            statement.setLong(10, epochSecond);
            statement.setInt(11, NANO_INSTANT.getNano());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void advanceDirectStream(Connection connection, String sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE public.agent_runtime_session_streams
                   SET next_sequence = next_sequence + 1
                 WHERE tenant_id = ? AND session_id = ?
                   AND target_kind = 'AGENT' AND target_id = 'agent-direct'
                """)) {
            statement.setString(1, TENANT.value());
            statement.setString(2, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String currentTenantSetting(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT current_setting('app.tenant_id', true)")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean hasCauseMessage(Throwable failure, String expectedMessage) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(expectedMessage)) {
                return true;
            }
        }
        return false;
    }

    private static void assertTriggerRejected(String sql, String expectedMessage) throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertEquals("P0001", failure.getSQLState());
            assertTrue(failure.getMessage().contains(expectedMessage),failure.getMessage());
        }
    }

    private static void assertTenantUpdateRejected(
            String sql, String expectedMessage) throws SQLException {
        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            bindTenant(connection, TENANT.value());
            try (Statement statement = connection.createStatement()) {
                SQLException failure = assertThrows(
                        SQLException.class, () -> statement.executeUpdate(sql));
                assertEquals("P0001", failure.getSQLState());
                assertTrue(failure.getMessage().contains(expectedMessage),failure.getMessage());
            } finally {
                connection.rollback();
            }
        }
    }
}
