package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebCorsFilterTest {
    @Test void preflightDoesNotEnterBusinessInterceptorsAndActualRequestStillContinues() throws Exception {
        var config = new WebConfig(mock(PermissionCheckInterceptor.class), mock(com.skillforge.core.foundation.security.FoundationSecurityInterceptor.class), mock(RateLimitInterceptor.class),
                mock(IdempotencyInterceptor.class), "http://127.0.0.1:13200", "http://preview.local");
        var filter = config.corsFilter().getFilter();
        var preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/chat");
        preflight.addHeader("Origin", "http://127.0.0.1:13200");
        preflight.addHeader("Access-Control-Request-Method", "POST");
        preflight.addHeader("Access-Control-Request-Headers", "X-Channel-Token,Content-Type");
        var response = new MockHttpServletResponse();var called = new AtomicBoolean();
        filter.doFilter(preflight, response, (request, reply) -> called.set(true));
        assertFalse(called.get());assertEquals(200, response.getStatus());
        assertEquals("http://127.0.0.1:13200", response.getHeader("Access-Control-Allow-Origin"));
        var actual = new MockHttpServletRequest("GET", "/api/v1/chat/history");
        actual.addHeader("Origin", "http://127.0.0.1:13200");
        filter.doFilter(actual, new MockHttpServletResponse(), (request, reply) -> called.set(true));
        assertTrue(called.get());
        var denied = new MockHttpServletRequest("OPTIONS", "/api/v1/chat");
        denied.addHeader("Origin", "http://other.local");denied.addHeader("Access-Control-Request-Method", "POST");
        var deniedReply = new MockHttpServletResponse();
        filter.doFilter(denied, deniedReply, (request, reply) -> fail("unlisted origin continued"));
        assertEquals(403, deniedReply.getStatus());
    }
}
