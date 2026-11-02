package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.adapter.jdbc.fixture.SessionSubjectJsonFixture;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.port.ConversationTaskPort;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 PostgreSQL 验证任务与 SessionLog 原子性、完整归属和 fencing。 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcConversationTaskPortTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static final Instant TIME = Instant.parse("2026-09-22T01:00:00.123456789Z");
    private final TestCodec codec = new TestCodec();
    private final JdbcConversationTaskPort port = new JdbcConversationTaskPort(dataSource(), codec);
    private final JdbcSessionLogPort log = new JdbcSessionLogPort(dataSource());

    @BeforeAll static void initialize() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE task_app LOGIN PASSWORD 'task_test' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO task_app");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO task_app");
        }
    }

    @Test void crossSessionResumeKeepsIdentityAndReplayDoesNotRebind() {
        var first = owner("resume-first", "tenant-resume", 1);
        var second = owner("resume-second", "tenant-resume", 1);
        source(first);
        var task = task(first, "resume-task", 1);
        var initial = change(first, task, "create", true, Optional.empty());
        assertInstanceOf(ConversationTaskPort.Result.Committed.class, port.commit(initial));
        assertEquals(task, port.find(second, task.agentId(), task.taskId()).orElseThrow());
        assertTrue(port.current(second, task.agentId()).isEmpty());
        assertTrue(port.find(owner("other", "tenant-other", 1), task.agentId(), task.taskId()).isEmpty());
        assertTrue(port.find(owner("other", "tenant-resume", 2), task.agentId(), task.taskId()).isEmpty());
        assertTrue(port.search(second, task.agentId(), "progress", false, 0, 10).contains(task));
        var updated = task(first, task.taskId(), 2);
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(second, updated, "resume", true, Optional.empty())));
        var replay = assertInstanceOf(ConversationTaskPort.Result.Committed.class, port.commit(initial));
        assertTrue(replay.replayed());
        assertEquals(1, replay.task().revision());
        assertEquals(2, port.find(first, task.agentId(), task.taskId()).orElseThrow().revision());
        assertEquals(task.taskId(), port.current(second, task.agentId()).orElseThrow());
        var selection = log.readAfter(new SessionLogPort.ReadSessionLog(second, 0, 10)).stream()
                .filter(event -> event.eventType().equals("conversation-task.selected")).findFirst().orElseThrow();
        assertTrue(selection.payload().canonicalJson().contains("\"taskId\":\"resume-task\""));
        assertEquals(new EventId("resume"), selection.trace().causedByEventId().orElseThrow());
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(second, updated, "stale", true, Optional.empty())));
        assertTrue(log.findByEvent(second, new EventId("stale")).isEmpty());
    }

    /** 正式迁移的触发器直接验证新 owner 协议，应用 codec 不能代替数据库来源约束。 */
    @Test void databaseTaskSnapshotsRequireExplicitUserSubjectAndExactOriginalSource() throws Exception {
        var owner = owner("subject-source", "tenant-subject-source", 7);
        source(owner);
        var task = task(owner, "subject-source-task", 1);
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(owner, task, "subject-source-create", false, Optional.empty())));
        String valid = codec.encode(task).canonicalJson();
        assertSnapshotUpdate(owner, task.taskId(), valid, null);
        var json = SessionSubjectJsonFixture.configure(new com.fasterxml.jackson.databind.ObjectMapper());
        for (String drift : List.of("legacy", "missing-kind", "group", "mixed", "missing-source")) {
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(valid);
            var source = (com.fasterxml.jackson.databind.node.ObjectNode) changed.withArray("sources").get(0);
            var sourceOwner = (com.fasterxml.jackson.databind.node.ObjectNode) source.get("owner");
            var subject = (com.fasterxml.jackson.databind.node.ObjectNode) sourceOwner.get("subject");
            switch (drift) {
                case "legacy" -> { sourceOwner.set("endUserId", subject.get("endUserId")); sourceOwner.remove("subject"); }
                case "missing-kind" -> subject.remove("kind");
                case "group" -> {
                    subject.removeAll(); subject.put("kind", "GROUP");
                    var binding = subject.putObject("groupBindingRef");
                    binding.putObject("tenantId").put("value", owner.sessionRef().tenantId().value());
                    binding.put("groupBindingId", "group");
                    var target = (com.fasterxml.jackson.databind.node.ObjectNode) sourceOwner.get("target");
                    target.put("kind", "GROUP"); target.put("id", "group");
                }
                case "mixed" -> sourceOwner.set("endUserId", subject.get("endUserId"));
                case "missing-source" -> ((com.fasterxml.jackson.databind.node.ObjectNode) source.get("eventId")).put("value", "missing-source");
                default -> throw new IllegalArgumentException("unknown snapshot drift");
            }
            assertSnapshotUpdate(owner, task.taskId(), json.writeValueAsString(changed),
                    drift.equals("missing-source") ? "session_history_task_source_missing_or_retired" : "session_history_task_source_invalid");
        }
        try (var connection = dataSource().getConnection(); var query = connection.prepareStatement(
                "SELECT public.agent_runtime_session_task_snapshot_referenced(?,?,?,?,?,?,?,?)")) {
            for (String event : List.of("source", "unrelated")) {
                query.setString(1, valid); query.setString(2, owner.sessionRef().tenantId().value());
                query.setString(3, owner.sessionRef().sessionId()); query.setString(4, "AGENT");
                query.setString(5, owner.target().id()); query.setLong(6, owner.requireUser().value());
                query.setString(7, event); query.setLong(8, 1);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(event.equals("source"), rows.getBoolean(1)); }
            }
        }
        assertEquals(task, port.find(owner, task.agentId(), task.taskId()).orElseThrow());
    }

    @Test void leasesRejectForeignAndReleasedHoldersAndPreserveFence() {
        var owner = owner("lease", "tenant-lease", 1);
        source(owner);
        var task = task(owner, "lease-task", 1);
        port.commit(change(owner, task, "lease-create", true, Optional.empty()));
        var now = Instant.now();
        var first = port.acquire(owner, task.agentId(), task.taskId(), "run-a", now, now.plusSeconds(120));
        assertThrows(IllegalStateException.class,
                () -> port.acquire(owner, task.agentId(), task.taskId(), "run-b", now, now.plusSeconds(120)));
        port.release(owner, task.agentId(), task.taskId(), "run-b", first.fence());
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(owner, task(owner, task.taskId(), 2), "no-lease", false, Optional.empty())));
        port.release(owner, task.agentId(), task.taskId(), "run-a", first.fence());
        var second = port.acquire(owner, task.agentId(), task.taskId(), "run-b", now, now.plusSeconds(120));
        assertTrue(second.fence() > first.fence());
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(owner, task(owner, task.taskId(), 2), "old-lease", false, Optional.of(first))));
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(owner, task(owner, task.taskId(), 2), "new-lease", false, Optional.of(second))));
    }

    @Test void missingSourcesAndProjectionFailureLeaveNoTaskEvent() throws Exception {
        var owner = owner("rollback", "tenant-rollback", 1);
        var task = task(owner, "rollback-task", 1);
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(owner, task, "missing-source", true, Optional.empty())));
        assertTrue(port.find(owner, task.agentId(), task.taskId()).isEmpty());
        source(owner);
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION public.fail_task_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_id = 'rollback-task' THEN RAISE EXCEPTION 'forced_task_failure'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER fail_task_projection BEFORE INSERT ON public.agent_runtime_conversation_tasks FOR EACH ROW EXECUTE FUNCTION public.fail_task_projection()");
        }
        try {
            assertThrows(RuntimeException.class,
                    () -> port.commit(change(owner, task, "failed-projection", true, Optional.empty())));
            assertTrue(log.findByEvent(owner, new EventId("failed-projection")).isEmpty());
            assertTrue(port.current(owner, task.agentId()).isEmpty());
            assertTrue(port.find(owner, task.agentId(), task.taskId()).isEmpty());
        } finally {
            try (var connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER fail_task_projection ON public.agent_runtime_conversation_tasks");
                statement.execute("DROP FUNCTION public.fail_task_projection()");
            }
        }
        var result = assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(owner, task, "failed-projection", true, Optional.empty())));
        assertEquals(2, result.event().sessionSequence());
    }

    @Test void maintenanceCursorCommitsWithTaskSurvivesRecoveryAndPreservesOtherSessions() {
        var first = owner("cursor-first", "tenant-cursor", 1);
        var second = owner("cursor-second", "tenant-cursor", 1);
        var firstRun = new RunRef(first.sessionRef().tenantId(), "run-first");
        var secondRun = new RunRef(second.sessionRef().tenantId(), "run-second");
        source(first, "first-evidence", Optional.of(firstRun));
        var firstReceipt = maintenance(first, "cursor-task", firstRun, "first-batch",
                ConversationTask.MaintenancePhase.ENTRY, 0, 1, 0, "first-evidence");
        var initial = task(first, "cursor-task", 1, "first-evidence", List.of(firstReceipt));
        var initialChange = change(first, initial, "first-batch:change", false,
                Optional.empty(), Optional.of(firstRun));
        assertInstanceOf(ConversationTaskPort.Result.Committed.class, port.commit(initialChange));
        assertEquals(List.of(firstReceipt), new JdbcConversationTaskPort(dataSource(), codec)
                .find(first, initial.agentId(), initial.taskId()).orElseThrow().maintenance());

        source(second, "second-evidence", Optional.of(secondRun));
        var secondReceipt = maintenance(second, initial.taskId(), secondRun, "second-batch",
                ConversationTask.MaintenancePhase.RESUME, 0, 1, 1, "second-evidence");
        var resumed = task(first, initial.taskId(), 2, "first-evidence", List.of(firstReceipt, secondReceipt));
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(second, resumed, "second-batch:change", false,
                        Optional.empty(), Optional.of(secondRun))));

        source(first, "first-next", Optional.of(firstRun));
        var advancedFirst = maintenance(first, initial.taskId(), firstRun, "first-next-batch",
                ConversationTask.MaintenancePhase.EXIT, 1, 3, 2, "first-next");
        var completed = task(first, initial.taskId(), 3, "first-evidence", List.of(advancedFirst, secondReceipt));
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(first, completed, "first-next-batch:change", false,
                        Optional.empty(), Optional.of(firstRun))));

        var replay = assertInstanceOf(ConversationTaskPort.Result.Committed.class, port.commit(initialChange));
        assertTrue(replay.replayed());
        assertEquals(List.of(firstReceipt), replay.task().maintenance());
        assertEquals(List.of(advancedFirst, secondReceipt),
                port.find(first, initial.agentId(), initial.taskId()).orElseThrow().maintenance());
    }

    @Test void maintenanceRejectsOutOfRangeForgedOwnerAndRevisionWithoutAdvancing() {
        var rangeOwner = owner("cursor-range", "tenant-cursor-range", 1);
        var rangeRun = new RunRef(rangeOwner.sessionRef().tenantId(), "run-range");
        source(rangeOwner, "range-evidence", Optional.of(rangeRun));
        var outOfRange = maintenance(rangeOwner, "range-task", rangeRun, "range-batch",
                ConversationTask.MaintenancePhase.ENTRY, 0, 0, 0, "range-evidence");
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(rangeOwner, task(rangeOwner, "range-task", 1, "range-evidence",
                                List.of(outOfRange)), "range-batch:change", false,
                        Optional.empty(), Optional.of(rangeRun))));
        assertTrue(log.findByEvent(rangeOwner, new EventId("range-batch:change")).isEmpty());
        assertTrue(port.find(rangeOwner, outOfRange.agentId(), "range-task").isEmpty());

        var actualOwner = owner("cursor-owner", "tenant-cursor-owner", 1);
        var forgedOwner = owner("cursor-forged", "tenant-cursor-owner", 1);
        var ownerRun = new RunRef(actualOwner.sessionRef().tenantId(), "run-owner");
        source(actualOwner, "owner-evidence", Optional.of(ownerRun));
        var forged = maintenance(forgedOwner, "owner-task", ownerRun, "owner-batch",
                ConversationTask.MaintenancePhase.ENTRY, 0, 1, 0, "owner-evidence");
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(actualOwner, task(actualOwner, "owner-task", 1, "owner-evidence",
                                List.of(forged)), "owner-batch:change", false,
                        Optional.empty(), Optional.of(ownerRun))));
        assertTrue(log.findByEvent(actualOwner, new EventId("owner-batch:change")).isEmpty());

        var revisionOwner = owner("cursor-revision", "tenant-cursor-revision", 1);
        source(revisionOwner);
        var original = task(revisionOwner, "revision-task", 1);
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(revisionOwner, original, "revision-create", false, Optional.empty())));
        var revisionRun = new RunRef(revisionOwner.sessionRef().tenantId(), "run-revision");
        var staleReceipt = maintenance(revisionOwner, original.taskId(), revisionRun, "revision-batch",
                ConversationTask.MaintenancePhase.EXIT, 0, 1, 0, "source");
        var staleTask = task(revisionOwner, original.taskId(), 2, "source", List.of(staleReceipt));
        assertInstanceOf(ConversationTaskPort.Result.Conflict.class,
                port.commit(change(revisionOwner, staleTask, "revision-batch:change", false,
                        Optional.empty(), Optional.of(revisionRun))));
        assertEquals(1, port.find(revisionOwner, original.agentId(), original.taskId()).orElseThrow().revision());
        assertTrue(log.findByEvent(revisionOwner, new EventId("revision-batch:change")).isEmpty());
    }

    @Test void projectionFailureRollsBackMaintenanceEventAndCursor() throws Exception {
        var owner = owner("cursor-rollback", "tenant-cursor-rollback", 1);
        var run = new RunRef(owner.sessionRef().tenantId(), "run-rollback");
        source(owner, "cursor-source", Optional.of(run));
        var firstReceipt = maintenance(owner, "cursor-rollback-task", run, "cursor-create",
                ConversationTask.MaintenancePhase.ENTRY, 0, 1, 0, "cursor-source");
        var initial = task(owner, "cursor-rollback-task", 1, "cursor-source", List.of(firstReceipt));
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                port.commit(change(owner, initial, "cursor-create:change", false,
                        Optional.empty(), Optional.of(run))));
        source(owner, "cursor-next", Optional.of(run));
        var advanced = maintenance(owner, initial.taskId(), run, "cursor-advance",
                ConversationTask.MaintenancePhase.EXIT, 1, 3, 1, "cursor-next");
        var updated = task(owner, initial.taskId(), 2, "cursor-source", List.of(advanced));
        var update = change(owner, updated, "cursor-advance:change", false,
                Optional.empty(), Optional.of(run));
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION public.fail_cursor_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_id = 'cursor-rollback-task' AND NEW.revision = 2 THEN RAISE EXCEPTION 'forced_cursor_failure'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER fail_cursor_projection BEFORE INSERT ON public.agent_runtime_conversation_tasks FOR EACH ROW EXECUTE FUNCTION public.fail_cursor_projection()");
        }
        try {
            assertThrows(RuntimeException.class, () -> port.commit(update));
            assertEquals(List.of(firstReceipt), port.find(owner, initial.agentId(), initial.taskId()).orElseThrow().maintenance());
            assertTrue(log.findByEvent(owner, new EventId("cursor-advance:change")).isEmpty());
        } finally {
            try (var connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER fail_cursor_projection ON public.agent_runtime_conversation_tasks");
                statement.execute("DROP FUNCTION public.fail_cursor_projection()");
            }
        }
        assertInstanceOf(ConversationTaskPort.Result.Committed.class, port.commit(update));
        assertEquals(List.of(advanced), port.find(owner, initial.agentId(), initial.taskId()).orElseThrow().maintenance());
    }

    private void source(SessionOwner owner) {
        source(owner, "source", Optional.empty());
    }
    /** 使用真实受限角色直接提交快照；失败须命中正式来源触发器，且事务总是回滚。 */
    private void assertSnapshotUpdate(SessionOwner owner, String taskId, String snapshot, String expectedError) throws Exception {
        try (var connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                    bind.setString(1, owner.sessionRef().tenantId().value()); bind.execute();
                }
                try (var update = connection.prepareStatement("UPDATE public.agent_runtime_conversation_tasks SET snapshot=? WHERE tenant_id=? AND task_id=?")) {
                    update.setString(1, snapshot); update.setString(2, owner.sessionRef().tenantId().value()); update.setString(3, taskId);
                    if (expectedError == null) assertEquals(1, update.executeUpdate());
                    else {
                        var failure = assertThrows(java.sql.SQLException.class, update::executeUpdate);
                        assertEquals("P0001", failure.getSQLState()); assertTrue(failure.getMessage().contains(expectedError));
                    }
                }
            } finally { connection.rollback(); }
        }
    }
    private void source(SessionOwner owner, String eventId, Optional<RunRef> runRef) {
        log.append(event(owner, eventId, new TypedPayload("user-message.v1", "{}"), runRef));
    }
    private ConversationTask task(SessionOwner sourceOwner, String id, long revision) {
        return task(sourceOwner, id, revision, "source", List.of());
    }
    private ConversationTask task(SessionOwner sourceOwner, String id, long revision, String sourceEventId,
            List<ConversationTask.Maintenance> maintenance) {
        return new ConversationTask(id, sourceOwner.sessionRef().tenantId(), sourceOwner.requireUser(),
                sourceOwner.target().requireAgent(), revision, ConversationTask.Status.OPEN, "progress", "saved",
                new TypedPayload(ConversationTask.STATE_SCHEMA, "{}"),
                List.of(new ConversationTask.Source(sourceOwner, new EventId(sourceEventId))), maintenance, TIME);
    }
    private ConversationTaskPort.Change change(SessionOwner owner, ConversationTask task, String id,
            boolean selected, Optional<ConversationTaskPort.Lease> lease) {
        return change(owner, task, id, selected, lease, Optional.empty());
    }
    private ConversationTaskPort.Change change(SessionOwner owner, ConversationTask task, String id,
            boolean selected, Optional<ConversationTaskPort.Lease> lease, Optional<RunRef> runRef) {
        return new ConversationTaskPort.Change(owner, task, task.revision() - 1,
                event(owner, id, codec.encode(task), runRef), selected, lease);
    }
    private static SessionLogPort.AppendSessionEvent event(SessionOwner owner, String id, TypedPayload payload) {
        return event(owner, id, payload, Optional.empty());
    }
    private static SessionLogPort.AppendSessionEvent event(SessionOwner owner, String id, TypedPayload payload,
            Optional<RunRef> runRef) {
        var input = new InputId("input");
        return new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner, input,
                runRef, Optional.empty(), Optional.empty(), Optional.empty()),
                new SessionEventDraft(new EventId(id), "task.changed", "1", "fingerprint-" + id,
                        new TraceEnvelope("trace", "span", input, Optional.empty()), payload, TIME));
    }
    private static ConversationTask.Maintenance maintenance(SessionOwner owner, String taskId, RunRef runRef,
            String batchId, ConversationTask.MaintenancePhase phase, long from, long through,
            long expectedRevision, String... sourceEventIds) {
        return new ConversationTask.Maintenance(owner, taskId, runRef, batchId, phase, from, through,
                expectedRevision, java.util.Arrays.stream(sourceEventIds).map(EventId::new).toList());
    }
    private static SessionOwner owner(String session, String tenant, long user) {
        return SessionOwner.user(new SessionRef(new TenantId(tenant), session), new AgentId("agent"), new EndUserId(user));
    }
    private static Connection admin() throws Exception {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }
    private static DataSource dataSource() {
        var source = new PGSimpleDataSource();
        source.setUrl(DATABASE.getJdbcUrl()); source.setUser("task_app"); source.setPassword("task_test");
        return source;
    }
    /** 测试只替换宿主 Codec，JDBC、事务与 RLS 均使用真实实现。 */
    private static final class TestCodec implements JdbcConversationTaskPort.Codec {
        private static final String SCHEMA = "skillforge.conversation-task.snapshot.v2";
        private final com.fasterxml.jackson.databind.ObjectMapper json = SessionSubjectJsonFixture.configure(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        @Override public TypedPayload encode(ConversationTask task) {
            try { return new TypedPayload(SCHEMA, json.writeValueAsString(task)); }
            catch (Exception failure) { throw new IllegalArgumentException("task_encode_failed", failure); }
        }
        @Override public ConversationTask decode(TypedPayload payload) {
            if (!SCHEMA.equals(payload.schemaId())) throw new IllegalArgumentException("task_snapshot_schema_invalid");
            try { return json.readValue(payload.canonicalJson(), ConversationTask.class); }
            catch (Exception failure) { throw new IllegalArgumentException("task_decode_failed", failure); }
        }
    }
}
