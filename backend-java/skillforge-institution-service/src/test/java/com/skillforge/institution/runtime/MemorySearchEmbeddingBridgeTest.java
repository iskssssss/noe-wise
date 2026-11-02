package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MemorySearchEmbeddingBridgeTest {
    private static final Instant NOW=Instant.parse("2026-10-23T00:00:00Z");
    private final PlatformModelCatalogService models=mock(PlatformModelCatalogService.class);
    private final EmbeddingClient client=mock(EmbeddingClient.class);
    private final ExecutionBudgetPort budgets=mock(ExecutionBudgetPort.class);
    private final MemorySearchEmbeddingBridge bridge=new MemorySearchEmbeddingBridge(models,client,budgets,Clock.fixed(NOW,ZoneOffset.UTC));

    @Test void restoresOriginalResponsibilityAndRegisteredBudgetWithoutLeakingThreadCost() {
        var request=request();configureModel();
        var original=request.context().runIdentity();
        var registration=new ExecutionBudgetPort.Registration(original.runRef(),new RunRef(original.runRef().tenantId(),"real-parent-root"),
                ExecutionBudgetPort.Role.MEMBER,new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD,"original-policy"));
        when(budgets.find(original.runRef())).thenReturn(Optional.of(registration));
        when(client.embedBatch(eq("101"),any(),eq(request.invocationId()),eq(List.of("正式正文")),any(Duration.class),any()))
                .thenAnswer(call->{
                    var cost=MeterScope.current();
                    assertEquals("original-input",cost.requestId());assertEquals("root-input",cost.rootInputId());
                    assertEquals("original-session",cost.sessionId());assertEquals(7L,cost.endUserId());
                    assertEquals(202L,cost.agentId());assertEquals("original-run",cost.runId());
                    assertNull(cost.channelId());
                    assertEquals(original.runRef(),ExecutionBudgetScope.current().orElseThrow().run());
                    return new EmbeddingClient.Outcome.Available(List.of(new float[]{1,2,3}));
                });
        CostContext unrelated=CostContext.builder().sessionId("unrelated-session").channelId("other-channel").build();
        try(var ignored=MeterScope.openIsolated(unrelated)) {
            var result=assertInstanceOf(MemorySearchEmbedding.Outcome.Available.class,bridge.embed(request));
            assertEquals(List.of(1d,2d,3d),result.vector());
            assertSame(unrelated,MeterScope.current());
            assertTrue(ExecutionBudgetScope.current().isEmpty());
        }
        verify(budgets,never()).register(any());
    }

    @Test void unregisteredSourceLeavesBudgetScopeEmptyForExistingStableMaintenanceBranchAndKeepsUnknown() {
        var request=request();configureModel();when(budgets.find(any())).thenReturn(Optional.empty());
        when(client.embedBatch(eq("101"),any(),eq(request.invocationId()),anyList(),any(Duration.class),any()))
                .thenAnswer(call->{
                    assertTrue(ExecutionBudgetScope.current().isEmpty());
                    assertEquals("original-run",MeterScope.current().runId());
                    return new EmbeddingClient.Outcome.OutcomeUnknown(request.invocationId());
                });
        var result=assertInstanceOf(MemorySearchEmbedding.Outcome.OutcomeUnknown.class,bridge.embed(request));
        assertEquals("memory_embedding_outcome_unknown",result.reasonCode());
        verify(client,times(1)).embedBatch(eq("101"),any(),eq("stable-memory-embedding"),anyList(),any(Duration.class),any());
        verify(budgets,never()).register(any());
    }

    @Test void refusesAnUnrelatedBudgetScopeBeforeCallingTheModel() {
        configureModel();var request=request();
        try(var ignored=ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(budgets,new RunRef(new TenantId("101"),"other-run")))) {
            var failure=assertInstanceOf(MemorySearchEmbedding.Outcome.Rejected.class,bridge.embed(request));
            assertEquals("memory_embedding_budget_identity_mismatch",failure.reasonCode());
        }
        verifyNoInteractions(client);
    }

    @Test void currentModelDisableStopsDispatchWithoutReplacingFrozenRevision() {
        when(models.requireEnabled("901","EMBEDDING")).thenThrow(new com.skillforge.core.shared.exception.ApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"platform_model_disabled"));
        var failure=assertInstanceOf(MemorySearchEmbedding.Outcome.Rejected.class,bridge.embed(request()));
        assertEquals("memory_embedding_model_unavailable",failure.reasonCode());
        verify(models,never()).readRevision(anyString(),anyLong());
        verifyNoInteractions(client);
    }

    private void configureModel() {
        var model=mock(PlatformModelConfig.class);
        when(model.id()).thenReturn("901");when(model.revision()).thenReturn(2L);when(model.kind()).thenReturn("EMBEDDING");
        when(model.enabled()).thenReturn(true);when(model.dimensions()).thenReturn(3);
        when(models.requireEnabled("901","EMBEDDING")).thenReturn(model);
        when(models.readRevision("901",2)).thenReturn(model);
    }
    private static MemorySearchEmbedding.Request request() {
        var tenant=new TenantId("101");var agent=new AgentId("202");var input=new InputId("original-input");
        var owner=SessionOwner.user(new SessionRef(tenant,"original-session"),agent,new EndUserId(7));
        var run=new RunIdentity(owner,agent,input,new RunRef(tenant,"original-run"),
                new TraceEnvelope("original-trace","original-span",new InputId("root-input"),Optional.empty()),new ResultRef("original-identity"),
                new RunRequester.EndUser(new EndUserId(7)),Optional.empty(),new RunOriginRef(tenant,"original-origin"));
        var verified=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,new EndUserId(7)),owner,Set.of("memory:read"),3,new ResultRef("original-verification"));
        return new MemorySearchEmbedding.Request(new PortCallContext(run,verified,new InvocationControl(NOW.plusSeconds(10),()->false)),
                "stable-memory-embedding",new MemoryRetrievalPolicy.EmbeddingModel("901",2,3),"正式正文");
    }
}
