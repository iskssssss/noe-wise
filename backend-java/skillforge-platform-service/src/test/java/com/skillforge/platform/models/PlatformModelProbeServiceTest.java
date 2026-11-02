package com.skillforge.platform.models;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.knowledge.PlatformKnowledgeModelProbe;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.platform.models.PlatformModelProbeService.Discovery;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.api.port.ModelPort;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlatformModelProbeServiceTest {
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final PlatformKnowledgeModelProbe knowledge = mock(PlatformKnowledgeModelProbe.class);
    private final ModelProviderCatalog catalog = mock(ModelProviderCatalog.class);
    private final ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
    private final PlatformModelProbeService probes = new PlatformModelProbeService(models, crypto, knowledge, catalog, runtime);

    @Test void reasoningOptionsKeepUnknownModelImagesUnverifiedWithoutCredentialsOrSupplierCalls() {
        var realCatalog = ModelProviderCatalog.standard();
        var configured = new PlatformModelProbeService(models, crypto, knowledge, realCatalog, runtime);
        var selections = List.of(new PlatformModelProbeService.ReasoningSelection(
                        "aliyun-bailian", "bailian-cn-beijing", "2026-09-15-global-v1", "openai-chat-completions",
                        "bearer-api-key", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                new PlatformModelProbeService.ReasoningSelection("custom", "custom-openai-compatible", "custom-v1",
                        "openai-chat-completions", "bearer-api-key", "http://127.0.0.1:11434/v1", "account-model"),
                new PlatformModelProbeService.ReasoningSelection("custom", "custom-openai-compatible", "custom-v1",
                        "openai-responses", "bearer-api-key", "http://127.0.0.1:11434/v1", "account-model"));
        for (var selection : selections) {
            var images = configured.reasoningOptions(selection).imageSupport();
            assertEquals(ModelProviderCatalog.SupportLevel.UNVERIFIED, images.userImages());
            assertEquals(ModelProviderCatalog.SupportLevel.UNVERIFIED, images.toolImages());
            assertNull(images.defaults());
        }
        verifyNoInteractions(runtime, crypto, models, knowledge);
    }

    @Test
    void queriesReasoningOptionsWithoutCredentialsOrSupplierCalls() {
        when(catalog.reasoningOptions(any())).thenReturn(new com.skillforge.runtime.adapter.model.provider.ReasoningOptions(
                List.of(ModelPort.ReasoningPolicy.DEFAULT, ModelPort.ReasoningPolicy.HIGH),
                com.skillforge.runtime.adapter.model.provider.ReasoningOptions.Source.OFFICIAL_DOCUMENTATION, "https://docs.example/reasoning"));
        var result = probes.reasoningOptions(new PlatformModelProbeService.ReasoningSelection(
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions",
                "bearer-api-key", "https://api.deepseek.com", "deepseek-flash"));
        assertEquals(List.of("DEFAULT", "HIGH"), result.options());
        assertEquals("OFFICIAL_DOCUMENTATION", result.source());
        assertEquals("https://docs.example/reasoning", result.sourceUrl());
        verifyNoInteractions(runtime, crypto, models, knowledge);
    }

    @Test
    void reasoningOptionsUseSnakeCaseWireFieldsAndRejectInvalidSelectionsSafely() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var selection = json.readValue("""
                {"provider_id":"deepseek","service_id":"deepseek-api","profile_version":"2026-09-13",
                 "protocol":"openai-chat-completions","authentication_scheme":"bearer-api-key",
                 "endpoint":"https://api.deepseek.com","model_id":"deepseek-flash"}
                """, PlatformModelProbeService.ReasoningSelection.class);
        assertEquals("deepseek-flash", selection.modelId());
        assertEquals("deepseek-api", selection.serviceId());
        assertTrue(json.readTree(json.writeValueAsString(new PlatformModelProbeService.ReasoningOptionsView(
                List.of("DEFAULT"), "UNVERIFIED", ""))).has("source_url"));
        when(catalog.reasoningOptions(any())).thenThrow(new IllegalArgumentException("private selection details"));
        assertEquals("model_provider_profile_invalid",
                assertThrows(ApiException.class, () -> probes.reasoningOptions(selection)).getMessage());
        verifyNoInteractions(runtime, crypto, models, knowledge);
    }

    @Test
    void discoversThroughTheInjectedCatalogAndRuntimeWithOnlySafeModelNamesInTheResult() {
        var target = mock(ModelProviderCatalog.ResolvedDiscovery.class);
        when(target.providerId()).thenReturn("custom");
        when(catalog.resolveDiscovery(any())).thenReturn(target);
        when(runtime.discover(eq("custom"), any())).thenReturn(List.of(new DiscoveredModel("visible-model", "private-owner")));
        var result = probes.discover(discovery("credential"));
        assertEquals(List.of(new PlatformModelProbeService.DiscoveredModel("visible-model")), result.models());
        var request = ArgumentCaptor.forClass(ModelProvider.DiscoveryRequest.class);
        verify(runtime).discover(eq("custom"), request.capture());
        assertSame(target, request.getValue().target());
        assertEquals(Duration.ofSeconds(15), request.getValue().timeout());
        assertEquals("credential", request.getValue().credential());
        assertFalse(result.toString().contains("credential"));
        assertFalse(result.toString().contains("private-owner"));
    }

    @Test
    void discoveredMetadataIsProjectedAsReportedWithoutInventingMissingCapabilities() {
        var target = mock(ModelProviderCatalog.ResolvedDiscovery.class);
        when(target.providerId()).thenReturn("custom");
        when(catalog.resolveDiscovery(any())).thenReturn(target);
        var metadata = new com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadata(
                "display name", new com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadata.Context(
                        64000L, null, 4096L, null, null, null), null, null,
                new com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadata.Reasoning(List.of("low", "vendor-specific"), "low"),
                null, null, null);
        when(runtime.discover(eq("custom"), any())).thenReturn(List.of(new DiscoveredModel(
                "visible-model", "private-owner", java.util.Optional.of(metadata))));
        var projected = probes.discover(discovery("credential")).models().getFirst();
        assertSame(metadata, projected.metadata());
        assertNull(projected.metadata().capabilities());
        assertEquals(List.of("low", "vendor-specific"), projected.metadata().reasoning().supportedLevels());
        assertFalse(projected.toString().contains("private-owner"));
    }

    @Test
    void rejectsMissingCredentialsBeforeProviderWorkAndDoesNotExposeDiscoveryFailures() {
        assertEquals("model_discovery_credential_required",
                assertThrows(ApiException.class, () -> probes.discover(discovery(" "))).getMessage());
        verifyNoInteractions(runtime, crypto, models);
        when(catalog.resolveDiscovery(any())).thenThrow(new IllegalArgumentException("private credential and endpoint"));
        assertEquals("model_discovery_configuration_invalid",
                assertThrows(ApiException.class, () -> probes.discover(discovery("credential"))).getMessage());
        verifyNoInteractions(runtime);
    }

    @Test
    void discoversWithStoredMessagesCredentialUsingTheServicesDiscoveryAuthentication() {
        var realCatalog = ModelProviderCatalog.standard();
        var service = new PlatformModelProbeService(models, crypto, knowledge, realCatalog, runtime);
        var saved = mock(PlatformModelConfig.class);
        when(saved.revision()).thenReturn(3L);
        when(saved.providerId()).thenReturn("deepseek");
        when(saved.serviceId()).thenReturn("deepseek-api");
        when(saved.endpoint()).thenReturn("https://api.deepseek.com");
        when(saved.authenticationScheme()).thenReturn("anthropic-api-key");
        when(saved.apiKeyEncrypted()).thenReturn("encrypted");
        when(models.draftForProbe("7001")).thenReturn(saved);
        when(crypto.decrypt("encrypted")).thenReturn("stored-secret");
        when(runtime.discover(eq("deepseek"), any())).thenReturn(List.of(new DiscoveredModel("deepseek-flash", null)));
        var request = new Discovery("deepseek", "deepseek-api", "2026-09-13", "anthropic-messages",
                "anthropic-api-key", "https://api.deepseek.com", null, "7001", 3L);
        assertEquals("deepseek-flash", service.discover(request).models().getFirst().modelId());
        var captured = ArgumentCaptor.forClass(ModelProvider.DiscoveryRequest.class);
        verify(runtime).discover(eq("deepseek"), captured.capture());
        assertEquals(ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, captured.getValue().target().authenticationScheme());
        assertEquals("stored-secret", captured.getValue().credential());
        assertFalse(service.discover(request).toString().contains("stored-secret"));
    }

    @Test
    void rejectsStoredDiscoveryCredentialReuseAfterScopeChangesBeforeDecryptionOrNetwork() {
        var service = new PlatformModelProbeService(models, crypto, knowledge, ModelProviderCatalog.standard(), runtime);
        when(models.draftForProbe("7001")).thenReturn(llm());
        var request = new Discovery("deepseek", "deepseek-api", "2026-09-13", "anthropic-messages",
                "anthropic-api-key", "https://api.deepseek.com", null, "7001", 1L);
        var rejected = assertThrows(ApiException.class, () -> service.discover(request));
        assertEquals("model_discovery_credential_scope_changed", rejected.getCode());
        verifyNoInteractions(crypto, runtime);
    }

    @Test
    void testsUnsavedPayloadWithoutSavingACompatibilityReport() {
        var payload = mock(com.skillforge.core.model.ModelWrite.class);
        when(models.previewForProbe(null, payload)).thenReturn(llm());
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        when(runtime.stream(eq("custom"), any())).thenReturn(events(new ModelProvider.StreamEvent.OutcomeUnknown("private")));
        var result = probes.test(null, payload);
        assertFalse(result.ok());
        assertEquals("model_probe_outcome_unknown", result.error());
        verify(models, never()).saveCompatibilityReport(any(), anyLong(), any());
        verify(models, never()).draftForProbe(any());
        assertEquals("model_probe_configuration_required",
                assertThrows(ApiException.class, () -> probes.test(null, null)).getMessage());
    }

    @Test
    void streamsSavedPolicyAndReplaysTheNativeToolTurnWithLocalEchoOnly() {
        when(models.draftForProbe("model")).thenReturn(llm());
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        var first = new ModelProvider.InvocationResult.Completed(null,
                List.of(new ModelProvider.ToolCall("call-1", "compatibility_echo", "{\"value\":\"probe_echo\"}")),
                "visible-model", new ModelPort.ModelUsage(OptionalLong.of(1), OptionalLong.of(1)), "tool_calls", "reasoning");
        var second = new ModelProvider.InvocationResult.Completed("echo:probe_echo", List.of(), "visible-model",
                new ModelPort.ModelUsage(OptionalLong.of(1), OptionalLong.of(1)), "stop");
        when(runtime.stream(eq("custom"), any())).thenReturn(events(
                new ModelProvider.StreamEvent.ReasoningDelta(1, "reasoning"),
                new ModelProvider.StreamEvent.ToolCallDelta(2, 0, "call-1", "compatibility_echo", "{\"value\":\"probe_echo\"}"),
                new ModelProvider.StreamEvent.Completed(first)), events(
                new ModelProvider.StreamEvent.ContentDelta(1, "echo:probe_echo"), new ModelProvider.StreamEvent.Completed(second)));
        var result = probes.test("model");
        assertTrue(result.ok());
        assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.PASSED, result.compatibilityReport().toolContinuation().status());
        var requests = ArgumentCaptor.forClass(ModelProvider.InvocationRequest.class);
        verify(runtime, times(2)).stream(eq("custom"), requests.capture());
        var one = requests.getAllValues().getFirst();
        var two = requests.getAllValues().getLast();
        assertEquals(Duration.ofSeconds(9), one.timeout());
        assertEquals(ModelPort.ReasoningPolicy.DISABLED, one.reasoning());
        assertEquals(one.generation(), two.generation());
        assertEquals(one.tools(), two.tools());
        assertEquals(ModelProvider.ToolChoice.AUTO, one.toolChoice());
        assertEquals(ModelProvider.ToolChoice.NONE, two.toolChoice());
        assertEquals(first.reasoningContent(), two.messages().get(1).reasoningContent());
        assertEquals(first.protocolState(), two.messages().get(1).protocolState());
        assertEquals("call-1", two.messages().getLast().toolCallId());
        assertEquals("echo:probe_echo", two.messages().getLast().textContent());
        verify(models).saveCompatibilityReport(eq("model"), eq(1L), eq(result.compatibilityReport()));
        verify(runtime, never()).generate(anyString(), any());
        verifyNoInteractions(knowledge);
    }

    @Test
    void incompleteOrFilteredCompletionFailsWithoutContinuation() {
        when(models.draftForProbe("model")).thenReturn(llm());
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        for (var finish : List.of("length", "content_filter")) {
            var completed = new ModelProvider.InvocationResult.Completed("partial", List.of(), "visible-model",
                    new ModelPort.ModelUsage(OptionalLong.of(1), OptionalLong.of(1)), finish);
            when(runtime.stream(eq("custom"), any())).thenReturn(events(
                    new ModelProvider.StreamEvent.ContentDelta(1, "partial"), new ModelProvider.StreamEvent.Completed(completed)));
            var result = probes.test("model");
            assertFalse(result.ok());
            assertEquals(finish.equals("length") ? "model_output_length" : "model_content_filtered", result.error());
            assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.FAILED, result.compatibilityReport().status());
            assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.NOT_RUN, result.compatibilityReport().toolContinuation().status());
        }
        verify(runtime, times(2)).stream(eq("custom"), any());
    }

    @Test
    void unknownStreamIsSavedWithoutRetryOrToolContinuationAndPrivateTextIsNotReturned() {
        when(models.draftForProbe("model")).thenReturn(llm());
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        when(runtime.stream(eq("custom"), any())).thenReturn(events(new ModelProvider.StreamEvent.OutcomeUnknown("private credential")));
        var result = probes.test("model");
        assertFalse(result.ok());
        assertEquals("model_probe_outcome_unknown", result.error());
        assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.UNKNOWN, result.compatibilityReport().status());
        assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.NOT_RUN, result.compatibilityReport().toolContinuation().status());
        assertFalse(result.toString().contains("private credential"));
        verify(runtime, times(1)).stream(eq("custom"), any());
        verify(models).saveCompatibilityReport(eq("model"), eq(1L), eq(result.compatibilityReport()));
    }

    @Test
    void completedWithoutDeltasDoesNotPassStreamingAndConflictingSaveIsNotOverwritten() {
        when(models.draftForProbe("model")).thenReturn(llm());
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        var completed = new ModelProvider.InvocationResult.Completed("echo:probe_echo", List.of(), "visible-model",
                new ModelPort.ModelUsage(OptionalLong.of(1), OptionalLong.of(1)), "stop");
        when(runtime.stream(eq("custom"), any())).thenReturn(events(new ModelProvider.StreamEvent.Completed(completed)));
        var result = probes.test("model");
        assertFalse(result.ok());
        assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.PASSED, result.compatibilityReport().configurationAcceptance().status());
        assertEquals(com.skillforge.core.model.ModelCompatibilityReport.Status.FAILED, result.compatibilityReport().streaming().status());
        doThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "model_revision_conflict"))
                .when(models).saveCompatibilityReport(eq("model"), eq(1L), any());
        assertEquals("model_revision_conflict", assertThrows(ApiException.class, () -> probes.test("model")).getMessage());
    }

    private static java.util.concurrent.Flow.Publisher<ModelProvider.StreamEvent> events(ModelProvider.StreamEvent... events) {
        return subscriber -> subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            boolean sent, cancelled;
            @Override public void request(long count) {
                if (sent) return;
                sent = true;
                for (var event : events) { if (cancelled) return; subscriber.onNext(event); }
                if (!cancelled) subscriber.onComplete();
            }
            @Override public void cancel() { cancelled = true; }
        });
    }

    @Test
    void delegatesKnowledgeModelProbesWithoutStartingAnLlmProviderCall() {
        var config = mock(PlatformModelConfig.class);
        when(config.kind()).thenReturn("EMBEDDING");
        when(models.draftForProbe("model")).thenReturn(config);
        when(knowledge.test(config)).thenReturn(new PlatformKnowledgeModelProbe.Result(true, 3, null, 1536, null, null));
        assertEquals(new PlatformModelProbeService.ProbeResult(true, 3, null, 1536), probes.test("model"));
        verifyNoInteractions(runtime, crypto);
    }

    @Test void unsavedFreshAndSavedKnowledgeProbesUseOnlyTheCurrentValidatedSnapshot() throws Exception {
        for (String id : new String[] {null, "saved-model"}) {
            var payload = mock(com.skillforge.core.model.ModelWrite.class);
            var config = mock(PlatformModelConfig.class);
            when(config.kind()).thenReturn("RERANK");
            when(models.previewForProbe(id, payload)).thenReturn(config);
            var ranking = List.of(new PlatformKnowledgeModelProbe.Ranking(1, 0.9), new PlatformKnowledgeModelProbe.Ranking(0, 0.1));
            var usage = new PlatformKnowledgeModelProbe.Usage(0L, 9007199254740993L);
            when(knowledge.test(config)).thenReturn(new PlatformKnowledgeModelProbe.Result(true, 7, null, null, ranking, usage));
            var result = probes.test(id, payload);
            assertEquals(ranking, result.ranking()); assertEquals(usage, result.usage());
            assertNull(result.compatibilityReport()); assertNull(result.dimensions());
            verify(models).previewForProbe(id, payload);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().setPropertyNamingStrategy(
                    com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
            var wire = json.readTree(json.writeValueAsString(result));
            assertEquals("9007199254740993", wire.path("usage").path("total_tokens").textValue());
            assertEquals("0", wire.path("usage").path("input_tokens").textValue());
            assertEquals(1, wire.path("ranking").get(0).path("index").intValue());
            assertFalse(json.writeValueAsString(result).contains("encrypted"));
        }
        verify(models, never()).draftForProbe(any());
        verify(models, never()).saveCompatibilityReport(anyString(), anyLong(), any());
        verifyNoInteractions(runtime, crypto);
    }

    private static Discovery discovery(String credential) {
        return new Discovery("custom", "custom-openai-compatible", "profile", "openai-chat-completions",
                "bearer-api-key", "https://models.example/v1", credential);
    }

    private static PlatformModelConfig llm() {
        return new PlatformModelConfig("7001", 1, "probe fixture", "LLM", true,
                "custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions",
                "bearer-api-key", "openai-compatible-chat-v1", "https://models.example/v1", "visible-model",
                "encrypted", 4096, 64000, 128, null, "DISABLED", 9,
                null, null, null, null, null, "CNY");
    }
}
