package com.skillforge.systemagent.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 异步审计的每个宿主访问均绑定受理租户，退出时恢复工作线程的原上下文。 */
class SystemAgentModelInvocationAuditTest {

    private static final String TENANT = "7";
    private static final Provider PROVIDER = new Provider("provider", "service", "model-alias");
    private static final ModelUsage USAGE = new ModelUsage(OptionalLong.of(10), OptionalLong.of(5));

    @AfterEach
    void clearScopes() {
        TenantContext.clear();
        ExternalInvocationScope.clear();
        MeterScope.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "usageReported", "succeeded", "failed", "unresolved"})
    void emptyProviderThreadBindsTenantForEveryCallbackAndClearsItAfterward(String callback) throws Exception {
        Fixture fixture = fixture();
        TenantContext.setTenantId("99");
        try (var worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "model-provider-prepare"))) {
            worker.submit(() -> {
                assertNull(TenantContext.getTenantId(), "工作线程不得继承提交方租户");
                invoke(fixture, callback);
                assertNull(TenantContext.getTenantId(), "完成后不得遗留本次审计租户");
                assertNull(ExternalInvocationScope.current());
                assertNull(MeterScope.current());
            }).get(5, TimeUnit.SECONDS);
        }
        assertEquals("99", TenantContext.getTenantId());
        assertEquals(expectedEvents(callback), fixture.events());
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "usageReported", "succeeded", "failed", "unresolved"})
    void everyCallbackRestoresThePreviouslyBoundTenant(String callback) {
        Fixture fixture = fixture();
        TenantContext.setTenantId("99");
        invoke(fixture, callback);
        assertEquals("99", TenantContext.getTenantId());
        assertEquals(expectedEvents(callback), fixture.events());
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "usageReported", "succeeded", "failed", "unresolved"})
    void everyCallbackRestoresTenantAfterHostFailure(String callback) {
        for (String previous : new String[] {null, "99"}) {
            Fixture fixture = fixture();
            IllegalStateException failure = new IllegalStateException("host-audit-failed");
            fail(fixture, callback, failure);
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(fixture, callback)));
            assertEquals(previous, TenantContext.getTenantId());
            assertNull(ExternalInvocationScope.current());
            assertNull(MeterScope.current());
            if ("succeeded".equals(callback)) {
                assertEquals(List.of("llm-failure",
                        "finish:UNRESOLVED:USAGE_RECORDING_FAILED"), fixture.events());
            }
        }
    }

    @Test
    void failedAttemptUsesFrozenPricingAndRollsBackOnCostFailure() {
        Fixture fixture = fixture();
        fixture.audit().failed(fixture.call(), PROVIDER, "PROVIDER_REJECTED");
        verify(fixture.recorder()).recordFailedModelUsage(eq(TENANT), any(), any(), eq("model-alias"),
                eq(new ModelRevisionRef("81", 3)), any());
        verify(fixture.transactions()).commit(any());
        doThrow(new IllegalStateException("cost_failed")).when(fixture.recorder())
                .recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        assertEquals("cost_failed", assertThrows(IllegalStateException.class,
                () -> fixture.audit().failed(fixture.call(), PROVIDER, "PROVIDER_REJECTED")).getMessage());
        verify(fixture.transactions()).rollback(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedAuditAttemptsUnknownInSeparateTransactionAndAlwaysPropagatesOriginal(boolean updated) {
        Fixture fixture = fixture();
        var original = new IllegalStateException("cost_failed");
        doThrow(original).when(fixture.recorder()).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        doAnswer(call -> { assertTenant(); return updated; }).when(fixture.ledger()).finish(eq(TENANT), eq("direct-attempt"),
                eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("MODEL_TERMINAL_AUDIT_FAILED"), any());
        TenantContext.setTenantId("99");
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> fixture.audit().failed(fixture.call(), PROVIDER, "PROVIDER_REJECTED")));
        assertEquals("99", TenantContext.getTenantId());
        var order = inOrder(fixture.ledger(), fixture.recorder());
        order.verify(fixture.ledger()).finish(eq(TENANT), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), eq("PROVIDER_REJECTED"), any());
        order.verify(fixture.recorder()).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        order.verify(fixture.ledger()).finish(eq(TENANT), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("MODEL_TERMINAL_AUDIT_FAILED"), any());
        verify(fixture.transactions()).rollback(any()); verify(fixture.transactions()).commit(any());
        var definitions = org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(fixture.transactions(), times(2)).getTransaction(definitions.capture());
        assertTrue(definitions.getAllValues().stream().allMatch(definition ->
                definition.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void failedAuditPreservesOriginalAndFallbackFailures() {
        Fixture fixture = fixture();
        var original = new IllegalStateException("cost_failed"); var fallback = new IllegalStateException("fallback_failed");
        doThrow(original).when(fixture.recorder()).recordFailedModelUsage(any(), any(), any(), any(), any(), any());
        doThrow(fallback).when(fixture.ledger()).finish(eq(TENANT), eq("direct-attempt"),
                eq(ExternalInvocationStatus.UNRESOLVED), isNull(), eq("MODEL_TERMINAL_AUDIT_FAILED"), any());
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> fixture.audit().failed(fixture.call(), PROVIDER, "PROVIDER_REJECTED")));
        assertArrayEquals(new Throwable[]{fallback}, original.getSuppressed());
        verify(fixture.transactions(), times(2)).rollback(any()); verify(fixture.transactions(), never()).commit(any());
    }

    @Test
    void duplicateStartDoesNotRecordInitialUsage() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            assertTenant();
            return false;
        }).when(fixture.ledger()).start(anyString(), any(), any());
        assertFalse(fixture.audit().start(fixture.call(), PROVIDER));
        assertTrue(fixture.events().isEmpty());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void terminalConflictStillFailsAndRestoresCallerTenant() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            assertTenant();
            return false;
        }).when(fixture.ledger()).finish(anyString(), anyString(), any(), any(), any(), any());
        TenantContext.setTenantId("99");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.audit().failed(fixture.call(), PROVIDER, "PROVIDER_REJECTED"));
        assertEquals("model_attempt_terminal_conflict", failure.getMessage());
        assertEquals("99", TenantContext.getTenantId());
    }

    @Test
    void actualActionAttemptAndRequestDriveTheStartFact() {
        Fixture fixture = fixture();
        assertTrue(fixture.audit().start(fixture.call(), PROVIDER));
        var requests = org.mockito.ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(fixture.ledger()).start(eq(TENANT), requests.capture(), any());
        assertEquals("direct-action", requests.getValue().invocationId());
        assertEquals("direct-attempt", requests.getValue().attemptId());
        assertEquals("direct-request", assertInstanceOf(com.skillforge.core.shared.tracing.TraceContext.class,
                requests.getValue().context()).eventId());
        verify(fixture.transactions()).commit(any());
    }

    @Test
    void partialUsageAndUnknownPreserveRevisionAndUnreportedDimensions() {
        Fixture fixture = fixture();
        assertTrue(fixture.audit().start(fixture.call(), PROVIDER));
        fixture.audit().usageReported(fixture.call(), PROVIDER, 3,
                new ModelUsage(OptionalLong.of(7), OptionalLong.empty(), OptionalLong.of(2), OptionalLong.empty(), OptionalLong.empty()));
        fixture.audit().unresolved(fixture.call(), PROVIDER, "provider_connection_lost");
        var revisions = org.mockito.ArgumentCaptor.forClass(Long.class);
        var reports = org.mockito.ArgumentCaptor.forClass(LlmUsage.class);
        verify(fixture.recorder(), times(2)).recordUsageReport(eq(TENANT), any(), any(), anyString(), revisions.capture(), reports.capture(), any());
        assertEquals(List.of(0L, 3L), revisions.getAllValues());
        assertEquals(UsageSource.UNKNOWN, reports.getAllValues().getFirst().usageSource());
        assertNull(reports.getAllValues().getFirst().promptTokens());
        assertNull(reports.getAllValues().getFirst().completionTokens());
        var reported = reports.getAllValues().getLast();
        assertEquals(7L, reported.promptTokens()); assertEquals(2L, reported.cachedInputTokens());
        assertNull(reported.completionTokens()); assertNull(reported.totalTokens());
        verify(fixture.ledger()).finish(eq(TENANT), eq("direct-attempt"), eq(ExternalInvocationStatus.UNRESOLVED),
                isNull(), eq("provider_connection_lost"), any());
        verify(fixture.recorder(), never()).recordLlm(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initialReportFailureRollsBackStartTransactionAndRestoresTenant() {
        Fixture fixture = fixture();
        var failure = new IllegalStateException("initial-report-failed");
        doThrow(failure).when(fixture.recorder()).recordUsageReport(anyString(), any(), any(), anyString(), eq(0L), any(), any());
        TenantContext.setTenantId("99");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.audit().start(fixture.call(), PROVIDER)));
        verify(fixture.transactions()).rollback(any()); verify(fixture.transactions(), never()).commit(any());
        assertEquals("99", TenantContext.getTenantId());
    }

    @Test
    void changedActorAttemptRequestTargetStrategyAndSnapshotRejectBeforeLedger() {
        var ledger = mock(ExternalInvocationLedger.class); var recorder = mock(CostRecorder.class);
        var direct = direct("platform-model:81:3");
        var host = new SystemAgentModelInvocationAudit(ledger, recorder, transactions());
        var audit = host.dispatched(direct.receipt().registered(), direct.receipt().prepared(), direct.binding());
        var original = direct.call().context().callContext(); var dispatch = direct.call().context().dispatch();
        var changedActor = new VerifiedIdentity(original.identity().principal(), original.identity().owner(), original.identity().scopes(),
                original.identity().policyRevision(), new ResultRef("changed-verification"));
        var actorContext = ProviderCallContext.dispatched(new PortCallContext(original.runIdentity(), changedActor, original.control()), dispatch);
        var attemptContext = ProviderCallContext.dispatched(original, new ProviderDispatch(dispatch.run(), dispatch.action(), dispatch.invocationId(),
                "other-attempt", dispatch.callKind(), dispatch.requestRef(), dispatch.capabilityRef(), dispatch.fingerprint(), dispatch.fence()));
        var requestContext = ProviderCallContext.dispatched(original, new ProviderDispatch(dispatch.run(), dispatch.action(), dispatch.invocationId(),
                dispatch.attemptId(), dispatch.callKind(), new ResultRef("other-request"), dispatch.capabilityRef(), dispatch.fingerprint(), dispatch.fence()));
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId(TENANT), "other-binding", 1), direct.call().target().effectiveModelId(),
                direct.call().target().capabilities(), direct.call().target().timeout(), direct.call().target().resolutionRef());
        var bad = List.of(changed(direct, actorContext, direct.call().requestedStrategy(), direct.call().target(), direct.call().invocationSnapshotRef()),
                changed(direct, attemptContext, direct.call().requestedStrategy(), direct.call().target(), direct.call().invocationSnapshotRef()),
                changed(direct, requestContext, direct.call().requestedStrategy(), direct.call().target(), direct.call().invocationSnapshotRef()),
                changed(direct, direct.call().context(), direct.call().requestedStrategy(), target, direct.call().invocationSnapshotRef()),
                changed(direct, direct.call().context(), new RequestedModelStrategy("other-strategy", "v1"), direct.call().target(), direct.call().invocationSnapshotRef()),
                changed(direct, direct.call().context(), direct.call().requestedStrategy(), direct.call().target(), new ResultRef("other-snapshot")));
        for (var call : bad) assertThrows(IllegalArgumentException.class, () -> audit.start(call, PROVIDER));
        var nonModel = RegisteredProviderFixture.create(original.runIdentity(), original.identity(), ExecutionStore.Kind.TOOL,
                PortalCallKind.TOOL_INVOKE, "tool", new TypedPayload("request.v1", "{}"));
        assertThrows(IllegalArgumentException.class, () -> host.dispatched(nonModel.registered(), nonModel.prepared(), direct.binding()));
        var invalid = direct("platform-model:81:latest");
        assertThrows(IllegalStateException.class, () -> host.dispatched(invalid.receipt().registered(), invalid.receipt().prepared(), invalid.binding()));
        verifyNoInteractions(ledger, recorder);
    }

    private static ModelCall changed(DirectModel fixture, ProviderCallContext context, RequestedModelStrategy strategy,
            FrozenModelTarget target, ResultRef snapshot) {
        return new ModelCall(context, strategy, target, snapshot, fixture.call().messages(), fixture.call().response(),
                fixture.call().generation(), fixture.call().reasoning());
    }

    private static Fixture fixture() {
        ExternalInvocationLedger ledger = mock(ExternalInvocationLedger.class);
        CostRecorder recorder = mock(CostRecorder.class);
        List<String> events = new ArrayList<>();
        DirectModel direct = direct("platform-model:81:3");
        ModelCall call = direct.call();
        when(ledger.start(anyString(), any(), any())).thenAnswer(invocation -> {
            assertTenant();
            assertEquals(TENANT, invocation.getArgument(0));
            events.add("start");
            return true;
        });
        when(ledger.finish(anyString(), anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertTenant();
            assertEquals(TENANT, invocation.getArgument(0));
            assertEquals("direct-attempt", invocation.getArgument(1));
            events.add("finish:" + invocation.getArgument(2) + ":" + invocation.getArgument(4));
            return true;
        });
        doAnswer(invocation -> {
            assertTenant();
            assertEquals(TENANT, invocation.getArgument(0));
            events.add("usage:" + invocation.getArgument(4));
            return null;
        }).when(recorder).recordUsageReport(anyString(), any(), any(), anyString(), anyLong(), any(), any());
        doAnswer(invocation -> {
            assertTenant();
            assertEquals(TENANT, invocation.getArgument(0));
            assertEquals(new ModelRevisionRef("81", 3), invocation.getArgument(6));
            assertEquals("direct-attempt", ExternalInvocationScope.current().attemptId());
            assertEquals("system-agent.model", MeterScope.current().callPoint());
            events.add("llm");
            return null;
        }).when(recorder).recordLlm(anyString(), any(), anyString(), anyString(), any(), any(Integer.class), any(), any());
        var transactions = transactions();
        var host = new SystemAgentModelInvocationAudit(ledger, recorder, transactions);
        return new Fixture(host.dispatched(direct.receipt().registered(), direct.receipt().prepared(), direct.binding()),
                ledger, recorder, call, events, transactions);
    }

    private static void invoke(Fixture fixture, String callback) {
        switch (callback) {
            case "start" -> assertTrue(fixture.audit().start(fixture.call(), PROVIDER));
            case "usageReported" -> fixture.audit().usageReported(fixture.call(), PROVIDER, 3, USAGE);
            case "succeeded" -> fixture.audit().succeeded(fixture.call(), PROVIDER, USAGE);
            case "failed" -> fixture.audit().failed(fixture.call(), PROVIDER, "PROVIDER_REJECTED");
            case "unresolved" -> fixture.audit().unresolved(fixture.call(), PROVIDER, "PROVIDER_RESULT_UNKNOWN");
            default -> throw new IllegalArgumentException(callback);
        }
    }

    private static List<String> expectedEvents(String callback) {
        return switch (callback) {
            case "start" -> List.of("start", "usage:0");
            case "usageReported" -> List.of("usage:3");
            case "succeeded" -> List.of("llm", "finish:SUCCEEDED:null");
            case "failed" -> List.of("finish:FAILED:PROVIDER_REJECTED");
            case "unresolved" -> List.of("finish:UNRESOLVED:PROVIDER_RESULT_UNKNOWN");
            default -> throw new IllegalArgumentException(callback);
        };
    }

    private static void fail(Fixture fixture, String callback, RuntimeException failure) {
        if ("start".equals(callback)) {
            doAnswer(invocation -> {
                assertTenant();
                throw failure;
            }).when(fixture.ledger()).start(anyString(), any(), any());
        } else if ("usageReported".equals(callback)) {
            doAnswer(invocation -> {
                assertTenant();
                throw failure;
            }).when(fixture.recorder()).recordUsageReport(anyString(), any(), any(), anyString(), anyLong(), any(), any());
        } else if ("succeeded".equals(callback)) {
            doAnswer(invocation -> {
                assertTenant();
                fixture.events().add("llm-failure");
                throw failure;
            }).when(fixture.recorder()).recordLlm(anyString(), any(), anyString(), anyString(), any(), any(Integer.class), any(), any());
        } else {
            doAnswer(invocation -> {
                assertTenant();
                throw failure;
            }).when(fixture.ledger()).finish(anyString(), anyString(), any(), any(), any(), any());
        }
    }

    private static void assertTenant() {
        assertEquals(TENANT, TenantContext.getTenantId(), "每次宿主访问前必须显式绑定本次Run租户");
    }

    private static PlatformTransactionManager transactions() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return transactions;
    }

    /** 结构合法的纯单测值不代表 Store/IAM/真实数据库已经通过。 */
    private static DirectModel direct(String source) {
        var tenant = new TenantId(TENANT);
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"), new AgentId("11"), new EndUserId(9));
        var identity = new RunIdentity(owner, owner.target().requireAgent(), new InputId("input-1"), new RunRef(tenant, "run-1"),
                new TraceEnvelope("trace-1", "span-1", new InputId("root-1"), Optional.empty()), new ResultRef("causation"),
                new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant,SystemAgentRuntimeContracts.SYSTEM_PRINCIPAL_ID)),
                Optional.empty(), new RunOriginRef(tenant,source));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of("sessions.write"), 1, new ResultRef("verified"));
        var target = new FrozenModelTarget(new ModelBindingRef(tenant, "chat", 12), "frozen-model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("model-capability")),
                Duration.ofSeconds(5), new ResultRef("resolution"));
        var strategy = new RequestedModelStrategy("chat", "v1");
        var binding = new JdbcModelBindings.Binding(target, strategy, "http://127.0.0.1:1", "encrypted-fixture",
                new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                        "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1"), source);
        var receipt = RegisteredProviderFixture.create(identity, actor, ExecutionStore.Kind.MODEL,
                PortalCallKind.MODEL_STREAM, "chat", new TypedPayload("request.v1", "{}"));
        var call = new ModelCall(receipt.context(), strategy, target, receipt.prepared().request().reference(),
                List.of(new ModelMessage("message", ModelRole.USER, "hello")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "direct-audit-fixture"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        return new DirectModel(receipt, binding, call);
    }

    private record DirectModel(RegisteredProviderFixture receipt, JdbcModelBindings.Binding binding, ModelCall call) { }
    private record Fixture(ModelInvocationAudit audit, ExternalInvocationLedger ledger,
                           CostRecorder recorder, ModelCall call, List<String> events,
                           PlatformTransactionManager transactions) { }
}
