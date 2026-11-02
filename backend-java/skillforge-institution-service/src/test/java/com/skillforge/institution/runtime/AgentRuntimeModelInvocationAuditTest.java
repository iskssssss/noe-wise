package com.skillforge.institution.runtime;

import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.metering.UsageSource;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 真实直接登记值和已加载Binding是唯一来源；纯unit不代替Store/IAM/数据库验收。 */
class AgentRuntimeModelInvocationAuditTest {
    @AfterEach void clearScopes() { TenantContext.clear(); ExternalInvocationScope.clear(); MeterScope.clear(); }

    @Test void capturedBindingAndActualAttemptDriveStartUsagePricingAndTerminalWithoutNewSourceRead() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var transactions = transactions();
        var f = fixture("platform-model:81:3"); var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions, WorkflowResourceOriginFixture.ordinary())
                .dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var provider = new Provider("provider", "service", "returned-model-alias");
        var usage = new ModelUsage(OptionalLong.of(10), OptionalLong.of(5));
        when(ledger.start(eq("7"), any(), any())).thenReturn(true, false);
        when(ledger.finish(eq("7"), eq("direct-attempt"), any(), isNull(), any(), any())).thenReturn(true);
        var context = new AtomicReference<CostContext>();
        doAnswer(call -> { context.set(MeterScope.current()); return null; }).when(recorder).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        TenantContext.setTenantId("original-worker");
        assertTrue(audit.start(f.call(), provider)); assertFalse(audit.start(f.call(), provider));
        audit.usageReported(f.call(), provider, 1, usage); audit.succeeded(f.call(), provider, usage);
        assertEquals("original-worker", TenantContext.getTenantId());
        var requests = org.mockito.ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(ledger, times(2)).start(eq("7"), requests.capture(), any());
        assertEquals("direct-action", requests.getValue().invocationId()); assertEquals("direct-attempt", requests.getValue().attemptId());
        assertEquals("direct-request", assertInstanceOf(com.skillforge.core.shared.tracing.TraceContext.class,
                requests.getValue().context()).eventId());
        verify(recorder, times(2)).recordUsageReport(eq("7"), any(), any(), eq("returned-model-alias"), anyLong(), any(), any());
        verify(recorder).recordLlm(eq("7"), eq(CostType.LLM_CHAT), eq("provider"), eq("returned-model-alias"), any(), eq(1),
                eq(new ModelRevisionRef("81", 3)), eq(Map.of("runtime_run_id", "run-1")));
        assertEquals("run-1", context.get().runId()); assertEquals("root-1", context.get().rootInputId());
        assertEquals("agent-runtime.model", context.get().callPoint()); assertNull(context.get().executionId());
        verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.SUCCEEDED), isNull(), isNull(), any());
        verify(transactions, times(2)).commit(any()); assertNull(MeterScope.current()); assertNull(ExternalInvocationScope.current());
    }

    @Test void failedAttemptUsesFrozenPricingAndRollsBackOnCostFailure() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var transactions = transactions();
        var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions, WorkflowResourceOriginFixture.ordinary()).dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var provider = new Provider("provider", "service", "alias");
        when(ledger.finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), eq("rejected"), any())).thenReturn(true);
        audit.failed(f.call(), provider, "rejected");
        verify(recorder).recordFailedModelUsage(eq("7"), any(), any(), eq("alias"), eq(new ModelRevisionRef("81", 3)), any());
        verify(transactions).commit(any());
        doThrow(new IllegalStateException("cost_failed")).when(recorder).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        assertEquals("cost_failed", assertThrows(IllegalStateException.class, () -> audit.failed(f.call(), provider, "rejected")).getMessage());
        verify(transactions).rollback(any());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failedAuditAttemptsUnknownInSeparateTransactionAndAlwaysPropagatesOriginal(boolean updated) {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var transactions = transactions();
        var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions, WorkflowResourceOriginFixture.ordinary())
                .dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var original = new IllegalStateException("cost_failed");
        when(ledger.finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), eq("rejected"), any())).thenReturn(true);
        when(ledger.finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("MODEL_TERMINAL_AUDIT_FAILED"), any()))
                .thenAnswer(call -> { assertEquals("7", TenantContext.getTenantId()); return updated; });
        doThrow(original).when(recorder).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        TenantContext.setTenantId("previous");
        assertSame(original, assertThrows(IllegalStateException.class, () -> audit.failed(f.call(), new Provider("p", "s", "m"), "rejected")));
        assertEquals("previous", TenantContext.getTenantId());
        var order = inOrder(ledger, recorder);
        order.verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), eq("rejected"), any());
        order.verify(recorder).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        order.verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("MODEL_TERMINAL_AUDIT_FAILED"), any());
        verify(transactions).rollback(any()); verify(transactions).commit(any());
        var definitions = org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definitions.capture());
        assertTrue(definitions.getAllValues().stream().allMatch(definition ->
                definition.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test void failedAuditPreservesOriginalAndFallbackFailures() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var transactions = transactions();
        var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions, WorkflowResourceOriginFixture.ordinary())
                .dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var original = new IllegalStateException("cost_failed"); var fallback = new IllegalStateException("fallback_failed");
        when(ledger.finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), eq("rejected"), any())).thenReturn(true);
        doThrow(original).when(recorder).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        doThrow(fallback).when(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("MODEL_TERMINAL_AUDIT_FAILED"), any());
        assertSame(original, assertThrows(IllegalStateException.class, () -> audit.failed(f.call(), new Provider("p", "s", "m"), "rejected")));
        assertArrayEquals(new Throwable[]{fallback}, original.getSuppressed());
        verify(transactions, times(2)).rollback(any()); verify(transactions, never()).commit(any());
    }

    @Test void changedActorAttemptRequestTargetStrategyAndSnapshotRejectBeforeLedger() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class);
        var f = fixture("platform-model:81:3"); var host = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions(), WorkflowResourceOriginFixture.ordinary());
        var audit = host.dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var original = f.call().context().callContext(); var direct = f.call().context().dispatch();
        var changedActor = new VerifiedIdentity(original.identity().principal(), original.identity().owner(), original.identity().scopes(),
                original.identity().policyRevision(), new ResultRef("changed-verification"));
        var actorContext = ProviderCallContext.dispatched(new PortCallContext(original.runIdentity(), changedActor, original.control()), direct);
        var attemptContext = ProviderCallContext.dispatched(original, new ProviderDispatch(direct.run(), direct.action(), direct.invocationId(), "other-attempt",
                direct.callKind(), direct.requestRef(), direct.capabilityRef(), direct.fingerprint(), direct.fence()));
        var requestContext = ProviderCallContext.dispatched(original, new ProviderDispatch(direct.run(), direct.action(), direct.invocationId(), direct.attemptId(),
                direct.callKind(), new ResultRef("other-request"), direct.capabilityRef(), direct.fingerprint(), direct.fence()));
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId("7"), "other-binding", 1), f.call().target().effectiveModelId(),
                f.call().target().capabilities(), f.call().target().timeout(), f.call().target().resolutionRef());
        var bad = List.of(call(f, actorContext, f.call().requestedStrategy(), f.call().target(), f.call().invocationSnapshotRef()),
                call(f, attemptContext, f.call().requestedStrategy(), f.call().target(), f.call().invocationSnapshotRef()),
                call(f, requestContext, f.call().requestedStrategy(), f.call().target(), f.call().invocationSnapshotRef()),
                call(f, f.call().context(), f.call().requestedStrategy(), target, f.call().invocationSnapshotRef()),
                call(f, f.call().context(), new RequestedModelStrategy("other-strategy", "v1"), f.call().target(), f.call().invocationSnapshotRef()),
                call(f, f.call().context(), f.call().requestedStrategy(), f.call().target(), new ResultRef("other-snapshot")));
        for (var changed : bad) assertThrows(IllegalArgumentException.class, () -> audit.start(changed, new Provider("p", "s", "m")));
        var nonModel = RegisteredProviderFixture.create(original.runIdentity(), original.identity(), ExecutionStore.Kind.TOOL, PortalCallKind.TOOL_INVOKE, "tool", new TypedPayload("request.v1", "{}"));
        assertThrows(IllegalArgumentException.class, () -> host.dispatched(nonModel.registered(), nonModel.prepared(), f.binding()));
        var invalidSource = fixture("platform-model:81:latest");
        assertThrows(IllegalStateException.class, () -> host.dispatched(invalidSource.receipt().registered(), invalidSource.receipt().prepared(), invalidSource.binding()));
        verifyNoInteractions(ledger, recorder);
    }

    @Test void partialUsageAndUnknownKeepActualRevisionAndNullUnreportedDimensions() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions(), WorkflowResourceOriginFixture.ordinary()).dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var provider = new Provider("provider", "service", "returned-model-alias");
        when(ledger.start(eq("7"), any(), any())).thenReturn(true);
        when(ledger.finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), any(), any())).thenReturn(true);
        assertTrue(audit.start(f.call(), provider));
        audit.usageReported(f.call(), provider, 3, new ModelUsage(OptionalLong.of(7), OptionalLong.empty(), OptionalLong.of(2), OptionalLong.empty(), OptionalLong.empty()));
        audit.unresolved(f.call(), provider, "provider_connection_lost");
        var revisions = org.mockito.ArgumentCaptor.forClass(Long.class); var reports = org.mockito.ArgumentCaptor.forClass(LlmUsage.class);
        verify(recorder, times(2)).recordUsageReport(eq("7"), any(), any(), anyString(), revisions.capture(), reports.capture(), any());
        assertEquals(List.of(0L, 3L), revisions.getAllValues());
        assertEquals(UsageSource.UNKNOWN, reports.getAllValues().getFirst().usageSource()); assertNull(reports.getAllValues().getFirst().promptTokens());
        var reported = reports.getAllValues().getLast(); assertEquals(7L, reported.promptTokens()); assertEquals(2L, reported.cachedInputTokens());
        assertNull(reported.completionTokens()); assertNull(reported.totalTokens());
        verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("provider_connection_lost"), any());
        verify(recorder, never()).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = "8")
    void asynchronousCallbacksBindOriginalTenantAndRestoreReusedThread(String previousTenant) throws Exception {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions(), WorkflowResourceOriginFixture.ordinary()).dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        when(ledger.start(eq("7"), any(), any())).thenAnswer(call -> { assertEquals("7", TenantContext.getTenantId()); return true; });
        when(ledger.finish(eq("7"), eq("direct-attempt"), any(), isNull(), any(), any())).thenAnswer(call -> { assertEquals("7", TenantContext.getTenantId()); return true; });
        doAnswer(call -> { assertEquals("7", TenantContext.getTenantId()); return null; }).when(recorder).recordUsageReport(any(), any(), any(), any(), anyLong(), any(), any());
        doAnswer(call -> { assertEquals("7", TenantContext.getTenantId()); return null; }).when(recorder).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        TenantContext.setTenantId("parent");
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                if (previousTenant != null) TenantContext.setTenantId(previousTenant);
                try {
                    var provider = new Provider("p", "s", "m"); var usage = new ModelUsage(OptionalLong.of(1), OptionalLong.of(1));
                    assertTrue(audit.start(f.call(), provider)); assertEquals(previousTenant, TenantContext.getTenantId());
                    audit.usageReported(f.call(), provider, 1, usage); assertEquals(previousTenant, TenantContext.getTenantId());
                    audit.succeeded(f.call(), provider, usage); assertEquals(previousTenant, TenantContext.getTenantId());
                    audit.failed(f.call(), provider, "rejected"); assertEquals(previousTenant, TenantContext.getTenantId());
                    audit.unresolved(f.call(), provider, "unknown"); assertEquals(previousTenant, TenantContext.getTenantId());
                } finally { TenantContext.clear(); }
            }).get(10, TimeUnit.SECONDS);
        }
        assertEquals("parent", TenantContext.getTenantId());
    }

    @Test void startFailureRollsBackAndRestoresTenantBeforePropagatingWithoutUsage() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var transactions = transactions(); var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions, WorkflowResourceOriginFixture.ordinary()).dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        when(ledger.start(eq("7"), any(), any())).thenThrow(new IllegalStateException("ledger_failed"));
        TenantContext.setTenantId("8");
        assertEquals("ledger_failed", assertThrows(IllegalStateException.class, () -> audit.start(f.call(), new Provider("p", "s", "m"))).getMessage());
        assertEquals("8", TenantContext.getTenantId()); verify(transactions).rollback(any()); verify(transactions, never()).commit(any()); verifyNoInteractions(recorder);
    }

    @Test void usageRecordingFailureKeepsOriginalAttemptUnresolvedAndTerminalConflictVisible() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class); var f = fixture("platform-model:81:3");
        var audit = new AgentRuntimeModelInvocationAudit(ledger, recorder, transactions(), WorkflowResourceOriginFixture.ordinary()).dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding());
        var provider = new Provider("p", "s", "m");
        doThrow(new IllegalStateException("usage_failed")).when(recorder).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
        when(ledger.finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), any(), any())).thenReturn(true);
        assertEquals("usage_failed", assertThrows(IllegalStateException.class, () -> audit.succeeded(f.call(), provider,
                new ModelUsage(OptionalLong.of(1), OptionalLong.of(1)))).getMessage());
        verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("USAGE_RECORDING_FAILED"), any());
        assertEquals("model_attempt_terminal_conflict", assertThrows(IllegalStateException.class, () -> audit.failed(f.call(), provider, "late_terminal")).getMessage());
        audit.unresolved(f.call(), provider, "still_unknown"); assertNull(MeterScope.current()); assertNull(ExternalInvocationScope.current());
    }

    private static PlatformTransactionManager transactions() {
        var transactions = mock(PlatformTransactionManager.class); when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus()); return transactions;
    }
    private static ModelCall call(DirectModel f, ProviderCallContext context, RequestedModelStrategy strategy, FrozenModelTarget target, ResultRef snapshot) {
        return new ModelCall(context, strategy, target, snapshot, f.call().messages(), f.call().response(), f.call().generation(), f.call().reasoning());
    }
    @Test void workflowModelKeepsActualRunAndFreezesParentAttributionOnce() {
        var ledger=mock(ExternalInvocationLedger.class);var recorder=mock(CostRecorder.class);var f=fixture("platform-model:81:3");
        var nodes=mock(AgentRuntimeWorkflowNodeExecutions.class);var actual=f.receipt().registered().run().identity();
        var parent=new RunIdentity(actual.owner(),actual.agentId(),actual.causationInputId(),new RunRef(actual.runRef().tenantId(),"workflow-parent"),
                actual.trace(),actual.causationIdentityRef(),actual.requester(),actual.sourceMessageRef(),actual.originRef());
        when(nodes.resourceOrigin(f.receipt().registered().run())).thenReturn(parent);
        when(ledger.start(any(),any(),any())).thenReturn(true);
        var audit=new AgentRuntimeModelInvocationAudit(ledger,recorder,transactions(),WorkflowResourceOriginFixture.provider(nodes))
                .dispatched(f.receipt().registered(),f.receipt().prepared(),f.binding());
        assertTrue(audit.start(f.call(),new Provider("p","s","model")));
        var captured=org.mockito.ArgumentCaptor.forClass(CostContext.class);
        verify(recorder).recordUsageReport(eq("7"),captured.capture(),any(),anyString(),eq(0L),any(),any());
        assertEquals(actual.runRef().runId(),captured.getValue().runId());assertEquals("workflow-parent",captured.getValue().attributionRunId());
        verify(nodes,times(1)).resourceOrigin(f.receipt().registered().run());
    }

    private static DirectModel fixture(String source) {
        var tenant = new TenantId("7"); var input = new InputId("input-1");
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"), new AgentId("11"), new EndUserId(9));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run-1"), new TraceEnvelope("trace-1", "span-1", new InputId("root-1"), Optional.empty()), new ResultRef("causation"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner, Set.of("sessions.write"), 1, new ResultRef("direct-verified"));
        var target = new FrozenModelTarget(new ModelBindingRef(tenant, "chat", 12), "frozen-model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("model-capability")), Duration.ofSeconds(5), new ResultRef("resolution"));
        var strategy = new RequestedModelStrategy("chat", "v1");
        var binding = new JdbcModelBindings.Binding(target, strategy, "http://127.0.0.1:1", "encrypted-fixture",
                new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1"), source);
        var receipt = RegisteredProviderFixture.create(run, actor, ExecutionStore.Kind.MODEL, PortalCallKind.MODEL_STREAM, "chat", new TypedPayload("request.v1", "{}"));
        var call = new ModelCall(receipt.context(), strategy, target, receipt.prepared().request().reference(), List.of(new ModelMessage("message", ModelRole.USER, "hello")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "direct-audit-fixture"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        return new DirectModel(receipt, binding, call);
    }
    private record DirectModel(RegisteredProviderFixture receipt, JdbcModelBindings.Binding binding, ModelCall call) { }
}
