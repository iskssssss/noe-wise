package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.auth.ChannelTokenAuthenticator;
import com.skillforge.institution.auth.OpenApiAuthenticator;
import com.skillforge.institution.auth.ServicePrincipalAuthenticator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;

/**
 * 外部认证拦截器单元测试。
 *
 * <p>覆盖三路分流：B 端 JWT 跳过、渠道令牌路径、API Key 路径和无凭证回退 401。
 */
class ExternalAuthInterceptorTest {

    @Test void institutionManagementUsesOnlyExactPathsAndFixedServicePrincipalAuthentication() throws Exception {
        assertFalse(ExternalAuthPaths.isInstitutionManagementPath("/api/v1/tenant/sub-institutions/extra"));
        assertFalse(ExternalAuthPaths.OPEN_PATHS.contains("/api/v1/tenant/**"));
        try(var mocked=mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            for(String path:ExternalAuthPaths.INSTITUTION_MANAGEMENT_PATHS) {
                assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));
                var signed=new org.springframework.mock.web.MockHttpServletRequest("POST",path);
                signed.addHeader("X-Api-Key","institution-key");signed.addHeader("X-Timestamp","123");signed.addHeader("X-Signature","signature");
                var cached=new CachedBodyHttpServletRequest(signed,1024);
                assertTrue(interceptor.preHandle(cached,response,null));
                verify(servicePrincipalAuthenticator).authenticate(eq("institution-key"),eq("123"),eq("signature"),eq(cached),aryEq(new byte[0]));
                var channel=new org.springframework.mock.web.MockHttpServletRequest("POST",path);channel.addHeader("X-Channel-Token","terminal");
                assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ApiException.class,()->interceptor.preHandle(channel,response,null)).getStatus());
            }
            verifyNoInteractions(channelTokenAuthenticator,openApiAuthenticator);
        }
    }

    @Test
    void signedAgentWritePreservesBodyAndCredentialTenantThroughRoleCheck() throws Exception {
        var channels = mock(com.skillforge.core.channel.mapper.ChannelMapper.class);
        var crypto = mock(com.skillforge.core.foundation.application.CryptoService.class);
        var channel = new com.skillforge.core.channel.model.ChannelDO();
        channel.setId(2001L);
        channel.setTenantId(1001L);
        channel.setChannelType("openapi");
        channel.setConfig(java.util.Map.of());
        channel.setApiSecretEncrypted("encrypted");
        when(channels.selectForAuthByApiKeyHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(channel);
        when(crypto.decrypt("encrypted")).thenReturn("test-secret");
        var auth = new ExternalAuthInterceptor(channelTokenAuthenticator, new OpenApiAuthenticator(channels, crypto), servicePrincipalAuthenticator);
        String body = "{\"agent_id\":\"42\",\"payload\":{\"name\":\"updated\"}}";
        var raw = new org.springframework.mock.web.MockHttpServletRequest("PATCH", "/api/v1/agents/update");
        raw.setContent(body.getBytes(StandardCharsets.UTF_8));
        raw.addHeader("X-Tenant-ID", "9999");
        raw.addHeader("X-Api-Key", "integration-key");
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        raw.addHeader("X-Timestamp", timestamp);
        raw.addHeader("X-Signature", com.skillforge.institution.security.HmacSignatureVerifier.sign("test-secret",
                com.skillforge.institution.security.HmacSignatureVerifier.canonical(timestamp, "PATCH", raw.getRequestURI(), body)));
        var cached = new CachedBodyHttpServletRequest(raw, 8 * 1024 * 1024);
        raw.addHeader("X-Trace-Id", "signed-agent-write");
        var tenant = new TenantContextInterceptor();
        var controller = mock(com.skillforge.institution.controller.AgentController.class);
        var handler = new org.springframework.web.method.HandlerMethod(controller,
                com.skillforge.institution.controller.AgentController.class.getMethod("updateAgentEndpoint",
                        com.skillforge.institution.controller.AgentController.UpdateAgentCommand.class));
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            new TraceIdFilter(mock(org.springframework.web.servlet.HandlerExceptionResolver.class))
                    .doFilter(cached, response, (req, res) -> {
                try {
                    assertTrue(auth.preHandle(cached, response, handler));
                    assertTrue(tenant.preHandle(cached, response, handler));
                    assertTrue(new PermissionCheckInterceptor(org.mockito.Mockito.mock(com.skillforge.core.foundation.access.FoundationAccess.class)).preHandle(cached, response, handler));
                    assertEquals("1001", TenantContext.getTenantId());
                    assertEquals(body, new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception failure) {
                    throw new jakarta.servlet.ServletException(failure);
                }
            });
            org.junit.jupiter.api.Assertions.assertNull(TenantContext.getTenantId());
        }
    }

    @Test
    void documentedManagementPathsRejectTerminalTokensAndAcceptSignedOpenApi() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            for (String path : ExternalAuthPaths.SIGNED_OPENAPI_PATHS) {
                assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));
                var terminal = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
                terminal.addHeader("X-Channel-Token", "terminal-token");
                assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                        () -> interceptor.preHandle(terminal, response, null)).getStatus());
                var signed = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
                signed.addHeader("X-Api-Key", "integration-key");
                signed.addHeader("X-Timestamp", "123");
                signed.addHeader("X-Signature", "signature");
                assertTrue(interceptor.preHandle(signed, response, null));
                verify(openApiAuthenticator).authenticate("integration-key", "123", "signature", signed, "");
            }
            verifyNoInteractions(channelTokenAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /** 渠道令牌认证器 Mock。 */
    private ChannelTokenAuthenticator channelTokenAuthenticator;

    /** OpenAPI 认证器 Mock。 */
    private OpenApiAuthenticator openApiAuthenticator;

    private ServicePrincipalAuthenticator servicePrincipalAuthenticator;

    /** 被测拦截器。 */
    private ExternalAuthInterceptor interceptor;

    /** 请求 Mock。 */
    private HttpServletRequest request;

    /** 响应 Mock。 */
    private HttpServletResponse response;

    /**
     * 初始化拦截器与 Mock。
     */
    @BeforeEach
    void setUp() {
        channelTokenAuthenticator = mock(ChannelTokenAuthenticator.class);
        openApiAuthenticator = mock(OpenApiAuthenticator.class);
        servicePrincipalAuthenticator = mock(ServicePrincipalAuthenticator.class);
        interceptor = new ExternalAuthInterceptor(
                channelTokenAuthenticator, openApiAuthenticator, servicePrincipalAuthenticator);
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/chat");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        response = mock(HttpServletResponse.class);
    }

    /**
     * 清理线程上下文，避免测试间串扰。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ChannelContext.clear();
    }

    /**
     * B 端已登录（JWT）应直接放行，不触发外部认证。
     */
    @Test
    void bSideJwtSkipsExternalAuth() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(true);
            assertTrue(interceptor.preHandle(request, response, null));
            verifyNoInteractions(channelTokenAuthenticator, openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /** 两端限额查询共享外部聊天路径，仍要求渠道认证而不取得管理接口权限。 */
    @Test
    void chatLimitsPathUsesSharedAllowlistAndExistingChannelAuthentication() throws Exception {
        String path = "/api/v1/chat/limits";
        assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));
        assertFalse(ExternalAuthPaths.isSignedOpenApiPath(path));
        var terminal = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
        terminal.addHeader("X-Channel-Token", "terminal-token");
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            assertTrue(interceptor.preHandle(terminal, response, null));
            verify(channelTokenAuthenticator).authenticate("terminal-token", terminal);
            verifyNoInteractions(openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    @Test
    void taskDetailPathUsesChannelAuthenticationAndRejectsMissingCredentials() throws Exception {
        String path = "/api/v1/chat/work/tasks/detail";
        assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));
        assertFalse(ExternalAuthPaths.isSignedOpenApiPath(path));
        assertFalse(ExternalAuthPaths.OPEN_PATHS.contains("/api/v1/chat/work/tasks/**"));
        var terminal = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
        terminal.addHeader("X-Channel-Token", "terminal-token");
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            assertTrue(interceptor.preHandle(terminal, response, null));
            verify(channelTokenAuthenticator).authenticate("terminal-token", terminal);
            var anonymous = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
            mocked.when(StpUtil::checkLogin).thenThrow(new NotLoginException("未登录", "login", "token"));
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(anonymous, response, null));
            mocked.verify(StpUtil::checkLogin);
            verifyNoInteractions(openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /** 工作区状态属于终端聊天读取，必须进入共享开放清单并仍执行渠道认证。 */
    @Test
    void workspaceStatusPathUsesChannelAuthentication() throws Exception {
        String path = "/api/v1/chat/sessions/workspace";
        assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));
        assertFalse(ExternalAuthPaths.isSignedOpenApiPath(path));
        var terminal = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
        terminal.setParameter("sessionId", "workspace-session");
        terminal.addHeader("X-Channel-Token", "terminal-token");
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            assertTrue(interceptor.preHandle(terminal, response, null));
            verify(channelTokenAuthenticator).authenticate("terminal-token", terminal);
            verifyNoInteractions(openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /**
     * 携带 X-Channel-Token 应走渠道令牌认证。
     */
    @Test
    void channelTokenPathAuthenticates() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            when(request.getHeader("X-Channel-Token")).thenReturn("ch_valid");
            when(request.getHeader("X-Api-Key")).thenReturn(null);

            assertTrue(interceptor.preHandle(request, response, null));
            verify(channelTokenAuthenticator).authenticate(eq("ch_valid"), eq(request));
            verifyNoInteractions(openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    @Test
    void runControlPathsAreChannelAuthenticatedOpenPaths() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            for (String path : java.util.List.of(
                    "/api/v1/chat/commands",
                    "/api/v1/chat/runs/collaboration",
                    "/api/v1/chat/runs/plan-mode",
                    "/api/v1/chat/runs/cancel",
                    "/api/v1/chat/runs/status", "/api/v1/chat/runs/delivery",
                    "/api/v1/chat/runs/budget",
                    "/api/v1/chat/goals", "/api/v1/chat/goals/create",
                    "/api/v1/chat/goals/pause", "/api/v1/chat/goals/resume")) {
                assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));
                assertFalse(ExternalAuthPaths.isSignedOpenApiPath(path));
                var channelRequest = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
                channelRequest.addHeader("X-Channel-Token", "ch_valid");

                assertTrue(interceptor.preHandle(channelRequest, response, null));
                verify(channelTokenAuthenticator).authenticate("ch_valid", channelRequest);
            }
            verify(channelTokenAuthenticator, times(11))
                    .authenticate(eq("ch_valid"), org.mockito.ArgumentMatchers.any());
            verifyNoInteractions(openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    @Test
    void runControlAcceptsSignedOpenApiAndPreservesCancellationBody() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            for (String operation : java.util.List.of("cancel", "status")) {
                String body = "cancel".equals(operation)
                        ? "{\"session_id\":\"session-1\",\"run_id\":\"run-1\"}" : "";
                var raw = new org.springframework.mock.web.MockHttpServletRequest(
                        "cancel".equals(operation) ? "POST" : "GET", "/api/v1/chat/runs/" + operation);
                raw.setContent(body.getBytes(StandardCharsets.UTF_8));
                raw.addHeader("X-Api-Key", "integration-key");
                raw.addHeader("X-Timestamp", "123");
                raw.addHeader("X-Signature", "signature");
                var cached = new CachedBodyHttpServletRequest(raw, 8 * 1024 * 1024);
                assertTrue(interceptor.preHandle(cached, response, null));
                verify(openApiAuthenticator).authenticate("integration-key", "123", "signature", cached, body);
            }
            verifyNoInteractions(channelTokenAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /**
     * 携带 X-Api-Key 应走 OpenAPI 认证并传入缓存请求体。
     */
    @Test
    void apiKeyPathAuthenticatesWithBody() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            String body = "{\"message\":\"hi\"}";
            CachedBodyHttpServletRequest wrapper = cachingWrapper(body);
            when(wrapper.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            when(wrapper.getHeader("X-Channel-Token")).thenReturn(null);
            when(wrapper.getHeader("X-Api-Key")).thenReturn("ak_valid");
            when(wrapper.getHeader("X-Timestamp")).thenReturn("1700000000");
            when(wrapper.getHeader("X-Signature")).thenReturn("sig");

            assertTrue(interceptor.preHandle(wrapper, response, null));
            verify(openApiAuthenticator).authenticate(
                    eq("ak_valid"), eq("1700000000"), eq("sig"), eq(wrapper), eq(body));
        }
    }

    @Test
    void openPlatformApiKeyUsesServicePrincipalInsteadOfChannelAuthenticator() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            String body = "{}";
            CachedBodyHttpServletRequest wrapper = cachingWrapper(body);
            when(wrapper.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            when(wrapper.getRequestURI()).thenReturn("/api/v1/open-platform/sessions/s1/events");
            when(wrapper.getHeader("X-Channel-Token")).thenReturn(null);
            when(wrapper.getHeader("X-Api-Key")).thenReturn("spk_valid");
            when(wrapper.getHeader("X-Timestamp")).thenReturn("1700000000");
            when(wrapper.getHeader("X-Signature")).thenReturn("sig");

            assertTrue(interceptor.preHandle(wrapper, response, null));

            verify(servicePrincipalAuthenticator).authenticate(
                    eq("spk_valid"), eq("1700000000"), eq("sig"), eq(wrapper), aryEq(body.getBytes(StandardCharsets.UTF_8)));
            verifyNoInteractions(openApiAuthenticator);
        }
    }

    /** 二进制SP请求把原字节交给认证器，包装器保留的正文仍能被Controller再次完整读取。 */
    @Test
    void servicePrincipalReceivesActualBinaryWithoutUtf8Decoding() throws Exception {
        byte[] body = {0, (byte) 0xff, (byte) 0xfe, (byte) 0xc3, 0x28};
        var raw = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/open-platform/sessions/media");
        raw.setContentType("application/octet-stream"); raw.setContent(body);
        raw.addHeader("X-Api-Key", "spk_valid"); raw.addHeader("X-Timestamp", "1700000000"); raw.addHeader("X-Signature", "sig");
        var cached = new CachedBodyHttpServletRequest(raw, 1024);
        assertTrue(interceptor.preHandle(cached, response, null));
        verify(servicePrincipalAuthenticator).authenticate(eq("spk_valid"), eq("1700000000"), eq("sig"), eq(cached), aryEq(body));
        org.junit.jupiter.api.Assertions.assertArrayEquals(body, cached.getInputStream().readAllBytes());
        verifyNoInteractions(channelTokenAuthenticator, openApiAuthenticator);
    }

    /** 缺少原字节缓存时连无体SP请求也拒绝，旧multipart不能用空正文签名进入认证器。 */
    @Test
    void servicePrincipalRejectsUncachedAndMultipartRequestsWithoutEmptyFallback() {
        for (String type : java.util.List.of("application/octet-stream", "multipart/form-data; boundary=fixture")) {
            var raw = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/open-platform/sessions/attachments");
            raw.setContentType(type); raw.setContent(new byte[]{1, 2, 3}); raw.addHeader("X-Api-Key", "spk_valid");
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                    () -> interceptor.preHandle(raw, response, null)).getStatus());
        }
        verifyNoInteractions(servicePrincipalAuthenticator, openApiAuthenticator, channelTokenAuthenticator);
    }

    @Test
    void openPlatformRejectsChannelTokenBeforeAnyChannelAuthentication() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            when(request.getRequestURI()).thenReturn("/api/v1/open-platform/sessions/s1/events");
            when(request.getHeader("X-Channel-Token")).thenReturn("channel-token");

            ApiException failure = assertThrows(ApiException.class,
                    () -> interceptor.preHandle(request, response, null));

            assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatus());
            verifyNoInteractions(channelTokenAuthenticator, openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /**
     * 无任何凭证应回退登录校验（抛 NotLoginException 由全局处理器转 401）。
     */
    @Test
    void noCredentialFallsBackToCheckLogin() throws Exception {
        try (var mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            mocked.when(StpUtil::checkLogin).thenThrow(
                    new NotLoginException("未登录", "login", "token"));
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            when(request.getHeader("X-Channel-Token")).thenReturn(null);
            when(request.getHeader("X-Api-Key")).thenReturn(null);

            assertThrows(NotLoginException.class,
                    () -> interceptor.preHandle(request, response, null));
            verifyNoInteractions(channelTokenAuthenticator, openApiAuthenticator, servicePrincipalAuthenticator);
        }
    }

    /**
     * 异步 dispatch（SSE 子请求）应直接放行。
     */
    @Test
    void asyncDispatchSkips() throws Exception {
        when(request.getDispatcherType()).thenReturn(DispatcherType.ASYNC);
        assertTrue(interceptor.preHandle(request, response, null));
        verifyNoInteractions(channelTokenAuthenticator, openApiAuthenticator, servicePrincipalAuthenticator);
    }

    /**
     * 构造带缓存请求体的 CachedBodyHttpServletRequest Mock。
     *
     * @param body 请求体
     * @return 包装后的请求 Mock
     */
    private CachedBodyHttpServletRequest cachingWrapper(String body) {
        CachedBodyHttpServletRequest wrapper = mock(CachedBodyHttpServletRequest.class);
        when(wrapper.getRequestURI()).thenReturn("/api/v1/chat");
        when(wrapper.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(wrapper.getCachedBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return wrapper;
    }
}
