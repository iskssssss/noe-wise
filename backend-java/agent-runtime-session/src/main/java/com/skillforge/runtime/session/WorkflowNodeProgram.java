package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Workflow外部节点只推进一个真实效果；与Agent共用唯一Coordinator，不解码Agent决策。 */
public final class WorkflowNodeProgram implements ExecutionCoordinator.Program<WorkflowNodeProgram.Materials> {
    public static final String SCRIPT_REQUEST_SCHEMA="skillforge.runtime.execution.workflow-script-request.v1";
    public static final class ScriptOutputRejected extends IllegalArgumentException { public ScriptOutputRejected(){super("workflow_script_output_schema_rejected");} }
    public static final String CONTEXT_SCHEMA = "skillforge.runtime.execution.workflow-node-context.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Sources sources;
    private final AgentExecutionCodec codec;
    private final CoreRuntime core;
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();

    /** 配置是原已受理节点的唯一实际请求；当前IAM和真实Workflow来源由Store派发事务核验。 */
    public record Configuration(VerifiedIdentity actor, ExecutionStore.Kind kind, String target,
            ResultRef capability, ExecutionStore.Material request, ExecutionBudgetPort.Category category,
            ExecutionBudgetPort.Resources resources) {
        public Configuration {
            Objects.requireNonNull(actor); Objects.requireNonNull(kind); Objects.requireNonNull(target);
            Objects.requireNonNull(capability); Objects.requireNonNull(request); Objects.requireNonNull(category); Objects.requireNonNull(resources);
            if (target.isBlank() || !Set.of(ExecutionStore.Kind.MODEL, ExecutionStore.Kind.TOOL, ExecutionStore.Kind.KNOWLEDGE).contains(kind)
                    || kind == ExecutionStore.Kind.TOOL && category != ExecutionBudgetPort.Category.TOOL
                    || kind == ExecutionStore.Kind.KNOWLEDGE && category != ExecutionBudgetPort.Category.KNOWLEDGE
                    || kind == ExecutionStore.Kind.MODEL && category != ExecutionBudgetPort.Category.BUSINESS_MODEL
                            && category != ExecutionBudgetPort.Category.AUXILIARY_MODEL) throw invalid();
        }
    }
    public interface Sources {
        Configuration configuration(ExecutionStore.Run run);
        ExecutionStore.Material material(ExecutionStore.Run run, ResultRef reference);
        Optional<TypedPayload> scriptFeedback(TypedPayload request,ToolOutcome actual);
    }
    public enum State { READY, SUCCEEDED, FAILED, WAITING, UNKNOWN }
    /** Context只保存精确结果和等待引用；不复制原请求、原checkpoint或整个Run。 */
    public record Materials(Configuration configuration, State state, Optional<ResultRef> result,
            Optional<ExecutionStore.WaitRef> waiting, Optional<ToolOutcome.InputRequired> toolInput) {
        public Materials {
            Objects.requireNonNull(configuration); Objects.requireNonNull(state); Objects.requireNonNull(result);
            Objects.requireNonNull(waiting); Objects.requireNonNull(toolInput);
            if ((state == State.WAITING) != waiting.isPresent() || waiting.isPresent() != toolInput.isPresent()) throw invalid();
        }
        @Override public String toString() { return "WorkflowNodeMaterials[state=" + state + ",REDACTED]"; }
    }
    public WorkflowNodeProgram(Sources sources, CoreRuntime core, CodecLimits limits) {
        this.sources = Objects.requireNonNull(sources); this.core = Objects.requireNonNull(core); codec = new AgentExecutionCodec(limits);
    }
    public static ExecutionStore.Material initial(com.skillforge.runtime.api.RunRef run) {
        return context(run, State.READY, Optional.empty(), Optional.empty());
    }
    @Override public Materials open(ExecutionStore.Run run, ExecutionStore.Material context) {
        var configuration = sources.configuration(run);
        if (!configuration.actor().owner().equals(run.identity().owner())
                || !configuration.actor().verificationRef().equals(run.actorVerificationRef())) throw invalid();
        var value = parse(context.content());
        var state = State.valueOf(text(value, "state"));
        var result = optional(value, "result").map(ResultRef::new);
        Optional<ExecutionStore.WaitRef> waiting = value.get("action").isNull() ? Optional.empty()
                : Optional.of(new ExecutionStore.WaitRef(new ActionRef(run.identity().runRef().tenantId(), text(value, "action")), Long.parseLong(text(value, "generation"))));
        if (!context(run.identity().runRef(), state, result, waiting).equals(context)) throw invalid();
        Optional<ToolOutcome.InputRequired> input = waiting.map(ignored -> {
            var actual = codec.decodeToolOutcome(sources.material(run, result.orElseThrow(WorkflowNodeProgram::invalid)).content());
            if (!(actual instanceof ToolOutcome.InputRequired required)) throw invalid(); return required;
        });
        return new Materials(configuration, state, result, waiting, input);
    }
    @Override public ExecutionCoordinator.Step next(ExecutionStore.Run run, Materials materials) {
        return switch (materials.state()) {
            case READY -> dispatch(run, materials.configuration(), prepare(run, materials.configuration(), materials.configuration().request()), 1, Optional.empty());
            case SUCCEEDED -> new ExecutionCoordinator.Step.Finish(RunStatus.COMPLETED, List.of());
            case FAILED -> new ExecutionCoordinator.Step.Finish(RunStatus.FAILED, List.of());
            case UNKNOWN -> new ExecutionCoordinator.Step.Yield("workflow_node_result_unknown");
            case WAITING -> new ExecutionCoordinator.Step.Yield("workflow_node_input_required");
        };
    }
    @Override public ExecutionCoordinator.Step continuation(ExecutionStore.Run run, Materials before,
            ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
        var accepted = waiting.acceptedResponse().orElseThrow(WorkflowNodeProgram::invalid);
        var input = before.toolInput().orElseThrow(WorkflowNodeProgram::invalid);
        if (before.state() != State.WAITING || before.configuration().kind() != ExecutionStore.Kind.TOOL
                || !before.waiting().equals(Optional.of(waiting.reference())) || !run.waiting().equals(before.waiting())
                || waiting.kind() != ExecutionStore.WaitKind.TOOL || !waiting.run().equals(run.identity().runRef())
                || waiting.continuationAttempt().isPresent() || !waiting.requested().equals(methods(input))
                || !accepted.actorRef().equals(run.actorVerificationRef()) || !accepted.response().equals(response.reference())) throw invalid();
        var responses = codec.decodeToolResponses(response.content());
        if (!responses.keySet().equals(waiting.requested().keySet())) throw invalid();
        responses.forEach((key, value) -> { if (waiting.requested().get(key) != value.method()) throw invalid(); });
        var original = codec.decodeTool(before.configuration().request().content());
        if (original.continuation().isPresent()) throw invalid();
        var request = new AgentExecutionCodec.ToolRequest(original.target(), original.binding(), original.arguments(), original.callId(), original.wireName(),
                Optional.of(new AgentExecutionCodec.ToolContinuation(waiting.reference(), before.result().orElseThrow(), response.reference(), responses, input.requestState(), accepted.input(), accepted.acceptedRevision())));
        var content = codec.encodeTool(request);
        var material = new ExecutionStore.Material(new ResultRef("workflow-node-continuation:" + AgentExecutionCodec.digest(
                run.identity().runRef() + ":" + waiting.reference().generation() + ":" + response.reference())), content);
        return dispatch(run, before.configuration(), prepare(run, before.configuration(), material),
                Math.incrementExact(waiting.reference().generation()), Optional.of(waiting.reference()));
    }
    @Override public ExecutionCoordinator.Adoption<Materials> adopt(ExecutionStore.Run run, Materials before,
            ExecutionStore.Prepared prepared, ExecutionCoordinator.CapabilityResult actual) {
        if (!prepared.action().equals(action(run)) || prepared.ordinal() != 1 || prepared.kind() != before.configuration().kind()
                || !prepared.target().equals(before.configuration().target()) || !prepared.capability().equals(before.configuration().capability())) throw invalid();
        var snapshot = codec.decodeOutcome(actual.result().content());
        State state; boolean savedSuccessRejected = false;
        Optional<ExecutionStore.WaitRef> waiting = Optional.empty(); Optional<ToolOutcome.InputRequired> input = Optional.empty();
        Optional<ExecutionStore.WaitDeclaration> declaration = Optional.empty();
        if (prepared.kind() == ExecutionStore.Kind.MODEL) {
            if (!prepared.request().equals(before.configuration().request())) throw invalid();
            var outcome = core.decodeModelOutcome(snapshot, PortalCallKind.MODEL_GENERATE);
            state = outcome instanceof ModelResult.Completed ? State.SUCCEEDED : outcome instanceof ModelResult.Failed ? State.FAILED : State.UNKNOWN;
        } else if (prepared.kind() == ExecutionStore.Kind.KNOWLEDGE) {
            if (!prepared.request().equals(before.configuration().request())) throw invalid();
            var outcome = core.decodeKnowledgeOutcome(snapshot, PortalCallKind.KNOWLEDGE_EXECUTE, payloads);
            state = outcome instanceof KnowledgeOutcome.Retrieved ? State.SUCCEEDED
                    : outcome instanceof KnowledgeOutcome.Failed ? State.FAILED : State.UNKNOWN;
        } else if(SCRIPT_REQUEST_SCHEMA.equals(prepared.request().content().schemaId())) {
            if(!prepared.request().equals(before.configuration().request()))throw invalid();
            var outcome=codec.decodeToolOutcome(actual.result().content());Optional<TypedPayload> feedback;
            try{feedback=sources.scriptFeedback(prepared.request().content(),outcome);}
            catch(ScriptOutputRejected rejected){feedback=Optional.empty();savedSuccessRejected=true;}
            if(!feedback.equals(actual.feedback().map(ExecutionStore.Material::content)))throw invalid();
            state=outcome instanceof ToolOutcome.Succeeded?(savedSuccessRejected?State.FAILED:State.SUCCEEDED):outcome instanceof ToolOutcome.Failed?State.FAILED:State.UNKNOWN;
        } else {
            var request = codec.decodeTool(prepared.request().content());
            var original = codec.decodeTool(before.configuration().request().content());
            if (!new AgentExecutionCodec.ToolRequest(request.target(), request.binding(), request.arguments(), request.callId(), request.wireName()).equals(original)) throw invalid();
            var outcome = core.decodeToolOutcomeForInspection(snapshot, PortalCallKind.TOOL_INVOKE, payloads);
            Optional<TypedPayload> feedback; boolean outputRejected = false;
            try { feedback = codec.safeToolFeedback(request, outcome); }
            catch (AgentExecutionCodec.ToolOutputRejected rejected) {
                if (!(outcome instanceof ToolOutcome.Succeeded) || actual.kind() != ExecutionStore.ResultKind.SUCCEEDED || actual.feedback().isPresent()) throw invalid();
                feedback = Optional.empty(); outputRejected = true;
            }
            if (!feedback.equals(actual.feedback().map(ExecutionStore.Material::content))) throw invalid();
            savedSuccessRejected = outputRejected;
            state = switch (outcome) {
                case ToolOutcome.Succeeded ignored -> outputRejected ? State.FAILED : State.SUCCEEDED;
                case ToolOutcome.Failed ignored -> State.FAILED;
                case ToolOutcome.OutcomeUnknown ignored -> State.UNKNOWN;
                case ToolOutcome.InputRequired required -> State.WAITING;
            };
            if (outcome instanceof ToolOutcome.InputRequired required) {
                long generation = request.continuation().map(value -> Math.incrementExact(value.waiting().generation())).orElse(1L);
                var reference = new ExecutionStore.WaitRef(prepared.action(), generation);
                waiting = Optional.of(reference); input = Optional.of(required);
                declaration = Optional.of(new ExecutionStore.WaitDeclaration(reference, ExecutionStore.WaitKind.TOOL, methods(required), Optional.empty()));
            }
        }
        var expected = switch (state) {
            case SUCCEEDED -> ExecutionStore.ResultKind.SUCCEEDED;
            case FAILED -> savedSuccessRejected ? ExecutionStore.ResultKind.SUCCEEDED : ExecutionStore.ResultKind.FAILED;
            case WAITING -> ExecutionStore.ResultKind.INPUT_REQUIRED;
            case UNKNOWN -> ExecutionStore.ResultKind.UNKNOWN;
            default -> throw invalid();
        };
        if (actual.kind() != expected) throw invalid();
        var result = Optional.of(actual.result().reference());
        var context = context(run.identity().runRef(), state, result, waiting);
        var after = new Materials(before.configuration(), state, result, waiting, input);
        long models = before.configuration().kind() == ExecutionStore.Kind.MODEL ? 1 : 0;
        long knowledge = before.configuration().kind() == ExecutionStore.Kind.KNOWLEDGE ? 1 : 0;
        long tools = before.configuration().kind() == ExecutionStore.Kind.TOOL ? Math.incrementExact(run.progress().toolCalls()) : 0;
        List<ExecutionStore.Event> events = prepared.kind() == ExecutionStore.Kind.MODEL
                ? List.of(ExecutionModelLifecycle.finished(run, prepared, actual.kind(), codec))
                : input.isPresent() ? List.of(waitingEvent(run, waiting.orElseThrow(), input.orElseThrow())) : List.of();
        return new ExecutionCoordinator.Adoption<>(after, new ExecutionStore.Progress(models, tools, knowledge, context.reference()), context, events, declaration);
    }
    @Override public ExecutionCoordinator.Adoption<Materials> acceptResponse(ExecutionStore.Run run, Materials materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) { throw invalid(); }
    @Override public ExecutionCoordinator.Adoption<Materials> planAdoption(ExecutionStore.Run run,Materials materials,ExecutionCoordinator.Step.PlanLocal command,ExecutionStore.Material actualResult){throw invalid();}
    @Override public ExecutionCoordinator.Adoption<Materials> acceptInput(ExecutionStore.Run run,Materials materials,ExecutionStore.SupplementalInput input,ExecutionStore.Material content){throw invalid();}
    @Override public boolean batchParallel(Materials materials) { throw invalid(); }
    @Override public ExecutionCoordinator.BatchResult inspectBatch(ExecutionStore.Run run, Materials materials, ExecutionCoordinator.BatchCall result, int slot) { throw invalid(); }
    @Override public ExecutionCoordinator.Adoption<Materials> adoptBatch(ExecutionStore.Run run, Materials materials, List<ExecutionCoordinator.BatchOutcome> results) { throw invalid(); }
    @Override public ExecutionCoordinator.BatchPlan<Materials> batchContinuation(ExecutionStore.Run run,Materials materials,List<ExecutionCoordinator.BatchOutcome> results,List<ExecutionCoordinator.BatchResponse> responses){throw invalid();}

    private ExecutionCoordinator.Step.Dispatch dispatch(ExecutionStore.Run run, Configuration configuration,
            ExecutionStore.Prepared prepared, long generation, Optional<ExecutionStore.WaitRef> continuation) {
        String attempt = prepared.action().actionId() + ":attempt:" + generation;
        var events = prepared.kind() == ExecutionStore.Kind.MODEL ? List.of(ExecutionModelLifecycle.started(run, prepared, codec)) : List.<ExecutionStore.Event>of();
        return new ExecutionCoordinator.Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt,
                prepared.fingerprint(), configuration.category(), configuration.resources(), false), configuration.actor(), continuation, events);
    }
    private static ExecutionStore.Prepared prepare(ExecutionStore.Run run, Configuration configuration, ExecutionStore.Material request) {
        return new ExecutionStore.Prepared(action(run), 1, configuration.kind(), configuration.target(), configuration.capability(), request,
                AgentExecutionCodec.digest(request.content().schemaId() + "\0" + request.content().canonicalJson()));
    }
    private static ActionRef action(ExecutionStore.Run run) {
        return new ActionRef(run.identity().runRef().tenantId(), "workflow-node-effect:" + AgentExecutionCodec.digest(run.identity().runRef().runId()));
    }
    private static Map<String,ToolInputMethod> methods(ToolOutcome.InputRequired input) {
        return input.inputRequests().entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().method()));
    }
    private ExecutionStore.Event waitingEvent(ExecutionStore.Run run,ExecutionStore.WaitRef waiting,ToolOutcome.InputRequired input) {
        try {
            var value=JSON.createObjectNode();value.put("actionId",waiting.action().actionId());value.put("generation",Long.toString(waiting.generation()));
            value.put("kind","tool");value.putNull("question");value.put("inputId",run.identity().causationInputId().value());
            value.put("rootInputId",run.identity().trace().rootInputId().value());value.put("traceId",run.identity().trace().traceId());
            value.put("spanId",run.identity().trace().spanId());value.put("agentId",run.identity().agentId().value());
            var requests=value.putObject("requests");new TreeMap<>(input.inputRequests()).forEach((key,request)->{
                var item=requests.putObject(key);item.put("method",request.method().wireName());var parameters=item.putObject("params");
                parameters.put("schemaId",request.params().schemaId());parameters.put("canonicalJson",request.params().canonicalJson());
            });
            return new ExecutionStore.Event(new com.skillforge.runtime.api.EventId("workflow-node-wait:"+AgentExecutionCodec.digest(waiting.toString())),
                    "execution.waiting",new TypedPayload("skillforge.runtime.execution.waiting.v1",JSON.writeValueAsString(value)));
        } catch(java.io.IOException invalid){throw invalid();}
    }
    private static ExecutionStore.Material context(com.skillforge.runtime.api.RunRef run, State state,
            Optional<ResultRef> result, Optional<ExecutionStore.WaitRef> waiting) {
        try {
            var value = new TreeMap<String,Object>(); value.put("state", state.name()); value.put("result", result.map(ResultRef::value).orElse(null));
            value.put("action", waiting.map(reference -> reference.action().actionId()).orElse(null));
            value.put("generation", waiting.map(reference -> Long.toString(reference.generation())).orElse(null));
            var content = new TypedPayload(CONTEXT_SCHEMA, JSON.writeValueAsString(value));
            return new ExecutionStore.Material(new ResultRef("workflow-node-context:" + AgentExecutionCodec.digest(run.runId() + "\0" + content.canonicalJson())), content);
        } catch (java.io.IOException invalid) { throw invalid(); }
    }
    private static JsonNode parse(TypedPayload content) {
        if (!CONTEXT_SCHEMA.equals(content.schemaId())) throw invalid();
        try {
            var value = JSON.readTree(content.canonicalJson()); var keys = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(Set.of("state", "result", "action", "generation"))) throw invalid(); return value;
        } catch (java.io.IOException invalid) { throw invalid(); }
    }
    private static String text(JsonNode value, String field) { var item = value.get(field); if (item == null || !item.isTextual() || item.asText().isBlank()) throw invalid(); return item.asText(); }
    private static Optional<String> optional(JsonNode value, String field) { if (value.get(field) == null) throw invalid(); return value.get(field).isNull() ? Optional.empty() : Optional.of(text(value, field)); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("workflow_node_program_invalid"); }
}
