package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.testkit.SessionStorePortContract;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunTarget;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.SessionStorePort.ChangeSessionStatus;
import com.skillforge.runtime.api.port.SessionStorePort.CreateSession;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCasResult;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCreateResult;
import com.skillforge.runtime.api.port.SessionStorePort.SessionLookup;
import com.skillforge.runtime.api.port.SessionStorePort.SessionStatus;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** V96 SessionStore 在 PostgreSQL 16、owner root 与强制 RLS 下的验收。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcSessionStorePortTest extends SessionStorePortContract {

    private static final String APP_ROLE = "agent_runtime_session_store_test";
    private static final String APP_PASSWORD = "session_store_test_password";
    private static final TenantId TENANT = new TenantId("tenant-store-jdbc");

    private static final List<String> ALL_TABLES = List.of(
            "agent_runtime_session_conflict_audits",
            "agent_runtime_session_mutations",
            "agent_runtime_sessions",
            "agent_runtime_run_publication_mutations",
            "agent_runtime_run_state_publications",
            "agent_runtime_run_mutations",
            "agent_runtime_session_checkpoints",
            "agent_runtime_runs",
            "agent_runtime_inbox_mutations",
            "agent_runtime_session_inbox",
            "agent_runtime_input_ledger",
            "agent_runtime_session_input_streams",
            "agent_runtime_session_events",
            "agent_runtime_session_streams",
            "agent_runtime_session_owners");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge")
                    .withUsername("skillforge_admin")
                    .withPassword("skillforge_admin_password");

    @BeforeAll
    static void initializeDatabase() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {

            statement.execute("CREATE ROLE " + APP_ROLE
                    + " LOGIN PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.grantPersistenceReads(statement, APP_ROLE);
            // 当前Session owner一致性读取typed Run；仅补该读取依赖，仍由原RLS隔离。
            statement.execute("GRANT SELECT ON public.agent_runtime_execution_runs TO " + APP_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON "
                    + qualifiedTables() + " TO " + APP_ROLE);
        }
    }

    @Override
    protected SessionStoreFixture createFixture(Instant initialTime) {
        resetDatabase();
        SessionStorePort store = new JdbcSessionStorePort(applicationDataSource());
        SessionLogPort log = new JdbcSessionLogPort(applicationDataSource());
        return new SessionStoreFixture() {
            @Override
            public SessionStorePort store() {
                return store;
            }

            @Override
            public SessionLogPort log() {
                return log;
            }

            @Override
            public Instant storageTime() {
                return databaseTime();
            }

            @Override
            public void advance(Duration duration) {
                awaitDatabaseAdvance(duration);
            }
        };
    }

    @Test
    void twoAdaptersSerializeCreateAndCasFirstWinners() throws Exception {
        SessionOwner owner = owner("first-winner", 701);
        JdbcSessionStorePort first = new JdbcSessionStorePort(applicationDataSource());
        JdbcSessionStorePort second = new JdbcSessionStorePort(applicationDataSource());
        CreateSession personaA = create(owner, "persona-a", "create-a");
        CreateSession personaB = create(owner, "persona-b", "create-b");

        List<Object> creates = race(() -> first.create(personaA), () -> second.create(personaB));
        assertEquals(1, creates.stream().filter(SessionCreateResult.Created.class::isInstance).count());
        assertEquals(1, creates.stream().filter(SessionCreateResult.Conflict.class::isInstance).count());

        SessionStorePort.SessionRecord current = first.find(new SessionLookup(owner)).orElseThrow();
        ChangeSessionStatus activationA = activate(owner, current.sessionRevision(), "cas-a");
        ChangeSessionStatus activationB = activate(owner, current.sessionRevision(), "cas-b");
        List<Object> mutations = race(
                () -> first.compareAndSet(activationA),
                () -> second.compareAndSet(activationB));
        assertEquals(1, mutations.stream().filter(SessionCasResult.Applied.class::isInstance).count());
        assertEquals(1, mutations.stream().filter(SessionCasResult.Conflict.class::isInstance).count());
        assertEquals(2, first.find(new SessionLookup(owner)).orElseThrow().sessionRevision());
        assertEquals(1, adminCount(
                "SELECT count(*) FROM public.agent_runtime_session_mutations"));
    }

    @Test
    void concurrentLogAndStoreFirstWritesCannotSplitOwners() throws Exception {
        SessionOwner storeOwner = owner("cross-domain", 801);
        SessionOwner logOwner = owner("cross-domain", 802);
        JdbcSessionStorePort store = new JdbcSessionStorePort(applicationDataSource());
        JdbcSessionLogPort log = new JdbcSessionLogPort(applicationDataSource());

        List<Object> results = race(
                () -> store.create(create(storeOwner, "persona-store", "store")),
                () -> log.append(logCommand(logOwner, "log")));

        long successes = results.stream().filter(result ->
                result instanceof SessionCreateResult.Created
                        || result instanceof SessionLogPort.AppendResult.Appended).count();
        long conflicts = results.stream().filter(result ->
                result instanceof SessionCreateResult.Conflict
                        || result instanceof SessionLogPort.AppendResult.Conflict).count();
        assertEquals(1, successes);
        assertEquals(1, conflicts);
        assertEquals(1, adminCount(
                "SELECT count(*) FROM public.agent_runtime_session_owners"
                        + " WHERE tenant_id = 'tenant-store-jdbc'"
                        + " AND session_id = 'cross-domain'"));
        assertEquals(0, adminCount("""
                SELECT count(*)
                  FROM public.agent_runtime_sessions session_record
                  JOIN public.agent_runtime_session_streams stream
                    USING (tenant_id, target_kind, target_id, session_id)
                 WHERE session_record.end_user_id <> stream.end_user_id
                """));
    }

    @Test
    void lastSequenceTracksOnlyTheCommittedSessionLog() {
        SessionOwner owner = owner("sequence-source", 901);
        var store = new JdbcSessionStorePort(applicationDataSource());
        created(store.create(create(owner, "persona", "sequence")));
        assertEquals(0, store.find(new SessionLookup(owner)).orElseThrow().lastSequence());
        var log = new JdbcSessionLogPort(applicationDataSource());
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                log.append(logCommand(owner, "authoritative")));
        assertEquals(1, store.find(new SessionLookup(owner)).orElseThrow().lastSequence());
    }

    @Test
    void restrictedRoleUsesTransactionLocalTenantAndStoreTablesForceRls()
            throws Exception {
        SessionOwner owner = owner("rls", 1001);
        new JdbcSessionStorePort(applicationDataSource()).create(
                create(owner, "persona", "rls"));

        try (Connection admin = adminConnection();
                PreparedStatement role = admin.prepareStatement(
                        "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            role.setString(1, APP_ROLE);
            try (ResultSet result = role.executeQuery()) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1));
                assertFalse(result.getBoolean(2));
            }
        }
        assertEquals(4, adminCount("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname IN (
                       'agent_runtime_session_owners',
                       'agent_runtime_sessions',
                       'agent_runtime_session_mutations',
                       'agent_runtime_session_conflict_audits')
                   AND relrowsecurity AND relforcerowsecurity
                """));

        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(0, queryCount(connection,
                    "SELECT count(*) FROM public.agent_runtime_sessions"));
            bindTenant(connection, "tenant-wrong");
            assertEquals(0, queryCount(connection,
                    "SELECT count(*) FROM public.agent_runtime_sessions"));
            connection.rollback();
            bindTenant(connection, TENANT.value());
            assertEquals(1, queryCount(connection,
                    "SELECT count(*) FROM public.agent_runtime_sessions"));
            connection.commit();
            assertEquals(0, queryCount(connection,
                    "SELECT count(*) FROM public.agent_runtime_sessions"));
            connection.rollback();
        }
    }

    @Test
    void freshAdaptersRecoverCommandsAuditAndLiveSequence() throws Exception {
        SessionOwner owner = owner("restart", 1101);
        CreateSession create = create(owner, "persona", "restart");
        JdbcSessionStorePort first = new JdbcSessionStorePort(applicationDataSource());
        created(first.create(create));
        SessionCreateResult.Conflict conflict = assertInstanceOf(
                SessionCreateResult.Conflict.class,
                first.create(create(owner, "persona-drift", "restart-drift")));
        ChangeSessionStatus activation = activate(owner, 1, "restart-activate");
        assertInstanceOf(SessionCasResult.Applied.class, first.compareAndSet(activation));
        new JdbcSessionLogPort(applicationDataSource()).append(logCommand(owner, "restart-event"));

        JdbcSessionStorePort restarted = new JdbcSessionStorePort(applicationDataSource());
        assertEquals(1, assertInstanceOf(
                SessionCreateResult.Replayed.class, restarted.create(create))
                .session().lastSequence());
        assertEquals(1, assertInstanceOf(
                SessionCasResult.Replayed.class, restarted.compareAndSet(activation))
                .session().lastSequence());
        assertEquals(conflict.auditRef(), assertInstanceOf(
                SessionCreateResult.Conflict.class,
                restarted.create(create(owner, "persona-drift", "restart-drift")))
                .auditRef());
        assertEquals(1, adminCount("""
                SELECT count(*)
                  FROM public.agent_runtime_sessions
                 WHERE create_command_data IS NOT NULL
                """));
        assertEquals(1, adminCount("""
                SELECT count(*)
                  FROM public.agent_runtime_session_mutations
                 WHERE command_data IS NOT NULL
                """));
        assertEquals(create, SessionPersistenceCodec.decode(
                adminText("SELECT create_command_data FROM public.agent_runtime_sessions"
                        + " WHERE session_id = 'restart'"), CreateSession.class));
        assertEquals(activation, SessionPersistenceCodec.decode(
                adminText("SELECT command_data FROM public.agent_runtime_session_mutations"
                        + " WHERE session_id = 'restart'"), ChangeSessionStatus.class));
    }

    @Test
    void sessionMutationAndConflictAuditFailuresRollbackAtomically() throws Exception {
        SessionOwner createOwner = owner("rollback-create", 1201);
        installInsertFailure(
                "agent_runtime_sessions", "session_store_fail_session_insert");
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> new JdbcSessionStorePort(applicationDataSource()).create(
                            create(createOwner, "persona", "rollback-create")));
            assertEquals("session_store_storage_failure", failure.getMessage());
            assertTrue(hasCauseMessage(failure, "forced agent_runtime_sessions insert failure"));
            assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_session_owners"));
            assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_sessions"));
        } finally {
            removeInsertFailure(
                    "agent_runtime_sessions", "session_store_fail_session_insert");
        }

        JdbcSessionStorePort store = new JdbcSessionStorePort(applicationDataSource());
        SessionOwner mutationOwner = owner("rollback-mutation", 1202);
        created(store.create(create(mutationOwner, "persona", "rollback-mutation")));
        installInsertFailure(
                "agent_runtime_session_mutations", "session_store_fail_mutation_insert");
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> store.compareAndSet(activate(mutationOwner, 1, "rollback-mutation")));
            assertEquals("session_store_storage_failure", failure.getMessage());
            assertTrue(hasCauseMessage(
                    failure, "forced agent_runtime_session_mutations insert failure"));
            assertEquals(1, store.find(new SessionLookup(mutationOwner))
                    .orElseThrow().sessionRevision());
            assertEquals(0, adminCount(
                    "SELECT count(*) FROM public.agent_runtime_session_mutations"));
        } finally {
            removeInsertFailure(
                    "agent_runtime_session_mutations", "session_store_fail_mutation_insert");
        }

        installInsertFailure(
                "agent_runtime_session_conflict_audits", "session_store_fail_audit_insert");
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> store.create(create(
                            mutationOwner, "persona-drift", "rollback-conflict")));
            assertEquals("session_store_storage_failure", failure.getMessage());
            assertTrue(hasCauseMessage(
                    failure, "forced agent_runtime_session_conflict_audits insert failure"));
            assertEquals(0, adminCount(
                    "SELECT count(*) FROM public.agent_runtime_session_conflict_audits"));
            assertEquals(1, store.find(new SessionLookup(mutationOwner))
                    .orElseThrow().sessionRevision());
        } finally {
            removeInsertFailure(
                    "agent_runtime_session_conflict_audits", "session_store_fail_audit_insert");
        }
    }

    @Test
    void lostCommitResponsesReplayThroughFreshAdaptersWithoutDuplicateEffects() {
        SessionOwner createOwner = owner("lost-create", 1301);
        CreateSession create = create(createOwner, "persona", "lost-create");
        IllegalStateException createFailure = assertThrows(
                IllegalStateException.class,
                () -> new JdbcSessionStorePort(
                        new CommitFailureDataSource(applicationDataSource())).create(create));
        assertEquals("session_store_storage_failure", createFailure.getMessage());
        assertTrue(hasCauseMessage(createFailure, "forced post-commit response loss"));

        JdbcSessionStorePort recovered = new JdbcSessionStorePort(applicationDataSource());
        assertInstanceOf(SessionCreateResult.Replayed.class, recovered.create(create));
        assertEquals(1, adminCount("SELECT count(*) FROM public.agent_runtime_sessions"
                + " WHERE session_id = 'lost-create'"));

        SessionOwner casOwner = owner("lost-cas", 1302);
        created(recovered.create(create(casOwner, "persona", "lost-cas-seed")));
        ChangeSessionStatus activation = activate(casOwner, 1, "lost-cas");
        IllegalStateException casFailure = assertThrows(
                IllegalStateException.class,
                () -> new JdbcSessionStorePort(
                        new CommitFailureDataSource(applicationDataSource()))
                        .compareAndSet(activation));
        assertEquals("session_store_storage_failure", casFailure.getMessage());
        assertTrue(hasCauseMessage(casFailure, "forced post-commit response loss"));

        JdbcSessionStorePort restarted = new JdbcSessionStorePort(applicationDataSource());
        assertInstanceOf(SessionCasResult.Replayed.class,
                restarted.compareAndSet(activation));
        assertEquals(2, restarted.find(new SessionLookup(casOwner))
                .orElseThrow().sessionRevision());
        assertEquals(1, adminCount("SELECT count(*)"
                + " FROM public.agent_runtime_session_mutations"
                + " WHERE session_id = 'lost-cas'"));
    }

    @Test
    void schemaGuardsLifecycleOwnerLinksAndJournalBidirectionally() {
        SessionOwner owner = owner("schema-guard", 1401);
        created(new JdbcSessionStorePort(applicationDataSource()).create(
                create(owner, "persona", "schema-guard")));

        assertSqlRejected("""
                UPDATE public.agent_runtime_sessions
                   SET channel = 'tampered'
                 WHERE session_id = 'schema-guard'
                """, "immutable facts changed");
        assertSqlRejected("""
                UPDATE public.agent_runtime_sessions
                   SET status = 'CLOSED', session_revision = 2
                 WHERE session_id = 'schema-guard'
                """, "illegal agent runtime session lifecycle transition");
        assertSqlRejected("""
                UPDATE public.agent_runtime_sessions
                   SET status = 'ACTIVE', session_revision = 2
                 WHERE session_id = 'schema-guard'
                """, "session lifecycle transition must commit its journal");
        assertSqlRejected("""
                INSERT INTO public.agent_runtime_session_mutations (
                    tenant_id, session_id, target_kind, target_id, end_user_id,
                    expected_status, target_status, expected_revision,
                    causation_input_id, command_fingerprint, command_data,
                    output_status, output_revision,
                    output_updated_epoch_second, output_updated_nano)
                SELECT tenant_id, session_id, target_kind, target_id, end_user_id,
                       'CREATED', 'ACTIVE', 1, 'input-orphan',
                       repeat('a', 64), 'orphan-command', 'ACTIVE', 2,
                       updated_epoch_second, updated_nano
                  FROM public.agent_runtime_sessions
                 WHERE session_id = 'schema-guard'
                """, "session mutation journal must match committed lifecycle output");
        assertSqlRejected("""
                INSERT INTO public.agent_runtime_session_streams
                    (tenant_id, session_id, target_kind, target_id, end_user_id, next_sequence)
                VALUES (
                    'tenant-store-jdbc', 'schema-guard',
                    'AGENT', 'agent-store-jdbc', 9999, 1)
                """, "domain_stream_subject_root_fk");

        assertEquals("CREATED", adminText("SELECT status"
                + " FROM public.agent_runtime_sessions"
                + " WHERE session_id = 'schema-guard'"));
        assertEquals(1, adminCount("SELECT session_revision"
                + " FROM public.agent_runtime_sessions"
                + " WHERE session_id = 'schema-guard'"));
        assertEquals(0, adminCount(
                "SELECT count(*) FROM public.agent_runtime_session_mutations"));
    }

    @Test
    void schemaAllowsOnlyOneFirstWinnerJournalPerLifecycleRevision() {
        SessionOwner owner = owner("journal-first-winner", 1451);
        JdbcSessionStorePort store = new JdbcSessionStorePort(applicationDataSource());
        created(store.create(create(owner, "persona", "journal-first-winner")));
        assertInstanceOf(SessionCasResult.Applied.class,
                store.compareAndSet(activate(owner, 1, "journal-first-winner")));

        assertSqlRejected("""
                INSERT INTO public.agent_runtime_session_mutations (
                    tenant_id, session_id, target_kind, target_id, end_user_id,
                    expected_status, target_status, expected_revision,
                    causation_input_id, command_fingerprint, command_data,
                    output_status, output_revision,
                    output_updated_epoch_second, output_updated_nano)
                SELECT tenant_id, session_id, target_kind, target_id, end_user_id,
                       expected_status, target_status, expected_revision,
                       'input-shadow-winner', repeat('b', 64), 'shadow-command',
                       output_status, output_revision,
                       output_updated_epoch_second, output_updated_nano
                  FROM public.agent_runtime_session_mutations
                 WHERE session_id = 'journal-first-winner'
                """, "uq_agent_runtime_session_mutation_output");
        assertEquals(1, adminCount("SELECT count(*)"
                + " FROM public.agent_runtime_session_mutations"
                + " WHERE session_id = 'journal-first-winner'"));
    }

    @Test
    void corruptedLogOwnerFailsClosedInsteadOfFallingBackToZeroSequence()
            throws Exception {
        SessionOwner owner = owner("corrupt-owner", 1501);
        JdbcSessionStorePort store = new JdbcSessionStorePort(applicationDataSource());
        created(store.create(create(owner, "persona", "corrupt-owner")));
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                new JdbcSessionLogPort(applicationDataSource()).append(
                        logCommand(owner, "corrupt-owner")));

        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.agent_runtime_session_streams"
                    + " DISABLE TRIGGER ALL");
            statement.execute("UPDATE public.agent_runtime_session_streams"
                    + " SET end_user_id = 1502"
                    + " WHERE session_id = 'corrupt-owner'");
            statement.execute("ALTER TABLE public.agent_runtime_session_streams"
                    + " ENABLE TRIGGER ALL");
        }

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.find(new SessionLookup(owner)));
        assertEquals("session_store_storage_failure", failure.getMessage());
        assertTrue(hasCauseMessage(failure, "session_store_row_corrupt"));
    }

    @Test
    void corruptedSessionOwnerFailsClosedBeforeWrongOwnerMapping() throws Exception {
        SessionOwner owner = owner("corrupt-session-owner", 1551);
        JdbcSessionStorePort store = new JdbcSessionStorePort(applicationDataSource());
        created(store.create(create(owner, "persona", "corrupt-session-owner")));

        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.agent_runtime_sessions DISABLE TRIGGER ALL");
            statement.execute("UPDATE public.agent_runtime_sessions"
                    + " SET end_user_id = 1552"
                    + " WHERE session_id = 'corrupt-session-owner'");
            statement.execute("ALTER TABLE public.agent_runtime_sessions ENABLE TRIGGER ALL");
        }

        IllegalStateException findFailure = assertThrows(
                IllegalStateException.class,
                () -> store.find(new SessionLookup(owner)));
        assertEquals("session_store_storage_failure", findFailure.getMessage());
        assertTrue(hasCauseMessage(findFailure, "session_store_row_corrupt"));

        IllegalStateException casFailure = assertThrows(
                IllegalStateException.class,
                () -> store.compareAndSet(activate(owner, 1, "corrupt-session-owner")));
        assertEquals("session_store_storage_failure", casFailure.getMessage());
        assertTrue(hasCauseMessage(casFailure, "session_store_row_corrupt"));
    }

    @Test
    void rootOnlyWrongOwnerCreateFailsClosedWhenCurrentLogOwnerIsCorrupt() throws Exception {
        var owner = owner("corrupt-root-only-log", 1581);
        var wrong = owner("corrupt-root-only-log", 1582);
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                new JdbcSessionLogPort(applicationDataSource()).append(logCommand(owner, "corrupt-root-only-log")));
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.agent_runtime_session_streams DISABLE TRIGGER ALL");
            statement.execute("UPDATE public.agent_runtime_session_streams SET end_user_id=9997 WHERE session_id='corrupt-root-only-log'");
            statement.execute("ALTER TABLE public.agent_runtime_session_streams ENABLE TRIGGER ALL");
        }
        var failure = assertThrows(IllegalStateException.class, () -> new JdbcSessionStorePort(applicationDataSource())
                .create(create(wrong, "persona", "corrupt-root-only-log-wrong")));
        assertEquals("session_store_storage_failure", failure.getMessage());
        assertTrue(hasCauseMessage(failure, "session_owner_graph_corrupt"));
    }

    @Test
    void ownerIdentityTextRangeIsConsistentAcrossSessionStoreAndLog() {
        SessionOwner owner = SessionOwner.user(
                new SessionRef(
                        new TenantId("tenant-" + "t".repeat(64)),
                        "session-" + "s".repeat(128)),
                new AgentId("agent-" + "a".repeat(128)), new EndUserId(1601));
        DataSource dataSource = applicationDataSource();

        assertInstanceOf(SessionCreateResult.Created.class,
                new JdbcSessionStorePort(dataSource).create(
                        create(owner, "persona", "long-owner")));
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                new JdbcSessionLogPort(dataSource).append(logCommand(owner, "long-owner")));
    }

    private static List<Object> race(ThrowingSupplier left, ThrowingSupplier right)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = executor.submit(() -> awaitAndGet(ready, start, left));
            Future<Object> second = executor.submit(() -> awaitAndGet(ready, start, right));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Object awaitAndGet(
            CountDownLatch ready, CountDownLatch start, ThrowingSupplier supplier)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return supplier.get();
    }

    private static CreateSession create(
            SessionOwner owner, String persona, String suffix) {
        return new CreateSession(
                owner, "channel-" + suffix, "scope-" + suffix, persona,
                new InputId("input-create-" + suffix), trace(suffix),
                Instant.ofEpochSecond(1_700_000_000L, 987_654_321));
    }

    private static ChangeSessionStatus activate(
            SessionOwner owner, long revision, String suffix) {
        return new ChangeSessionStatus(
                owner, SessionStatus.CREATED, SessionStatus.ACTIVE, revision,
                new InputId("input-" + suffix), trace(suffix),
                Instant.ofEpochSecond(1_700_000_100L, 123_456_789));
    }

    private static SessionLogPort.AppendSessionEvent logCommand(
            SessionOwner owner, String suffix) {
        return new SessionLogPort.AppendSessionEvent(
                new SessionProjectionTarget(
                        owner, new InputId("input-log-" + suffix),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                new SessionEventDraft(
                        new EventId("event-log-" + suffix), "session.store-test", "1",
                        "fingerprint-log-" + suffix, trace("log-" + suffix),
                        new TypedPayload(
                                "skillforge.runtime.session-store-test.v1",
                                "{\"suffix\":\"" + suffix + "\"}"),
                        Instant.ofEpochSecond(1_700_000_200L, 333_444_555)));
    }

    private static TraceEnvelope trace(String suffix) {
        return new TraceEnvelope(
                "trace-" + suffix, "span-" + suffix,
                new InputId("root-" + suffix),
                Optional.of(new EventId("cause-" + suffix)));
    }

    private static SessionOwner owner(String sessionId, long endUserId) {
        return SessionOwner.user(
                new SessionRef(TENANT, sessionId),
                new AgentId("agent-store-jdbc"), new EndUserId(endUserId));
    }

    private static SessionStorePort.SessionRecord created(SessionCreateResult result) {
        return assertInstanceOf(SessionCreateResult.Created.class, result).session();
    }

    private static void resetDatabase() {
        try {
            removeInsertFailure(
                    "agent_runtime_sessions", "session_store_fail_session_insert");
            removeInsertFailure(
                    "agent_runtime_session_mutations", "session_store_fail_mutation_insert");
            removeInsertFailure(
                    "agent_runtime_session_conflict_audits", "session_store_fail_audit_insert");
        } catch (SQLException failure) {
            throw new IllegalStateException("session_store_test_failure_cleanup", failure);
        }
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + qualifiedTables() + " CASCADE");
        } catch (SQLException failure) {
            throw new IllegalStateException("session_store_test_reset_failure", failure);
        }
    }

    private static void installInsertFailure(String table, String trigger)
            throws SQLException {
        String function = "public." + trigger + "_function";
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION " + function + "() RETURNS trigger"
                    + " LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced "
                    + table + " insert failure'; END; $$");
            statement.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON public."
                    + table + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        }
    }

    private static void removeInsertFailure(String table, String trigger)
            throws SQLException {
        String function = "public." + trigger + "_function";
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS " + trigger + " ON public." + table);
            statement.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    private static void assertSqlRejected(String sql, String expectedMessage) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                SQLException failure = assertThrows(SQLException.class, () -> {
                    statement.execute(sql);
                    connection.commit();
                });
                assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("session_store_schema_assertion_failure", failure);
        }
    }

    private static boolean hasCauseMessage(Throwable failure, String text) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String qualifiedTables() {
        return ALL_TABLES.stream().map(name -> "public." + name)
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private static String readResource(String resourceName) throws Exception {
        try (InputStream resource = JdbcSessionStorePortTest.class
                .getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(resource, resourceName + " must be on the test runtime classpath");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DataSource applicationDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {POSTGRES.getHost()});
        dataSource.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
        dataSource.setDatabaseName(POSTGRES.getDatabaseName());
        dataSource.setUser(APP_ROLE);
        dataSource.setPassword(APP_PASSWORD);
        return dataSource;
    }

    private static Instant databaseTime() {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT clock_timestamp()")) {
            assertTrue(result.next());
            return result.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException("session_store_database_time_failure", failure);
        }
    }

    private static void awaitDatabaseAdvance(Duration duration) {
        Instant target = databaseTime().plus(duration);
        while (databaseTime().isBefore(target)) {
            Thread.onSpinWait();
        }
    }

    private static void bindTenant(Connection connection, String tenantId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId);
            statement.executeQuery().close();
        }
    }

    private static int queryCount(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static int adminCount(String query) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("session_store_count_failure", failure);
        }
    }

    private static String adminText(String query) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("session_store_text_query_failure", failure);
        }
    }

    /** 真实提交后只丢失一次响应，用于证明未知结果只能靠原命令重放协调。 */
    private static final class CommitFailureDataSource implements DataSource {

        private final DataSource delegate;
        private final AtomicBoolean failNextCommit = new AtomicBoolean(true);

        private CommitFailureDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        Object result;
                        try {
                            result = method.invoke(connection, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (method.getName().equals("commit")
                                && failNextCommit.compareAndSet(true, false)) {
                            throw new SQLException("forced post-commit response loss");
                        }
                        return result;
                    });
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
