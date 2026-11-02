package com.skillforge.core.foundation.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.operations.OperationsContracts.AuditQuery;
import com.skillforge.core.foundation.operations.persistence.AuditExportRow;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实导出 SQL 与受限角色；仅最小审计读取表夹具，不冒称全量迁移或 HTTP 验收。 */
@Testcontainers(disabledWithoutDocker = false)
class AuditExportProjectionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("audit_export_test");
    private static SqlSessionFactory factory;

    @BeforeAll static void initialize() throws Exception {
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE nw_audit_logs(id bigint PRIMARY KEY,tenant_id bigint,actor_id text,action text,category text,outcome text,resource_type text,resource_id text,message text,trace_id text,payload jsonb,created_at timestamptz)");
            sql.execute("INSERT INTO nw_audit_logs SELECT n,7,'42','auth.login','authentication','failure','account','r','说明','trace',"
                    + "(SELECT jsonb_object_agg('field-'||i,repeat('x',1024)) FROM generate_series(1,128) i),'2026-10-29T12:00:00Z' FROM generate_series(1,2) n");
            sql.execute("INSERT INTO nw_audit_logs SELECT 3,7,'42','different','authentication','failure','account','r','排除动作','trace','{}'::jsonb,'2026-10-29T12:00:00Z'::timestamptz UNION ALL "
                    + "SELECT 4,8,'42','auth.login','authentication','failure','account','r','其他机构','trace','{}'::jsonb,'2026-10-29T12:00:00Z'::timestamptz UNION ALL "
                    + "SELECT 5,NULL,'42','auth.login','authentication','failure','account','r','平台','trace','{}'::jsonb,'2026-10-29T12:00:00Z'::timestamptz");
            // V280 的读取批次表定义；本类仍为最小表夹具，不声明全量迁移。
            sql.execute("""
CREATE TABLE public.nw_foundation_audit_read_batches (
    audit_id bigint PRIMARY KEY REFERENCES public.nw_audit_logs(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    scope varchar(32) NOT NULL CHECK (scope = 'platform' OR scope ~ '^[1-9][0-9]{0,18}$'),
    actor_id varchar(128) NOT NULL,
    authorization_version bigint NOT NULL CHECK (authorization_version >= 0),
    query_digest varchar(64) NOT NULL CHECK (query_digest ~ '^[0-9a-f]{64}$'),
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end = window_start + interval '1 minute'),
    filter_action varchar(64),
    filter_actor_id varchar(128),
    filter_category varchar(32),
    filter_outcome varchar(32),
    filter_resource_type varchar(64),
    filter_trace_id varchar(64),
    filter_from timestamptz,
    filter_to timestamptz,
    filter_from_epoch_second bigint,
    filter_from_nano integer,
    filter_to_epoch_second bigint,
    filter_to_nano integer,
    UNIQUE (scope, actor_id, authorization_version, query_digest, window_start),
    CHECK ((filter_from IS NULL AND filter_from_epoch_second IS NULL AND filter_from_nano IS NULL)
        OR (filter_from IS NOT NULL AND filter_from_epoch_second IS NOT NULL AND filter_from_nano IS NOT NULL AND filter_from_nano BETWEEN 0 AND 999999999)),
    CHECK ((filter_to IS NULL AND filter_to_epoch_second IS NULL AND filter_to_nano IS NULL)
        OR (filter_to IS NOT NULL AND filter_to_epoch_second IS NOT NULL AND filter_to_nano IS NOT NULL AND filter_to_nano BETWEEN 0 AND 999999999)),
    CHECK (filter_from IS NULL OR filter_to IS NULL
        OR (filter_from_epoch_second,filter_from_nano) < (filter_to_epoch_second,filter_to_nano))
);
                """);
            sql.execute("ALTER TABLE nw_audit_logs ENABLE ROW LEVEL SECURITY; ALTER TABLE nw_audit_logs FORCE ROW LEVEL SECURITY");
            // 与V279机构SELECT策略一致；平台授权读取窗口不属于本夹具。
            sql.execute("CREATE POLICY tenant_read ON nw_audit_logs FOR SELECT USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)");
            sql.execute("CREATE ROLE export_reader LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS; GRANT USAGE ON SCHEMA public TO export_reader; GRANT SELECT ON nw_audit_logs TO export_reader");
            sql.execute("GRANT SELECT,INSERT ON nw_foundation_audit_read_batches TO export_reader");
        }
        var data = new PGSimpleDataSource(); data.setURL(PG.getJdbcUrl());
        data.setUser("export_reader"); data.setPassword("test-only");
        var configuration = new Configuration(new Environment("audit-export", new JdbcTransactionFactory(), data));
        configuration.setMapUnderscoreToCamelCase(true); configuration.addInterceptor(new TenantRlsInterceptor());
        try (var source = AuditExportProjectionPostgresTest.class.getClassLoader().getResourceAsStream("mapper/foundation/OperationsMapper.xml")) {
            assertNotNull(source); new XMLMapperBuilder(source, configuration, "mapper/foundation/OperationsMapper.xml", configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void fixedExportColumnsMatchFullRowsAndEliminateLargePayloadWithSameFilters() throws Exception {
        TenantContext.setTenantId("7");
        var query = new AuditQuery("auth.login", "42", "authentication", "failure", "account", "trace",
                Instant.parse("2026-10-29T12:00:00Z"), Instant.parse("2026-10-29T13:00:00Z"), 1, 100);
        try (var session = factory.openSession()) {
            session.getConnection().setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            session.getConnection().setReadOnly(true);
            var mapper = session.getMapper(OperationsMapper.class);
            assertEquals(2, mapper.auditCount("7", query));
            var baseline = mapper.audits("7", query, 10001, 0);
            var rows = mapper.auditExportRows("7", query, 10001);
            var summaries = mapper.auditSummaries("7", query, 100, 0);
            assertEquals(baseline.stream().map(row -> new com.skillforge.core.foundation.operations.persistence.AuditSummaryRow(
                    row.getId(), row.getActorId(), row.getAction(), row.getCategory(), row.getOutcome(),
                    row.getResourceType(), row.getMessage(), row.getTraceId(), row.getCreatedAt())).toList(), summaries);
            assertEquals(List.of(summaries.get(1)), mapper.auditSummaries("7", query, 1, 1));
            assertTrue(mapper.auditSummaries("8", query, 100, 0).isEmpty());
            assertTrue(mapper.auditSummaries("platform", query, 100, 0).isEmpty());
            assertEquals(List.of(2L, 1L), rows.stream().map(AuditExportRow::id).toList());
            assertEquals(baseline.stream().map(row -> new AuditExportRow(row.getId(), row.getTenantId(), row.getActorId(),
                    row.getAction(), row.getCategory(), row.getOutcome(), row.getResourceType(), row.getResourceId(),
                    row.getMessage(), row.getTraceId(), row.getCreatedAt())).toList(), rows);
            assertEquals(128, baseline.getFirst().getPayload().size(), "列表仍读取完整脱敏payload");
            try (var sql = session.getConnection().createStatement(); var role = sql.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next()); assertTrue(role.getBoolean(1));
            }
            var arguments = Map.<String, Object>of("scope", "7", "query", query, "limit", 10001, "offset", 0L);
            long oldBytes = rowJsonBytes(session, "audits", arguments, 12);
            long newBytes = rowJsonBytes(session, "auditExportRows", arguments, 11);
            assertTrue(oldBytes > newBytes + 262144);
            long summaryBytes = rowJsonBytes(session, "auditSummaries", arguments, 9);
            assertTrue(oldBytes > summaryBytes + 262144);
            var summarySql = factory.getConfiguration().getMappedStatement(OperationsMapper.class.getName() + ".auditSummaries")
                    .getBoundSql(arguments).getSql();
            assertFalse(summarySql.contains("payload"));
            assertFalse(summarySql.substring(0, summarySql.indexOf("FROM")).contains("tenant_id"));
            assertFalse(summarySql.contains("resource_id"));
            System.out.println("AUDIT_SUMMARY_READ_BYTES rows=2 baseline=" + oldBytes + " candidate=" + summaryBytes
                    + " boundary=row-json-sum-only-no-latency-or-disk-claim");
            System.out.println("AUDIT_EXPORT_READ_BYTES rows=2 baseline=" + oldBytes + " candidate=" + newBytes
                    + " boundary=row-json-sum-only-no-latency-or-disk-claim");
            assertTrue(mapper.auditExportRows("8", query, 10001).isEmpty(), "伪造scope参数不能绕过真实RLS");
            assertTrue(mapper.auditExportRows("platform", query, 10001).isEmpty(), "机构连接不能读取平台事件");
        }
    }

    private static long rowJsonBytes(org.apache.ibatis.session.SqlSession session, String method,
            Map<String, Object> arguments, int expectedColumns) throws Exception {
        var statement = factory.getConfiguration().getMappedStatement(OperationsMapper.class.getName() + "." + method);
        var bound = statement.getBoundSql(arguments);
        try (var query = session.getConnection().prepareStatement(bound.getSql())) {
            new DefaultParameterHandler(statement, arguments, bound).setParameters(query);
            try (var rows = query.executeQuery()) {
                assertEquals(expectedColumns, rows.getMetaData().getColumnCount());
                if (expectedColumns == 11 || expectedColumns == 9) {
                    for (int column = 1; column <= expectedColumns; column++) assertNotEquals("jsonb", rows.getMetaData().getColumnTypeName(column));
                }
            }
        }
        try (var aggregate = session.getConnection().prepareStatement("SELECT coalesce(sum(octet_length(row_to_json(r)::text)),0) FROM (" + bound.getSql() + ") r")) {
            new DefaultParameterHandler(statement, arguments, bound).setParameters(aggregate);
            try (var result = aggregate.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    @Test void summaryAndFullReadsKeepOneBatchPerFilterAndAuthorizationVersion() {
        TenantContext.setTenantId("7");
        var query = new AuditQuery("auth.login", "42", null, null, null, null, null, null, 1, 50);
        var subject = new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, "7", "42");
        var first = AuditReadBatch.create(subject, 3, query, Instant.parse("2026-10-30T12:00:10Z"));
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(OperationsMapper.class);
            assertEquals(2, mapper.auditSummaries("7", query, 50, 0).size());
            assertEquals(1, mapper.insertReadBatch(new AuditReadBatch(1, first.scope(), first.actorId(), first.authorizationVersion(),
                    first.queryDigest(), first.windowStart(), first.windowEnd(), first.query())));
            assertEquals(2, mapper.audits("7", query, 50, 0).size());
            assertEquals(0, mapper.insertReadBatch(new AuditReadBatch(2, first.scope(), first.actorId(), first.authorizationVersion(),
                    first.queryDigest(), first.windowStart(), first.windowEnd(), first.query())));
            assertEquals(1, mapper.insertReadBatch(new AuditReadBatch(2, first.scope(), first.actorId(), 4,
                    first.queryDigest(), first.windowStart(), first.windowEnd(), first.query())));
            session.rollback();
        }
    }

    @Test void exportQueryReturnsOverflowSentinelInsteadOfSilentlyTruncatingAtTenThousand() throws Exception {
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO nw_audit_logs SELECT n,7,'42','bulk','operation','success',NULL,NULL,'',NULL,'{}',now() FROM generate_series(100,10101) n");
        }
        TenantContext.setTenantId("7");
        var query = new AuditQuery("bulk", null, null, null, null, null, null, null, 1, 100);
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(OperationsMapper.class);
            assertEquals(10002, mapper.auditCount("7", query));
            assertEquals(10001, mapper.auditExportRows("7", query, 10001).size());
        }
    }
}
