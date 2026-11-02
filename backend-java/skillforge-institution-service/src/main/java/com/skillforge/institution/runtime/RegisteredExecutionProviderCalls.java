package com.skillforge.institution.runtime;

import com.skillforge.runtime.session.RegisteredExecutionCall;

import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgePortal;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLookup;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.ObservedModelInvocationAudit;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import com.skillforge.core.capability.integration.runtime.AgentRuntimeMcpToolProvider;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Agent与Workflow节点共用的真实Provider边界；只消费当前Store实际登记回执，不另建执行循环。 */
public final class RegisteredExecutionProviderCalls {
    private final OpenAiModelPort models;
    private final AgentRuntimeModelInvocationAudit audit;
    private final AgentRuntimeToolInvocationAudit toolAudit;
    private final AgentRuntimePlatformToolProvider tools;
    private final AgentRuntimeMcpToolProvider mcp;
    private final JdbcKnowledgePortal knowledge;
    private final ExecutionStore store;
    private final CodecLimits limits;
    private final AgentExecutionCodec codec;
    private final CoreRuntime.PortalResults outcomes;
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
    private final SessionExecutionWorkflows workflowCodec;
    private final WorkflowTestRuntimePolicy tests;

    public RegisteredExecutionProviderCalls(OpenAiModelPort models, AgentRuntimeModelInvocationAudit audit, AgentRuntimeToolInvocationAudit toolAudit,
            AgentRuntimePlatformToolProvider tools, AgentRuntimeMcpToolProvider mcp, JdbcKnowledgePortal knowledge,
            ExecutionStore store, CoreRuntime core, CodecLimits limits,WorkflowTestRuntimePolicy tests) {
        this.models = Objects.requireNonNull(models); this.audit = Objects.requireNonNull(audit);
        this.toolAudit = Objects.requireNonNull(toolAudit);
        this.tools = Objects.requireNonNull(tools); this.mcp = Objects.requireNonNull(mcp);
        this.knowledge = Objects.requireNonNull(knowledge); this.store = Objects.requireNonNull(store);
        this.limits = Objects.requireNonNull(limits); codec = new AgentExecutionCodec(limits);
        outcomes = Objects.requireNonNull(core).portalResults(payloads, payloads, payloads);
        workflowCodec = new SessionExecutionWorkflows(limits);this.tests=Objects.requireNonNull(tests);
    }

    /** 普通模型节点保留其原响应契约；Agent决策解码由各自Program在真实结果落盘后执行。 */
    public ExecutionCoordinator.CapabilityResult model(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, PortalCallKind kind, FrozenModelRequest request,
            JdbcModelBindings.Binding binding, InvocationControl control) {
        return model(registered, prepared, kind, request, binding, control, content -> { });
    }

    /** 观察器只负责非权威展示，结果保存仍沿同一真实模型调用。 */
    public ExecutionCoordinator.CapabilityResult model(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, PortalCallKind kind, FrozenModelRequest request,
            JdbcModelBindings.Binding binding, InvocationControl control, Consumer<String> bodyPreview) {
        if (kind != PortalCallKind.MODEL_GENERATE && kind != PortalCallKind.MODEL_STREAM)
            throw new IllegalArgumentException("execution_model_call_kind_invalid");
        if (!codec.decodeModelRequest(prepared, refs -> store.materials(registered.run().identity().runRef().tenantId(), refs)).request().equals(request)) throw requestMismatch();
        var context = context(registered, prepared, kind, control);
        RegisteredExecutionCall.require(registered, prepared, context, ExecutionStore.Kind.MODEL);
        try { tests.provider(registered.run(),prepared); }
        catch(com.skillforge.core.shared.exception.ApiException denied) {
            var failed=new ModelResult.Failed(new PortFailure(denied.getCode(),RetryMode.NONE,new ResultRef("execution-result:"+registered.attempt().id())));
            return new ExecutionCoordinator.CapabilityResult(ExecutionStore.ResultKind.FAILED,result(registered,outcomes.encodeModel(kind,failed)),Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.unreported());
        }
        var call = new ModelCall(context, request.requestedStrategy(), request.target(), prepared.request().reference(),
                request.messages(), request.tools(), request.toolChoice(), request.response(), request.generation(), request.reasoning(), request.budgetCategory());
        var profile = binding.providerProfile();
        var transport = new OpenAiModelPort.Binding(binding.target(), binding.strategy(), binding.endpoint(), binding.encryptedCredential(),
                new OpenAiModelPort.ProviderProfile(profile.providerId(), profile.serviceId(), profile.profileVersion(),
                        ModelProviderCatalog.ModelProtocol.fromId(profile.protocol()), ModelProviderCatalog.AuthenticationScheme.fromId(profile.authenticationScheme()), profile.dialectVersion()));
        var attemptAudit = new ObservedModelInvocationAudit(audit.dispatched(registered, prepared, binding));
        var model = models.forDispatch(call, transport, attemptAudit, bodyPreview);
        ModelResult actual = kind == PortalCallKind.MODEL_STREAM
                ? stream(model, call) : model.generate(call);
        var failedUsage = attemptAudit.observed().map(ObservedModelInvocationAudit.ObservedUsage::usage);
        ExecutionBudgetPort.Usage usage = actual instanceof ModelResult.Completed completed
                ? ExecutionBudgetPort.Usage.reported(completed.usage().inputTokens(), completed.usage().outputTokens())
                : actual instanceof ModelResult.Failed && failedUsage.isPresent()
                ? ExecutionBudgetPort.Usage.reported(failedUsage.orElseThrow().inputTokens(), failedUsage.orElseThrow().outputTokens())
                : ExecutionBudgetPort.Usage.unreported();
        var status = actual instanceof ModelResult.Completed ? ExecutionStore.ResultKind.SUCCEEDED
                : actual instanceof ModelResult.Failed ? ExecutionStore.ResultKind.FAILED : ExecutionStore.ResultKind.UNKNOWN;
        return new ExecutionCoordinator.CapabilityResult(status, result(registered, outcomes.encodeModel(kind, actual)),
                Optional.empty(), Optional.empty(), usage);
    }

    /** 工具原始envelope先保存；安全反馈拒绝不能覆盖已经发生的真实结果。 */
    public ExecutionCoordinator.CapabilityResult tool(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, AgentExecutionCodec.ToolRequest request,
            Optional<JdbcMcpBindings.Binding> binding, Optional<TypedPayload> extraParameters, InvocationControl control) {
        return tool(registered,prepared,request,binding,extraParameters,control,Optional.empty(),Optional.empty());
    }

    /** 独立program子Run的Provider入口；source仅证明真实原准入，不能替代Store当前派发许可。 */
    public ExecutionCoordinator.CapabilityResult programTool(AgentRuntimeProgramTools source,ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared,AgentExecutionCodec.ToolRequest request,Optional<JdbcMcpBindings.Binding> binding,
            Optional<TypedPayload> extraParameters,InvocationControl control) {
        source.requireCall(registered,prepared);
        return tool(registered,prepared,request,binding,extraParameters,control,Optional.of(source),Optional.empty());
    }

    /** 真实Workflow节点首次/继续Tool共享同一已证明来源，不从工具名推断Workflow身份。 */
    public ExecutionCoordinator.CapabilityResult workflowTool(AgentRuntimeWorkflowNodeExecutions source,ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared,AgentExecutionCodec.ToolRequest request,Optional<JdbcMcpBindings.Binding> binding,
            Optional<TypedPayload> extraParameters,InvocationControl control) {
        source.requireCall(registered,prepared);
        return tool(registered,prepared,request,binding,extraParameters,control,Optional.empty(),Optional.of(source));
    }

    private ExecutionCoordinator.CapabilityResult tool(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, AgentExecutionCodec.ToolRequest request,
            Optional<JdbcMcpBindings.Binding> binding, Optional<TypedPayload> extraParameters, InvocationControl control,
            Optional<AgentRuntimeProgramTools> program,Optional<AgentRuntimeWorkflowNodeExecutions> workflow) {
        if(program.isPresent()&&workflow.isPresent())throw new IllegalArgumentException("execution_tool_source_mismatch");
        Objects.requireNonNull(binding); Objects.requireNonNull(extraParameters);
        if (!codec.decodeTool(prepared.request().content()).equals(request)) throw requestMismatch();
        SessionRuntimePayloads.validateAgainstSchema(request.target().inputSchema(), request.arguments(), limits);
        var call = new ToolCall(context(registered, prepared, PortalCallKind.TOOL_INVOKE, control),
                request.binding(), request.target(), request.arguments(), new IdempotencyKey(prepared.action().actionId()));
        RegisteredExecutionCall.require(registered, prepared, call.context(), ExecutionStore.Kind.TOOL);
        ToolOutcome actual;String testRejection=null;
        try {tests.provider(registered.run(),prepared);tests.tool(registered.run(),request);}
        catch(com.skillforge.core.shared.exception.ApiException denied) {testRejection=denied.getCode();}
        if(testRejection!=null)actual=rejectedTool(registered,testRejection);
        else if (!request.target().modelInvocable()) actual = rejectedTool(registered, "tool_model_invocation_not_allowed");
        else if (request.target().confirmation() != ToolConfirmationPolicy.NEVER) actual = rejectedTool(registered, "tool_confirmation_required");
        else if (request.continuation().isPresent()) {
            var continuation = request.continuation().orElseThrow();
            if (!registered.attempt().continuation().equals(Optional.of(continuation.waiting())))
                throw new IllegalArgumentException("execution_tool_continuation_mismatch");
            var waiting = store.waiting(registered.run().identity().runRef(), continuation.waiting())
                    .orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
            var accepted = waiting.acceptedResponse().orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
            if (!accepted.input().equals(continuation.inputId()) || accepted.acceptedRevision() != continuation.acceptedRevision()
                    || !accepted.response().equals(continuation.response())) throw requestMismatch();
            var resumed = new ToolContinuationCall(call.context(), call.binding(), call.target(), call.arguments(), call.idempotencyKey(),
                    continuation.responses(), continuation.requestState());
            actual = switch (request.binding().providerType()) {
                case PLATFORM -> toolAudit.platform(registered, prepared, call,
                        () -> workflow.isPresent()?tools.resumeWorkflowRegistered(registered,prepared,resumed,workflow.orElseThrow()):tools.resumeRegistered(registered, prepared, resumed));
                case MCP -> toolAudit.mcp(registered, prepared, call,
                        beforeSend -> mcp.forContinuation(resumed, binding.orElseThrow(RegisteredExecutionProviderCalls::requestMismatch),
                                () -> testToolAllowed(registered,request) && store.authorizeSend(registered, prepared), beforeSend, this::validObservation, extraParameters).resume(resumed));
                default -> throw new IllegalArgumentException("execution_tool_provider_unsupported");
            };
        } else {
            if (registered.attempt().continuation().isPresent()) throw new IllegalArgumentException("execution_tool_continuation_missing");
            actual = switch (request.binding().providerType()) {
                case PLATFORM -> toolAudit.platform(registered, prepared, call,
                        () -> program.isPresent()?tools.invokeProgramRegistered(registered,prepared,call,program.orElseThrow()):workflow.isPresent()?tools.invokeWorkflowRegistered(registered,prepared,call,workflow.orElseThrow()):tools.invokeRegistered(registered, prepared, call));
                case MCP -> toolAudit.mcp(registered, prepared, call,
                        beforeSend -> mcp.forDispatch(call, binding.orElseThrow(RegisteredExecutionProviderCalls::requestMismatch),
                                () -> testToolAllowed(registered,request) && store.authorizeSend(registered, prepared), beforeSend, this::validObservation, extraParameters).invoke(call));
                default -> throw new IllegalArgumentException("execution_tool_provider_unsupported");
            };
        }
        var encoded = codec.encodeToolOutcome(outcomes, actual);
        actual = encoded.outcome();
        var savedResult = new ExecutionStore.Material(new ResultRef("execution-result:" + registered.attempt().id()), encoded.payload());
        Optional<ExecutionStore.Material> feedback;
        try {
            feedback = codec.safeToolFeedback(request, actual).map(value -> new ExecutionStore.Material(
                    new ResultRef("execution-feedback:" + registered.attempt().id()), value));
        } catch (AgentExecutionCodec.ToolOutputRejected rejected) { feedback = Optional.empty(); }
        var status = switch (actual) {
            case ToolOutcome.Succeeded ignored -> ExecutionStore.ResultKind.SUCCEEDED;
            case ToolOutcome.Failed ignored -> ExecutionStore.ResultKind.FAILED;
            case ToolOutcome.InputRequired ignored -> ExecutionStore.ResultKind.INPUT_REQUIRED;
            case ToolOutcome.OutcomeUnknown ignored -> ExecutionStore.ResultKind.UNKNOWN;
        };
        return new ExecutionCoordinator.CapabilityResult(status, savedResult, feedback, Optional.empty(), ExecutionBudgetPort.Usage.unreported());
    }

    private boolean testToolAllowed(ExecutionStore.DispatchResult.Registered registered,AgentExecutionCodec.ToolRequest request) {
        try {tests.tool(registered.run(),request);return true;}
        catch(com.skillforge.core.shared.exception.ApiException denied) {return false;}
    }

    /** 查询消费冻结知识绑定；确定的前置拒绝与已经发送但未决的技术异常保持区别。 */
    public ExecutionCoordinator.CapabilityResult knowledge(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, AgentExecutionCodec.KnowledgeCallRequest request,
            JdbcKnowledgeBindings.Binding binding, InvocationControl control) {
        if (!codec.decodeKnowledgeRequest(prepared.request().content()).equals(request)) throw requestMismatch();
        var context = context(registered, prepared, PortalCallKind.KNOWLEDGE_EXECUTE, control);
        RegisteredExecutionCall.require(registered, prepared, context, ExecutionStore.Kind.KNOWLEDGE);
        var reference = new ResultRef("execution-result:" + registered.attempt().id());
        KnowledgeOutcome actual;
        try {
            tests.provider(registered.run(),prepared);tests.knowledge(registered.run(),request);
            var bundle = knowledge.retrieve(request.request(), request.target(), binding, prepared.fingerprint(), control,
                    () -> !control.cancellationSignal().isCancellationRequested());
            actual = new KnowledgeOutcome.Retrieved(bundle, reference);
        } catch(com.skillforge.core.shared.exception.ApiException denied) {
            actual=new KnowledgeOutcome.Failed(new PortFailure(denied.getCode(),RetryMode.NONE,reference));
        } catch (JdbcKnowledgePortal.RetrievalRejected rejected) {
            actual = new KnowledgeOutcome.Failed(new PortFailure(rejected.reasonCode(), RetryMode.NONE, reference));
        }
        var status = actual instanceof KnowledgeOutcome.Retrieved ? ExecutionStore.ResultKind.SUCCEEDED : ExecutionStore.ResultKind.FAILED;
        return new ExecutionCoordinator.CapabilityResult(status, result(registered, outcomes.encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, actual)),
                Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
    }

    /** Workflow与模型/工具共用同一个Registered边界；实际raw结果始终无损编码。 */
    ExecutionCoordinator.CapabilityResult workflow(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared,
            SessionExecutionWorkflows.Request request, SdkWorkflowPortal workflows, JdbcExecutionLedgerPort executions,
            WorkflowInteractionView interactions, InvocationControl control) {
        if (!workflowCodec.decode(prepared.request().content()).equals(request)) throw requestMismatch();
        var context = context(registered, prepared, request.callKind(), control);
        RegisteredExecutionCall.require(registered, prepared, context, ExecutionStore.Kind.WORKFLOW);
        if (request instanceof SessionExecutionWorkflows.Resume resume) {
            var reference = registered.attempt().continuation().orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
            var waiting = store.waiting(registered.run().identity().runRef(), reference).orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
            if (waiting.kind() != ExecutionStore.WaitKind.WORKFLOW || !(waiting.internal().orElseThrow() instanceof ExecutionStore.WorkflowWait exact)
                    || !exact.execution().equals(resume.executionRef()) || !exact.suspendRef().equals(resume.suspendRef())
                    || !exact.actionKey().equals(resume.actionKey()) || exact.executionRevision() != resume.expectedRevision()
                    || waiting.acceptedResponse().isEmpty() || !waiting.continuationAttempt().equals(Optional.of(registered.attempt().id()))
                    || !waiting.acceptedResponse().orElseThrow().input().equals(resume.inputId())
                    || !waiting.acceptedResponse().orElseThrow().response().equals(resume.responseRef())
                    || !store.material(registered.run().identity().runRef().tenantId(), waiting.acceptedResponse().orElseThrow().response())
                            .orElseThrow(RegisteredExecutionProviderCalls::requestMismatch).content().equals(resume.response())) throw requestMismatch();
        } else if (registered.attempt().continuation().isPresent()) throw requestMismatch();
        com.skillforge.core.shared.exception.ApiException testRejection=null;
        try {tests.provider(registered.run(),prepared);}
        catch(com.skillforge.core.shared.exception.ApiException denied) {
            if(!(request instanceof SessionExecutionWorkflows.Dispatch)&&!(request instanceof SessionExecutionWorkflows.Resume))throw denied;
            testRejection=denied;
        }
        var key = new IdempotencyKey(prepared.action().actionId());
        PortalOutcomeSnapshot raw; Optional<TypedPayload> feedback = Optional.empty();
        Optional<WorkflowResult.Suspended> suspended = Optional.empty();
        if(testRejection!=null) {
            var failed=new WorkflowResult.Failed(request.executionRef(),java.util.OptionalLong.empty(),
                    new PortFailure(testRejection.getCode(),RetryMode.NONE,new ResultRef("execution-result:"+registered.attempt().id())));
            raw=outcomes.encodeWorkflow(request.callKind(),failed);feedback=workflowCodec.feedback(failed);
        } else if (request instanceof SessionExecutionWorkflows.Dispatch dispatch) {
            var actual = workflows.dispatchRegistered(registered, prepared, new WorkflowDispatch(context, dispatch.snapshot(), dispatch.target(),
                    dispatch.executionRef(), dispatch.parameters(), dispatch.evidenceRefs(), key));
            raw = outcomes.encodeWorkflow(request.callKind(), actual); feedback = workflowCodec.feedback(actual);
            if (actual instanceof WorkflowResult.Suspended value) suspended = Optional.of(value);
        } else if (request instanceof SessionExecutionWorkflows.Resume resume) {
            var actual = workflows.resumeRegistered(registered, prepared, new WorkflowResume(context, resume.snapshot(), resume.target(), resume.executionRef(),
                    resume.suspendRef(), resume.actionKey(), resume.response(), resume.expectedRevision(), key));
            raw = outcomes.encodeWorkflow(request.callKind(), actual); feedback = workflowCodec.feedback(actual);
            if (actual instanceof WorkflowResult.Suspended value) suspended = Optional.of(value);
        } else if (request instanceof SessionExecutionWorkflows.Cancel cancel) {
            var actual = workflows.cancelRegistered(registered, prepared, new WorkflowCancel(context, cancel.snapshot(), cancel.target(), cancel.executionRef(),
                    cancel.actionKey(), cancel.expectedRevision(), cancel.reasonCode(), key));
            raw = outcomes.encodeWorkflowControl(request.callKind(), actual);
        } else {
            if (!store.authorizeSend(registered, prepared)) throw new IllegalStateException("workflow_query_dispatch_rejected");
            var actual = workflows.recoverResult(new WorkflowResultQuery(context, request.snapshot(), request.target(), request.executionRef()));
            raw = outcomes.encodeWorkflowRecovery(request.callKind(), actual);
            if (actual instanceof WorkflowRecoveredResult.Suspended value) suspended = Optional.of(value.result());
        }
        Optional<ExecutionStore.WorkflowWait> waiting = suspended.map(value -> {
            var current = executions.find(new ExecutionLookup(registered.actor(), value.executionRef())).orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
            var exact = current.waiting().orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
            if (!current.owner().equals(registered.run().identity()) || !current.executionRef().equals(request.executionRef())
                    || current.executionRevision() != value.revision() || !exact.suspendRef().equals(value.suspendRef())
                    || current.cancelRequestId().isPresent() || !current.state().isWaiting()) throw requestMismatch();
            return new ExecutionStore.WorkflowWait(current.executionRef(), exact.suspendRef(), exact.actionKey(), current.executionRevision());
        });
        var kind = switch (raw.resolution()) {
            case KNOWN_RESULT -> ExecutionStore.ResultKind.SUCCEEDED;
            case KNOWN_FAILURE -> ExecutionStore.ResultKind.FAILED;
            case OUTCOME_UNKNOWN -> ExecutionStore.ResultKind.UNKNOWN;
            case INPUT_REQUIRED -> throw requestMismatch();
        };
        var human = suspended.filter(value -> value.reason() == com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL).flatMap(value ->
                interactions.find(new WorkflowQuery(registered.actor(),value.executionRef())).map(pending -> {
                    var exact = waiting.orElseThrow(RegisteredExecutionProviderCalls::requestMismatch);
                    if (!pending.owner().equals(registered.run().identity().owner()) || !pending.runRef().equals(registered.run().identity().runRef())
                            || !pending.executionRef().equals(exact.execution()) || !pending.suspendRef().equals(exact.suspendRef())
                            || !pending.actionKey().equals(exact.actionKey()) || pending.expectedRevision()!=exact.executionRevision()) throw requestMismatch();
                    return new SessionExecutionWorkflows.HumanInteraction(pending.card());
                }));
        var saved = new ExecutionStore.Material(new ResultRef("execution-result:" + registered.attempt().id()),
                workflowCodec.encodeResult(new SessionExecutionWorkflows.Result(codec.encodeOutcome(raw), waiting, human)));
        return new ExecutionCoordinator.CapabilityResult(kind, saved, feedback.map(value -> new ExecutionStore.Material(
                new ResultRef("execution-feedback:" + registered.attempt().id()), value)), Optional.of(request.executionRef().executionId()), ExecutionBudgetPort.Usage.unreported());
    }

    /** 脚本沿真实工具结果契约无损编码；本方法纯计算，不取得发送权或更新执行事实。 */
    public ExecutionStore.Material encodeToolResult(ExecutionStore.DispatchResult.Registered registered, ToolOutcome actual) {
        return encodeToolResult(Objects.requireNonNull(registered).attempt(), actual);
    }

    /** 对原已派发操作的准确只读观察复用原结果身份；不构造 Registered 或签发发送权。 */
    public ExecutionStore.Material encodeToolResult(ExecutionStore.Attempt original, ToolOutcome actual) {
        return new ExecutionStore.Material(new ResultRef("execution-result:" + Objects.requireNonNull(original).id()),
                codec.encodeToolOutcome(outcomes, Objects.requireNonNull(actual)).payload());
    }

    private ExecutionStore.Material result(ExecutionStore.DispatchResult.Registered registered, PortalOutcomeSnapshot actual) {
        return new ExecutionStore.Material(new ResultRef("execution-result:" + registered.attempt().id()), codec.encodeOutcome(actual));
    }
    private ToolOutcome rejectedTool(ExecutionStore.DispatchResult.Registered registered, String reason) {
        return new ToolOutcome.Failed(new PortFailure(reason, RetryMode.NONE, new ResultRef("execution-tool-rejected:" + registered.attempt().id())));
    }
    private boolean validObservation(TypedPayload observation) {
        try { payloads.validate(observation, limits); return true; }
        catch (IllegalArgumentException rejected) { return false; }
    }
    private static IllegalArgumentException requestMismatch() { return new IllegalArgumentException("registered_provider_request_mismatch"); }
    ProviderCallContext context(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared,
            PortalCallKind kind, InvocationControl control) {
        var run = registered.run();
        var dispatch = new ProviderDispatch(run.identity().runRef(), registered.action().reference(), registered.action().reference().actionId(),
                registered.attempt().id(), kind, registered.attempt().request(), registered.action().capability(), prepared.fingerprint(), registered.attempt().fence());
        return ProviderCallContext.dispatched(new PortCallContext(run.identity(), registered.actor(), Objects.requireNonNull(control)), dispatch);
    }

    /** 流只采纳实际终态；没有终态或技术异常保留原派发，不伪造成功或自动重发。 */
    private ModelResult stream(OpenAiModelPort model, ModelCall call) {
        var result = new CompletableFuture<ModelResult>(); var subscription = new AtomicReference<Flow.Subscription>();
        model.stream(call).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(Long.MAX_VALUE); }
            @Override public void onNext(ModelStreamEvent value) {
                switch (value) {
                    case ModelStreamEvent.Completed completed -> complete(completed.result());
                    case ModelStreamEvent.Failed failed -> complete(new ModelResult.Failed(failed.failure(), failed.retryAt()));
                    case ModelStreamEvent.OutcomeUnknown unknown -> complete(new ModelResult.OutcomeUnknown(unknown.failure()));
                    default -> { }
                }
            }
            private void complete(ModelResult value) {
                if (!result.complete(value)) result.obtrudeException(new IllegalStateException("model_stream_multiple_terminals"));
            }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { if (!result.isDone()) result.completeExceptionally(new IllegalStateException("model_stream_terminal_missing")); }
        });
        try { return Objects.requireNonNull(result.get(Math.addExact(call.target().timeout().toMillis(), 1000), TimeUnit.MILLISECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("execution_stream_interrupted", interrupted); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("execution_stream_unresolved", failure); }
        finally {
            if (subscription.get() != null) subscription.get().cancel();
        }
    }
}
