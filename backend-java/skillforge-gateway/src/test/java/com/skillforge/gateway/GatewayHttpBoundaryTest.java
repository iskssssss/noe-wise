package com.skillforge.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class GatewayHttpBoundaryTest {
    @Test
    void projectsOnlyGatewayFailuresWithAnExplicitNullDataAndSnakeCaseTrace() throws Exception {
        var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var boundary = new GatewayHttpBoundary(mapper);
        for (var failure : new Exception[] {new ConnectException("private upstream"), new TimeoutException("private endpoint")}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/echo").header("X-Trace-Id", "trace-test"));
            boundary.filter(exchange, current -> boundary.handle(current, failure)).block();
            var result = mapper.readTree(exchange.getResponse().getBodyAsString().block());
            assertTrue(result.has("data"));
            assertTrue(result.get("data").isNull());
            assertEquals("trace-test", result.path("trace_id").asText());
            assertFalse(result.has("traceId"));
            assertFalse(result.path("retryable").asBoolean());
            assertEquals(failure instanceof ConnectException ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_TIMEOUT", result.path("code").asText());
            assertFalse(result.toString().contains("private"));
        }
    }

    @Test
    void keepsTheUpstreamBodyAndTransportFailureAfterCommitUnwrapped() {
        var boundary = new GatewayHttpBoundary(new ObjectMapper());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
        boundary.filter(exchange, current -> current.getResponse().writeWith(Mono.just(
                current.getResponse().bufferFactory().wrap("event: result\ndata: done\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).block();
        assertTrue(exchange.getResponse().isCommitted());
        var failure = new ConnectException("after commit");
        var observed = assertThrows(RuntimeException.class, () -> boundary.handle(exchange, failure).block());
        assertSame(failure, reactor.core.Exceptions.unwrap(observed));
        assertEquals("event: result\ndata: done\n\n", exchange.getResponse().getBodyAsString().block());
    }
}
