package com.skillforge.core.model.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.resilience.ResilienceFixtures;
import com.skillforge.core.foundation.application.CryptoService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class EmbeddingClientTest {
    @Test void failureDiagnosticOnlyExposesAllowlistedCodesOrStructuredStatus() {
        assertEquals("platform_model_credential_unavailable", EmbeddingClient.safeFailureCode(
                new IllegalStateException("platform_model_credential_unavailable",
                        new IllegalArgumentException("secret-customer-payload"))));
        assertEquals("unclassified", EmbeddingClient.safeFailureCode(
                new IllegalStateException("Authorization: Bearer secret-provider-key")));
        assertEquals("unclassified", EmbeddingClient.safeFailureCode(new IllegalStateException()));
        assertEquals("database_42501", EmbeddingClient.safeFailureCode(
                new IllegalStateException("budget_storage_failed",
                        new java.sql.SQLException("private SQL and credentials", "42501"))));
        assertEquals("unclassified", EmbeddingClient.safeFailureCode(
                new java.sql.SQLException("private SQL", "secret-provider-key")));
        assertEquals("provider_http_401", EmbeddingClient.safeFailureCode(
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "secret-provider-response")));
    }

    @Test void cancellationBeforeDispatchDoesNotCreateAnExternalAttempt() {
        var http = mock(PlatformModelHttpSupport.class);
        var guard = mock(ExternalInvocationGuard.class);
        var costs = mock(CostRecorder.class);
        var client = new EmbeddingClient(http, ResilienceFixtures.withDefaultSettings(), costs, guard);
        var result = assertInstanceOf(EmbeddingClient.Outcome.Rejected.class,
                client.embedBatch("7", model(), "cancelled:original", List.of("one"),
                        Duration.ofMillis(250), () -> true));
        assertEquals("embedding_cancelled", result.reasonCode());
        verifyNoInteractions(http, guard, costs);
    }

    @Test void cancellationDuringPreflightDoesNotCreateAnExternalAttempt() {
        var http = mock(PlatformModelHttpSupport.class);
        var guard = mock(ExternalInvocationGuard.class);
        var costs = mock(CostRecorder.class);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var client = new EmbeddingClient(http, ResilienceFixtures.withDefaultSettings(), costs, guard);
        var result = assertInstanceOf(EmbeddingClient.Outcome.Rejected.class,
                client.embedBatch("7", model(), "cancelled:original", List.of("one"),
                        Duration.ofMillis(250), () -> checks.incrementAndGet() > 1));
        assertEquals("embedding_cancelled", result.reasonCode());
        verify(http).client(eq(model()), eq(Duration.ofMillis(250)));
        verifyNoInteractions(guard, costs);
    }

    @Test void pinnedResponseAlignsByIndexAndDoesNotExposeMutableStoredVectors() throws Exception {
        var fixture = response("""
                {"data":[{"index":1,"embedding":[3,4]},{"index":0,"embedding":[1,2]}],
                 "usage":{"prompt_tokens":9,"total_tokens":9}}
                """);
        var result = assertInstanceOf(EmbeddingClient.Outcome.Available.class,
                fixture.client.embedBatch("7", model(), "memory-query:original", List.of("first", "second")));
        assertArrayEquals(new float[]{1,2}, result.vectors().getFirst());
        assertArrayEquals(new float[]{3,4}, result.vectors().get(1));
        result.vectors().getFirst()[0] = 99;
        assertEquals(1, result.vectors().getFirst()[0]);
        var request = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(fixture.guard).invokeModel(eq("7"), request.capture(), any(), any());
        assertEquals("memory-query:original", request.getValue().invocationId());
        assertFalse(request.getValue().metadata().toString().contains("first"));
        fixture.server.verify();
    }

    @Test void incompleteOrWrongDimensionResponseIsRejectedAfterActualUsageIsRetained() throws Exception {
        for (String data : List.of("[]", "[{\"index\":0,\"embedding\":[1]}]",
                "[{\"index\":0,\"embedding\":null}]")) {
            var fixture = response("{\"data\":" + data + ",\"usage\":{\"prompt_tokens\":7}}");
            assertInstanceOf(EmbeddingClient.Outcome.Rejected.class,
                    fixture.client.embedBatch("7", model(), "memory-index:original", List.of("one")));
            verify(fixture.costs).recordLlm(eq("7"), any(), anyString(), anyString(), any(), eq(1), any(), any());
            verify(fixture.guard).invokeModel(eq("7"), any(), any(), any());
            fixture.server.verify();
        }
    }

    @Test void zeroVectorFromRealLocalHttpIsRejectedAfterActualUsageIsRetained() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"data":[{"index":0,"embedding":[0,0]}],
                     "usage":{"prompt_tokens":7,"total_tokens":7}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var crypto = mock(CryptoService.class);
            when(crypto.decrypt("encrypted")).thenReturn("local-test-key");
            var guard = mock(ExternalInvocationGuard.class);
            when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(call -> {
                ExternalInvocationGuard.ExternalCall<List<float[]>> action = call.getArgument(3);
                return action.call();
            });
            var costs = mock(CostRecorder.class);
            var client = new EmbeddingClient(new PlatformModelHttpSupport(crypto),
                    ResilienceFixtures.withDefaultSettings(), costs, guard);
            assertInstanceOf(EmbeddingClient.Outcome.Rejected.class,
                    client.embedBatch("7", model("http://127.0.0.1:" + server.getAddress().getPort()),
                            "memory-zero:original", List.of("one"), Duration.ofSeconds(2), () -> false));
            assertEquals(1, calls.get());
            var usage = ArgumentCaptor.forClass(com.skillforge.core.billing.metering.LlmUsage.class);
            verify(costs).recordLlm(eq("7"), any(), anyString(), anyString(), usage.capture(), eq(1), any(), any());
            assertEquals(Long.valueOf(7), usage.getValue().promptTokens());
            verify(guard).invokeModel(eq("7"), any(), any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test void uncertainTransportReturnsOriginalInvocationWithoutAnyAutomaticReplay() throws Exception {
        var http = mock(PlatformModelHttpSupport.class);
        var guard = mock(ExternalInvocationGuard.class);
        when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(call -> {
            ExternalInvocationGuard.ExternalCall<List<float[]>> action = call.getArgument(3);
            return action.call();
        });
        var rest = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(http.client(any(), any(java.time.Duration.class))).thenReturn(rest);
        when(http.endpoint(any(), eq("/embeddings"))).thenReturn("https://embedding.example/embeddings");
        when(rest.post().uri(anyString()).contentType(any()).body(any(EmbeddingClient.EmbeddingRequest.class)).retrieve()
                .body(EmbeddingClient.EmbeddingResponse.class)).thenThrow(new ResourceAccessException("transport uncertain"));
        var result = assertInstanceOf(EmbeddingClient.Outcome.OutcomeUnknown.class,
                new EmbeddingClient(http, ResilienceFixtures.withDefaultSettings(), mock(CostRecorder.class), guard)
                        .embedBatch("7", model(), "memory:original", List.of("one")));
        assertEquals("memory:original", result.invocationId());
        verify(guard).invokeModel(eq("7"), any(), any(), any());
    }

    private Fixture response(String body) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://embedding.example/embeddings"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var http = mock(PlatformModelHttpSupport.class);
        when(http.client(any(), any(java.time.Duration.class))).thenReturn(builder.build());
        when(http.endpoint(any(), eq("/embeddings"))).thenReturn("https://embedding.example/embeddings");
        var guard = mock(ExternalInvocationGuard.class);
        when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(call -> {
            ExternalInvocationGuard.ExternalCall<List<float[]>> action = call.getArgument(3);
            return action.call();
        });
        var costs = mock(CostRecorder.class);
        return new Fixture(new EmbeddingClient(http, ResilienceFixtures.withDefaultSettings(), costs, guard),
                server, guard, costs);
    }

    private record Fixture(EmbeddingClient client, MockRestServiceServer server,
            ExternalInvocationGuard guard, CostRecorder costs) { }

    private static PlatformModelConfig model() {
        return model("https://embedding.example");
    }

    private static PlatformModelConfig model(String endpoint) {
        return new PlatformModelConfig("81", 3, "Embedding", "EMBEDDING", true,
                "custom", "custom-embedding", "custom-v1", "openai-embeddings", "bearer-api-key", "openai-compatible-vector-v1",
                endpoint, "embedding-test", "encrypted",
                null, null, null, null, null, 20, 2, null, null, null, null, "CNY");
    }
}
