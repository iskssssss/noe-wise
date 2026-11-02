package com.skillforge.institution.controller;

import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.security.SecurityMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.*;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.AuthService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.security.RefreshTokens;
import com.skillforge.core.foundation.security.ScopedJwtLogic;
import com.skillforge.core.shared.web.HostTraceIdFilter;
import com.skillforge.institution.config.ExternalAuthInterceptor;
import com.skillforge.institution.config.SaTokenConfiguration;
import com.skillforge.institution.config.TenantContextInterceptor;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 隔离 RLS/Redis 下的 MockMvc 清理场景与随机端口真实 Sa-Token/JWT 刷新验收。 */
@Testcontainers
class AuthRefreshHttpIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static JdbcTemplate admin;
    private static org.springframework.jdbc.datasource.DataSourceTransactionManager transactions;
    private static SqlSessionTemplate sessions;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private MockMvc mvc;
    private MockedStatic<StpUtil> stp;
    private AnnotationConfigWebApplicationContext context;

    @BeforeAll static void schema() {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        admin = new JdbcTemplate(source);
        admin.execute("CREATE ROLE skillforge NOSUPERUSER NOBYPASSRLS");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        admin.execute("CREATE ROLE auth_http_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO auth_http_test");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO auth_http_test");
        var application = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "auth_http_test", "test-only-password");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("auth-http-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(new AuditMetaObjectHandler());
        configuration.addInterceptor(new TenantRlsInterceptor());
        for (var mapper : List.of(TenantMapper.class, TenantMemberMapper.class, AuditLogMapper.class)) configuration.addMapper(mapper);
        try(var xml=AuthRefreshHttpIntegrationTest.class.getClassLoader().getResourceAsStream("mapper/foundation/security/SecurityMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,configuration,"mapper/foundation/security/SecurityMapper.xml",configuration.getSqlFragments()).parse();
        } catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
        transactions=new org.springframework.jdbc.datasource.DataSourceTransactionManager(application);
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        assertEquals(false, new JdbcTemplate(application).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        admin.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target,is_active) VALUES(701,'auth-http-plan','测试套餐',0.99,true)");
        admin.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'机构','auth-http-seven',701)");
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections); redis.afterPropertiesSet();
    }

    @BeforeEach void prepareHttp() {
        admin.update("DELETE FROM nw_foundation_security_sessions");
        admin.update("DELETE FROM nw_audit_logs WHERE tenant_id=7");
        admin.update("DELETE FROM nw_tenant_members WHERE tenant_id=7");
        admin.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,password_hash) VALUES(11,7,'member','developer',?)",new BCryptPasswordEncoder().encode("OldPassword123"));
        try (var connection = connections.getConnection()) { connection.serverCommands().flushDb(); }
        stp = mockStatic(StpUtil.class); stp.when(StpUtil::getTokenValue).thenReturn("test-only-issued.jwt");
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(RefreshMvcConfiguration.class);
        context.refresh();
        var filter = new HostTraceIdFilter(context.getBean("handlerExceptionResolver", HandlerExceptionResolver.class));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }

    @AfterEach void clear() {
        if (context != null) context.close();
        if (stp != null) stp.close();
        TenantContext.clear(); RequestContext.clear();
    }
    @AfterAll static void closeRedis() { if (connections != null) connections.destroy(); }

    /** 仅注册刷新所需 MVC Bean，复用生产认证排除路径及租户拦截器配置。 */
    @Configuration
    @EnableWebMvc
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import({SaTokenConfiguration.class, AuthController.class, GlobalExceptionHandler.class})
    static class RefreshMvcConfiguration implements WebMvcConfigurer {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
        @Bean SkillForgeProperties properties() { return new SkillForgeProperties(); }
        @Bean TenantContextInterceptor tenantContextInterceptor() { return new TenantContextInterceptor(); }
        @Bean ExternalAuthInterceptor externalAuthInterceptor() { return mock(ExternalAuthInterceptor.class); }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(){return transactions;}
        @Bean FoundationSecurityService security(SkillForgeProperties properties) {
            properties.setSecretKey("synthetic-auth-http-master-key-at-least32");
            var access=mock(FoundationAccess.class);
            when(access.snapshot(any())).thenReturn(new FoundationAccess.Snapshot(List.of("developer"),List.of(),1));
            when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","11"));
            return new FoundationSecurityService(sessions.getMapper(SecurityMapper.class),access,new BCryptPasswordEncoder(),
                    new CryptoService(properties),properties,redis,mock(NotificationService.class),new AuditService(sessions.getMapper(AuditLogMapper.class)));
        }
        @Bean AuthService authService(FoundationSecurityService security){return new AuthService(security);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(objectMapper()));
        }
    }

    /** 最小 Servlet 宿主，仅装配生产刷新 MVC 配置、真实 JWT 上下文与追踪过滤器。 */
    @Configuration
    static class RefreshHttpServerConfiguration {
        @Bean ServletWebServerFactory webServerFactory() { return new TomcatServletWebServerFactory(0); }
        @Bean ServletRegistrationBean<DispatcherServlet> dispatcherServlet(WebApplicationContext context) {
            var registration = new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
            registration.setLoadOnStartup(1);
            return registration;
        }
        @Bean BlockingQueue<Boolean> cleanedHttpRequests() { return new LinkedBlockingQueue<>(); }
        @Bean FilterRegistrationBean<Filter> cleanupObservation(BlockingQueue<Boolean> cleanedHttpRequests) {
            Filter observation = (request, response, chain) -> {
                try { chain.doFilter(request, response); }
                finally {
                    cleanedHttpRequests.add(TenantContext.getTenantId() == null
                            && RequestContext.getUserId() == null && RequestContext.getTraceId() == null);
                }
            };
            var registration = new FilterRegistrationBean<>(observation);
            registration.setOrder(-30);
            return registration;
        }
        @Bean FilterRegistrationBean<HostTraceIdFilter> traceFilter(
                @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
            var registration = new FilterRegistrationBean<>(new HostTraceIdFilter(errors));
            registration.setOrder(-20);
            return registration;
        }
        @Bean FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> saTokenContextFilter() {
            var registration = new FilterRegistrationBean<>(new SaTokenContextFilterForJakartaServlet());
            registration.setOrder(-10);
            return registration;
        }
    }

    @Test void randomPortHttpRefreshWithoutAuthorizationSignsRealJwtThenRejectsReplayedCredential() throws Exception {
        stp.close(); stp = null;
        context.close(); context = null;
        var previousLogic = StpUtil.getStpLogic();
        var previousConfig = SaManager.getConfig();
        var previousContext = SaManager.getSaTokenContext();
        var previousDao = SaManager.getSaTokenDao();
        var isolatedDao = new SaTokenDaoDefaultImpl();
        var signing = new SaTokenConfig().setJwtSecretKey("test-only-http-jwt-signing-key-with-no-production-secret")
                .setTokenName("Authorization").setTokenPrefix("Bearer").setIsReadCookie(false).setIsPrint(false);
        try (var server = new AnnotationConfigServletWebServerApplicationContext()) {
            SaManager.setConfig(signing);
            SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
            SaManager.setSaTokenDao(isolatedDao);
            server.register(RefreshMvcConfiguration.class, RefreshHttpServerConfiguration.class);
            server.refresh();
            var logic = (ScopedJwtLogic) server.getBean(cn.dev33.satoken.stp.StpLogic.class);
            logic.setConfig(signing);
            StpUtil.setStpLogic(logic);
            @SuppressWarnings("unchecked")
            var cleaned = (BlockingQueue<Boolean>) server.getBean("cleanedHttpRequests");
            String raw=seedSession("network");String oldHash=hash(raw);
            String base = "http://127.0.0.1:" + server.getWebServer().getPort();
            var refresh = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/refresh"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("X-Trace-Id", "real-refresh-http")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"refresh_token\":\"7."+raw+"\"}")).build();
            assertTrue(refresh.headers().firstValue("Authorization").isEmpty());
            try (var client = HttpClient.newHttpClient()) {
                var success = client.send(refresh, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, success.statusCode(), success.body());
                var body = new ObjectMapper().readTree(success.body());
                assertEquals("OK", body.path("code").asText());
                assertEquals("7", body.path("data").path("tenant_id").asText());
                assertEquals("real-refresh-http", success.headers().firstValue("X-Trace-Id").orElseThrow());
                assertEquals("real-refresh-http", body.path("trace_id").asText());
                String access = body.path("data").path("access_token").asText();
                assertEquals(3, access.split("\\.").length);
                assertEquals("7", logic.getExtra(access, "tenant_id"));
                assertEquals("11", String.valueOf(logic.getLoginIdByToken(access)));
                assertNotEquals(oldHash,admin.queryForObject("SELECT refresh_hash FROM nw_foundation_security_sessions WHERE id=701",String.class));
                assertEquals(Boolean.TRUE, cleaned.poll(10, TimeUnit.SECONDS));

                var me = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me"))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, me.statusCode(), me.body());
                assertEquals("7", new ObjectMapper().readTree(me.body()).path("data").path("tenant_id").asText());
                assertEquals(Boolean.TRUE, cleaned.poll(10, TimeUnit.SECONDS));

                var replay = client.send(refresh, HttpResponse.BodyHandlers.ofString());
                assertEquals(401, replay.statusCode(), replay.body());
                assertEquals(Boolean.TRUE, cleaned.poll(10, TimeUnit.SECONDS));
            }
        } finally {
            StpUtil.setStpLogic(previousLogic);
            SaManager.setConfig(previousConfig);
            SaManager.setSaTokenContext(previousContext);
            SaManager.setSaTokenDao(previousDao);
            isolatedDao.destroy();
        }
    }

    @Test void noAuthorizationHeaderRefreshesThroughControllerAndRealRlsThenOldTokenIsRejectedAndContextsAreCleared() throws Exception {
        String raw=seedSession("valid");String oldHash=hash(raw);
        TenantContext.setTenantId("8"); RequestContext.setUserId("stale-user");
        mvc.perform(post("/api/v1/auth/refresh").header("X-Trace-Id", "refresh-http-success")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"7."+raw+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.tenant_id").value("7"))
                .andExpect(jsonPath("$.trace_id").value("refresh-http-success"))
                .andExpect(header().string("X-Trace-Id", "refresh-http-success"));
        stp.verify(StpUtil::checkLogin, never());
        assertNotEquals(oldHash,admin.queryForObject("SELECT refresh_hash FROM nw_foundation_security_sessions WHERE id=701",String.class)); assertContextsCleared();
        mvc.perform(post("/api/v1/auth/refresh").header("X-Trace-Id", "refresh-http-repeat")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"7."+raw+"\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.trace_id").value("refresh-http-repeat"));
        assertContextsCleared();
    }

    @Test void disabledMemberReturns401RetainsOldCredentialAndClearsContext() throws Exception {
        admin.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=11");
        String raw=seedSession("disabled");String oldHash=hash(raw);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"7."+raw+"\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(oldHash,admin.queryForObject("SELECT refresh_hash FROM nw_foundation_security_sessions WHERE id=701",String.class));
        assertContextsCleared();
    }

    @Test void signingFailureRollsBackUndeliveredCredentialRotationAndClearsRequestContext() throws Exception {
        String raw=seedSession("signing-failure");String oldHash=hash(raw);
        stp.when(() -> StpUtil.login(any(), any(SaLoginModel.class))).thenThrow(new IllegalStateException("test signing unavailable"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"7."+raw+"\"}"))
                .andExpect(status().isInternalServerError());
        assertEquals(oldHash,admin.queryForObject("SELECT refresh_hash FROM nw_foundation_security_sessions WHERE id=701",String.class)); assertContextsCleared();
    }

    private void assertContextsCleared() {
        assertNull(TenantContext.getTenantId()); assertNull(RequestContext.getUserId()); assertNull(RequestContext.getTraceId());
    }
    private String seedSession(String label) throws Exception {
        String raw=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8)));
        String credential=hash(admin.queryForObject("SELECT password_hash FROM nw_tenant_members WHERE id=11",String.class));
        admin.update("DELETE FROM nw_foundation_security_sessions WHERE id=701");
        admin.update("INSERT INTO nw_foundation_security_sessions(id,scope_key,member_id,credential_hash,refresh_hash,state,device_label,expires_at) VALUES(701,'tenant:7',11,?,?,'full','http test',now()+interval '1 hour')",credential,hash(raw));
        return raw;
    }
    private static String hash(String raw) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
}
