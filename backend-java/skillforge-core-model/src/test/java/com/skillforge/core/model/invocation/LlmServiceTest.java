package com.skillforge.core.model.invocation;

import com.skillforge.core.shared.resilience.ResilienceFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.shared.resilience.Resilience;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.util.OptionalLong;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证宿主模型门面只通过 Agent Runtime Provider 发送请求。 */
class LlmServiceTest {

    @Test void connectionTestRejectsIncompleteAndFilteredCompletions() {
        var runtime = mock(ModelProviderRuntime.class);
        var http = mock(LlmHttpSupport.class);
        var settings = mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class);
        when(settings.read("calls")).thenReturn(new com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot(
                1, java.util.Map.of("llm_timeout_seconds", 5)));
        when(http.resolveApiKey("encrypted")).thenReturn("key");
        var providers = ModelProviderCatalog.standard();
        var profile = providers.resolve(new ModelProviderCatalog.Selection("deepseek", "deepseek-api", "2026-09-13",
                "deepseek-chat", ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "https://api.deepseek.com"));
        var service = new LlmService(mock(com.skillforge.core.model.PlatformModelCatalogService.class), http,
                ResilienceFixtures.withDefaultSettings(), settings, new DefaultModelInvocationPolicyChain(), runtime,
                providers, mock(ExternalInvocationGuard.class), mock(CostRecorder.class), mock(ModelCallRecords.class));
        for (var finish : java.util.List.of("length", "content_filter", "stop")) {
            when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Completed("partial",
                    java.util.List.of(), "deepseek-chat", new ModelUsage(OptionalLong.of(1), OptionalLong.of(1)), finish));
            var result = service.testConnection(profile, "encrypted");
            assertEquals(finish.equals("stop"), result.ok());
            if (!result.ok()) assertEquals(finish.equals("length") ? "model_output_length" : "model_content_filtered", result.error());
        }
    }

    @Test void messagesAuxiliaryCallRejectsUnsupportedOutputBeforeBudgetOrProviderAndStillAllowsText() {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        var http = mock(LlmHttpSupport.class);
        var runtime = mock(ModelProviderRuntime.class);
        var budget = guard();
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "Messages", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "anthropic-messages", "anthropic-api-key",
                "deepseek-messages-v1", "https://api.deepseek.com", "deepseek-flash", "encrypted-key",
                8192, 64000, 4096, 0.5, "DISABLED", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        var service = new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(),
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                budget, mock(CostRecorder.class), mock(ModelCallRecords.class));
        for (ResponseFormat format : java.util.List.of(new ResponseFormat.JsonObject(),
                new ResponseFormat.JsonSchema(new com.skillforge.runtime.api.TypedPayload("json-schema", "{\"type\":\"object\"}")))) {
            var failure = assertThrows(ApiException.class,
                    () -> service.generatePinned("7", "10", 1, "系统", "输入", format, 5));
            assertEquals("model_call_capability_unsupported", failure.getDetail());
        }
        org.mockito.Mockito.verifyNoInteractions(http, runtime, budget);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Completed("文本结果",
                java.util.List.of(), "deepseek-flash", new ModelUsage(OptionalLong.of(2), OptionalLong.of(3)), "stop"));
        assertEquals("文本结果", service.generatePinned("7", "10", 1, "系统", "输入", new ResponseFormat.Text(), 5).content());
    }

    @Test
    void delegatesTenantCallToRuntimeClientWithStoredProviderProfile() {
        var tenants = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        LlmHttpSupport http = mock(LlmHttpSupport.class);
        ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
        CostRecorder recorder = mock(CostRecorder.class);
        var tenant = new com.skillforge.core.model.PlatformModelConfig("10",1,"test","LLM",true,
                "deepseek","deepseek-api","2026-09-13","openai-chat-completions","bearer-api-key",
                "openai-compatible-chat-v1","https://api.deepseek.com","model-x","encrypted-key",
                8192,64000,4096,0.5,"DEFAULT",30,null,null,null,null,null,"CNY");
        when(tenants.resolveLlm("7",null)).thenReturn(tenant);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any())).thenReturn(
                new ModelProvider.InvocationResult.Completed(
                "回答", java.util.List.of(), "model-x", new ModelUsage(
                        OptionalLong.of(2), OptionalLong.of(3)), "stop"));
        LlmService service = new LlmService(
                tenants, http, ResilienceFixtures.withDefaultSettings(), mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime,
                ModelProviderCatalog.standard(), guard(), recorder, mock(ModelCallRecords.class));

        LlmResult result = service.generateForTenant(
                "7", "系统", "问题", new ResponseFormat.JsonObject(), 5);

        assertEquals("回答", result.content());
        assertEquals(5, result.usage().totalTokens());
        ArgumentCaptor<ModelProvider.InvocationRequest> request =
                ArgumentCaptor.forClass(ModelProvider.InvocationRequest.class);
        verify(runtime).generate(org.mockito.ArgumentMatchers.eq("deepseek"), request.capture());
        assertEquals("https://api.deepseek.com", request.getValue().endpoint());
        assertInstanceOf(ResponseFormat.JsonObject.class,
                request.getValue().response().format());
        assertEquals(2, request.getValue().messages().size());
        assertEquals(4096, request.getValue().generation().maxOutputTokens().orElseThrow());
        verify(recorder).recordLlm(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(new com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef("10", 1)), any());

        when(runtime.generate(any(), any())).thenReturn(
                new ModelProvider.InvocationResult.Completed("回答", java.util.List.of(), "model-x",
                        new ModelUsage(OptionalLong.empty(), OptionalLong.empty()), "stop"));
        assertEquals(com.skillforge.core.billing.metering.UsageSource.UNKNOWN,
                service.generateForTenant("7", "系统", "问题", new ResponseFormat.JsonObject(), 5)
                        .usage().usageSource());
    }

    @Test
    void typedHistoryPreservesRolesOrderAndEmptyContentAtProviderBoundary() {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        var http = mock(LlmHttpSupport.class);
        var runtime = mock(ModelProviderRuntime.class);
        var settings = mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class);
        when(settings.read("calls")).thenReturn(
                new com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot(
                        1, java.util.Map.of("chat_timeout_seconds", 5)));
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.resolveLlm("7", null)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Completed(
                "回复", java.util.List.of(), "model-x",
                new ModelUsage(OptionalLong.empty(), OptionalLong.empty()), "stop"));
        var service = new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(), settings,
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                guard(), mock(CostRecorder.class), mock(ModelCallRecords.class));
        com.skillforge.common.context.TenantContext.setTenantId("7");
        try {
            service.generate("系统", "当前输入", java.util.List.of(
                    new LlmHistoryMessage(LlmHistoryMessage.Role.USER, "历史问题"),
                    new LlmHistoryMessage(LlmHistoryMessage.Role.ASSISTANT, null)));
        } finally {
            com.skillforge.common.context.TenantContext.clear();
        }
        var request = ArgumentCaptor.forClass(ModelProvider.InvocationRequest.class);
        verify(runtime).generate(eq("deepseek"), request.capture());
        assertEquals(java.util.List.of(ModelProvider.Role.SYSTEM, ModelProvider.Role.USER,
                        ModelProvider.Role.ASSISTANT, ModelProvider.Role.USER),
                request.getValue().messages().stream().map(ModelProvider.Message::role).toList());
        assertEquals(java.util.List.of("系统", "历史问题", "", "当前输入"),
                request.getValue().messages().stream().map(ModelProvider.Message::textContent).toList());
    }

    @Test
    void controlledPinnedCallPassesDynamicOutputAndTimeoutToProvider() {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        LlmHttpSupport http = mock(LlmHttpSupport.class);
        ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
        CostRecorder recorder = mock(CostRecorder.class);
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Completed(
                "摘要", java.util.List.of(), "model-x",
                new ModelUsage(OptionalLong.of(2), OptionalLong.of(3)), "stop"));
        LlmService service = new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(),
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                guard(), recorder, mock(ModelCallRecords.class));

        service.generatePinned("7", "10", 1, "系统", "输入", new ResponseFormat.Text(),
                Duration.ofSeconds(4), 123, () -> false);

        ArgumentCaptor<ModelProvider.InvocationRequest> request = ArgumentCaptor.forClass(ModelProvider.InvocationRequest.class);
        verify(runtime).generate(org.mockito.ArgumentMatchers.eq("deepseek"), request.capture());
        assertEquals(Duration.ofSeconds(4), request.getValue().timeout());
        assertEquals(123, request.getValue().generation().maxOutputTokens().orElseThrow());
    }

    @Test
    void truncatedSummaryIsRejectedWithoutRetryEvenWhenBodyIsEmpty() {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        LlmHttpSupport http = mock(LlmHttpSupport.class);
        ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
        CostRecorder recorder = mock(CostRecorder.class);
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Completed(
                null, java.util.List.of(), "model-x",
                new ModelUsage(OptionalLong.of(2), OptionalLong.of(3)), "length"));
        LlmService service = new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(),
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                guard(), recorder, mock(ModelCallRecords.class));

        assertThrows(LlmService.TruncatedOutputException.class, () -> service.generatePinnedSummary(
                "7", "10", 1, "系统", "{\"messages\":[{\"text\":\"已完成工具\",\"closed\":true}]}",
                new ResponseFormat.Text(), Duration.ofSeconds(4), 73, () -> false));
        verify(runtime).generate(eq("deepseek"), any());
        verify(recorder).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void summaryKeepsOnlyACompletePrefixWithinTheContextWindow() throws Exception {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        LlmHttpSupport http = mock(LlmHttpSupport.class);
        ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
        CostRecorder recorder = mock(CostRecorder.class);
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any())).thenReturn(new ModelProvider.InvocationResult.Completed(
                "摘要", java.util.List.of(), "model-x", new ModelUsage(OptionalLong.empty(), OptionalLong.empty()), "stop"));
        LlmService service = new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(),
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                guard(), recorder, mock(ModelCallRecords.class));
        var input = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        input.putArray("currentContext").addObject().put("id", "input").put("role", "USER")
                .put("text", "当前目标：保留精确事实与来源");
        var messages = input.putArray("messages");
        messages.addObject().put("id", "first").put("role", "USER").put("text", "保留").put("closed", true);
        messages.addObject().put("id", "second").put("role", "USER")
                .put("text", "x ".repeat(200_000)).put("closed", true);

        var result = service.generatePinnedSummary("7", "10", 1, "系统", input.toString(),
                new ResponseFormat.Text(), Duration.ofSeconds(4), 4096, () -> false);

        assertEquals(1, result.coveredMessages());
        ArgumentCaptor<ModelProvider.InvocationRequest> request = ArgumentCaptor.forClass(ModelProvider.InvocationRequest.class);
        verify(runtime).generate(org.mockito.ArgumentMatchers.eq("deepseek"), request.capture());
        var sent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.getValue().messages().get(1).textContent());
        assertEquals(1, sent.path("messages").size());
        assertEquals("first", sent.path("messages").get(0).path("id").asText());
        assertEquals(input.path("currentContext"), sent.path("currentContext"), "裁历史前缀仍完整保留当前目标，且不计覆盖条数");
    }

    @Test
    void controlledSummaryUsesSingleAttemptAndRecordsUsageAfterCancellation() throws Exception {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        LlmHttpSupport http = mock(LlmHttpSupport.class);
        ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
        CostRecorder recorder = mock(CostRecorder.class);
        Resilience resilience = mock(Resilience.class);
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        AtomicBoolean cancelled = new AtomicBoolean();
        when(runtime.generate(any(), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            return new ModelProvider.InvocationResult.Completed("摘要", java.util.List.of(), "model-x",
                    new ModelUsage(OptionalLong.of(2), OptionalLong.of(3)), "stop");
        });
        when(resilience.callOnce(eq("llm:7"), any())).thenAnswer(invocation -> {
            Callable<LlmResult> action = invocation.getArgument(1);
            return action.call();
        });
        LlmService service = new LlmService(catalog, http, resilience,
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                guard(), recorder, mock(ModelCallRecords.class));
        var input = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        input.putArray("messages").addObject().put("id", "first").put("role", "USER")
                .put("text", "保留").put("closed", true);

        var result = service.generatePinnedSummary("7", "10", 1, "系统", input.toString(),
                new ResponseFormat.Text(), Duration.ofSeconds(4), 100, cancelled::get);

        assertEquals(5, result.result().usage().totalTokens());
        verify(resilience).callOnce(eq("llm:7"), any());
        verify(resilience, never()).call(eq("llm:7"), any());
        verify(recorder).recordLlm(any(), any(), any(), any(), any(), any(),
                eq(new com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef("10", 1)), any());
    }

    @Test
    void providerFailureIsMappedToStableSafeApiError() throws Exception {
        var catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        LlmHttpSupport http = mock(LlmHttpSupport.class);
        ModelProviderRuntime runtime = mock(ModelProviderRuntime.class);
        Resilience resilience = mock(Resilience.class);
        var model = new com.skillforge.core.model.PlatformModelConfig("10", 1, "test", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x", "encrypted-key",
                8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        when(http.resolveApiKey("encrypted-key")).thenReturn("plain-key");
        when(runtime.generate(any(), any()))
                .thenThrow(new IllegalStateException("provider-private-secret"));
        when(resilience.callOnce(eq("llm:7"), any())).thenAnswer(invocation -> {
            Callable<LlmResult> action = invocation.getArgument(1);
            return action.call();
        });
        LlmService service = new LlmService(catalog, http, resilience,
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class),
                new DefaultModelInvocationPolicyChain(), runtime, ModelProviderCatalog.standard(),
                guard(), mock(CostRecorder.class), mock(ModelCallRecords.class));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.generatePinned("7", "10", 1, "系统", "输入",
                        new ResponseFormat.Text(), 5));

        assertEquals("LLM 服务调用失败", failure.getDetail());
        assertFalse(failure.toString().contains("provider-private-secret"));
        assertNull(failure.getCause());
        verify(resilience).callOnce(eq("llm:7"), any());
        verify(resilience, never()).call(eq("llm:7"), any());
    }

    /** 本组只验证 LLM 门面，账本守卫执行语义由独立测试覆盖；夹具实际执行传入动作。 */
    private static ExternalInvocationGuard guard() {
        var guard = mock(ExternalInvocationGuard.class);
        try {
            when(guard.invokeModel(any(), any(), any(), any())).thenAnswer(invocation -> {
                ExternalInvocationGuard.ExternalCall<?> call = invocation.getArgument(3);
                return call.call();
            });
        } catch (Exception impossible) { throw new AssertionError(impossible); }
        return guard;
    }
}
