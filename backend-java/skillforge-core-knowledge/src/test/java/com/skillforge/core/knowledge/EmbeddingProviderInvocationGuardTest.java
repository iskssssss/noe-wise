package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.core.shared.resilience.Resilience;

import com.skillforge.core.shared.resilience.ResilienceFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.budget.AuxiliaryModelBudgets;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.shared.tracing.TraceContext;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.http.MediaType;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

/** Embedding 批次在发送 HTTP 请求前必须经过 ExternalInvocationGuard。 */
class EmbeddingProviderInvocationGuardTest {

    @AfterEach
    void clearScope() {
        MeterScope.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void guardedBatchRecordsBatchMetadataWithoutEmbeddingPayload(boolean reportsUsage) throws Exception {
        PlatformModelHttpSupport http = mock(PlatformModelHttpSupport.class);
        RestClient client = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        GuardFixture fixture = guardFixture();
        ExternalInvocationGuard guard = fixture.guard();
        CostRecorder costRecorder = mock(CostRecorder.class);
        PlatformModelConfig config = embeddingConfig();
        when(http.client(eq(config), any(java.time.Duration.class))).thenReturn(client);
        when(http.endpoint(config, "/embeddings"))
                .thenReturn("https://provider.example/v1/embeddings");
        when(client.post().uri(anyString()).contentType(any()).body(any(EmbeddingClient.EmbeddingRequest.class)).retrieve().body(EmbeddingClient.EmbeddingResponse.class))
                .thenReturn(reportsUsage
                        ? new EmbeddingClient.EmbeddingResponse(List.of(new EmbeddingClient.EmbeddingItem(0, List.of(1.5, 2.5))),
                                new EmbeddingClient.EmbeddingUsage(37L, 37L))
                        : new EmbeddingClient.EmbeddingResponse(List.of(new EmbeddingClient.EmbeddingItem(0, List.of(1.5, 2.5))), null));
        EmbeddingProvider provider = spy(createProvider(
                mock(KnowledgeProviderConfigService.class), http, ResilienceFixtures.withDefaultSettings(), costRecorder, guard));
        doReturn(config).when(provider).config("7");
        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .eventId("action-1")
                .traceContext(new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null))
                .build())) {
            List<float[]> vectors = provider.embedBatch("7", List.of("secret text"));
            assertEquals(1, vectors.size());
            assertEquals(1.5F, vectors.getFirst()[0]);
        }

        ArgumentCaptor<ExternalInvocationRequest> request = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        ArgumentCaptor<AuxiliaryModelBudgets.Request> budget = ArgumentCaptor.forClass(AuxiliaryModelBudgets.Request.class);
        verify(guard).invokeModel(eq("7"), request.capture(), budget.capture(), any());
        assertEquals(com.skillforge.runtime.api.port.ModelTokenBounds.textInputs(List.of("secret text")),
                budget.getValue().inputUpperBound());
        assertEquals(0, budget.getValue().outputLimit());
        assertFalse(request.getValue().metadata().toString().contains("secret text"));
        verify(fixture.ledger()).finish(eq("7"), eq(request.getValue().attemptId()),
                eq(ExternalInvocationStatus.SUCCEEDED), eq(null), eq(null), any());
        verify(fixture.attempt()).resultSaved();
        assertEquals(ExternalOperationType.MODEL_EMBEDDING, request.getValue().operationType());
        assertEquals(1, request.getValue().metadata().get("input_count"));
        var usage = ArgumentCaptor.forClass(com.skillforge.core.billing.metering.LlmUsage.class);
        verify(costRecorder).recordLlm(anyString(), any(), anyString(), anyString(), usage.capture(), any(),
                eq(new ModelRevisionRef("81", 3)), any());
        assertEquals(reportsUsage ? Long.valueOf(37) : null, usage.getValue().promptTokens());
        assertEquals(reportsUsage ? "PROVIDER_REPORTED" : "UNKNOWN", usage.getValue().usageSource().name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantBusinessCallWithoutTraceStillCreatesGuardAttempt() throws Exception {
        PlatformModelHttpSupport http = mock(PlatformModelHttpSupport.class);
        RestClient client = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        GuardFixture fixture = guardFixture();
        ExternalInvocationGuard guard = fixture.guard();
        PlatformModelConfig config = embeddingConfig();
        when(http.client(eq(config), any(java.time.Duration.class))).thenReturn(client);
        when(http.endpoint(config, "/embeddings"))
                .thenReturn("https://provider.example/v1/embeddings");
        when(client.post().uri(anyString()).contentType(any()).body(any(EmbeddingClient.EmbeddingRequest.class)).retrieve().body(EmbeddingClient.EmbeddingResponse.class))
                .thenReturn(new EmbeddingClient.EmbeddingResponse(List.of(new EmbeddingClient.EmbeddingItem(0, List.of(1.0, 2.0))), null));
        EmbeddingProvider provider = spy(createProvider(
                mock(KnowledgeProviderConfigService.class), http, ResilienceFixtures.withDefaultSettings(),
                mock(CostRecorder.class), guard));
        doReturn(config).when(provider).config("7");
        doReturn(config).when(provider).config("");

        assertEquals(1, provider.embedBatch("7", List.of("probe")).size());
        try (MeterScope ignored = MeterScope.open(CostContext.builder().build())) {
            assertEquals(1, provider.embedBatch("7", List.of("probe")).size());
        }
        assertNull(provider.embedBatch("", List.of("probe")));

        verify(guard, times(2)).invokeModel(eq("7"), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unscopedTenantCallUsesSyntheticTraceInsteadOfBypassingGuard() throws Exception {
        PlatformModelHttpSupport http = mock(PlatformModelHttpSupport.class);
        RestClient client = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        GuardFixture fixture = guardFixture();
        ExternalInvocationGuard guard = fixture.guard();
        PlatformModelConfig config = embeddingConfig();
        when(http.client(eq(config), any(java.time.Duration.class))).thenReturn(client);
        when(http.endpoint(config, "/embeddings"))
                .thenReturn("https://provider.example/v1/embeddings");
        when(client.post().uri(anyString()).contentType(any()).body(any(EmbeddingClient.EmbeddingRequest.class)).retrieve().body(EmbeddingClient.EmbeddingResponse.class))
                .thenReturn(new EmbeddingClient.EmbeddingResponse(List.of(new EmbeddingClient.EmbeddingItem(0, List.of(1.0, 2.0))), null));
        EmbeddingProvider provider = spy(createProvider(
                mock(KnowledgeProviderConfigService.class), http, ResilienceFixtures.withDefaultSettings(),
                mock(CostRecorder.class), guard));
        doReturn(config).when(provider).config("7");

        assertEquals(1, provider.embedBatch("7", List.of("probe")).size());

        ArgumentCaptor<ExternalInvocationRequest> request = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(guard).invokeModel(eq("7"), request.capture(), any(), any());
        assertEquals(request.getValue().idempotencyKey(), ((TraceContext) request.getValue().context()).inputId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncertainTransportOutcomeIsReturnedAsFallbackInsteadOfAPretendedVector() throws Exception {
        PlatformModelHttpSupport http = mock(PlatformModelHttpSupport.class);
        RestClient client = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        GuardFixture fixture = guardFixture();
        ExternalInvocationGuard guard = fixture.guard();
        PlatformModelConfig config = embeddingConfig();
        when(http.client(eq(config), any(java.time.Duration.class))).thenReturn(client);
        when(http.endpoint(config, "/embeddings"))
                .thenReturn("https://provider.example/v1/embeddings");
        when(client.post().uri(anyString()).contentType(any()).body(any(EmbeddingClient.EmbeddingRequest.class)).retrieve().body(EmbeddingClient.EmbeddingResponse.class))
                .thenThrow(new ResourceAccessException("timeout"));
        CostRecorder costs = mock(CostRecorder.class);
        EmbeddingProvider provider = spy(createProvider(
                mock(KnowledgeProviderConfigService.class), http, ResilienceFixtures.withDefaultSettings(), costs, guard));
        doReturn(config).when(provider).config("7");
        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .traceContext(new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null))
                .build())) {
            assertNull(provider.embedBatch("7", List.of("text")));
        }

        int retries = 1; // UNKNOWN不自动重发，原attempt保留UNRESOLVED。
        ArgumentCaptor<ExternalInvocationRequest> requests = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(guard, times(retries)).invokeModel(eq("7"), requests.capture(), any(), any());
        assertEquals(1, requests.getAllValues().stream().map(ExternalInvocationRequest::invocationId).distinct().count());
        assertEquals(retries, requests.getAllValues().stream().map(ExternalInvocationRequest::attemptId).distinct().count());
        verify(fixture.ledger(), times(retries)).finish(eq("7"), anyString(),
                eq(ExternalInvocationStatus.UNRESOLVED), eq(null), eq("EXTERNAL_OUTCOME_UNKNOWN"), any());
        verify(fixture.attempt(), never()).resultSaved();
        verifyNoInteractions(costs);
    }

    @Test
    void embeddingResponseIndexRestoresInputOrder() throws Exception {
        var vectors = embeddingResponse(List.of("first", "second"), inputs -> """
                {"data":[{"index":1,"embedding":[3.0,4.0]},
                         {"index":0,"embedding":[1.0,2.0]}]}
                """);
        assertArrayEquals(new float[]{1, 2}, vectors.get(0));
        assertArrayEquals(new float[]{3, 4}, vectors.get(1));
    }

    @Test
    void duplicateOrMissingResponseIndexesCannotBindVectorsToInputs() throws Exception {
        for (String response : List.of(
                "{\"data\":[{\"index\":0,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[3,4]}]}",
                "{\"data\":[{\"embedding\":[1,2]},{\"index\":1,\"embedding\":[3,4]}]}")) {
            assertNull(embeddingResponse(List.of("first", "second"), inputs -> response));
        }
    }

    @Test
    void missingBatchEntryCannotShiftTheFollowingBatchVectors() throws Exception {
        var inputs = IntStream.range(0, 21).mapToObj(index -> "text-" + index).toList();
        assertNull(embeddingResponse(inputs, batch -> {
            var data = new ArrayList<Map<String, Object>>();
            int returned = batch.size() == 20 ? 19 : batch.size();
            for (int index = 0; index < returned; index++) {
                data.add(Map.of("index", index, "embedding", List.of(index + 1.0, index + 2.0)));
            }
            try { return new ObjectMapper().writeValueAsString(Map.of("data", data)); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }));
    }

    @Test
    void responseIndexMustBeAnInRangeIntegerAndNeverCoercesOtherScalars() throws Exception {
        for (String index : List.of("-1", "1", "0.5", "\"0\"")) {
            assertNull(embeddingResponse(List.of("single"), inputs ->
                    "{\"data\":[{\"index\":" + index + ",\"embedding\":[1.0,2.0]}]}"));
        }
    }

    private List<float[]> embeddingResponse(List<String> texts,
            Function<List<String>, String> response) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var json = new ObjectMapper();
        server.expect(ExpectedCount.manyTimes(), requestTo("https://provider.example/v1/embeddings"))
                .andRespond(request -> {
                    var root = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    var inputs = new ArrayList<String>();
                    root.path("input").forEach(value -> inputs.add(value.asText()));
                    return withSuccess(response.apply(inputs), MediaType.APPLICATION_JSON).createResponse(request);
                });
        var config = embeddingConfig();
        var http = mock(PlatformModelHttpSupport.class);
        when(http.client(eq(config), any(java.time.Duration.class))).thenReturn(builder.build());
        when(http.endpoint(config, "/embeddings")).thenReturn("https://provider.example/v1/embeddings");
        var configs = mock(KnowledgeProviderConfigService.class);
        when(configs.embedding("7")).thenReturn(config);
        var provider = createProvider(configs, http, ResilienceFixtures.withDefaultSettings(),
                mock(CostRecorder.class), guardFixture().guard());
        var result = provider.embedBatch("7", texts);
        server.verify();
        return result;
    }

    private GuardFixture guardFixture() {
        ExternalInvocationLedger ledger = mock(ExternalInvocationLedger.class);
        AuxiliaryModelBudgets budgets = mock(AuxiliaryModelBudgets.class);
        AuxiliaryModelBudgets.Attempt attempt = mock(AuxiliaryModelBudgets.Attempt.class);
        when(ledger.startAuxiliary(anyString(), any(), any())).thenReturn(true);
        when(budgets.reserve(anyString(), anyString(), anyString(), any())).thenReturn(attempt);
        when(attempt.sent()).thenReturn(true);
        return new GuardFixture(spy(new ExternalInvocationGuard(ledger, budgets)), ledger, attempt);
    }

    private record GuardFixture(ExternalInvocationGuard guard, ExternalInvocationLedger ledger,
            AuxiliaryModelBudgets.Attempt attempt) {}

    private static EmbeddingProvider createProvider(KnowledgeProviderConfigService configs,
            PlatformModelHttpSupport http, Resilience resilience, CostRecorder costs, ExternalInvocationGuard guard) {
        return new EmbeddingProvider(configs, new EmbeddingClient(http, resilience, costs, guard));
    }

    private static PlatformModelConfig embeddingConfig() {
        return new PlatformModelConfig("81", 3, "Embedding", "EMBEDDING", true,
                "openai", null, null, null, "bearer-api-key", null,
                "https://provider.example/v1", "embedding-test", "encrypted",
                null, null, null, null, null, 20, 2, null,
                null, null, null, "CNY");
    }
}
