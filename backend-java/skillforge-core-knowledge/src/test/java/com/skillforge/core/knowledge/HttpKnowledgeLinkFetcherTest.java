package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.sandbox.SandboxGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpKnowledgeLinkFetcherTest {

    private HttpServer server;
    private SandboxGuard sandboxGuard;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        sandboxGuard = mock(SandboxGuard.class);
        when(sandboxGuard.assertSafe(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsOnlyBoundedUtf8Body() {
        server.createContext("/body", exchange -> {
            byte[] body = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpKnowledgeLinkFetcher fetcher = fetcher();
        KnowledgeLinkFetcher.FetchResult result = fetcher.fetch(baseUrl + "/body", 4, java.time.Duration.ofSeconds(120));

        assertEquals(200, result.statusCode());
        assertEquals("0123", result.body());
        assertTrue(result.truncated());
        assertTrue(result.successful());
    }

    @Test
    void characterBudgetPreservesCompleteSupplementaryCodepoints() {
        server.createContext("/unicode", exchange -> {
            byte[] body = "A😀B".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var result = fetcher().fetch(baseUrl + "/unicode", 2, java.time.Duration.ofSeconds(120));
        assertEquals("A😀", result.body());
        assertEquals(2, result.body().codePointCount(0, result.body().length()));
        assertTrue(result.truncated());
    }

    @Test
    void doesNotFollowRedirects() {
        AtomicInteger targetCalls = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        KnowledgeLinkFetcher.FetchResult result = fetcher().fetch(baseUrl + "/redirect", 100, java.time.Duration.ofSeconds(120));

        assertEquals(302, result.statusCode());
        assertTrue(result.redirected());
        assertFalse(result.successful());
        assertEquals(0, targetCalls.get());
    }

    @Test
    void preservesNonSuccessfulStatusWithoutRaising() {
        server.createContext("/denied", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });

        KnowledgeLinkFetcher.FetchResult result = fetcher().fetch(baseUrl + "/denied", 100, java.time.Duration.ofSeconds(120));

        assertEquals(403, result.statusCode());
        assertFalse(result.successful());
    }

    @Test
    void sandboxRejectionPreventsNetworkCall() {
        String url = baseUrl + "/blocked";
        doThrow(new IllegalArgumentException("blocked"))
                .when(sandboxGuard).assertSafe(url);

        assertThrows(IllegalArgumentException.class, () -> fetcher().fetch(url, 100, java.time.Duration.ofSeconds(120)));
    }

    private HttpKnowledgeLinkFetcher fetcher() {
        return new HttpKnowledgeLinkFetcher(RestClient.builder(), sandboxGuard);
    }
}
