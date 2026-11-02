package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.chat.persistence.UserProfileMapper;
import com.skillforge.core.chat.persistence.UserProfileDO;
import com.skillforge.core.chat.profile.UserProfileService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.*;
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

/** 真实 PostgreSQL/Mapper/RLS 下确定性复现异步会话总结的丢更新。 */
@Testcontainers(disabledWithoutDocker = false)
class UserProfileAppendConcurrencyTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate admin;
    private static UserProfileMapper profiles;
    private static DriverManagerDataSource application;

    @BeforeAll
    static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        // 只装配画像写入使用的正式表定义；不宣称完整迁移链验收。
        String baseline = migration("V1__baseline.sql");
        int start = baseline.indexOf("CREATE TABLE public.nw_user_profiles (");
        assertTrue(start >= 0);
        admin.execute(baseline.substring(start, baseline.indexOf(';', start) + 1));
        admin.execute(migration("V20__memory_user_profiles_summaries.sql"));
        admin.execute("ALTER TABLE nw_user_profiles ADD CONSTRAINT skillforge_user_profiles_pkey PRIMARY KEY(id)");
        admin.execute("ALTER TABLE nw_user_profiles ADD CONSTRAINT uq_skillforge_user_profiles UNIQUE(tenant_id,end_user_id)");
        admin.execute("ALTER TABLE nw_user_profiles ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE nw_user_profiles FORCE ROW LEVEL SECURITY");
        int policy = baseline.indexOf("CREATE POLICY skillforge_user_profiles_tenant_isolation ");
        assertTrue(policy >= 0);
        admin.execute(baseline.substring(policy, baseline.indexOf(';', policy) + 1));
        admin.execute("CREATE ROLE profile_append_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT SELECT,INSERT,UPDATE ON nw_user_profiles TO profile_append_test");
        application = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "profile_append_test", "test-only-password");
        assertEquals(false, new JdbcTemplate(application).queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("profile-append-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(UserProfileMapper.class);
        try (var input = UserProfileAppendConcurrencyTest.class.getClassLoader()
                .getResourceAsStream("mapper/UserProfileMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, "mapper/UserProfileMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        profiles = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(UserProfileMapper.class);
    }

    @BeforeEach
    void seed() {
        admin.execute("TRUNCATE nw_user_profiles");
        admin.update("INSERT INTO nw_user_profiles(id,tenant_id,end_user_id,summary,summaries,interaction_count)"
                + " VALUES(1,7,101,?, '[{\"content\":\"seed\"}]'::jsonb,1)",
                "{\"用户目标\":\"\",\"关键偏好\":{},\"历史结论\":[\"seed\"]}");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void concurrentSessionSummariesBothSurviveAndIncrementInteractionCount() throws Exception {
        var barrier = new CyclicBarrier(2);
        var settings = mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(
                1, Map.of("max_raw_summaries", 10L, "incremental_merge", false)));
        when(settings.read("prompts")).thenReturn(new OperationalSettingsService.Snapshot(1, Map.of()));
        var target = new UserProfileService(profiles, mock(LlmService.class), new ObjectMapper(), settings) {
            @Override
            public UserProfileDO get(String tenant, Long user) {
                UserProfileDO snapshot = super.get(tenant, user);
                // 两条旧读路径均已读到同一持久化窗口后才允许继续写入，无固定 sleep。
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception exception) { throw new IllegalStateException("profile_read_barrier_failed", exception); }
                return snapshot;
            }
        };
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(application),
                new AnnotationTransactionAttributeSource()));
        UserProfileService service = (UserProfileService) proxy.getProxy();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> append(service, "session-one"));
            var second = executor.submit(() -> append(service, "session-two"));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(3, admin.queryForObject("SELECT interaction_count FROM nw_user_profiles WHERE id=1", Integer.class));
        var raw = new ObjectMapper().readTree(admin.queryForObject(
                "SELECT summaries::text FROM nw_user_profiles WHERE id=1", String.class));
        var contents = new java.util.HashSet<String>();
        raw.forEach(entry -> contents.add(entry.path("content").asText()));
        assertEquals(java.util.Set.of("seed", "session-one", "session-two"), contents);
        TenantContext.setTenantId("8");
        assertNull(new UserProfileService(profiles, mock(LlmService.class), new ObjectMapper(), settings).get("7", 101L));
    }

    private static void append(UserProfileService service, String summary) {
        TenantContext.setTenantId("7");
        try { service.appendSummary("7", 101L, summary); }
        finally { TenantContext.clear(); }
    }

    @Test
    void concurrentFirstAppendArbitratesOneRow() throws Exception {
        admin.execute("TRUNCATE nw_user_profiles");
        var service = service(mock(LlmService.class), false, 10);
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); append(service, "first"); return null; });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); append(service, "second"); return null; });
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_user_profiles", Integer.class));
        assertEquals(2, admin.queryForObject("SELECT interaction_count FROM nw_user_profiles", Integer.class));
        assertEquals(2, admin.queryForObject("SELECT jsonb_array_length(summaries) FROM nw_user_profiles", Integer.class));
    }

    /** 模型暂停时允许另一会话提交；旧结果不得覆盖新窗口，也不重复付费调用。 */
    @Test
    void mergeRunsOutsideCallerTransactionAndRejectsStaleSnapshot() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var llm = mock(LlmService.class);
        when(llm.generate(anyString(), anyString())).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return new com.skillforge.core.model.invocation.LlmResult(
                    "{\"用户目标\":\"stale-merged\",\"关键偏好\":{},\"历史结论\":[]}", "test", null);
        });
        var merging = service(llm, true, 2);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                TenantContext.setTenantId("7");
                try {
                    new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(application))
                            .executeWithoutResult(status -> merging.appendSummary("7", 101L, "before-merge"));
                } finally { TenantContext.clear(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            append(service(mock(LlmService.class), false, 2), "newer-summary");
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        verify(llm, times(1)).generate(anyString(), anyString());
        assertEquals(3, admin.queryForObject("SELECT interaction_count FROM nw_user_profiles", Integer.class));
        assertEquals(2L, admin.queryForObject("SELECT revision FROM nw_user_profiles", Long.class));
        assertFalse(admin.queryForObject("SELECT summary FROM nw_user_profiles", String.class).contains("stale-merged"));
        var window = new ObjectMapper().readTree(admin.queryForObject("SELECT summaries::text FROM nw_user_profiles", String.class));
        assertEquals(List.of("before-merge", "newer-summary"), List.of(
                window.get(0).path("content").asText(), window.get(1).path("content").asText()));
        TenantContext.setTenantId("7");
        profiles.upsert(99L, 7L, 101L,
                "{\"用户目标\":\"\",\"关键偏好\":{},\"历史结论\":[]}", Map.of(), Map.of(), 1, java.time.Instant.now());
        assertEquals(3L, admin.queryForObject("SELECT revision FROM nw_user_profiles", Long.class));
        assertEquals(0, profiles.mergeSummaryIfRevision(7L, 101L, 2, "stale"));
    }

    private UserProfileService service(LlmService llm, boolean merge, int max) {
        var settings = mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("max_raw_summaries", (long) max, "incremental_merge", merge)));
        when(settings.read("prompts")).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("profile_merge_prompt", "merge")));
        var proxy = new ProxyFactory(new UserProfileService(profiles, llm, new ObjectMapper(), settings));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(application),
                new AnnotationTransactionAttributeSource()));
        return (UserProfileService) proxy.getProxy();
    }

    private static String migration(String name) throws Exception {
        try (var input = UserProfileAppendConcurrencyTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
