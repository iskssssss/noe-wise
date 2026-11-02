package com.skillforge.core.knowledge.persistence;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.knowledge.KnowledgeReferenceService.Revision;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import java.util.Set;
import java.util.stream.Collectors;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 私有 PG 验证正式 XML/RLS；结果行 JSON 计量不代表扫描量、网络传输或延时。 */
@Testcontainers(disabledWithoutDocker = false)
class KnowledgeActiveReferenceProjectionPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static final Instant BASE = Instant.parse("2026-10-30T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResultEvidence EVIDENCE = new ResultEvidence();
    private static JdbcTemplate owner;
    private static JdbcTemplate reader;
    private static TransactionTemplate transactions;
    private static KnowledgeReferenceService references;
    private static final String BASE_COLUMNS = "id,tenant_id,name,description,status,active_revision_id,rebuild_job_id,control_revision,"
            + "embedding_model_config_id,embedding_model_revision,embedding_dimensions,chunk_size,chunk_overlap,indexing_mode,"
            + "retrieval_mode,top_k,score_threshold_enabled,score_threshold,vector_weight,rerank_enabled,created_at,updated_at";
    private static final String REVISION_COLUMNS = "id,tenant_id,kb_id,status,capability_version_id";

    @BeforeAll static void database() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE knowledge_reference_reader LOGIN PASSWORD 'synthetic-reference-reader' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE TABLE nw_knowledge_bases(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,name text,description text,status text,"
                + "active_revision_id bigint,rebuild_job_id bigint,control_revision bigint,embedding_model_config_id bigint,embedding_model_revision bigint,"
                + "embedding_dimensions integer,chunk_size integer,chunk_overlap integer,indexing_mode text,retrieval_mode text,top_k integer,"
                + "score_threshold_enabled boolean,score_threshold double precision,vector_weight double precision,rerank_enabled boolean,created_at timestamptz,updated_at timestamptz)");
        // 对应真实指针边界：active_revision_id 没有保证修订归属的外键。
        owner.execute("CREATE TABLE nw_knowledge_base_revisions(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,kb_id bigint NOT NULL,"
                + "status text,capability_version_id bigint,source_versions jsonb)");
        for (String table : List.of("nw_knowledge_bases", "nw_knowledge_base_revisions")) {
            owner.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            owner.execute("CREATE POLICY tenant_isolation ON " + table + " USING(tenant_id::text=current_setting('app.tenant_id',true))");
        }
        owner.execute("GRANT USAGE ON SCHEMA public TO knowledge_reference_reader");
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO knowledge_reference_reader");
        String large = "synthetic-knowledge-description-".repeat(2048);
        String sources = JSON.writeValueAsString(List.of("synthetic-source-version-".repeat(4096)));
        for (long i = 100; i < 140; i++) seed(i, 7, "active", i + 1000, i + 1000, "published", 10000 + i, large, sources);
        seed(1, 7, "active", 1001, 1001, "published", 9001, large, sources);
        seed(2, 7, "active", 1002, 1002, "published", 9002, large, sources);
        seed(3, 7, "active", 1003, 1003, "published", 9002, large, sources);
        seed(4, 7, "archived", 1004, 1004, "published", 9001, large, sources);
        seed(5, 7, "active", 1005, 1005, "ready", 9001, large, sources);
        seed(6, 7, "active", 1006, 1006, "published", 9999, large, sources);
        seed(7, 8, "active", 1007, 1007, "published", 9001, large, sources);
        seed(8, 7, "active", 1008, 1008, "published", 9005, large, sources);
        // KB9 错误指向 KB8 的真实修订；该指针不能遮蔽 KB8 的合法候选。
        base(9, 7, "active", 1008L, large);
        base(10, 7, "active", null, large);
        base(11, 7, "active", 999999L, large);
        // 非正数身份不属于宿主的合法候选。
        seed(-1, 7, "active", 1010, 1010, "published", 9001, large, sources);
        seed(12, 7, "active", -2, -2, "published", 9001, large, sources);
        // 已发布但不再活动的修订不能进入候选集。
        seed(13, 7, "active", 1013, 1013, "ready", 9001, large, sources);
        owner.update("INSERT INTO nw_knowledge_base_revisions VALUES(1014,7,13,'published',9001,?::jsonb)", sources);
        // 指针指向缺失或其他知识库的修订，没有合法所属知识库可以关联。
        base(14, 7, "active", 1015L, large);
        owner.update("INSERT INTO nw_knowledge_base_revisions VALUES(1015,7,99,'published',9001,?::jsonb)", sources);
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "knowledge_reference_reader", "synthetic-reference-reader");
        reader = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration(new Environment("knowledge-reference-private-pg", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor()); configuration.addInterceptor(EVIDENCE);
        String resource = "mapper/KnowledgeBaseRevisionMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(KnowledgeBaseRevisionMapper.class);
        references = new KnowledgeReferenceService(org.mockito.Mockito.mock(com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper.class), mapper);
    }

    @BeforeEach void tenant() { TenantContext.setTenantId("7"); EVIDENCE.clear(); }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void targetedFiveScalarJoinMatchesOldTwoReadCandidatesAndMeasuresActualResultMaterial() {
        var old = oldRead(7, Set.of(9001L));
        var actual = current(7, List.of(9001L));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(old.candidates())
                .containsExactly(new Revision(1001L, 7L, 1L, "published", 9001L));
        assertThat(old.bases().columns).isEqualTo(22); assertThat(old.revisions().columns).isEqualTo(5);
        assertThat(old.bases().queries + old.revisions().queries).isEqualTo(2);
        assertThat(EVIDENCE.material.queries).isEqualTo(1); assertThat(EVIDENCE.material.columns).isEqualTo(5);
        assertThat(EVIDENCE.material.names).containsExactly("id", "tenant_id", "kb_id", "status", "capability_version_id");
        assertThat(old.bases().rows).isGreaterThan(40); assertThat(old.revisions().rows).isGreaterThan(40);
        assertThat(EVIDENCE.material.rows).isEqualTo(1);
        assertThat(EVIDENCE.material.bytes).isLessThan(old.bases().bytes + old.revisions().bytes);
        // source_versions 虽然较大，但旧五字段修订查询和新投影均未读取，不能计入节省。
        System.out.printf("knowledge-reference same-input result-row-json: oldQueries=%d newQueries=%d oldBaseRows=%d oldRevisionRows=%d newRows=%d oldUtf8Bytes=%d newUtf8Bytes=%d%n",
                old.bases().queries + old.revisions().queries, EVIDENCE.material.queries, old.bases().rows, old.revisions().rows,
                EVIDENCE.material.rows, old.bases().bytes + old.revisions().bytes, EVIDENCE.material.bytes);
    }

    @Test void scopeStateAndMultiplicityRemainExactAndEmptyVersionsIssueNoQuery() {
        assertThat(current(7, List.of(9002L))).containsExactlyInAnyOrderElementsOf(oldRead(7, Set.of(9002L)).candidates())
                .containsExactlyInAnyOrder(new Revision(1002L, 7L, 2L, "published", 9002L), new Revision(1003L, 7L, 3L, "published", 9002L));
        // 两个真实知识库候选都须返回宿主，由宿主拒绝歧义能力候选。
        assertThat(current(7, List.of(123456L))).isEmpty();
        assertThat(current(7, List.of(9001L, 9002L))).hasSize(3);
        assertThat(current(7, List.of())).isEmpty();
        assertThat(EVIDENCE.material.queries).isZero();
    }

    @Test void badPointerInAnotherBaseCannotHideTheRevisionFromItsActualOwner() {
        // 按已确认的归属修正，不与旧无序 activeRevisionId→KB Map 的偶然覆盖结果比较。
        // 旧覆盖依赖行顺序；新 JOIN 明确要求 b.id=r.kb_id。
        assertThat(current(7, List.of(9005L))).containsExactly(new Revision(1008L, 7L, 8L, "published", 9005L));
    }

    @Test void selectOnlyNonBypassRoleEnforcesRlsAcrossBothTablesAndRejectsWrites() {
        assertThat(reader.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        for (String table : List.of("nw_knowledge_bases", "nw_knowledge_base_revisions")) {
            assertThat(reader.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid=?::regclass", Boolean.class, table)).isTrue();
        }
        assertThat(current(8, List.of(9001L))).isEmpty();
        assertThat(oldRead(8, Set.of(9001L)).candidates()).isEmpty();
        TenantContext.setTenantId("8");
        assertThat(current(8, List.of(9001L))).containsExactly(new Revision(1007L, 8L, 7L, "published", 9001L));
        assertThat(current(7, List.of(9001L))).isEmpty();
        assertThatThrownBy(() -> reader.update("UPDATE nw_knowledge_bases SET status='archived' WHERE id=7"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> reader.update("DELETE FROM nw_knowledge_base_revisions WHERE id=1007"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static List<Revision> current(long tenant, List<Long> versions) {
        EVIDENCE.clear(); return transactions.execute(status -> references.activePublishedForCapabilityVersions(tenant, versions));
    }

    /** 原知识域 SQL 先读取全部活动知识库（22 列），再读取已发布修订（5 列）。
     * 候选按真实归属对照，不把旧错误指针下 Map 的偶然覆盖作为预期。 */
    private static Baseline oldRead(long tenant, Set<Long> versions) {
        var baseMaterial = new Material(); var revisionMaterial = new Material();
        return transactions.execute(status -> {
            reader.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, TenantContext.getTenantId());
            List<BaseRow> bases = reader.query("SELECT " + BASE_COLUMNS + " FROM nw_knowledge_bases WHERE tenant_id=? AND status='active' AND active_revision_id IS NOT NULL",
                    (org.springframework.jdbc.core.ResultSetExtractor<List<BaseRow>>) rows -> {
                        baseMaterial.inspectColumns(rows); var result = new ArrayList<BaseRow>();
                        while (rows.next()) { baseMaterial.capture(rows); result.add(new BaseRow(rows.getLong("id"), (Long) rows.getObject("active_revision_id"))); }
                        return result;
                    }, tenant);
            List<Long> ids = bases.stream().map(BaseRow::activeRevisionId).filter(java.util.Objects::nonNull).distinct().toList();
            if (ids.isEmpty()) return new Baseline(List.of(), baseMaterial, revisionMaterial);
            var args = new ArrayList<Object>(); args.add(tenant); args.addAll(ids);
            List<Revision> revisions = reader.query("SELECT " + REVISION_COLUMNS + " FROM nw_knowledge_base_revisions WHERE tenant_id=? AND status='published' AND id IN ("
                            + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                    (org.springframework.jdbc.core.ResultSetExtractor<List<Revision>>) rows -> {
                        revisionMaterial.inspectColumns(rows); var result = new ArrayList<Revision>();
                        while (rows.next()) { revisionMaterial.capture(rows); result.add(new Revision(rows.getLong("id"), rows.getLong("tenant_id"), rows.getLong("kb_id"), rows.getString("status"), (Long) rows.getObject("capability_version_id"))); }
                        return result;
                    }, args.toArray());
            Set<Long> owned = bases.stream().filter(base -> base.id() > 0).map(BaseRow::id).collect(Collectors.toSet());
            var candidates = revisions.stream().filter(revision -> revision.id() > 0 && owned.contains(revision.kbId()) && versions.contains(revision.capabilityVersionId()))
                    .filter(revision -> bases.stream().anyMatch(base -> base.id() == revision.kbId() && revision.id().equals(base.activeRevisionId()))).toList();
            return new Baseline(candidates, baseMaterial, revisionMaterial);
        });
    }

    private record BaseRow(long id, Long activeRevisionId) {}
    private record Baseline(List<Revision> candidates, Material bases, Material revisions) {}

    private static void base(long id, long tenant, String status, Long active, String description) {
        owner.update("INSERT INTO nw_knowledge_bases(id,tenant_id,name,description,status,active_revision_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                id, tenant, "synthetic-kb-" + id, description, status, active, Timestamp.from(BASE), Timestamp.from(BASE));
    }
    private static void seed(long id, long tenant, String baseStatus, long active, long revisionId, String revisionStatus,
            long version, String description, String sources) {
        base(id, tenant, baseStatus, active, description);
        owner.update("INSERT INTO nw_knowledge_base_revisions VALUES(?,?,?,?,?,?::jsonb)", revisionId, tenant, id, revisionStatus, version, sources);
    }

    /** 两路径用同一编码器计量实际ResultSet各列值的row JSON，不是PG磁盘/网络或延迟估计。 */
    private static final class Material {
        int columns; int queries; long rows; long bytes; List<String> names = List.of();
        void inspectColumns(ResultSet source) throws java.sql.SQLException {
            queries++; var meta = source.getMetaData(); columns = meta.getColumnCount(); var labels = new ArrayList<String>();
            for (int i = 1; i <= columns; i++) labels.add(meta.getColumnLabel(i)); names = List.copyOf(labels);
        }
        void capture(ResultSet source) throws java.sql.SQLException {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                Object value = source.getObject(i);
                values.put(names.get(i - 1), value == null || value instanceof Number || value instanceof Boolean ? value : value.toString());
            }
            try { bytes += JSON.writeValueAsString(values).getBytes(StandardCharsets.UTF_8).length; }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new java.sql.SQLException("fixture row JSON encoding failed", failure); }
            rows++;
        }
    }

    /** 正式Mapper实际JDBC结果集在MyBatis映射前测量，列与行来自同一次查询。 */
    @Intercepts(@Signature(type = ResultSetHandler.class, method = "handleResultSets", args = Statement.class))
    private static final class ResultEvidence implements Interceptor {
        private Material material = new Material();
        void clear() { material = new Material(); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Statement actual = (Statement) invocation.getArgs()[0];
            invocation.getArgs()[0] = Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(actual, args);
                    if (!method.getName().equals("getResultSet") || result == null) return result;
                    var rows = (ResultSet) result; material.inspectColumns(rows);
                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (rowProxy, rowMethod, rowArgs) -> {
                        try {
                            Object value = rowMethod.invoke(rows, rowArgs);
                            if (rowMethod.getName().equals("next") && Boolean.TRUE.equals(value)) material.capture(rows);
                            return value;
                        } catch (InvocationTargetException error) { throw error.getCause(); }
                    });
                } catch (InvocationTargetException error) { throw error.getCause(); }
            });
            return invocation.proceed();
        }
    }
}
