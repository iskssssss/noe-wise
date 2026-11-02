package com.skillforge.core.chat.persistence;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionWebhookDeliveryLedger;
import com.skillforge.core.shared.exception.ApiException;
import static org.mockito.Mockito.mock;
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

/** 私有最小15列DDL、真实RLS和正式Mapper/Service；结果集行JSON不代表网络、磁盘或延迟。 */
@Testcontainers(disabledWithoutDocker = false)
class SessionOutboxCursorProjectionPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static final Instant BASE = Instant.parse("2026-10-30T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResultEvidence EVIDENCE = new ResultEvidence();
    private static JdbcTemplate owner;
    private static JdbcTemplate reader;
    private static TransactionTemplate transactions;
    private static SessionOutboxMapper outbox;
    private static SessionMailboxService mailbox;

    @BeforeAll static void database() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE outbox_cursor_reader LOGIN PASSWORD 'synthetic-cursor-reader' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE TABLE nw_chat_session_outbox(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,session_id varchar(128) NOT NULL,"
                + "runtime_run_id varchar(128),event_id varchar(128) NOT NULL,input_id varchar(128),root_input_id varchar(128),"
                + "trace_id varchar(128),source_event_id varchar(128),caused_by_event_id varchar(128),session_sequence bigint NOT NULL CHECK(session_sequence>0),"
                + "event_type varchar(64) NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,"
                + "UNIQUE(tenant_id,event_id),UNIQUE(tenant_id,session_id,session_sequence))");
        owner.execute("ALTER TABLE nw_chat_session_outbox ENABLE ROW LEVEL SECURITY");
        owner.execute("ALTER TABLE nw_chat_session_outbox FORCE ROW LEVEL SECURITY");
        owner.execute("CREATE POLICY tenant_isolation ON nw_chat_session_outbox USING(tenant_id::text=current_setting('app.tenant_id',true))");
        owner.execute("GRANT USAGE ON SCHEMA public TO outbox_cursor_reader");
        owner.execute("GRANT SELECT ON nw_chat_session_outbox TO outbox_cursor_reader");
        seed(1, 7, "session-a", 10); seed(2, 7, "session-a", 30);
        seed(3, 7, "session-b", 20); seed(4, 8, "session-a", 40); seed(5, 7, "session-a", 20);
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "outbox_cursor_reader", "synthetic-cursor-reader");
        reader = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration(new Environment("outbox-cursor-private-pg", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor()); configuration.addInterceptor(EVIDENCE);
        String resource = "mapper/SessionOutboxMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        outbox = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(SessionOutboxMapper.class);
        mailbox = new SessionMailboxService(outbox, mock(SessionOutboxSequenceMapper.class), mock(SessionWebhookDeliveryLedger.class));
    }

    @BeforeEach void tenant() { TenantContext.setTenantId("7"); EVIDENCE.clear(); }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void exactCursorSequenceMatchesTheFullMapperRowWithoutReturningLargePayload() {
        EVIDENCE.clear();
        var full = transactions.execute(status -> outbox.selectOne(new QueryWrapper<SessionOutboxEntryDO>()
                .eq("tenant_id", 7L).eq("session_id", "session-a").eq("event_id", "event-1")));
        var old = EVIDENCE.material;
        assertThat(full).isNotNull(); assertThat(full.getPayload().toString()).contains("cursor-body-1");
        assertThat(old.columns).isEqualTo(15); assertThat(old.rows).isEqualTo(1);
        EVIDENCE.clear();
        long actual = transactions.execute(status -> mailbox.resolveOutputSequence("7", "session-a", "event-1"));
        var scalar = EVIDENCE.material;
        assertThat(actual).isEqualTo(full.getSessionSequence());
        assertThat(scalar.columns).isEqualTo(1); assertThat(scalar.names).containsExactly("session_sequence");
        assertThat(scalar.rows).isEqualTo(1); assertThat(scalar.bytes).isLessThan(old.bytes);
        System.out.printf("outbox-cursor same-input result-row-json: rows=%d fullColumns=%d cursorColumns=%d fullUtf8Bytes=%d cursorUtf8Bytes=%d%n",
                scalar.rows, old.columns, scalar.columns, old.bytes, scalar.bytes);
    }

    @Test void strictAfterCursorPagesKeepAllEventIdentitiesOrderAndPayloads() {
        var expected = transactions.execute(status -> outbox.selectList(new QueryWrapper<SessionOutboxEntryDO>()
                .eq("tenant_id", 7L).eq("session_id", "session-a").gt("session_sequence", 10L).orderByAsc("session_sequence")));
        var first = transactions.execute(status -> mailbox.listOutputsAfter("7", "session-a", "event-1", 1));
        var second = transactions.execute(status -> mailbox.listOutputsAfter("7", "session-a", first.getFirst().getEventId(), 100));
        var actual = new ArrayList<com.skillforge.core.chat.session.SessionOutboxEntry>(); actual.addAll(first); actual.addAll(second);
        assertThat(actual).extracting(com.skillforge.core.chat.session.SessionOutboxEntry::getEventId).containsExactly("event-5", "event-2");
        assertThat(actual).extracting(com.skillforge.core.chat.session.SessionOutboxEntry::getSessionSequence).containsExactly(20L, 30L);
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < actual.size(); i++) {
            assertThat(actual.get(i).getPayload()).isEqualTo(expected.get(i).getPayload());
            assertThat(actual.get(i).getCreatedAt()).isEqualTo(expected.get(i).getCreatedAt());
            assertThat(actual.get(i).getSessionId()).isEqualTo("session-a");
            assertThat(actual.get(i).getTenantId()).isEqualTo(7L);
        }
    }

    @Test void exactScopeAndNonBypassRlsKeepForeignOrMissingCursorsExpired() {
        assertThat(reader.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(reader.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='nw_chat_session_outbox'::regclass", Boolean.class)).isTrue();
        expired("7", "session-a", "event-3"); expired("7", "session-a", "missing"); expired("8", "session-a", "event-4");
        EVIDENCE.clear();
        long absent = transactions.execute(status -> mailbox.resolveOutputSequence("7", "session-a", null));
        long blank = transactions.execute(status -> mailbox.resolveOutputSequence("7", "session-a", "  "));
        assertThat(absent).isZero(); assertThat(blank).isZero();
        assertThat(EVIDENCE.material.rows).isZero(); assertThat(EVIDENCE.material.columns).isZero();
        TenantContext.setTenantId("8");
        long foreign = transactions.execute(status -> mailbox.resolveOutputSequence("8", "session-a", "event-4"));
        assertThat(foreign).isEqualTo(40L);
        expired("7", "session-a", "event-1");
        assertThatThrownBy(() -> reader.update("UPDATE nw_chat_session_outbox SET payload='{}'::jsonb WHERE id=4"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static void expired(String tenant, String session, String event) {
        var failure = catchThrowableOfType(() -> transactions.execute(status -> mailbox.resolveOutputSequence(tenant, session, event)), ApiException.class);
        assertThat(failure).isNotNull(); assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(failure.getCode()).isEqualTo("EVENT_CURSOR_EXPIRED");
    }

    private static void seed(long id, long tenant, String session, long sequence) throws Exception {
        String payload = JSON.writeValueAsString(Map.of("event", "message.delta", "data", Map.of("message_id", "message-" + id,
                "message_version", 1, "content", "cursor-body-" + id + "🙂".repeat(32_768))));
        owner.update("INSERT INTO nw_chat_session_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                id, tenant, session, "run-" + id, "event-" + id, "input-" + id, "root-" + id, "trace-" + id, "source-" + id,
                "cause-" + id, sequence, "message.delta", payload, Timestamp.from(BASE), Timestamp.from(BASE));
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
    @Intercepts({
            @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = Statement.class),
            @Signature(type = ResultSetHandler.class, method = "handleCursorResultSets", args = Statement.class)
    })
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
