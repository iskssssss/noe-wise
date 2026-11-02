package com.skillforge.core.chat.persistence;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.persistence.SessionOutboxMapper.TraceEntry;
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

/** 私有PG上的正式XML投影对照；只计量结果行JSON，不推导扫描量、网络或延迟。 */
@Testcontainers(disabledWithoutDocker = false)
class SessionOutboxTraceProjectionPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static final Instant BASE = Instant.parse("2026-10-30T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResultEvidence EVIDENCE = new ResultEvidence();
    private static JdbcTemplate owner;
    private static JdbcTemplate reader;
    private static TransactionTemplate transactions;
    private static SessionOutboxMapper outbox;
    // 原实体完整15列查询保留在测试中，生产只有摘要投影。
    private static final String OLD_SQL = "SELECT id,tenant_id,session_id,runtime_run_id,event_id,input_id,root_input_id,trace_id,source_event_id,"
            + "caused_by_event_id,session_sequence,event_type,payload,created_at,updated_at FROM nw_chat_session_outbox"
            + " WHERE tenant_id=? AND root_input_id=? ORDER BY created_at ASC,session_sequence ASC";

    @BeforeAll static void database() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE outbox_trace_reader LOGIN PASSWORD 'synthetic-trace-reader' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE TABLE nw_chat_session_outbox(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,session_id varchar(128) NOT NULL,"
                + "runtime_run_id varchar(128),event_id varchar(128) NOT NULL,input_id varchar(128),root_input_id varchar(128),"
                + "trace_id varchar(128),source_event_id varchar(128),caused_by_event_id varchar(128),session_sequence bigint NOT NULL CHECK(session_sequence>0),"
                + "event_type varchar(64) NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,"
                + "UNIQUE(tenant_id,event_id),UNIQUE(tenant_id,session_id,session_sequence))");
        owner.execute("ALTER TABLE nw_chat_session_outbox ENABLE ROW LEVEL SECURITY");
        owner.execute("ALTER TABLE nw_chat_session_outbox FORCE ROW LEVEL SECURITY");
        owner.execute("CREATE POLICY tenant_isolation ON nw_chat_session_outbox USING(tenant_id::text=current_setting('app.tenant_id',true))");
        owner.execute("GRANT USAGE ON SCHEMA public TO outbox_trace_reader");
        owner.execute("GRANT SELECT ON nw_chat_session_outbox TO outbox_trace_reader");
        String payload = JSON.writeValueAsString(Map.of("synthetic_payload", "轨迹大正文-".repeat(8192)));
        // 插入顺序、id顺序均不同于查询排序；同根输入跨两个Session。
        seed(1, 7, "session-a", "root-shared", 30, BASE.plusSeconds(1), "trace-last", "source-last", payload);
        seed(2, 7, "session-b", "root-shared", 20, BASE, null, null, payload);
        seed(3, 7, "session-a", "root-shared", 10, BASE, "trace-first", "source-first", payload);
        seed(4, 7, "session-b", "root-other", 40, BASE, "trace-other", null, payload);
        seed(5, 8, "session-foreign", "root-shared", 50, BASE, "trace-foreign", null, payload);
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "outbox_trace_reader", "synthetic-trace-reader");
        reader = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration(new Environment("outbox-trace-private-pg", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor()); configuration.addInterceptor(EVIDENCE);
        String resource = "mapper/SessionOutboxMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        outbox = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(SessionOutboxMapper.class);
    }

    @BeforeEach void tenant() { TenantContext.setTenantId("7"); EVIDENCE.clear(); }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void sixColumnsMatchFullFifteenColumnValuesAndOrderAcrossSessionsWithLargePayloads() {
        var oldEvidence = new Material();
        var expected = oldRead(7, "root-shared", oldEvidence);
        var actual = current(7, "root-shared");
        assertThat(actual).containsExactlyElementsOf(expected).containsExactly(
                new TraceEntry("event-3", "message.completed", "trace-first", BASE, 10L, "source-first"),
                new TraceEntry("event-2", "message.completed", null, BASE, 20L, null),
                new TraceEntry("event-1", "message.completed", "trace-last", BASE.plusSeconds(1), 30L, "source-last"));
        assertThat(oldEvidence.columns).isEqualTo(15); assertThat(EVIDENCE.material.columns).isEqualTo(6);
        assertThat(oldEvidence.rows).isEqualTo(3); assertThat(EVIDENCE.material.rows).isEqualTo(3);
        assertThat(EVIDENCE.material.names).containsExactly("event_id", "event_type", "trace_id", "created_at", "session_sequence", "source_event_id");
        assertThat(EVIDENCE.material.bytes).isLessThan(oldEvidence.bytes);
        System.out.printf("outbox-trace same-input result-row-json: rows=%d fullColumns=%d traceColumns=%d fullUtf8Bytes=%d traceUtf8Bytes=%d%n",
                actual.size(), oldEvidence.columns, EVIDENCE.material.columns, oldEvidence.bytes, EVIDENCE.material.bytes);
        assertThat(current(7, "root-other")).extracting(TraceEntry::eventId).containsExactly("event-4");
        assertThat(current(7, "root-missing")).isEmpty();
    }

    @Test void selectOnlyNonBypassRoleEnforcesTenantContextAndRejectsWrites() {
        assertThat(reader.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(reader.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='nw_chat_session_outbox'::regclass", Boolean.class)).isTrue();
        assertThat(current(8, "root-shared")).isEmpty();
        assertThat(oldRead(8, "root-shared", new Material())).isEmpty();
        TenantContext.setTenantId("8");
        assertThat(current(8, "root-shared")).extracting(TraceEntry::eventId).containsExactly("event-5");
        assertThat(current(7, "root-shared")).isEmpty();
        assertThatThrownBy(() -> reader.update("UPDATE nw_chat_session_outbox SET event_type='forbidden' WHERE id=5"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static List<TraceEntry> current(long tenant, String root) {
        EVIDENCE.clear(); return transactions.execute(status -> outbox.traceEntries(tenant, root));
    }

    private static List<TraceEntry> oldRead(long tenant, String root, Material evidence) {
        return transactions.execute(status -> {
            reader.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, TenantContext.getTenantId());
            return reader.query(OLD_SQL, (org.springframework.jdbc.core.ResultSetExtractor<List<TraceEntry>>) rows -> {
                evidence.inspectColumns(rows); var result = new ArrayList<TraceEntry>();
                while (rows.next()) {
                    evidence.capture(rows);
                    result.add(new TraceEntry(rows.getString("event_id"), rows.getString("event_type"), rows.getString("trace_id"),
                            rows.getTimestamp("created_at").toInstant(), (Long) rows.getObject("session_sequence"), rows.getString("source_event_id")));
                }
                return result;
            }, tenant, root);
        });
    }

    private static void seed(long id, long tenant, String session, String root, long sequence, Instant created,
            String trace, String source, String payload) {
        owner.update("INSERT INTO nw_chat_session_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                id, tenant, session, "run-" + id, "event-" + id, "input-" + id, root, trace, source,
                "cause-" + id, sequence, "message.completed", payload, Timestamp.from(created), Timestamp.from(created));
    }

    /** 两路径用同一编码器计量实际ResultSet各列值的row JSON，不是PG磁盘/网络或延迟估计。 */
    private static final class Material {
        int columns; long rows; long bytes; List<String> names = List.of();
        void inspectColumns(ResultSet source) throws java.sql.SQLException {
            var meta = source.getMetaData(); columns = meta.getColumnCount(); var labels = new ArrayList<String>();
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
