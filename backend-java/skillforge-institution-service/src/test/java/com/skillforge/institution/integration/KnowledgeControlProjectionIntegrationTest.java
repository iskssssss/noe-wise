package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeRebuildJobDO;
import com.skillforge.core.shared.config.MapperExecutionLoggingInterceptor;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 验证正式显式投影的完整映射、受限角色 RLS 与行锁；不替代整条迁移链验收。 */
@Testcontainers(disabledWithoutDocker = false)
class KnowledgeControlProjectionIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeControlProjectionIntegrationTest.class);
    private static final Instant TIME = Instant.parse("2026-01-02T03:04:05Z");
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static SqlSessionFactory sessions;
    private static JdbcTemplate admin;
    private KnowledgeBaseDO base;
    private KnowledgeRebuildJobDO job;

    /** 从正式迁移提取本路径表和策略，构建受限应用工厂；资源缺失或 SQL 错误直接失败。 */
    @BeforeAll
    static void schema() throws Exception {
        LOG.debug("event=knowledge_projection_schema phase=enter");
        try {
            admin = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
            admin.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY)");
            admin.execute("CREATE TABLE nw_platform_model_versions(model_id bigint, revision bigint, PRIMARY KEY(model_id,revision))");
            admin.execute(statement("V1__baseline.sql", "CREATE TABLE public.nw_knowledge_bases ("));
            admin.execute("ALTER TABLE nw_knowledge_bases ADD PRIMARY KEY(id)");
            admin.execute(statement("V77__knowledge_base_revision_lifecycle.sql", "ALTER TABLE public.nw_knowledge_bases"));
            admin.execute(statement("V156__knowledge_embedding_model_pin.sql", "ALTER TABLE nw_knowledge_bases"));
            admin.execute(statement("V256__knowledge_embedding_rebuild.sql", "ALTER TABLE nw_knowledge_bases"));
            admin.execute(statement("V256__knowledge_embedding_rebuild.sql", "CREATE TABLE nw_knowledge_rebuild_jobs("));
            admin.execute(statement("V273__application_usage_export.sql", "ALTER TABLE public.nw_knowledge_rebuild_jobs"));
            admin.execute(statement("V273__application_usage_export.sql", "CREATE FUNCTION public.guard_knowledge_rebuild_metering_origin()"));
            admin.execute(statement("V273__application_usage_export.sql", "CREATE TRIGGER knowledge_rebuild_metering_origin_immutable "));
            admin.execute("ALTER TABLE nw_knowledge_bases ENABLE ROW LEVEL SECURITY");
            admin.execute("ALTER TABLE nw_knowledge_bases FORCE ROW LEVEL SECURITY");
            admin.execute(statement("V1__baseline.sql", "CREATE POLICY skillforge_knowledge_bases_tenant_isolation "));
            admin.execute(statement("V256__knowledge_embedding_rebuild.sql", "ALTER TABLE nw_knowledge_rebuild_jobs ENABLE"));
            admin.execute(statement("V256__knowledge_embedding_rebuild.sql", "ALTER TABLE nw_knowledge_rebuild_jobs FORCE"));
            admin.execute(statement("V256__knowledge_embedding_rebuild.sql", "CREATE POLICY skillforge_knowledge_rebuild_jobs_tenant "));
            admin.execute("CREATE ROLE knowledge_control_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
            admin.execute("GRANT SELECT,INSERT,UPDATE ON nw_knowledge_bases,nw_knowledge_rebuild_jobs TO knowledge_control_test");
            var data = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "knowledge_control_test", "test-only-password");
            assertFalse(new JdbcTemplate(data).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("knowledge-control", new JdbcTransactionFactory(), data));
            config.addInterceptor(new TenantRlsInterceptor());
            config.addInterceptor(new MapperExecutionLoggingInterceptor());
            config.addMapper(KnowledgeBaseMapper.class);
            for (String mapper : List.of("KnowledgeBaseRevisionMapper", "KnowledgeRebuildJobMapper")) {
                String path = "mapper/" + mapper + ".xml";
                try (var input = KnowledgeControlProjectionIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
                    assertNotNull(input);
                    new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
                }
            }
            sessions = new MybatisSqlSessionFactoryBuilder().build(config);
            assertEquals(1, config.getInterceptors().stream().filter(MapperExecutionLoggingInterceptor.class::isInstance).count());
            for (String name : List.of("KnowledgeBaseRevisionMapper.lockBase", "KnowledgeRebuildJobMapper.lock", "KnowledgeRebuildJobMapper.due")) {
                assertTrue(config.hasStatement("com.skillforge.core.knowledge.mapper." + name));
            }
            admin.execute("INSERT INTO nw_tenants VALUES(7),(8)");
            admin.execute("INSERT INTO nw_platform_model_versions VALUES(21,3)");
        } finally { LOG.debug("event=knowledge_projection_schema phase=exit"); }
    }

    /** 创建每例独立事实；所有字段非默认且时间精确到秒，避免漏列被默认值掩盖。 */
    @BeforeEach
    void seed() {
        LOG.debug("event=knowledge_projection_seed phase=enter");
        try {
            admin.execute("TRUNCATE nw_knowledge_rebuild_jobs,nw_knowledge_bases CASCADE");
            base = new KnowledgeBaseDO();
            base.setId(10L); base.setTenantId(7L); base.setName("projection"); base.setDescription("fixture");
            base.setStatus("active"); base.setActiveRevisionId(91L); base.setRebuildJobId(31L); base.setControlRevision(13L);
            base.setEmbeddingModelConfigId(21L); base.setEmbeddingModelRevision(3L); base.setEmbeddingDimensions(1536);
            base.setChunkSize(800); base.setChunkOverlap(120); base.setIndexingMode("high_quality"); base.setRetrievalMode("hybrid");
            base.setTopK(9); base.setScoreThresholdEnabled(true); base.setScoreThreshold(0.7); base.setVectorWeight(0.6);
            base.setRerankEnabled(true); base.setCreatedAt(TIME); base.setUpdatedAt(TIME.plusSeconds(1));
            job = new KnowledgeRebuildJobDO();
            job.setId(31L); job.setTenantId(7L); job.setKbId(10L); job.setIdempotencyKey("fixture-key"); job.setFingerprint("fixture-hash");
            job.setStatus("RUNNING"); job.setRevision(17L); job.setFence(23L); job.setLeaseUntil(TIME);
            job.setTargetModelId(22L); job.setTargetModelRevision(5L); job.setTargetDimensions(3072);
            job.setOriginalActiveRevisionId(91L); job.setOriginalModelId(21L); job.setOriginalModelRevision(3L);
            job.setErrorCode("fixture_error"); job.setPublishedRevision(19L); job.setCreatedAt(TIME); job.setUpdatedAt(TIME.plusSeconds(1));
            job.setMeteringRequestId("source-request-1"); job.setMeteringRootInputId("source-input-root-1");
            job.setMeteringSessionId("source-session-1"); job.setMeteringRunId("source-run-1");
            job.setMeteringScopeType("control_operation"); job.setMeteringScopeId("source-operation-1");
            job.setMeteringEndUserId(9007199254740993L); job.setMeteringAgentId(9007199254740994L);
            job.setMeteringChannelId("source-channel-1");
            TenantContext.setTenantId("7");
            try (var session = sessions.openSession(true)) {
                assertEquals(1, session.getMapper(KnowledgeBaseMapper.class).insert(base));
                assertEquals(1, session.getMapper(KnowledgeRebuildJobMapper.class).insert(job));
            }
        } finally { LOG.debug("event=knowledge_projection_seed phase=exit"); }
    }

    /** 无返回值；无论断言是否失败均清除线程身份，防止污染下一用例。 */
    @AfterEach
    void clearTenant() {
        LOG.debug("event=knowledge_projection_cleanup phase=enter");
        TenantContext.clear();
        LOG.debug("event=knowledge_projection_cleanup phase=exit");
    }

    /** 完整实体比较覆盖发布指针、模型、租约、fence、九项来源及时间；重开会话排除一级缓存伪装读回。 */
    @Test
    void mapsEveryControlAndRecoveryField() {
        LOG.debug("event=knowledge_projection_mapping phase=enter");
        try {
            try (var session = sessions.openSession(true)) {
                assertEquals(base, session.getMapper(KnowledgeBaseRevisionMapper.class).lockBase(7, 10));
                assertEquals(job, session.getMapper(KnowledgeRebuildJobMapper.class).lock(7, 31));
                assertEquals(List.of(job), session.getMapper(KnowledgeRebuildJobMapper.class).due(7));
            }
            // 插入和首轮读取均已关闭；重开正式 SqlSession 验证数据库中的完整冻结来源。
            try (var restarted = sessions.openSession(true)) {
                assertEquals(job, restarted.getMapper(KnowledgeRebuildJobMapper.class).lock(7, 31));
                assertEquals(List.of(job), restarted.getMapper(KnowledgeRebuildJobMapper.class).due(7));
                admin.update("UPDATE nw_knowledge_rebuild_jobs SET lease_until=now()+interval '1 day' WHERE id=31");
                restarted.clearCache();
                assertTrue(restarted.getMapper(KnowledgeRebuildJobMapper.class).due(7).isEmpty());
                admin.update("UPDATE nw_knowledge_rebuild_jobs SET status='QUEUED' WHERE id=31");
                restarted.clearCache();
                assertEquals(1, restarted.getMapper(KnowledgeRebuildJobMapper.class).due(7).size());
            }
        } finally { LOG.debug("event=knowledge_projection_mapping phase=exit"); }
    }

    /** 正式 V273 触发器保护九个来源字段，受限应用 Mapper 无法改写，失败后新会话仍读到原事实。 */
    @Test
    void frozenMeteringOriginCannotBeUpdatedThroughApplicationMapper() {
        LOG.debug("event=knowledge_projection_origin_immutable phase=enter");
        try {
            for (String column : List.of("metering_request_id", "metering_root_input_id", "metering_session_id",
                    "metering_run_id", "metering_scope_type", "metering_scope_id", "metering_end_user_id",
                    "metering_agent_id", "metering_channel_id")) {
                try (var session = sessions.openSession(true)) {
                    Object changed = column.equals("metering_end_user_id") || column.equals("metering_agent_id")
                            ? 9007199254740995L : "changed-origin";
                    var error = assertThrows(RuntimeException.class, () -> session.getMapper(KnowledgeRebuildJobMapper.class)
                            .update(null, new UpdateWrapper<KnowledgeRebuildJobDO>()
                                    .eq("tenant_id", 7L).eq("id", 31L).set(column, changed)));
                    Throwable cause = error;
                    while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
                    assertEquals("P0001", assertInstanceOf(SQLException.class, cause).getSQLState());
                }
                try (var restarted = sessions.openSession(true)) {
                    assertEquals(job, restarted.getMapper(KnowledgeRebuildJobMapper.class).lock(7, 31));
                }
            }
        } finally { LOG.debug("event=knowledge_projection_origin_immutable phase=exit"); }
    }

    /** SQL 参数和线程身份分别错配都必须不可见；缺失身份也不能借用先前连接身份。 */
    @Test
    void rejectsForeignAndMissingTenantAcrossAllQueries() {
        LOG.debug("event=knowledge_projection_isolation phase=enter");
        try {
            for (String identity : new String[]{"7", "8", null}) {
                if (identity == null) TenantContext.clear(); else TenantContext.setTenantId(identity);
                long requestedTenant = "7".equals(identity) ? 8 : 7;
                try (var session = sessions.openSession(true)) {
                    assertNull(session.getMapper(KnowledgeBaseRevisionMapper.class).lockBase(requestedTenant, 10));
                    assertNull(session.getMapper(KnowledgeRebuildJobMapper.class).lock(requestedTenant, 31));
                    assertTrue(session.getMapper(KnowledgeRebuildJobMapper.class).due(requestedTenant).isEmpty());
                }
            }
        } finally { LOG.debug("event=knowledge_projection_isolation phase=exit"); }
    }

    /** 两个独立事务竞争同一行；SQLSTATE 55P03 证明实际锁冲突，释放后查询必须恢复成功。 */
    @Test
    void bothLockQueriesHoldRowLockUntilTransactionEnds() throws Exception {
        LOG.debug("event=knowledge_projection_lock phase=enter");
        try {
            for (boolean lockBase : new boolean[]{true, false}) {
                try (var owner = sessions.openSession(false); var contender = sessions.openSession(false)) {
                    if (lockBase) assertNotNull(owner.getMapper(KnowledgeBaseRevisionMapper.class).lockBase(7, 10));
                    else assertNotNull(owner.getMapper(KnowledgeRebuildJobMapper.class).lock(7, 31));
                    try (var command = contender.getConnection().createStatement()) { command.execute("SET LOCAL lock_timeout='250ms'"); }
                    var error = assertThrows(RuntimeException.class, () -> {
                        if (lockBase) contender.getMapper(KnowledgeBaseRevisionMapper.class).lockBase(7, 10);
                        else contender.getMapper(KnowledgeRebuildJobMapper.class).lock(7, 31);
                    });
                    Throwable cause = error;
                    while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
                    assertEquals("55P03", assertInstanceOf(SQLException.class, cause).getSQLState());
                    // 纯 SELECT 不设置 MyBatis dirty 标志，必须强制结束真实数据库事务及其行锁。
                    contender.rollback(true); owner.rollback(true);
                    if (lockBase) assertEquals(base, contender.getMapper(KnowledgeBaseRevisionMapper.class).lockBase(7, 10));
                    else assertEquals(job, contender.getMapper(KnowledgeRebuildJobMapper.class).lock(7, 31));
                    contender.rollback(true);
                }
            }
        } finally { LOG.debug("event=knowledge_projection_lock phase=exit"); }
    }

    /** 提取唯一前缀定位的正式 DDL；函数保留完整 dollar quote 正文，禁止在函数内部的分号截断。
     * @param file 迁移资源名
     * @param prefix SQL 起始标识
     * @return 含结束分号的正式 SQL
     * @throws Exception 资源读取失败
     */
    private static String statement(String file, String prefix) throws Exception {
        LOG.debug("event=knowledge_projection_ddl phase=enter resource={}", file);
        try (var input = KnowledgeControlProjectionIntegrationTest.class.getClassLoader().getResourceAsStream("db/migration/" + file)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf(prefix);
            assertTrue(start >= 0, prefix);
            int bodyEnd = start;
            if (prefix.startsWith("CREATE FUNCTION")) {
                int opening = sql.indexOf("$$", start);
                assertTrue(opening > start, "函数必须包含正式 dollar quote 起点");
                bodyEnd = sql.indexOf("$$", opening + 2);
                assertTrue(bodyEnd > opening, "函数必须包含完整 dollar quote 终点");
                bodyEnd += 2;
            }
            int end = sql.indexOf(';', bodyEnd);
            assertTrue(end > start, "正式 DDL 必须包含结束分号");
            return sql.substring(start, end + 1);
        } finally { LOG.debug("event=knowledge_projection_ddl phase=exit resource={}", file); }
    }
}
