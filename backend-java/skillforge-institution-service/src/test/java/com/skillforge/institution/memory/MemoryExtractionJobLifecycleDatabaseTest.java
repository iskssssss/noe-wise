package com.skillforge.institution.memory;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorePort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionStorePort.CreateSession;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCreateResult;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Evidence;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Relation;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Temporal;
import com.skillforge.institution.memory.MemoryExtractionMaterial.FrozenInput;
import com.skillforge.institution.memory.MemoryExtractionMaterial.FactAlias;
import com.skillforge.institution.memory.MemoryExtractionMaterial.PreparedData;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式 V324+ 隔离库中的真实 Mapper、Spring 事务与受限角色；只覆盖 Job 领取材料收束。 */
@Testcontainers(disabledWithoutDocker = false)
final class MemoryExtractionJobLifecycleDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("memory_job_lifecycle").withUsername("skillforge").withPassword("isolated-memory-owner");
    private static DataSource restricted;

    /** 沿用现有真实PG测试入口，全部正式迁移后才给受限业务角色授权；不依赖外部fixture或缩减DDL。 */
    @BeforeAll static void migrate() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var owner = new PGSimpleDataSource();
        owner.setURL(DATABASE.getJdbcUrl()); owner.setUser(DATABASE.getUsername()); owner.setPassword(DATABASE.getPassword());
        try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE memory_job_test LOGIN PASSWORD 'isolated-memory-role' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO memory_job_test");
            statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO memory_job_test");
        }
        var source = new PGSimpleDataSource();
        source.setURL(DATABASE.getJdbcUrl()); source.setUser("memory_job_test"); source.setPassword("isolated-memory-role");
        restricted = source;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String POLICY = "memory-extraction-policy-v2";
    private static final String TABLE = "public.agent_runtime_memory_extraction_jobs";

    /** 使用正式迁移的受限角色核验输入只冻结一次、父fence、逐项回执和UNKNOWN保留。 */
    @Test void originalInputCandidateReceiptAndUnknownSurviveLeaseTakeoverWithoutCrossTenantWrites() throws Exception {
        DataSource source = source();
        Fixture own = fixture(source), other = fixture(source);
        Harness harness = harness(source);
        String extractor = "semantic-test-" + UUID.randomUUID();
        var original = insert(source, own, "semantic", extractor, "READY", false, false, false);
        assertNull(harness.call(null, mapper -> mapper.next(own.tenant(), extractor, POLICY)));
        assertNull(harness.call(other.tenant(), mapper -> mapper.next(own.tenant(), extractor, POLICY)));
        assertNull(harness.call(own.tenant(), mapper -> mapper.next(own.tenant(), extractor, "different-policy")));
        var frozen = new FrozenInput(Instant.parse("2026-10-23T00:00:00.123456789Z"), List.of(new FactAlias("f0", own.fact().ref().factId(), own.fact().revision())), List.of());
        Candidate candidate = candidate(true);
        PreparedData prepared = prepared(own, true);
        var first = harness.call(own.tenant(), mapper -> {
            var claimed = mapper.next(own.tenant(), extractor, POLICY).claimed("first-token");
            assertEquals(1, mapper.claim(claimed, extractor));
            assertEquals("first-token", mapper.lockClaim(claimed, extractor));
            assertEquals(1, mapper.freezeInput(claimed, extractor, frozen));
            assertEquals(0, mapper.freezeInput(claimed, extractor, new FrozenInput(frozen.sourceTime().plusSeconds(1), frozen.facts(), List.of())));
            assertEquals(1, mapper.modelResult(claimed, extractor, "WAITING", "run", "configuration", "invocation"));
            assertEquals(0, mapper.modelResult(claimed, extractor, "WAITING", "other-run", "configuration", "invocation"));
            assertEquals(0, mapper.modelResult(claimed, extractor, "WAITING", "run", "other-configuration", "invocation"));
            assertEquals(0, mapper.modelResult(claimed, extractor, "WAITING", "run", "configuration", "other-invocation"));
            assertEquals(1, mapper.modelResult(claimed, extractor, "COMPLETED", "run", "configuration", "invocation"));
            assertEquals(1, mapper.insertCandidate(claimed, extractor, 0, "original-command-" + extractor, candidate));
            assertEquals(1, mapper.prepareCandidate(claimed, extractor, 0, prepared));
            assertEquals(0, mapper.prepareCandidate(claimed, extractor, 0, prepared(own, false)));
            assertEquals(1, mapper.candidateResult(claimed, extractor, 0, "ACK_PENDING", null, null, "memory_log_pending"));
            return claimed;
        });
        var before = snapshot(source, own.tenant());
        assertThrows(SQLException.class, () -> {
            try (var connection = connection(source, own.tenant()); var statement = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET source_time_nano=0 WHERE tenant_id=? AND canonical_fact_key='semantic'")) {
                statement.setString(1, own.tenant()); statement.executeUpdate();
            }
        });
        assertThrows(SQLException.class, () -> {
            try (var connection = connection(source, own.tenant()); var statement = connection.prepareStatement(
                    "UPDATE public.agent_runtime_memory_extraction_candidates SET review_required=false WHERE tenant_id=?")) {
                statement.setString(1, own.tenant()); statement.executeUpdate();
            }
        });
        assertEquals(0, harness.<Integer>call(other.tenant(), mapper -> mapper.finish(first, extractor, "COMPLETED", null, null, null)));
        assertTrue(harness.call(other.tenant(), mapper -> mapper.candidates(first, extractor)).isEmpty());
        // 仅测试夹具推进已登记租约；生产Worker不回放UNKNOWN，不构造新业务命令。
        try (var connection = connection(source, own.tenant()); var statement = connection.prepareStatement(
                "UPDATE " + TABLE + " SET lease_until=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND canonical_fact_key='semantic'")) {
            statement.setString(1, own.tenant()); assertEquals(1, statement.executeUpdate()); connection.commit();
        }
        var second = harness.call(own.tenant(), mapper -> {
            var claimed = mapper.next(own.tenant(), extractor, POLICY).claimed("second-token");
            assertEquals(1, mapper.claim(claimed, extractor)); return claimed;
        });
        assertEquals(0, harness.<Integer>call(own.tenant(), mapper -> mapper.candidateResult(first, extractor, 0, "RECORDED",
                own.fact().ref().factId(), own.fact().revision(), null)));
        assertEquals(0, harness.<Integer>call(own.tenant(), mapper -> mapper.finish(first, extractor, "FAILED", null, null, "late_worker")));
        var item = harness.call(own.tenant(), mapper -> mapper.candidates(second, extractor)).getFirst();
        assertEquals("ACK_PENDING", item.status()); assertEquals("original-command-" + extractor, item.commandKey());
        assertEquals(own.fact().ref().factId(), item.targetFactId()); assertEquals(own.fact().revision(), item.targetRevision());
        assertEquals(prepared, item.prepared());
        assertEquals(candidate, item.candidate());
        assertEquals(frozen, harness.call(own.tenant(), mapper -> mapper.input(second, extractor)));
        harness.call(own.tenant(), mapper -> {
            assertEquals(1, mapper.candidateResult(second, extractor, 0, "UNKNOWN", null, null, "memory_unknown"));
            assertEquals(1, mapper.finish(second, extractor, "UNKNOWN", null, null, "memory_unknown")); return null;
        });
        assertNull(harness.call(own.tenant(), mapper -> mapper.next(own.tenant(), extractor, POLICY)));
        assertEquals(0, harness.<Integer>call(own.tenant(), mapper -> mapper.compactTerminalCandidates(own.tenant(), 20)));
        var after = snapshot(source, own.tenant());
        assertEquals(before.get("semantic").get("source_time_epoch_second"), after.get("semantic").get("source_time_epoch_second"));
        assertEquals(before.get("semantic").get("source_time_nano"), after.get("semantic").get("source_time_nano"));
        assertEquals("COMPLETED", after.get("semantic").get("model_status"));
        assertEquals("UNKNOWN", harness.call(own.tenant(), mapper -> mapper.candidates(second, extractor)).getFirst().status());
        assertEquals(original.sourceEventId(), own.event().eventId().value());
    }

    @Test void recordedCandidateMappingAndFrozenSnapshotsRemainAfterSuccessfulCompaction() throws Exception {
        DataSource source = source(); Fixture own = fixture(source); Harness harness = harness(source);
        String extractor = "complete-test-" + UUID.randomUUID();
        insert(source, own, "complete", extractor, "READY", false, false, false);
        var job = harness.call(own.tenant(), mapper -> {
            var claimed = mapper.next(own.tenant(), extractor, POLICY).claimed("token");
            assertEquals(1, mapper.claim(claimed, extractor));
            assertEquals(1, mapper.freezeInput(claimed, extractor, new FrozenInput(own.event().occurredAt(), List.of(), List.of())));
            assertEquals(1, mapper.insertCandidate(claimed, extractor, 0, "complete-command-" + extractor, candidate(false)));
            assertEquals(1, mapper.prepareCandidate(claimed, extractor, 0, prepared(own, false)));
            assertEquals(1, mapper.candidateResult(claimed, extractor, 0, "RECORDED", own.fact().ref().factId(), own.fact().revision(), null));
            assertEquals(1, mapper.finish(claimed, extractor, "COMPLETED", own.fact().ref().factId(), own.fact().revision(), null));
            return claimed;
        });
        var before = snapshot(source, own.tenant());
        assertFalse(before.get("complete").containsKey("candidate_data"));
        assertNotNull(before.get("complete").get("source_time_epoch_second"));
        assertEquals(0, harness.<Integer>call(own.tenant(), mapper -> mapper.compactTerminalCandidates(own.tenant(), 20)));
        assertEquals(before, snapshot(source, own.tenant()));
        var item = harness.call(own.tenant(), mapper -> mapper.candidates(job, extractor)).getFirst();
        assertEquals("RECORDED", item.status()); assertEquals(own.fact().ref().factId(), item.factId());
        assertEquals(own.fact().revision(), item.factRevision()); assertNotNull(item.prepared());
    }

    @Test void typedMaterialsRejectForeignRevisionsMissingEvidenceAndLateAppendAtomically() throws Exception {
        DataSource source = source(); Fixture own = fixture(source), other = fixture(source); Harness harness = harness(source);
        String extractor = "typed-material-" + UUID.randomUUID();
        insert(source, own, "typed", extractor, "READY", false, false, false);
        assertThrows(RuntimeException.class, () -> harness.call(own.tenant(), mapper -> {
            var job = mapper.next(own.tenant(), extractor, POLICY).claimed("foreign-alias");
            assertEquals(1, mapper.claim(job, extractor));
            return mapper.freezeInput(job, extractor, new FrozenInput(own.event().occurredAt(),
                    List.of(new FactAlias("f0", other.fact().ref().factId(), other.fact().revision())), List.of()));
        }));
        assertThrows(RuntimeException.class, () -> harness.call(own.tenant(), mapper -> {
            var job = mapper.next(own.tenant(), extractor, POLICY).claimed("incomplete-evidence");
            assertEquals(1, mapper.claim(job, extractor));
            assertEquals(1, mapper.freezeInput(job, extractor, new FrozenInput(own.event().occurredAt(), List.of(), List.of())));
            // 故意只写父候选；deferred完整性守卫必须拒绝缺失的typed集合，整个事务回滚。
            return mapper.insertCandidateData(job, extractor, MemoryExtractionMaterial.CandidateData.from(0, "missing-evidence-" + extractor, candidate(false)));
        }));
        var job = harness.call(own.tenant(), mapper -> {
            var claimed = mapper.next(own.tenant(), extractor, POLICY).claimed("complete-material");
            assertNull(claimed.sourceTimeEpochSecond());
            assertEquals(1, mapper.claim(claimed, extractor));
            assertEquals(1, mapper.freezeInput(claimed, extractor, new FrozenInput(own.event().occurredAt(), List.of(), List.of())));
            assertEquals(1, mapper.insertCandidate(claimed, extractor, 0, "complete-material-" + extractor, candidate(false)));
            return claimed;
        });
        assertThrows(RuntimeException.class, () -> harness.call(own.tenant(), mapper -> mapper.insertCondition(job, extractor, 0, 2, "late-condition")));
        assertNull(harness.call(other.tenant(), mapper -> mapper.input(job, extractor)));
        assertTrue(harness.call(other.tenant(), mapper -> mapper.aliases(job, extractor)).isEmpty());
        assertEquals(candidate(false), harness.call(own.tenant(), mapper -> mapper.candidates(job, extractor)).getFirst().candidate());
        try (var connection = connection(source, own.tenant()); var query = connection.prepareStatement("""
                SELECT table_name,column_name FROM information_schema.columns
                 WHERE table_schema='public' AND table_name IN
                  ('agent_runtime_memory_extraction_jobs','agent_runtime_memory_extraction_sources',
                   'agent_runtime_memory_extraction_candidates','agent_runtime_memory_extraction_fact_aliases',
                   'agent_runtime_memory_extraction_conditions','agent_runtime_memory_extraction_uncertainties',
                   'agent_runtime_memory_extraction_review_reasons','agent_runtime_memory_extraction_candidate_evidence')
                  AND data_type IN ('json','jsonb') ORDER BY table_name,column_name
                """)) {
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals("agent_runtime_memory_extraction_candidates", rows.getString(1));
                assertEquals("value_json", rows.getString(2)); assertFalse(rows.next());
            }
        }
    }

    private static Candidate candidate(boolean correction) {
        var value = JSON.createObjectNode().put("text", "原始候选😀");
        value.set("precise", com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("0.1000000000000000000000000000000000001")));
        return new Candidate("preference", "user:1", "theme", value,
                "observed", false, "user_agent", new Temporal("stable", null, null), List.of("办公时", "使用桌面端"),
                List.of(new Evidence("s0", "真实 Memory 来源😀")),
                new Relation(correction ? "correction" : "new", correction ? "f0" : null), List.of("尚未再次确认"));
    }

    private static PreparedData prepared(Fixture fixture, boolean correction) {
        return new PreparedData("preference", "USER_AGENT", "standard", "v2", "skillforge.memory.preference.v2",
                fixture.event().occurredAt().getEpochSecond(), fixture.event().occurredAt().getNano(), null, null, null, null,
                0.8, 0.5, true, "PENDING_CONFIRM", correction ? fixture.fact().ref().factId() : null,
                correction ? fixture.fact().revision() : null, List.of("memory_candidate_confirmation_required", "memory_candidate_uncertain"));
    }

    /** 来源和结果经真实公共 Port 创建；不创建缩减表、不禁用约束，也不改写历史事实。 */
    private static Fixture fixture(DataSource source) throws Exception {
        var tenant = new TenantId(Long.toString(8_000_000_000_000L + (UUID.randomUUID().getMostSignificantBits() & 0x0fffffff)));
        var owner = SessionOwner.user(new SessionRef(tenant, "s08-" + UUID.randomUUID()), new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input-" + UUID.randomUUID());
        var trace = new TraceEnvelope("trace", "span", input, Optional.empty());
        Instant time = Instant.now();
        assertInstanceOf(SessionCreateResult.Created.class,
                new JdbcSessionStorePort(source).create(new CreateSession(owner, "test", "test", "persona", input, trace, time)));
        var draft = new SessionEventDraft(new EventId("source-" + UUID.randomUUID()), "agent.tool-result", "1", "source-fingerprint",
                trace, new TypedPayload("skillforge.memory.source.v1", "{\"text\":\"真实 Memory 来源😀\"}"), time);
        var target = new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var event = assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                new JdbcSessionLogPort(source).append(new AppendSessionEvent(target, draft))).event();
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of("runtime:memory"), 1, new ResultRef("verification"));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "memory-run"), trace, new ResultRef("causation"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var context = new PortCallContext(run, identity, new InvocationControl(Instant.parse("2100-01-01T00:00:00Z"), () -> false));
        var factDraft = new MemoryFactDraft(
                new MemoryOwner.Session(owner.sessionRef()),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v2",
                "user:1",
                "theme",
                new TypedPayload("skillforge.memory.preference.v2", "{\"value\":{\"text\":\"dark\"},\"conditions\":[],\"temporal\":{\"kind\":\"stable\",\"from\":null,\"to\":null}}"),
                List.of(event.eventId()),
                owner.sessionRef(),
                Optional.empty(),
                Optional.empty(),
                "memory-extractor-v2",
                time,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.5,
                false,
                Optional.empty(),
                FactStatus.ACTIVE);
        var fact = assertInstanceOf(MemoryMutationResult.Applied.class,
                new JdbcMemoryPort(source, MemoryExtractionJobLifecycleDatabaseTest::fixtureSearchDocument).submit(new MemoryCommand.Create(context, new IdempotencyKey("create-fact"), factDraft))).fact();
        return new Fixture(owner, event, context, fact);
    }

    private static MemorySearchDocument fixtureSearchDocument(MemoryFact fact) {
        if (!"skillforge.memory.preference.v2".equals(fact.object().schemaId()))
            throw new IllegalArgumentException("unknown_fixture_memory_schema");
        try {
            var value = JSON.readTree(fact.object().canonicalJson()).get("value");
            if (value == null || !value.path("text").isTextual()) throw new IllegalArgumentException("fixture_memory_value_required");
            return new MemorySearchDocument(value.path("text").textValue(), MemorySearchDocument.VERSION);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("fixture_memory_payload_invalid", failure);
        }
    }

    /** 仅设置新测试 Job 的输入状态；正式 source/result 外键与受限角色 RLS 全程生效。 */
    private static MemoryExtractionMapper.Job insert(DataSource source, Fixture fixture, String key, String extractor,
            String status, boolean claimed, boolean completed, boolean result) throws Exception {
        try (var connection = connection(source, fixture.tenant()); var statement = connection.prepareStatement("""
                INSERT INTO public.agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,memory_type,
                    canonical_fact_key,agent_id,end_user_id,source_session_id,source_execution_id,source_event_type,
                    status,attempts,result_fact_id,result_revision,error_code,available_at,claimed_at,completed_at,created_at,updated_at,
                    source_target_kind,source_target_id)
                VALUES(?,?,?,'preference',?,'agent',1,?,NULL,'agent.tool-result',?,0,?,?,?,
                    now()-interval '2 days',CASE WHEN ? THEN now()-interval '1 day' END,
                    CASE WHEN ? THEN now()-interval '1 day' END,now()-interval '3 days',now()-interval '1 day','AGENT','agent')
                """)) {
            statement.setString(1, fixture.tenant()); statement.setString(2, fixture.event().eventId().value());
            statement.setString(3, extractor); statement.setString(4, key); statement.setString(5, fixture.owner().sessionRef().sessionId());
            statement.setString(6, status);
            statement.setString(7, result ? fixture.fact().ref().factId() : null);
            statement.setObject(8, result ? fixture.fact().revision() : null);
            statement.setString(9, status.equals("READY") ? null : "retained-outcome:" + key);
            statement.setBoolean(10, claimed); statement.setBoolean(11, completed);
            assertEquals(1, statement.executeUpdate());
            connection.commit();
        }
        return new MemoryExtractionMapper.Job(fixture.tenant(), fixture.event().eventId().value(), "preference", key,
                "agent", "AGENT", "agent", 1, fixture.owner().sessionRef().sessionId(), null, "agent.tool-result", POLICY, null, null, null, null, null, null, null, null, null);
    }

    private static Harness harness(DataSource source) throws Exception {
        var configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setPlugins(new TenantRlsInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/MemoryExtractionMapper.xml"));
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setTimeout(10);
        return new Harness(new SqlSessionTemplate(factory.getObject()).getMapper(MemoryExtractionMapper.class), transactions);
    }

    private record Harness(MemoryExtractionMapper mapper, TransactionTemplate transactions) {
        <T> T call(String tenant, Function<MemoryExtractionMapper, T> work) { return call(tenant, work, false); }
        <T> T call(String tenant, Function<MemoryExtractionMapper, T> work, boolean rollback) {
            String previous = TenantContext.getTenantId();
            if (tenant == null) TenantContext.clear(); else TenantContext.setTenantId(tenant);
            try { return transactions.execute(status -> { T value = work.apply(mapper); if (rollback) status.setRollbackOnly(); return value; }); }
            finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
        }
    }

    private record Fixture(SessionOwner owner, CommittedSessionEvent event, PortCallContext context, MemoryFact fact) {
        String tenant() { return owner.sessionRef().tenantId().value(); }
    }

    private static Map<String, Map<String, Object>> snapshot(DataSource source, String tenant) throws Exception {
        try (var connection = connection(source, tenant)) { return snapshot(connection, tenant); }
    }

    /** Spring 事务中读取刚 claim 的非空领取事实，不借另一个连接观察未提交数据。 */
    private static Map<String, Map<String, Object>> snapshotCurrent(DataSource source, String tenant) {
        Connection connection = DataSourceUtils.getConnection(source);
        try { return snapshot(connection, tenant); }
        catch (Exception failure) { throw new AssertionError("job_transaction_snapshot_failed", failure); }
        finally { DataSourceUtils.releaseConnection(connection, source); }
    }

    private static Map<String, Map<String, Object>> snapshot(Connection connection, String tenant) throws Exception {
        var result = new TreeMap<String, Map<String, Object>>();
        try (var statement = connection.prepareStatement("SELECT canonical_fact_key,to_jsonb(j)::text FROM " + TABLE
                + " j WHERE tenant_id=? ORDER BY canonical_fact_key")) {
            statement.setString(1, tenant);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> row = JSON.readValue(rows.getString(2), new TypeReference<Map<String, Object>>() {});
                    assertNull(result.put(rows.getString(1), row));
                }
            }
        }
        return result;
    }

    private static void assertUnchangedExcept(Map<String, Object> before, Map<String, Object> after, Set<String> permitted) {
        assertEquals(before.keySet(), after.keySet());
        for (var field : before.keySet()) if (!permitted.contains(field)) assertEquals(before.get(field), after.get(field), field);
    }

    private static Connection connection(DataSource source, String tenant) throws SQLException {
        var connection = source.getConnection(); connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
            statement.setString(1, tenant); statement.execute();
        } catch (SQLException failure) { connection.close(); throw failure; }
        return connection;
    }

    /** 独占容器与受限角色是本测试唯一连接入口。 */
    private static DataSource source() throws Exception {
        try (var connection = restricted.getConnection(); var statement = connection.createStatement();
                var roles = statement.executeQuery("SELECT current_database(),rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(roles.next()); assertEquals("memory_job_lifecycle", roles.getString(1));
            assertFalse(roles.getBoolean(2)); assertFalse(roles.getBoolean(3));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM public.flyway_schema_history WHERE version='324' AND success"));
        }
        return restricted;
    }

    private static long scalar(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            try (var row = statement.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

}
