package com.skillforge.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 请求头不能建立身份，正式认证结果是共享上下文映射的唯一来源。 */
class HostTenantContextInterceptorTest {
    private final HostTenantContextInterceptor interceptor = new HostTenantContextInterceptor();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        ChannelContext.clear();
        ServicePrincipalContext.clear();
        RequestObservability.clear();
    }

    @Test
    void unauthenticatedHeadersCannotCreateTenantOrUserIdentity() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(false);

            interceptor.preHandle(forgedRequest(), new MockHttpServletResponse(), new Object());

            assertThat(TenantContext.getTenantId()).isNull();
            assertThat(RequestContext.getUserId()).isNull();
        }
    }

    @Test
    void authenticatedJwtClaimsOverrideForgedIdentityHeaders() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(true);
            login.when(() -> StpUtil.getExtra("tenant_id")).thenReturn("7");
            login.when(StpUtil::getLoginIdAsString).thenReturn("member-1");

            interceptor.preHandle(forgedRequest(), new MockHttpServletResponse(), new Object());

            assertThat(TenantContext.getTenantId()).isEqualTo("7");
            assertThat(RequestContext.getUserId()).isEqualTo("member-1");
        }
    }

    @Test
    void tenantlessPlatformJwtCannotAcquireTenantFromHeaders() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(true);
            login.when(StpUtil::getLoginIdAsString).thenReturn("platform-operator");

            interceptor.preHandle(forgedRequest(), new MockHttpServletResponse(), new Object());

            assertThat(TenantContext.getTenantId()).isNull();
            assertThat(RequestContext.getUserId()).isEqualTo("platform-operator");
        }
    }

    @Test
    void verifiedExternalIdentitySurvivesJwtMappingWithoutHeaderOverride() {
        TenantContext.setTenantId("7");
        ChannelContext.setChannelId("channel-1");
        var principal = new ServicePrincipalContext.Identity(
                "principal-1", "7", "integration-1", 1, Set.of("sessions:read"));
        ServicePrincipalContext.set(principal);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(false);

            interceptor.preHandle(forgedRequest(), new MockHttpServletResponse(), new Object());

            assertThat(TenantContext.getTenantId()).isEqualTo("7");
            assertThat(ChannelContext.getChannelId()).isEqualTo("channel-1");
            assertThat(ServicePrincipalContext.get()).isSameAs(principal);
            assertThat(RequestContext.getUserId()).isNull();
        }
    }

    private MockHttpServletRequest forgedRequest() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", "9999");
        request.addHeader("X-User-ID", "forged-user");
        request.addHeader("X-Role", "platform_admin");
        return request;
    }
}
