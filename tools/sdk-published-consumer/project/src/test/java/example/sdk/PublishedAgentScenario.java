package example.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.model.JTokkitSessionTokenCounter;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** 正式 JAR 的小型宿主；模型 HTTP 服务和只读工具均为本地受控夹具。 */
final class PublishedAgentScenario {
    private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 128);
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String NONCE = "published-agent-tool-result";
    // 本样例只支持这一份受控配置；读取原材料并精确匹配后才重建它。
    private static final TypedPayload CONFIG = new TypedPayload("published-agent-config.v1",
            "{\"binding\":\"published-model\",\"model\":\"fixture-model\",\"tool\":\"published-read\",\"version\":1}");
    private static final RequestedModelStrategy STRATEGY = new RequestedModelStrategy("published-agent", "1");

    private PublishedAgentScenario() { }

    static void verify(DataSource dataSource, ConsumerHostIdentity identities, Supplier<ExecutionStore> stores) throws Exception {
        for (var type : List.of(AgentExecutionProgram.class, ExecutionCoordinator.class, OpenAiModelPort.class,
                CoreRuntime.class, JdbcExecutionBudgets.class)) {
            assertTrue(type.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"), type.getName());
        }
        var tenant = new TenantId("published-agent-tenant");
        var owner = SessionOwner.user(new SessionRef(tenant, "published-agent-session"), new AgentId("agent"), new EndUserId(900005));
        var inputId = new InputId("published-agent-input");
        var run = new RunRef(tenant, "published-agent-run");
        var actor = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant, "consumer-host"), owner,
                Set.of("sessions.write"), 1, new ResultRef("published-agent-identity"));
        identities.register(actor);
        var identity = new RunIdentity(owner, owner.target().requireAgent(), inputId, run,
                new TraceEnvelope("published-agent-trace", "published-agent-span", inputId, Optional.empty()),
                actor.verificationRef(), new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, inputId.value()));
        var capacity = new ExecutionBudgetPort.Resources(2, 0, 1, 0, 10000, 1000, 11000);
        var policy = new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(
                ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 60, 60, "published-agent-v1"));
        var registration = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY, policy);
        var budgets = new JdbcExecutionBudgets(dataSource);
        var store = stores.get();
        var scenario = new Host(store, budgets, actor, policy);
        var configuration = new ExecutionStore.Material(new ResultRef("published-agent-config"), CONFIG);
        var inputContent = scenario.codec.encodeInput(List.of(new ModelMessage("input", ModelRole.USER, "Read the local value.")),
                new TypedPayload("published-agent-input.v1", "{}"));
        var input = new ExecutionStore.Material(new ResultRef("published-agent-input-material"), inputContent);
        var context = scenario.program.initialContext(input.reference());
        assertTrue(store.admit(new ExecutionStore.Admission(actor, identity, "published-agent", configuration.reference(),
                input, context, registration, List.of(), List.of(configuration))).created());
        try (var fixture = new ModelFixture(); var host = new PublishedExecutionHost(store, run, scenario.coordinator(fixture))) {
            var finished = host.execute();
            assertEquals(RunStatus.COMPLETED, finished.status());
            assertEquals(identity, finished.identity());
            var actions = store.actions(run, 0, 10);
            assertEquals(List.of(ExecutionStore.Kind.MODEL, ExecutionStore.Kind.TOOL, ExecutionStore.Kind.MODEL),
                    actions.stream().map(ExecutionStore.Action::kind).toList());
            for (var action : actions) {
                assertEquals(ExecutionStore.ActionStatus.SUCCEEDED, action.status());
                var attempt = store.attempt(run, action.currentAttempt()).orElseThrow();
                assertEquals(run, attempt.run());
                assertEquals(action.reference(), attempt.action());
                assertEquals(Optional.of(ExecutionStore.ResultKind.SUCCEEDED), attempt.resultKind());
                assertTrue(attempt.adopted());
            }
            assertEquals(2, fixture.requests.get());
            assertEquals(1, scenario.toolCalls.get());
            assertEquals(new ExecutionBudgetPort.Resources(2, 0, 1, 0, 14, 6, 20), budgets.snapshot(run).consumed());
            var rebuilt = stores.get();
            assertEquals(finished, rebuilt.load(run).orElseThrow());
            assertEquals(identity, rebuilt.load(run).orElseThrow().identity());
            assertEquals(configuration, rebuilt.material(tenant, configuration.reference()).orElseThrow());
            assertEquals(actions, rebuilt.actions(run, 0, 10));
            assertTrue(rebuilt.event(run, AgentExecutionProgram.finishedEventId(run)).orElseThrow()
                    .event().data().canonicalJson().contains(NONCE));
            assertEquals(budgets.snapshot(run).consumed(), new JdbcExecutionBudgets(dataSource).snapshot(run).consumed());
            var recovered = new Host(rebuilt, new JdbcExecutionBudgets(dataSource), actor, policy);
            var current = rebuilt.load(run).orElseThrow();
            var reopened = recovered.program.open(current, recovered.material(current, current.progress().context()));
            assertTrue(reopened.messages().stream().anyMatch(message -> message.textContent().contains(NONCE)));
            var pool = Executors.newSingleThreadExecutor();
            try {
                var outcome = pool.submit(() -> recovered.coordinator(fixture).drive(
                        new ExecutionStore.Claim(run, "published-restarted", "published-restarted-claim", Duration.ofMinutes(1))))
                        .get(10, TimeUnit.SECONDS);
                assertEquals(ExecutionCoordinator.Stop.NOT_CLAIMED, outcome.stop());
            } finally { pool.shutdownNow(); }
            assertEquals(2, fixture.requests.get());
            assertEquals(0, recovered.toolCalls.get());
            assertEquals(20, rebuilt.usage(run).reportedTokens());
        }
        System.out.println("SDK_PUBLISHED_AGENT_CONSUMPTION_COMPLETE model=2 tool=1 rebuild=1");
    }

    private static final class Host implements AgentExecutionProgram.Sources {
        private final ExecutionStore store;
        private final JdbcExecutionBudgets budgets;
        private final VerifiedIdentity actor;
        private final ExecutionBudgetPort.Policy policy;
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AgentExecutionCodec codec = new AgentExecutionCodec(LIMITS);
        private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
        private final CoreRuntime core = new CoreRuntime(LIMITS, payloads);
        private final AgentExecutionProgram program = new AgentExecutionProgram(this, core, LIMITS);

        Host(ExecutionStore store, JdbcExecutionBudgets budgets, VerifiedIdentity actor, ExecutionBudgetPort.Policy policy) {
            this.store = store; this.budgets = budgets; this.actor = actor; this.policy = policy;
        }

        @Override public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, ResultRef input) {
            assertEquals(CONFIG, material(run, run.configuration()).content());
            var target = new FrozenModelTarget(new ModelBindingRef(run.identity().runRef().tenantId(), "published-model", 1), "fixture-model",
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("published-model-capabilities")),
                    Duration.ofSeconds(20), new ResultRef("published-model-resolution"));
            var profile = new SessionPreparations.Profile(run.configuration(), SessionPreparations.PROFILE_VERSION,
                    SessionPreparations.AGENT_INPUT_RENDERER_VERSION, SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                    new SessionPrompt(List.of(new SessionPrompt.Module("system", SessionPrompt.Type.HOST, "1", "Use the local tool."))),
                    target.binding(), STRATEGY, new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "published-native"),
                    new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED,
                    PortalCallKind.MODEL_GENERATE, new ResultRef("published-tokenizer"), SessionContextPolicy.forInputBudget(10000),
                    10000, 10000, 100, false, Set.of(InboundKind.MESSAGE));
            var tool = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:published-read"),
                    new CapabilityRef(CapabilityType.TOOL, "published-read", "1"), "published-deployment", 1,
                    new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"), ToolEffect.READ_ONLY,
                    true, Duration.ofSeconds(5), true, ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), false);
            var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM, "published", "read_value");
            var entry = new SessionToolDirectory.Entry(tool, binding,
                    new SessionToolDirectory.Discovery(binding.serverIdentity(), binding.serverIdentity() + "/" + binding.toolName(),
                            binding.toolName(), Optional.empty(), "Read a local value"), true);
            var declaration = new ModelTool("sf_read_value", tool.handle().value(), "Read a local value", tool.inputSchema(), false, ModelToolKind.TOOL_CALL, true);
            return new AgentExecutionProgram.Configuration(profile, target, List.of(declaration), List.of(entry), LoopBudget.from(policy),
                    new CapabilitySnapshotRef(run.identity().owner().sessionRef(), run.identity().causationInputId(), "published-tools", 1),
                    run.configuration(), codec.decodeInput(material(run, input).content()), actor, List.of(), List.of(), Set.of(), List.of(), PlanPort.State.absent(), false);
        }
        @Override public ExecutionStore.Material material(ExecutionStore.Run run, ResultRef ref) {
            return store.material(run.identity().runRef().tenantId(), ref).orElseThrow();
        }
        @Override public List<Optional<ExecutionStore.Material>> materials(ExecutionStore.Run run, List<ResultRef> refs) {
            return store.materials(run.identity().runRef().tenantId(), refs);
        }
        @Override public Optional<ExecutionBudgetPort.Snapshot> budgetSnapshot(ExecutionStore.Run run) {
            return Optional.of(budgets.snapshot(run.identity().runRef()));
        }
        @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages, List<ModelTool> tools) {
            var profile = new SessionTokenCounter.Profile(configuration.profile().tokenizerProfileRef(), "published-v1",
                    JTokkitSessionTokenCounter.TOKENIZER_VERSION, "cl100k_base", configuration.target(),
                    new SessionTokenCounter.RoleCosts(1, 1, 1, 1), 3, 3, 2000, 2_000_000);
            return new JTokkitSessionTokenCounter(ref -> Optional.of(profile)).measure(profile.reference(), profile.target(), messages, tools);
        }
        @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run, AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages) {
            return new AgentExecutionModelMedia.Prepared(messages, List.of());
        }
        @Override public AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run) { return AgentExecutionTime.sample(CLOCK); }
        @Override public Duration elapsed(ExecutionStore.Run run) { return Duration.ZERO; }

        ExecutionCoordinator<AgentExecutionProgram.Materials> coordinator(ModelFixture fixture) {
            return new ExecutionCoordinator<>(store, program, (registered, prepared) -> invoke(fixture, registered, prepared),
                    ignored -> new ExecutionCoordinator.Control() {
                        public boolean cancelled() { return false; }
                        public boolean ownershipLost() { return false; }
                        public void close() { }
                    }, new ExecutionCoordinator.LocalPlans() {
                        public <T> ExecutionCoordinator.LocalPlanResult<T> execute(ExecutionStore.Run run, ExecutionCoordinator.Step.PlanLocal command,
                                java.util.function.BiFunction<ExecutionStore.Run, ExecutionStore.Material, ExecutionCoordinator.Adoption<T>> adopter) {
                            throw new IllegalStateException("published_plan_unavailable");
                        }
                    });
        }

        private ExecutionCoordinator.CapabilityResult invoke(ModelFixture fixture, ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
            assertEquals(actor, registered.actor());
            if (prepared.kind() == ExecutionStore.Kind.TOOL) {
                var request = codec.decodeTool(prepared.request().content());
                SessionRuntimePayloads.validateAgainstSchema(request.target().inputSchema(), request.arguments(), LIMITS);
                assertTrue(store.authorizeSend(registered, prepared));
                assertEquals("tool:published-read", request.target().handle().value());
                toolCalls.incrementAndGet();
                var observation = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"" + NONCE + "\"}]}");
                var actual = new ToolPortal.ToolOutcome.Succeeded(observation, new ResultRef("published-tool-receipt"), List.of());
                var encoded = codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeTool(PortalCallKind.TOOL_INVOKE, actual));
                return new ExecutionCoordinator.CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,
                        new ExecutionStore.Material(AgentExecutionCodec.reference(encoded), encoded),
                        codec.safeToolFeedback(request, actual).map(value -> new ExecutionStore.Material(AgentExecutionCodec.reference(value), value)),
                        Optional.empty(), ExecutionBudgetPort.Usage.unreported());
            }
            assertEquals(ExecutionStore.Kind.MODEL, prepared.kind());
            var request = codec.decodeModelRequest(prepared).request();
            var dispatch = new ProviderDispatch(registered.run().identity().runRef(), prepared.action(), prepared.action().actionId(),
                    registered.attempt().id(), PortalCallKind.MODEL_GENERATE, prepared.request().reference(), prepared.capability(),
                    prepared.fingerprint(), registered.attempt().fence());
            var context = ProviderCallContext.dispatched(new PortCallContext(registered.run().identity(), registered.actor(),
                    new InvocationControl(CLOCK.instant().plus(request.target().timeout()), () -> false)), dispatch);
            var call = new ModelCall(context, request.requestedStrategy(), request.target(), prepared.request().reference(),
                    request.messages(), request.tools(), request.toolChoice(), request.response(), request.generation(), request.reasoning(), request.budgetCategory());
            var binding = new OpenAiModelPort.Binding(request.target(), STRATEGY, fixture.endpoint(), "fixture-key",
                    new OpenAiModelPort.ProviderProfile("custom", "custom-openai-compatible", "custom-v1", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                            AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1"));
            try (var port = new OpenAiModelPort(ref -> Optional.of(binding), ref -> "fixture-key".getBytes(StandardCharsets.UTF_8),
                    candidate -> candidate == call && store.authorizeSend(registered, prepared),
                    (candidate, output) -> { payloads.validate(output, LIMITS); return true; },
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), CLOCK, 100_000)) {
                var actual = assertInstanceOf(ModelResult.Completed.class, port.generate(call));
                var encoded = codec.encodeOutcome(core.encodeOutcome(PortalCallKind.MODEL_GENERATE, actual));
                return new ExecutionCoordinator.CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,
                        new ExecutionStore.Material(AgentExecutionCodec.reference(encoded), encoded), Optional.empty(), Optional.empty(),
                        ExecutionBudgetPort.Usage.reported(actual.usage().inputTokens(), actual.usage().outputTokens()));
            }
        }
    }

    private static final class ModelFixture implements AutoCloseable {
        private final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;
        ModelFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int number = requests.incrementAndGet();
                if (number == 2 && !body.contains(NONCE)) throw new IllegalStateException("tool_result_not_returned_to_model");
                String message = number == 1
                        ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"sf_read_value\",\"arguments\":\"{}\"}}]}"
                        : "{\"role\":\"assistant\",\"content\":\"" + NONCE + "\"}";
                String response = "{\"id\":\"fixture-" + number + "\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-model\","
                        + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + (number == 1 ? "tool_calls" : "stop")
                        + "\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        public void close() { server.stop(0); }
    }
}
