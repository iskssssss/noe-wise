package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.core.shared.resilience.Resilience;

import com.skillforge.core.shared.resilience.ResilienceFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.model.PlatformModelConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;

class KnowledgeModelProviderCoverageTest {

    @Test
    void laterUnknownBatchDoesNotReplayCompletedInputsOrStartRemainingBatch() {
        var configs = mock(KnowledgeProviderConfigService.class);
        var config = embedding("81", 3);
        when(configs.embedding("81", 3)).thenReturn(config);
        var shared = mock(EmbeddingClient.class);
        when(shared.embedBatch(eq("7"), eq(config), anyString(), any()))
                .thenReturn(new EmbeddingClient.Outcome.Available(java.util.stream.IntStream.range(0, 20)
                        .mapToObj(i -> new float[]{i, 2}).toList()))
                .thenAnswer(call -> new EmbeddingClient.Outcome.OutcomeUnknown(call.getArgument(2)));
        var inputs = java.util.stream.IntStream.range(0, 41).mapToObj(i -> "text-" + i).toList();
        try (var scope = com.skillforge.core.billing.metering.MeterScope.open(
                com.skillforge.core.billing.metering.CostContext.builder().eventId("original-index-job").build())) {
            assertNull(new EmbeddingProvider(configs, shared).embedBatch("7", "81", 3, inputs));
        }
        verify(shared).embedBatch("7", config, "original-index-job:embedding:0", inputs.subList(0, 20));
        verify(shared).embedBatch("7", config, "original-index-job:embedding:1", inputs.subList(20, 40));
        verify(shared, times(2)).embedBatch(anyString(), any(), anyString(), any());
    }

    @Test
    void embeddingHandlesEmptyAndBlankInputsBeforeResolvingPlatformSelection() {
        KnowledgeProviderConfigService configs = mock(KnowledgeProviderConfigService.class);
        EmbeddingProvider provider = embeddingProvider(configs, mock(PlatformModelHttpSupport.class));

        assertEquals(List.of(), provider.embedBatch("7", List.of()));
        assertNull(provider.embed("7", "  "));
        verify(configs, org.mockito.Mockito.never()).embedding(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddingReadsFrozenRevisionAndSplitsLargeBatches() {
        KnowledgeProviderConfigService configs = mock(KnowledgeProviderConfigService.class);
        PlatformModelHttpSupport http = mock(PlatformModelHttpSupport.class);
        RestClient client = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        PlatformModelConfig config = embedding("81", 3);
        when(configs.embedding("81", 3)).thenReturn(config);
        when(http.client(eq(config), any(java.time.Duration.class))).thenReturn(client);
        when(http.endpoint(config, "/embeddings")).thenReturn("https://embedding.example/v1/embeddings");
        var requestBody = client.post().uri("https://embedding.example/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON);
        when(requestBody.body(any(EmbeddingClient.EmbeddingRequest.class)).retrieve().body(EmbeddingClient.EmbeddingResponse.class))
                .thenReturn(embeddingResponse(0, 20), embeddingResponse(20, 1));
        clearInvocations(requestBody);

        List<float[]> vectors = embeddingProvider(configs, http).embedBatch(
                "7", "81", 3, java.util.stream.IntStream.range(0, 21)
                        .mapToObj(i -> "text-" + i).toList());

        assertEquals(21, vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals((float) i, vectors.get(i)[0]);
        }
        ArgumentCaptor<EmbeddingClient.EmbeddingRequest> batches = ArgumentCaptor.forClass(EmbeddingClient.EmbeddingRequest.class);
        verify(requestBody, times(2)).body(batches.capture());
        assertEquals(java.util.stream.IntStream.range(0, 20).mapToObj(i -> "text-" + i).toList(),
                batches.getAllValues().get(0).input());
        assertEquals(List.of("text-20"), batches.getAllValues().get(1).input());
        verify(configs).embedding("81", 3);
    }

    @Test
    void rerankReturnsNoResultWhenSelectionCannotBeResolved() {
        KnowledgeProviderConfigService configs = mock(KnowledgeProviderConfigService.class);
        when(configs.rerank("7")).thenThrow(new IllegalStateException("selection missing"));
        RerankProvider provider = new RerankProvider(configs, mock(CostRecorder.class),
                mock(ExternalInvocationGuard.class), mock(RerankClientFactory.class));

        assertNull(provider.rerank("7", "query", List.of("doc")));
        assertNull(provider.rerank("7", "query", List.of()));
    }

    @Test
    void rerankUsesResolvedPlatformModelAndItsTimeout() throws Exception {
        KnowledgeProviderConfigService configs = mock(KnowledgeProviderConfigService.class);
        PlatformModelConfig config = rerank();
        when(configs.rerank("7")).thenReturn(config);
        RerankClientFactory factory = mock(RerankClientFactory.class);
        RerankClient client = mock(RerankClient.class);
        when(factory.forConfig(config)).thenReturn(client);
        when(client.postRerank(any(), anyString(), any()))
                .thenReturn(new RerankClient.Response(List.of(new RerankProvider.RerankResult(0, .88)), com.skillforge.core.billing.metering.LlmUsage.empty()));
        ExternalInvocationGuard guard = mock(ExternalInvocationGuard.class);
        when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            ExternalInvocationGuard.ExternalCall<List<RerankProvider.RerankResult>> call =
                    invocation.getArgument(3);
            return call.call();
        });
        RerankProvider provider = new RerankProvider(
                configs, mock(CostRecorder.class), guard, factory);

        assertEquals(1, provider.rerank("7", "query",
                List.of("doc")).size());
        verify(configs).rerank("7");
        verify(guard).invokeModel(eq("7"), any(), any(), any());
        verify(client).postRerank(eq(config), eq("query"), eq(List.of("doc")));
    }

    private EmbeddingProvider embeddingProvider(
            KnowledgeProviderConfigService configs, PlatformModelHttpSupport http) {
        ExternalInvocationGuard guard = mock(ExternalInvocationGuard.class);
        try {
            when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(invocation -> {
                ExternalInvocationGuard.ExternalCall<List<float[]>> call = invocation.getArgument(3);
                return call.call();
            });
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return createProvider(configs, http, ResilienceFixtures.withDefaultSettings(),
                mock(CostRecorder.class), guard);
    }

    private EmbeddingClient.EmbeddingResponse embeddingResponse(int firstIndex, int count) {
        return new EmbeddingClient.EmbeddingResponse(java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new EmbeddingClient.EmbeddingItem(index,
                        List.of((double) (firstIndex + index), 2.0))).toList(), null);
    }

    private static EmbeddingProvider createProvider(KnowledgeProviderConfigService configs,
            PlatformModelHttpSupport http, Resilience resilience, CostRecorder costs, ExternalInvocationGuard guard) {
        return new EmbeddingProvider(configs, new EmbeddingClient(http, resilience, costs, guard));
    }

    private PlatformModelConfig embedding(String id, long revision) {
        return new PlatformModelConfig(id, revision, "Embedding", "EMBEDDING", true,
                "openai", null, null, null, "bearer-api-key", null,
                "https://embedding.example/v1", "embed-v1", "encrypted",
                null, null, null, null, null, 20, 2, null,
                null, null, null, "CNY");
    }

    private PlatformModelConfig rerank() {
        return new PlatformModelConfig("82", 2, "Rerank", "RERANK", true,
                "rerank", null, null, null, "bearer-api-key", null,
                "https://rerank.example/v1", "rank-v1", "encrypted",
                null, null, null, null, null, 1, null, "/rerank",
                null, null, null, "CNY");
    }
}
