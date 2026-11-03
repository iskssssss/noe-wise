package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.*;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.knowledge.mapper.*;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.knowledge.search.SearchIndexer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 真实 pgvector/Mapper/RLS 验证关键词输出；禁止读取向量列作为投影的负控。 */
class KnowledgeKeywordProjectionIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withUsername("skillforge");
    private static final boolean EXTERNAL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"));
    private static final String CI_ROLE="knowledge_projection_test";
    private static final String CI_PASSWORD="test-only-password";
    private static JdbcTemplate admin;
    private static KnowledgeChunkMapper chunks;
    private KnowledgeService service;
    private EmbeddingProvider embeddings;
    private RerankProvider rerank;

    @BeforeAll
    static void schema() throws Exception {
        if(EXTERNAL){assertEquals("external",external("MODE"));}
        else {
            Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                    || "true".equalsIgnoreCase(System.getenv("KNOWLEDGE_KEYWORD_ACCEPTANCE")),
                    "需要 CI、显式隔离关键词验收或父进程提供的外部夹具");
            DATABASE.start();
            Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()).locations("classpath:db/migration").load().migrate();
        }
        admin=new JdbcTemplate(source(false));
        verifyFixture(admin,false);
        if(!EXTERNAL){
            admin.execute("CREATE ROLE "+CI_ROLE+" LOGIN PASSWORD '"+CI_PASSWORD+"' NOSUPERUSER NOBYPASSRLS");
            admin.execute("GRANT USAGE ON SCHEMA public TO "+businessRole());
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO "+businessRole());
        }
        var application=source(true);verifyFixture(new JdbcTemplate(application),true);
        // 完整实体SELECT必须因view列权限拒绝；底层真实所有者表仍保留security_invoker所需SELECT。
        admin.execute("REVOKE SELECT ON public.knowledge_chunk_values FROM "+businessRole());
        admin.execute("GRANT SELECT(id,tenant_id,kb_id,document_id,content,chunk_index,source_version) ON public.knowledge_chunk_values TO "+businessRole());
        admin.execute("INSERT INTO nw_tenants(id,name,slug,plan_id) SELECT id,'Keyword fixture','keyword-fixture-'||id,(SELECT id FROM nw_billing_plans WHERE plan_code='free') FROM (VALUES(7::bigint),(8::bigint)) x(id)");
        admin.execute("INSERT INTO nw_platform_models(id,current_revision,draft) VALUES(901,NULL,'{}')");
        admin.execute("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(901,1,'{}','keyword-synthetic-fixture')");
        admin.execute("UPDATE nw_platform_models SET current_revision=1 WHERE id=901");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("knowledge-projection-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(KnowledgeChunkMapper.class);
        String mapperPath = "mapper/KnowledgeChunkMapper.xml";
        try (var input = KnowledgeKeywordProjectionIntegrationTest.class.getClassLoader().getResourceAsStream(mapperPath)) {
            assertNotNull(input);
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, mapperPath,
                    configuration.getSqlFragments()).parse();
        }
        // 来源版本 Mapper 仍由 fixture 提供，仅注册其 Lambda 元数据供参数边界解析。
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                configuration, "keyword-source-model-fixture"), KnowledgeDocumentVersionDO.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        chunks = sessions.getMapper(KnowledgeChunkMapper.class);
    }

    @BeforeEach
    void seed() {
        admin.execute("TRUNCATE nw_knowledge_chunks,nw_knowledge_document_versions,nw_knowledge_chunk_embeddings,nw_knowledge_chunk_contents,nw_knowledge_document_contents,nw_knowledge_chunk_sets,nw_knowledge_documents,nw_knowledge_bases CASCADE");
        admin.execute("INSERT INTO nw_knowledge_bases(id,tenant_id,name,embedding_model_config_id,embedding_model_revision,embedding_dimensions) VALUES(10,7,'Primary',901,1,2),(11,7,'Other base',901,1,2),(80,8,'Foreign',901,1,2)");
        insertChunk(31, 7, 10, 20, "v1", 2, "needle third");
        insertChunk(32, 7, 10, 20, "v1", 0, "needle first");
        insertChunk(33, 7, 10, 20, "v1", 1, "needle second");
        insertChunk(34, 7, 10, 20, "v2", 0, "needle other version");
        insertChunk(35, 8, 80, 80, "v1", 0, "needle foreign tenant");
        insertChunk(36, 7, 11, 21, "v1", 0, "needle other base");
        insertChunk(37, 7, 10, 20, "v1", 3, "literal %_\\ needle");
        insertChunk(38, 7, 10, 20, "v1", 4, "MixedCasePolicy");
        admin.execute("UPDATE nw_knowledge_document_versions v SET chunk_count=(SELECT count(*) FROM nw_knowledge_chunks c WHERE c.tenant_id=v.tenant_id AND c.chunk_set_id=v.chunk_set_id),status='ready'");
        admin.execute("UPDATE nw_knowledge_documents d SET current_source_version=(SELECT source_version FROM nw_knowledge_document_versions v WHERE v.tenant_id=d.tenant_id AND v.document_id=d.id ORDER BY v.source_version DESC LIMIT 1)");
        var base = new KnowledgeBaseDO();
        base.setId(10L); base.setTenantId(7L); base.setStatus("active");
        base.setIndexingMode("economical"); base.setRetrievalMode("full_text");
        var bases = mock(KnowledgeBaseMapper.class);
        when(bases.selectOne(any())).thenReturn(base);
        var settings = mock(KnowledgeOperationalSettings.class);
        when(settings.capture()).thenReturn(new KnowledgeOperationalSettings.Snapshot(
                1, 500, 50, 10, 2000, "high_quality", "semantic", 5, .5, .5));
        var versions = mock(KnowledgeDocumentVersionMapper.class);
        when(versions.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<KnowledgeDocumentVersionDO> requested = call.getArgument(0);
            requested.getSqlSegment();
            var values = requested.getParamNameValuePairs().values();
            long tenant = values.contains(7L) ? 7L : values.contains(8L) ? 8L : -1L;
            if (tenant < 0 || !values.contains(tenant==7L?10L:80L) || !values.contains("ready") || !values.contains("v1"))
                return List.of();
            return List.of(readySourceVersion(tenant));
        });
        embeddings = mock(EmbeddingProvider.class);
        rerank = mock(RerankProvider.class);
        service = new KnowledgeService(bases, mock(KnowledgeBaseRevisionMapper.class),
                mock(KnowledgeDocumentMapper.class), versions, chunks,
                embeddings, rerank, mock(SearchIndexer.class), settings, mock(KnowledgeStorageService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        TenantContext.setTenantId("7");
    }

    @AfterEach
    void clearTenant() {
        try { verifyNoInteractions(embeddings, rerank); }
        finally { TenantContext.clear(); }
    }

    @Test
    void keywordProjectionReadsNoVectorAndKeepsTenantBaseVersionOrderAndLimit() {
        assertThrows(RuntimeException.class, () -> chunks.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getTenantId, 7L)));

        var result = search("7", "needle", 2);

        assertEquals(List.of(hit(32, 20, "needle first"), hit(33, 20, "needle second")), result);
        assertTrue(search("8", "needle", 2).isEmpty());
        TenantContext.setTenantId("8");
        assertTrue(search("7", "needle", 2).isEmpty());
        assertEquals(List.of(hit(35, 80, "needle foreign tenant")), search("8", "needle", 2));
        TenantContext.clear();
        assertTrue(search("7", "needle", 2).isEmpty());
    }

    @Test
    void keywordFallbackTreatsWildcardsAndBackslashesAsLiteralText() {
        assertEquals(List.of(hit(37, 20, "literal %_\\ needle")), search("7", "%_\\", 10));
        for (String literal : List.of("%", "_", "\\")) {
            assertEquals(List.of(hit(37, 20, "literal %_\\ needle")), search("7", literal, 10));
        }
    }

    @Test
    void keywordFallbackMatchesMixedCaseWithoutChangingSourceIdentity() {
        var expected = List.of(hit(38, 20, "MixedCasePolicy"));
        assertEquals(expected, search("7", "mixedcasepolicy", 10));
        assertEquals(expected, search("7", "MIXEDCASEPOLICY", 10));
        assertEquals(expected, search("7", "CasePol", 10));
    }

    private List<com.skillforge.core.knowledge.KnowledgeHit> search(String tenant, String query, int limit) {
        String kb="8".equals(tenant)?"80":"10";
        return service.search(tenant, kb, query, limit,
                new KnowledgeSearchRequest(kb, query, limit, "full_text", false, null,
                        null, false, "v1"));
    }

    /** 来源模型解析仍执行当前契约；仅关键词投影使用真实数据库与受限角色。 */
    private static KnowledgeDocumentVersionDO readySourceVersion(long tenant) {
        var source = new KnowledgeDocumentVersionDO();
        source.setId(tenant * 100 + 1); source.setTenantId(tenant); source.setKbId(tenant==7L?10L:80L);
        source.setDocumentId(tenant == 7L ? 20L : 80L); source.setSourceVersion("v1");
        source.setStatus("ready"); source.setChunkCount(tenant == 7L ? 5 : 1);
        source.setEmbeddingModelConfigId(901L); source.setEmbeddingModelRevision(1L);
        source.setEmbeddingDimensions(2);
        return source;
    }

    private static com.skillforge.core.knowledge.KnowledgeHit hit(long chunk, long document, String content) {
        return new com.skillforge.core.knowledge.KnowledgeHit(document==80L?"80":"10", String.valueOf(document), String.valueOf(chunk), "v1", content, 1.0, null);
    }

    private static void insertChunk(long id, long tenant, long kb, long document,
            String version, int index, String content) {
        long set=tenant*100000+kb*1000+document*10+("v1".equals(version)?1:2);
        admin.update("INSERT INTO nw_knowledge_documents(id,tenant_id,kb_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",document,tenant,kb);
        admin.update("INSERT INTO nw_knowledge_document_contents(id,tenant_id,document_id,format,digest,content) VALUES(?,?,?,'text/plain; charset=utf-8',encode(sha256(convert_to(?,'UTF8')),'hex'),?) ON CONFLICT DO NOTHING",set,tenant,document,version,version);
        admin.update("INSERT INTO nw_knowledge_chunk_sets(id,tenant_id,kb_id,document_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",set,tenant,kb,document);
        admin.update("INSERT INTO nw_knowledge_document_versions(id,tenant_id,kb_id,document_id,source_version,title,source_type,content_id,chunk_set_id,status,embedding_model_config_id,embedding_model_revision,embedding_dimensions) VALUES(?,?,?,?,?,'fixture','file',?,?,'processing',901,1,2) ON CONFLICT DO NOTHING",set,tenant,kb,document,version,set,set);
        admin.update("INSERT INTO nw_knowledge_chunk_contents(id,tenant_id,document_id,format,digest,content) VALUES(?,?,?,'text/plain; charset=utf-8',encode(sha256(convert_to(?,'UTF8')),'hex'),?)",id,tenant,document,content,content);
        admin.update("INSERT INTO nw_knowledge_chunk_embeddings(id,tenant_id,content_id,model_id,model_revision,dimensions,digest,embedding) VALUES(?,?,?,901,1,2,encode(sha256(convert_to('[0.1,0.2]','UTF8')),'hex'),'[0.1,0.2]'::vector)",id,tenant,id);
        admin.update("INSERT INTO nw_knowledge_chunks(id,tenant_id,kb_id,document_id,chunk_set_id,chunk_index,content_id,embedding_id) VALUES(?,?,?,?,?,?,?,?)",id,tenant,kb,document,set,index,id,id);
    }

    @AfterAll static void release(){if(!EXTERNAL&&DATABASE.isRunning())DATABASE.stop();}
    private static String external(String suffix){String key="SKILLFORGE_S03_EXTERNAL_"+suffix,value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("knowledge_keyword_missing:"+key);return value;}
    private static String businessRole(){String role=EXTERNAL?external("DB_USER"):CI_ROLE;assertTrue(role.matches(EXTERNAL?"s03_business_[a-z0-9_]+":"[a-z_]+"));return role;}
    private static DataSource source(boolean business){return new DriverManagerDataSource(EXTERNAL?external("JDBC_URL"):DATABASE.getJdbcUrl(),EXTERNAL?external(business?"DB_USER":"ADMIN_USER"):business?CI_ROLE:DATABASE.getUsername(),EXTERNAL?external(business?"DB_PASSWORD":"ADMIN_PASSWORD"):business?CI_PASSWORD:DATABASE.getPassword());}
    private static void verifyFixture(JdbcTemplate jdbc,boolean business){
        if(business)assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)));
        if(!EXTERNAL)return;
        assertEquals("knowledge_keyword",external("FIXTURE_LANE"));assertEquals("286",external("SCHEMA_VERSION"));
        assertTrue(external("ADMIN_USER").matches("s03_migrator_[a-z0-9_]+"));
        var uri=java.net.URI.create(external("JDBC_URL").replaceFirst("^jdbc:",""));String database=jdbc.queryForObject("SELECT current_database()",String.class);
        assertEquals("postgresql",uri.getScheme());assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPort()>0&&uri.getUserInfo()==null&&uri.getRawQuery()==null);
        assertNotNull(database);assertTrue(database.matches("sf_s03_knowledge_keyword_[a-z0-9_]+"));assertEquals("/"+database,uri.getPath());
        assertEquals(external(business?"DB_USER":"ADMIN_USER"),jdbc.queryForObject("SELECT current_user",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane='knowledge_keyword' AND token=? AND schema_version=?",Integer.class,external("FIXTURE_TOKEN"),external("SCHEMA_VERSION")));
        assertEquals(185,jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE NOT success",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='286' AND success",Integer.class));
        if(business)assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.s03_host_fixture_guard','INSERT,UPDATE,DELETE')",Boolean.class)));
    }
}
