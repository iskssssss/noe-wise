package com.skillforge.systemagent.runtime;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.core.*;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.skillforge.runtime.starter.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** typed系统宿主的真实结果与用量映射；SQL、IAM和HTTP仍须其实际集成验证。 */
class SystemAgentExecutionsTest {
    @Test void heartbeatUsesTenSecondsForDefaultLeaseAndAnEarlierThirdForShortLeases() {
        for (var lease : List.of(java.time.Duration.ofSeconds(30), java.time.Duration.ofMillis(300))) {
            var f = new Fixture(); f.properties.setWorkerLeaseDuration(lease);
            when(f.scheduler.scheduleWithFixedDelay(any(), any())).thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
            try {
                f.host.begin(f.run.identity().runRef());
                var expected = lease.equals(java.time.Duration.ofSeconds(30)) ? java.time.Duration.ofSeconds(10) : java.time.Duration.ofMillis(100);
                verify(f.scheduler).scheduleWithFixedDelay(any(), eq(expected));
            } finally { f.host.close(); }
        }
    }
    @Test void oldCatalogBudgetShapeCannotBeReadAsTheNewBusinessCallContract() {
        var f = new Fixture(); var reference = new ResultRef("system-agent-catalog:" + f.run.identity().runRef().runId());
        when(f.store.material(f.run.identity().runRef().tenantId(), reference)).thenReturn(Optional.of(
                new Material(reference, new TypedPayload("skillforge.runtime.system-agent.catalog.v1", "{\"budget\":{\"maxIterations\":5}}"))));
        assertFalse(f.host.owns(f.run));
    }
    @Test void timeSampleUsesInjectedHostClockInstantWithoutInferringItsZoneAsUserTimezone() {
        var f=new Fixture();assertEquals(Instant.parse("2026-10-22T01:02:03Z"),f.host.sampleModelTime(f.run).sampledAt());
        verifyNoInteractions(f.store,f.transport);
    }
    @Test void doesNotDeclareTerminalOrUnownedPlanAndInteractionControls() {
        var profile=mock(SessionPreparations.Profile.class);
        when(profile.contextKind()).thenReturn(SessionAdmissionFacts.ContextKind.AGENT_INPUT);
        when(profile.response()).thenReturn(new ResponseContract(new ResponseFormat.NativeConversation(),EnforcementLevel.STRICT,"native"));
        var tools=SystemAgentExecutions.modelTools(profile,List.of(),Map.of(),List.of());
        assertTrue(tools.isEmpty());
    }

    @Test void completedResultReadsExactTerminalAndSumsOnlyActuallyReportedModelUsage() {
        var f=new Fixture();f.models(new ModelUsage(OptionalLong.of(10),OptionalLong.of(4),OptionalLong.of(2),OptionalLong.empty(),OptionalLong.of(1)),
                new ModelUsage(OptionalLong.of(20),OptionalLong.of(6),OptionalLong.of(3),OptionalLong.empty(),OptionalLong.of(2)));
        var result=f.host.completed(f.run,"business-model");assertEquals("{\"result\":\"完成\"}",result.content());
        assertEquals(30L,result.usage().promptTokens());assertEquals(10L,result.usage().completionTokens());assertEquals(40L,result.usage().totalTokens());
        assertEquals(5L,result.usage().cachedInputTokens());assertNull(result.usage().cacheWriteInputTokens());assertEquals(3L,result.usage().reasoningOutputTokens());
        verify(f.store).event(f.run.identity().runRef(),AgentExecutionProgram.finishedEventId(f.run.identity().runRef()));
        verify(f.store,never()).events(any(),anyLong(),anyInt());verifyNoInteractions(f.transport);
    }

    @Test void missingUsageDimensionStaysUnknownInsteadOfBecomingZeroOrPartialTotal() {
        var f=new Fixture();f.models(new ModelUsage(OptionalLong.of(10),OptionalLong.of(4)),new ModelUsage(OptionalLong.of(20),OptionalLong.empty()));
        var usage=f.host.completed(f.run,"model").usage();assertEquals(30L,usage.promptTokens());assertNull(usage.completionTokens());assertNull(usage.totalTokens());assertNull(usage.cachedInputTokens());
    }

    @Test void entirelyUnreportedUsageKeepsUnknownSource() {
        var f=new Fixture();f.models(new ModelUsage(OptionalLong.empty(),OptionalLong.empty()));
        var usage=f.host.completed(f.run,"model").usage();assertNull(usage.promptTokens());assertNull(usage.completionTokens());
        assertNull(usage.totalTokens());assertEquals(com.skillforge.core.billing.metering.UsageSource.UNKNOWN,usage.usageSource());
    }

    @Test void rejectsTerminalEventFromAnotherRevisionBeforeReturningAnyBusinessResult() {
        var f=new Fixture();when(f.store.event(any(com.skillforge.runtime.api.RunRef.class),any())).thenReturn(Optional.of(new SavedEvent(f.run.identity().runRef(),1,6,Instant.EPOCH,f.terminal)));
        assertThrows(IllegalStateException.class,()->f.host.completed(f.run,"model"));verify(f.store,never()).actions(any(),anyLong(),anyInt());
    }

    @Test void frozenSystemHostInvokesRealSdkHttpOnceAndDeliversItsAuditedResult() throws Exception {
        var f = new Fixture(); var identity = f.run.identity(); var tenant = identity.runRef().tenantId();
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, identity.owner().requireUser()),
                identity.owner(), Set.of("sessions.write"), 1, new ResultRef("verified-system-http"));
        var strategy = new RequestedModelStrategy("chat", "v1");
        var target = new FrozenModelTarget(new ModelBindingRef(tenant, "frozen-system", 12), "system-fixture-model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("system-capability")),
                java.time.Duration.ofSeconds(5), new ResultRef("system-resolution"));
        var contract = new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "system-native");
        var generation = new GenerationOptions(OptionalInt.of(32), OptionalDouble.empty());
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "frozen-system-input"));
        var frozen = new FrozenModelRequest(strategy, target, messages, List.of(), ToolChoice.NONE, contract,
                generation, ReasoningPolicy.DISABLED, ExecutionBudgetPort.Category.BUSINESS_MODEL);
        var receipt = RegisteredProviderFixture.create(identity, actor, Kind.MODEL, PortalCallKind.MODEL_GENERATE,
                "chat", f.codec.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(frozen, List.of(), 0, Optional.empty())));
        var run = receipt.registered().run(); var count = new java.util.concurrent.atomic.AtomicInteger();
        var wire = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var json = new ObjectMapper().findAndRegisterModules();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            count.incrementAndGet(); wire.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            var bytes = ("{\"model\":\"system-fixture-model\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"system-sdk-answer\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var binding = new JdbcModelBindings.Binding(target, strategy,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "frozen-credential",
                new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                        "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1"), "platform-model:10:3");
        var profile = new SessionPreparations.Profile(run.configuration(), SessionPreparations.PROFILE_VERSION,
                SessionPreparations.AGENT_INPUT_RENDERER_VERSION, SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                SessionPrompt.single("system", SessionPrompt.Type.SYSTEM_PURPOSE, "frozen-v1", "frozen-system-prompt"),
                target.binding(), strategy, contract, generation, ReasoningPolicy.DISABLED, PortalCallKind.MODEL_GENERATE,
                new ResultRef("tokenizer"), SessionContextPolicy.defaults(), 20_000, 100_000, 10, false, Set.of(InboundKind.MESSAGE));
        when(f.profiles.preparationProfile(tenant, run.configuration())).thenReturn(Optional.of(profile));
        var bindings = mock(JdbcModelBindings.class);
        when(bindings.findForOwner(target.binding(), identity.agentId())).thenReturn(Optional.of(binding));
        var identities = mock(JdbcIdentityPolicyPort.class);
        when(identities.originalVerification(identity.owner(), run.actorVerificationRef())).thenReturn(Optional.of(actor));
        when(f.store.authorizeSend(receipt.registered(), receipt.prepared())).thenReturn(true);
        var inputRef = new ResultRef("system-http-input");
        when(f.store.material(tenant, inputRef)).thenReturn(Optional.of(new Material(inputRef,
                f.codec.encodeInput(messages, new TypedPayload("input.v1", "{}")))));
        var source = new SystemAgentCapabilitySource.SourceSnapshot("business", "system-agent:11:published:1", 1,
                new com.skillforge.core.agent.systemagent.SystemAgentTypes.Config("用途", "", "frozen-system-prompt", "10", 5, 2, List.of()),
                3, List.of(), SystemAgentCapabilitySource.inputFingerprint("frozen-system-input"));
        var budget = new AgentRuntimeAdmissionConfiguration.Budget(2, 0, 0, 1000, 100, 1100, java.time.Duration.ofSeconds(5));
        var catalog = new SystemAgentExecutions.Catalog(source, budget, List.of(), List.of(), Set.of());
        var catalogRef = new ResultRef("system-agent-catalog:" + identity.runRef().runId());
        when(f.store.material(tenant, catalogRef)).thenReturn(Optional.of(new Material(catalogRef,
                new TypedPayload(SystemAgentExecutions.CATALOG_SCHEMA, json.writeValueAsString(catalog)))));
        var business = mock(SystemAgentRuntimeStore.class); var sources = mock(SystemAgentCapabilitySource.class);
        var row = new SystemAgentRuntimeStore.SourceRow(); row.sourceData = "frozen-source";
        when(business.source(identity.owner(), identity.causationInputId())).thenReturn(Optional.of(row));
        when(sources.decode("frozen-source")).thenReturn(source);
        var resources = new ExecutionBudgetPort.Resources(2, 0, 0, 0, 1000, 100, 1100);
        var policy = new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                resources, resources, resources, 5, 5, "system-http-budget"));
        var budgets = mock(JdbcExecutionBudgets.class);
        when(budgets.snapshot(identity.runRef())).thenReturn(new ExecutionBudgetPort.Snapshot(
                new ExecutionBudgetPort.Registration(identity.runRef(), identity.runRef(), ExecutionBudgetPort.Role.PRIMARY, policy),
                null, null, null, null, null, ExecutionBudgetPort.Phase.BUSINESS, 0, 0, 0));
        var starts = new java.util.concurrent.atomic.AtomicInteger(); var successes = new java.util.concurrent.atomic.AtomicInteger();
        var reported = new java.util.concurrent.atomic.AtomicReference<ModelUsage>();
        var audit = mock(SystemAgentModelInvocationAudit.class);
        when(audit.dispatched(receipt.registered(), receipt.prepared(), binding)).thenReturn(new com.skillforge.runtime.adapter.model.ModelInvocationAudit() {
            public boolean start(ModelCall call, Provider provider) { assertEquals(0, count.get()); return starts.incrementAndGet() == 1; }
            public void succeeded(ModelCall call, Provider provider, ModelUsage usage) { successes.incrementAndGet(); reported.set(usage); }
            public void failed(ModelCall call, Provider provider, String reason) { fail("unexpected known failure: " + reason); }
            public void unresolved(ModelCall call, Provider provider, String reason) { fail("unexpected unknown: " + reason); }
        });
        try (var http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()) {
            var models = new OpenAiModelPort(ref -> { throw new AssertionError("must use captured binding"); },
                    encrypted -> { assertEquals("frozen-credential", encrypted); return "fixture-key".getBytes(java.nio.charset.StandardCharsets.UTF_8); },
                    call -> { throw new AssertionError("must use dispatched host view"); }, (call, output) -> true,
                    http, java.time.Clock.systemUTC(), 100_000);
            @SuppressWarnings("unchecked") var workers = (ObjectProvider<ExecutionHostWorker>) mock(ObjectProvider.class);
            try (var host = new SystemAgentExecutions(f.store, budgets, identities, f.profiles, bindings,
                    mock(SessionTokenCounter.class), sources, business, models, audit, mock(SystemAgentPlatformToolProvider.class),
                    f.properties, json, f.core, workers, f.scheduler, java.time.Clock.systemUTC()); var control = host.open(run)) {
                assertEquals(target, host.configuration(run, inputRef).target());
                var result = host.invoke(receipt.registered(), receipt.prepared());
                assertEquals(ResultKind.SUCCEEDED, result.kind());
                var actual = f.codec.decodeModelOutcome(result.result().content());
                assertTrue(actual instanceof ModelResult.Completed);
                assertEquals("system-sdk-answer", json.readTree(((ModelResult.Completed) actual).output().canonicalJson()).asText());
            }
            var scopedAudit = mock(com.skillforge.runtime.adapter.model.ModelInvocationAudit.class);
            var attemptAudits = mock(SystemAgentModelInvocationAudit.class);
            when(attemptAudits.dispatched(any(), any(), eq(binding))).thenReturn(scopedAudit);
            var mockedTransport = mock(OpenAiModelPort.class);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var provider = new com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider("custom", "service", "model");
            var partial = new ModelUsage(OptionalLong.of(0), OptionalLong.empty());
            var unknownUsage = new ModelUsage(OptionalLong.of(9), OptionalLong.of(2));
            var completedUsage = new ModelUsage(OptionalLong.of(4), OptionalLong.of(3));
            var failure = new PortFailure("model-failed", RetryMode.NONE, new ResultRef("failure"));
            when(mockedTransport.forDispatch(any(), any(), any())).thenAnswer(invocation -> {
                ModelCall call = invocation.getArgument(0);
                com.skillforge.runtime.adapter.model.ModelInvocationAudit exact = invocation.getArgument(2);
                var model = mock(OpenAiModelPort.class);
                when(model.generate(call)).thenAnswer(ignored -> {
                    int index = calls.getAndIncrement();
                    if (index == 0) exact.usageReported(call, provider, 1, partial);
                    if (index == 2 || index == 3) exact.usageReported(call, provider, 1, unknownUsage);
                    if (index == 2) { exact.unresolved(call, provider, "unknown"); return new ModelResult.OutcomeUnknown(failure); }
                    if (index == 3) return new ModelResult.Completed(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA, "{\"type\":\"respond\",\"output\":\"done\"}"), "model", completedUsage, "stop", new ResultRef("completed"));
                    exact.failed(call, provider, "failed");
                    return new ModelResult.Failed(failure);
                });
                return model;
            });
            var receipts = java.util.stream.IntStream.range(0, 4).mapToObj(index -> RegisteredProviderFixture.create(identity, actor,
                    Kind.MODEL, PortalCallKind.MODEL_GENERATE, "chat", receipt.prepared().request().content(), "attempt-" + index)).toList();
            when(f.store.authorizeSend(any(), any())).thenReturn(true);
            try (var host = new SystemAgentExecutions(f.store, budgets, identities, f.profiles, bindings,
                    mock(SessionTokenCounter.class), sources, business, mockedTransport, attemptAudits, mock(SystemAgentPlatformToolProvider.class),
                    f.properties, json, f.core, workers, f.scheduler, java.time.Clock.systemUTC()); var control = host.open(run)) {
                host.configuration(run, inputRef);
                var results = receipts.stream().map(value -> host.invoke(value.registered(), value.prepared())).toList();
                assertEquals(ResultKind.FAILED, results.get(0).kind());
                assertEquals(ExecutionBudgetPort.Usage.reported(OptionalLong.of(0), OptionalLong.empty()), results.get(0).usage());
                assertEquals(ExecutionBudgetPort.Usage.unreported(), results.get(1).usage());
                assertEquals(ResultKind.UNKNOWN, results.get(2).kind());
                assertEquals(ExecutionBudgetPort.Usage.unreported(), results.get(2).usage());
                assertEquals(ExecutionBudgetPort.Usage.reported(OptionalLong.of(4), OptionalLong.of(3)), results.get(3).usage());
            }
            assertEquals(4, calls.get());
            verify(scopedAudit, times(3)).usageReported(any(), eq(provider), eq(1L), any());
            verify(scopedAudit).unresolved(any(), eq(provider), eq("unknown"));
            verify(mockedTransport, times(4)).forDispatch(any(), any(), any());
            var streamProfile = new SessionPreparations.Profile(run.configuration(), SessionPreparations.PROFILE_VERSION,
                    SessionPreparations.AGENT_INPUT_RENDERER_VERSION, SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                    SessionPrompt.single("system", SessionPrompt.Type.SYSTEM_PURPOSE, "frozen-v1", "frozen-system-prompt"),
                    target.binding(), strategy, contract, generation, ReasoningPolicy.DISABLED, PortalCallKind.MODEL_STREAM,
                    new ResultRef("tokenizer"), SessionContextPolicy.defaults(), 20_000, 100_000, 10, false, Set.of(InboundKind.MESSAGE));
            when(f.profiles.preparationProfile(tenant, run.configuration())).thenReturn(Optional.of(streamProfile));
            var streamReceipt = RegisteredProviderFixture.create(identity, actor, Kind.MODEL, PortalCallKind.MODEL_STREAM,
                    "chat", receipt.prepared().request().content(), "stream");
            var providerRuntime = mock(com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime.class);
            when(providerRuntime.supportsInvocationProfile(anyString(), any())).thenReturn(true);
            var providerCalls = new java.util.concurrent.atomic.AtomicInteger();
            when(providerRuntime.stream(anyString(), any())).thenAnswer(invocation -> {
                providerCalls.incrementAndGet();
                return (java.util.concurrent.Flow.Publisher<com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent>) subscriber ->
                        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                            public void request(long count) {
                                subscriber.onNext(new com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent.UsageDelta(1, partial));
                                subscriber.onNext(new com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent.Completed(
                                        new com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult.Completed(
                                                "answer", List.of(), "system-fixture-model", unknownUsage, "stop")));
                                subscriber.onComplete();
                            }
                            public void cancel() { }
                        });
            });
            var streamAudit = mock(com.skillforge.runtime.adapter.model.ModelInvocationAudit.class);
            when(streamAudit.start(any(), any())).thenReturn(true);
            when(attemptAudits.dispatched(any(), any(), eq(binding))).thenReturn(streamAudit);
            var streamTransport = new OpenAiModelPort(ref -> { throw new AssertionError("must use captured binding"); },
                    encrypted -> "fixture-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    call -> { throw new AssertionError("must use dispatched host view"); }, (call, output) -> false,
                    http, java.time.Clock.systemUTC(), 100_000, providerRuntime);
            try (var host = new SystemAgentExecutions(f.store, budgets, identities, f.profiles, bindings,
                    mock(SessionTokenCounter.class), sources, business, streamTransport, attemptAudits, mock(SystemAgentPlatformToolProvider.class),
                    f.properties, json, f.core, workers, f.scheduler, java.time.Clock.systemUTC()); var control = host.open(run)) {
                host.configuration(run, inputRef);
                var result = host.invoke(streamReceipt.registered(), streamReceipt.prepared());
                assertEquals(ResultKind.FAILED, result.kind());
                assertEquals(ExecutionBudgetPort.Usage.reported(OptionalLong.of(9), OptionalLong.of(2)), result.usage());
                verify(streamAudit).usageReported(any(), any(), eq(2L), eq(unknownUsage));
                doThrow(new IllegalStateException("audit unavailable")).when(streamAudit).usageReported(any(), any(), eq(2L), any());
                var secondReceipt = RegisteredProviderFixture.create(identity, actor, Kind.MODEL, PortalCallKind.MODEL_STREAM,
                        "chat", receipt.prepared().request().content(), "stream-audit-failure");
                var unknown = host.invoke(secondReceipt.registered(), secondReceipt.prepared());
                assertEquals(ResultKind.UNKNOWN, unknown.kind());
                assertEquals(ExecutionBudgetPort.Usage.unreported(), unknown.usage());
                verify(streamAudit).unresolved(any(), any(), eq("model_outcome_unknown"));
                assertEquals(2, providerCalls.get());
            }
        } finally { server.stop(0); }
        assertEquals(1, count.get()); assertEquals(1, starts.get()); assertEquals(1, successes.get());
        assertEquals(7, reported.get().inputTokens().orElseThrow()); assertEquals(3, reported.get().outputTokens().orElseThrow());
        assertEquals("system-fixture-model", wire.get().path("model").asText());
        assertEquals("frozen-system-input", wire.get().path("messages").get(0).path("content").asText());
        verify(f.store, times(1)).authorizeSend(receipt.registered(), receipt.prepared());
        verify(audit).dispatched(receipt.registered(), receipt.prepared(), binding);
    }

    private static final class Fixture {
        private final CodecLimits limits=new CodecLimits(1_000_000,100_000,128);private final SessionRuntimePayloads payloads=new SessionRuntimePayloads();
        private final CoreRuntime core=new CoreRuntime(limits,payloads);private final AgentExecutionCodec codec=new AgentExecutionCodec(limits);
        private final JdbcExecutionStore store=mock(JdbcExecutionStore.class);private final OpenAiModelPort transport=mock(OpenAiModelPort.class);
        private final AgentRuntimeModelConfiguration profiles=mock(AgentRuntimeModelConfiguration.class);private final SystemAgentExecutions host;
        private final AgentRuntimeProperties properties=new AgentRuntimeProperties();private final ExecutionHostScheduler scheduler=mock(ExecutionHostScheduler.class);
        private final Run run;private final Event terminal;
        @SuppressWarnings("unchecked") private Fixture(){
            var tenant=new TenantId(SystemAgentRuntimeContracts.TENANT_ID);var owner=SessionOwner.user(new SessionRef(tenant,"system-agent:business"),new AgentId("11"),new EndUserId(1));
            var identity=new RunIdentity(owner,owner.target().requireAgent(),new InputId("input"),new RunRef(tenant,"system-agent-run:business"),new TraceEnvelope("trace","span",new InputId("input"),Optional.empty()),new ResultRef("actor"), new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant,SystemAgentRuntimeContracts.SYSTEM_PRINCIPAL_ID)), Optional.empty(), new RunOriginRef(tenant,"business"));
            run=new Run(identity,new ResultRef("profile"),RunStatus.COMPLETED,7,1,false,Phase.FINISHED,new Progress(2,0,0,new ResultRef("context")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),new ResultRef("actor"),Optional.empty(),Instant.EPOCH,Instant.EPOCH);
            terminal=new Event(AgentExecutionProgram.finishedEventId(identity.runRef()),AgentExecutionProgram.COMPLETED_EVENT,new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA,"{\"status\":\"COMPLETED\",\"text\":\"{\\\"result\\\":\\\"完成\\\"}\",\"taskResultRef\":null}"));
            when(store.event(identity.runRef(),terminal.id())).thenReturn(Optional.of(new SavedEvent(identity.runRef(),1,7,Instant.EPOCH,terminal)));
            var profile=mock(SessionPreparations.Profile.class);when(profile.callKind()).thenReturn(PortalCallKind.MODEL_GENERATE);when(profiles.preparationProfile(tenant,run.configuration())).thenReturn(Optional.of(profile));
            host=new SystemAgentExecutions(store,mock(JdbcExecutionBudgets.class),mock(JdbcIdentityPolicyPort.class),profiles,mock(JdbcModelBindings.class),mock(SessionTokenCounter.class),mock(SystemAgentCapabilitySource.class),mock(SystemAgentRuntimeStore.class),transport,mock(SystemAgentModelInvocationAudit.class),mock(SystemAgentPlatformToolProvider.class),properties,new ObjectMapper().findAndRegisterModules(),core,mock(ObjectProvider.class),scheduler,java.time.Clock.fixed(Instant.parse("2026-10-22T01:02:03Z"),java.time.ZoneOffset.ofHours(8)));
        }
        private void models(ModelUsage... usage){var actions=new ArrayList<Action>();for(int index=0;index<usage.length;index++){
            int ordinal=index+1;var action=new Action(new ActionRef(run.identity().runRef().tenantId(),"model-"+ordinal),run.identity().runRef(),ordinal,Kind.MODEL,"model",new ResultRef("capability"),new ResultRef("request-"+ordinal),"fingerprint",ActionStatus.SUCCEEDED,"attempt-"+ordinal,Optional.of("attempt-"+ordinal),Optional.empty());actions.add(action);
            var raw=new ModelResult.Completed(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,"{\"type\":\"respond\",\"output\":\"done\"}"),"model",usage[index],"stop",new ResultRef("provider-"+ordinal));
            var material=new Material(new ResultRef("result-"+ordinal),codec.encodeOutcome(core.encodeOutcome(PortalCallKind.MODEL_GENERATE,raw)));
            var attempt=new Attempt(action.currentAttempt(),action.reference(),run.identity().runRef(),1,action.request(),Optional.empty(),Optional.of(ResultKind.SUCCEEDED),Optional.of(material.reference()),Optional.empty(),Optional.empty(),Optional.empty(),true);
            when(store.attempt(run.identity().runRef(),attempt.id())).thenReturn(Optional.of(attempt));when(store.material(run.identity().runRef().tenantId(),material.reference())).thenReturn(Optional.of(material));
        }when(store.actions(run.identity().runRef(),0,100)).thenReturn(actions);}
    }
}
