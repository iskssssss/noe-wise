package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.integration.runtime.AgentRuntimeMcpToolProvider;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgePortal;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RegisteredExecutionModelBudgetTest {
    private static final ModelUsage USAGE = new ModelUsage(OptionalLong.of(7), OptionalLong.of(3));
    private static final ExecutionBudgetPort.Usage REPORTED = ExecutionBudgetPort.Usage.reported(USAGE.inputTokens(), USAGE.outputTokens());
    private static final ModelInvocationAudit.Provider PROVIDER = new ModelInvocationAudit.Provider("custom", "custom-openai-compatible", "model");
    private static final PortFailure FAILURE = new PortFailure("provider_failure", RetryMode.NONE, new ResultRef("receipt"));

    @Test void nonStreamUsesOnlyThisAttemptSuccessfullyDelegatedUsageAndUnknownDoesNotSettle() {
        var f = new Fixture();
        var scoped = new AtomicReference<ModelInvocationAudit>();
        when(f.models.forDispatch(any(), any(), any(), any())).thenAnswer(invocation -> {
            scoped.set(invocation.getArgument(2)); return f.model;
        });
        when(f.model.generate(any())).thenAnswer(invocation -> {
            ModelCall call = invocation.getArgument(0);
            scoped.get().usageReported(call, PROVIDER, 1, USAGE);
            return new ModelResult.Failed(FAILURE);
        }).thenReturn(new ModelResult.Failed(FAILURE)).thenAnswer(invocation -> {
            scoped.get().usageReported(invocation.getArgument(0), PROVIDER, 1, USAGE);
            return new ModelResult.OutcomeUnknown(FAILURE);
        });
        assertEquals(REPORTED, f.invoke(PortalCallKind.MODEL_GENERATE).usage());
        assertEquals(ExecutionBudgetPort.Usage.unreported(), f.invoke(PortalCallKind.MODEL_GENERATE).usage());
        assertEquals(ExecutionBudgetPort.Usage.unreported(), f.invoke(PortalCallKind.MODEL_GENERATE).usage());
        verify(f.delegate, times(2)).usageReported(any(), eq(PROVIDER), eq(1L), eq(USAGE));
        verify(f.model, times(3)).generate(any());
        verify(f.models, times(3)).forDispatch(any(), any(), any(), any());
    }

    @Test void realStreamPortSettlesLatestCompletedFrameUsageWhenOutputGovernanceRejects() {
        var f = new Fixture();
        var providers = mock(ModelProviderRuntime.class);
        when(providers.supportsInvocationProfile(anyString(), any())).thenReturn(true);
        when(f.delegate.start(any(), any())).thenReturn(true);
        var latest = new ModelUsage(OptionalLong.of(11), OptionalLong.of(5));
        when(providers.stream(anyString(), any())).thenReturn(providerStream(latest));
        try (var http = HttpClient.newHttpClient()) {
            var port = new OpenAiModelPort(ref -> Optional.empty(), cipher -> "key".getBytes(StandardCharsets.UTF_8),
                    call -> true, (call, output) -> false, http, Clock.systemUTC(), 100_000, providers, ModelInvocationAudit.noop());
            var result = f.invoke(PortalCallKind.MODEL_STREAM, f.callsFor(port));
            assertEquals(ExecutionStore.ResultKind.FAILED, result.kind());
            assertEquals(ExecutionBudgetPort.Usage.reported(latest.inputTokens(), latest.outputTokens()), result.usage());
            verify(f.delegate).usageReported(any(), any(), eq(1L), eq(USAGE));
            verify(f.delegate).usageReported(any(), any(), eq(2L), eq(latest));
            verify(f.delegate).failed(any(), any(), eq("model_output_schema_rejected"));
            verify(providers).stream(anyString(), any());
        }
    }

    @Test void realStreamPortDoesNotSettleWhenLatestUsageAuditFails() {
        var f = new Fixture();
        var providers = mock(ModelProviderRuntime.class);
        when(providers.supportsInvocationProfile(anyString(), any())).thenReturn(true);
        when(f.delegate.start(any(), any())).thenReturn(true);
        var latest = new ModelUsage(OptionalLong.of(11), OptionalLong.of(5));
        doThrow(new IllegalStateException("audit_unavailable")).when(f.delegate).usageReported(any(), any(), eq(2L), eq(latest));
        when(providers.stream(anyString(), any())).thenReturn(providerStream(latest));
        try (var http = HttpClient.newHttpClient()) {
            var port = new OpenAiModelPort(ref -> Optional.empty(), cipher -> "key".getBytes(StandardCharsets.UTF_8),
                    call -> true, (call, output) -> false, http, Clock.systemUTC(), 100_000, providers, ModelInvocationAudit.noop());
            var result = f.invoke(PortalCallKind.MODEL_STREAM, f.callsFor(port));
            assertEquals(ExecutionStore.ResultKind.UNKNOWN, result.kind());
            assertEquals(ExecutionBudgetPort.Usage.unreported(), result.usage());
            verify(f.delegate).usageReported(any(), any(), eq(1L), eq(USAGE));
            verify(f.delegate).usageReported(any(), any(), eq(2L), eq(latest));
            verify(providers).stream(anyString(), any());
        }
    }

    @Test void completedUsesResultUsageEvenWhenAuditReportedDifferentUsage() {
        var f = new Fixture();
        var scoped = new AtomicReference<ModelInvocationAudit>();
        when(f.models.forDispatch(any(), any(), any(), any())).thenAnswer(invocation -> {
            scoped.set(invocation.getArgument(2)); return f.model;
        });
        var completedUsage = new ModelUsage(OptionalLong.of(11), OptionalLong.of(5));
        when(f.model.generate(any())).thenAnswer(invocation -> {
            scoped.get().usageReported(invocation.getArgument(0), PROVIDER, 1, USAGE);
            return new ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA, "\"answer\""), "model",
                    completedUsage, "stop", new ResultRef("completed"), Optional.empty());
        });
        assertEquals(ExecutionBudgetPort.Usage.reported(completedUsage.inputTokens(), completedUsage.outputTokens()),
                f.invoke(PortalCallKind.MODEL_GENERATE).usage());
        verify(f.model).generate(any());
    }

    private static Flow.Publisher<ModelProvider.StreamEvent> providerStream(ModelUsage latest) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                subscriber.onNext(new ModelProvider.StreamEvent.UsageDelta(1, USAGE));
                subscriber.onNext(new ModelProvider.StreamEvent.Completed(
                        new ModelProvider.InvocationResult.Completed("answer", List.of(), "model", latest, "stop")));
                subscriber.onComplete();
            }
            public void cancel() { }
        });
    }

    private static final class Fixture {
        final OpenAiModelPort models = mock(OpenAiModelPort.class), model = mock(OpenAiModelPort.class);
        final AgentRuntimeModelInvocationAudit audit = mock(AgentRuntimeModelInvocationAudit.class);
        final ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
        final ExecutionStore store = mock(ExecutionStore.class);
        final CodecLimits limits = new CodecLimits(100_000, 10_000, 100);
        final AgentExecutionCodec codec = new AgentExecutionCodec(limits);
        final TenantId tenant = new TenantId("7");
        final InputId input = new InputId("input");
        final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("11"), new EndUserId(9));
        final RunIdentity identity = new RunIdentity(owner, new AgentId("11"), input, new RunRef(tenant, "run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("causation"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "input:input"));
        final VerifiedIdentity actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of("sessions.write"), 1, identity.causationIdentityRef());
        final FrozenModelTarget target = new FrozenModelTarget(new ModelBindingRef(tenant, "chat", 12), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("capability")),
                Duration.ofSeconds(5), new ResultRef("resolution"));
        final RequestedModelStrategy strategy = new RequestedModelStrategy("chat", "v1");
        final FrozenModelRequest request = new FrozenModelRequest(strategy, target, List.of(new ModelMessage("message", ModelRole.USER, "hello")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "text"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        final JdbcModelBindings.Binding binding = new JdbcModelBindings.Binding(target, strategy, "http://127.0.0.1:1", "encrypted",
                new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1"), "source");
        final RegisteredExecutionProviderCalls calls = callsFor(models);
        RegisteredExecutionProviderCalls callsFor(OpenAiModelPort port) { return new RegisteredExecutionProviderCalls(port, audit, mock(AgentRuntimeToolInvocationAudit.class),
                mock(AgentRuntimePlatformToolProvider.class), mock(AgentRuntimeMcpToolProvider.class), mock(JdbcKnowledgePortal.class),
                store, new CoreRuntime(limits, new SessionRuntimePayloads()), limits, mock(WorkflowTestRuntimePolicy.class)); }
        Fixture() { when(audit.dispatched(any(), any(), any())).thenReturn(delegate); }
        com.skillforge.runtime.session.ExecutionCoordinator.CapabilityResult invoke(PortalCallKind kind) {
            return invoke(kind, calls);
        }
        com.skillforge.runtime.session.ExecutionCoordinator.CapabilityResult invoke(PortalCallKind kind, RegisteredExecutionProviderCalls selected) {
            var encoded = codec.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(request, List.of(), 0, Optional.empty()));
            var receipt = RegisteredProviderFixture.create(identity, actor, ExecutionStore.Kind.MODEL, kind, "chat", encoded);
            return selected.model(receipt.registered(), receipt.prepared(), kind, request, binding,
                    new InvocationControl(Instant.now().plusSeconds(20), () -> false));
        }
    }
}
