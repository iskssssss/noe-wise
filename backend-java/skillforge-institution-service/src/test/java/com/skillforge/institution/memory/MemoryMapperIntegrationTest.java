package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.institution.knowledge.JdbcKnowledgeWriteProposalSourceMapper;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立 PostgreSQL Mapper 契约：真实 Spring 事务、受限角色 RLS 与记录映射。 */
@Testcontainers
class MemoryMapperIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate admin;
    SqlSessionTemplate sessions;
    TransactionTemplate transactions;

    @BeforeAll
    static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE ROLE memory_reader LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS");
        admin.execute("CREATE ROLE skillforge NOLOGIN NOSUPERUSER NOBYPASSRLS");
        admin.execute("""
            create table nw_tenants(id bigint primary key,status text);
            create table agent_runtime_memory_extraction_jobs(
                tenant_id text, source_event_id text, extractor_version text, memory_type text,
                canonical_fact_key text, agent_id text, source_target_kind text, source_target_id text,
                end_user_id bigint, source_session_id text, source_execution_id text, source_event_type text,
                candidate_data jsonb, status text, claimed_at timestamptz, available_at timestamptz default now(),
                created_at timestamptz default now(),updated_at timestamptz,attempts int default 0,
                result_fact_id text,result_revision bigint,error_code text,completed_at timestamptz,
                constraint pk_agent_runtime_memory_extraction_job primary key(tenant_id,source_event_id,extractor_version,memory_type,canonical_fact_key),
                constraint ck_agent_runtime_memory_extraction_status check(status in ('READY','PROCESSING','COMPLETED','FAILED','SKIPPED')));
            create table agent_runtime_session_events(tenant_id text,event_id text,agent_id text,
                target_kind text,target_id text,end_user_id bigint,session_id text,execution_id text,
                event_type text,payload_canonical_json text,run_id text,payload_schema_id text);
            create table nw_runtime_knowledge_results(tenant_id text,bundle_json text);
            create table agent_runtime_session_owners(tenant_id text,session_id text,subject_kind text,end_user_id bigint);
            create table agent_runtime_memory_fact_heads(tenant_id text,fact_id text,end_user_id bigint,
                agent_id text,current_revision bigint,scope text,type_id text,group_binding_id text);
            create table agent_runtime_memory_fact_revisions(tenant_id text,fact_id text,revision bigint,
                session_id text,source_session_id text,status text,created_epoch_second bigint,created_nano int,
                primary key(tenant_id,fact_id,revision));
            alter table agent_runtime_memory_fact_revisions add column content_revision bigint not null default 1;
            create table agent_runtime_memory_fact_sources(tenant_id text,fact_id text,revision bigint,
                agent_id text,source_ordinal int);
            create table agent_runtime_memory_usage_projection(tenant_id text,fact_id text,
                projected_decay_score double precision,last_referenced_at timestamptz,updated_at timestamptz,
                primary key(tenant_id,fact_id));
            grant usage on schema public to memory_reader;
            grant select,insert,update on all tables in schema public to memory_reader;
            """);
        try (var sql = new ClassPathResource("db/migration/V258__memory_restore_decay_projection.sql").getInputStream()) {
            admin.execute(new String(sql.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        // 正式 Mapper 总是经过公开的持久正文读取函数；本夹具使用未归档的 inline 来源，
        // 直接加载当前迁移函数，保留其完整解码/摘要行为，不另造 RETURN stored 替身。
        String archives = "db/migration/V287__runtime_session_private_archive_retirement.sql";
        for (String function : List.of("agent_runtime_session_archive_text_digest",
                "agent_runtime_session_archive_marker", "agent_runtime_session_archive_is_marker",
                "agent_runtime_session_archived_event_payload")) {
            installMigrationFunction(archives, function);
        }
        installMigrationFunction("db/migration/V286__runtime_session_storage_lifecycle.sql",
                "agent_runtime_session_output_payload");
        installMigrationFunction("db/migration/V321__group_memory_ownership.sql",
                "agent_runtime_session_event_payload");
        installMigrationFunction("db/migration/V133__agent_runtime_memory_store.sql", "agent_runtime_memory_immutable");

        // 提取队列/候选直接执行本次完整正式迁移，避免在测试维护第二份语义队列DDL。
        try (var migration = new ClassPathResource("db/migration/V324__memory_semantic_extraction.sql").getInputStream()) {
            admin.execute(new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        admin.execute("grant select,insert,update on all tables in schema public to memory_reader");

        for (String table : List.of("agent_runtime_memory_extraction_jobs", "agent_runtime_session_events", "agent_runtime_session_owners",
                "nw_runtime_knowledge_results", "agent_runtime_memory_fact_heads", "agent_runtime_memory_fact_revisions",
                "agent_runtime_memory_fact_sources", "agent_runtime_memory_usage_projection")) {
            admin.execute("alter table " + table + " enable row level security");
            admin.execute("alter table " + table + " force row level security");
            admin.execute("create policy tenant_scope on " + table
                    + " using (tenant_id=nullif(current_setting('app.tenant_id',true),''))");
        }
    }

    /** 只提取所需正式函数的原始定义；未迁移的引用分支由对应完整链验收负责。 */
    private static void installMigrationFunction(String resource, String function) throws Exception {
        String migration;
        try (var input = new ClassPathResource(resource).getInputStream()) {
            migration = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var declaration = java.util.regex.Pattern.compile("CREATE(?: OR REPLACE)? FUNCTION public\\."
                + java.util.regex.Pattern.quote(function) + "\\(").matcher(migration);
        assertTrue(declaration.find(), () -> "正式迁移缺少函数 " + function);
        int bodyStart = migration.indexOf("AS $$", declaration.start());
        int bodyEnd = migration.indexOf("$$;", bodyStart + 5);
        assertTrue(bodyStart > declaration.start() && bodyEnd > bodyStart,
                () -> "正式函数定义不完整 " + function);
        admin.execute(migration.substring(declaration.start(), bodyEnd + 3));
    }

    @BeforeEach
    void setUp() throws Exception {
        admin.execute("truncate nw_tenants,agent_runtime_memory_extraction_candidates,agent_runtime_memory_extraction_jobs,agent_runtime_session_events,agent_runtime_session_owners,"
                + "nw_runtime_knowledge_results,agent_runtime_memory_fact_heads,agent_runtime_memory_fact_revisions,"
                + "agent_runtime_memory_fact_sources,agent_runtime_memory_usage_projection cascade");
        admin.update("insert into agent_runtime_session_owners values('1','session','USER',42)");
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "memory_reader", "test");
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(new TenantRlsInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/ActiveMemoryTenantMapper.xml"),
                new ClassPathResource("mapper/MemoryGovernanceMapper.xml"),
                new ClassPathResource("mapper/MemoryDecayMapper.xml"),
                new ClassPathResource("mapper/MemoryExtractionMapper.xml"),
                new ClassPathResource("mapper/MemoryProposalSourceMapper.xml"),
                new ClassPathResource("mapper/JdbcKnowledgeWriteProposalSourceMapper.xml"));
        sessions = new SqlSessionTemplate(factory.getObject());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantContext.setTenantId("1");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void terminalCandidateCompactionIsBoundedAndKeepsFailureAndResultIdentity() {
        for (String event : List.of("a", "b", "c", "failed", "processing")) {
            String status = "failed".equals(event) ? "FAILED" : "processing".equals(event) ? "PROCESSING" : "COMPLETED";
            admin.update("""
                insert into agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,
                    memory_type,canonical_fact_key,agent_id,source_target_kind,source_target_id,end_user_id,
                    source_session_id,source_event_type,status,result_fact_id,result_revision,claimed_at,completed_at)
                values('1',?,'memory-extractor-v2','preference','key','agent','AGENT','agent',42,'session',
                    'inbound.message',?,'fact',1,now(),
                    case when ? in ('COMPLETED','SKIPPED','FAILED') then now() end)
                """, event, status, status);
        }
        var mapper = sessions.getMapper(MemoryExtractionMapper.class);
        transactions.executeWithoutResult(status -> {
            assertEquals(2, mapper.compactTerminalCandidates("1", 2));
            assertEquals(1, mapper.compactTerminalCandidates("1", 2));
            assertEquals(0, mapper.compactTerminalCandidates("1", 2));
        });
        assertEquals(5, admin.queryForObject("select count(*) from agent_runtime_memory_extraction_jobs where result_fact_id='fact' and result_revision=1", Integer.class));
        assertEquals(2, admin.queryForObject("select count(*) from agent_runtime_memory_extraction_jobs where claimed_at is not null", Integer.class));
        assertEquals(5, admin.queryForObject("select count(*) from agent_runtime_memory_extraction_jobs", Integer.class));
    }

    @Test
    void claimAndFinishParticipateInSameTransactionAndEnforceTenantAndExtractor() {
        admin.update("""
            insert into agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,
                memory_type,canonical_fact_key,agent_id,source_target_kind,source_target_id,end_user_id,
                source_session_id,source_event_type,status)
            values('1','event','memory-extractor-v2','preference','key','agent','AGENT','agent',42,'session','inbound.message','READY')
            """);
        var mapper = sessions.getMapper(MemoryExtractionMapper.class);
        assertNull(mapper.next("1", "other-extractor", "memory-extraction-policy-v2"));
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            var job = mapper.next("1", "memory-extractor-v2", "memory-extraction-policy-v2").claimed("test-token");
            assertEquals(42, job.endUserId());
            assertNull(job.sourceExecutionId());
            assertEquals(1, mapper.claim(job, "memory-extractor-v2"));
            mapper.finish(job, "memory-extractor-v2", "COMPLETED", "fact", 2L, null);
            throw new IllegalStateException("injected rollback");
        }));
        assertEquals("READY", admin.queryForObject("select status from agent_runtime_memory_extraction_jobs", String.class));
        assertEquals(0, admin.queryForObject("select attempts from agent_runtime_memory_extraction_jobs", Integer.class));
        TenantContext.setTenantId("2");
        assertNull(mapper.next("1", "memory-extractor-v2", "memory-extraction-policy-v2"));
        TenantContext.clear();
        assertNull(mapper.next("1", "memory-extractor-v2", "memory-extraction-policy-v2"));
        TenantContext.setTenantId("1");
        transactions.executeWithoutResult(status -> {
            var job = mapper.next("1", "memory-extractor-v2", "memory-extraction-policy-v2").claimed("test-token");
            mapper.claim(job, "memory-extractor-v2");
            mapper.finish(job, "memory-extractor-v2", "FAILED", null, null, "rejected");
        });
        assertEquals("FAILED", admin.queryForObject("select status from agent_runtime_memory_extraction_jobs", String.class));
        assertNull(admin.queryForObject("select result_revision from agent_runtime_memory_extraction_jobs", Long.class));
    }

    @Test
    void mapsNullableSourcesAndEvidenceAndClearsMissingTenant() {
        admin.update("insert into agent_runtime_session_events(tenant_id,event_id,agent_id,target_kind,target_id,end_user_id,session_id,execution_id,event_type,payload_canonical_json,run_id,payload_schema_id) values('1','event','agent','AGENT','agent',42,'session',null,'inbound.message','{\"text\":\"hi\"}',null,'inbound.message.v1')");
        admin.update("insert into nw_runtime_knowledge_results values('1','{\"bundleId\":\"bundle\"}')");
        var source = sessions.getMapper(MemoryProposalSourceMapper.class);
        assertEquals(new MemoryProposalSourceMapper.Source("agent", 42, "session", null), source.sources("1", "event").getFirst());
        var evidence = sessions.getMapper(JdbcKnowledgeWriteProposalSourceMapper.class);
        var observation = evidence.observations("1", "event").getFirst();
        assertEquals("inbound.message", observation.type());
        assertNull(observation.executionId());
        assertEquals("{\"text\":\"hi\"}", observation.payload());
        assertEquals(1, evidence.bundles("1", "bundle").size());
        assertEquals(List.of("{\"text\":\"hi\"}"), evidence.eventPayloads("1", "event"));
        TenantContext.clear();
        assertTrue(source.sources("1", "event").isEmpty());
        assertTrue(evidence.bundles("1", "bundle").isEmpty());
    }

    @Test
    void mapsGovernanceOwnersAndDecayProjectionAndRotatesGlobalTenantDirectory() {
        admin.update("insert into nw_tenants values(1,'active'),(2,'suspended'),(3,'active')");
        var scan = new ActiveMemoryTenantScan(sessions.getMapper(ActiveMemoryTenantMapper.class));
        assertEquals(List.of("1", "3"), scan.next());
        assertEquals(List.of("1", "3"), scan.next());
        admin.update("insert into agent_runtime_memory_fact_heads(tenant_id,fact_id,end_user_id,agent_id,current_revision,scope,type_id) values('1','fact',42,null,1,'USER','preference')");
        admin.update("insert into agent_runtime_memory_fact_revisions values('1','fact',1,null,'session','ACTIVE',0,0,1)");
        admin.update("insert into agent_runtime_memory_fact_sources values('1','fact',1,'source-agent',0)");
        admin.update("insert into agent_runtime_memory_usage_projection values('1','fact',0,null,null)");
        var governance = sessions.getMapper(MemoryGovernanceMapper.class);
        assertEquals(new MemoryGovernanceMapper.Owner("fact",42,"source-agent","session",0), governance.owner("1", "fact"));
        assertEquals(1, governance.owners("1", "ACTIVE", "USER", "preference", null, 42L, 20).size());
        assertTrue(governance.owners("1", "ACTIVE", "SESSION", null, null, null, 20).isEmpty());
        var decay = sessions.getMapper(MemoryDecayMapper.class);
        transactions.executeWithoutResult(status -> {
            assertEquals(1, decay.project("1", decay.active("1", "", 200)));
            assertEquals(List.of(new MemoryDecayMapper.Due("fact",1)), decay.due("1", "", 200));
        });
        TenantContext.setTenantId("2");
        assertNull(governance.owner("1", "fact"));
    }
    @Test
    void sessionGovernanceDerivesPersonalActorFromCommittedSourceWithoutInventingFactOwnership() {
        admin.update("insert into agent_runtime_memory_fact_heads(tenant_id,fact_id,end_user_id,agent_id,current_revision,scope,type_id) values('1','session-fact',null,null,1,'SESSION','preference')");
        admin.update("insert into agent_runtime_memory_fact_revisions values('1','session-fact',1,'session','session','ACTIVE',0,0,1)");
        admin.update("insert into agent_runtime_memory_fact_sources values('1','session-fact',1,'source-agent',0)");
        var governance = sessions.getMapper(MemoryGovernanceMapper.class);
        assertEquals(new MemoryGovernanceMapper.Owner("session-fact",42,"source-agent","session",0), governance.owner("1", "session-fact"));
        assertEquals(1, governance.owners("1", "ACTIVE", "SESSION", "preference", "source-agent", 42L, 20).size());
        assertTrue(governance.owners("1", "ACTIVE", "SESSION", "preference", "other-agent", 42L, 20).isEmpty());
        assertTrue(governance.owners("1", "ACTIVE", "SESSION", "preference", "source-agent", 43L, 20).isEmpty());
    }

    @Test
    void decaySkipsUnchangedAndTerminalRowsAndResumesBoundedPages() {
        for (String fact : List.of("a", "b", "c", "terminal")) {
            admin.update("insert into agent_runtime_memory_fact_heads(tenant_id,fact_id,end_user_id,agent_id,current_revision,scope,type_id) values('1',?,42,'agent',1,'USER_AGENT','preference')", fact);
            admin.update("insert into agent_runtime_memory_fact_revisions values('1',?,1,null,'session',?,0,0,1)",
                    fact, "terminal".equals(fact) ? "DECAYED" : "ACTIVE");
            admin.update("insert into agent_runtime_memory_usage_projection values('1',?,0,null,null)", fact);
        }
        var decay = sessions.getMapper(MemoryDecayMapper.class);
        transactions.executeWithoutResult(status -> {
            assertEquals(List.of("a", "b"), decay.active("1", "", 2));
            assertEquals(2, decay.project("1", decay.active("1", "", 2)));
            assertEquals(1, decay.project("1", decay.active("1", "b", 2)));
            assertEquals(0, decay.project("1", decay.active("1", "", 200)));
            assertEquals(List.of(new MemoryDecayMapper.Due("a", 1), new MemoryDecayMapper.Due("b", 1)),
                    decay.due("1", "", 2));
            assertEquals(List.of(new MemoryDecayMapper.Due("c", 1)), decay.due("1", "b", 2));
        });
        assertNull(admin.queryForObject("select updated_at from agent_runtime_memory_usage_projection where fact_id='terminal'", java.sql.Timestamp.class));
        var before = admin.queryForObject("select updated_at from agent_runtime_memory_usage_projection where fact_id='a'", java.sql.Timestamp.class);
        transactions.executeWithoutResult(status -> assertEquals(0, decay.project("1", decay.active("1", "", 200))));
        assertEquals(before, admin.queryForObject("select updated_at from agent_runtime_memory_usage_projection where fact_id='a'", java.sql.Timestamp.class));
    }
    @Test
    void activationAndDecayResetCommitOrRollbackTogetherAndReplayDoesNotResetAgain() {
        admin.update("insert into agent_runtime_memory_fact_heads(tenant_id,fact_id,end_user_id,agent_id,current_revision,scope,type_id) values('1','fact',42,'agent',1,'USER_AGENT','preference')");
        admin.update("insert into agent_runtime_memory_fact_revisions values('1','fact',1,null,'session','DECAYED',0,0,1),('1','fact',2,null,'session','ACTIVE',0,0,1)");
        admin.update("insert into agent_runtime_memory_usage_projection values('1','fact',1,null,null)");
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "memory_reader", "test");
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id','1',true)", String.class);
            assertEquals(1, jdbc.update("update agent_runtime_memory_fact_heads set current_revision=2 where tenant_id='1' and fact_id='fact'"));
            assertEquals(0.0, jdbc.queryForObject("select projected_decay_score from agent_runtime_memory_usage_projection", Double.class));
            throw new IllegalStateException("injected failure after projection");
        }));
        assertEquals(1L, admin.queryForObject("select current_revision from agent_runtime_memory_fact_heads", Long.class));
        assertEquals(1.0, admin.queryForObject("select projected_decay_score from agent_runtime_memory_usage_projection", Double.class));
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id','1',true)", String.class);
            jdbc.update("update agent_runtime_memory_fact_heads set current_revision=2 where tenant_id='1' and fact_id='fact'");
        });
        assertEquals(0.0, admin.queryForObject("select projected_decay_score from agent_runtime_memory_usage_projection", Double.class));
        admin.update("update agent_runtime_memory_usage_projection set projected_decay_score=0.7");
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id','1',true)", String.class);
            jdbc.update("update agent_runtime_memory_fact_heads set current_revision=2 where tenant_id='1' and fact_id='fact'");
        });
        assertEquals(0.7, admin.queryForObject("select projected_decay_score from agent_runtime_memory_usage_projection", Double.class));
    }

}
