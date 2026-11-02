package com.skillforge.core.foundation.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;
import com.skillforge.core.shared.web.HostTraceIdFilter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** 管理机器请求的认证域、完整正文及线程身份清理；不能以 JWT 或请求属性替代验签。 */
class ManagementAuthenticationInterceptorTest {
    @AfterEach void cleanup() { ManagementContext.clear(); ServicePrincipalContext.clear(); TenantContext.clear(); }

    @Test void authenticatesExactBodyAndQueryWithoutExecutionIdentity() throws Exception {
        var service = mock(PlatformManagementService.class);
        var request = request("POST", "/api/v1/platform/institutions/provision");
        request.setQueryString("mode=1&ref=a%2Fb");
        String body = "{\"name\":\"机构一\"}";
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        var identity = new ManagementContext.Identity("101", 1, 2);
        when(service.authenticate("management-key", "123", "signed", "POST",
                "/api/v1/platform/institutions/provision?mode=1&ref=a%2Fb", body)).thenReturn(identity);
        var cached = new CachedBodyHttpServletRequest(request, 1024);
        assertTrue(new ManagementAuthenticationInterceptor(service).preHandle(cached, new MockHttpServletResponse(), new Object()));
        assertEquals(identity, ManagementContext.get());
        assertTrue(ManagementAuthenticationInterceptor.verified(cached));
        assertNull(TenantContext.getTenantId());
        assertNull(ServicePrincipalContext.get());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), cached.getInputStream().readAllBytes());
    }

    @ParameterizedTest @CsvSource({
            "GET,/api/v1/open-platform/usage/events", "POST,/api/v1/chat",
            "POST,/api/v1/platform/management-principals", "GET,/api/v1/platform/institutions/provision",
            "GET,/api/v1/platform/agent-deployments/arbitrary", "POST,/api/v1/integration/tenants/provision"})
    void managementKeyCannotCrossExactMethodAndPathBoundary(String method, String path) throws Exception {
        var service = mock(PlatformManagementService.class);
        var request = new CachedBodyHttpServletRequest(request(method, path), 1024);
        assertThrows(ApiException.class, () -> new ManagementAuthenticationInterceptor(service)
                .preHandle(request, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(service);
        assertNull(ManagementContext.get());
        assertFalse(ManagementAuthenticationInterceptor.verified(request));
    }

    @Test void rejectsUncachedBodyAndDoesNotTrustSourceAttributeAlone() {
        var service = mock(PlatformManagementService.class);
        var raw = request("POST", "/api/v1/platform/institutions/provision");
        raw.setContentType("multipart/form-data; boundary=x");
        raw.setAttribute("AUTH_SOURCE", "MANAGEMENT");
        assertFalse(ManagementAuthenticationInterceptor.verified(raw));
        assertThrows(ApiException.class, () -> new ManagementAuthenticationInterceptor(service)
                .preHandle(raw, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(service);
    }

    @Test void outerHostFilterClearsIdentityAfterExceptionAndSuccess() throws Exception {
        var errors = mock(HandlerExceptionResolver.class);
        when(errors.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());
        var filter = new HostTraceIdFilter(errors);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/platform/institutions"), new MockHttpServletResponse(), (req,res) -> {
            ManagementContext.set(new ManagementContext.Identity("101", 1, 1));
            throw new IllegalStateException("controller failure");
        });
        assertNull(ManagementContext.get());
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/platform/institutions"), new MockHttpServletResponse(), (req,res) -> {
            assertNull(ManagementContext.get());
            ManagementContext.set(new ManagementContext.Identity("102", 1, 1));
        });
        assertNull(ManagementContext.get());
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Management-Key", "management-key");
        request.addHeader("X-Timestamp", "123");
        request.addHeader("X-Signature", "signed");
        return request;
    }
}
