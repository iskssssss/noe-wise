package com.skillforge.institution.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.channel.ChannelAgentBindingService;
import com.skillforge.core.channel.dto.request.ChannelAgentBindingRequest;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 PostgreSQL 唯一约束、非超级用户 RLS 与服务事务，验证完整替换及插入失败回滚。 */
@Testcontainers(disabledWithoutDocker = false)
class ChannelBindingReplacementTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate admin;
    private static ChannelAgentBindingService service;
    private static ChannelMapper channelMapper;
    private static DriverManagerDataSource applicationSource;

    @BeforeAll static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        admin.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY)");
        // 直接加载当前 baseline 的渠道定义，凭据约束的验证不能复制一套测试 schema。
        try (var input = ChannelBindingReplacementTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__baseline.sql")) {
            assertNotNull(input);
            String baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = baseline.indexOf("CREATE TABLE public.nw_channels (");
            assertTrue(start >= 0);
            admin.execute(baseline.substring(start, baseline.indexOf("\n);", start) + 3));
        }
        admin.execute("ALTER TABLE nw_channels ADD PRIMARY KEY(id)");
        admin.execute("ALTER TABLE nw_channels ADD FOREIGN KEY(tenant_id) REFERENCES nw_tenants(id)");
        admin.execute("CREATE TABLE nw_agents(id bigint PRIMARY KEY,tenant_id bigint REFERENCES nw_tenants(id),status text)");
        try (var input = ChannelBindingReplacementTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V41__channel_agent_bindings.sql")) {
            assertNotNull(input);
            admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        admin.execute("CREATE ROLE skillforge LOGIN PASSWORD 'channel_binding_test_password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT SELECT,INSERT,DELETE,UPDATE ON ALL TABLES IN SCHEMA public TO skillforge");
        for (var table : List.of("nw_channels", "nw_agents")) {
            admin.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            admin.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            admin.execute("CREATE POLICY tenant_isolation ON " + table
                    + " USING(tenant_id::text=current_setting('app.tenant_id',true))");
        }
        admin.execute("ALTER TABLE nw_channel_agent_bindings ENABLE ROW LEVEL SECURITY");
        try (var input = ChannelBindingReplacementTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V40__channel_auth_resolve_functions.sql")) {
            assertNotNull(input);
            admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        admin.execute("CREATE ROLE channel_public_test LOGIN PASSWORD 'public_test_password' NOSUPERUSER NOBYPASSRLS");
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                "skillforge", "channel_binding_test_password");
        applicationSource = source;
        var configuration = new Configuration();
        configuration.addMapper(ChannelMapper.class);
        configuration.addMapper(ChannelAgentBindingMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/ChannelMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/ChannelAgentBindingMapper.xml"));
        factory.setPlugins(new TenantRlsInterceptor());
        var sessions = new SqlSessionTemplate(factory.getObject());
        channelMapper = sessions.getMapper(ChannelMapper.class);
        var target = new ChannelAgentBindingService(sessions.getMapper(ChannelAgentBindingMapper.class),
                sessions.getMapper(ChannelMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (ChannelAgentBindingService) proxy.getProxy();
    }

    @BeforeEach void seed() {
        admin.execute("TRUNCATE nw_channel_agent_bindings,nw_channels,nw_agents,nw_tenants CASCADE");
        admin.update("INSERT INTO nw_tenants VALUES(7),(8)");
        admin.update("INSERT INTO nw_channels(id,tenant_id,channel_type,name,channel_token_hash) VALUES"
                + "(31,7,'openapi','api','token31'),(32,7,'web_widget','widget','token32'),(41,8,'openapi','foreign','token41')");
        admin.update("INSERT INTO nw_agents VALUES(81,7,'published'),(91,7,'published'),(92,7,'published'),"
                + "(93,7,'published'),(94,7,'draft'),(191,8,'published')");
        admin.update("INSERT INTO nw_channel_agent_bindings(id,tenant_id,channel_id,agent_id,is_default)"
                + " VALUES(1,7,31,81,true),(2,7,32,81,true),(3,8,41,191,true)");
        TenantContext.setTenantId("7");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void replacingAndRepeatingDefaultBindingKeepOnlyTheCompleteTargetSet() {
        var target = List.of(new ChannelAgentBindingRequest(91L, 3, true, 100),
                new ChannelAgentBindingRequest(92L, 2, false, 0));
        assertEquals(2, service.replaceBindings(7, 31, target));
        assertEquals(List.of("91:3:true:100", "92:2:false:0"), routes());
        assertEquals(2, service.replaceBindings(7, 31, target));
        assertEquals(List.of("91:3:true:100", "92:2:false:0"), routes());
        assertEquals(2, admin.queryForObject("SELECT count(*) FROM nw_channel_agent_bindings WHERE channel_id<>31", Integer.class));
        service.unbind(7, 31, 92);
        assertEquals(List.of("91:3:true:100"), routes());
        assertEquals(0, service.replaceBindings(7, 31, List.of()));
        assertTrue(routes().isEmpty());
    }

    @Test void duplicateDefaultForeignDraftAndWrongTenantRequestsCannotChangeOldBindings() {
        for (var rejected : List.of(
                List.of(new ChannelAgentBindingRequest(91L, 0, true, 100), new ChannelAgentBindingRequest(92L, 0, true, 0)),
                List.of(new ChannelAgentBindingRequest(91L, 0, false, 0), new ChannelAgentBindingRequest(91L, 1, false, 0)),
                List.of(new ChannelAgentBindingRequest(191L, 0, true, 100)),
                List.of(new ChannelAgentBindingRequest(94L, 0, true, 100)),
                List.of(new ChannelAgentBindingRequest(91L, 0, true, 101)))) {
            assertThrows(ApiException.class, () -> service.replaceBindings(7, 31, rejected));
            assertEquals(List.of("81:0:true:100"), routes());
        }
        assertThrows(ApiException.class, () -> service.replaceBindings(8, 41, List.of()));
        assertEquals(3, admin.queryForObject("SELECT count(*) FROM nw_channel_agent_bindings", Integer.class));
    }

    @Test void databaseInsertFailureRollsBackDeletionAndAllNewBindings() {
        var before = admin.queryForObject("SELECT created_at FROM nw_channel_agent_bindings WHERE id=1", java.time.OffsetDateTime.class);
        admin.execute("CREATE FUNCTION reject_test_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " IF NEW.agent_id=93 THEN RAISE EXCEPTION 'test_insert_rejected'; END IF; RETURN NEW; END $$");
        admin.execute("CREATE TRIGGER reject_test_binding BEFORE INSERT ON nw_channel_agent_bindings"
                + " FOR EACH ROW EXECUTE FUNCTION reject_test_binding()");
        try {
            assertThrows(RuntimeException.class, () -> service.replaceBindings(7, 31,
                    List.of(new ChannelAgentBindingRequest(91L, 0, true, 100), new ChannelAgentBindingRequest(93L, 0, false, 0))));
            assertEquals(List.of("81:0:true:100"), routes());
            assertEquals(before, admin.queryForObject("SELECT created_at FROM nw_channel_agent_bindings WHERE id=1", java.time.OffsetDateTime.class));
            assertEquals(3, admin.queryForObject("SELECT count(*) FROM nw_channel_agent_bindings", Integer.class));
        } finally {
            admin.execute("DROP TRIGGER reject_test_binding ON nw_channel_agent_bindings");
            admin.execute("DROP FUNCTION reject_test_binding()");
        }
    }

    @Test void dedicatedCredentialsAndResolverPreserveRestrictedRoleRlsAndFunctionPrivileges() throws Exception {
        var restricted = new JdbcTemplate(applicationSource);
        assertFalse(restricted.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        try (var connection = applicationSource.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM nw_channels")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "没有租户上下文不能直读渠道");
            }
            statement.execute("SELECT set_config('app.tenant_id','7',false)");
            try (var rows = statement.executeQuery("SELECT count(*) FROM nw_channels")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
            statement.execute("SELECT set_config('app.tenant_id','8',false)");
            try (var rows = statement.executeQuery("SELECT count(*) FROM nw_channels WHERE id=31")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
        String firstHash = com.skillforge.core.shared.security.TokenHash.sha256Hex("issued-api-key");
        String nextHash = com.skillforge.core.shared.security.TokenHash.sha256Hex("rotated-api-key");
        assertEquals(1, channelMapper.updateApiCredentials(7, 31, firstHash, "encrypted-secret"));
        TenantContext.clear();
        var resolved = channelMapper.selectForAuthByApiKeyHash(firstHash);
        assertNotNull(resolved); assertEquals(7L, resolved.getTenantId());
        assertEquals("encrypted-secret", resolved.getApiSecretEncrypted()); assertTrue(resolved.getConfig().isEmpty());
        assertNull(channelMapper.selectForAuthByApiKeyHash("missing-hash"));
        TenantContext.setTenantId("8");
        assertEquals(0, channelMapper.updateApiCredentials(7, 31, nextHash, "cross-tenant-secret"));
        TenantContext.setTenantId("7");
        assertThrows(RuntimeException.class, () -> channelMapper.updateApiCredentials(7, 31, firstHash, null));
        assertThrows(RuntimeException.class, () -> channelMapper.updateApiCredentials(7, 32, nextHash, "wrong-type-secret"));
        assertThrows(RuntimeException.class, () -> admin.update("UPDATE nw_channels SET config='{\"api_key_hash\":\"injected\"}'::jsonb WHERE id=31"));
        assertThrows(RuntimeException.class, () -> admin.update("UPDATE nw_channels SET config='{\"api_secret_encrypted\":\"injected\"}'::jsonb WHERE id=31"));
        assertEquals(1, channelMapper.updateApiCredentials(7, 31, nextHash, "rotated-secret"));
        TenantContext.clear();
        assertNull(channelMapper.selectForAuthByApiKeyHash(firstHash));
        assertEquals("rotated-secret", channelMapper.selectForAuthByApiKeyHash(nextHash).getApiSecretEncrypted());
        admin.update("UPDATE nw_channels SET status='disabled' WHERE id=31");
        assertNull(channelMapper.selectForAuthByApiKeyHash(nextHash));
        admin.update("UPDATE nw_channels SET status='active' WHERE id=31");
        TenantContext.setTenantId("7");
        assertEquals(1, channelMapper.updateApiCredentials(7, 31, null, null));
        TenantContext.clear(); assertNull(channelMapper.selectForAuthByApiKeyHash(nextHash));
        assertNotNull(channelMapper.selectForAuthByTokenHash("token31"));
        var publicRole = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                "channel_public_test", "public_test_password"));
        for (String function : List.of("resolve_channel_by_api_key_hash", "resolve_channel_by_token_hash")) {
            assertFalse(publicRole.queryForObject("SELECT has_function_privilege(current_user, 'public."
                    + function + "(text)', 'EXECUTE')", Boolean.class));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> publicRole.queryForList("SELECT * FROM public." + function + "('token31')"));
        }
    }

    private List<String> routes() {
        return admin.query("SELECT agent_id,priority,is_default,traffic_percentage FROM nw_channel_agent_bindings"
                + " WHERE tenant_id=7 AND channel_id=31 ORDER BY agent_id", (row, index) ->
                row.getLong(1) + ":" + row.getInt(2) + ":" + row.getBoolean(3) + ":" + row.getInt(4));
    }
}
