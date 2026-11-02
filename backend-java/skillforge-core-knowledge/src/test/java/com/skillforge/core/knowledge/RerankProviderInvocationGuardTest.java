package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.common.context.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

/** Rerank 异步 worker 必须显式接收 Trace，并在网络调用前登记 attempt。 */
class RerankProviderInvocationGuardTest {

    @AfterEach
    void clearScope() {
        MeterScope.clear();
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncRerankCarriesCapturedTraceIntoGuard() throws Exception {
        RerankClientFactory factory = mock(RerankClientFactory.class);
        RerankClient client = mock(RerankClient.class);
        ExternalInvocationGuard guard = mock(ExternalInvocationGuard.class);
        PlatformModelConfig config = rerankConfig();
        when(factory.forConfig(config)).thenReturn(client);
        var usage = new com.skillforge.core.billing.metering.LlmUsage(79L, null, null, null, null, 79L,
                com.skillforge.core.billing.metering.UsageSource.PROVIDER_REPORTED, null);
        when(client.postRerank(any(), anyString(), any())).thenReturn(new RerankClient.Response(
                List.of(new RerankProvider.RerankResult(0, .9)), usage));
        when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            assertEquals("7", TenantContext.getTenantId());
            ExternalInvocationGuard.ExternalCall<List<RerankProvider.RerankResult>> call = invocation.getArgument(3);
            return call.call();
        });
        CostRecorder costRecorder = mock(CostRecorder.class);
        RerankProvider provider = spy(new RerankProvider(
                mock(KnowledgeProviderConfigService.class), costRecorder, guard, factory));
        doReturn(config).when(provider).config("7");
        TenantContext.setTenantId("caller-context");
        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .eventId("action-1")
                .traceContext(new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null))
                .build())) {
            assertEquals(List.of(new RerankProvider.RerankResult(0, .9)),
                    provider.rerank("7", "secret query", List.of("secret document")));
            assertEquals("caller-context", TenantContext.getTenantId());
        }

        ArgumentCaptor<ExternalInvocationRequest> request = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(guard).invokeModel(org.mockito.ArgumentMatchers.eq("7"), request.capture(), any(), any());
        assertEquals(ExternalOperationType.MODEL_RERANK, request.getValue().operationType());
        assertEquals(1, request.getValue().metadata().get("document_count"));
        assertEquals("root-1", ((TraceContext) request.getValue().context()).rootInputId());
        verify(costRecorder).recordLlm(anyString(), any(), anyString(), anyString(), eq(usage), any(),
                eq(new ModelRevisionRef("82", 2)), any());
    }

    @Test
    void tenantRerankWithoutTraceStillCreatesAnExternalAttempt() throws Exception {
        RerankClientFactory factory = mock(RerankClientFactory.class);
        RerankClient client = mock(RerankClient.class);
        ExternalInvocationGuard guard = mock(ExternalInvocationGuard.class);
        PlatformModelConfig config = rerankConfig();
        when(factory.forConfig(config)).thenReturn(client);
        when(client.postRerank(any(), anyString(), any())).thenReturn(new RerankClient.Response(List.of(), com.skillforge.core.billing.metering.LlmUsage.empty()));
        when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            ExternalInvocationGuard.ExternalCall<List<RerankProvider.RerankResult>> call = invocation.getArgument(3);
            return call.call();
        });
        RerankProvider provider = spy(new RerankProvider(
                mock(KnowledgeProviderConfigService.class), mock(CostRecorder.class), guard, factory));
        doReturn(config).when(provider).config("7");

        assertEquals(List.of(), provider.rerank("7", "probe", List.of("probe")));
        try (MeterScope ignored = MeterScope.open(CostContext.builder().build())) {
            assertEquals(List.of(), provider.rerank("7", "probe", List.of("probe")));
        }

        verify(guard, org.mockito.Mockito.times(2)).invokeModel(eq("7"), any(), any(), any());
        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .eventId("")
                .traceContext(new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null))
                .build())) {
            assertEquals(List.of(), provider.rerank("7", "probe", List.of("probe")));
        }
        verify(guard, org.mockito.Mockito.times(3)).invokeModel(eq("7"), any(), any(), any());
    }

    @Test
    void uncertainAsyncTransportBecomesUnresolvedAttemptWithoutWritingUsage() {
        RerankClientFactory factory = mock(RerankClientFactory.class);
        RerankClient client = mock(RerankClient.class);
        ExternalInvocationLedger ledger = mock(ExternalInvocationLedger.class);
        var budgets = mock(com.skillforge.core.billing.budget.AuxiliaryModelBudgets.class);
        var attempt = mock(com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Attempt.class);
        when(budgets.reserve(anyString(), anyString(), anyString(), any())).thenReturn(attempt);
        when(attempt.sent()).thenReturn(true);
        ExternalInvocationGuard guard = new ExternalInvocationGuard(ledger, budgets);
        PlatformModelConfig config = rerankConfig();
        when(factory.forConfig(config)).thenReturn(client);
        when(client.postRerank(any(), anyString(), any())).thenAnswer(invocation -> {
            assertEquals("7", TenantContext.getTenantId());
            throw new ResourceAccessException("connection reset");
        });
        when(ledger.startAuxiliary(anyString(), any(), any())).thenReturn(true);
        CostRecorder costRecorder = mock(CostRecorder.class);
        RerankProvider provider = spy(new RerankProvider(
                mock(KnowledgeProviderConfigService.class), costRecorder, guard, factory));
        doReturn(config).when(provider).config("7");
        TenantContext.setTenantId("caller-before-failure");

        try (MeterScope ignored = MeterScope.open(CostContext.builder()
                .eventId("action-1")
                .traceContext(new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null))
                .build())) {
            assertNull(provider.rerank("7", "query", List.of("document")));
            assertEquals("caller-before-failure", TenantContext.getTenantId());
        }

        verify(ledger).finish(eq("7"), anyString(), eq(ExternalInvocationStatus.UNRESOLVED),
                eq(null), eq("EXTERNAL_OUTCOME_UNKNOWN"), any());
        verifyNoInteractions(costRecorder);
    }

    @Test
    void concurrentTenantsKeepTheirOwnAsyncGuardAndCallerContext() throws Exception {
        var factory = mock(RerankClientFactory.class);
        var client = mock(RerankClient.class);
        var guard = mock(ExternalInvocationGuard.class);
        var config = rerankConfig();
        when(factory.forConfig(config)).thenReturn(client);
        when(client.postRerank(any(), anyString(), any())).thenReturn(new RerankClient.Response(List.of(new RerankProvider.RerankResult(0, .9)), com.skillforge.core.billing.metering.LlmUsage.empty()));
        var entered = new java.util.concurrent.CountDownLatch(2);
        var tenants = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            String tenant = invocation.getArgument(0);
            assertEquals(tenant, TenantContext.getTenantId());
            tenants.add(tenant); entered.countDown();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(tenant, TenantContext.getTenantId());
            ExternalInvocationGuard.ExternalCall<List<RerankProvider.RerankResult>> call = invocation.getArgument(3);
            return call.call();
        });
        var provider = spy(new RerankProvider(mock(KnowledgeProviderConfigService.class), mock(CostRecorder.class), guard, factory));
        doReturn(config).when(provider).config("7"); doReturn(config).when(provider).config("8");
        try (var callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.Stream.of("7", "8").map(tenant -> callers.submit(() -> {
                TenantContext.setTenantId("caller-" + tenant);
                try {
                    assertEquals(1, provider.rerank(tenant, "query", List.of("document")).size());
                    assertEquals("caller-" + tenant, TenantContext.getTenantId());
                } finally { TenantContext.clear(); }
            })).toList();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(java.util.Set.of("7", "8"), tenants);
    }

    private static PlatformModelConfig rerankConfig() {
        return new PlatformModelConfig("82", 2, "Rerank", "RERANK", true,
                "rerank-provider", null, null, null, "bearer-api-key", null,
                "https://provider.example/v1", "rerank-test", "encrypted",
                null, null, null, null, null, 20, null, "/rerank",
                null, null, null, "CNY");
    }
}
