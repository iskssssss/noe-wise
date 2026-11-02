package com.skillforge.runtime.adapter.model.openai;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedDiscovery;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiModelDiscoveryClientTest {

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void discoversSortedUniqueModelsWithoutInferringCapabilities() {
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"object\":\"list\",\"data\":["
                    + "{\"id\":\"qwen-plus\",\"owned_by\":\"aliyun\"},"
                    + "{\"id\":\"deepseek-chat\",\"owned_by\":\"deepseek\"},"
                    + "{\"id\":\"qwen-plus\",\"owned_by\":\"duplicate\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var client = new OpenAiModelDiscoveryClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), 16_384);

        var models = client.discover(target(), "temporary-key", Duration.ofSeconds(2));

        assertEquals("Bearer temporary-key", authorization.get());
        assertEquals(2, models.size());
        assertEquals("deepseek-chat", models.get(0).modelId());
        assertEquals("qwen-plus", models.get(1).modelId());
        assertEquals("aliyun", models.get(1).ownedBy());
    }

    @Test
    void rejectsRedirectsAndDoesNotReadThemAsModelLists() {
        server.createContext("/models", exchange -> {
            exchange.getResponseHeaders().add("Location", endpoint + "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        var client = new OpenAiModelDiscoveryClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), 16_384);

        var failure = assertThrows(
                OpenAiModelDiscoveryClient.ModelDiscoveryException.class,
                () -> client.discover(target(), "temporary-key", Duration.ofSeconds(2)));

        assertEquals("model_discovery_http_302", failure.getMessage());
    }

    @Test void preservesDynamicDeepSeekStyleFactsWithoutRecognizingTheModelName() {
        server.createContext("/models", exchange -> {
            byte[] response = """
                    {"data":[{"id":"never-seen-model-99","owned_by":"new-owner","name":"A new model",
                     "context_window":1048576,"max_output_tokens":393216,
                     "input_modalities":["text","image"],"output_modalities":["text"],
                     "effort":{"supported_levels":["low","high","max"],"default_level":"high"},
                     "api_capabilities":{"anthropic_messages":{"system_prompt_update":"in-history","tool_update":"addition-only"}}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); }
            finally { exchange.close(); }
        });
        var models = new OpenAiModelDiscoveryClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), 16_384)
                .discover(target(), "temporary-key", Duration.ofSeconds(2));
        assertEquals("never-seen-model-99", models.getFirst().modelId());
        var metadata = models.getFirst().metadata().orElseThrow();
        assertEquals("A new model", metadata.name());
        assertEquals(1048576L, metadata.context().contextWindow());
        assertEquals(393216L, metadata.context().maxOutputTokens());
        assertEquals(java.util.List.of("low", "high", "max"), metadata.reasoning().supportedLevels());
        assertEquals("high", metadata.reasoning().defaultLevel());
        assertEquals("in-history", metadata.messages().systemPromptUpdate());
        assertEquals("addition-only", metadata.messages().toolUpdate());
        assertNull(metadata.features()); assertNull(metadata.capabilities());
    }

    @Test
    void rejectsMalformedProviderResponses() {
        server.createContext("/models", exchange -> {
            byte[] response = "{\"data\":[{\"owned_by\":\"missing-id\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var client = new OpenAiModelDiscoveryClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), 16_384);

        var failure = assertThrows(
                OpenAiModelDiscoveryClient.ModelDiscoveryException.class,
                () -> client.discover(target(), "temporary-key", Duration.ofSeconds(2)));

        assertEquals("model_discovery_response_invalid", failure.getMessage());
    }

    @Test
    void doesNotExposeMalformedProviderBodyInFailureChain() {
        server.createContext("/models", exchange -> {
            byte[] response = "provider-private-secret"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var client = new OpenAiModelDiscoveryClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), 16_384);

        var failure = assertThrows(
                OpenAiModelDiscoveryClient.ModelDiscoveryException.class,
                () -> client.discover(target(), "temporary-key", Duration.ofSeconds(2)));

        assertEquals("model_discovery_response_invalid", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("provider-private-secret"));
    }

    @Test
    void rejectsResponsesLargerThanTheConfiguredBound() {
        server.createContext("/models", exchange -> {
            byte[] response = "{\"data\":[{\"id\":\"too-large\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var client = new OpenAiModelDiscoveryClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), 8);

        var failure = assertThrows(
                OpenAiModelDiscoveryClient.ModelDiscoveryException.class,
                () -> client.discover(target(), "temporary-key", Duration.ofSeconds(2)));

        assertEquals("model_discovery_response_too_large", failure.getMessage());
    }

    @Test
    void totalDeadlineIncludesAStalledResponseBody() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch handlerStopped = new CountDownLatch(1);
        server.createContext("/models", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"data\":["
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                handlerStopped.countDown();
            }
        });
        var client = new OpenAiModelDiscoveryClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), 16_384);

        boolean started = false;
        try {
            var failure = assertThrows(
                    OpenAiModelDiscoveryClient.ModelDiscoveryException.class,
                    () -> client.discover(target(), "temporary-key",
                            Duration.ofMillis(500)));

            assertEquals("model_discovery_timeout", failure.getMessage());
            started = bodyStarted.await(1, TimeUnit.SECONDS);
            assertTrue(started);
        } finally {
            releaseBody.countDown();
            if (started) {
                assertTrue(handlerStopped.await(2, TimeUnit.SECONDS));
            }
        }
    }

    private ResolvedDiscovery target() {
        return new ResolvedDiscovery("provider", "service", "version",
                AuthenticationScheme.BEARER_API_KEY, endpoint,
                endpoint + "/models", "openai-model-list-v1",
                VerificationStatus.UNVERIFIED);
    }
}
