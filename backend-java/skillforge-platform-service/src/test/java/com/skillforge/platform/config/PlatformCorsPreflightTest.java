package com.skillforge.platform.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.security.FoundationSecurityInterceptor;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.platform.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 平台生产 MVC/CORS/认证拦截链的最小组合实验，不启动端口、数据库或 Redis。
 *
 * <p>Sa-Token/JWT 和 Servlet 派发真实执行；业务持久依赖仅作为受控前置。
 * 捕获异常类型与源位置，不打印异常 message、token 或正文；不能替代完整业务宿主和浏览器验收。</p>
 */
@ResourceLock("sa-token-global")
class PlatformCorsPreflightTest {
    private static final String ORIGIN = "http://localhost:3001";
    private static final String PATH = "/api/v1/platform/auth/me";
    private SaTokenConfig previousConfig;
    private SaTokenContext previousContext;
    private SaTokenDao previousDao;
    private StpLogic previousLogic;
    private SaTokenDaoDefaultImpl ownedDao;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    /** 保存并恢复库全局引用，测试只使用本实例内存会话，不借用已有登录状态。 */
    @BeforeEach
    void openOwnedServletFixture() {
        previousConfig = SaManager.getConfig();
        previousContext = SaManager.getSaTokenContext();
        previousDao = SaManager.getSaTokenDao();
        previousLogic = StpUtil.getStpLogic();
        var config = new SaTokenConfig().setTokenName("X-Platform-Token").setTokenPrefix("Bearer")
                .setJwtSecretKey("cors-owned-fixture-jwt-secret-key-20261012")
                .setTimeout(600).setIsReadBody(false).setIsReadCookie(false).setIsReadHeader(true)
                .setIsWriteHeader(false).setIsPrint(false);
        ownedDao = new SaTokenDaoDefaultImpl();
        SaManager.setConfig(config);
        SaManager.setSaTokenDao(ownedDao);
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ServletFixture.class);
        context.refresh();
        var logic = context.getBean(StpLogic.class);
        logic.setConfig(config);
        StpUtil.setStpLogic(logic);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** 关闭本测试的会话缓存线程和容器，并恢复调用前所有全局引用。 */
    @AfterEach
    void closeOwnedServletFixture() {
        if (context != null) context.close();
        if (ownedDao != null) ownedDao.timedCache.endRefreshThread();
        if (previousLogic != null) StpUtil.setStpLogic(previousLogic);
        if (previousContext != null) SaManager.setSaTokenContext(previousContext);
        if (previousDao != null) SaManager.setSaTokenDao(previousDao);
        if (previousConfig != null) SaManager.setConfig(previousConfig);
        RequestContextHolder.resetRequestAttributes();
        RequestContext.clear();
        TenantContext.clear();
    }

    /**
     * 标准预检不携带 token；随后实际 GET 使用同一自有登录会话，并保留未认证 GET 拒绝对照。
     * 先完成三次观察再断言，让失败预检也保留真实异常因果及认证 GET 的独立结果。
     */
    @ParameterizedTest
    @CsvSource({
            "http://localhost:3001,/api/v1/platform/auth/me",
            "http://127.0.0.1:3001,/api/v1/platform/auth/me",
            "http://localhost:3001,/api/v1/admin/foundation/catalog/preferences",
            "http://127.0.0.1:3001,/api/v1/admin/foundation/catalog/preferences"
    })
    void standardPreflightAndSameOwnedSessionGetKeepCorsAndAuthentication(String origin, String path) throws Exception {
        var login = mvc.perform(post("/api/v1/platform/auth/login")).andReturn();
        assertEquals(200, login.getResponse().getStatus());
        String token = login.getResponse().getContentAsString();
        assertFalse(token.isBlank());
        var causes = context.getBean(SafeCauses.class);
        var probe = context.getBean(ProbeController.class);
        var preflight = observe(options(path).header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-platform-token"), causes);
        int afterPreflight = probe.reads.get();
        var authenticated = observe(get(path).header(HttpHeaders.ORIGIN, origin)
                .header("X-Platform-Token", "Bearer " + token), causes);
        var unauthenticated = observe(get(path).header(HttpHeaders.ORIGIN, origin), causes);
        // 仅输出状态和安全栈位置，不把请求/响应正文或令牌传给断言报告。
        System.out.println("CORS_DIAGNOSTIC origin=" + origin + " path=" + path
                + " preflight_status=" + preflight.status()
                + " preflight_cause=" + preflight.cause()
                + " authenticated_get_status=" + authenticated.status()
                + " authenticated_get_cause=" + authenticated.cause()
                + " unauthenticated_get_status=" + unauthenticated.status()
                + " unauthenticated_get_cause=" + unauthenticated.cause());
        assertAll(
                () -> assertEquals("200", preflight.status(), preflight.cause()),
                () -> assertEquals(0, afterPreflight, "预检不能执行实际 GET 控制器"),
                () -> assertEquals("200", authenticated.status(), authenticated.cause()),
                () -> assertEquals("401", unauthenticated.status(), unauthenticated.cause()),
                () -> assertEquals(1, probe.reads.get(), "只有同会话已认证 GET 可以进入控制器"),
                () -> requireCors(preflight, origin),
                () -> requireCors(authenticated, origin),
                () -> requireCors(unauthenticated, origin),
                () -> assertTrue(preflight.result().getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)
                        .contains("GET")),
                () -> assertTrue(preflight.result().getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)
                        .toLowerCase(java.util.Locale.ROOT).contains("x-platform-token")),
                () -> assertTrue(authenticated.result().getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)
                        .toLowerCase(java.util.Locale.ROOT).contains("x-trace-id")));
    }

    /** 只带 OPTIONS 方法或缺少一个预检头时仍需认证，不能靠方法名绕过业务入口。 */
    @Test
    void nonPreflightOptionsRemainAuthenticated() {
        var causes = context.getBean(SafeCauses.class);
        for (var request : List.of(options(PATH), options(PATH).header(HttpHeaders.ORIGIN, ORIGIN),
                options(PATH).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))) {
            var response = observe(request, causes);
            assertEquals("401", response.status(), response.cause());
        }
        assertEquals(0, context.getBean(ProbeController.class).reads.get());
    }

    /** 单请求 Servlet 抛出也只保存安全因果，让其他对照继续执行，不伪造 HTTP 状态。 */
    private Observation observe(MockHttpServletRequestBuilder request, SafeCauses causes) {
        causes.latest = "NONE";
        try {
            return new Observation(mvc.perform(request).andReturn(), causes.latest);
        } catch (Exception failure) {
            causes.capture(failure);
            return new Observation(null, causes.latest);
        }
    }

    /**
     * 一次独立请求的实际返回或 Servlet 失败。
     * @param result 正常返回的 MVC 回执；null 表示 Servlet 抛出且没有 HTTP 回执
     * @param cause 仅包含异常类型和源位置的有界摘要
     */
    private record Observation(MvcResult result, String cause) {
        /** 抛出没有 HTTP 回执时单独报告 THREW，不能冒充网络 500。 */
        String status() { return result == null ? "THREW" : Integer.toString(result.getResponse().getStatus()); }
    }

    /** 检查生产 wildcard pattern 反射实际 Origin，并保留 credentials 响应语义。 */
    private static void requireCors(Observation observation, String origin) {
        assertNotNull(observation.result(), observation.cause());
        var result = observation.result();
        assertEquals(origin, result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    /** 实例诊断只保留类型和位置；resolver 返回 null，不取代生产错误映射。 */
    static final class SafeCauses {
        private String latest = "NONE";

        /** 只投影有界异常链和固定栈字段，故障消息与载荷始终留在异常对象内。 */
        void capture(Exception failure) {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            var chain = new ArrayList<String>();
            Throwable current = failure;
            while (current != null && chain.size() < 8 && seen.add(current)) {
                var locations = new ArrayList<String>();
                for (var frame : current.getStackTrace()) {
                    if (frame.getClassName().startsWith("com.skillforge.")
                            || frame.getClassName().startsWith("cn.dev33.satoken.")
                            || frame.getClassName().startsWith("org.springframework.web.servlet.")) {
                        locations.add(frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
                        if (locations.size() == 3) break;
                    }
                }
                chain.add(current.getClass().getName() + "@" + locations);
                current = current.getCause();
            }
            latest = String.join(" -> ", chain);
        }
    }

    /** 使用两个正式配置和真实共享拦截算法；仅为业务持久依赖提供本实例受控返回。 */
    @Configuration
    @EnableWebMvc
    @Import({WebConfig.class, SaTokenConfiguration.class})
    static class ServletFixture implements WebMvcConfigurer {
        private final SafeCauses causes;

        /** 注入当前测试独有的错误观察者。 */
        ServletFixture(SafeCauses causes) { this.causes = causes; }

        /** 在生产 advice 前观察真实 MVC 异常，继续沿原 resolver 链处理响应。 */
        @Override
        public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
            resolvers.addFirst((request, response, handler, failure) -> {
                causes.capture(failure);
                return null;
            });
        }

        /** 静态 bean 避免观察者与 MVC 配置构造形成循环依赖。 */
        @Bean static SafeCauses safeCauses() { return new SafeCauses(); }
        /** 仅为 Schema resolver 提供真实 JSON mapper。 */
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        /** 使用平台协议的 issuer/audience，密钥和会话由当前测试独立安装。 */
        @Bean SkillForgeProperties properties() {
            var properties = new SkillForgeProperties();
            properties.setJwtIssuer("skillforge-platform");
            properties.setJwtAudience("skillforge-platform-api");
            return properties;
        }
        /** 仅已认证固定平台成员的业务授权前置，不改变 Sa-Token 登录校验。 */
        @Bean FoundationAccess access() {
            var access = mock(FoundationAccess.class);
            when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "990099"));
            return access;
        }
        /** DB 会话读取由实例 mock 承接；生产认证和租户拦截算法仍实际执行。 */
        @Bean FoundationSecurityService security() { return mock(FoundationSecurityService.class, RETURNS_DEEP_STUBS); }
        /** 实际安全拦截器使用受控持久前置。 */
        @Bean FoundationSecurityInterceptor securityInterceptor(FoundationSecurityService security, FoundationAccess access) {
            return new FoundationSecurityInterceptor(security, access);
        }
        /** 实际权限声明拦截器，不替换预检处理器类型判断。 */
        @Bean PermissionCheckInterceptor permissions(FoundationAccess access) { return new PermissionCheckInterceptor(access); }
        /** 实际 JWT 上下文提取器。 */
        @Bean TenantContextInterceptor tenants() { return new TenantContextInterceptor(); }
        /** 受控 Redis adapter 不打开连接。 */
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        /** 固定正向限流前置，不让缺失配置替代认证或 CORS 因果。 */
        @Bean OperationalSettingsService settings() {
            var settings = mock(OperationalSettingsService.class, RETURNS_DEEP_STUBS);
            when(settings.read("rate_limits").integer("requests_per_minute_default")).thenReturn(1000);
            return settings;
        }
        /** 实际限流拦截算法，Redis 返回空计数不产生过载。 */
        @Bean RateLimitInterceptor rateLimits(StringRedisTemplate redis, OperationalSettingsService settings) {
            return new RateLimitInterceptor(redis, settings);
        }
        /** 实际只写方法幂等检查。 */
        @Bean IdempotencyInterceptor idempotency(StringRedisTemplate redis) { return new IdempotencyInterceptor(redis); }
        /** 原公共异常 advice，不包装或吞掉观察到的原因。 */
        @Bean GlobalExceptionHandler errors(ObjectMapper json) { return new GlobalExceptionHandler(json); }
        /** 测试专用 login/GET 端点只证明认证传输边界，不作为生产诊断 API。 */
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    /** 最小真实 Servlet 控制器；记录实际 GET 次数，并使用原 StpLogic 建立独立会话。 */
    @RestController
    static class ProbeController {
        private final AtomicInteger reads = new AtomicInteger();

        /** 测试自有身份经真实 JWT/Sa-Token 登录，令牌仅在测试请求中使用。 */
        @PostMapping("/api/v1/platform/auth/login")
        String login() {
            StpUtil.login("990099", 600);
            return StpUtil.getTokenValue();
        }

        /** 实际读取继续要求登录；未认证调用不能增加控制器计数。 */
        @GetMapping({PATH, "/api/v1/admin/foundation/catalog/preferences"})
        Map<String, String> me(HttpServletResponse response) {
            String member = StpUtil.getLoginIdAsString();
            reads.incrementAndGet();
            response.setHeader("X-Trace-Id", "cors-owned-fixture-trace");
            return Map.of("member_id", member);
        }
    }
}
