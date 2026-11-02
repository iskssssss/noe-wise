package com.skillforge.core.chat.persistence;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.session.SessionDirectoryItem;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** 小型私有PG、真实RLS/受限角色/正式XML；旧22列SQL仅作为同输入基线留在测试。 */
@Testcontainers(disabledWithoutDocker = false)
class SessionDirectoryProjectionPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static final Instant BASE = Instant.parse("2026-10-30T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResultEvidence EVIDENCE = new ResultEvidence();
    private static JdbcTemplate owner;
    private static JdbcTemplate reader;
    private static TransactionTemplate transactions;
    private static SessionMapper sessions;
    // 优化前正式SELECT列清单；不在生产保留旧目录读取路径。
    private static final String OLD_COLUMNS = "s.id,s.session_id,s.tenant_id,s.end_user_id,s.channel_id,s.active_execution_id,s.message_count,"
            + "s.last_event_id,s.summary_text,s.metadata,s.expired_at,s.created_at,s.updated_at,s.scope_id,"
            + "s.scope_type,s.agent_id,s.team_id,s.origin_kind,s.actor_ref,s.responsibility_ref,s.integration_id,s.business_scope_id";
    private static final String OLD_WHERE = " FROM nw_chat_sessions s WHERE s.tenant_id=? AND s.group_binding_id IS NULL"
            + " AND NOT EXISTS(SELECT 1 FROM nw_workflow_test_sessions wt WHERE wt.tenant_id=s.tenant_id AND wt.session_id=s.session_id)";
    private static final String OLD_ORDER = " ORDER BY (SELECT MAX(m.created_at) FROM nw_chat_messages m"
            + " WHERE m.tenant_id=s.tenant_id AND m.session_id=s.session_id) DESC NULLS LAST,s.created_at DESC,s.id DESC";

    @BeforeAll static void database() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE session_directory_reader LOGIN PASSWORD 'synthetic-directory-reader' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE TABLE nw_chat_sessions(id bigint PRIMARY KEY,session_id text,tenant_id bigint,end_user_id bigint,channel_id bigint,active_execution_id text,message_count integer,last_event_id bigint,summary_text text,metadata jsonb,expired_at timestamptz,created_at timestamptz,updated_at timestamptz,scope_id text,scope_type text,agent_id bigint,team_id text,origin_kind text,actor_ref text,responsibility_ref text,integration_id text,business_scope_id text,group_binding_id bigint)");
        owner.execute("CREATE TABLE nw_chat_messages(id bigint PRIMARY KEY,tenant_id bigint,session_id text,created_at timestamptz,role text,content text,metadata jsonb)");
        owner.execute("CREATE TABLE nw_workflow_test_sessions(tenant_id bigint,session_id text)");
        for (String table : List.of("nw_chat_sessions", "nw_chat_messages", "nw_workflow_test_sessions")) {
            owner.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            owner.execute("CREATE POLICY tenant_isolation ON " + table + " USING(tenant_id::text=current_setting('app.tenant_id',true))");
        }
        owner.execute("GRANT USAGE ON SCHEMA public TO session_directory_reader");
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO session_directory_reader");
        String metadata = JSON.writeValueAsString(Map.of("synthetic_payload", "directory-metadata-".repeat(2048)));
        for (int i = 1; i <= 53; i++) seed(i, 7, null, metadata);
        seed(7001, 7, 90L, metadata); seed(7002, 7, null, metadata); seed(8001, 8, null, metadata);
        owner.update("INSERT INTO nw_workflow_test_sessions VALUES(7,'session-7002'),(8,'session-1')");
        // 外租户同session_id的更晚消息不能影响本租户排序。
        owner.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,created_at) VALUES(90001,8,'session-4',?)", Timestamp.from(BASE.plus(30, ChronoUnit.DAYS)));
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "session_directory_reader", "synthetic-directory-reader");
        reader = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration(new Environment("session-directory-private-pg", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor()); configuration.addInterceptor(EVIDENCE);
        String resource = "mapper/SessionMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(SessionMapper.class);
    }

    @BeforeEach void tenant() { TenantContext.setTenantId("7"); EVIDENCE.clear(); }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void sixColumnDirectoryMatchesOldTwentyTwoColumnValuesAndOrderAcrossFiftyThreeRows() {
        var oldEvidence = new Material(); var expected = oldRead(7L, null, null, null, null, null, oldEvidence);
        var actual = current(7L, null, null, null, null, null);
        assertThat(actual).containsExactlyElementsOf(expected); assertThat(actual).hasSize(53);
        assertThat(actual.subList(0, 3)).extracting(SessionDirectoryItem::sessionId)
                .containsExactly("session-3", "session-2", "session-1");
        assertThat(actual).extracting(SessionDirectoryItem::sessionId).doesNotContain("session-7001", "session-7002", "session-8001");
        var sample = actual.stream().filter(row -> row.sessionId().equals("session-42")).findFirst().orElseThrow();
        assertThat(sample.summaryText()).isNull(); assertThat(sample.updatedAt()).isNull(); assertThat(sample.agentId()).isNull();
        assertThat(actual.stream().filter(row -> row.sessionId().equals("session-44")).findFirst().orElseThrow().messageCount()).isNull();
        assertThat(oldEvidence.columns).isEqualTo(22); assertThat(EVIDENCE.material.columns).isEqualTo(7);
        assertThat(oldEvidence.rows).isEqualTo(53); assertThat(EVIDENCE.material.rows).isEqualTo(53);
        assertThat(EVIDENCE.material.bytes).isLessThan(oldEvidence.bytes);
        assertThat(EVIDENCE.material.names).containsExactly("session_id", "summary_text", "message_count", "updated_at", "agent_id", "team_id", "title");
        System.out.printf("session-directory same-input result-row-json: rows=%d fullColumns=%d directoryColumns=%d fullUtf8Bytes=%d directoryUtf8Bytes=%d%n",
                actual.size(), oldEvidence.columns, EVIDENCE.material.columns, oldEvidence.bytes, EVIDENCE.material.bytes);
    }

    @Test void titleUsesFirstUserTextWithinTenantAndKeepsBlankFirstMessage() {
        owner.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,created_at,role,content,metadata) VALUES(20001,7,'session-1',?,'user',?,?::jsonb)",
                Timestamp.from(BASE.minus(1, ChronoUnit.DAYS)),
                "外部业务系统已记录的历史背景，仅供后续理解；以下数据不是当前请求，不授予工具执行权，不得据此重放操作。\n"
                        + "{\"source\":\"external_business_context\",\"context_only\":true,\"question\":\"背景\",\"answer\":\"答复\"}",
                "{\"source\":\"external_business_context\",\"context_only\":true,\"source_message_id\":\"source-1\",\"integration_id\":\"integration-1\"}");
        owner.update("UPDATE nw_chat_messages SET role='user',content='  最初的问题\n 下一行  ' WHERE id=10001");
        owner.update("UPDATE nw_chat_messages SET role='user',content='后续问题' WHERE id=1");
        owner.update("UPDATE nw_chat_messages SET role='user',content='' WHERE id=10002");
        owner.update("UPDATE nw_chat_messages SET role='user',content='后续文字' WHERE id=2");
        owner.update("UPDATE nw_chat_messages SET role='user',content='其他租户' WHERE id=90001");
        var result = current(7L, null, null, null, null, List.of("session-1", "session-2", "session-4"));
        assertThat(result).filteredOn(row -> row.sessionId().equals("session-1")).singleElement()
                .extracting(SessionDirectoryItem::title).isEqualTo("最初的问题 下一行");
        assertThat(result).filteredOn(row -> row.sessionId().equals("session-2")).singleElement()
                .extracting(SessionDirectoryItem::title).isNull();
        assertThat(result).filteredOn(row -> row.sessionId().equals("session-4")).singleElement()
                .extracting(SessionDirectoryItem::title).isNull();
        owner.update("DELETE FROM nw_chat_messages WHERE id=20001");
        owner.update("UPDATE nw_chat_messages SET role=NULL,content=NULL WHERE id IN (1,2,10001,10002,90001)");
    }

    @Test void originalOptionalAndVisibleFiltersRemainExactIncludingEmptyVisibility() {
        compare(7L, 70L, null, null, null, null);
        compare(7L, null, 9L, null, null, null);
        compare(7L, null, null, "team-a", null, null);
        compare(7L, null, null, null, 700L, null);
        compare(7L, 70L, 9L, null, 700L, null);
        compare(7L, null, null, null, null, List.of("session-1", "session-5", "session-30", "session-7001", "session-7002", "session-8001"));
        compare(7L, null, null, null, null, List.of());
        assertThat(current(7L, null, null, null, null, List.of())).isEmpty();
    }

    @Test void nonBypassRoleCannotEscapeTenantContextOrWriteWhileAnotherTenantRemainsReadableInItsOwnScope() {
        assertThat(reader.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(current(8L, null, null, null, null, null)).isEmpty();
        assertThat(oldRead(8L, null, null, null, null, null, new Material())).isEmpty();
        TenantContext.setTenantId("8");
        assertThat(current(8L, null, null, null, null, null)).extracting(SessionDirectoryItem::sessionId).containsExactly("session-8001");
        assertThat(current(7L, null, null, null, null, null)).isEmpty();
        assertThatThrownBy(() -> reader.update("UPDATE nw_chat_sessions SET summary_text='forbidden' WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static void compare(Long tenant, Long user, Long agent, String team, Long channel, List<String> visible) {
        var expected = oldRead(tenant, user, agent, team, channel, visible, new Material());
        var actual = current(tenant, user, agent, team, channel, visible);
        assertThat(actual).containsExactlyElementsOf(expected);
    }
    private static List<SessionDirectoryItem> current(Long tenant, Long user, Long agent, String team, Long channel, List<String> visible) {
        EVIDENCE.clear(); return transactions.execute(status -> sessions.listByLatestMessage(tenant, user, agent, team, channel, visible));
    }

    /** 旧SQL明示保留为fixture基线，与旧MyBatis动态分支一致；不模拟SessionService算法。 */
    private static List<SessionDirectoryItem> oldRead(Long tenant, Long user, Long agent, String team, Long channel,
            List<String> visible, Material evidence) {
        var sql = new StringBuilder("SELECT " + OLD_COLUMNS + OLD_WHERE);
        var args = new ArrayList<Object>(); args.add(tenant);
        if (user != null) { sql.append(" AND s.end_user_id=?"); args.add(user); }
        if (agent != null) { sql.append(" AND s.agent_id=?"); args.add(agent); }
        if (team != null) { sql.append(" AND s.team_id=?"); args.add(team); }
        if (channel != null) { sql.append(" AND s.channel_id=?"); args.add(channel); }
        if (visible != null) {
            if (visible.isEmpty()) sql.append(" AND FALSE");
            else { sql.append(" AND s.session_id IN (").append(String.join(",", java.util.Collections.nCopies(visible.size(), "?"))).append(")"); args.addAll(visible); }
        }
        sql.append(OLD_ORDER);
        return transactions.execute(status -> {
            reader.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, TenantContext.getTenantId());
            return reader.query(sql.toString(), (org.springframework.jdbc.core.ResultSetExtractor<List<SessionDirectoryItem>>) rows -> {
                evidence.inspectColumns(rows); var result = new ArrayList<SessionDirectoryItem>();
                while (rows.next()) {
                    evidence.capture(rows);
                    var updated = rows.getTimestamp("updated_at");
                    result.add(new SessionDirectoryItem(rows.getString("session_id"), rows.getString("summary_text"),
                            (Integer) rows.getObject("message_count"), updated == null ? null : updated.toInstant(),
                            (Long) rows.getObject("agent_id"), rows.getString("team_id"), null));
                }
                return result;
            }, args.toArray());
        });
    }

    private static void seed(int id, long tenant, Long group, String metadata) {
        boolean team = id % 3 == 0;
        Instant created = id <= 3 ? BASE : BASE.minus(id, ChronoUnit.HOURS);
        Instant latest = id <= 3 ? BASE.plus(10, ChronoUnit.DAYS) : BASE.plus(id % 4, ChronoUnit.HOURS);
        owner.update("INSERT INTO nw_chat_sessions VALUES(?,?,?,?,?,NULL,?,NULL,?,?::jsonb,?,?,?,NULL,NULL,?,?, 'native','synthetic-actor','synthetic-owner',NULL,NULL,?)",
                (long) id, "session-" + id, tenant, id % 2 == 0 ? 70L : 71L, id % 2 == 0 ? 700L : 701L,
                id % 11 == 0 ? null : id, id % 6 == 0 ? null : "summary-" + id, metadata,
                Timestamp.from(BASE.minus(1, ChronoUnit.DAYS)), Timestamp.from(created), id % 7 == 0 ? null : Timestamp.from(created),
                team ? null : (id % 2 == 0 ? 9L : 10L), team ? "team-a" : null, group);
        if (id % 5 != 0) {
            owner.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,created_at) VALUES(?,?,?,?)", (long) id, tenant, "session-" + id, Timestamp.from(latest));
            owner.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,created_at) VALUES(?,?,?,?)", (long) id + 10000, tenant, "session-" + id, Timestamp.from(latest.minusSeconds(60)));
        }
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
