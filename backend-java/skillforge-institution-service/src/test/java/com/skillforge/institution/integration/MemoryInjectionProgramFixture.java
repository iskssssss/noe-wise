package com.skillforge.institution.integration;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.institution.runtime.MemorySearchProperties;
import com.skillforge.institution.runtime.MemorySearchToolHandler;
import com.skillforge.institution.runtime.GroupMemoryRecall;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.core.*;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.AgentExecutionCodec.ModelCallRequest;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionAdmissionFacts;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionPrompt;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;

/**
 * 真实 Store/Program/Coordinator 的记忆注入夹具；只有准入和 MODEL Provider 受控。
 * search_memory 的检索、选择、反馈及持久恢复均走正式实现，不构造预期记忆结果。
 */
public final class MemoryInjectionProgramFixture implements AgentExecutionProgram.Sources {
    private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 64);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String WIRE_NAME = "search_memory";
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() { };

    private final DataSource dataSource;
    private final Clock clock = Clock.systemUTC();
    private final AgentExecutionCodec codec = new AgentExecutionCodec(LIMITS);
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
    private final CoreRuntime core = new CoreRuntime(LIMITS, payloads);
    private final MemorySearchToolHandler handler;
    private final GroupMemoryRecall recall;
    private final AgentExecutionProgram.Configuration configuration;
    private final String query;
    private final Optional<EndUserId> subject;
    private final String claimId = UUID.randomUUID().toString();
    private final List<ModelCallRequest> modelRequests = new ArrayList<>();
    private JdbcExecutionBudgets budgets;
    private JdbcExecutionStore store;
    private AgentExecutionProgram program;
    private ExecutionCoordinator<AgentExecutionProgram.Materials> coordinator;
    private int tools;
    private int stopAfter = Integer.MAX_VALUE;
    private Map<String, Object> lastToolResult;
    private PlatformToolExecutionContext lastToolContext;

    public final RunIdentity identity;
    public final VerifiedIdentity actor;

    public MemoryInjectionProgramFixture(DataSource dataSource, MemoryRuntime runtime, JdbcMemoryPort memories,
            GroupBindingService groups, SessionOwner owner, String query) {
        this(dataSource, runtime, memories, groups, owner, null, null, query, Optional.empty());
    }

    /** 群Session归属不提供执行Agent/requester；测试从正式可信上下文传入两者。 */
    public MemoryInjectionProgramFixture(DataSource dataSource, MemoryRuntime runtime, JdbcMemoryPort memories, GroupBindingService groups,
            RunIdentity identity, VerifiedIdentity actor, String query, Optional<EndUserId> subject) {
        this(dataSource, runtime, memories, groups, identity.owner(), identity, actor, query, subject);
    }

    private MemoryInjectionProgramFixture(DataSource dataSource, MemoryRuntime runtime, JdbcMemoryPort memories, GroupBindingService groups,
            SessionOwner owner, RunIdentity providedIdentity, VerifiedIdentity providedActor, String query, Optional<EndUserId> subject) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.query = Objects.requireNonNull(query);
        this.subject = Objects.requireNonNull(subject);
        handler = new MemorySearchToolHandler(runtime, clock, new MemorySearchProperties(), groups);
        recall = new GroupMemoryRecall(runtime, memories, clock, new MemorySearchProperties());
        String suffix = UUID.randomUUID().toString();
        TenantId tenant = owner.sessionRef().tenantId();
        InputId input = new InputId("memory-injection-input:" + suffix);
        identity = providedIdentity == null ? new RunIdentity(owner, new AgentId(owner.target().id()), input,
                new RunRef(tenant, "memory-injection-run:" + suffix),
                new TraceEnvelope("memory-injection-trace:" + suffix, "memory-injection-span:" + suffix,
                        input, Optional.empty()),
                new ResultRef("memory-injection-identity:" + suffix),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(),
                new RunOriginRef(tenant, "input:" + input.value())) : providedIdentity;
        input = identity.causationInputId();
        actor = providedActor == null ? new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()),
                owner, Set.of("sessions.write", "memory:query"), 1, identity.causationIdentityRef()) : providedActor;
        var target = new FrozenModelTarget(new ModelBindingRef(tenant, "memory-injection-model", 1),
                "fixture-model", new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false,
                        true, new ResultRef("memory-injection-model-capabilities")),
                Duration.ofSeconds(1), new ResultRef("memory-injection-model-resolution"));
        var profile = new SessionPreparations.Profile(new ResultRef("memory-injection-profile"),
                SessionPreparations.PROFILE_VERSION, SessionPreparations.AGENT_INPUT_RENDERER_VERSION,
                SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                SessionPrompt.single("system", SessionPrompt.Type.HOST, "1", "先检索相关记忆，再回答当前问题"),
                target.binding(), new RequestedModelStrategy("fixture", "1"),
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DEFAULT,
                PortalCallKind.MODEL_GENERATE, new ResultRef("memory-injection-tokenizer"),
                SessionContextPolicy.defaults(), 100_000, 100_000, 100, false, Set.of(InboundKind.MESSAGE));
        var binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM, WIRE_NAME, WIRE_NAME);
        var frozenTool = new FrozenToolTarget(new ToolHandle("tool:search-memory"),
                new CapabilityRef(CapabilityType.TOOL, WIRE_NAME, "1"), WIRE_NAME, 1,
                new TypedPayload("json-schema", """
                        {"type":"object","properties":{"query":{"type":"string"},"subject_end_user_id":{"type":"string"}},"required":["query"],"additionalProperties":false}
                        """.strip()),
                new TypedPayload("json-schema", "{}"), ToolEffect.READ_ONLY, true,
                Duration.ofSeconds(30), true, ToolConfirmationPolicy.NEVER, Set.of(),
                Set.of("platform_handler:search_memory"), true);
        var tool = new SessionToolDirectory.Entry(frozenTool, binding,
                new SessionToolDirectory.Discovery(binding.serverIdentity(),
                        binding.serverIdentity() + "/" + binding.toolName(), binding.toolName(),
                        Optional.empty(), "检索当前用户的相关记忆"), false);
        configuration = new AgentExecutionProgram.Configuration(profile, target,
                List.of(new ModelTool(WIRE_NAME, frozenTool.handle().value(), "检索相关记忆",
                        frozenTool.inputSchema(), false, ModelToolKind.TOOL_CALL, false)),
                List.of(tool), new LoopBudget.Enforced(10, 10, 0, 100_000, 100_000, 200_000,
                        Duration.ofMinutes(10)),
                new CapabilitySnapshotRef(owner.sessionRef(), input, "memory-injection-mounts", 1),
                new ResultRef("memory-injection-mounts"), initialMessages(identity, query),
                actor, List.of(), List.of(), Set.of(), List.of(), PlanPort.State.absent(), false);
        restart();
        var capacity = new ExecutionBudgetPort.Resources(10, 0, 10, 0, 1_000_000, 1_000_000, 2_000_000);
        var budget = new ExecutionBudgetPort.Registration(identity.runRef(), identity.runRef(),
                ExecutionBudgetPort.Role.PRIMARY, new ExecutionBudgetPort.Policy.Enforced(
                        new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                                capacity, capacity, capacity, 600, 600, "memory-injection:v1")));
        var content = new ExecutionStore.Material(new ResultRef("memory-injection-original:" + suffix),
                codec.encodeInput(configuration.initialMessages(),
                        new TypedPayload("input.v1", encode(new InputBody(query, Map.of())))));
        store.admit(new ExecutionStore.Admission(actor, identity, "memory-injection-admission:" + suffix,
                profile.reference(), content, program.initialContext(content.reference()), budget,
                List.of(AgentExecutionProgram.admissionEvent(identity)), List.of()));
    }

    /** 重建执行对象，从原 Store 材料恢复；同一有界夹具继续使用原领取身份，不修改租约数据。 */
    public void restart() {
        budgets = new JdbcExecutionBudgets(dataSource);
        store = new JdbcExecutionStore(dataSource, new JdbcIdentityPolicyPort(dataSource, Map.of()), budgets, (session, run, current, prepared) -> recall.allowed(session,run,current));
        program = new AgentExecutionProgram(this, core, LIMITS);
        coordinator = new ExecutionCoordinator<>(store, new BoundedProgram(), this::invoke, run ->
                new ExecutionCoordinator.Control() {
                    private final ExecutionBudgetScope scope = ExecutionBudgetScope.open(
                            new ExecutionBudgetScope.Binding(budgets, identity.runRef(),
                                    () -> ExecutionBudgetPort.ClosingSpec.TEXT,
                                    () -> Optional.of(Duration.ofSeconds(1)),
                                    () -> Optional.of(new ExecutionBudgetScope.ClosingSizing(
                                            ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 100, Duration.ofSeconds(1))),
                                    () -> Duration.ZERO));
                    @Override public boolean cancelled() { return false; }
                    @Override public boolean ownershipLost() { return false; }
                    @Override public void close() { scope.close(); }
                }, new ExecutionCoordinator.LocalPlans() {
                    @Override public <T> ExecutionCoordinator.LocalPlanResult<T> execute(ExecutionStore.Run run,
                            ExecutionCoordinator.Step.PlanLocal command,
                            java.util.function.BiFunction<ExecutionStore.Run, ExecutionStore.Material,
                                    ExecutionCoordinator.Adoption<T>> adopter) {
                        throw new IllegalStateException("memory_injection_unexpected_plan");
                    }
                });
    }

    /** maxActions 只限制本次新能力调用；Yield 发生在真实结果已提交、下一动作准备之前。 */
    public ExecutionCoordinator.Outcome drive(int maxActions) {
        if (maxActions < 1) throw new IllegalArgumentException("maxActions must be positive");
        stopAfter = Math.addExact(Math.addExact(modelCalls(), tools), maxActions);
        try {
            return coordinator.drive(new ExecutionStore.Claim(identity.runRef(), "memory-injection-worker",
                    claimId, Duration.ofMinutes(5)));
        } finally {
            stopAfter = Integer.MAX_VALUE;
        }
    }

    public ExecutionCoordinator.Outcome drive() { return drive(20); }
    public JdbcExecutionStore store() { return store; }
    public int modelCalls() { return modelRequests.size(); }
    public int toolCalls() { return tools; }
    public List<ModelCallRequest> modelRequests() { return List.copyOf(modelRequests); }
    public ModelCallRequest nextModelRequest() {
        if (modelRequests.size() < 2) throw new IllegalStateException("memory_injection_next_model_not_called");
        return modelRequests.get(1);
    }
    public Map<String, Object> toolResult() {
        if (lastToolResult == null) throw new IllegalStateException("memory_injection_tool_not_called");
        return lastToolResult;
    }
    public PlatformToolExecutionContext toolContext() {
        if (lastToolContext == null) throw new IllegalStateException("memory_injection_tool_not_called");
        return lastToolContext;
    }

    private ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered dispatch,
            ExecutionStore.Prepared prepared) {
        if (!store.authorizeSend(dispatch, prepared)) throw new IllegalStateException("memory_injection_send_not_authorized");
        if (prepared.kind() == ExecutionStore.Kind.MODEL) {
            var request = codec.decodeModelRequest(prepared);
            modelRequests.add(request);
            var turn = modelRequests.size() == 1
                    ? new AssistantTurn(List.of(new AssistantContent.ToolCall(
                            "provider-search:" + dispatch.attempt().id(), WIRE_NAME, encode(new SearchArguments(query,subject.map(id->Long.toString(id.value())).orElse(null))))))
                    : new AssistantTurn(List.of(new AssistantContent.Text("已依据本次实际检索结果完成回答。")));
            var result = new ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA, encode(turn.content())),
                    "fixture-model", new ModelUsage(OptionalLong.of(3), OptionalLong.of(2)),
                    modelRequests.size() == 1 ? "tool_calls" : "stop",
                    new ResultRef("memory-injection-model-result:" + dispatch.attempt().id()), Optional.empty(), Optional.of(turn));
            return result(prepared, core.encodeOutcome(PortalCallKind.MODEL_GENERATE, result), Optional.empty());
        }
        if (prepared.kind() != ExecutionStore.Kind.TOOL) throw new IllegalStateException("memory_injection_unexpected_capability");
        var request = codec.decodeTool(prepared.request().content());
        if (!WIRE_NAME.equals(request.wireName()) || !WIRE_NAME.equals(request.binding().toolName()))
            throw new IllegalStateException("memory_injection_tool_target_mismatch");
        Map<String, Object> arguments;
        try {
            arguments = JSON.readValue(request.arguments().canonicalJson(), ARGUMENTS);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("memory_injection_tool_arguments_invalid", invalid);
        }
        tools++;
        var context = new PlatformToolExecutionContext(new PortCallContext(identity, actor,
                new InvocationControl(clock.instant().plusSeconds(120), () -> false)),
                "memory-injection:" + prepared.action().actionId(), dispatch.attempt().id());
        lastToolContext = context;
        lastToolResult = handler.execute(arguments, context);
        var outcome = new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                encode(new ToolResultBody(List.of(), false, "complete", lastToolResult))),
                new ResultRef("memory-injection-search-result:" + dispatch.attempt().id()), List.of());
        return result(prepared, core.portalResults(payloads, payloads, payloads)
                .encodeTool(PortalCallKind.TOOL_INVOKE, outcome), codec.safeToolFeedback(request, outcome));
    }

    private ExecutionCoordinator.CapabilityResult result(ExecutionStore.Prepared prepared,
            PortalOutcomeSnapshot outcome, Optional<TypedPayload> feedback) {
        return new ExecutionCoordinator.CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,
                new ExecutionStore.Material(new ResultRef("memory-injection-result:" + prepared.action().actionId()),
                        codec.encodeOutcome(outcome)),
                feedback.map(value -> new ExecutionStore.Material(
                        new ResultRef("memory-injection-feedback:" + prepared.action().actionId()), value)),
                Optional.empty(), ExecutionBudgetPort.Usage.reported(OptionalLong.of(3), OptionalLong.of(2)));
    }

    @Override public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, ResultRef input) {
        if (!identity.equals(run.identity())) throw new IllegalStateException("memory_injection_run_mismatch");
        return configuration;
    }
    @Override public ExecutionStore.Material material(ExecutionStore.Run run, ResultRef reference) {
        return store.material(run.identity().runRef().tenantId(), reference).orElseThrow();
    }
    @Override public List<Optional<ExecutionStore.Material>> materials(ExecutionStore.Run run, List<ResultRef> references) {
        return store.materials(run.identity().runRef().tenantId(), references);
    }
    @Override public Duration elapsed(ExecutionStore.Run run) { return Duration.ZERO; }
    @Override public AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run) { return AgentExecutionTime.sample(clock); }
    @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run,
            AgentExecutionProgram.Configuration current, List<ModelMessage> messages) {
        return new AgentExecutionModelMedia.Prepared(messages, List.of());
    }
    @Override public List<ModelMessage> modelContext(ExecutionStore.Run run, AgentExecutionProgram.Configuration current,
            List<ModelMessage> history, Optional<List<ModelMessage>> frozenRequestMessages) {
        return frozenRequestMessages.map(frozen -> recall.restore(run.identity(), history, frozen)).orElseGet(() ->
                recall.prepare(new PortCallContext(run.identity(), current.actor(),
                        new InvocationControl(clock.instant().plusSeconds(120), () -> false)), current.initialMessages(), history));
    }
    @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration current,
            List<ModelMessage> messages, List<com.skillforge.runtime.api.port.ModelPort.ModelTool> tools) {
        var profile = new SessionTokenCounter.Profile(current.profile().tokenizerProfileRef(), "1", "fixture", "fixture",
                current.target(), new SessionTokenCounter.RoleCosts(0, 0, 0, 0), 0, 0, 100, 1_000_000);
        return new SessionTokenCounter.Measurement(profile, messages, Collections.nCopies(messages.size(), 1L),
                messages.size(), 0, 0, tools, tools.isEmpty() ? 0 : 1);
    }

    /** 仅在正式程序的下一边界让出；其余行为全数委托真实 Program。 */
    private final class BoundedProgram implements ExecutionCoordinator.Program<AgentExecutionProgram.Materials> {
        @Override public AgentExecutionProgram.Materials open(ExecutionStore.Run run, ExecutionStore.Material context) { return program.open(run, context); }
        @Override public ExecutionCoordinator.Step next(ExecutionStore.Run run, AgentExecutionProgram.Materials materials) {
            return modelCalls() + tools >= stopAfter ? new ExecutionCoordinator.Step.Yield("memory_injection_action_limit") : program.next(run, materials);
        }
        @Override public ExecutionCoordinator.Step continuation(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) { return program.continuation(run, materials, waiting, response); }
        @Override public ExecutionCoordinator.Adoption<AgentExecutionProgram.Materials> acceptResponse(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) { return program.acceptResponse(run, materials, waiting, response); }
        @Override public ExecutionCoordinator.Adoption<AgentExecutionProgram.Materials> acceptInput(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, ExecutionStore.SupplementalInput input, ExecutionStore.Material content) { return program.acceptInput(run, materials, input, content); }
        @Override public AgentExecutionProgram.Materials inputCommitted(ExecutionStore.InputAcceptance accepted, AgentExecutionProgram.Materials materials) { return program.inputCommitted(accepted, materials); }
        @Override public ExecutionCoordinator.Adoption<AgentExecutionProgram.Materials> planAdoption(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, ExecutionCoordinator.Step.PlanLocal command, ExecutionStore.Material actualResult) { return program.planAdoption(run, materials, command, actualResult); }
        @Override public boolean batchParallel(AgentExecutionProgram.Materials materials) { return program.batchParallel(materials); }
        @Override public ExecutionCoordinator.BatchResult inspectBatch(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, ExecutionCoordinator.BatchCall result, int slot) { return program.inspectBatch(run, materials, result, slot); }
        @Override public ExecutionCoordinator.Adoption<AgentExecutionProgram.Materials> adoptBatch(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, List<ExecutionCoordinator.BatchOutcome> results) { return program.adoptBatch(run, materials, results); }
        @Override public ExecutionCoordinator.BatchPlan<AgentExecutionProgram.Materials> batchContinuation(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, List<ExecutionCoordinator.BatchOutcome> results, List<ExecutionCoordinator.BatchResponse> responses) { return program.batchContinuation(run, materials, results, responses); }
        @Override public ExecutionCoordinator.Adoption<AgentExecutionProgram.Materials> adopt(ExecutionStore.Run run, AgentExecutionProgram.Materials materials, ExecutionStore.Prepared prepared, ExecutionCoordinator.CapabilityResult result) { return program.adopt(run, materials, prepared, result); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SearchArguments(String query,@JsonProperty("subject_end_user_id") String subjectEndUserId) { }
    private record InputBody(String text, Map<String, Object> extraParameters) { }
    private record ToolResultBody(List<Object> content, boolean isError, String resultType, Map<String, Object> structuredContent) { }
    private static List<ModelMessage> initialMessages(RunIdentity identity,String query) {
        if (!(identity.owner().subject() instanceof SessionSubject.Group)) return List.of(new ModelMessage("input", ModelRole.USER, query));
        var source=identity.sourceMessageRef().orElseThrow();
        var author=(RunRequester.EndUser)identity.requester();
        String provenance="群消息来源："+encode(new com.skillforge.institution.group.GroupMessageContracts.EndUserAuthor(author.endUserId().value()))
                +"；消息："+source.messageId();
        return List.of(new ModelMessage("group-source:"+source.messageId(),ModelRole.USER,
                List.of(new ModelContent.Text(provenance),new ModelContent.Text(query))));
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("memory_injection_fixture_encoding_invalid", invalid); }
    }
}
