package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.fixture.SessionSubjectJsonFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 中 Team Session 工作占用、CAS 与 SessionLog 的组合验收。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcDagWorkPortTest {
    private static final TestJdbcIdentityTransactions identities = new TestJdbcIdentityTransactions();

    private static final String APP_ROLE = "agent_runtime_dag_work_test";
    private static final String APP_PASSWORD = "dag_work_test_password";
    private static final TenantId TENANT = new TenantId("tenant-dag-work");
    private static final AgentId COORDINATOR = new AgentId("coordinator");

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
                    + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.grantSessionLogReads(statement, APP_ROLE);
            statement.execute("""
                    GRANT SELECT, INSERT, UPDATE ON public.agent_runtime_session_owners,
                        public.agent_runtime_session_streams,
                        public.agent_runtime_session_events,
                        public.agent_runtime_dag_works,
                        public.agent_runtime_dag_admissions,
                        public.agent_runtime_dag_step_actions,
                        public.agent_runtime_dag_commands,
                        public.agent_runtime_dag_control_runs,
                        public.agent_runtime_dag_inputs,
                        public.agent_runtime_dag_permits
                    TO %s
                    """.formatted(APP_ROLE));
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);
            statement.execute("GRANT DELETE ON public.agent_runtime_dag_permits TO " + APP_ROLE);
            statement.execute("GRANT SELECT ON public.agent_runtime_execution_runs TO " + APP_ROLE);
            statement.execute("GRANT SELECT ON public.agent_runtime_team_work_answers TO " + APP_ROLE);
        }
    }

    @BeforeEach
    void resetDatabase() {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            // 多态scope余额没有预算根FK，重置原Run前须显式清空。
            statement.execute("""
                    TRUNCATE TABLE public.agent_runtime_dag_permits,
                        public.agent_runtime_dag_inputs,
                        public.agent_runtime_dag_control_runs,
                        public.agent_runtime_dag_commands,
                        public.agent_runtime_dag_admissions,
                        public.agent_runtime_dag_step_actions,
                        public.agent_runtime_dag_works,
                        public.agent_runtime_session_events,
                        public.agent_runtime_session_streams,
                        public.agent_runtime_session_owners,
                        public.agent_runtime_execution_sessions,
                        public.agent_runtime_execution_materials,
                        public.agent_runtime_budget_roots,
                        public.agent_runtime_budget_balances,
                        public.agent_runtime_team_work_answers
                    RESTART IDENTITY CASCADE
                    """);
        } catch (Exception failure) {
            throw new IllegalStateException("dag_work_test_reset_failure", failure);
        }
    }

    @Test
    void cancelledWorkRecoversOnlyItsExactUncancelledActiveControlRun() throws Exception {
        var owner = teamOwner("cancel-parent-recovery");
        establishRuntimeSession(owner, "cancel-parent-bootstrap");
        var works = workPort();
        var queued = queued(owner, "cancel-parent-input", "cancel-parent-control");
        assertTrue(works.create(queued, "create-cancel-parent", "create-cancel-parent"));
        var active = activeWithoutTasks(queued);
        assertEquals(DagWorkPort.Commit.APPLIED, works.commit(queued.key(), 1, "activate-parent", "activate-parent", active,
                List.of(new DagWorkPort.Event("work.handoff", "", "coordinator handoff"))));
        var seed = seedRuntimeRun(active, active.request().controlRun().runId(), COORDINATOR, active.key().rootInputId());
        seedRuntimeRun(active, "old-routing-control", COORDINATOR, new InputId("old-routing-input"));
        try (var connection = adminConnection(); var statement = connection.prepareStatement(
                "INSERT INTO agent_runtime_dag_control_runs(tenant_id,session_id,root_input_id,run_id,agent_id,role) VALUES (?,?,?,?,?,'ROUTING')")) {
            statement.setString(1, TENANT.value()); statement.setString(2, owner.sessionRef().sessionId());
            statement.setString(3, active.key().rootInputId().value()); statement.setString(4, "old-routing-control");
            statement.setString(5, COORDINATOR.value()); statement.executeUpdate();
        }
        var cancelled = new DagWork(active.key(), active.coordinator(), 3, DagWork.Status.CANCELLED,
                active.plan(), active.approvedRevision(), active.spentTokens(), active.executions(), active.consumedEvents(), active.request());
        assertEquals(DagWorkPort.Commit.APPLIED, works.commit(active.key(), 2, "control:original-cancel", "cancel-parent", cancelled,
                List.of(new DagWorkPort.Event("work.cancel_requested", "", "取消"), new DagWorkPort.Event("work.closed", "", "取消完成"))));
        assertTrue(workPort().recoverable(TENANT, 100).contains(active.key()), "a rebuilt worker must pick up orphaned cancelled Work");
        var runRef = active.request().controlRun();
        var cancel = new InputId("plan-parent-cancel:control:original-cancel");
        var cancelledRun = seed.store().cancel(new com.skillforge.runtime.session.spi.ExecutionStore.Cancel(
                seed.identity(), runRef, cancel, List.of()));
        assertEquals(com.skillforge.runtime.api.RunStatus.CANCELLED, cancelledRun.status());
        assertEquals(com.skillforge.runtime.session.spi.ExecutionStore.Phase.FINISHED, cancelledRun.phase());
        assertTrue(workPort().recoverable(TENANT, 100).isEmpty(), "pending cancellation with no unsettled input leaves the recovery scan");
        try (var connection = adminConnection(); var statement = connection.prepareStatement(
                "INSERT INTO agent_runtime_dag_inputs(tenant_id,session_id,root_input_id,input_id,payload) VALUES (?,?,?,?, '{}')")) {
            statement.setString(1, TENANT.value()); statement.setString(2, owner.sessionRef().sessionId());
            statement.setString(3, active.key().rootInputId().value()); statement.setString(4, "original-cancel");
            statement.executeUpdate();
            statement.setString(4, "unrelated-input"); statement.executeUpdate();
        }
        assertTrue(workPort().recoverable(TENANT, 100).contains(active.key()), "original ACCEPTED cancel remains recoverable even after parent cancelRequest commits");
        try (var connection = adminConnection(); var statement = connection.prepareStatement(
                "UPDATE agent_runtime_dag_inputs SET state='APPLIED' WHERE tenant_id=? AND session_id=? AND input_id=?")) {
            statement.setString(1, TENANT.value()); statement.setString(2, owner.sessionRef().sessionId());
            statement.setString(3, "original-cancel"); assertEquals(1, statement.executeUpdate());
        }
        assertTrue(workPort().recoverable(TENANT, 100).isEmpty(), "an unrelated ACCEPTED input and the old routing Run must not keep historical Work recoverable");
    }

    @Test
    void primaryAdmissionsShareRunAndRemainIndependentAfterRebuild() {
        var owner = teamOwner("primary-steps");
        establishRuntimeSession(owner, "primary-bootstrap");
        var port = workPort();
        var initial = queued(owner, "primary-input", "primary-control");
        assertTrue(port.create(initial, "create-primary", "create-primary"));
        var first = new DagPlan.Task("P1", "分析", COORDINATOR, DagPlan.ExecutorType.PRIMARY,
                "分析资料", List.of(), Set.of("result"), Set.of());
        var second = new DagPlan.Task("P2", "汇总", COORDINATOR, DagPlan.ExecutorType.PRIMARY,
                "汇总资料", List.of(), Set.of("result"), Set.of());
        var plan = new DagPlan(1, false, List.of(first, second));
        var control = initial.request().controlRun();
        var firstBinding = new DagWork.Binding("primary-admission-1", 1, first, control, Map.of());
        var secondBinding = new DagWork.Binding("primary-admission-2", 1, second, control, Map.of());
        var executions = Map.of(
                "P1", new DagWork.Execution(firstBinding, DagWork.TaskStatus.COMPLETED, Optional.empty(),
                        false, 0, Map.of(), "已分析", Optional.empty()),
                "P2", new DagWork.Execution(secondBinding, DagWork.TaskStatus.RUNNING,
                        Optional.of(new DagWork.Permit(2, Instant.parse("2026-10-19T01:00:00Z"))),
                        false, 0, Map.of(), "正在汇总", Optional.empty()));
        var next = new DagWork(initial.key(), COORDINATOR, 2, DagWork.Status.ACTIVE, plan,
                0, 0, executions, Map.of(), initial.request());
        assertEquals(DagWorkPort.Commit.APPLIED, port.commit(initial.key(), 1, "primary-steps",
                "primary-steps", next, List.of(new DagWorkPort.Event("task.primary_started", "P2", "主步骤"))));
        assertEquals(next, workPort().read(initial.key()).orElseThrow());
        assertTrue(port.bindPrimaryAction(initial.key(), "P2", "primary-admission-2", control, "action-p2"));
        assertTrue(port.bindPrimaryAction(initial.key(), "P2", "primary-admission-2", control, "action-p2"));
        assertEquals(Optional.of("P2"), workPort().taskForAction(control, "action-p2"));
        assertEquals(false, port.bindPrimaryAction(initial.key(), "P1", "primary-admission-1", control, "action-p2"));
        assertEquals(2, adminCount("SELECT count(*) FROM public.agent_runtime_dag_admissions WHERE run_id='primary-control'"));
        assertEquals(0, permitCount("primary-control"));
        assertTrue(workPort().workForRun(control).isEmpty());
        assertEquals(initial.key(), workPort().workForControl(control).orElseThrow());
        assertEquals(5, sessionEventCount(owner));
    }

    @Test
    void admissionFailureRollsBackSessionEventsAndWorkRevisionTogether() {
        var firstOwner=teamOwner("atomic-first");
        var secondOwner=teamOwner("atomic-second");
        establishRuntimeSession(firstOwner,"atomic-first-bootstrap");
        establishRuntimeSession(secondOwner,"atomic-second-bootstrap");
        var port=workPort();
        var first=queued(firstOwner,"atomic-first-input","atomic-first-control");
        var second=queued(secondOwner,"atomic-second-input","atomic-second-control");
        assertTrue(port.create(first,"atomic-first-create","atomic-first-create"));
        assertTrue(port.create(second,"atomic-second-create","atomic-second-create"));
        var firstActive=active(first,"atomic-first-admission","atomic-shared-run");
        var secondActive=active(second,"atomic-second-admission","atomic-shared-run");
        var events=List.of(new DagWorkPort.Event("task.admitted","task-1","atomic admission"));
        assertEquals(DagWorkPort.Commit.APPLIED,port.commit(first.key(),first.revision(),"atomic-first-commit","atomic-first-commit",firstActive,events));
        long before=sessionEventCount(secondOwner);
        assertThrows(IllegalStateException.class,()->port.commit(second.key(),second.revision(),"atomic-second-commit","atomic-second-commit",secondActive,events));
        assertEquals(second,workPort().read(second.key()).orElseThrow());
        assertEquals(before,sessionEventCount(secondOwner));
        assertEquals(0,adminCount("SELECT count(*) FROM public.agent_runtime_dag_commands WHERE command_id='atomic-second-commit'"));
        assertEquals(0,adminCount("SELECT count(*) FROM public.agent_runtime_dag_admissions WHERE admission_id='atomic-second-admission'"));
    }

    @Test
    void recordedAnswerPreventsLatePlanMutation() throws Exception {
        SessionOwner owner=teamOwner("answer-freeze");
        establishRuntimeSession(owner,"answer-freeze-bootstrap");
        var port=workPort();var queued=queued(owner,"answer-input","answer-control");
        assertTrue(port.create(queued,"accept-answer","accept-answer"));
        var active=active(queued,"answer-admission","answer-run");
        try(var connection=adminConnection();var statement=connection.prepareStatement(
                """
                INSERT INTO public.agent_runtime_team_work_answers(
                    tenant_id,session_id,root_input_id,result_ref,answer_type,answer_text,source_run_refs,
                    answer_revision,message_id,message_event_id,completion_event_id,status)
                VALUES (?,?,?,'answer-final','CANCELLED','已取消','[]',1,
                    'answer-message','answer-event','answer-completed','RECORDED')
                """)) {
            statement.setString(1,owner.sessionRef().tenantId().value());
            statement.setString(2,owner.sessionRef().sessionId());statement.setString(3,queued.key().rootInputId().value());
            statement.executeUpdate();
        }
        assertEquals(DagWorkPort.Commit.CONFLICT,port.commit(queued.key(),queued.revision(),"late-plan","late-plan",active,
                List.of(new DagWorkPort.Event("work.plan_revised","","late"))));
        assertEquals(queued,port.read(queued.key()).orElseThrow());
    }

    @Test
    void teamSessionAdmissionUsesOneCasAndKeepsTheBindingAfterRebuild() throws Exception {
        SessionOwner owner = teamOwner("session-cas");
        establishRuntimeSession(owner, "bootstrap-cas");
        JdbcDagWorkPort first = workPort();
        DagWork queued = queued(owner, "input-cas", "control-cas");
        assertTrue(first.create(queued, "create-cas", "fingerprint-create-cas"));

        DagWork active = active(queued, "admission-cas", "run-cas");
        JdbcDagWorkPort second = workPort();
        List<DagWorkPort.Commit> commits = race(List.of(
                () -> first.commit(queued.key(), queued.revision(), "complete-cas-a",
                        "fingerprint-complete-a", active,
                        List.of(new DagWorkPort.Event("task.admitted", "task-1", "first trigger"))),
                () -> second.commit(queued.key(), queued.revision(), "complete-cas-b",
                        "fingerprint-complete-b", active,
                        List.of(new DagWorkPort.Event("task.admitted", "task-1", "second trigger")))));

        assertEquals(1, commits.stream().filter(DagWorkPort.Commit.APPLIED::equals).count());
        assertEquals(1, commits.stream().filter(DagWorkPort.Commit.CONFLICT::equals).count());
        assertEquals(1, adminCount("""
                SELECT count(*) FROM public.agent_runtime_dag_admissions
                 WHERE tenant_id = 'tenant-dag-work'
                   AND admission_id = 'admission-cas' AND run_id = 'run-cas'
                """));

        JdbcDagWorkPort rebuilt = workPort();
        assertEquals(active, rebuilt.read(queued.key()).orElseThrow());
        assertEquals(queued.key(), rebuilt.workForRun(new RunRef(TENANT, "run-cas")).orElseThrow());
        assertEquals(List.of(active), rebuilt.history(owner, 10));
        assertThrows(IllegalArgumentException.class,
                () -> rebuilt.history(owner, 0));
        assertThrows(IllegalArgumentException.class,
                () -> rebuilt.history(owner, 1001));
        assertEquals(5, sessionEventCount(owner));
    }

    @Test
    void handoffControlRunWithoutRuntimeRunStillOccupiesTheTeamSession() {
        SessionOwner owner = teamOwner("session-handoff");
        establishRuntimeSession(owner, "bootstrap-handoff");
        JdbcDagWorkPort works = workPort();

        DagWork firstQueued = queued(owner, "input-first", "control-first");
        assertTrue(works.create(firstQueued, "create-first", "fingerprint-create-first"));
        DagWork firstActive = activeWithoutTasks(firstQueued);
        assertEquals(DagWorkPort.Commit.APPLIED, works.commit(firstQueued.key(), firstQueued.revision(),
                "handoff-first", "fingerprint-handoff-first", firstActive,
                List.of(new DagWorkPort.Event("work.handoff", "", "coordinator handoff"))));
        assertEquals(0, adminCount("SELECT count(*) FROM public.agent_runtime_execution_runs"));
        assertEquals(firstQueued.key(),
                works.workForControl(new RunRef(TENANT, "control-first")).orElseThrow());

        DagWork secondQueued = queued(owner, "input-second", "control-second");
        assertTrue(works.create(secondQueued, "create-second", "fingerprint-create-second"));
        DagWork secondActive = activeWithoutTasks(secondQueued);
        assertEquals(DagWorkPort.Commit.CONFLICT, works.commit(secondQueued.key(), secondQueued.revision(),
                "handoff-second", "fingerprint-handoff-second", secondActive,
                List.of(new DagWorkPort.Event("work.started", "", "second work"))));
        assertEquals(firstActive, works.active(owner).orElseThrow());
    }

    @Test
    void notDispatchedReservedTaskCancelsAndReleasesItsPermitBeforeRunCreation() {
        SessionOwner owner = teamOwner("session-not-dispatched");
        establishRuntimeSession(owner, "bootstrap-not-dispatched");
        JdbcDagWorkPort works = workPort();
        DagWork queued = queued(owner, "input-not-dispatched", "control-not-dispatched");
        assertTrue(works.create(queued, "create-not-dispatched", "fingerprint-create-not-dispatched"));

        DagWork reserved = reservedActive(queued, "admission-not-dispatched", "run-not-dispatched");
        assertEquals(DagWorkPort.Commit.APPLIED, works.commit(queued.key(), queued.revision(),
                "reserve-not-dispatched", "fingerprint-reserve-not-dispatched", reserved,
                List.of(new DagWorkPort.Event("task.reserved", "task-1", "reserved before dispatch"))));
        assertEquals(1, permitCount("run-not-dispatched"));

        DagWork cancelled = cancelledBeforeDispatch(reserved);
        assertEquals(DagWorkPort.Commit.APPLIED, works.commit(reserved.key(), reserved.revision(),
                "cancel-not-dispatched", "fingerprint-cancel-not-dispatched", cancelled,
                List.of(new DagWorkPort.Event("task.not_dispatched", "task-1", "run was not created"))));
        assertEquals(DagWork.TaskStatus.CANCELLED,
                works.read(reserved.key()).orElseThrow().executions().get("task-1").status());
        assertEquals(0, permitCount("run-not-dispatched"));
        assertEquals(7, sessionEventCount(owner));
    }

    @Test
    void notDispatchedCancellationConflictsWhenTheRunAlreadyExists() {
        SessionOwner owner = teamOwner("session-dispatched");
        establishRuntimeSession(owner, "bootstrap-dispatched");
        JdbcDagWorkPort works = workPort();
        DagWork queued = queued(owner, "input-dispatched", "control-dispatched");
        assertTrue(works.create(queued, "create-dispatched", "fingerprint-create-dispatched"));

        DagWork reserved = reservedActive(queued, "admission-dispatched", "run-dispatched");
        assertEquals(DagWorkPort.Commit.APPLIED, works.commit(queued.key(), queued.revision(),
                "reserve-dispatched", "fingerprint-reserve-dispatched", reserved,
                List.of(new DagWorkPort.Event("task.reserved", "task-1", "reserved before dispatch"))));
        insertRuntimeRun(reserved, "run-dispatched");

        DagWork cancelled = cancelledBeforeDispatch(reserved);
        assertEquals(DagWorkPort.Commit.CONFLICT, works.commit(reserved.key(), reserved.revision(),
                "cancel-dispatched", "fingerprint-cancel-dispatched", cancelled,
                List.of(new DagWorkPort.Event("task.not_dispatched", "task-1", "run exists"))));
        assertEquals(DagWork.TaskStatus.RESERVED,
                works.read(reserved.key()).orElseThrow().executions().get("task-1").status());
        assertEquals(1, permitCount("run-dispatched"));
        assertEquals(5, sessionEventCount(owner));
    }

    private static SessionOwner teamOwner(String sessionId) {
        return SessionOwner.user(new SessionRef(TENANT, sessionId), SessionTarget.team("team-1"),
                new EndUserId(1L));
    }

    private static DagWork queued(SessionOwner owner, String inputId, String controlRun) {
        return new DagWork(new DagWork.Key(owner, new InputId(inputId)), COORDINATOR, 1,
                DagWork.Status.QUEUED, null, 0, 0, Map.of(), Map.of(),
                request(inputId, controlRun));
    }

    private static DagWork active(DagWork queued, String admissionId, String runId) {
        DagPlan.Task task = new DagPlan.Task("task-1", "task-1", new AgentId("member-1"),
                "完成任务", List.of(), Set.of("result"), Set.of());
        DagPlan plan = new DagPlan(1, false, List.of(task));
        DagWork.Binding binding = new DagWork.Binding(admissionId, 1, task,
                new RunRef(TENANT, runId), Map.of());
        DagWork.Execution execution = new DagWork.Execution(binding, DagWork.TaskStatus.WAITING,
                Optional.empty(), false, 0, Map.of(), "waiting", Optional.empty());
        return new DagWork(queued.key(), queued.coordinator(), 2, DagWork.Status.ACTIVE, plan, 1, 0, Map.of(task.id(), execution), Map.of(),
                queued.request());
    }

    private static DagWork reservedActive(DagWork queued, String admissionId, String runId) {
        DagPlan.Task task = new DagPlan.Task("task-1", "task-1", new AgentId("member-1"),
                "完成任务", List.of(), Set.of("result"), Set.of());
        DagPlan plan = new DagPlan(1, false, List.of(task));
        DagWork.Binding binding = new DagWork.Binding(admissionId, 1, task,
                new RunRef(TENANT, runId), Map.of());
        DagWork.Execution execution = new DagWork.Execution(binding, DagWork.TaskStatus.RESERVED,
                Optional.of(new DagWork.Permit(1, Instant.parse("2026-09-25T01:00:00Z"))), false, 0,
                Map.of(), "reserved", Optional.empty());
        return new DagWork(queued.key(), queued.coordinator(), 2, DagWork.Status.ACTIVE, plan, 1, 0, Map.of(task.id(), execution), Map.of(),
                queued.request());
    }

    private static DagWork cancelledBeforeDispatch(DagWork reserved) {
        DagWork.Execution previous = reserved.executions().get("task-1");
        DagWork.Execution cancelled = new DagWork.Execution(previous.binding(), DagWork.TaskStatus.CANCELLED,
                Optional.empty(), false, previous.usedTokens(), previous.outputs(), "not-dispatched",
                previous.sourceEvent());
        return new DagWork(reserved.key(), reserved.coordinator(), reserved.revision() + 1,
                reserved.status(), reserved.plan(), reserved.approvedRevision(), reserved.spentTokens(), Map.of("task-1", cancelled),
                reserved.consumedEvents(), reserved.request());
    }

    private static DagWork activeWithoutTasks(DagWork queued) {
        DagWork.Request request = new DagWork.Request(queued.request().text(),
                queued.request().selectedAgent(),
                queued.request().controlRun(), "handoff", DagWork.Request.Stage.WORKING,
                queued.request().receivedAt(), queued.request().planMode(), queued.request().requesterId(),
                queued.request().channelId());
        return new DagWork(queued.key(), queued.coordinator(), 2, DagWork.Status.ACTIVE, null, 0, 0, Map.of(), Map.of(), request);
    }

    private static DagWork.Request request(String inputId, String controlRun) {
        return new DagWork.Request("团队任务 " + inputId, Optional.empty(),
                new RunRef(TENANT, controlRun), "", DagWork.Request.Stage.ROUTING,
                Instant.parse("2026-09-25T00:00:00Z"), false, "requester", "channel");
    }

    private static JdbcDagWorkPort workPort() {
        return new JdbcDagWorkPort(applicationDataSource(), new TestCodec());
    }

    private static void establishRuntimeSession(SessionOwner owner, String eventId) {
        SessionProjectionTarget target = new SessionProjectionTarget(owner, COORDINATOR,
                new InputId("bootstrap-input-" + eventId), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        SessionEventDraft event = new SessionEventDraft(new EventId(eventId), "session.opened", "1",
                "fingerprint-" + eventId,
                new TraceEnvelope("trace-" + eventId, "span-" + eventId, target.inputId(), Optional.empty()),
                new TypedPayload("test.session.v1", "{}"), Instant.parse("2026-09-25T00:00:00Z"));
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                new JdbcSessionLogPort(applicationDataSource()).append(
                        new SessionLogPort.AppendSessionEvent(target, event)));
    }

    private static long sessionEventCount(SessionOwner owner) {
        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            setTenant(connection, owner.sessionRef().tenantId().value());
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT count(*) FROM public.agent_runtime_session_events
                     WHERE tenant_id = ? AND session_id = ? AND target_kind = ? AND target_id = ?
                    """)) {
                statement.setString(1, owner.sessionRef().tenantId().value());
                statement.setString(2, owner.sessionRef().sessionId());
                statement.setString(3, owner.target().kind().name());
                statement.setString(4, owner.target().id());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("session_event_count_failure", failure);
        }
    }


    private static long adminCount(String sql) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        } catch (Exception failure) {
            throw new IllegalStateException("dag_work_test_count_failure", failure);
        }
    }

    private static long permitCount(String runId) {
        return adminCount("SELECT count(*) FROM public.agent_runtime_dag_permits WHERE tenant_id = '"
                + TENANT.value() + "' AND run_id = '" + runId + "'");
    }

    /** 使用正式 Store 准入建立 TEAM Run，保留已派发 Run 阻止取消预留的原断言。 */
    private static void insertRuntimeRun(DagWork work, String runId) {
        var binding = work.executions().get("task-1").binding();
        seedRuntimeRun(work, runId, binding.task().agentId(), new InputId(binding.admissionId()));
    }

    private static RunSeed seedRuntimeRun(DagWork work, String runId, AgentId actor, InputId input) {
        var owner = work.key().owner();
        var verification = new com.skillforge.runtime.api.ResultRef("verification-" + runId);
        var trace = new TraceEnvelope("trace-" + runId, "span-" + runId, work.key().rootInputId(), Optional.empty());
        var identity = new com.skillforge.runtime.api.port.RunIdentity(owner, actor, input, new RunRef(TENANT, runId), trace, verification, new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var verified = new com.skillforge.runtime.api.port.VerifiedIdentity(
                new com.skillforge.runtime.api.PrincipalRef.SystemPrincipal(TENANT, "dag-test"),
                owner, Set.of("runtime.accept"), 1, verification);
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        var store = new com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore(source, identities,
                (session, run, currentActor, prepared) -> true);
        // 本夹具只证明 DAG 领域对真实 Run 的存在/取消关系；Plan child 共享预算准入由 Store 组合用例覆盖。
        var capacity = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources(1, 1, 1, 1, 100, 100, 200);
        var budget = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(identity.runRef(), identity.runRef(),
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Limits(
                        com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.TEAM, capacity, capacity, capacity,
                        60, 60, "dag-work-fixture:v1")));
        var command = new com.skillforge.runtime.session.spi.ExecutionStore.Admission(verified, identity,
                "dag-seed-" + runId, new com.skillforge.runtime.api.ResultRef("configuration-" + runId),
                new com.skillforge.runtime.session.spi.ExecutionStore.Material(new com.skillforge.runtime.api.ResultRef("input-" + runId),
                        new TypedPayload("dag.input.v1", "{}")),
                new com.skillforge.runtime.session.spi.ExecutionStore.Material(new com.skillforge.runtime.api.ResultRef("context-" + runId),
                        new TypedPayload("dag.context.v1", "{}")), budget, List.of(), List.of());
        identities.register(verified);
        assertTrue(store.admit(command).created());
        assertEquals(identity, store.load(identity.runRef()).orElseThrow().identity());
        return new RunSeed(store, verified);
    }
    private record RunSeed(com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore store,
                           com.skillforge.runtime.api.port.VerifiedIdentity identity) { }

    private static <T> List<T> race(List<Supplier<T>> operations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.size());
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = operations.stream().map(operation -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return operation.get();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return futures.stream().map(JdbcDagWorkPortTest::await).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("dag_work_race_failure", failure);
        }
    }

    private static void setTenant(Connection connection, String tenantId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId);
            statement.execute();
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DataSource applicationDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] { POSTGRES.getHost() });
        dataSource.setPortNumbers(new int[] { POSTGRES.getMappedPort(5432) });
        dataSource.setDatabaseName(POSTGRES.getDatabaseName());
        dataSource.setUser(APP_ROLE);
        dataSource.setPassword(APP_PASSWORD);
        return dataSource;
    }

    private static String readResource(String resourceName) throws Exception {
        try (InputStream resource = JdbcDagWorkPortTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertTrue(resource != null, resourceName + " must be on the test runtime classpath");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 使用与宿主相同的 JSON 格式；重建适配器不能依赖进程内快照缓存。 */
    private static final class TestCodec implements JdbcDagWorkPort.Codec {
        private final com.fasterxml.jackson.databind.ObjectMapper json=SessionSubjectJsonFixture.configure(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        @Override
        public String encode(DagWork work) {
            try{return json.writeValueAsString(work);}catch(java.io.IOException invalid){throw new IllegalArgumentException(invalid);}
        }

        @Override
        public DagWork decode(String payload) {
            try{return json.readValue(payload,DagWork.class);}catch(java.io.IOException invalid){throw new IllegalArgumentException(invalid);}
        }

        @Override
        public String encodeBinding(DagWork.Binding binding) {
            try{return json.writeValueAsString(binding);}catch(java.io.IOException invalid){throw new IllegalArgumentException(invalid);}
        }
    }
}
