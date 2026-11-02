package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 同控存储度量；同一文件可在优化前副本和候选副本分别运行，不依赖新生产Java类型。
 * 只在本类隔离Testcontainers数据库安装行级探针，统计真实受影响行和新增/变化载荷字节。
 * 不将供应商Mock、SQL调用数或载荷字节当作生产容量/真实模型证据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.enabled=false")
class KnowledgeStorageGrowthIntegrationTest extends AbstractIntegrationTest {
    private static final boolean EXTERNAL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"));
    private static final String CI_ROLE="knowledge_growth_test";
    private static final String CI_PASSWORD="knowledge_growth_test_password";
    @Autowired private KnowledgeService knowledge;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmbeddingProvider embedding;
    @MockitoBean private SearchIndexer index;

    /** 每个操作显式绑定所属租户，结束不将授权留在线程中。 */
    @AfterEach void cleanup() { TenantContext.clear(); }

    /** 标题和单块编辑用相同三块输入及确定性向量，在两份schema中产生可对照实际行数。 */
    @Test void controlledTitleAndSingleChunkEditWritesProduceMeasuredCounts() throws Exception {
        ensureFreePlan(plans);
        String tenant=createTenant(tenants,plans,"knowledge-storage-measure-"+SnowflakeIds.nextId());
        float[] vector=new float[2];vector[0]=1;
        var model=models.create(new ModelWrite(null,"知识存储度量模型","EMBEDDING",true,
                "openai",null,null,null,"bearer-api-key","https://embedding.example/v1","storage-test",
                "test-secret",null,null,null,null,null,30,2,null,null,null,null,"CNY"),"knowledge-storage-test");
        models.publish(model.id(),model.revision(),"knowledge-storage-test");
        when(embedding.config(anyString())).thenReturn(models.readRevision(model.id(),model.revision()));
        when(embedding.embed(anyString(),anyString(),anyLong(),anyString())).thenReturn(vector);
        when(embedding.embedBatch(anyString(),anyString(),anyLong(),anyList())).thenAnswer(call -> {
            List<String> texts=call.getArgument(3);return texts.stream().map(text->vector).toList();
        });
        TenantContext.setTenantId(tenant);
        var base=knowledge.createBase(tenant,"同控知识存储",null);
        knowledge.updateBase(tenant,base.id(),null,null,null,
                new KnowledgeService.KnowledgeBaseSettingsUpdate(20,0,null,null,null,null,null,null,null));
        var initial=knowledge.uploadFile(tenant,base.id(),"初始标题",new MockMultipartFile("file","controlled.txt","text/plain",
                "alpha body first\n\nbeta body second\n\ngamma body third".getBytes(StandardCharsets.UTF_8)));
        var originalChunks=knowledge.listChunks(tenant,initial.documentId(),1,200).items();
        assertTrue(originalChunks.size()>1);
        boolean references=jdbc.queryForObject("SELECT to_regclass('public.nw_knowledge_chunk_contents') IS NOT NULL",Boolean.class);
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)));
        if(EXTERNAL)assertTrue(references,"S03 growth requires the full current reference schema");
        try(var probe=new RowProbe(probeAdmin(),tenant,references)) {
            var before=counts(tenant,references);
            var titled=knowledge.updateDocument(tenant,initial.documentId(),"仅变标题",null,initial.sourceVersion());
            var after=counts(tenant,references);
            assertEquals(before.versions()+1,after.versions());
            if(references) {
                assertEquals(before.documentBodies(),after.documentBodies());
                assertEquals(before.chunkBodies(),after.chunkBodies());
                assertEquals(before.embeddings(),after.embeddings());
                assertEquals(before.sets(),after.sets());
                assertEquals(before.relationships(),after.relationships());
                assertEquals(before.payloadBytes(),after.payloadBytes());
                assertEquals(0,probe.changedPayloadBytes());
            } else {
                assertEquals(before.chunkBodies()+originalChunks.size(),after.chunkBodies());
                assertTrue(after.payloadBytes()>before.payloadBytes());
            }
            emit(references,"title",before,after,probe.rows());
            probe.reset();
            var selected=knowledge.listChunks(tenant,initial.documentId(),1,200).items().getFirst();
            before=after;
            var changed=knowledge.updateChunk(tenant,selected.id(),"changed first body",titled.sourceVersion());
            after=counts(tenant,references);
            if(references) {
                assertEquals(before.documentBodies()+1,after.documentBodies());
                assertEquals(before.chunkBodies()+1,after.chunkBodies());
                assertEquals(before.embeddings()+1,after.embeddings());
                assertEquals(before.relationships()+originalChunks.size(),after.relationships());
            } else assertEquals(before.chunkBodies()+originalChunks.size(),after.chunkBodies());
            String values=references?"knowledge_chunk_values":"knowledge_chunks";
            assertEquals(originalChunks.size(),queryCount(jdbc,tenant,
                    "SELECT count(*) FROM "+values+" WHERE tenant_id=? AND source_version=?",Long.parseLong(tenant),initial.sourceVersion()));
            emit(references,"single_chunk_edit",before,after,probe.rows());
            probe.reset();
            knowledge.updateChunk(tenant,changed.id(),changed.content(),changed.sourceVersion());
            if(references) assertTrue(probe.rows().isEmpty(),"相同分块正文不能新增版本或UPDATE");
        }
    }

    /** 快照统计实际保留行和逻辑载荷，所有查询绑定同一测试租户。 */
    private Counts counts(String tenant,boolean references) {
        long id=Long.parseLong(tenant);
        long versions=queryCount(jdbc,tenant,"SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id=?",id);
        long relationships=queryCount(jdbc,tenant,"SELECT count(*) FROM nw_knowledge_chunks WHERE tenant_id=?",id);
        String docs=references?"knowledge_document_contents":"knowledge_document_versions";
        String chunks=references?"knowledge_chunk_contents":"knowledge_chunks";
        String vectors=references?"knowledge_chunk_embeddings":"knowledge_chunks";
        long documentBodies=queryCount(jdbc,tenant,"SELECT count(*) FROM "+docs+" WHERE tenant_id=?",id);
        long chunkBodies=queryCount(jdbc,tenant,"SELECT count(*) FROM "+chunks+" WHERE tenant_id=?",id);
        long embeddings=queryCount(jdbc,tenant,"SELECT count(*) FROM "+vectors+" WHERE tenant_id=? AND embedding IS NOT NULL",id);
        long payload=queryCount(jdbc,tenant,"SELECT coalesce(sum(octet_length(content)),0)::bigint FROM "+docs+" WHERE tenant_id=?",id)
                +queryCount(jdbc,tenant,"SELECT coalesce(sum(octet_length(content)),0)::bigint FROM "+chunks+" WHERE tenant_id=?",id)
                +queryCount(jdbc,tenant,"SELECT coalesce(sum(vector_dims(embedding)::bigint*4),0)::bigint FROM "+vectors+" WHERE tenant_id=?",id);
        long sets=references?queryCount(jdbc,tenant,"SELECT count(*) FROM nw_knowledge_chunk_sets WHERE tenant_id=?",id):0;
        return new Counts(versions,sets,relationships,documentBodies,chunkBodies,embeddings,payload);
    }

    /** 输出原始测量行供协调报告复用；没有执行本测试时不得填入结果。 */
    private static void emit(boolean references,String operation,Counts before,Counts after,List<Map<String,Object>> writes) throws Exception {
        System.out.println("KNOWLEDGE_STORAGE_MEASURE "+new ObjectMapper().writeValueAsString(Map.of(
                "schema",references?"references":"copies","operation",operation,"before",before,"after",after,"writes",writes)));
    }

    /** 每种持久事实独立计数，避免把夹具、业务行和引用关系混在一个分母。 */
    private record Counts(long versions,long sets,long relationships,long documentBodies,long chunkBodies,long embeddings,long payloadBytes) {}

    /** 隔离数据库行级探针；对象名来自验证后的测试租户，生命周期限于本用例。 */
    private static final class RowProbe implements AutoCloseable {
        private final JdbcTemplate jdbc;
        private final String name;
        private final List<String> tables=new ArrayList<>(List.of("knowledge_document_versions","knowledge_chunks","knowledge_documents"));
        /** 记录每个INSERT/UPDATE实际影响行；UPDATE只统计真正变化的正文或向量载荷。 */
        private RowProbe(JdbcTemplate jdbc,String tenant,boolean references) {
            if(!tenant.matches("[1-9][0-9]*"))throw new IllegalArgumentException("invalid test tenant");
            this.jdbc=jdbc;this.name="knowledge_measure_"+tenant;
            if(references)tables.addAll(List.of("knowledge_document_contents","knowledge_chunk_contents","knowledge_chunk_embeddings","knowledge_chunk_sets"));
            jdbc.execute("CREATE TABLE "+name+"(table_name text,operation text,payload_bytes bigint)");
            jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$ DECLARE body jsonb; previous jsonb; bytes bigint=0; BEGIN "
                    +"body=to_jsonb(NEW); previous=CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END; "
                    +"IF body->>'tenant_id'='"+tenant+"' THEN "
                    +"IF body->'content' IS DISTINCT FROM previous->'content' THEN bytes=coalesce(octet_length(body->>'content'),0); END IF; "
                    +"IF body->>'embedding' IS NOT NULL AND body->'embedding' IS DISTINCT FROM previous->'embedding' THEN bytes=bytes+vector_dims((body->>'embedding')::vector)::bigint*4; END IF; "
                    +"INSERT INTO "+name+" VALUES(TG_TABLE_NAME,TG_OP,bytes); END IF; RETURN NEW; END $$");
            for(String table:tables)jdbc.execute("CREATE TRIGGER "+name+" AFTER INSERT OR UPDATE ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        }
        /** 返回每表、每种操作的真实受影响行数和变化载荷。 */
        private List<Map<String,Object>> rows() {
            return jdbc.queryForList("SELECT table_name,operation,count(*) AS affected_rows,sum(payload_bytes) AS changed_payload_bytes FROM "+name+" GROUP BY table_name,operation ORDER BY table_name,operation");
        }
        /** 原始变化载荷总数，不含表/索引/TOAST的数据库物理开销。 */
        private long changedPayloadBytes() { return jdbc.queryForObject("SELECT coalesce(sum(payload_bytes),0)::bigint FROM "+name,Long.class); }
        /** 清除已导出的测量行，在相同环境测量下一个受控操作。 */
        private void reset() { jdbc.execute("TRUNCATE "+name); }
        /** 只释放本用例精确探针对象，不影响被测业务记录。 */
        @Override public void close() {
            for(String table:tables)jdbc.execute("DROP TRIGGER "+name+" ON "+table);
            jdbc.execute("DROP FUNCTION "+name+"()");jdbc.execute("DROP TABLE "+name);
        }
    }

    /** 只管理新fixture/probe；服务、Mapper及Spring事务使用独立受限业务连接。 */
    private static JdbcTemplate probeAdmin() {
        var admin=new JdbcTemplate(new DriverManagerDataSource(EXTERNAL?external("JDBC_URL"):POSTGRES.getJdbcUrl(),
                EXTERNAL?external("ADMIN_USER"):POSTGRES.getUsername(),EXTERNAL?external("ADMIN_PASSWORD"):POSTGRES.getPassword()));
        if(EXTERNAL){
            assertEquals("external",external("MODE"));assertEquals("knowledge_growth",external("FIXTURE_LANE"));assertEquals("286",external("SCHEMA_VERSION"));
            assertTrue(external("ADMIN_USER").matches("s03_migrator_[a-z0-9_]+"));
            String database=admin.queryForObject("SELECT current_database()",String.class);
            var uri=java.net.URI.create(external("JDBC_URL").replaceFirst("^jdbc:",""));
            assertEquals("postgresql",uri.getScheme());assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPort()>0&&uri.getUserInfo()==null&&uri.getRawQuery()==null);
            assertNotNull(database);assertTrue(database.matches("sf_s03_knowledge_growth_[a-z0-9_]+"));assertEquals("/"+database,uri.getPath());
            assertEquals(1,admin.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane='knowledge_growth' AND token=? AND schema_version=?",Integer.class,external("FIXTURE_TOKEN"),external("SCHEMA_VERSION")));
            assertEquals(185,admin.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success",Integer.class));
            assertEquals(0,admin.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE NOT success",Integer.class));
        }
        return admin;
    }
    private static String external(String suffix){String key="SKILLFORGE_S03_EXTERNAL_"+suffix,value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("knowledge_growth_missing:"+key);return value;}

    /** CI完整Flyway在管理连接执行；显式业务Bean避免公共基类CI管理员成为被测写入者。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class BusinessConnection {
        @Bean DataSource dataSource() {
            if(!EXTERNAL){
                Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
                var admin=probeAdmin();
                admin.execute("CREATE ROLE "+CI_ROLE+" LOGIN PASSWORD '"+CI_PASSWORD+"' NOSUPERUSER NOBYPASSRLS");
                admin.execute("GRANT USAGE ON SCHEMA public TO "+CI_ROLE);
                admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+CI_ROLE);
                admin.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+CI_ROLE);
                admin.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO "+CI_ROLE);
            } else { probeAdmin(); assertTrue(external("DB_USER").matches("s03_business_[a-z0-9_]+")); }
            return new DriverManagerDataSource(EXTERNAL?external("JDBC_URL"):POSTGRES.getJdbcUrl(),EXTERNAL?external("DB_USER"):CI_ROLE,EXTERNAL?external("DB_PASSWORD"):CI_PASSWORD);
        }
    }
}
