package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.adapter.jdbc.fixture.SessionSubjectJsonFixture;
import com.skillforge.runtime.adapter.jdbc.schema.RuntimeJdbcSchema;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionStorePort.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 自有 pgvector PostgreSQL 空库通过正式 Runtime schema 初始化，业务连接使用真实受限角色。
 * 历史事实通过真实SessionStore原生codec/Mapper和三个独立事务创建、激活、关闭，保留原关闭纳秒。
 * 每项测试使用独立tenant，类结束由Testcontainers回收整份隔离DB；不disable trigger或伪造decoder。
 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcSessionStorageMaintenanceDatabaseTest {
    private static final String APP_ROLE = "maintenance_app";
    private static final String APP_PASSWORD = "maintenance_test_password";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("runtime_maintenance").withUsername("maintenance_admin")
            .withPassword("maintenance_admin_test_password");
    private static DataSource applicationSource;

    /** 引用检查会跨 Runtime 领域读取保护事实；不授予这些领域写权或任何平台表权限。 */
    private static final List<String> PROTECTION_TABLES = List.of(
            "agent_runtime_actions", "agent_runtime_runs", "agent_runtime_execution_runs",
            "agent_runtime_execution_actions", "agent_runtime_executions", "agent_runtime_input_ledger",
            "agent_runtime_session_inbox", "agent_runtime_session_tool_projections", "agent_runtime_session_outbox",
            "agent_runtime_session_output_chunks", "agent_runtime_session_event_payload_archives",
            "agent_runtime_memory_fact_sources", "agent_runtime_memory_extraction_jobs",
            "agent_runtime_memory_extraction_evidence", "agent_runtime_run_state_publications",
            "agent_runtime_execution_state_publications", "agent_runtime_action_state_publications",
            "agent_runtime_event_deliveries", "agent_runtime_event_conflict_audits",
            "agent_runtime_delegations", "agent_runtime_delegation_waits");
    /** 包含不可变事件拒绝路径、正文恢复与历史引用检查的函数闭包；不开放整个 public 函数集合。 */
    private static final List<String> FUNCTIONS = List.of(
            "agent_runtime_session_archive_gate", "agent_runtime_session_archive_is_marker",
            "agent_runtime_session_archive_marker", "agent_runtime_session_archive_original_row",
            "agent_runtime_session_archive_spec", "agent_runtime_session_archive_text_digest",
            "agent_runtime_session_archive_update_valid", "agent_runtime_session_archived_event_payload",
            "agent_runtime_session_event_digest", "agent_runtime_session_event_host_referenced",
            "agent_runtime_session_event_payload", "agent_runtime_session_event_referenced",
            "agent_runtime_session_history_answer_guard", "agent_runtime_session_history_closed_guard",
            "agent_runtime_session_history_field", "agent_runtime_session_history_native_fields",
            "agent_runtime_session_history_owner_protection", "agent_runtime_session_history_snapshot",
            "agent_runtime_session_history_string", "agent_runtime_session_history_string_token",
            "agent_runtime_session_output_payload", "agent_runtime_session_owner_archive_protection",
            "agent_runtime_session_task_snapshot_referenced", "agent_runtime_session_task_source_fields",
            "agent_runtime_session_task_user_owner_valid");

    @BeforeAll
    static void initializeDatabase() throws Exception {
        var admin = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        RuntimeJdbcSchema.install(admin);
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            for (String table : PROTECTION_TABLES) grant(statement, "SELECT", table);
            for (String table : List.of("agent_runtime_session_owners", "agent_runtime_sessions",
                    "agent_runtime_session_streams", "agent_runtime_session_history_retirements",
                    "agent_runtime_conversation_task_roots", "agent_runtime_conversation_tasks",
                    "agent_runtime_conversation_task_bindings")) {
                grant(statement, "SELECT,INSERT,UPDATE", table);
            }
            for (String table : List.of("agent_runtime_session_mutations", "agent_runtime_session_event_tombstones",
                    "agent_runtime_conversation_task_operations",
                    "agent_runtime_session_event_bodies", "agent_runtime_session_event_body_refs")) {
                grant(statement, "SELECT,INSERT", table);
            }
            // 必须让非法UPDATE/DELETE真正到达正式触发器，不能以缺少SQL权限代替不可变性断言。
            grant(statement, "SELECT,INSERT,UPDATE,DELETE", "agent_runtime_session_events");
            grant(statement, "UPDATE(content_hash)", "agent_runtime_session_event_bodies");
            // 这七项只使用显式revision和Session stream，不调用两个独立fence sequence，故不授予sequence权限。
            statement.execute("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");
            try (var functions = connection.prepareStatement("SELECT p.proname, p.oid::regprocedure::text AS signature "
                    + "FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'")) {
                try (var rows = functions.executeQuery()) {
                    while (rows.next()) {
                        if (FUNCTIONS.contains(rows.getString("proname"))) {
                            statement.execute("GRANT EXECUTE ON FUNCTION " + rows.getString("signature") + " TO " + APP_ROLE);
                        }
                    }
                }
            }
        }
        applicationSource = dataSource(APP_ROLE, APP_PASSWORD);
        try (var connection = applicationSource.getConnection(); var statement = connection.createStatement();
                var roles = statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(roles.next());
            assertTrue(roles.getBoolean(1), "所有业务断言必须由真实受限RLS角色执行");
        }
    }

    private static void grant(java.sql.Statement statement, String permissions, String table) throws SQLException {
        statement.execute("GRANT " + permissions + " ON public." + table + " TO " + APP_ROLE);
    }

    private static PGSimpleDataSource dataSource(String user, String password) {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(user); source.setPassword(password);
        return source;
    }

    @Test void javaAndPostgresHaveTheSameIndependentFullEnvelopeGolden() throws Exception {
        var source=source();
        String golden=RuntimeJson.writeCanonical(Map.ofEntries(
                Map.entry("tenant_id","t"),Map.entry("session_id","s"),Map.entry("target_kind","AGENT"),Map.entry("target_id","a"),
                Map.entry("end_user_id","1"),Map.entry("agent_id","a"),Map.entry("input_id","input"),Map.entry("run_id","run"),
                Map.entry("execution_id","execution"),Map.entry("action_id","action"),Map.entry("delegation_id","delegation"),
                Map.entry("event_id","event"),Map.entry("event_type","agent.model-reasoning"),Map.entry("event_version","1"),
                Map.entry("event_fingerprint","fingerprint"),Map.entry("trace_id","trace"),Map.entry("span_id","span"),
                Map.entry("root_input_id","root"),Map.entry("caused_by_event_id","cause"),Map.entry("payload_schema_id","reasoning.v1"),
                Map.entry("payload_canonical_json","{\"text\":\"原文😀\"}"),Map.entry("occurred_at_epoch_second","1780000000"),
                Map.entry("occurred_at_nano","123456789"),Map.entry("session_sequence","17")));
        try(var connection=source.getConnection();var statement=connection.prepareStatement(
                "SELECT public.agent_runtime_session_event_digest(jsonb_populate_record(NULL::public.agent_runtime_session_events,?::jsonb))")) {
            statement.setString(1,golden);
            try(var rows=statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals("974ab814c641ec95106623832b9bc514e7c290a41475937e131c8e540250f4d1",rows.getString(1));
            }
        }
    }
    @Test void restrictedRlsAndDirectIllegalDeleteUpdateAndFabricatedTombstoneAreRejected() throws Exception {
        var source=source(); var owner=historicalOwner(source);
        var log=new JdbcSessionLogPort(source); var original=reasoning(owner,1,"推理😀");
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,log.append(original));
        try(var connection=connection(source,owner)) {
            try(var statement=connection.createStatement(); var roles=statement.executeQuery(
                    "SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(roles.next()); assertTrue(roles.getBoolean(1),"真实执行角色必须NOSUPERUSER/NOBYPASSRLS");
            }
            var denied=assertThrows(SQLException.class,()->execute(connection,
                    "DELETE FROM public.agent_runtime_session_events WHERE tenant_id=? AND session_id=?",
                    owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId()));
            assertEquals("P0001",denied.getSQLState()); connection.rollback(); bind(connection,owner.sessionRef().tenantId().value());
            var immutable=assertThrows(SQLException.class,()->execute(connection,
                    "UPDATE public.agent_runtime_session_events SET event_fingerprint='altered' WHERE tenant_id=? AND session_id=?",
                    owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId()));
            assertEquals("P0001",immutable.getSQLState()); connection.rollback(); bind(connection,owner.sessionRef().tenantId().value());
            registerRetirement(connection,owner);
            var fabricated=assertThrows(SQLException.class,()->execute(connection,
                    "INSERT INTO public.agent_runtime_session_event_tombstones(tenant_id,session_id,target_kind,target_id,end_user_id,event_id,session_sequence,event_digest) VALUES(?,?,'AGENT',?,1,?,1,?)",
                    owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),owner.target().id(),original.event().eventId().value(),"a".repeat(64)));
            assertEquals("P0001",fabricated.getSQLState()); assertTrue(fabricated.getMessage().contains("digest_mismatch"));
            connection.rollback(); bind(connection,"9223372036854775000");
            assertEquals(0,scalar(connection,"SELECT count(*) FROM public.agent_runtime_session_events WHERE tenant_id=?",
                    owner.sessionRef().tenantId().value()),"另一租户真实RLS不可见");
            connection.rollback();
        }
        assertEquals(1,countEvents(source,owner));
    }

    @Test void realReasoningDeleteMeasuresActualBytesAndOriginalRequestReplaysExactSequence() throws Exception {
        var source=source(); var owner=historicalOwner(source); var log=new JdbcSessionLogPort(source);
        var original=reasoning(owner,1,"原始推理😀");
        var committed=assertInstanceOf(SessionLogPort.AppendResult.Appended.class,log.append(original)).event();
        long physicalBytes;
        try(var connection=connection(source,owner)) {
            physicalBytes=scalar(connection,"SELECT octet_length(payload_canonical_json) FROM public.agent_runtime_session_events WHERE tenant_id=? AND event_id=?",
                    owner.sessionRef().tenantId().value(),original.event().eventId().value());
        }
        var result=new JdbcSessionStorageMaintenance(source).retireHistory(owner.sessionRef().tenantId(),4,8,null);
        assertEquals(1,result.rows()); assertEquals(physicalBytes,result.bytes()); assertEquals(0,countEvents(source,owner));
        var replay=assertInstanceOf(SessionLogPort.AppendResult.Replayed.class,log.append(original)).event();
        assertEquals(committed,replay); assertEquals(0,countEvents(source,owner));
        var draft=original.event();
        var changed=new SessionEventDraft(draft.eventId(),draft.eventType(),draft.eventVersion(),draft.eventFingerprint(),draft.trace(),
                new TypedPayload(draft.payload().schemaId(),draft.payload().canonicalJson().replace("原始","变更")),draft.occurredAt());
        assertInstanceOf(SessionLogPort.AppendResult.Conflict.class,log.append(new AppendSessionEvent(original.target(),changed)));
        try(var connection=connection(source,owner)) {
            assertEquals(1,scalar(connection,"SELECT count(*) FROM public.agent_runtime_session_event_tombstones WHERE tenant_id=?",
                    owner.sessionRef().tenantId().value()));
            assertEquals(1,scalar(connection,"SELECT retired_reasoning_count FROM public.agent_runtime_session_history_retirements WHERE tenant_id=?",
                    owner.sessionRef().tenantId().value()));
        }
    }

    @Test void nativeMetadataPreservesExactNulAndLiteralEscapeLexemesAndRejectsFieldClosureChanges() throws Exception {
        var source=source();
        String query="SELECT public.agent_runtime_session_history_native_fields(?,ARRAY['attemptId','content','messageId','messageVersion','operationKey','phase','sourceSequenceThrough'])->>'content'";
        for(String content:List.of("NUL"+'\0',"literal\\u0000","mixed"+'\0'+"\\u0000\\\"😀\n,\"phase\":\"final\"")) {
            String body=RuntimeJson.writeCanonical(Map.of("attemptId","attempt","content",content,"messageId","message",
                    "messageVersion","1","operationKey","operation","phase","pending","sourceSequenceThrough","1"));
            try(var connection=source.getConnection();var statement=connection.prepareStatement(query)) {
                statement.setString(1,body);
                try(var rows=statement.executeQuery()) { assertTrue(rows.next());assertEquals(RuntimeJson.writeCanonical(content),rows.getString(1)); }
            }
            for(String malformed:List.of(body.replace(",\"messageId\":",",\"content\":\"duplicate\",\"messageId\":"),
                    body.replace("\"content\":"+RuntimeJson.writeCanonical(content),"\"content\":0"))) {
                try(var connection=source.getConnection();var statement=connection.prepareStatement(query)) {
                    statement.setString(1,malformed);
                    var rejected=assertThrows(SQLException.class,statement::executeQuery);
                    assertEquals("P0001",rejected.getSQLState());assertTrue(rejected.getMessage().contains("session_history_payload_invalid"));
                }
            }
        }
    }

    @Test void lockedSourceIsSkippedWithoutLostProgressAndRetiresAfterTheReferenceLockIsReleased() throws Exception {
        var source=source(); var owner=historicalOwner(source); var original=reasoning(owner,1,"被引用领取的原片段");
        new JdbcSessionLogPort(source).append(original);
        var maintenance=new JdbcSessionStorageMaintenance(source);
        try(var blocker=connection(source,owner)) {
            execute(blocker,"SELECT event_id FROM public.agent_runtime_session_events WHERE tenant_id=? AND event_id=? FOR KEY SHARE",
                    owner.sessionRef().tenantId().value(),original.event().eventId().value());
            var blocked=maintenance.retireHistory(owner.sessionRef().tenantId(),4,8,null);
            assertEquals(0,blocked.rows()); assertTrue(blocked.protectedReasons().contains("EVENT_LOCKED"));
            try(var inspection=connection(source,owner)) {
                assertEquals(1,scalar(inspection,"SELECT scan_after_sequence FROM public.agent_runtime_session_history_retirements WHERE tenant_id=?",
                        owner.sessionRef().tenantId().value()));
                assertEquals(0,scalar(inspection,"SELECT resume_after_sequence FROM public.agent_runtime_session_history_retirements WHERE tenant_id=?",
                        owner.sessionRef().tenantId().value()));
            }
            blocker.rollback();
        }
        var result=maintenance.retireHistory(owner.sessionRef().tenantId(),4,8,null);
        assertEquals(1,result.rows()); assertEquals(0,countEvents(source,owner));
    }

    @Test void diagnosticDeleteAlsoRetainsExactReplayAndIndependentPhysicalMetrics() throws Exception {
        var source=source(); var owner=historicalOwner(source); var log=new JdbcSessionLogPort(source);
        String schema="skillforge.runtime.agent.runtime-diagnostic.v1";
        String body=RuntimeJson.writeCanonical(Map.of("kind","stage","operationKey","operation","metrics",Map.of("duration","1")));
        var target=new SessionProjectionTarget(owner,new InputId("input"),Optional.of(new RunRef(owner.sessionRef().tenantId(),"run")),
                Optional.empty(),Optional.empty(),Optional.empty());
        var original=new AppendSessionEvent(target,new SessionEventDraft(new EventId("runtime-diagnostic:"+UUID.randomUUID()),
                "agent.runtime-diagnostic","1",hash(schema,body),new TraceEnvelope("trace","span",new InputId("input"),Optional.empty()),
                new TypedPayload(schema,body),Instant.now().minus(Duration.ofDays(35))));
        var committed=assertInstanceOf(SessionLogPort.AppendResult.Appended.class,log.append(original)).event();
        var result=new JdbcSessionStorageMaintenance(source).purgeDiagnostics(owner,Instant.now().minus(Duration.ofDays(10)),8);
        assertEquals(1,result.rows()); assertEquals(body.getBytes(StandardCharsets.UTF_8).length,result.bytes());
        assertEquals(0,countEvents(source,owner));
        assertEquals(committed,assertInstanceOf(SessionLogPort.AppendResult.Replayed.class,log.append(original)).event());
        try(var connection=connection(source,owner)) {
            assertEquals(1,scalar(connection,"SELECT retired_diagnostic_count FROM public.agent_runtime_session_history_retirements WHERE tenant_id=?",
                    owner.sessionRef().tenantId().value()));
            assertEquals(0,scalar(connection,"SELECT retired_event_count FROM public.agent_runtime_session_history_retirements WHERE tenant_id=?",
                    owner.sessionRef().tenantId().value()),"诊断回收不能冒充普通展示退休计量");
        }
    }

    @Test void realSessionCausationAndConversationTaskReferencesAreCheckedBeforeAnyDeletion() throws Exception {
        var source=source(); var owner=historicalOwner(source); var log=new JdbcSessionLogPort(source);
        var original=reasoning(owner,1,"原始来源"); log.append(original);
        // 旧Runtime outbox已封存；通过现行SessionLog追加真实因果引用，不能为造数解除封存触发器。
        var trace=original.event().trace();
        String referenceBody=RuntimeJson.writeCanonical(Map.of("sourceEventId",original.event().eventId().value()));
        var reference=new AppendSessionEvent(original.target(),new SessionEventDraft(
                new EventId("causation-reference:"+UUID.randomUUID()),"session.jdbc-test","1",
                hash("session.jdbc-test.v1",referenceBody),new TraceEnvelope(trace.traceId(),trace.spanId(),trace.rootInputId(),
                        Optional.of(original.event().eventId())),new TypedPayload("session.jdbc-test.v1",referenceBody),Instant.now()));
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,log.append(reference));
        var result=new JdbcSessionStorageMaintenance(source).retireHistory(owner.sessionRef().tenantId(),4,8,null);
        assertEquals(0,result.rows()); assertTrue(result.protectedReasons().contains("EVENT_REFERENCE"));
        assertEquals(2,countEvents(source,owner),"真实因果引用和被引用reasoning均保留");
        try(var connection=connection(source,owner)) {
            assertEquals(1,scalar(connection,"SELECT public.agent_runtime_session_event_referenced(?,?, 'AGENT',?,1,?)::integer",
                    owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),owner.target().id(),original.event().eventId().value()));
        }
        var taskOwner=historicalOwner(source); var taskOriginal=reasoning(taskOwner,1,"任务引用来源"); log.append(taskOriginal);
        String task="task-"+UUID.randomUUID();
        var codec=new TaskCodec();
        var taskValue=new ConversationTask(task,taskOwner.sessionRef().tenantId(),taskOwner.requireUser(),taskOwner.target().requireAgent(),1,
                ConversationTask.Status.OPEN,"保护来源引号\"反斜\\😀字面\\u0000","完整任务快照引号\"反斜\\😀字面\\u0000",
                new TypedPayload(ConversationTask.STATE_SCHEMA,"{\"memo\":\"\\u0000\"}"),
                List.of(new ConversationTask.Source(taskOwner,taskOriginal.event().eventId())),List.of(),Instant.now());
        var payload=codec.encode(taskValue);
        var taskDraft=new SessionEventDraft(new EventId("task-event:"+UUID.randomUUID()),"task.changed","1","task-fingerprint",
                taskOriginal.event().trace(),payload,Instant.now());
        var taskAppend=new AppendSessionEvent(new SessionProjectionTarget(taskOwner,new InputId("input"),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()),taskDraft);
        var tasks=new JdbcConversationTaskPort(source,codec);
        assertInstanceOf(ConversationTaskPort.Result.Committed.class,tasks.commit(
                new ConversationTaskPort.Change(taskOwner,taskValue,0,taskAppend,true,Optional.empty())));
        assertEquals(taskValue,tasks.find(taskOwner,taskOwner.target().requireAgent(),task).orElseThrow(),"临时metadata隔离不能修改持久任务正文");
        // 独立PG text title/summary不支持裸NUL；仅SELECT原canonical JSON中的转义NUL，不扩大生产Task契约。
        var nulMetadata=new ConversationTask(task,taskValue.tenantId(),taskValue.endUserId(),taskValue.agentId(),taskValue.revision(),
                taskValue.status(),taskValue.title()+'\0',taskValue.summary()+'\0',taskValue.state(),taskValue.sources(),taskValue.maintenance(),taskValue.updatedAt());
        try(var connection=source.getConnection();var statement=connection.prepareStatement(
                "SELECT metadata->>'title',metadata->>'summary' FROM (SELECT public.agent_runtime_session_task_source_fields(?) AS metadata) scoped")) {
            statement.setString(1,codec.encode(nulMetadata).canonicalJson());
            try(var rows=statement.executeQuery()) {
                assertTrue(rows.next());assertEquals(codec.json.writeValueAsString(nulMetadata.title()),rows.getString(1));
                assertEquals(codec.json.writeValueAsString(nulMetadata.summary()),rows.getString(2));
            }
        }
        var protectedTask=new JdbcSessionStorageMaintenance(source).retireHistory(taskOwner.sessionRef().tenantId(),4,8,null);
        assertEquals(0,protectedTask.rows()); assertTrue(protectedTask.protectedReasons().contains("EVENT_REFERENCE"));
        assertEquals(3,countEvents(source,taskOwner),"原reasoning、完整task.changed与独立selected事实均保留");
    }

    /** 测试始终连接本类容器的受限应用角色，不能通过环境变量切换外部数据库。 */
    private static DataSource source() { return applicationSource; }
    /** 真实完整JSON codec，不通过内存map或pass-through解码替代持久任务快照。 */
    private static final class TaskCodec implements JdbcConversationTaskPort.Codec {
        private final com.fasterxml.jackson.databind.ObjectMapper json=SessionSubjectJsonFixture.configure(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        @Override public TypedPayload encode(ConversationTask task) {
            try { return new TypedPayload("skillforge.conversation-task.snapshot.v2",json.writeValueAsString(task)); }
            catch(java.io.IOException failure) { throw new IllegalArgumentException(failure); }
        }
        @Override public ConversationTask decode(TypedPayload payload) {
            if(!"skillforge.conversation-task.snapshot.v2".equals(payload.schemaId()))throw new IllegalArgumentException("task_schema_invalid");
            try { return json.readValue(payload.canonicalJson(),ConversationTask.class); }
            catch(java.io.IOException failure) { throw new IllegalArgumentException(failure); }
        }
    }
    private static SessionOwner historicalOwner(DataSource source) throws Exception {
        var tenant=new TenantId(Long.toString(8_000_000_000_000L+(UUID.randomUUID().getMostSignificantBits()&0x0fffffff)));
        var owner=SessionOwner.user(new SessionRef(tenant,"r5-"+UUID.randomUUID()),new AgentId("agent"),new EndUserId(1));
        Instant time=Instant.now().minus(Duration.ofDays(40)).with(java.time.temporal.ChronoField.NANO_OF_SECOND,19);
        var configuration=new Configuration(new Environment("r5-historical-source",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource="mapper/AgentRuntimeSessionStoreMapper.xml";
        try(InputStream xml=JdbcSessionStorageMaintenanceDatabaseTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml); new XMLMapperBuilder(xml,configuration,resource,configuration.getSqlFragments()).parse();
        }
        var sessions=new SqlSessionFactoryBuilder().build(configuration);
        var input=new InputId("input"); var trace=new TraceEnvelope("trace","span",input,Optional.empty());
        var create=new CreateSession(owner,"test","test","persona",input,trace,time);
        String encoded=SessionPersistenceCodec.encode(create);
        try(var session=sessions.openSession(false)) {
            var mapper=session.getMapper(SessionStoreMapper.class); mapper.bindTenant(tenant.value());
            mapper.ensureOwner(SessionOwnerRow.initial(owner));
            assertEquals(1,mapper.insertSession(SessionStoreRow.initial(create,encoded,SessionStoreHashes.fingerprint("create",encoded),time)));
            session.commit();
        }
        for(int revision=1; revision<=2; revision++) {
            var before=revision==1?SessionStatus.CREATED:SessionStatus.ACTIVE;
            var after=revision==1?SessionStatus.ACTIVE:SessionStatus.CLOSED;
            Instant updated=time.plusSeconds(revision);
            var change=new ChangeSessionStatus(owner,before,after,revision,input,trace,updated);
            String command=SessionPersistenceCodec.encode(change);
            try(var session=sessions.openSession(false)) {
                var mapper=session.getMapper(SessionStoreMapper.class); mapper.bindTenant(tenant.value());
                mapper.lockOwner(tenant.value(),"AGENT","agent",owner.sessionRef().sessionId());
                mapper.lockSession(tenant.value(),"AGENT","agent",owner.sessionRef().sessionId());
                mapper.insertMutation(SessionStoreMutationRow.from(change,command,SessionStoreHashes.fingerprint("cas",command),revision+1,updated));
                assertEquals(1,mapper.updateSession(tenant.value(),"AGENT","agent",owner.sessionRef().sessionId(),1,before.name(),after.name(),
                        revision,revision+1,updated.getEpochSecond(),updated.getNano()));
                session.commit();
            }
        }
        var stored=new JdbcSessionStorePort(source).find(new SessionLookup(owner)).orElseThrow();
        assertEquals(SessionStatus.CLOSED,stored.status()); assertEquals(19,stored.updatedAt().getNano());
        return owner;
    }
    private static AppendSessionEvent reasoning(SessionOwner owner,long ordinal,String content) {
        String body=RuntimeJson.writeCanonical(Map.of("attemptId","attempt","batchOrdinal",Long.toString(ordinal),"content",content,
                "sourceSequenceFrom",Long.toString(ordinal),"sourceSequenceThrough",Long.toString(ordinal)));
        String identity=RuntimeJson.writeCanonical(Map.of("operationKey","operation","attemptId","attempt","batchOrdinal",Long.toString(ordinal)));
        return append(owner,new EventId("model-reasoning:"+hash("skillforge.runtime.agent.model-reasoning-batch-identity.v1",identity)),
                "agent.model-reasoning","1","skillforge.runtime.agent.model-reasoning.v2",body);
    }
    private static AppendSessionEvent append(SessionOwner owner,EventId id,String type,String version,String schema,String body) {
        var target=new SessionProjectionTarget(owner,new InputId("input"),Optional.of(new RunRef(owner.sessionRef().tenantId(),"run")),Optional.empty(),
                Optional.of(new ActionRef(owner.sessionRef().tenantId(),"model-action:operation")),Optional.empty());
        return new AppendSessionEvent(target,new SessionEventDraft(id,type,version,"fingerprint:"+id.value(),
                new TraceEnvelope("trace","span",new InputId("input"),Optional.empty()),new TypedPayload(schema,body),
                Instant.now().minus(Duration.ofDays(35))));
    }
    private static String hash(String schema,String body) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((schema+"\n"+body).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static Connection connection(DataSource source,SessionOwner owner) throws Exception {
        var connection=source.getConnection(); connection.setAutoCommit(false); bind(connection,owner.sessionRef().tenantId().value()); return connection;
    }
    private static void bind(Connection connection,String tenant) throws Exception { execute(connection,"SELECT set_config('app.tenant_id',?,true)",tenant); }
    private static void registerRetirement(Connection connection,SessionOwner owner) throws Exception {
        execute(connection,"INSERT INTO public.agent_runtime_session_history_retirements(tenant_id,session_id,target_kind,target_id,end_user_id,source_session_revision,closed_epoch_second,closed_nano) SELECT tenant_id,session_id,target_kind,target_id,end_user_id,session_revision,updated_epoch_second,updated_nano FROM public.agent_runtime_sessions WHERE tenant_id=? AND session_id=?",
                owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId());
    }
    private static void execute(Connection connection,String sql,Object... arguments) throws Exception {
        try(var statement=connection.prepareStatement(sql)) { for(int index=0;index<arguments.length;index++) statement.setObject(index+1,arguments[index]); statement.execute(); }
    }
    private static long scalar(Connection connection,String sql,Object... arguments) throws Exception {
        try(var statement=connection.prepareStatement(sql)) {
            for(int index=0;index<arguments.length;index++) statement.setObject(index+1,arguments[index]);
            try(var result=statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }
    private static long countEvents(DataSource source,SessionOwner owner) throws Exception {
        try(var connection=connection(source,owner)) { return scalar(connection,"SELECT count(*) FROM public.agent_runtime_session_events WHERE tenant_id=? AND session_id=?",
                owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId()); }
    }
}
