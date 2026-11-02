package com.skillforge.runtime.adapter.jdbc.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.testkit.MemoryPortContract;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryTaskConstraint;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactRead;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactReadBatch;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 当前 Session/source target 与 scope-owned Memory Store 在受限 PostgreSQL 角色下的共享契约验收。 */
final class JdbcMemoryPortContractTest extends MemoryPortContract {

    private static final String ROLE = "agent_runtime_memory_test";
    private static final String PASSWORD = "memory_test_password";
    private static final List<String> MEMORY_TABLES = List.of(
            "agent_runtime_memory_fact_revisions",
            "agent_runtime_memory_fact_sources",
            "agent_runtime_memory_fact_heads",
            "agent_runtime_memory_operations",
            "agent_runtime_memory_conflict_audits",
            "agent_runtime_memory_extraction_evidence",
            "agent_runtime_memory_search_documents","agent_runtime_memory_embeddings","agent_runtime_memory_query_embeddings");

    /** 外部S03夹具必须显式绑定新库及受限身份；只有完全未配置时采用CI的独立容器。 */
    private static final ExternalFixture EXTERNAL = externalFixture();
    private static final PostgreSQLContainer<?> POSTGRES =
            EXTERNAL == null ? new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge")
                    .withUsername("skillforge_admin")
                    .withPassword("skillforge_admin_password") : null;

    private JdbcMemoryPort port;

    @org.junit.jupiter.api.BeforeAll
    static void initializeDatabase() throws Exception {
        if (POSTGRES != null) POSTGRES.start();
        if (EXTERNAL != null) verifyExternalFixture();
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(jdbcUrl(), adminUser(), adminPassword());
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            String role = businessUser();
            if (EXTERNAL == null) statement.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON "
                    + qualified(MEMORY_TABLES) + " TO " + role);
            statement.execute("GRANT DELETE ON public.agent_runtime_memory_fact_revisions TO " + role);
            statement.execute("GRANT DELETE ON public.agent_runtime_memory_search_documents,public.agent_runtime_memory_embeddings TO "+role);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON public.agent_runtime_memory_usage_projection TO " + role);
            statement.execute("GRANT SELECT ON public.agent_runtime_memory_extraction_jobs,public.agent_runtime_runs,"
                    + "public.agent_runtime_actions,public.agent_runtime_session_checkpoints TO " + role);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON "
                    + "public.agent_runtime_session_owners, "
                    + "public.agent_runtime_session_streams, "
                    + "public.agent_runtime_session_events, "
                    + "public.agent_runtime_session_event_bodies, "
                    + "public.agent_runtime_session_event_body_refs TO " + role);
            statement.execute("GRANT SELECT ON public.agent_runtime_session_history_retirements, "
                    + "public.agent_runtime_session_event_tombstones TO " + role);
        }
    }

    /** 仅释放本类在CI新建的容器；外部S03资源与数据库生命周期始终归父负责人。 */
    @org.junit.jupiter.api.AfterAll
    static void stopOwnedDatabase() {
        if (POSTGRES != null && POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Override
    protected MemoryPort createPort() {
        resetDatabase();
        port = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        return port;
    }

    @Override
    protected MemoryPort recreatePort() {
        return new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
    }

    @Override
    protected Optional<MemoryFact> findCurrent(
            MemoryPort subject, PortCallContext context, MemoryFactRef ref) {
        return ((JdbcMemoryPort) subject).findCurrent(context, ref);
    }

    @Override
    protected void assertStoreTime(Instant sampledAt) {
        try {
            Instant database = databaseTime();
            assertFalse(sampledAt.isAfter(database));
            assertTrue(sampledAt.isAfter(database.minusSeconds(10)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void futureAndExpiredRevisionProposalsCannotArchiveCurrentOrCommitJournal() {
        var ctx = context(SESSION);
        MemoryFact current = applied(port.submit(createCommand("timed-accept-current", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        Instant now = Instant.now();
        for (String interval : List.of("future", "past", "expired")) {
            var original = revisionProposal(current, current.owner(), current.type(), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM);
            var draft = new MemoryFactDraft(original.owner(), original.type(), original.retentionClass(), original.schemaVersion(),
                    original.subject(), original.predicate(), original.object(), original.sourceEventIds(), original.sourceSessionRef(),
                    original.sourceExecutionRef(), original.exportPath(), original.extractorVersion(),
                    interval.equals("future") ? now.plusSeconds(86400) : now.minusSeconds(86400),
                    interval.equals("past") ? Optional.of(now.minusSeconds(3600)) : Optional.empty(),
                    interval.equals("expired") ? Optional.of(now.minusSeconds(3600)) : Optional.empty(),
                    original.confidence(), original.importance(), original.reviewRequired(), original.derivedFrom(), FactStatus.PENDING_CONFIRM);
            MemoryFact proposal = applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("timed-proposal-" + interval), draft)));
            var key = new IdempotencyKey("timed-accept-" + interval);
            long revisionCount = adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions");
            var rejected = assertInstanceOf(MemoryMutationResult.Rejected.class, port.submit(new MemoryCommand.AcceptRevision(ctx,
                    key, current.ref(), current.revision(), proposal.ref(), proposal.revision())));
            assertEquals("memory_revision_not_effective", rejected.failure().reasonCode());
            assertEquals(current, port.findCurrent(ctx, current.ref()).orElseThrow());
            assertEquals(proposal, port.findCurrent(ctx, proposal.ref()).orElseThrow());
            assertEquals(revisionCount, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions"));
            assertFalse(port.hasRecordedMutation(ctx, key));
        }
    }

    @Test
    void recordedMutationProbeReadsOriginalTenantJournalAndPropagatesFailure() {
        var ctx = context(SESSION);
        var command = createCommand("recorded-probe", new EventId("source-" + SESSION.sessionId()), Optional.empty());
        assertFalse(port.hasRecordedMutation(ctx, command.idempotencyKey()));
        applied(port.submit(command));
        assertTrue(port.hasRecordedMutation(ctx, command.idempotencyKey()));
        assertFalse(port.hasRecordedMutation(ctx, new IdempotencyKey("different-probe")));
        var otherTenant = new TenantId("7002");
        var originalRun = ctx.runIdentity();
        var otherOwner = com.skillforge.runtime.api.port.SessionOwner.user(new SessionRef(otherTenant, SESSION.sessionId()), AGENT, USER);
        var otherRun = new com.skillforge.runtime.api.port.RunIdentity(otherOwner, AGENT, originalRun.causationInputId(),
                new com.skillforge.runtime.api.RunRef(otherTenant, originalRun.runRef().runId()), originalRun.trace(), originalRun.causationIdentityRef(),
                originalRun.requester(), Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(otherTenant, originalRun.originRef().originId()));
        var other = new PortCallContext(otherRun, new com.skillforge.runtime.api.port.VerifiedIdentity(
                new com.skillforge.runtime.api.PrincipalRef.EndUserPrincipal(otherTenant, USER), otherOwner,
                ctx.identity().scopes(), 1, new com.skillforge.runtime.api.ResultRef("other-tenant-probe")), ctx.control());
        assertFalse(port.hasRecordedMutation(other, command.idempotencyKey()));
        DataSource unavailable = (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> { throw new SQLException("controlled_connection_failure"); });
        var broken = new JdbcMemoryPort(unavailable, JdbcMemoryPortContractTest::searchDocument);
        assertThrows(RuntimeException.class, () -> broken.hasRecordedMutation(ctx, command.idempotencyKey()));
    }

    @Test
    void acceptExistingProposalCommitsTwoHeadsAndReplaysOriginalReceiptAfterReconstruction() {
        var ctx = context(SESSION);
        MemoryFact current = applied(port.submit(createCommand("accept-current", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact proposal = applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("accept-proposal"),
                revisionProposal(current, current.owner(), current.type(), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM))));
        var command = new MemoryCommand.AcceptRevision(ctx, new IdempotencyKey("accept-existing"), current.ref(), 1, proposal.ref(), 1);
        var receipt = assertInstanceOf(MemoryMutationResult.Applied.class, port.submit(command));
        assertEquals(Optional.of(current), receipt.before());
        MemoryFact activated = receipt.fact();
        assertEquals(proposal.ref(), activated.ref());
        assertEquals(2, activated.revision());
        assertEquals(FactStatus.ACTIVE, activated.status());
        assertEquals(proposal.object(), activated.object());
        assertEquals(proposal.sourceEventIds(), activated.sourceEventIds());
        assertEquals(proposal.createdAt(), activated.createdAt());
        MemoryFact archived = port.findCurrent(ctx, current.ref()).orElseThrow();
        assertEquals(FactStatus.ARCHIVED, archived.status());
        assertEquals(Optional.of(proposal.ref()), archived.supersededBy());
        assertTrue(archived.validTo().isPresent());
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_heads"));
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions WHERE fact_data IS NOT NULL"));
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_sources"));
        applied(port.submit(new MemoryCommand.Transition(ctx, new IdempotencyKey("accepted-decay"), proposal.ref(), 2, FactStatus.DECAYED, "decay")));
        var replay = assertInstanceOf(MemoryMutationResult.Applied.class, recreatePort().submit(command));
        assertTrue(replay.replayed());
        assertEquals(activated, replay.fact());
        assertEquals(Optional.of(current), replay.before());
        assertEquals(FactStatus.DECAYED, port.findCurrent(ctx, proposal.ref()).orElseThrow().status());
        assertEquals(Optional.of(activated), port.findRevision(ctx, activated.ref(), activated.revision()));
        assertEquals(Optional.of(proposal), port.findRevision(ctx, proposal.ref(), 1));
        assertTrue(port.findRevision(ctx, new MemoryFactRef(new TenantId("other-tenant"), proposal.ref().factId()), 1).isEmpty());
        assertTrue(port.findRevision(context(HISTORICAL), proposal.ref(), 1).isEmpty());
        assertInstanceOf(MemoryMutationResult.Conflict.class, port.submit(new MemoryCommand.AcceptRevision(ctx,
                command.idempotencyKey(), current.ref(), 1, proposal.ref(), 2)));
    }

    @Test
    void acceptRevisionRejectsOwnerTypeLineageStateAndBothStaleRevisionsWithoutPartialWrites() {
        var ctx = context(SESSION);
        MemoryFact current = applied(port.submit(createCommand("accept-reject-current", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        List<MemoryFactDraft> invalid = List.of(
                revisionProposal(current, new MemoryOwner.User(TENANT, USER), current.type(), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM),
                revisionProposal(current, current.owner(), new MemoryTypeRef("episode"), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM),
                revisionProposal(current, current.owner(), current.type(), Optional.empty(), FactStatus.PENDING_CONFIRM),
                revisionProposal(current, current.owner(), current.type(), Optional.of(current.ref()), FactStatus.ACTIVE));
        for (int i = 0; i < invalid.size(); i++) {
            MemoryFact proposal = applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("accept-invalid-" + i), invalid.get(i))));
            long revisions = adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions");
            assertInstanceOf(MemoryMutationResult.Rejected.class, port.submit(new MemoryCommand.AcceptRevision(ctx,
                    new IdempotencyKey("accept-invalid-submit-" + i), current.ref(), 1, proposal.ref(), 1)));
            assertEquals(revisions, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions"));
            assertEquals(current, port.findCurrent(ctx, current.ref()).orElseThrow());
            assertEquals(proposal, port.findCurrent(ctx, proposal.ref()).orElseThrow());
        }
        MemoryFact proposal = applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("accept-valid-stale"),
                revisionProposal(current, current.owner(), current.type(), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM))));
        for (boolean staleCurrent : List.of(true, false)) {
            var conflict = assertInstanceOf(MemoryMutationResult.Conflict.class, port.submit(new MemoryCommand.AcceptRevision(ctx,
                    new IdempotencyKey("accept-stale-" + staleCurrent), current.ref(), staleCurrent ? 2 : 1, proposal.ref(), staleCurrent ? 1 : 2)));
            assertEquals(staleCurrent ? current.ref() : proposal.ref(), conflict.fact());
            assertEquals(current, port.findCurrent(ctx, current.ref()).orElseThrow());
            assertEquals(proposal, port.findCurrent(ctx, proposal.ref()).orElseThrow());
        }
        assertThrows(IllegalArgumentException.class, () -> new MemoryCommand.AcceptRevision(ctx, new IdempotencyKey("wrong-tenant"),
                current.ref(), 1, new MemoryFactRef(new TenantId("7002"), proposal.ref().factId()), 1));
    }

    @Test
    void acceptanceRollsBackBothHeadsWhenSearchProjectionCannotCommit() {
        var ctx = context(SESSION);
        MemoryFact current = applied(port.submit(createCommand("rollback-accept-current", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact proposal = applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("rollback-accept-proposal"),
                revisionProposal(current, current.owner(), current.type(), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM))));
        JdbcMemoryPort failing = new JdbcMemoryPort(dataSource(), fact -> { throw new IllegalStateException("controlled_projection_failure"); });
        assertInstanceOf(MemoryMutationResult.Rejected.class, failing.submit(new MemoryCommand.AcceptRevision(ctx,
                new IdempotencyKey("rollback-accept"), current.ref(), 1, proposal.ref(), 1)));
        assertEquals(current, port.findCurrent(ctx, current.ref()).orElseThrow());
        assertEquals(proposal, port.findCurrent(ctx, proposal.ref()).orElseThrow());
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions"));
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_memory_operations"));
    }

    @Test
    void concurrentAcceptancesChooseOneExistingProposalAndKeepLoserPending() throws Exception {
        var ctx = context(SESSION);
        MemoryFact current = applied(port.submit(createCommand("concurrent-accept-current", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        List<MemoryFact> proposals = new ArrayList<>();
        for (int i = 0; i < 2; i++) proposals.add(applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("concurrent-accept-proposal-" + i),
                revisionProposal(current, current.owner(), current.type(), Optional.of(current.ref()), FactStatus.PENDING_CONFIRM)))));
        // 两个事务均完成治理锁前的 owner 读取后才竞争，确保锁后 CAS 不复用等待前的 head。
        var governanceReads = new CountDownLatch(2);
        var competingPort = new JdbcMemoryPort(governanceBarrierSource(governanceReads), JdbcMemoryPortContractTest::searchDocument);
        var start = new CountDownLatch(1);
        List<MemoryMutationResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<MemoryMutationResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return competingPort.submit(new MemoryCommand.AcceptRevision(ctx, new IdempotencyKey("concurrent-accept-" + index),
                            current.ref(), 1, proposals.get(index).ref(), 1));
                }));
            }
            start.countDown();
            results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(MemoryMutationResult.Applied.class::isInstance).count(), () -> "acceptance outcomes=" + results);
            assertEquals(1, results.stream().filter(MemoryMutationResult.Conflict.class::isInstance).count(), () -> "acceptance outcomes=" + results);
        }
        assertEquals(3, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_heads"));
        assertEquals(1, proposals.stream().map(f -> port.findCurrent(ctx, f.ref()).orElseThrow()).filter(f -> f.status() == FactStatus.ACTIVE).count());
        assertEquals(1, proposals.stream().map(f -> port.findCurrent(ctx, f.ref()).orElseThrow()).filter(f -> f.status() == FactStatus.PENDING_CONFIRM).count());
        assertEquals(5, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions"));
        int loser = results.get(0) instanceof MemoryMutationResult.Conflict ? 0 : 1;
        assertEquals(proposals.get(loser), port.findCurrent(ctx, proposals.get(loser).ref()).orElseThrow());
        assertEquals(4, adminCount("SELECT count(*) FROM public.agent_runtime_memory_operations"));
        assertEquals(results.get(loser), recreatePort().submit(new MemoryCommand.AcceptRevision(ctx,
                new IdempotencyKey("concurrent-accept-" + loser), current.ref(), 1, proposals.get(loser).ref(), 1)));
        assertEquals(1, adminCount("SELECT count(*) FROM public.agent_runtime_memory_conflict_audits"));
        assertEquals(5, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_revisions"));
    }

    @Test
    void extractionSlotSerializesDifferentObjectsAndExactRelatedReadRetainsFutureIntervals() throws Exception {
        var ctx = context(SESSION);
        var template = memoryDraft(new EventId("source-" + SESSION.sessionId()), Optional.empty());
        var future = new MemoryFactDraft(template.owner(), template.type(), template.retentionClass(), template.schemaVersion(),
                template.subject(), template.predicate(), template.object(), template.sourceEventIds(), template.sourceSessionRef(),
                template.sourceExecutionRef(), template.exportPath(), template.extractorVersion(), Instant.now().plusSeconds(86400),
                Optional.empty(), Optional.empty(), template.confidence(), template.importance(), false, Optional.empty(), FactStatus.ACTIVE);
        var entered = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<MemoryFact>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return port.withExtractionSlot(ctx, template.owner(), template.type(), template.subject(), template.predicate(), () -> {
                        peak.accumulateAndGet(entered.incrementAndGet(), Math::max);
                        try {
                            var existing = port.relatedCurrentFacts(ctx, template.owner(), template.type(), template.subject(), template.predicate());
                            return existing.isEmpty() ? applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("slot-candidate-" + index), future))) : existing.getFirst();
                        } finally { entered.decrementAndGet(); }
                    });
                }));
            }
            start.countDown();
            assertEquals(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, peak.get());
        assertEquals(1, port.relatedCurrentFacts(ctx, template.owner(), template.type(), template.subject(), template.predicate()).size());
        assertTrue(port.relatedCurrentFacts(ctx, template.owner(), template.type(), template.subject(), 16).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> port.withExtractionSlot(ctx,
                new MemoryOwner.User(TENANT, OTHER_MEMBER), template.type(), template.subject(), template.predicate(), () -> null));
    }

    private static MemoryFactDraft revisionProposal(MemoryFact current, MemoryOwner owner, MemoryTypeRef type,
            Optional<MemoryFactRef> derivedFrom, FactStatus status) {
        return new MemoryFactDraft(owner, type, current.retentionClass(), current.schemaVersion(), current.subject(), current.predicate(),
                new TypedPayload(current.object().schemaId(), "{\"value\":\"revised preference\"}"), current.sourceEventIds(), current.sourceSessionRef(),
                current.sourceExecutionRef(), current.exportPath(), "revision-extractor", current.validFrom(), Optional.empty(), Optional.empty(),
                current.confidence(), current.importance(), true, derivedFrom, status);
    }

    @Test
    void statusRevisionsReuseOneBodyAndSourceAndHistoricalReceiptReplaysAfterRecreation() {
        var template = memoryDraft(new EventId("source-" + SESSION.sessionId()), Optional.empty());
        var large = new MemoryFactDraft(template.owner(),
                template.type(), template.retentionClass(), template.schemaVersion(), template.subject(), template.predicate(),
                new TypedPayload(template.object().schemaId(), "{\"value\":\"" + "x".repeat(16_000) + "\"}"),
                template.sourceEventIds(), template.sourceSessionRef(), template.sourceExecutionRef(), template.exportPath(),
                template.extractorVersion(), template.validFrom(), template.validTo(), template.expiresAt(), template.confidence(),
                template.importance(), template.reviewRequired(), template.derivedFrom(), template.initialStatus());
        MemoryFact first = applied(port.submit(new MemoryCommand.Create(context(SESSION), new IdempotencyKey("body-owner"), large)));
        long beforeBytes = adminCount("select sum(octet_length(fact_data)) from public.agent_runtime_memory_fact_revisions");
        var decay = new MemoryCommand.Transition(context(SESSION), new IdempotencyKey("body-decay"),
                first.ref(), 1, FactStatus.DECAYED, "decay");
        MemoryFact decayed = applied(port.submit(decay));
        assertEquals(expectedStateRevision(first, 2, FactStatus.DECAYED), decayed);
        MemoryFact restored = applied(port.submit(new MemoryCommand.Transition(context(SESSION), new IdempotencyKey("body-restore"),
                first.ref(), 2, FactStatus.ACTIVE, "restore")));
        assertEquals(3, restored.revision());
        assertEquals(expectedStateRevision(first, 3, FactStatus.ACTIVE), restored);
        assertEquals(beforeBytes, adminCount("select sum(coalesce(octet_length(fact_data),0)) from public.agent_runtime_memory_fact_revisions"));
        assertEquals(1, adminCount("select count(*) from public.agent_runtime_memory_fact_sources"));
        assertEquals(2, adminCount("select count(*) from public.agent_runtime_memory_fact_revisions where fact_data is null and content_revision=1"));
        assertTrue(adminCount("select max(octet_length(command_data)) from public.agent_runtime_memory_operations where command_kind='TRANSITION'") < 8_000);
        assertTrue(adminCount("select max(octet_length(command_data)) from public.agent_runtime_memory_operations where command_kind='CREATE'") < 8_000,
                "CREATE命令只保存上下文及草稿摘要，大正文由精确结果revision唯一拥有");
        var replay = assertInstanceOf(MemoryMutationResult.Applied.class, new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument).submit(decay));
        assertTrue(replay.replayed());
        assertEquals(Optional.of(first), replay.before());
        assertEquals(decayed, replay.fact());
        assertEquals(Optional.of(restored), new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument).findCurrent(context(SESSION), first.ref()));
    }

    @Test
    void groupOwnershipIsStoredWithoutInventedUserAndRejectsMixedOwnerColumns() throws Exception {
        var context = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        var draft = groupDraft(new MemoryOwner.GroupMember(GROUP, OTHER_MEMBER), GROUP_SESSION,
                FactStatus.PENDING_CONFIRM, "group memory", Optional.empty());
        var fact = applied(port.submit(new MemoryCommand.Create(context, new IdempotencyKey("group-owner-columns"), draft)));
        assertEquals(1, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_heads WHERE scope='GROUP_MEMBER' AND end_user_id IS NULL AND agent_id IS NULL AND session_id IS NULL AND group_binding_id='memory-group-binding' AND subject_end_user_id=9002"));
        assertEquals(1, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_sources WHERE source_target_kind='GROUP' AND agent_id IS NULL"));
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class, () -> statement.execute("""
                INSERT INTO public.agent_runtime_memory_fact_heads(tenant_id,fact_id,current_revision,end_user_id,agent_id,session_id,scope,type_id,group_binding_id,subject_end_user_id)
                SELECT tenant_id,'illegal-mixed-owner',current_revision,9001,agent_id,session_id,scope,type_id,group_binding_id,subject_end_user_id
                  FROM public.agent_runtime_memory_fact_heads WHERE fact_id='""" + fact.ref().factId() + "'") );
            assertEquals("23514", failure.getSQLState());
            assertTrue(failure.getMessage().contains("ck_memory_head_typed_owner"));
        }
        assertEquals(Optional.of(fact), new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument).findCurrent(context, fact.ref()));
        assertEquals(1, adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_heads"));
    }

    @Test
    void jdbcFactCodecRejectsLegacyOwnerFramesAndPreservesCurrentGroupOwnership() {
        var context = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        var fact = applied(port.submit(new MemoryCommand.Create(context, new IdempotencyKey("group-codec"),
                groupDraft(new MemoryOwner.Group(GROUP), GROUP_SESSION, FactStatus.ACTIVE, "group memory", Optional.empty()))));
        byte[] current = MemoryJdbcCodec.encodeFact(fact);
        assertEquals(fact, MemoryJdbcCodec.decodeFact(current));
        for (int version : List.of(1, 2)) {
            byte[] old = current.clone(); old[7] = (byte) version;
            assertThrows(IllegalArgumentException.class, () -> MemoryJdbcCodec.decodeFact(old));
        }
    }

    @Test
    void largeGroupFactUsesTypedSessionBodiesAndCanonicalDatabaseReadback() throws Exception {
        var context = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        var draft = groupDraft(new MemoryOwner.Group(GROUP), GROUP_SESSION, FactStatus.ACTIVE,
                "group-memory-" + "雪".repeat(8_000), Optional.empty());
        var mutation = new MemoryRuntime.MutationCall(context,
                new MemoryRuntime.Mutation.Create(new IdempotencyKey("group-large-runtime"), draft), NOW.plusSeconds(1));
        var recorded = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, runtime(port).mutate(mutation));
        var replay = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, runtime(new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument)).mutate(mutation));
        assertEquals(recorded.fact(), replay.fact());
        assertEquals(recorded.event(), replay.event());
        assertTrue(replay.memoryReplayed());
        assertEquals(recorded.event(), new JdbcSessionLogPort(dataSource()).findByEvent(context.runIdentity().owner(), recorded.event().eventId()).orElseThrow());
        assertTrue(adminCount("SELECT count(*) FROM public.agent_runtime_session_event_bodies WHERE target_kind='GROUP' AND end_user_id IS NULL")>0);
        try (var connection = dataSource().getConnection(); var tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)");
                var query = connection.prepareStatement("""
                SELECT public.agent_runtime_session_event_payload(tenant_id,session_id,target_kind,target_id,end_user_id,run_id,event_id,event_type,payload_schema_id,payload_canonical_json)
                  FROM public.agent_runtime_session_events WHERE tenant_id=? AND target_kind='GROUP' AND target_id=? AND event_id=?
                """)) {
            tenant.setString(1,TENANT.value()); tenant.executeQuery().close();
            query.setString(1,TENANT.value()); query.setString(2,GROUP.groupBindingId()); query.setString(3,recorded.event().eventId().value());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals(recorded.event().payload().canonicalJson(),rows.getString(1));
            }
        }
    }

    @Test
    void sameSessionTextCannotReadAnotherGroupTargetsSessionFacts() {
        var context = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        var fact = applied(port.submit(new MemoryCommand.Create(context, new IdempotencyKey("session-group-target"),
                groupDraft(new MemoryOwner.Session(GROUP_SESSION), GROUP_SESSION, FactStatus.ACTIVE, "group memory", Optional.empty()))));
        var anotherTarget = groupContext(GROUP_SESSION, OTHER_GROUP, USER, AGENT);
        assertEquals(Optional.empty(), port.findCurrent(anotherTarget, fact.ref()));
        assertEquals(List.of(Optional.empty()), port.findCurrent(new CurrentFactReadBatch(List.of(new CurrentFactRead(anotherTarget, fact.ref())))));
        var query = new MemoryQuery("same-session-other-target", new MemoryOwner.Session(GROUP_SESSION), Optional.empty(),
                "group memory", Optional.empty(), Set.of(MemoryScope.SESSION), Set.of(TYPE), MemoryQueryProfile.DEFAULT, NOW.plusSeconds(1), MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
        var result = assertInstanceOf(MemoryQueryOutcome.Retrieved.class, port.query(new MemoryQueryCall(anotherTarget, query)));
        assertTrue(result.result().candidates().isEmpty());
        assertEquals(Optional.of(fact), port.findCurrent(context, fact.ref()));
    }

    @Test
    void hybridRetrievalFreezesQueryVectorAcrossNewControlAndVerification() {
        var model=new MemoryRetrievalPolicy.EmbeddingModel("embedding-fixture",1,3);
        var policy=hybridPolicy(model,MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var store=hybridStore(policy,request->{calls.incrementAndGet();return new MemorySearchEmbedding.Outcome.Available(List.of(1.0,0.0,0.0));});
        var ctx=groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        var first=applied(store.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("hybrid-tea"),groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"prefer tea",Optional.empty()))));
        var second=applied(store.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("hybrid-book"),groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"book appointment",Optional.empty()))));
        assertTrue(store.completeEmbedding(store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow(),List.of(1.0,0.0,0.0)));
        assertTrue(store.completeEmbedding(store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow(),List.of(0.0,1.0,0.0)));
        var query=hybridQuery("semantic-frozen",policy,"concept with no lexical overlap");
        var result=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,store.query(new MemoryQueryCall(ctx,query)));
        assertEquals(1,result.result().candidates().size());
        assertEquals(com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.HYBRID,result.result().diagnostics().mode());
        assertEquals(2,result.result().diagnostics().readyVectorCount());
        var renewed=new PortCallContext(ctx.runIdentity(),new com.skillforge.runtime.api.port.VerifiedIdentity(ctx.identity().principal(),ctx.identity().owner(),ctx.identity().scopes(),2,new com.skillforge.runtime.api.ResultRef("renewed-verification")),
                new com.skillforge.runtime.api.port.InvocationControl(Instant.parse("2101-01-01T00:00:00Z"),()->false));
        var replay=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,store.query(new MemoryQueryCall(renewed,query)));
        assertEquals(result.result().candidates(),replay.result().candidates());assertEquals(1,calls.get());
        var conflict=assertInstanceOf(MemoryQueryOutcome.Failed.class,store.query(new MemoryQueryCall(renewed,hybridQuery("semantic-frozen",policy,"changed request"))));
        assertEquals("memory_query_embedding_request_conflict",conflict.failure().reasonCode());assertEquals(1,calls.get());
        var fallbackPolicy=hybridPolicy(model,MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK);
        var fallbackConflict=assertInstanceOf(MemoryQueryOutcome.Failed.class,store.query(new MemoryQueryCall(renewed,hybridQuery("semantic-frozen",fallbackPolicy,"changed request"))));
        assertEquals("memory_query_embedding_request_conflict",fallbackConflict.failure().reasonCode());assertEquals(1,calls.get());
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_query_embeddings"));
    }

    @Test
    void failPolicyRechecksCoverageAfterQueryEmbeddingAndReusesOriginalVector() {
        assertFinalCoverageInterleaving(MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
    }

    @Test
    void fallbackPolicyRechecksCoverageAfterQueryEmbeddingAndUsesOnlyLexicalCandidates() {
        assertFinalCoverageInterleaving(MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK);
    }

    private static void assertFinalCoverageInterleaving(MemoryRetrievalPolicy.VectorFailurePolicy failurePolicy) {
        var policy=hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-interleave",1,3),failurePolicy);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var holder=new java.util.concurrent.atomic.AtomicReference<JdbcMemoryPort>();
        var ctx=groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        var store=hybridStore(policy,request->{
            calls.incrementAndGet();
            applied(holder.get().submit(new MemoryCommand.Create(ctx,new IdempotencyKey("interleaved-new-document"),
                    groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"coffee",Optional.empty()))));
            return new MemorySearchEmbedding.Outcome.Available(List.of(1.0,0.0,0.0));
        });holder.set(store);
        var tea=applied(store.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("interleave-original"),
                groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"tea",Optional.empty()))));
        assertTrue(store.completeEmbedding(store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow(),List.of(1.0,0.0,0.0)));
        var query=hybridQuery("interleaved-coverage",policy,"tea");
        var outcome=store.query(new MemoryQueryCall(ctx,query));
        if(failurePolicy==MemoryRetrievalPolicy.VectorFailurePolicy.FAIL){
            assertEquals("memory_vector_coverage_incomplete",assertInstanceOf(MemoryQueryOutcome.Failed.class,outcome).failure().reasonCode());
        }else{
            var result=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,outcome).result();
            assertEquals(Optional.of("memory_vector_coverage_incomplete"),result.diagnostics().degradationReason());
            assertEquals(com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.LEXICAL,result.diagnostics().mode());
            assertEquals(List.of(tea),result.candidates());
            assertTrue(result.diagnostics().candidates().stream().allMatch(candidate->candidate.cosineSimilarity().isEmpty()));
        }
        assertEquals(1,calls.get());assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_query_embeddings WHERE state='READY'"));
        assertTrue(store.completeEmbedding(store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow(),List.of(0.0,1.0,0.0)));
        var recovered=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,store.query(new MemoryQueryCall(ctx,query))).result();
        assertEquals(com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.HYBRID,recovered.diagnostics().mode());
        assertEquals(1,calls.get());
    }

    @Test
    void incompleteVectorCoverageIsExplicitAndUnknownQueryIsNotRedispatched() {
        var policy=hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-unknown",1,3),MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var store=hybridStore(policy,request->{calls.incrementAndGet();return new MemorySearchEmbedding.Outcome.OutcomeUnknown("provider_unknown");});
        var ctx=groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        applied(store.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("vector-unknown"),groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"prefer tea",Optional.empty()))));
        var failed=assertInstanceOf(MemoryQueryOutcome.Failed.class,store.query(new MemoryQueryCall(ctx,hybridQuery("coverage",policy,"tea"))));
        assertEquals("memory_vector_coverage_incomplete",failed.failure().reasonCode());assertEquals(0,calls.get());
        var fallbackPolicy=hybridPolicy(policy.embeddingModel().orElseThrow(),MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK);
        var fallback=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,store.query(new MemoryQueryCall(ctx,hybridQuery("coverage-fallback",fallbackPolicy,"tea"))));
        assertEquals(Optional.of("memory_vector_coverage_incomplete"),fallback.result().diagnostics().degradationReason());assertEquals(1,fallback.result().candidates().size());
        assertTrue(store.completeEmbedding(store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow(),List.of(1.0,0.0,0.0)));
        var query=hybridQuery("unknown-original",policy,"tea");
        for(int i=0;i<2;i++){
            var unknown=assertInstanceOf(MemoryQueryOutcome.Failed.class,store.query(new MemoryQueryCall(ctx,query)));
            assertEquals("provider_unknown",unknown.failure().reasonCode());
        }
        assertEquals(1,calls.get());assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_query_embeddings WHERE state='UNKNOWN'"));
    }

    @Test
    void indexingRebuildUsesOriginalIdentityAndExpiredClaimBecomesUnknown() throws Exception {
        var ctx=groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        var fact=applied(port.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("rebuild-original"),groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"prefer tea",Optional.empty()))));
        var policy=hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-rebuild",2,3),MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var store=hybridStore(policy,request->new MemorySearchEmbedding.Outcome.Available(List.of(1.0,0.0,0.0)));
        assertEquals(1,store.enqueueMissing(TENANT,policy,20));
        var work=store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMillis(1)).orElseThrow();
        assertEquals(ctx.runIdentity(),work.runIdentity());assertEquals(ctx.identity(),work.identity());
        while(databaseTime().isBefore(work.leaseUntil()))Thread.onSpinWait();
        assertTrue(store.claimEmbedding(TENANT,"other-worker",java.time.Duration.ofMinutes(1)).isEmpty());
        assertFalse(store.completeEmbedding(work,List.of(1.0,0.0,0.0)));
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_embeddings WHERE state='UNKNOWN'"));
        assertEquals(Optional.of(fact),store.findCurrent(ctx,fact.ref()));
        var rebuildFact=applied(port.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("rebuild-derived-body"),groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"prefer coffee",Optional.empty()))));
        executeAdmin("DELETE FROM public.agent_runtime_memory_search_documents WHERE fact_id='"+rebuildFact.ref().factId()+"'");
        var missing=assertInstanceOf(MemoryQueryOutcome.Failed.class,store.query(new MemoryQueryCall(ctx,hybridQuery("missing-body",policy,"tea"))));
        assertEquals("memory_search_projection_missing",missing.failure().reasonCode());
        assertEquals(1,store.enqueueMissing(TENANT,policy,20));
        var recovered=store.claimEmbedding(TENANT,"rebuild-worker",java.time.Duration.ofMinutes(1)).orElseThrow();
        assertEquals(ctx.runIdentity(),recovered.runIdentity());assertEquals(ctx.identity(),recovered.identity());
        assertThrows(IllegalArgumentException.class,()->store.completeEmbedding(recovered,List.of(0.0,0.0,0.0)));
        assertTrue(store.failEmbedding(recovered,"controlled-rejection",false));
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_embeddings WHERE state='UNKNOWN'"));
    }

    @Test
    void successfulIndexRebuildRetainsFrozenRevisionAndNeverBackfillsSupersededOrForgottenFacts() throws Exception {
        var ctx = context(SESSION);
        var owner = new MemoryOwner.UserAgent(TENANT, USER, AGENT);
        var draft = new MemoryFactDraft(owner, TYPE, new RetentionClassRef("standard"), "1",
                "prefer tea", "remembers", new TypedPayload("memory.preference.v1", "{\"value\":\"prefer tea\"}"),
                List.of(new EventId("source-" + SESSION.sessionId())), SESSION, Optional.empty(), Optional.empty(),
                "extractor-v1", NOW.minusSeconds(60), Optional.empty(), Optional.empty(), 0.9, 0.8,
                false, Optional.empty(), FactStatus.ACTIVE);
        var original = applied(port.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("lifecycle-original"),
                draft)));
        java.util.function.BiFunction<String, MemoryRetrievalPolicy, MemoryQuery> queryForUser = (id, retrievalPolicy) ->
                new MemoryQuery(id, owner, Optional.empty(), "concept with no lexical overlap", Optional.empty(),
                        Set.of(MemoryScope.USER_AGENT), Set.of(TYPE), MemoryQueryProfile.DEFAULT, Instant.now(),
                        retrievalPolicy, 10, 10_000, 10_000, true);
        java.util.function.Function<JdbcMemoryPort, MemoryRuntime> runtimeForUser = memory -> new MemoryRuntime(
                memory, new JdbcSessionLogPort(dataSource()),
                request -> new MemoryAccessPolicy.Decision.Allowed(new com.skillforge.runtime.api.ResultRef("memory-access-audit")),
                type -> type.equals(TYPE) ? Optional.of(new MemoryTypeRegistry.Registration(TYPE, Set.of(MemoryScope.USER_AGENT),
                        List.of(new MemoryTypeRegistry.Schema("1", "memory.preference.v1")), new RetentionClassRef("standard"),
                        false, false, (version, payload) -> new MemoryTypeRegistry.Validation.Allowed())) : Optional.empty(),
                request -> new MemorySourcePolicy.Decision.Allowed(new com.skillforge.runtime.api.ResultRef("memory-source-audit")),
                memory::findCurrent, Clock.fixed(NOW, ZoneOffset.UTC));
        var policy = hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-lifecycle", 1, 3),
                MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var queryCalls = new java.util.concurrent.atomic.AtomicInteger();
        MemorySearchEmbedding embedding = request -> {
            queryCalls.incrementAndGet();
            return new MemorySearchEmbedding.Outcome.Available(List.of(1.0, 0.0, 0.0));
        };
        var store = hybridStore(policy, embedding);

        // 从现有事实补建派生向量；受控向量只验证索引生命周期，不代表模型语义质量。
        assertEquals(1, store.enqueueMissing(TENANT, policy, 20));
        var work = store.claimEmbedding(TENANT, "lifecycle-worker", java.time.Duration.ofMinutes(1)).orElseThrow();
        assertEquals(ctx.runIdentity(), work.runIdentity());
        assertEquals(ctx.identity(), work.identity());
        assertTrue(store.completeEmbedding(work, List.of(1.0, 0.0, 0.0)));
        var query = queryForUser.apply("lifecycle-frozen", policy);
        var retrieval = assertInstanceOf(MemoryRuntime.RetrievalResult.Recorded.class,
                runtimeForUser.apply(store).retrieve(new MemoryRuntime.RetrievalCall(ctx, query)));
        var frozenEvent = retrieval.receipt().committedEvent();
        assertEquals(List.of(original), retrieval.receipt().candidateFacts());
        assertEquals(1, queryCalls.get());

        // 当前治理版本推进后，重建组件恢复原始候选版本，不重搜、不重新调用模型。
        applied(store.submit(new MemoryCommand.Transition(ctx, new IdempotencyKey("lifecycle-decay"),
                original.ref(), 1, FactStatus.DECAYED, "decay")));
        var restored = applied(store.submit(new MemoryCommand.Transition(ctx, new IdempotencyKey("lifecycle-restore"),
                original.ref(), 2, FactStatus.ACTIVE, "restore")));
        var rebuilt = hybridStore(policy, embedding);
        assertEquals(3, restored.revision());
        assertEquals(List.of(original), runtimeForUser.apply(rebuilt).replayRetrieval(ctx, frozenEvent).candidateFacts());
        assertEquals(Optional.of(restored), rebuilt.findCurrent(ctx, original.ref()));
        assertEquals(1, queryCalls.get());

        // 策略切换追加新模型版本，保留原向量和冻结回执。
        var nextPolicy = hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-lifecycle", 2, 3),
                MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var next = hybridStore(nextPolicy, embedding);
        assertEquals(1, next.enqueueMissing(TENANT, nextPolicy, 20));
        var nextWork = next.claimEmbedding(TENANT, "lifecycle-next-worker", java.time.Duration.ofMinutes(1)).orElseThrow();
        assertEquals(ctx.runIdentity(), nextWork.runIdentity());
        assertEquals(ctx.identity(), nextWork.identity());
        assertEquals(2, nextWork.model().revision());
        assertTrue(next.completeEmbedding(nextWork, List.of(1.0, 0.0, 0.0)));
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_memory_embeddings WHERE state='READY'"));
        assertEquals(List.of(original), runtimeForUser.apply(next).replayRetrieval(ctx, frozenEvent).candidateFacts());
        assertEquals(1, queryCalls.get());

        var proposal = applied(next.submit(new MemoryCommand.Create(ctx, new IdempotencyKey("lifecycle-proposal"),
                revisionProposal(restored, restored.owner(), restored.type(), Optional.of(restored.ref()), FactStatus.PENDING_CONFIRM))));
        var accepted = applied(next.submit(new MemoryCommand.AcceptRevision(ctx, new IdempotencyKey("lifecycle-accept"),
                restored.ref(), restored.revision(), proposal.ref(), proposal.revision())));
        assertTrue(next.completeEmbedding(next.claimEmbedding(TENANT, "lifecycle-revision-worker",
                java.time.Duration.ofMinutes(1)).orElseThrow(), List.of(1.0, 0.0, 0.0)));
        var revised = assertInstanceOf(MemoryQueryOutcome.Retrieved.class, next.query(new MemoryQueryCall(ctx,
                queryForUser.apply("lifecycle-revised", nextPolicy))));
        assertEquals(List.of(accepted), revised.result().candidates());

        var forgotten = applied(next.submit(new MemoryCommand.Transition(ctx, new IdempotencyKey("lifecycle-forget"),
                accepted.ref(), accepted.revision(), FactStatus.FORGOTTEN, "explicit forget")));
        assertEquals(3, restrictedMemorySql("DELETE FROM public.agent_runtime_memory_embeddings"));
        assertEquals(2, restrictedMemorySql("DELETE FROM public.agent_runtime_memory_search_documents"));
        var recovered = hybridStore(nextPolicy, embedding);
        assertEquals(0, recovered.enqueueMissing(TENANT, nextPolicy, 20));
        assertTrue(recovered.claimEmbedding(TENANT, "lifecycle-recovery-worker", java.time.Duration.ofMinutes(1)).isEmpty());
        var empty = assertInstanceOf(MemoryQueryOutcome.Retrieved.class, recovered.query(new MemoryQueryCall(ctx,
                queryForUser.apply("lifecycle-after-forget", nextPolicy))));
        assertTrue(empty.result().candidates().isEmpty());
        assertEquals(2, queryCalls.get(), "没有可用事实时不得发起新的查询向量调用");
        assertEquals(Optional.of(forgotten), recovered.findCurrent(ctx, accepted.ref()));
        assertEquals(Optional.of(original), recovered.findRevision(ctx, original.ref(), original.revision()));
        assertEquals(Optional.of(accepted), recovered.findRevision(ctx, accepted.ref(), accepted.revision()));
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class,
                () -> runtimeForUser.apply(recovered).replayRetrieval(ctx, frozenEvent)).getMessage());
    }

    @Test
    void restrictedRoleCannotRewriteEmbeddingIdentityOrDeleteUnresolvedOperations() throws Exception {
        var policy=hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-state-guard",1,3),MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var store=hybridStore(policy,request->new MemorySearchEmbedding.Outcome.Available(List.of(1.0,0.0,0.0)));
        var ctx=groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        var first=applied(store.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("guard-first-index"),
                groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"tea",Optional.empty()))));
        var running=store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow();
        var wrong=new MemoryEmbeddingWork(running.tenantId(),"wrong-work-fact",running.contentRevision(),running.projectionVersion(),running.analyzerVersion(),
                running.model(),running.textDigest(),running.text(),running.invocationId(),running.leaseToken(),running.leaseUntil(),running.runIdentity(),running.identity());
        assertFalse(store.completeEmbedding(wrong,List.of(1.0,0.0,0.0)));
        assertFalse(store.failEmbedding(wrong,"wrong-finish",false));
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_embeddings WHERE state='RUNNING'"));
        assertRestrictedMemorySqlRejected("DELETE FROM public.agent_runtime_memory_embeddings WHERE fact_id='"+first.ref().factId()+"'","memory_embedding_unresolved_operation_delete_forbidden");
        assertTrue(store.completeEmbedding(running,List.of(1.0,0.0,0.0)));
        assertRestrictedMemorySqlRejected("UPDATE public.agent_runtime_memory_embeddings SET model_id='forged-model'","memory_embedding_operation_identity_or_state_mismatch");
        assertRestrictedMemorySqlRejected("UPDATE public.agent_runtime_memory_embeddings SET state='QUEUED',embedding=NULL","memory_embedding_operation_identity_or_state_mismatch");
        assertInstanceOf(MemoryQueryOutcome.Retrieved.class,store.query(new MemoryQueryCall(ctx,hybridQuery("guard-original-query",policy,"tea"))));
        assertRestrictedMemorySqlRejected("UPDATE public.agent_runtime_memory_query_embeddings SET fingerprint=repeat('0',64)","memory_query_embedding_operation_identity_or_state_mismatch");
        assertRestrictedMemorySqlRejected("UPDATE public.agent_runtime_memory_query_embeddings SET state='RUNNING',embedding=NULL","memory_query_embedding_operation_identity_or_state_mismatch");
        assertRestrictedMemorySqlRejected("UPDATE public.agent_runtime_memory_search_documents SET identity_data='forged-source'","agent runtime memory history is immutable");
        var second=applied(store.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("guard-unknown-index"),
                groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"coffee",Optional.empty()))));
        var unknown=store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).orElseThrow();
        assertTrue(store.failEmbedding(unknown,"provider_unknown",true));
        assertRestrictedMemorySqlRejected("DELETE FROM public.agent_runtime_memory_embeddings WHERE fact_id='"+second.ref().factId()+"'","memory_embedding_unresolved_operation_delete_forbidden");
        assertRestrictedMemorySqlRejected("UPDATE public.agent_runtime_memory_embeddings SET state='QUEUED',reason_code=NULL WHERE state='UNKNOWN'","memory_embedding_operation_identity_or_state_mismatch");
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_embeddings WHERE state='UNKNOWN'"));
        assertEquals(1,restrictedMemorySql("DELETE FROM public.agent_runtime_memory_embeddings WHERE fact_id='"+first.ref().factId()+"'"));
        assertEquals(1,restrictedMemorySql("DELETE FROM public.agent_runtime_memory_search_documents WHERE fact_id='"+first.ref().factId()+"'"));
        assertEquals(2,adminCount("SELECT count(*) FROM public.agent_runtime_memory_fact_heads"));
        assertEquals(Optional.of(first),store.findCurrent(ctx,first.ref()));
        assertTrue(store.claimEmbedding(TENANT,"worker",java.time.Duration.ofMinutes(1)).isEmpty());
    }

    private static void assertRestrictedMemorySqlRejected(String sql,String reason){
        var failure=assertThrows(SQLException.class,()->restrictedMemorySql(sql));
        assertEquals("P0001",failure.getSQLState());assertTrue(failure.getMessage().contains(reason),failure.getMessage());
    }
    private static int restrictedMemorySql(String sql)throws SQLException{
        try(var connection=dataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var tenant=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                tenant.setString(1,TENANT.value());tenant.executeQuery().close();
            }
            try(var statement=connection.createStatement()){
                int result=statement.executeUpdate(sql);connection.commit();return result;
            }catch(SQLException failure){connection.rollback();throw failure;}
        }
    }

    @Test
    void humanLabeledSemanticEvaluationUsesFormalMemoryConsumersAndControlledVectors() throws Exception {
        var foreignSession=new SessionRef(new TenantId("7002"),"memory-semantic-foreign");
        try(var connection=dataSource().getConnection()){establishSession(connection,foreignSession);}
        var template=context(SESSION);var original=template.runIdentity();
        var owner=com.skillforge.runtime.api.port.SessionOwner.user(foreignSession,AGENT,USER);
        var run=new com.skillforge.runtime.api.port.RunIdentity(owner,AGENT,original.causationInputId(),
                new com.skillforge.runtime.api.RunRef(foreignSession.tenantId(),"memory-semantic-foreign-run"),original.trace(),
                new com.skillforge.runtime.api.ResultRef("foreign-causation"),new com.skillforge.runtime.api.RunRequester.EndUser(USER),Optional.empty(),
                new com.skillforge.runtime.api.RunOriginRef(foreignSession.tenantId(),"foreign-origin"));
        var identity=new com.skillforge.runtime.api.port.VerifiedIdentity(new com.skillforge.runtime.api.PrincipalRef.EndUserPrincipal(foreignSession.tenantId(),USER),owner,
                Set.of("runtime:memory"),1,new com.skillforge.runtime.api.ResultRef("foreign-verification"));
        var foreign=new PortCallContext(run,identity,template.control());
        com.skillforge.runtime.adapter.jdbc.memory.search.SemanticMemoryRetrievalDatabaseEvaluation.evaluate(dataSource(),context(SESSION),
                groupContext(GROUP_SESSION,GROUP,USER,AGENT),OTHER_MEMBER,foreign);
    }

    @Test
    void postgresTsqueryQuotesRemainLiteralLexemes() throws Exception {
        var tokens=List.of("o'reilly","a|b","!x","back\\slash");
        String query=com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchAnalyzer.queryLexemes(tokens);
        try(var connection=dataSource().getConnection();var statement=connection.prepareStatement("SELECT array_to_tsvector(?::text[]) @@ ?::tsquery")){
            statement.setArray(1,connection.createArrayOf("text",tokens.toArray()));statement.setString(2,query);
            try(var rows=statement.executeQuery()){assertTrue(rows.next());assertTrue(rows.getBoolean(1));}
        }
    }

    private static MemoryRetrievalPolicy hybridPolicy(MemoryRetrievalPolicy.EmbeddingModel model,MemoryRetrievalPolicy.VectorFailurePolicy failure){
        var base=MemoryRetrievalPolicy.lexicalV1();return new MemoryRetrievalPolicy("memory-hybrid","1",base.projectionVersion(),base.analyzerVersion(),64,60,0.7,Optional.of(model),failure);
    }
    private static JdbcMemoryPort hybridStore(MemoryRetrievalPolicy policy,MemorySearchEmbedding embedding){
        return new JdbcMemoryPort(dataSource(),(session,command,current)->true,JdbcMemoryPortContractTest::searchDocument,tenant->policy,embedding);
    }
    private static MemoryQuery hybridQuery(String id,MemoryRetrievalPolicy policy,String text){
        return new MemoryQuery(id,new MemoryOwner.Group(GROUP),Optional.empty(),text,Optional.empty(),Set.of(MemoryScope.GROUP),Set.of(TYPE),MemoryQueryProfile.DEFAULT,
                Instant.now(),policy,10,10_000,10_000,true);
    }

    @Test
    void exactAutomaticPreferenceReuseRetainsNewSourceAndHistoricReplayWithoutDuplicatingFact() {
        MemoryFact first = applied(port.submit(new MemoryCommand.Create(extractionContext(SESSION),
                new IdempotencyKey("automatic-first"), automaticPreferenceDraft(SESSION, MemoryScope.USER_AGENT))));
        var reuseContext = extractionContext(HISTORICAL);
        var draft = automaticPreferenceDraft(HISTORICAL, MemoryScope.USER_AGENT);
        var audit = new com.skillforge.runtime.api.ResultRef("access-audit");
        var sourceAudit = new com.skillforge.runtime.api.ResultRef("source-audit");
        MemoryFact reused = port.reuseOrCreateExtractedPreference(reuseContext, draft, audit, sourceAudit,
                existing -> existing.orElseThrow());
        assertEquals(first, reused);
        assertEquals(1, adminCount("select count(*) from public.agent_runtime_memory_fact_heads"));
        assertEquals(1, adminCount("select count(*) from public.agent_runtime_memory_fact_revisions"));
        assertEquals(1, adminCount("select count(*) from public.agent_runtime_memory_fact_sources"));
        assertEquals(1, adminCount("select count(*) from public.agent_runtime_memory_extraction_evidence"));
        assertEquals("missing", port.reuseOrCreateExtractedPreference(reuseContext,
                automaticPreferenceDraft(HISTORICAL, MemoryScope.USER), audit, sourceAudit,
                existing -> existing.isEmpty() ? "missing" : "unsafe-scope-reuse"));
        MemoryFact decayed = applied(port.submit(new MemoryCommand.Transition(reuseContext,
                new IdempotencyKey("automatic-decay"), first.ref(), 1, FactStatus.DECAYED, "decay")));
        var rebuilt = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        assertEquals(first, rebuilt.reuseOrCreateExtractedPreference(reuseContext, draft, audit, sourceAudit,
                existing -> existing.orElseThrow()));
        assertEquals(Optional.of(decayed), rebuilt.findCurrent(reuseContext, first.ref()));
        assertEquals(1, adminCount("select count(*) from public.agent_runtime_memory_extraction_evidence"));
    }

    @Test
    void forgottenSourceSurvivesNewIdsExtractorVersionsAndExplicitRestoreWhileNewExpressionRequiresConfirmation() throws Exception {
        var originalDraft = automaticPreferenceDraft(SESSION, MemoryScope.USER_AGENT);
        MemoryFact original = applied(port.submit(new MemoryCommand.Create(extractionContext(SESSION),
                new IdempotencyKey("forget-original"), originalDraft)));
        port.reuseOrCreateExtractedPreference(extractionContext(HISTORICAL), automaticPreferenceDraft(HISTORICAL, MemoryScope.USER_AGENT),
                new com.skillforge.runtime.api.ResultRef("reuse-access"), new com.skillforge.runtime.api.ResultRef("reuse-source"),
                existing -> existing.orElseThrow());
        MemoryFact forgotten = applied(port.submit(new MemoryCommand.Transition(context(SESSION),
                new IdempotencyKey("forget-original-command"), original.ref(), 1, FactStatus.FORGOTTEN, "user_request")));
        var rebuilt = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        var changedIdentity = extractionVariant(originalDraft, new MemoryTypeRef("episode"), "renamed-subject",
                "renamed-predicate", "extractor-next-version", false);
        var replayedSource = assertInstanceOf(MemoryMutationResult.Rejected.class, rebuilt.submit(new MemoryCommand.Create(
                extractionContext(SESSION), new IdempotencyKey("new-task-new-candidate"), changedIdentity)));
        assertEquals("memory_forgotten_source_suppressed", replayedSource.failure().reasonCode());
        assertEquals("memory_forgotten_source_suppressed", assertInstanceOf(MemoryMutationResult.Rejected.class,
                rebuilt.submit(new MemoryCommand.Create(extractionContext(HISTORICAL), new IdempotencyKey("reused-source-new-task"),
                        extractionVariant(automaticPreferenceDraft(HISTORICAL, MemoryScope.USER_AGENT), new MemoryTypeRef("episode"),
                                "new-subject", "new-predicate", "extractor-new", false)))).failure().reasonCode());

        var newSession = new SessionRef(TENANT, "forget-new-explicit-expression");
        try (var connection = dataSource().getConnection()) {
            establishSession(connection, newSession, Instant.now().plusSeconds(1));
        }
        var newExpression = automaticPreferenceDraft(newSession, MemoryScope.USER_AGENT);
        assertEquals(JdbcMemoryPort.ExtractionGovernance.FORGOTTEN_SLOT_REVIEW,
                rebuilt.extractionGovernance(extractionContext(newSession), newExpression));
        assertEquals("memory_forgotten_slot_confirmation_required", assertInstanceOf(MemoryMutationResult.Rejected.class,
                rebuilt.submit(new MemoryCommand.Create(extractionContext(newSession), new IdempotencyKey("new-expression-active"),
                        newExpression))).failure().reasonCode());
        var pending = applied(rebuilt.submit(new MemoryCommand.Create(extractionContext(newSession), new IdempotencyKey("new-expression-pending"),
                extractionVariant(newExpression, newExpression.type(), newExpression.subject(), newExpression.predicate(), "extractor-next-version", true))));
        assertEquals(FactStatus.PENDING_CONFIRM, pending.status());
        assertEquals(FactStatus.ACTIVE, applied(rebuilt.submit(new MemoryCommand.Transition(context(newSession),
                new IdempotencyKey("confirm-new-expression"), pending.ref(), 1, FactStatus.ACTIVE, "user_confirmed"))).status());
        applied(rebuilt.submit(new MemoryCommand.Transition(context(SESSION), new IdempotencyKey("explicit-restore-original"),
                original.ref(), forgotten.revision(), FactStatus.ACTIVE, "user_restore")));
        assertEquals("memory_forgotten_source_suppressed", assertInstanceOf(MemoryMutationResult.Rejected.class,
                rebuilt.submit(new MemoryCommand.Create(extractionContext(SESSION), new IdempotencyKey("old-source-after-restore"),
                        originalDraft))).failure().reasonCode());
        assertEquals(2, adminCount("select count(*) from public.agent_runtime_memory_fact_heads"));
    }

    @Test
    void supersededPreferenceSuppressesOriginalAndOlderSourcesWithoutSuppressingIndependentEpisodes() {
        MemoryFact first = applied(port.submit(new MemoryCommand.Create(extractionContext(SESSION), new IdempotencyKey("correction-original"),
                automaticPreferenceDraft(SESSION, MemoryScope.USER_AGENT))));
        var baseline = automaticPreferenceDraft(HISTORICAL, MemoryScope.USER_AGENT);
        var replacement = new MemoryFactDraft(baseline.owner(), baseline.type(), baseline.retentionClass(), baseline.schemaVersion(),
                baseline.subject(), baseline.predicate(), new TypedPayload(baseline.object().schemaId(), "{\"value\":\"light\"}"),
                baseline.sourceEventIds(), baseline.sourceSessionRef(), baseline.sourceExecutionRef(), baseline.exportPath(),
                baseline.extractorVersion(), baseline.validFrom(), baseline.validTo(), baseline.expiresAt(), baseline.confidence(),
                baseline.importance(), false, Optional.of(first.ref()), FactStatus.ACTIVE);
        applied(port.submit(new MemoryCommand.Supersede(context(HISTORICAL), new IdempotencyKey("correction-accepted"), first.ref(), 1, replacement)));
        var oldSource = assertInstanceOf(MemoryMutationResult.Rejected.class, port.submit(new MemoryCommand.Create(extractionContext(SESSION),
                new IdempotencyKey("correction-original-new-id"), automaticPreferenceDraft(SESSION, MemoryScope.USER_AGENT))));
        assertEquals("memory_superseded_source_suppressed", oldSource.failure().reasonCode());
        var olderIndependentSource = assertInstanceOf(MemoryMutationResult.Rejected.class, port.submit(new MemoryCommand.Create(extractionContext(HISTORICAL),
                new IdempotencyKey("older-task-preference"), baseline)));
        assertEquals("memory_superseded_source_suppressed", olderIndependentSource.failure().reasonCode());
        var independentEpisode = extractionVariant(baseline, new MemoryTypeRef("episode"), baseline.subject(), baseline.predicate(), "episode-extractor", false);
        assertEquals(FactStatus.ACTIVE, applied(port.submit(new MemoryCommand.Create(extractionContext(HISTORICAL),
                new IdempotencyKey("independent-old-episode"), independentEpisode))).status());
    }

    private static MemoryFactDraft extractionVariant(MemoryFactDraft baseline, MemoryTypeRef type, String subject,
            String predicate, String extractor, boolean review) {
        return new MemoryFactDraft(baseline.owner(), type, baseline.retentionClass(), baseline.schemaVersion(), subject,
                predicate, baseline.object(), baseline.sourceEventIds(), baseline.sourceSessionRef(), baseline.sourceExecutionRef(),
                baseline.exportPath(), extractor, baseline.validFrom(), baseline.validTo(), baseline.expiresAt(),
                baseline.confidence(), baseline.importance(), review, Optional.empty(), review ? FactStatus.PENDING_CONFIRM : FactStatus.ACTIVE);
    }

    @Test
    void supersededHistoryReclaimsOnlyUnreferencedStateRowsAndKeepsReplayPredecessor() throws Exception {
        MemoryFact garbage = applied(port.submit(createCommand("retired-garbage", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact protectedFact = applied(port.submit(createCommand("retired-protected", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact replacement = applied(port.submit(createCommand("retired-replacement", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        seedImportedSupersededHistory(garbage, replacement);
        seedImportedSupersededHistory(protectedFact, replacement);
        assertEquals(0, collectImportedStateHistory(garbage.ref().factId(), 29), "不足30天cutoff不得缩短保护窗口");
        var command = new MemoryCommand.Transition(context(SESSION), new IdempotencyKey("retired-replay"), protectedFact.ref(), 2, FactStatus.ACTIVE, "restore");
        try (var connection = adminConnection(); var statement = connection.prepareStatement("""
                insert into public.agent_runtime_memory_operations(tenant_id,idempotency_key,command_kind,command_data,
                    result_fact_id,result_revision,recorded_epoch_second,recorded_nano)
                values(?,'retired-replay','TRANSITION',?,?,3,0,0)
                """)) {
            statement.setString(1, TENANT.value());
            statement.setBytes(2, MemoryJdbcCodec.encodeCommand(command));
            statement.setString(3, protectedFact.ref().factId());
            statement.executeUpdate();
        }
        assertEquals(2, collectImportedStateHistory(garbage.ref().factId()));
        assertEquals(0, collectImportedStateHistory(protectedFact.ref().factId()));
        assertEquals(2, adminCount("select count(*) from public.agent_runtime_memory_fact_revisions where fact_id='" + garbage.ref().factId() + "'"));
        var replay = assertInstanceOf(MemoryMutationResult.Applied.class, new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument).submit(command));
        assertTrue(replay.replayed());
        assertEquals(3, replay.fact().revision());
    }

    /** 导入无回执的历史时逐revision提交合法CAS；同业务事务绑定租户，首版来源和head守卫始终生效。 */
    private static void seedImportedSupersededHistory(MemoryFact fact, MemoryFact replacement) throws Exception {
        try (var connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                    var insert = connection.prepareStatement("""
                insert into public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision,end_user_id,agent_id,session_id,
                    scope,type_id,status,source_session_id,created_epoch_second,created_nano,valid_from_epoch_second,valid_from_nano,
                    valid_to_epoch_second,valid_to_nano,expires_epoch_second,expires_nano,superseded_fact_id,content_revision,recorded_at)
                select r.tenant_id,r.fact_id,n.revision,r.end_user_id,r.agent_id,r.session_id,r.scope,r.type_id,
                    case n.revision when 2 then 'DECAYED' when 3 then 'ACTIVE' else 'ARCHIVED' end,
                    r.source_session_id,r.created_epoch_second,r.created_nano,r.valid_from_epoch_second,r.valid_from_nano,
                    r.valid_to_epoch_second,r.valid_to_nano,r.expires_epoch_second,r.expires_nano,
                    case when n.revision=4 then ? else null end,1,now()-interval '32 days'
                from public.agent_runtime_memory_fact_revisions r cross join (values(?::bigint)) n(revision)
                where r.tenant_id=? and r.fact_id=? and r.revision=1
                """); var head = connection.prepareStatement("update public.agent_runtime_memory_fact_heads set current_revision=? where tenant_id=? and fact_id=? and current_revision=?")) {
                for (long revision = 2; revision <= 4; revision++) {
                    // 每次commit都会释放transaction-local绑定；下一修订须在新事务重新绑定真实租户。
                    tenant.setString(1, fact.ref().tenantId().value()); tenant.executeQuery().close();
                    insert.setString(1, replacement.ref().factId()); insert.setLong(2, revision);
                    insert.setString(3, fact.ref().tenantId().value()); insert.setString(4, fact.ref().factId());
                    assertEquals(1, insert.executeUpdate());
                    head.setLong(1, revision); head.setString(2, fact.ref().tenantId().value());
                    head.setString(3, fact.ref().factId()); head.setLong(4, revision - 1);
                    assertEquals(1, head.executeUpdate());
                    connection.commit();
                }
            } catch (Exception | AssertionError failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** 使用受限生产角色和事务级截止点执行实际DELETE；没有任意caller退役声明。 */
    private static int collectImportedStateHistory(String factId) throws Exception {
        return collectImportedStateHistory(factId, 31);
    }

    /** 只在本夹具导入历史上选择DB clock天数，用于30天边界负控及正式DELETE trigger核验。 */
    private static int collectImportedStateHistory(String factId, int days) throws Exception {
        try (var connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var bind = connection.createStatement()) {
                bind.execute("select set_config('app.tenant_id','" + TENANT.value() + "',true)");
                bind.execute("select set_config('app.memory_history_retention_cutoff',(now()-make_interval(days=>" + days + "))::text,true)");
            }
            try (var delete = connection.prepareStatement("""
                    delete from public.agent_runtime_memory_fact_revisions r where r.tenant_id=? and r.fact_id=?
                        and public.agent_runtime_memory_history_unprotected(r.tenant_id,r.fact_id,r.revision,now()-make_interval(days=>?))
                    """)) {
                delete.setString(1, TENANT.value()); delete.setString(2, factId);
                delete.setInt(3, days);
                int deleted = delete.executeUpdate(); connection.commit(); return deleted;
            }
        }
    }

    /** 自动提取测试通过显式extract scope，不从普通读权限推定写入授权。 */
    private static PortCallContext extractionContext(com.skillforge.runtime.api.SessionRef source) {
        var base = context(source);
        var identity = new com.skillforge.runtime.api.port.VerifiedIdentity(base.identity().principal(), base.identity().owner(),
                Set.of("memory:extract"), base.identity().policyRevision(), base.identity().verificationRef());
        return new PortCallContext(base.runIdentity(), identity, base.control());
    }

    /** 精确偏好体与归属；来源Session单独变化，作用域不能跨越。 */
    private static MemoryFactDraft automaticPreferenceDraft(com.skillforge.runtime.api.SessionRef source, MemoryScope scope) {
        return new MemoryFactDraft(scope == MemoryScope.USER ? new MemoryOwner.User(TENANT, USER) : new MemoryOwner.UserAgent(TENANT, USER, AGENT), new MemoryTypeRef("preference"), new RetentionClassRef("standard"), "v1", "user:" + USER.value(), "theme",
                new TypedPayload("skillforge.memory.preference.v1", "{\"value\":\"dark\"}"),
                List.of(new EventId("source-" + source.sessionId())), source, Optional.empty(), Optional.empty(),
                "memory-extractor-v1", NOW, Optional.empty(), Optional.empty(), 0.8, 0.5, false, Optional.empty(), FactStatus.ACTIVE);
    }

    @Test
    void transitionGuardUsesBoundTenantTransactionAndRejectionWritesNoRevisionOrReceipt() {
        MemoryFact first = applied(port.submit(createCommand("guard-source",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var guarded = new JdbcMemoryPort(dataSource(), (session, command, current) -> {
            calls.incrementAndGet();
            assertEquals(first, current);
            try {
                var connection = session.getConnection();
                assertFalse(connection.getAutoCommit());
                try (var statement = connection.prepareStatement("select current_setting('app.tenant_id',true)");
                        var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(TENANT.value(), result.getString(1));
                }
            } catch (SQLException failure) { throw new AssertionError(failure); }
            return false;
        }, JdbcMemoryPortContractTest::searchDocument);
        var result = guarded.submit(new MemoryCommand.Transition(context(SESSION), new IdempotencyKey("guard-rejected"),
                first.ref(), first.revision(), FactStatus.DECAYED, "memory_time_decay_threshold"));
        assertEquals("memory_transition_precondition_failed",
                assertInstanceOf(MemoryMutationResult.Rejected.class, result).failure().reasonCode());
        assertEquals(1, calls.get());
        assertEquals(Optional.of(first), guarded.findCurrent(context(SESSION), first.ref()));
        assertEquals(1L, adminCount("select count(*) from public.agent_runtime_memory_fact_revisions"));
        assertEquals(0L, adminCount("select count(*) from public.agent_runtime_memory_operations where idempotency_key='guard-rejected'"));
    }

    @Test
    void lexicalCandidatesUseThreeCollectionHydrationReadsAndRetainRankAndBudget() throws Exception {
        var ctx = groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        for (int index=0;index<12;index++) {
            applied(port.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("candidate-batch-"+index),
                    groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"tea preference "+index,Optional.empty()))));
        }
        var query=hybridQuery("candidate-batch",MemoryRetrievalPolicy.lexicalV1(),"tea");
        var expected=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,port.query(new MemoryQueryCall(ctx,query))).result();
        var sql=new ArrayList<String>();
        var counted=new JdbcMemoryPort(countedSource(sql,new java.util.concurrent.atomic.AtomicInteger(),false),JdbcMemoryPortContractTest::searchDocument);
        var actual=assertInstanceOf(MemoryQueryOutcome.Retrieved.class,counted.query(new MemoryQueryCall(ctx,query))).result();
        assertEquals(expected.candidates(),actual.candidates());
        assertEquals(expected.diagnostics(),actual.diagnostics());
        assertEquals(12,actual.diagnostics().candidates().size());
        assertEquals(10,actual.candidates().size());
        assertEquals(1,sql.stream().filter(value->value.contains("q.request_ordinal")).count());
        assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT s.tenant_id,s.fact_id,f.revision")).count());
        assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT d.tenant_id,d.fact_id,d.content_revision")).count());
        assertFalse(sql.stream().anyMatch(value->value.startsWith("SELECT tenant_id,fact_id,content_revision")));
        var lexical=sql.stream().filter(value->value.contains("ts_rank(")).findFirst().orElseThrow();
        assertTrue(lexical.contains("FROM public.agent_runtime_memory_search_documents d"));
        assertFalse(lexical.contains("documents AS MATERIALIZED"));
        // 未命中的可见事实仍参与完整 scope 的投影完整性检查。
        var unmatched=applied(port.submit(new MemoryCommand.Create(ctx,new IdempotencyKey("candidate-missing"),
                groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"coffee",Optional.empty()))));
        executeAdmin("DELETE FROM public.agent_runtime_memory_search_documents WHERE fact_id='"+unmatched.ref().factId()+"'");
        var missing=assertInstanceOf(MemoryQueryOutcome.Failed.class,counted.query(new MemoryQueryCall(ctx,query)));
        assertEquals("memory_search_projection_missing",missing.failure().reasonCode());
    }

    @Test
    void currentFactBatchAtPageCapacityUsesOneConnectionAndTwoCollectionReadsWithoutWrites() {
        MemoryFact first = applied(port.submit(createCommand("batch-page",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact current = applied(port.submit(new MemoryCommand.Transition(context(SESSION),
                new IdempotencyKey("batch-page-decay"), first.ref(), first.revision(), FactStatus.DECAYED, "decay")));
        var sql = new ArrayList<String>();
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        var counted = new JdbcMemoryPort(countedSource(sql, connections, false), JdbcMemoryPortContractTest::searchDocument);
        var read = new CurrentFactRead(context(SESSION), current.ref());
        var batch = new CurrentFactReadBatch(java.util.Collections.nCopies(200, read));
        List<Long> before = MEMORY_TABLES.stream().map(table -> adminCount("SELECT count(*) FROM public." + table)).toList();
        var result = counted.findCurrent(batch);
        assertEquals(java.util.Collections.nCopies(200, Optional.of(current)), result);
        assertEquals(1, connections.get());
        assertEquals(3, sql.size(), "tenant bind plus two collection statements, independent of page size");
        assertEquals(1, sql.stream().filter(value -> value.contains("q.request_ordinal") && value.contains("agent_runtime_memory_fact_revisions")).count());
        assertEquals(1, sql.stream().filter(value -> value.startsWith("SELECT s.tenant_id,s.fact_id,f.revision")).count());
        assertEquals(before, MEMORY_TABLES.stream().map(table -> adminCount("SELECT count(*) FROM public." + table)).toList());
        assertEquals(first.validFrom().getNano(), result.getFirst().orElseThrow().validFrom().getNano());
        sql.clear(); connections.set(0);
        assertEquals(List.of(), counted.findCurrent(new CurrentFactReadBatch(List.of())));
        assertEquals(0, connections.get());
        assertTrue(sql.isEmpty());
        var hidden = new CurrentFactReadBatch(List.of(new CurrentFactRead(context(HISTORICAL), current.ref())));
        assertEquals(List.of(Optional.empty()), counted.findCurrent(hidden));
        assertEquals(2, sql.size(), "no source query for an entirely invisible batch");
    }

    @Test
    void currentFactBatchRetainsSourceTargetIntegrityChecks() throws Exception {
        MemoryFact fact = applied(port.submit(createCommand("batch-source-target",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role=replica");
            try (PreparedStatement corrupt = connection.prepareStatement("UPDATE agent_runtime_memory_fact_sources "
                    + "SET source_target_id='' WHERE tenant_id=? AND fact_id=?")) {
                corrupt.setString(1,TENANT.value());corrupt.setString(2,fact.ref().factId());
                assertEquals(1,corrupt.executeUpdate());
            } finally {
                statement.execute("SET session_replication_role=origin");
            }
        }
        var failure = assertThrows(IllegalStateException.class, () -> port.findCurrent(new CurrentFactReadBatch(
                List.of(new CurrentFactRead(context(SESSION),fact.ref())))));
        assertEquals("memory_current_fact_read_failure", failure.getMessage());
        assertEquals("memory_store_corrupt", failure.getCause().getMessage());
    }

    @Test
    void currentFactBatchDoesNotTurnSqlFailureIntoMissingFacts() {
        var sql = new ArrayList<String>();
        var failed = new JdbcMemoryPort(countedSource(sql, new java.util.concurrent.atomic.AtomicInteger(), true), JdbcMemoryPortContractTest::searchDocument);
        var batch = new CurrentFactReadBatch(List.of(new CurrentFactRead(context(SESSION), new MemoryFactRef(TENANT, "absent"))));
        var failure = assertThrows(IllegalStateException.class, () -> failed.findCurrent(batch));
        assertEquals("memory_current_fact_read_failure", failure.getMessage());
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
        assertTrue(cause instanceof SQLException);
        assertEquals("forced_memory_batch_read_failure", cause.getMessage());
    }

    @Test
    void restrictedRoleForcesRlsAndHidesEveryMemoryTableAcrossTenants() throws Exception {
        MemoryFact fact = applied(port.submit(createCommand(
                "rls-visible", new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        assertTrue(fact.revision() > 0);

        var policy=hybridPolicy(new MemoryRetrievalPolicy.EmbeddingModel("embedding-rls",1,3),MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
        var hybrid=hybridStore(policy,request->new MemorySearchEmbedding.Outcome.Available(List.of(1.0,0.0,0.0)));
        var group=groupContext(GROUP_SESSION,GROUP,USER,AGENT);
        applied(hybrid.submit(new MemoryCommand.Create(group,new IdempotencyKey("rls-hybrid-source"),
                groupDraft(new MemoryOwner.Group(GROUP),GROUP_SESSION,FactStatus.ACTIVE,"tea",Optional.empty()))));
        assertTrue(hybrid.completeEmbedding(hybrid.claimEmbedding(TENANT,"rls-worker",java.time.Duration.ofMinutes(1)).orElseThrow(),List.of(1.0,0.0,0.0)));
        assertInstanceOf(MemoryQueryOutcome.Retrieved.class,hybrid.query(new MemoryQueryCall(group,hybridQuery("rls-query",policy,"tea"))));
        assertEquals(2,adminCount("SELECT count(*) FROM public.agent_runtime_memory_search_documents"));
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_embeddings"));
        assertEquals(1,adminCount("SELECT count(*) FROM public.agent_runtime_memory_query_embeddings"));

        try (Connection connection = adminConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, businessUser());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1));
                assertFalse(rows.getBoolean(2));
            }
        }
        String names = "'" + String.join("','", MEMORY_TABLES) + "'";
        assertEquals(MEMORY_TABLES.size(), adminCount("""
                SELECT count(*) FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname IN (%s) AND relrowsecurity AND relforcerowsecurity
                """.formatted(names)));
        for (String table : MEMORY_TABLES) {
            long expected = adminCount("SELECT count(*) FROM public."+table+" WHERE tenant_id='7001'");
            assertEquals(expected, restrictedCount(TENANT.value(), table), table);
            assertEquals(0, restrictedCount("memory-other-tenant", table), table);
        }
        assertCrossTenantProjectionInsertRejected("agent_runtime_memory_search_documents",
                "fact_id,content_revision,projection_version,analyzer_version,search_text,text_digest,lexical_terms,run_data,identity_data");
        assertCrossTenantProjectionInsertRejected("agent_runtime_memory_embeddings",
                "fact_id,content_revision,projection_version,analyzer_version,model_id,model_revision,dimensions,text_digest,invocation_id,state,embedding");
        assertCrossTenantProjectionInsertRejected("agent_runtime_memory_query_embeddings",
                "operation_id,fingerprint,invocation_id,model_id,model_revision,dimensions,run_data,identity_data,state,embedding,deadline_at");
    }

    private static void assertCrossTenantProjectionInsertRejected(String table,String columns) throws Exception {
        long before=adminCount("SELECT count(*) FROM public."+table);
        try(var connection=dataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var tenant=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                tenant.setString(1,TENANT.value());tenant.executeQuery().close();
            }
            try(var insert=connection.prepareStatement("INSERT INTO public."+table+"(tenant_id,"+columns+") SELECT '7002',"+columns+" FROM public."+table+" LIMIT 1")){
                var failure=assertThrows(SQLException.class,insert::executeUpdate);
                assertEquals("42501",failure.getSQLState());
            }finally{connection.rollback();}
        }
        assertEquals(before,adminCount("SELECT count(*) FROM public."+table));
    }

    @Test
    void operationJournalFailureRollsBackFactHeadAndSources() throws Exception {
        executeAdmin("""
                CREATE FUNCTION public.fail_memory_operation_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced memory operation failure'; END;
                $$
                """);
        executeAdmin("""
                CREATE TRIGGER fail_memory_operation_insert
                BEFORE INSERT ON public.agent_runtime_memory_operations
                FOR EACH ROW EXECUTE FUNCTION public.fail_memory_operation_insert()
                """);
        try {
            MemoryMutationResult.Rejected rejected = assertInstanceOf(
                    MemoryMutationResult.Rejected.class,
                    port.submit(createCommand("rollback", new EventId(
                            "source-" + SESSION.sessionId()), Optional.empty())));
            assertEquals("memory_store_write_failure", rejected.failure().reasonCode());
            for (String table : MEMORY_TABLES) {
                assertEquals(0, adminCount("SELECT count(*) FROM public." + table), table);
            }
        } finally {
            executeAdmin("DROP TRIGGER IF EXISTS fail_memory_operation_insert "
                    + "ON public.agent_runtime_memory_operations");
            executeAdmin("DROP FUNCTION IF EXISTS public.fail_memory_operation_insert()");
        }
    }

    @Test
    void missingCommittedSourceEventRollsBackWholeMutation() throws Exception {
        MemoryMutationResult.Rejected rejected = assertInstanceOf(
                MemoryMutationResult.Rejected.class,
                port.submit(createCommand("missing-source", new EventId("source-missing"),
                        Optional.empty())));
        assertEquals("memory_store_write_failure", rejected.failure().reasonCode());
        for (String table : MEMORY_TABLES) {
            assertEquals(0, adminCount("SELECT count(*) FROM public." + table), table);
        }
        MemoryFact baseline = applied(port.submit(createCommand("source-guard-baseline",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        assertFirstRevisionRequiresOwnSource(baseline, SESSION, "source-guard-missing");
        var foreign = new SessionRef(new TenantId("memory-source-other-tenant"),
                SESSION.sessionId());
        try (Connection connection = dataSource().getConnection()) {
            establishSession(connection, foreign);
        }
        // 同factId在原租户已有合法来源，也不能满足另一个租户首版的来源要求。
        assertFirstRevisionRequiresOwnSource(baseline, foreign, baseline.ref().factId());
        assertEquals(1, restrictedCount(TENANT.value(), "agent_runtime_memory_fact_revisions"));
        assertEquals(1, restrictedCount(TENANT.value(), "agent_runtime_memory_fact_sources"));
        assertEquals(0, restrictedCount(foreign.tenantId().value(), "agent_runtime_memory_fact_revisions"));
        assertEquals(0, restrictedCount(foreign.tenantId().value(), "agent_runtime_memory_fact_sources"));
    }

    /** 状态合同只改变revision/status；全值比较覆盖首版身份、正文、来源和纳秒时间的精确恢复。 */
    private static MemoryFact expectedStateRevision(MemoryFact content, long revision, FactStatus status) {
        return new MemoryFact(content.ref(), content.owner(), content.type(), content.retentionClass(), content.schemaVersion(),
                content.subject(), content.predicate(), content.object(), content.sourceEventIds(),
                content.sourceSessionRef(), content.sourceExecutionRef(), content.exportPath(), content.extractorVersion(),
                revision, content.createdAt(), content.validFrom(), content.validTo(), content.expiresAt(),
                content.confidence(), content.importance(), content.reviewRequired(), content.derivedFrom(),
                content.supersededBy(), content.decayScore(), status);
    }

    /** 以合法规范正文独立核验首版延迟来源约束；仅遗漏本tenant/fact来源，失败事务整体回滚。 */
    private static void assertFirstRevisionRequiresOwnSource(MemoryFact baseline, SessionRef session, String factId)
            throws Exception {
        var candidate = new MemoryFact(new MemoryFactRef(session.tenantId(), factId), new MemoryOwner.Session(session), baseline.type(), baseline.retentionClass(),
                baseline.schemaVersion(), baseline.subject(), baseline.predicate(), baseline.object(),
                baseline.sourceEventIds(), session, baseline.sourceExecutionRef(), baseline.exportPath(),
                baseline.extractorVersion(), 1, baseline.createdAt(), baseline.validFrom(), baseline.validTo(),
                baseline.expiresAt(), baseline.confidence(), baseline.importance(), baseline.reviewRequired(),
                baseline.derivedFrom(), baseline.supersededBy(), baseline.decayScore(), FactStatus.ACTIVE);
        byte[] body = MemoryJdbcCodec.encodeFact(candidate);
        String extractionDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(MemoryJdbcCodec.encodeExtractionIdentity(candidate)));
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                    PreparedStatement revision = connection.prepareStatement("""
                    INSERT INTO public.agent_runtime_memory_fact_revisions(
                        tenant_id,fact_id,revision,end_user_id,agent_id,session_id,scope,type_id,status,
                        source_session_id,created_epoch_second,created_nano,valid_from_epoch_second,valid_from_nano,
                        valid_to_epoch_second,valid_to_nano,content_revision,fact_data,fact_data_digest,extraction_digest)
                    VALUES(?,?,1,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,1,?,encode(sha256(?),'hex'),?)
                    """)) {
                tenant.setString(1, session.tenantId().value()); tenant.executeQuery().close();
                revision.setString(1, candidate.ref().tenantId().value()); revision.setString(2, factId);
                revision.setNull(3, java.sql.Types.BIGINT); revision.setNull(4, java.sql.Types.VARCHAR);
                revision.setString(5, session.sessionId()); revision.setString(6, candidate.scope().name());
                revision.setString(7, candidate.type().value()); revision.setString(8, session.sessionId());
                revision.setLong(9, candidate.createdAt().getEpochSecond()); revision.setInt(10, candidate.createdAt().getNano());
                revision.setLong(11, candidate.validFrom().getEpochSecond()); revision.setInt(12, candidate.validFrom().getNano());
                revision.setLong(13, candidate.validTo().orElseThrow().getEpochSecond()); revision.setInt(14, candidate.validTo().orElseThrow().getNano());
                revision.setBytes(15, body); revision.setBytes(16, body); revision.setString(17, extractionDigest);
                assertEquals(1, revision.executeUpdate());
                // 合法head前置排除另一项缺失，只验证未提交来源拒绝及整个事务回滚。
                try (var head = connection.prepareStatement("""
                    INSERT INTO public.agent_runtime_memory_fact_heads(tenant_id,fact_id,current_revision,end_user_id,agent_id,session_id,scope,type_id,group_binding_id,subject_end_user_id)
                    SELECT tenant_id,fact_id,revision,end_user_id,agent_id,session_id,scope,type_id,group_binding_id,subject_end_user_id
                      FROM public.agent_runtime_memory_fact_revisions WHERE tenant_id=? AND fact_id=? AND revision=1
                    """)) {
                    head.setString(1, session.tenantId().value()); head.setString(2, factId); assertEquals(1, head.executeUpdate());
                }
                SQLException failure = assertThrows(SQLException.class, connection::commit);
                assertEquals("P0001", failure.getSQLState());
                assertTrue(failure.getMessage().contains("memory fact revision requires committed source events"), failure.getMessage());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void twoRebuiltAdaptersConvergeOnOneFirstWinner() throws Exception {
        MemoryCommand.Create command = createCommand("two-adapter-winner",
                new EventId("source-" + SESSION.sessionId()), Optional.empty());
        List<JdbcMemoryPort> adapters = List.of(
                new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument), new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<MemoryMutationResult>>();
            for (JdbcMemoryPort adapter : adapters) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return adapter.submit(command);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<MemoryMutationResult.Applied> results = futures.stream().map(future -> {
                try {
                    return assertInstanceOf(MemoryMutationResult.Applied.class, future.get());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertEquals(1, results.stream().filter(value -> !value.replayed()).count());
            assertEquals(1, results.stream().filter(MemoryMutationResult.Applied::replayed).count());
            assertEquals(1, results.stream().map(value -> value.fact().ref()).distinct().count());
        }
    }

    @Test
    void ordinaryQueryProducesNoDatabaseWrites() {
        applied(port.submit(createCommand("query-no-write",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        List<Long> before = MEMORY_TABLES.stream()
                .map(table -> adminCount("SELECT count(*) FROM public." + table)).toList();
        MemoryQuery query = new MemoryQuery("query-no-write", new MemoryOwner.Session(SESSION), Optional.empty(), "project alpha", Optional.empty(), Set.of(MemoryScope.SESSION), Set.of(TYPE),
                MemoryQueryProfile.DEFAULT, NOW, MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
        assertInstanceOf(MemoryQueryOutcome.Retrieved.class,
                port.query(new MemoryQueryCall(context(SESSION), query)));
        List<Long> after = MEMORY_TABLES.stream()
                .map(table -> adminCount("SELECT count(*) FROM public." + table)).toList();
        assertEquals(before, after);
    }

    @Test
    void recentEpisodeHonorsFrozenSourceTaskAndKeepsUnlinkedRelevantFact() throws Exception {
        assertFrozenSourceTaskFiltering(MemoryQueryProfile.RECENT_EPISODIC);
    }

    @Test
    void defaultQueryHonorsExplicitTaskConstraintWithinTheSameOwnerAndScope() throws Exception {
        assertFrozenSourceTaskFiltering(MemoryQueryProfile.DEFAULT);
    }

    private void assertFrozenSourceTaskFiltering(MemoryQueryProfile profile) throws Exception {
        var foreign = new SessionRef(TENANT, "memory-session-foreign-task");
        try (Connection connection = dataSource().getConnection()) {
            establishSession(connection, foreign);
        }
        appendTaskContext(HISTORICAL, "task-current");
        appendTaskContext(foreign, "task-foreign");

        MemoryFact matching = applied(port.submit(new MemoryCommand.Create(context(HISTORICAL),
                new IdempotencyKey("episode-task-current"), episodeDraft(HISTORICAL, "reset page record"))));
        MemoryFact otherTask = applied(port.submit(new MemoryCommand.Create(context(foreign),
                new IdempotencyKey("episode-task-foreign"), episodeDraft(
                        foreign, "reset page details"))));
        MemoryFact unlinked = applied(port.submit(new MemoryCommand.Create(context(SESSION),
                new IdempotencyKey("episode-task-unlinked"), episodeDraft(SESSION, "reset page summary"))));

        MemoryQuery currentTask = new MemoryQuery("episode-current-task", new MemoryOwner.Session(SESSION), Optional.empty(), "reset page", Optional.of(new MemoryTaskConstraint("task-current")),
                Set.of(MemoryScope.USER_AGENT), Set.of(new MemoryTypeRef("episode")),
                profile, NOW, MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
        var withTask = assertInstanceOf(MemoryQueryOutcome.Retrieved.class,
                port.query(new MemoryQueryCall(context(SESSION), currentTask)));
        assertEquals(Set.of(matching.ref(), unlinked.ref()), withTask.result().candidates().stream()
                .map(MemoryFact::ref).collect(java.util.stream.Collectors.toSet()));

        MemoryQuery directRequest = new MemoryQuery("episode-direct-request", new MemoryOwner.Session(SESSION), Optional.empty(), "reset page", Optional.empty(), Set.of(MemoryScope.USER_AGENT),
                Set.of(new MemoryTypeRef("episode")), profile,
                NOW, MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
        var withoutTask = assertInstanceOf(MemoryQueryOutcome.Retrieved.class,
                port.query(new MemoryQueryCall(context(SESSION), directRequest)));
        Set<MemoryFactRef> expected=profile==MemoryQueryProfile.RECENT_EPISODIC?Set.of(unlinked.ref()):Set.of(matching.ref(),otherTask.ref(),unlinked.ref());
        assertEquals(expected, withoutTask.result().candidates().stream()
                .map(MemoryFact::ref).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void corruptedFactPayloadAndIndexFailClosedAfterRebuild() throws Exception {
        MemoryFact payloadFact = applied(port.submit(createCommand("corrupt-payload",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact indexFact = applied(port.submit(createCommand("corrupt-index",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        try (Connection connection = adminConnection();
                PreparedStatement payload = connection.prepareStatement("""
                UPDATE public.agent_runtime_memory_fact_revisions SET fact_data = decode('00','hex'),
                    fact_data_digest=encode(sha256(decode('00','hex')),'hex')
                 WHERE tenant_id = ? AND fact_id = ?
                """);
                PreparedStatement index = connection.prepareStatement("""
                UPDATE public.agent_runtime_memory_fact_revisions SET type_id = 'tampered.type'
                 WHERE tenant_id = ? AND fact_id = ?
                """)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
            }
            payload.setString(1, TENANT.value());
            payload.setString(2, payloadFact.ref().factId());
            payload.executeUpdate();
            index.setString(1, TENANT.value());
            index.setString(2, indexFact.ref().factId());
            index.executeUpdate();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = origin");
            }
        }
        JdbcMemoryPort rebuilt = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        assertEquals("memory_current_fact_read_failure", assertThrows(
                IllegalStateException.class,
                () -> rebuilt.findCurrent(context(SESSION), payloadFact.ref())).getMessage());
        assertEquals("memory_current_fact_read_failure", assertThrows(
                IllegalStateException.class,
                () -> rebuilt.findCurrent(context(SESSION), indexFact.ref())).getMessage());
        for (MemoryFact corrupt : List.of(payloadFact, indexFact)) {
            var failure = assertThrows(IllegalStateException.class, () -> rebuilt.findCurrent(
                    new CurrentFactReadBatch(List.of(new CurrentFactRead(context(SESSION), corrupt.ref())))));
            assertEquals("memory_current_fact_read_failure", failure.getMessage());
            assertTrue(failure.getCause() != null);
        }
    }

    @Test
    void corruptedOperationHeadAndSourceOrderFailClosedAfterRebuild() throws Exception {
        MemoryCommand.Create operationCommand = createCommand("corrupt-operation",
                new EventId("source-" + SESSION.sessionId()), Optional.empty());
        applied(port.submit(operationCommand));
        MemoryFact substitute = applied(port.submit(createCommand("corrupt-operation-substitute",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact headFact = applied(port.submit(createCommand("corrupt-head",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        MemoryFact sourceFact = applied(port.submit(createCommand("corrupt-source-order",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));

        try (Connection connection = adminConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
            }
            try (PreparedStatement operation = connection.prepareStatement("""
                    UPDATE public.agent_runtime_memory_operations
                       SET result_fact_id=?, result_revision=?
                     WHERE tenant_id=? AND idempotency_key='corrupt-operation'
                    """);
                    PreparedStatement head = connection.prepareStatement("""
                    UPDATE public.agent_runtime_memory_fact_heads SET session_id=?
                     WHERE tenant_id=? AND fact_id=?
                    """);
                    PreparedStatement source = connection.prepareStatement("""
                    UPDATE public.agent_runtime_memory_fact_sources SET source_ordinal=5
                     WHERE tenant_id=? AND fact_id=? AND revision=1
                    """)) {
                operation.setString(1, substitute.ref().factId());
                operation.setLong(2, substitute.revision());
                operation.setString(3, TENANT.value());
                assertEquals(1, operation.executeUpdate());
                head.setString(1, HISTORICAL.sessionId());
                head.setString(2, TENANT.value());
                head.setString(3, headFact.ref().factId());
                assertEquals(1, head.executeUpdate());
                source.setString(1, TENANT.value());
                source.setString(2, sourceFact.ref().factId());
                assertEquals(1, source.executeUpdate());
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET session_replication_role = origin");
                }
            }
        }

        JdbcMemoryPort rebuilt = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        assertEquals("memory_store_write_failure", assertInstanceOf(
                MemoryMutationResult.Rejected.class, rebuilt.submit(operationCommand))
                .failure().reasonCode());
        assertEquals("memory_store_write_failure", assertInstanceOf(
                MemoryMutationResult.Rejected.class,
                rebuilt.submit(new MemoryCommand.Transition(context(HISTORICAL),
                        new IdempotencyKey("corrupt-head-transition"), headFact.ref(),
                        headFact.revision(), FactStatus.DECAYED, "corrupt-head")))
                .failure().reasonCode());
        assertEquals("memory_current_fact_read_failure", assertThrows(
                IllegalStateException.class,
                () -> rebuilt.findCurrent(context(SESSION), sourceFact.ref())).getMessage());
        assertEquals("memory_current_fact_read_failure", assertThrows(IllegalStateException.class,
                () -> rebuilt.findCurrent(new CurrentFactReadBatch(List.of(
                        new CurrentFactRead(context(SESSION), sourceFact.ref()))))).getMessage());
    }

    @Test
    void typedOwnerConstraintRejectsIndexDriftWhenImmediateGuardIsDisabled() throws Exception {
        MemoryFact fact = applied(port.submit(createCommand("head-foreign-key",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        executeAdmin("ALTER TABLE public.agent_runtime_memory_fact_heads "
                + "DISABLE TRIGGER trg_agent_runtime_memory_head_guard");
        try {
            SQLException failure = assertThrows(SQLException.class, () -> {
                try (Connection connection = adminConnection();
                        PreparedStatement statement = connection.prepareStatement("""
                        UPDATE public.agent_runtime_memory_fact_heads SET session_id=?
                         WHERE tenant_id=? AND fact_id=?
                        """)) {
                    statement.setString(1, HISTORICAL.sessionId());
                    statement.setString(2, TENANT.value());
                    statement.setString(3, fact.ref().factId());
                    statement.executeUpdate();
                }
            });
            assertEquals("P0001", failure.getSQLState());
            assertTrue(failure.getMessage().contains("memory current head typed owner mismatch"));
            assertEquals(Optional.of(fact), port.findCurrent(context(SESSION), fact.ref()));
        } finally {
            executeAdmin("ALTER TABLE public.agent_runtime_memory_fact_heads "
                    + "ENABLE TRIGGER trg_agent_runtime_memory_head_guard");
        }
    }

    @Test
    void immutableHistoryAndHeadIdentityRejectDirectMutation() {
        applied(port.submit(createCommand("immutable",
                new EventId("source-" + SESSION.sessionId()), Optional.empty())));
        assertSqlRejected("UPDATE public.agent_runtime_memory_fact_revisions "
                + "SET type_id='mutated'");
        assertSqlRejected("UPDATE public.agent_runtime_memory_fact_sources "
                + "SET source_event_id='mutated'");
        assertSqlRejected("UPDATE public.agent_runtime_memory_operations "
                + "SET command_kind='TRANSITION'");
        assertSqlRejected("UPDATE public.agent_runtime_memory_fact_heads "
                + "SET agent_id='mutated'");
    }

    @Test
    void nanosecondExpirySurvivesAdapterRebuild() {
        Instant expiresAt = NOW.plusSeconds(30).plusNanos(987);
        MemoryFact fact = applied(port.submit(createCommand("nano-expiry",
                new EventId("source-" + SESSION.sessionId()), Optional.of(expiresAt))));
        assertEquals(Optional.of(expiresAt), fact.expiresAt());
        assertEquals(Optional.of(fact), new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument)
                .findCurrent(context(SESSION), fact.ref()));
    }

    @Test
    void memoryRuntimeUsesCommittedSourceAndSessionLogAckAcrossRebuild() {
        PortCallContext callContext = context(SESSION);
        MemoryFactDraft draft = memoryDraft(
                new EventId("source-" + SESSION.sessionId()), Optional.empty());
        MemoryRuntime.MutationCall mutation = new MemoryRuntime.MutationCall(
                callContext,
                new MemoryRuntime.Mutation.Create(new IdempotencyKey("runtime-combination"), draft),
                NOW.plusSeconds(1));
        JdbcMemoryPort memory = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        MemoryRuntime runtime = runtime(memory);

        MemoryRuntime.MutationResult.Recorded first = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class, runtime.mutate(mutation));
        assertFalse(first.memoryReplayed());
        assertEquals(first.event(), new JdbcSessionLogPort(dataSource())
                .findByEvent(callContext.runIdentity().owner(), first.event().eventId())
                .orElseThrow());
        assertEquals(2, first.event().sessionSequence());

        JdbcMemoryPort rebuiltMemory = new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument);
        MemoryRuntime rebuilt = runtime(rebuiltMemory);
        MemoryRuntime.MutationResult.Recorded replay = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class, rebuilt.mutate(mutation));
        assertTrue(replay.memoryReplayed());
        assertEquals(first.fact(), replay.fact());
        assertEquals(first.event(), replay.event());
        assertEquals(2, adminCount("""
                SELECT count(*) FROM public.agent_runtime_session_events
                 WHERE tenant_id = '7001' AND agent_id = '8001'
                   AND session_id = 'memory-session-current'
                """));

        MemoryQuery query = new MemoryQuery("runtime-combination-query", new MemoryOwner.Session(SESSION), Optional.empty(), "project alpha", Optional.empty(), Set.of(MemoryScope.SESSION), Set.of(TYPE),
                MemoryQueryProfile.DEFAULT, NOW.plusSeconds(2), MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
        MemoryRuntime.RetrievalResult.Recorded retrieval = assertInstanceOf(
                MemoryRuntime.RetrievalResult.Recorded.class,
                rebuilt.retrieve(new MemoryRuntime.RetrievalCall(callContext, query)));
        assertEquals(List.of(first.fact()), retrieval.receipt().candidateFacts());
        assertEquals(3, retrieval.receipt().committedEvent().sessionSequence());
        MemoryRuntime.RetrievalReceipt restored = runtime(new JdbcMemoryPort(dataSource(), JdbcMemoryPortContractTest::searchDocument))
                .replayRetrieval(callContext, retrieval.receipt().committedEvent());
        assertEquals(retrieval.receipt().candidateFacts(), restored.candidateFacts());
        assertEquals(retrieval.receipt().trace(), restored.trace());
    }

    /** 重置本类独占的新测试库中Runtime事实；正式Schema、触发器及canonical decoder保持装配。 */
    private static void resetDatabase() {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE " + qualified(MEMORY_TABLES)
                    + ", public.agent_runtime_memory_extraction_jobs,public.agent_runtime_memory_usage_projection"
                    + ", public.agent_runtime_runs,public.agent_runtime_actions,public.agent_runtime_session_checkpoints"
                    + ", public.agent_runtime_session_events"
                    + ", public.agent_runtime_session_streams"
                    + ", public.agent_runtime_session_owners CASCADE");
            // 管理连接仅重置独占库；来源前置仍由受限业务连接通过正式RLS和来源guard写入。
            try (Connection fixture = dataSource().getConnection()) {
                establishSession(fixture, SESSION);
                establishSession(fixture, HISTORICAL);
                establishGroupSession(fixture, GROUP_SESSION, GROUP);
                establishGroupSession(fixture, GROUP_HISTORICAL, GROUP);
                establishGroupSession(fixture, OTHER_GROUP_SESSION, OTHER_GROUP);
            }
        } catch (Exception failure) {
            throw new AssertionError("memory_database_reset_failed", failure);
        }
    }

    private static MemoryCommand.Create createCommand(
            String key, EventId sourceEvent, Optional<Instant> expiresAt) {
        return new MemoryCommand.Create(context(SESSION), new IdempotencyKey(key),
                memoryDraft(sourceEvent, expiresAt));
    }

    private static MemoryFactDraft memoryDraft(
            EventId sourceEvent, Optional<Instant> expiresAt) {
        return new MemoryFactDraft(new MemoryOwner.Session(SESSION), TYPE, new RetentionClassRef("standard"), "1",
                "project alpha", "remembers",
                new TypedPayload("memory.preference.v1", "{\"value\":\"project alpha\"}"),
                List.of(sourceEvent), SESSION, Optional.empty(), Optional.empty(),
                "extractor-v1", NOW.minusNanos(123), Optional.of(NOW.plusSeconds(60)),
                expiresAt, 0.9, 0.8, false, Optional.empty(), FactStatus.ACTIVE);
    }

    private static MemoryFactDraft episodeDraft(
            com.skillforge.runtime.api.SessionRef source, String content) {
        return new MemoryFactDraft(new MemoryOwner.UserAgent(TENANT, USER, AGENT), new MemoryTypeRef("episode"),
                new RetentionClassRef("standard"), "1", content, "completed",
                new TypedPayload("memory.episode.v1", "{\"text\":\"" + content + "\"}"),
                List.of(new EventId("source-" + source.sessionId())), source,
                Optional.empty(), Optional.empty(), "extractor-v1", NOW.minusSeconds(60),
                Optional.empty(), Optional.empty(), 0.9, 0.8, false, Optional.empty(),
                FactStatus.ACTIVE);
    }

    /** 测试显式抽取实际payload值；不把字段名、schema或owner身份当作正文。 */
    private static MemorySearchDocument searchDocument(MemoryFact fact) {
        try {
            var values=new ArrayList<String>();
            collectPayloadValues(new com.fasterxml.jackson.databind.ObjectMapper().readTree(fact.object().canonicalJson()),values);
            return new MemorySearchDocument(String.join(" ",values),MemorySearchDocument.VERSION);
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("invalid_controlled_memory_body",invalid);}
    }
    private static void collectPayloadValues(com.fasterxml.jackson.databind.JsonNode node,List<String> values){
        if(node.isValueNode()){if(!node.isNull())values.add(node.asText());}
        else node.elements().forEachRemaining(value->collectPayloadValues(value,values));
    }

    private static MemoryRuntime runtime(JdbcMemoryPort memory) {
        MemoryAccessPolicy access = request ->
                new MemoryAccessPolicy.Decision.Allowed(new com.skillforge.runtime.api.ResultRef(
                        "memory-access-audit"));
        MemoryTypeRegistry registry = type -> type.equals(TYPE)
                ? Optional.of(new MemoryTypeRegistry.Registration(
                        TYPE, Set.of(MemoryScope.SESSION, MemoryScope.GROUP, MemoryScope.GROUP_MEMBER),
                        List.of(new MemoryTypeRegistry.Schema("1", "memory.preference.v1")),
                        new RetentionClassRef("standard"), false, false,
                        (version, payload) -> new MemoryTypeRegistry.Validation.Allowed()))
                : Optional.empty();
        MemorySourcePolicy sourcePolicy = request ->
                new MemorySourcePolicy.Decision.Allowed(new com.skillforge.runtime.api.ResultRef(
                        "memory-source-audit"));
        return new MemoryRuntime(memory, new JdbcSessionLogPort(dataSource()), access, registry,
                sourcePolicy, memory::findCurrent, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MemoryFact applied(MemoryMutationResult result) {
        return assertInstanceOf(MemoryMutationResult.Applied.class, result).fact();
    }

    private static long restrictedCount(String tenant, String table) throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, tenant);
                bind.executeQuery().close();
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT count(*) FROM public." + table)) {
                assertTrue(rows.next());
                return rows.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static long adminCount(String sql) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void executeAdmin(String sql) throws Exception {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertSqlRejected(String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> {
            try (Connection connection = adminConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
        assertTrue(failure.getMessage().contains("agent runtime memory"), failure.getMessage());
    }

    /** 群来源通过当前正式群事件schema提交，来源所有者没有个人用户。 */
    private static void establishGroupSession(Connection connection, SessionRef session,
            com.skillforge.runtime.api.GroupBindingRef group) throws Exception {
        connection.setAutoCommit(false);
        try (var tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                var owner = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_owners(tenant_id,session_id,agent_id,end_user_id,target_kind,target_id)
                VALUES(?,?,?,NULL,'GROUP',?)
                """);
                var stream = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_streams(tenant_id,session_id,agent_id,end_user_id,target_kind,target_id)
                VALUES(?,?,?,NULL,'GROUP',?)
                """);
                var event = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_events(tenant_id,session_id,agent_id,end_user_id,session_sequence,
                    input_id,run_id,event_id,event_fingerprint,event_type,event_version,trace_id,span_id,root_input_id,
                    payload_schema_id,payload_canonical_json,occurred_at_epoch_second,occurred_at_nano,target_kind,target_id)
                VALUES(?,?,?,NULL,1,?,NULL,?,?,'group.memory.source','1','memory-trace','memory-span',?,
                    'skillforge.group.memory-source.v1','{}',?,?,'GROUP',?)
                """);
                var advance = connection.prepareStatement("""
                UPDATE public.agent_runtime_session_streams SET next_sequence=2 WHERE tenant_id=? AND target_kind='GROUP' AND target_id=? AND session_id=?
                """)) {
            tenant.setString(1, session.tenantId().value()); tenant.executeQuery().close();
            for (var statement : List.of(owner, stream)) {
                statement.setString(1, session.tenantId().value()); statement.setString(2, session.sessionId());
                statement.setString(3, AGENT.value()); statement.setString(4, group.groupBindingId()); statement.executeUpdate();
            }
            event.setString(1, session.tenantId().value()); event.setString(2, session.sessionId()); event.setString(3, AGENT.value());
            event.setString(4, "group-input-"+session.sessionId()); event.setString(5, "source-"+session.sessionId());
            event.setString(6, "group-fixture-"+session.sessionId()); event.setString(7, "group-input-"+session.sessionId());
            event.setLong(8,NOW.getEpochSecond()); event.setInt(9,NOW.getNano()); event.setString(10,group.groupBindingId()); event.executeUpdate();
            advance.setString(1,session.tenantId().value()); advance.setString(2,group.groupBindingId()); advance.setString(3,session.sessionId()); advance.executeUpdate();
            connection.commit();
        } catch (Exception failure) { connection.rollback(); throw failure; }
    }

    /** 每个来源Session在同一业务事务绑定其真实租户；提交后事务级绑定自动释放。 */
    private static void establishSession(Connection connection, SessionRef session)
            throws Exception {
        establishSession(connection, session, NOW);
    }

    private static void establishSession(Connection connection, SessionRef session, Instant sourceOccurredAt)
            throws Exception {
        String sessionId = session.sessionId();
        connection.setAutoCommit(false);
        try (PreparedStatement tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                PreparedStatement owner = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_owners(
                    tenant_id, session_id, agent_id, end_user_id, target_kind, target_id)
                VALUES (?, ?, ?, ?, 'AGENT', ?)
                """);
                PreparedStatement stream = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_streams(
                    tenant_id, session_id, agent_id, end_user_id, target_kind, target_id)
                VALUES (?, ?, ?, ?, 'AGENT', ?)
                """);
                PreparedStatement event = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_session_events(
                    tenant_id, session_id, agent_id, end_user_id, session_sequence,
                    input_id, run_id, event_id, event_fingerprint, event_type,
                    event_version, trace_id, span_id, root_input_id,
                    payload_schema_id, payload_canonical_json,
                    occurred_at_epoch_second, occurred_at_nano, target_kind, target_id)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, 'memory.source', '1',
                    'memory-trace', 'memory-span', ?, 'memory.source.v1', '{}', ?, ?, 'AGENT', ?)
                """);
                PreparedStatement advance = connection.prepareStatement("""
                UPDATE public.agent_runtime_session_streams SET next_sequence = 2
                 WHERE tenant_id = ? AND agent_id = ? AND session_id = ?
                """)) {
            tenant.setString(1, session.tenantId().value());
            tenant.executeQuery().close();
            bindOwner(owner, session);
            owner.executeUpdate();
            bindOwner(stream, session);
            stream.executeUpdate();
            event.setString(1, session.tenantId().value());
            event.setString(2, sessionId);
            event.setString(3, AGENT.value());
            event.setLong(4, USER.value());
            event.setString(5, "memory-input-" + sessionId);
            event.setString(6, "memory-run-" + sessionId);
            event.setString(7, "source-" + sessionId);
            event.setString(8, "fixture-" + sessionId);
            event.setString(9, "memory-input-" + sessionId);
            event.setLong(10, sourceOccurredAt.getEpochSecond());
            event.setInt(11, sourceOccurredAt.getNano());
            event.setString(12, AGENT.value());
            event.executeUpdate();
            advance.setString(1, session.tenantId().value());
            advance.setString(2, AGENT.value());
            advance.setString(3, sessionId);
            advance.executeUpdate();
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** 任务上下文来源同样经过受限角色和事务级租户guard，不借管理员补入事件。 */
    private static void appendTaskContext(SessionRef session, String taskId) throws Exception {
        String sessionId = session.sessionId();
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                    PreparedStatement event = connection.prepareStatement("""
                    INSERT INTO public.agent_runtime_session_events(
                        tenant_id, session_id, agent_id, end_user_id, session_sequence,
                        input_id, run_id, event_id, event_fingerprint, event_type,
                        event_version, trace_id, span_id, root_input_id,
                        payload_schema_id, payload_canonical_json,
                        occurred_at_epoch_second, occurred_at_nano, target_kind, target_id)
                    VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?, 'agent.task-context', '2',
                        'memory-trace', 'memory-task-span', ?,
                        'skillforge.runtime.task-context.v2', ?, ?, ?, 'AGENT', ?)
                    """);
                    PreparedStatement advance = connection.prepareStatement("""
                    UPDATE public.agent_runtime_session_streams SET next_sequence = 3
                     WHERE tenant_id = ? AND agent_id = ? AND session_id = ?
                    """)) {
                tenant.setString(1, session.tenantId().value());
                tenant.executeQuery().close();
                event.setString(1, session.tenantId().value());
                event.setString(2, sessionId);
                event.setString(3, AGENT.value());
                event.setLong(4, USER.value());
                event.setString(5, "memory-input-" + sessionId);
                event.setString(6, "memory-run-" + sessionId);
                event.setString(7, "task-context-" + sessionId);
                event.setString(8, "task-fixture-" + sessionId);
                event.setString(9, "memory-input-" + sessionId);
                event.setString(10, "{\"task\":{\"taskId\":\"" + taskId + "\"}}");
                event.setLong(11, NOW.getEpochSecond());
                event.setInt(12, NOW.getNano());
                event.setString(13, AGENT.value());
                event.executeUpdate();
                advance.setString(1, session.tenantId().value());
                advance.setString(2, AGENT.value());
                advance.setString(3, sessionId);
                advance.executeUpdate();
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** Owner和stream的tenant/session字段来自同一个SessionRef，避免动态scope错绑固定租户。 */
    private static void bindOwner(PreparedStatement statement, SessionRef session)
            throws Exception {
        statement.setString(1, session.tenantId().value());
        statement.setString(2, session.sessionId());
        statement.setString(3, AGENT.value());
        statement.setLong(4, USER.value());
        statement.setString(5, AGENT.value());
    }

    private static Instant databaseTime() throws Exception {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT clock_timestamp()")) {
            assertTrue(rows.next());
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                jdbcUrl(), adminUser(), adminPassword());
    }

    private static DataSource countedSource(List<String> sql,
            java.util.concurrent.atomic.AtomicInteger connections, boolean failCurrentRead) {
        DataSource source = dataSource();
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(JdbcMemoryPortContractTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(source, args);
                        if (!method.getName().equals("getConnection")) return value;
                        connections.incrementAndGet();
                        Connection connection = (Connection) value;
                        return java.lang.reflect.Proxy.newProxyInstance(JdbcMemoryPortContractTest.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p, call, parameters) -> {
                                    if (call.getName().equals("prepareStatement")) {
                                        String query = ((String) parameters[0]).replaceAll("\\s+", " ").trim();
                                        sql.add(query);
                                        if (failCurrentRead && (query.contains("q.request_ordinal") && query.contains("agent_runtime_memory_fact_revisions")))
                                            throw new SQLException("forced_memory_batch_read_failure");
                                    }
                                    try { return call.invoke(connection, parameters); }
                                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(jdbcUrl());
        source.setUser(businessUser());
        source.setPassword(EXTERNAL == null ? PASSWORD : EXTERNAL.password());
        return source;
    }

    private static DataSource governanceBarrierSource(CountDownLatch reads) {
        DataSource source = dataSource();
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(JdbcMemoryPortContractTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(source, args);
                        if (!method.getName().equals("getConnection")) return value;
                        Connection connection = (Connection) value;
                        return java.lang.reflect.Proxy.newProxyInstance(JdbcMemoryPortContractTest.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p, call, parameters) -> {
                                    if (call.getName().equals("prepareStatement")
                                            && ((String) parameters[0]).contains("memory-extraction:")) {
                                        reads.countDown();
                                        assertTrue(reads.await(10, TimeUnit.SECONDS), "两个事务未到达治理锁竞争点");
                                    }
                                    try { return call.invoke(connection, parameters); }
                                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static String qualified(List<String> tables) {
        return tables.stream().map(table -> "public." + table)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    /** 外部模式只消费显式环境，不从.env、历史库名或容器默认值恢复缺失字段。 */
    private static ExternalFixture externalFixture() {
        String prefix = "SKILLFORGE_S03_EXTERNAL_";
        if (System.getenv().keySet().stream().noneMatch(name -> name.startsWith(prefix))) return null;
        String lane = requiredExternal("FIXTURE_LANE");
        String user = requiredExternal("DB_USER");
        if (!lane.matches("[a-z][a-z0-9_]{0,31}") || !user.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalStateException("memory_external_fixture_identity_invalid");
        return new ExternalFixture(requiredExternal("JDBC_URL"), user, requiredExternal("DB_PASSWORD"),
                requiredExternal("ADMIN_USER"), requiredExternal("ADMIN_PASSWORD"), lane, requiredExternal("FIXTURE_TOKEN"), requiredExternal("SCHEMA_VERSION"));
    }

    /** 缺失配置直接拒绝，错误只包含变量名，不能回显密码、连接字串或token。 */
    private static String requiredExternal(String suffix) {
        String name = "SKILLFORGE_S03_EXTERNAL_" + suffix;
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("memory_external_fixture_missing:" + name);
        return value;
    }

    /** 业务连接校验实际库名、父授权token和NOBYPASSRLS；不可信库禁止迁移及TRUNCATE。 */
    private static void verifyExternalFixture() throws Exception {
        try (var connection = dataSource().getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("select current_database(),rolsuper,rolbypassrls from pg_roles where rolname=current_user")) {
            assertTrue(rows.next());
            assertTrue(rows.getString(1).matches("sf_s03_" + EXTERNAL.lane() + "_[a-z0-9_]+"), "external fixture database not admitted");
            assertFalse(rows.getBoolean(2)); assertFalse(rows.getBoolean(3));
            try (var guard = connection.prepareStatement("select count(*) from public.s03_host_fixture_guard where lane=? and token=?")) {
                guard.setString(1, EXTERNAL.lane()); guard.setString(2, EXTERNAL.token());
                try (var bound = guard.executeQuery()) { assertTrue(bound.next()); assertEquals(1, bound.getInt(1)); }
            }
            try (var schema = connection.prepareStatement("select count(*) from public.flyway_schema_history where version=? and success")) {
                schema.setString(1, EXTERNAL.schemaVersion());
                try (var bound = schema.executeQuery()) { assertTrue(bound.next()); assertEquals(1, bound.getInt(1), "external formal migration not installed"); }
            }
        }
    }

    /** 当前新库URL，默认CI容器与S03父资源共用同一正式Schema测试路径。 */
    private static String jdbcUrl() { return EXTERNAL == null ? POSTGRES.getJdbcUrl() : EXTERNAL.url(); }

    /** 只允许夹具管理员执行正式迁移与本类新库清理。 */
    private static String adminUser() { return EXTERNAL == null ? POSTGRES.getUsername() : EXTERNAL.adminUser(); }

    /** 管理凭据不进入测试结果或源绑定文件。 */
    private static String adminPassword() { return EXTERNAL == null ? POSTGRES.getPassword() : EXTERNAL.adminPassword(); }

    /** RLS业务角色只可为安全SQL标识符；外部角色由父预建而非助手创建。 */
    private static String businessUser() { return EXTERNAL == null ? ROLE : EXTERNAL.user(); }

    /**
     * @param url 父批准的S03新库JDBC连接
     * @param user 受限业务角色
     * @param password 业务测试凭据
     * @param adminUser 新库迁移及清理角色
     * @param adminPassword 管理测试凭据
     * @param lane 父授权的baseline/candidate等输入lane
     * @param token 精确绑定新库的授权token，仅与guard表核验
     * @param schemaVersion 父已安装且明确授权的正式迁移版本
     */
    private record ExternalFixture(String url, String user, String password, String adminUser,
            String adminPassword, String lane, String token, String schemaVersion) { }
}
