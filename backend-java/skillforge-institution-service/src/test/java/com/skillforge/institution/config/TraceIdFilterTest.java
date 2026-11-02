package com.skillforge.institution.config;

import com.skillforge.core.shared.web.RequestObservability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.institution.auth.ChannelAuthContext;
import com.skillforge.institution.auth.ChannelTokenAuthenticator;
import com.skillforge.institution.auth.OpenApiAuthenticator;
import com.skillforge.institution.auth.ServicePrincipalAuthenticator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 验证请求线程身份在 SSE 交接、异常及后续派发之间隔离。 */
class TraceIdFilterTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        ChannelContext.clear();
        ServicePrincipalContext.clear();
        RequestObservability.clear();
    }

    @Test
    void sseHandoffReleasesChannelBeforeNextJwtRequest() throws Exception {
        var channels = mock(ChannelTokenAuthenticator.class);
        var channel = new ChannelDO();
        channel.setId(2001L);
        channel.setTenantId(1001L);
        doAnswer(call -> {
            ChannelAuthContext.inject(channel, call.getArgument(1), ExternalAuthPaths.SOURCE_CHANNEL);
            return null;
        }).when(channels).authenticate(eq("test-channel"), any());
        var controller = new IdentityController();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter())
                .addInterceptors(new ExternalAuthInterceptor(channels, mock(OpenApiAuthenticator.class),
                                mock(ServicePrincipalAuthenticator.class)),
                        new TenantContextInterceptor())
                .build();

        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(false);
            var stream = mvc.perform(get("/stream")
                            .header("X-Channel-Token", "test-channel")
                            .header("X-Trace-Id", "stream-trace"))
                    .andExpect(request().asyncStarted()).andReturn();
            try {
                assertEquals("1001:2001", controller.streamIdentity);
                // SSE 尚未完成，原请求线程已可安全承接管理员请求。
                assertThreadCleared();
                login.when(StpUtil::isLogin).thenReturn(true);
                login.when(() -> StpUtil.getExtra("tenant_id")).thenReturn("3001");
                login.when(StpUtil::getLoginIdAsString).thenReturn("admin");
                mvc.perform(get("/identity").header("X-Trace-Id", "admin-trace"))
                        .andExpect(status().isOk())
                        .andExpect(content().string("3001:admin:null:null"));
                assertThreadCleared();
            } finally {
                controller.emitter.complete();
                mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
            }
            assertThreadCleared();
        }
    }

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"REQUEST", "ASYNC", "ERROR"})
    void eachDispatchStartsAndEndsWithoutPreviousIdentity(DispatcherType dispatcherType) throws Exception {
        seedIdentity();
        var request = new MockHttpServletRequest();
        request.setDispatcherType(dispatcherType);
        request.addHeader("X-Trace-Id", "current-trace");
        filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertIdentityCleared();
            assertNull(RequestContext.getUserId());
            assertEquals("current-trace", RequestContext.getTraceId());
            assertEquals("", MDC.get("tenant_id"));
            seedIdentity();
        });
        assertThreadCleared();
    }

    @Test
    void authenticationFailureBeforeMvcCompletionStillClearsAllIdentity() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "failed-auth");
        var failure = assertThrows(ServletException.class,
                () -> filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    seedIdentity();
                    throw new ServletException("authentication failed before tenant interceptor");
                }));
        assertEquals("authentication failed before tenant interceptor", failure.getMessage());
        assertThreadCleared();
    }

    @Test
    void asyncRedispatchKeepsRequestTraceWithoutRestoringOldIdentity() throws Exception {
        var filter = filter();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "original-trace");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seedIdentity());
        assertThreadCleared();
        request.setDispatcherType(DispatcherType.ASYNC);
        request.removeHeader("X-Trace-Id");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertEquals("original-trace", RequestContext.getTraceId());
            assertIdentityCleared();
        });
        assertThreadCleared();
    }

    private static void seedIdentity() {
        TenantContext.setTenantId("old-tenant");
        ChannelContext.setChannelId("old-channel");
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "old-principal", "old-tenant", "old-integration", 1L, Set.of("sessions:read")));
        RequestContext.setUserId("old-user");
        RequestContext.setTraceId("old-trace");
        RequestObservability.syncMdc();
    }

    private static TraceIdFilter filter() {
        return new TraceIdFilter(mock(org.springframework.web.servlet.HandlerExceptionResolver.class));
    }

    private static void assertIdentityCleared() {
        assertNull(TenantContext.getTenantId());
        assertNull(ChannelContext.getChannelId());
        assertNull(ServicePrincipalContext.get());
    }

    private static void assertThreadCleared() {
        assertIdentityCleared();
        assertNull(RequestContext.getTraceId());
        assertNull(RequestContext.getUserId());
        assertNull(MDC.get("trace_id"));
        assertNull(MDC.get("tenant_id"));
    }

    @RestController
    static class IdentityController {
        private final SseEmitter emitter = new SseEmitter(5_000L);
        private String streamIdentity;

        @GetMapping("/stream")
        SseEmitter stream() {
            streamIdentity = TenantContext.getTenantId() + ":" + ChannelContext.getChannelId();
            return emitter;
        }

        @GetMapping("/identity")
        String identity() {
            return TenantContext.getTenantId() + ":" + RequestContext.getUserId() + ":"
                    + ChannelContext.getChannelId() + ":" + ServicePrincipalContext.get();
        }
    }
}
