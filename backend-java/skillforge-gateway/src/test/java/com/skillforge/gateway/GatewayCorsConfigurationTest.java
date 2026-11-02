package com.skillforge.gateway;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class GatewayCorsConfigurationTest {
    private final GatewayCorsConfiguration config = new GatewayCorsConfiguration();
    @Test void handlesAllowedPreflightBeforeRouteAndRejectsUnlistedOrigin() {
        var filter = config.gatewayCorsFilter("http://127.0.0.1:13200", "http://preview.local");
        var called = new AtomicBoolean();
        var allowed = MockServerWebExchange.from(MockServerHttpRequest.options("http://gateway.local/api/v1/chat")
                .header("Origin", "http://127.0.0.1:13200").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Channel-Token,Content-Type"));
        filter.filter(allowed, exchange -> { called.set(true); return Mono.empty(); }).block();
        assertFalse(called.get());
        assertEquals("http://127.0.0.1:13200", allowed.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
        var denied = MockServerWebExchange.from(MockServerHttpRequest.options("http://gateway.local/api/v1/chat")
                .header("Origin", "http://other.local").header("Access-Control-Request-Method", "POST"));
        filter.filter(denied, exchange -> { called.set(true); return Mono.empty(); }).block();
        assertEquals(403, denied.getResponse().getStatusCode().value());
        assertFalse(called.get());
    }
    @Test void actualRequestsContinueToAuthenticationAndUnsafeOriginsCannotBeConfigured() {
        var called = new AtomicBoolean();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://gateway.local/api/v1/chat/history")
                .header("Origin", "http://127.0.0.1:13200"));
        config.gatewayCorsFilter("http://127.0.0.1:13200", "").filter(exchange, request -> {
            called.set(true); request.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED); return Mono.empty();
        }).block();
        assertTrue(called.get());assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> config.gatewayCorsFilter("*", ""));
        assertThrows(IllegalArgumentException.class, () -> config.gatewayCorsFilter("null", ""));
        assertThrows(IllegalArgumentException.class, () -> config.gatewayCorsFilter("http://preview.local", "http://preview.local"));
    }
}
