package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.testkit.ExecutionLedgerPortContract;
import com.skillforge.runtime.adapter.jdbc.StateEventClaimJdbcCounter;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionCancelResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseRequest;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWait;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecoveryScan;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWriteResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.LeaseMutationStatus;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.port.PublicationAcknowledge;
import com.skillforge.runtime.api.port.PublicationClaimRequest;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.StateEventPublication;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V98 Execution Ledger 在真实 PostgreSQL、强制 RLS 与跨连接竞争下的共享契约验收。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcExecutionLedgerPortContractTest
        extends ExecutionLedgerPortContract {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();

    private static final String APP_ROLE = "agent_runtime_execution_ledger_test";
    private static final String APP_PASSWORD = "execution_ledger_test_password";
    private static final String RESUME_FAILURE_TRIGGER =
            "agent_runtime_execution_resume_second_publication_failure";
    private static final String RESUME_FAILURE_FUNCTION =
            "public.fail_agent_runtime_execution_resume_second_publication";
    private static final List<String> EXECUTION_TABLES = List.of(
            "agent_runtime_executions",
            "agent_runtime_execution_mutations",
            "agent_runtime_execution_state_publications",
            "agent_runtime_execution_publication_mutations");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge")
                    .withUsername("skillforge_admin")
                    .withPassword("skillforge_admin_password");

    private JdbcExecutionLedgerPort ledger;

    @Test
    void emptyStateEventClaimUsesOnlyTenantBindingAndEligibleQuery() {
        var counter = new StateEventClaimJdbcCounter(applicationDataSource());
        var port = new JdbcExecutionLedgerPort(counter.source());

        assertTrue(port.claimStateEvents(new PublicationClaimRequest(
                new TenantId("tenant-empty-publications"), "empty-worker", 1,
                LEASE_DURATION)).isEmpty());

        assertEquals(1, counter.connections());
        assertEquals(2, counter.executions());
    }

    @Test
    void nonemptyStateEventClaimUsesDatabaseSampleAndPreservesNanosecondLease()
            throws Exception {
        TenantId tenant = new TenantId("tenant-execution-publication-nano");
        SessionOwner session = SessionOwner.user(new SessionRef(tenant, "nano-session"),
                new AgentId("nano-agent"), new EndUserId(102));
        InputId input = new InputId("nano-input");
        RunIdentity owner = new RunIdentity(session, session.target().requireAgent(), input, new RunRef(tenant, "nano-run"), new TraceEnvelope("nano-trace", "nano-span", input, Optional.empty()), new ResultRef("nano-identity"), new com.skillforge.runtime.api.RunRequester.EndUser(session.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(session.sessionRef().tenantId(), input.value()));
        establishRunRoot(owner);
        CreateExecution command = ExecutionCommands.create(
                new ExecutionRef(tenant, "nano-execution"), owner,
                "unit", "definition:v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(),
                new TypedPayload("factory.checkpoint.v1", "{}"), "nano-fingerprint",
                new EventId("nano-queued"), INITIAL_TIME);
        assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.create(command));
        Duration duration = Duration.ofSeconds(2).plusNanos(789);
        Instant before = databaseTime();
        var claims = ledger.claimStateEvents(new PublicationClaimRequest(
                tenant, "nano-worker", 1, duration));
        Instant after = databaseTime();

        assertEquals(1, claims.size());
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT updated_epoch_second, updated_nano,
                               lease_until_epoch_second, lease_until_nano
                          FROM public.agent_runtime_execution_state_publications
                         WHERE status = 'CLAIMED'
                        """)) {
            assertTrue(result.next());
            Instant sampled = Instant.ofEpochSecond(result.getLong(1), result.getInt(2));
            Instant leaseUntil = Instant.ofEpochSecond(result.getLong(3), result.getInt(4));
            assertFalse(sampled.isBefore(before));
            assertFalse(sampled.isAfter(after));
            assertEquals(sampled.plus(duration), leaseUntil);
            assertEquals(leaseUntil, claims.getFirst().fence().leaseUntil());
            assertFalse(result.next());
        }
    }

    @Test
    void productionCommandFactoryPassesRealLedgerForEveryMutation() {
        resetDatabase();
        JdbcExecutionLedgerPort port = new JdbcExecutionLedgerPort(applicationDataSource());
        TenantId tenant = new TenantId("factory-tenant");
        SessionOwner session = SessionOwner.user(new SessionRef(tenant, "factory-session"),
                new AgentId("factory-agent"), new EndUserId(101));
        InputId input = new InputId("factory-input");
        RunIdentity owner = new RunIdentity(session, session.target().requireAgent(), input, new RunRef(tenant, "factory-run"), new TraceEnvelope("factory-trace", "factory-span", input, Optional.empty()), new ResultRef("factory-identity"), new com.skillforge.runtime.api.RunRequester.EndUser(session.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(session.sessionRef().tenantId(), input.value()));
        VerifiedIdentity identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, session.requireUser()),
                session, java.util.Set.of("execution.read", "execution.write"), 1, new ResultRef("factory-verification"));
        establishRunRoot(owner);
        TypedPayload checkpoint = new TypedPayload("factory.checkpoint.v1", "{\"message\":\"中文\\nvalue\"}");
        Instant at = Instant.now();
        CreateExecution create = ExecutionCommands.create(new ExecutionRef(tenant, "factory-execution"), owner,
                "unit", "definition:v1", ExecutionMode.FOREGROUND, Optional.empty(), Optional.empty(),
                checkpoint, "factory-fingerprint", new EventId("factory-queued"), at);
        assertInstanceOf(ExecutionWriteResult.Applied.class, port.create(create));
        assertInstanceOf(ExecutionWriteResult.Replayed.class, port.create(create));
        ExecutionLeaseResult.Acquired acquired = assertInstanceOf(ExecutionLeaseResult.Acquired.class,
                port.acquireLease(new ExecutionLeaseRequest(identity, create.executionRef(), "factory-worker", Duration.ofMinutes(5), at)));
        ExecutionLease lease = acquired.lease();
        ExecutionRecord running = assertInstanceOf(ExecutionWriteResult.Applied.class, port.transition(
                ExecutionCommands.transition(acquired.execution(), lease, WorkflowExecutionState.RUNNING, checkpoint,
                        Optional.empty(), Optional.empty(), Optional.empty(), new EventId("factory-started"), at))).execution();
        running = assertInstanceOf(ExecutionWriteResult.Applied.class, port.progress(
                ExecutionCommands.progress(running, lease, checkpoint, new EventId("factory-step"), at))).execution();
        ExecutionWait wait = new ExecutionWait("factory-suspend", "confirm");
        ExecutionRecord waiting = assertInstanceOf(ExecutionWriteResult.Applied.class, port.transition(
                ExecutionCommands.transition(running, lease, WorkflowExecutionState.WAITING_HITL, checkpoint,
                        Optional.of(wait), Optional.of(new ResultRef("factory-wait-result")), Optional.empty(),
                        new EventId("factory-wait"), at))).execution();
        running = assertInstanceOf(ExecutionWriteResult.Applied.class, port.resume(ExecutionCommands.resume(waiting,
                lease, wait, checkpoint, new EventId("factory-resumed"), new EventId("factory-restarted"), at))).execution();
        ExecutionRecord cancelled = assertInstanceOf(ExecutionCancelResult.Applied.class, port.requestCancel(
                ExecutionCommands.cancel(running, identity, new InputId("factory-cancel"), new EventId("factory-cancel-request"), at))).execution();
        ExecutionRecord terminal = assertInstanceOf(ExecutionWriteResult.Applied.class, port.transition(
                ExecutionCommands.transition(cancelled, lease, WorkflowExecutionState.CANCELLED, checkpoint,
                        Optional.empty(), Optional.of(new ResultRef("factory-cancel-result")), Optional.empty(),
                        new EventId("factory-cancelled"), at))).execution();
        assertEquals(WorkflowExecutionState.CANCELLED, terminal.state());
        assertThrows(IllegalArgumentException.class, () -> ExecutionCommands.progress(terminal, lease, checkpoint,
                new EventId("factory-invalid"), at));
        assertEquals(8, terminal.executionRevision());
    }

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
            // Workflow owner只由正式typed受理建立；夹具角色同样不能绕过RLS或写回封存旧Run。
            statement.execute("GRANT SELECT, INSERT, UPDATE ON "
                    + "public.agent_runtime_session_owners, public.agent_runtime_execution_sessions, "
                    + "public.agent_runtime_execution_runs, public.agent_runtime_execution_materials, "
                    + "public.agent_runtime_execution_events, public.agent_runtime_budget_roots, "
                    + "public.agent_runtime_budget_runs, public.agent_runtime_budget_balances TO " + APP_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON "
                    + "public.agent_runtime_executions, "
                    + "public.agent_runtime_execution_mutations, "
                    + "public.agent_runtime_execution_state_publications, "
                    + "public.agent_runtime_execution_publication_mutations TO "
                    + APP_ROLE);
            statement.execute("GRANT USAGE, SELECT ON SEQUENCE "
                    + "public.agent_runtime_execution_fencing_seq TO " + APP_ROLE);
        }
    }

    @Override
    protected ExecutionLedgerFixture createFixture(Instant initialTime) {
        Objects.requireNonNull(initialTime, "initialTime");
        resetDatabase();
        ledger = new JdbcExecutionLedgerPort(applicationDataSource());
        return new ExecutionLedgerFixture() {
            @Override
            public JdbcExecutionLedgerPort ledger() {
                return ledger;
            }

            @Override
            public void establishRun(RunIdentity owner) {
                establishRunRoot(owner);
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
    void releaseReplaySurvivesAdapterRebuildAfterDomainRevisionAdvance() {
        leaseBindsExactClaimRevisionAcrossDomainProgress();
        ExecutionLease released = storedCodec("""
                SELECT command_data
                  FROM public.agent_runtime_execution_mutations
                 WHERE mutation_kind = 'RELEASE'
                 ORDER BY expected_revision DESC
                 LIMIT 1
                """, ExecutionLease.class);

        assertEquals(LeaseMutationStatus.ALREADY_APPLIED,
                new JdbcExecutionLedgerPort(applicationDataSource()).release(released));
    }

    @Test
    void secondResumePublicationFailureRollsBackStateJournalAndBothEvents()
            throws Exception {
        installResumeSecondPublicationFailure();
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    this::resumeCommitsTwoRevisionsAndTwoOrderedEventsAtomically);
            assertEquals("execution_ledger_storage_failure", failure.getMessage());
            assertTrue(hasCauseMessage(
                    failure, "forced execution resume second publication failure"));
        } finally {
            removeResumeSecondPublicationFailure();
        }

        assertEquals("WAITING_HITL:3", adminText("""
                SELECT state || ':' || execution_revision
                  FROM public.agent_runtime_executions
                """));
        assertEquals(0, adminCount("""
                SELECT count(*)
                  FROM public.agent_runtime_execution_mutations
                 WHERE mutation_kind = 'RESUME'
                """));
        assertEquals(0, adminCount("""
                SELECT count(*)
                  FROM public.agent_runtime_execution_state_publications
                 WHERE source_revision > 3
                """));
    }

    @Test
    void restrictedRoleAndEveryExecutionTableForceRlsWhileMapperStaysIsolated()
            throws Exception {
        try (Connection connection = adminConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, APP_ROLE);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1));
                assertFalse(result.getBoolean(2));
            }
        }
        assertEquals(EXECUTION_TABLES.size(), adminCount("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname IN (
                       'agent_runtime_executions',
                       'agent_runtime_execution_mutations',
                       'agent_runtime_execution_state_publications',
                       'agent_runtime_execution_publication_mutations')
                   AND relrowsecurity
                   AND relforcerowsecurity
                """));

        String mapper = readResource(
                "mapper/AgentRuntimeExecutionLedgerMapper.xml");
        for (String oldTable : List.of(
                "workflow_execution_ledger",
                "workflow_execution_events",
                "workflow_execution_outbox")) {
            assertFalse(mapper.contains(oldTable), oldTable);
        }
        assertFalse(mapper.contains("agent_runtime_runs"));
    }

    @Test
    void mutationJournalCanonicalAndFingerprintTamperFailsClosed() throws Exception {
        createUsesStorageClockAndFreezesCompleteProjection();
        CreateExecution original = storedCodec("""
                SELECT command_data
                  FROM public.agent_runtime_execution_mutations
                 WHERE mutation_kind = 'CREATE'
                """, CreateExecution.class);
        CreateExecution forged = new CreateExecution(
                new ExecutionRef(
                        original.executionRef().tenantId(),
                        original.executionRef().executionId() + "-forged"),
                original.owner(), original.executionUnitId(),
                original.definitionVersion(), original.mode(),
                original.causativeActionRef(), original.delegationRef(),
                original.initialCheckpoint(), original.requestFingerprint(),
                original.queuedEvent());
        replaceMutationCommand(
                ExecutionLedgerCodec.encode(forged),
                JdbcExecutionLedgerPort.fingerprint(forged));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> ledger.create(original));
        assertEquals("execution_ledger_storage_failure", failure.getMessage());
        assertTrue(hasCauseMessage(failure, "execution_mutation_command_mismatch"));
    }

    @Test
    void publicationJournalCanonicalAndFingerprintTamperFailsClosed()
            throws Exception {
        publicationRetryAndAcknowledgeUseFencedCasAndExactReplay();
        PublicationAcknowledge original = storedCodec("""
                SELECT command_data
                  FROM public.agent_runtime_execution_publication_mutations
                 WHERE mutation_kind = 'ACKNOWLEDGE'
                """, PublicationAcknowledge.class);
        PublicationAcknowledge forged = new PublicationAcknowledge(
                original.tenantId(), original.sourceKind(),
                original.sourceId() + "-forged", original.eventId(),
                original.fence(), original.expectedPublicationRevision(),
                original.projectionRef());
        replacePublicationMutationCommand(
                ExecutionLedgerCodec.encode(forged),
                JdbcExecutionLedgerPort.fingerprint(forged));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ledger.acknowledgeStateEvent(original));
        assertEquals("execution_ledger_storage_failure", failure.getMessage());
        assertTrue(hasCauseMessage(
                failure, "execution_publication_command_mismatch"));
    }

    @Test
    void publicationPayloadWithSynchronizedFingerprintFailsClosed()
            throws Exception {
        createReplayRequiresSameCommandAndCurrentOutput();
        StateEventPublication original = storedCodec("""
                SELECT publication_data
                  FROM public.agent_runtime_execution_state_publications
                 WHERE source_revision = 1
                """, StateEventPublication.class);
        SessionEventDraft draft = original.event().sessionEvent();
        String forgedBody = "{\"executionId\":\"" + original.sourceId()
                + "\",\"revision\":\"999\",\"state\":\"RUNNING\"}";
        TypedPayload forgedPayload = new TypedPayload(
                draft.payload().schemaId(), forgedBody);
        SessionEventDraft forgedDraft = new SessionEventDraft(
                draft.eventId(), draft.eventType(), draft.eventVersion(),
                ExecutionLedgerEventCodec.sha256(
                        forgedPayload.schemaId() + "\n" + forgedBody),
                draft.trace(), forgedPayload, draft.occurredAt());
        LedgerEvent forgedEvent = new LedgerEvent(
                forgedDraft, original.event().ledgerEventType(),
                original.event().ledgerFingerprint(),
                original.event().privatePayload());
        StateEventPublication forged = new StateEventPublication(
                original.tenantId(), original.sourceKind(), original.sourceId(),
                original.sourceRevision(), original.committedFactRef(),
                original.projectionTarget(), forgedEvent,
                original.runtimeEventDestinationIds(),
                original.publicationRevision(), original.availableAt());
        replacePublicationData(ExecutionLedgerCodec.encode(forged));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ledger.claimStateEvents(new PublicationClaimRequest(
                        original.tenantId(), "tampered-publication", 1,
                        LEASE_DURATION)));

        assertEquals("execution_ledger_storage_failure", failure.getMessage());
        assertTrue(hasCauseMessage(
                failure, "execution_publication_source_fact_mismatch"));
    }

    @Test
    void cancelGuardRejectsCombinedDomainMutationBeforeDeferredClosure() {
        createUsesStorageClockAndFreezesCompleteProjection();

        assertSqlRejected("""
                UPDATE public.agent_runtime_executions
                   SET execution_revision = execution_revision + 1,
                       checkpoint_canonical_json = '{"tampered":true}',
                       cancel_request_id = 'forged-cancel',
                       cancel_requested_epoch_second = updated_epoch_second,
                       cancel_requested_nano = updated_nano,
                       record_data = 'forged-cancel-record',
                       updated_epoch_second = updated_epoch_second + 1
                """, "execution cancel request changed non-cancel state");
        assertEquals(0, adminCount("""
                SELECT count(*)
                  FROM public.agent_runtime_executions
                 WHERE cancel_request_id IS NOT NULL OR execution_revision <> 1
                """));
    }

    @Test
    void sourceRevisionConflictRejectsWithoutStateJournalOrPublicationResidue()
            throws Exception {
        progressIsRunningLeaseCasAndEventAtomic();
        ProgressExecution stored = storedCodec("""
                SELECT command_data
                  FROM public.agent_runtime_execution_mutations
                 WHERE mutation_kind = 'PROGRESS'
                """, ProgressExecution.class);
        ProgressExecution command = withEventId(
                stored, "source-revision-conflict-new-event");
        ExecutionRecord running = storedCodec("""
                SELECT output_data
                  FROM public.agent_runtime_execution_mutations
                 WHERE mutation_kind = 'TRANSITION' AND output_revision = 2
                """, ExecutionRecord.class);
        rewindWithSourceRevisionConflict(running, command);
        String beforeRecord = adminText(
                "SELECT record_data FROM public.agent_runtime_executions");
        int beforeMutations = adminCount(
                "SELECT count(*) FROM public.agent_runtime_execution_mutations");
        int beforePublications = adminCount(
                "SELECT count(*) FROM public.agent_runtime_execution_state_publications");

        ExecutionWriteResult.Rejected rejected = assertInstanceOf(
                ExecutionWriteResult.Rejected.class, ledger.progress(command));

        assertEquals("execution_publication_event_conflict",
                rejected.failure().reasonCode());
        assertEquals(beforeRecord, adminText(
                "SELECT record_data FROM public.agent_runtime_executions"));
        assertEquals(beforeMutations, adminCount(
                "SELECT count(*) FROM public.agent_runtime_execution_mutations"));
        assertEquals(beforePublications, adminCount(
                "SELECT count(*) FROM public.agent_runtime_execution_state_publications"));
    }

    @Test
    void connectionFailureUsesStableStorageCodeAndPreservesCause() {
        JdbcExecutionLedgerPort broken = new JdbcExecutionLedgerPort(
                new FailingDataSource());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> broken.scanRecoverable(new ExecutionRecoveryScan(
                        new TenantId("tenant-broken-storage"), 1, Optional.empty())));

        assertEquals("execution_ledger_storage_failure", failure.getMessage());
        assertNotNull(failure.getCause());
        assertTrue(hasCauseMessage(failure, "forced execution connection failure"));
    }

    @Test
    void schemaRejectsPartialTuplesAndCrossKindPublicationOutputs() {
        publicationRetryAndAcknowledgeUseFencedCasAndExactReplay();

        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_executions",
                "UPDATE public.agent_runtime_executions"
                        + " SET waiting_suspend_ref = 'orphan-wait'",
                "ck_agent_runtime_execution_waiting");
        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_executions",
                "UPDATE public.agent_runtime_executions"
                        + " SET lease_execution_revision = execution_revision",
                "ck_agent_runtime_execution_lease");
        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_executions",
                "UPDATE public.agent_runtime_executions"
                        + " SET cancel_request_id = 'orphan-cancel'",
                "ck_agent_runtime_execution_cancel");
        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_execution_state_publications",
                "UPDATE public.agent_runtime_execution_state_publications"
                        + " SET lease_worker_id = 'orphan-worker'",
                "ck_agent_runtime_execution_publication_lease");
        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_execution_publication_mutations",
                "UPDATE public.agent_runtime_execution_publication_mutations"
                        + " SET output_status = 'PENDING'"
                        + " WHERE mutation_kind = 'ACKNOWLEDGE'",
                "ck_agent_runtime_execution_publication_mutation_output");
    }

    @Test
    void schemaRejectsNullExpectedStateForProgressAndResume() {
        progressIsRunningLeaseCasAndEventAtomic();
        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_execution_mutations",
                "UPDATE public.agent_runtime_execution_mutations"
                        + " SET expected_state = NULL"
                        + " WHERE mutation_kind = 'PROGRESS'",
                "ck_agent_runtime_execution_mutation_revision");

        createEmptyLedger();
        resumeCommitsTwoRevisionsAndTwoOrderedEventsAtomically();
        assertCheckRejectedWithTriggersDisabled(
                "agent_runtime_execution_mutations",
                "UPDATE public.agent_runtime_execution_mutations"
                        + " SET expected_state = NULL"
                        + " WHERE mutation_kind = 'RESUME'",
                "ck_agent_runtime_execution_mutation_revision");
    }

    private static void establishRunRoot(RunIdentity owner) {
        // 此夹具隔离Workflow Ledger契约；调用方身份由共享契约给定，当前IAM另有独立验收。
        var actor = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(owner.runRef().tenantId(), "workflow-ledger-fixture"),
                owner.owner(), Set.of("sessions.write"), 1, owner.causationIdentityRef());
        identities.register(actor);
        String prefix = "workflow-owner:" + owner.runRef().runId();
        var input = new ExecutionStore.Material(new ResultRef(prefix + ":input"), new TypedPayload("fixture.workflow-owner-input.v1", "{}"));
        var context = new ExecutionStore.Material(new ResultRef(prefix + ":context"), new TypedPayload("fixture.workflow-owner-context.v1", "{}"));
        var capacity = new ExecutionBudgetPort.Resources(1, 1, 1, 1, 1_000, 100, 1_100);
        var budget = new ExecutionBudgetPort.Registration(owner.runRef(), owner.runRef(), ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 60, 60, "workflow-ledger-fixture:v1")));
        var admitted = new JdbcExecutionStore(applicationDataSource(), identities, (session, run, verified, prepared) -> true)
                .admit(new ExecutionStore.Admission(actor, owner, prefix, new ResultRef(prefix + ":profile"), input, context, budget, List.of(), List.of()));
        assertEquals(owner, admitted.run().identity());
    }

    private static void resetDatabase() {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS " + RESUME_FAILURE_TRIGGER
                    + " ON public.agent_runtime_execution_state_publications");
            statement.execute("DROP FUNCTION IF EXISTS "
                    + RESUME_FAILURE_FUNCTION + "()");
            statement.execute("""
                    TRUNCATE TABLE
                        public.agent_runtime_execution_publication_mutations,
                        public.agent_runtime_execution_state_publications,
                        public.agent_runtime_execution_mutations,
                        public.agent_runtime_executions,
                        public.agent_runtime_execution_sessions,
                        public.agent_runtime_execution_materials,
                        public.agent_runtime_budget_roots,
                        public.agent_runtime_budget_balances,
                        public.agent_runtime_session_owners
                    CASCADE
                    """);
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_fixture_reset_failure", failure);
        }
    }

    private static <T> T storedCodec(String query, Class<T> type) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            if (!result.next()) {
                throw new IllegalStateException("execution_test_codec_row_missing");
            }
            T decoded = ExecutionLedgerCodec.decode(result.getString(1), type);
            if (result.next()) {
                throw new IllegalStateException("execution_test_codec_row_not_unique");
            }
            return decoded;
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_test_codec_query_failure", failure);
        }
    }

    private static void replaceMutationCommand(String data, String fingerprint)
            throws SQLException {
        replaceJournalCommand(
                "agent_runtime_execution_mutations",
                "agent_runtime_execution_mutation_immutable",
                "mutation_kind = 'CREATE'", data, fingerprint);
    }

    private static void replacePublicationMutationCommand(
            String data, String fingerprint) throws SQLException {
        replaceJournalCommand(
                "agent_runtime_execution_publication_mutations",
                "agent_runtime_execution_publication_mutation_immutable",
                "mutation_kind = 'ACKNOWLEDGE'", data, fingerprint);
    }

    private static void replacePublicationData(String data) throws SQLException {
        try (Connection connection = adminConnection();
                Statement control = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                control.execute("ALTER TABLE public."
                        + "agent_runtime_execution_state_publications"
                        + " DISABLE TRIGGER agent_runtime_execution_publication_guard");
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE public.agent_runtime_execution_state_publications
                           SET publication_data = ?
                         WHERE source_revision = 1
                        """)) {
                    update.setString(1, data);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException(
                                "execution publication tamper target not unique");
                    }
                }
                control.execute("ALTER TABLE public."
                        + "agent_runtime_execution_state_publications"
                        + " ENABLE TRIGGER agent_runtime_execution_publication_guard");
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void replaceJournalCommand(
            String table,
            String immutableTrigger,
            String predicate,
            String data,
            String fingerprint) throws SQLException {
        try (Connection connection = adminConnection();
                Statement control = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                control.execute("ALTER TABLE public." + table
                        + " DISABLE TRIGGER " + immutableTrigger);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE public." + table
                                + " SET command_data = ?, command_fingerprint = ? WHERE "
                                + predicate)) {
                    update.setString(1, data);
                    update.setString(2, fingerprint);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("execution journal tamper target not unique");
                    }
                }
                control.execute("ALTER TABLE public." + table
                        + " ENABLE TRIGGER " + immutableTrigger);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void installResumeSecondPublicationFailure()
            throws SQLException {
        removeResumeSecondPublicationFailure();
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION " + RESUME_FAILURE_FUNCTION
                    + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.source_revision = 5 THEN"
                    + " RAISE EXCEPTION 'forced execution resume second publication failure';"
                    + " END IF; RETURN NEW; END; $$");
            statement.execute("CREATE TRIGGER " + RESUME_FAILURE_TRIGGER
                    + " BEFORE INSERT ON public.agent_runtime_execution_state_publications"
                    + " FOR EACH ROW EXECUTE FUNCTION "
                    + RESUME_FAILURE_FUNCTION + "()");
        }
    }

    private static void removeResumeSecondPublicationFailure()
            throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS " + RESUME_FAILURE_TRIGGER
                    + " ON public.agent_runtime_execution_state_publications");
            statement.execute("DROP FUNCTION IF EXISTS "
                    + RESUME_FAILURE_FUNCTION + "()");
        }
    }

    private static void rewindWithSourceRevisionConflict(
            ExecutionRecord running, ProgressExecution command) throws SQLException {
        LeaseFence supplied = command.lease().fence();
        Instant now = databaseTime();
        LeaseFence authority = new LeaseFence(
                supplied.tenantId(), supplied.claimId(), supplied.workerId(),
                supplied.fencingToken(), now.plus(Duration.ofMinutes(5)));
        ExecutionRecord rewound = new ExecutionRecord(
                running.executionRef(), running.owner(), running.executionUnitId(),
                running.definitionVersion(), running.mode(),
                running.causativeActionRef(), running.delegationRef(),
                running.state(), running.executionRevision(), running.checkpoint(),
                running.waiting(), running.resultRef(), running.reasonCode(),
                Optional.of(authority), running.cancelRequestId(),
                running.cancelRequestedAt(), running.createdAt(), now);

        try (Connection connection = adminConnection();
                Statement control = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                for (String table : EXECUTION_TABLES) {
                    control.execute("ALTER TABLE public." + table
                            + " DISABLE TRIGGER ALL");
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE public.agent_runtime_executions
                           SET state = ?, execution_revision = ?,
                               checkpoint_schema_id = ?,
                               checkpoint_canonical_json = ?,
                               lease_until_epoch_second = ?, lease_until_nano = ?,
                               record_data = ?, updated_epoch_second = ?, updated_nano = ?
                        """)) {
                    update.setString(1, rewound.state().name());
                    update.setLong(2, rewound.executionRevision());
                    update.setString(3, rewound.checkpoint().schemaId());
                    update.setString(4, rewound.checkpoint().canonicalJson());
                    update.setLong(5, authority.leaseUntil().getEpochSecond());
                    update.setInt(6, authority.leaseUntil().getNano());
                    update.setString(7, ExecutionLedgerCodec.encode(rewound));
                    update.setLong(8, now.getEpochSecond());
                    update.setInt(9, now.getNano());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("execution rewind target not unique");
                    }
                }
                for (String table : EXECUTION_TABLES) {
                    control.execute("ALTER TABLE public." + table
                            + " ENABLE TRIGGER ALL");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static ProgressExecution withEventId(
            ProgressExecution source, String eventId) {
        SessionEventDraft draft = source.stepEvent().sessionEvent();
        SessionEventDraft replacement = new SessionEventDraft(
                new EventId(eventId), draft.eventType(), draft.eventVersion(),
                draft.eventFingerprint(), draft.trace(), draft.payload(),
                draft.occurredAt());
        LedgerEvent event = new LedgerEvent(
                replacement, source.stepEvent().ledgerEventType(),
                source.stepEvent().ledgerFingerprint(),
                source.stepEvent().privatePayload());
        return new ProgressExecution(
                source.lease(), source.expectedRevision(), source.checkpoint(), event);
    }

    private static void assertSqlRejected(String sql, String message) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                SQLException failure = assertThrows(SQLException.class, () -> {
                    statement.execute(sql);
                    connection.commit();
                });
                assertTrue(failure.getMessage().contains(message), failure.getMessage());
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_schema_assertion_failure", failure);
        }
    }

    private static void assertCheckRejectedWithTriggersDisabled(
            String table, String update, String constraint) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("ALTER TABLE public." + table
                        + " DISABLE TRIGGER ALL");
                SQLException failure = assertThrows(
                        SQLException.class, () -> statement.execute(update));
                assertTrue(failure.getMessage().contains(constraint),
                        failure.getMessage());
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_check_assertion_failure", failure);
        }
    }

    private static int adminCount(String query) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_count_query_failure", failure);
        }
    }

    private static String adminText(String query) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_text_query_failure", failure);
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

    private static DataSource applicationDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(APP_ROLE);
        dataSource.setPassword(APP_PASSWORD);
        return dataSource;
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Instant databaseTime() {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT clock_timestamp()")) {
            assertTrue(result.next());
            return result.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException("execution_fixture_clock_failure", failure);
        }
    }

    private static void awaitDatabaseAdvance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "database time advance must be between zero and five seconds");
        }
        Instant target = databaseTime().plus(duration);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            Instant current = databaseTime();
            if (!current.isBefore(target)) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "database time did not advance within the bounded wait");
            }
            long sleepMillis = Math.max(1L, Math.min(50L,
                    Duration.between(current, target).toMillis()));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for database time", interrupted);
            }
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream resource = JdbcExecutionLedgerPortContractTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(resource, path + " must be present on the test runtime classpath");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class FailingDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("forced execution connection failure");
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            throw new SQLException("forced execution connection failure");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
