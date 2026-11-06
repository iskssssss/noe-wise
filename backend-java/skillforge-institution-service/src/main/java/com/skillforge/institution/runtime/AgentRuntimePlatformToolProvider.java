package com.skillforge.institution.runtime;

import com.skillforge.runtime.session.RegisteredExecutionCall;
import com.skillforge.common.context.TenantContext;

import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.api.port.InvocationControl;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.capability.connector.PlatformInteractiveToolHandler;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.institution.execution.ExecutionSubmissionRejectedException;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolContinuationCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;

/** Institution 内建 Platform Tool 的 Runtime Provider。 */
public final class AgentRuntimePlatformToolProvider {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AgentRuntimePlatformToolProvider.class);
    private static final TypeReference<Map<String,Object>> OBJECT=new TypeReference<>(){};
    private static final Set<String> TRUSTED_CONTEXT_FIELDS=Set.of(
            "tenant", "tenant_id", "tenantId", "end_user_id", "endUserId",
            "user", "user_id", "userId", "subject", "subject_id", "subjectId",
            "principal", "principal_ref", "principalRef", "session", "session_id", "sessionId",
            "session_ref", "sessionRef", "run", "run_id", "runId", "run_ref", "runRef",
            "input", "input_id", "inputId", "invocation", "invocation_id", "invocationId",
            "attempt", "attempt_id", "attemptId", "deadline", "deadline_at", "deadlineAt",
            "cancellation", "cancellation_signal", "cancellationSignal",
            "permit", "action_permit", "actionPermit", "program_child", "programChild", "workflow_node", "workflowNode");
    private final ObjectProvider<PlatformToolHandler> handlers;
    private final ExternalInvocationGuard external;
    private final ObjectMapper json;
    private final PlatformToolCatalog platformTools;

    public AgentRuntimePlatformToolProvider(ObjectProvider<PlatformToolHandler> handlers,
            ExternalInvocationGuard external, PlatformToolCatalog platformTools){
        this.handlers=Objects.requireNonNull(handlers);
        this.external=Objects.requireNonNull(external);
        this.json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.platformTools = Objects.requireNonNull(platformTools);
    }

    public ToolBinding.ProviderType providerType(){return ToolBinding.ProviderType.PLATFORM;}

    public boolean supports(ToolBinding binding,ToolCall call){
        if(!binding.equals(call.binding())||binding.providerType()!=providerType()
                ||!binding.providerRef().equals(binding.toolName())
                ||!call.target().providerRequirements().equals(Set.of("platform_handler:"+binding.toolName())))return false;
        return handler(binding).isPresent();
    }

    /**
     * 新主 Agent 的普通工具派发，仅接收 Store 已完成当前授权与预算登记的本次 Registered。
     * 程序子调用与 Workflow 节点不能使用此入口；本入口真实标记为 false/false，不从工具参数猜测。
     * 回执全值核对后复用实际 Handler、外调账本、媒体和交互输出，不再次读 IAM 或旧 permit。
     */
    public ToolOutcome invokeRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, ToolCall call) {
        if (registered.attempt().continuation().isPresent())
            throw new IllegalArgumentException("registered_tool_continuation_required");
        return invokeRegistered(registered, prepared, call, Optional.empty());
    }

    /** Program身份由真实原准入来源证明，不能由工具参数或workflow节点名称指定。 */
    public ToolOutcome invokeProgramRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared,ToolCall call,AgentRuntimeProgramTools source) {
        source.requireCall(registered,prepared);
        return invokeRegistered(registered,prepared,call,Optional.empty(),true,false);
    }

    /** Workflow节点身份来自所属Host对真实Engine/Registered来源的核验，与Program身份独立。 */
    public ToolOutcome invokeWorkflowRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared,ToolCall call,AgentRuntimeWorkflowNodeExecutions source) {
        if(registered.attempt().continuation().isPresent())throw new IllegalArgumentException("registered_tool_continuation_required");
        source.requireCall(registered,prepared);
        return invokeRegistered(registered,prepared,call,Optional.empty(),false,true);
    }

    public ToolOutcome resumeWorkflowRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared,ToolContinuationCall call,AgentRuntimeWorkflowNodeExecutions source) {
        if(registered.attempt().continuation().isEmpty())throw new IllegalArgumentException("registered_tool_continuation_missing");
        source.requireCall(registered,prepared);
        var base=new ToolCall(call.context(),call.binding(),call.target(),call.arguments(),call.idempotencyKey());
        return invokeRegistered(registered,prepared,base,Optional.of(call),false,true);
    }

    /** 同一Action的新Attempt恢复真实交互Handler，原opaque state与响应只传给resume。 */
    public ToolOutcome resumeRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, ToolContinuationCall call) {
        if (registered.attempt().continuation().isEmpty())
            throw new IllegalArgumentException("registered_tool_continuation_missing");
        var base = new ToolCall(call.context(), call.binding(), call.target(), call.arguments(), call.idempotencyKey());
        return invokeRegistered(registered, prepared, base, Optional.of(call));
    }

    /** 首次调用与续跑共享真实账本及上下文；continuation不借用旧许可或重新执行invoke。 */
    private ToolOutcome invokeRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, ToolCall call, Optional<ToolContinuationCall> continuation) {
        return invokeRegistered(registered,prepared,call,continuation,false,false);
    }

    private ToolOutcome invokeRegistered(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared,ToolCall call,Optional<ToolContinuationCall> continuation,boolean programChild,boolean workflowNode) {
        var dispatch = RegisteredExecutionCall.require(registered, prepared, call.context(), ExecutionStore.Kind.TOOL);
        if (dispatch.callKind() != PortalCallKind.TOOL_INVOKE)
            throw new IllegalArgumentException("registered_tool_call_kind_mismatch");
        return TenantContext.callWithTenant(dispatch.run().tenantId().value(),
                () -> invokeBound(dispatch, call, continuation, programChild, workflowNode));
    }

    /** 批次可能在新线程调用；租户只取已核验的原派发身份，不继承调用者或模型参数。 */
    private ToolOutcome invokeBound(ProviderDispatch dispatch, ToolCall call,
            Optional<ToolContinuationCall> continuation, boolean programChild, boolean workflowNode) {
        var reference = new ResultRef("platform-result:" + dispatch.attemptId());
        boolean dispatched = false;
        try {
            var binding = call.binding();
            if (!call.target().modelInvocable()) return failed(reference, "tool_model_invocation_not_allowed");
            if (call.target().confirmation() != com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER)
                return failed(reference, "tool_confirmation_required");
            if (!supports(binding, call)) return failed(reference, "platform_tool_not_mounted");
            if (!currentContract(binding, call)) return failed(reference, "platform_tool_contract_changed");
            var handler = handler(binding).orElseThrow();
            if(programChild&&handler instanceof PlatformInteractiveToolHandler)return failed(reference,"program_tool_requires_direct_call");
            if (continuation.isPresent() && !(handler instanceof PlatformInteractiveToolHandler))
                return failed(reference, "platform_tool_continuation_unsupported");
            Map<String, Object> arguments = json.readValue(call.arguments().canonicalJson(), OBJECT);
            rejectTrustedContextArguments(arguments);
            var original = call.context().callContext();
            Instant deadline = Instant.now().plus(call.target().timeout());
            if (original.control().deadlineAt().isBefore(deadline)) deadline = original.control().deadlineAt();
            var execution = new PlatformToolExecutionContext(new PortCallContext(original.runIdentity(), original.identity(),
                    new InvocationControl(deadline, original.control().cancellationSignal())),
                    dispatch.invocationId(), dispatch.attemptId(), programChild, workflowNode);
            var run = original.runIdentity();
            var trace = run.trace();
            var request = new ExternalInvocationRequest(dispatch.invocationId(), dispatch.attemptId(),
                    ExternalOperationType.TOOL_CALL, "platform", "tool:" + call.target().deploymentId(),
                    call.idempotencyKey().value(), new TraceContext(run.causationInputId().value(), trace.rootInputId().value(),
                            trace.traceId(), trace.spanId(), dispatch.requestRef().value(),
                            trace.causedByEventId().map(value -> value.value()).orElse(null)),
                    Map.of("deploymentId", call.target().deploymentId(), "revision", call.target().deploymentRevision(),
                            "toolHandle", call.target().handle().value()));
            dispatched = true;
            if (handler instanceof PlatformInteractiveToolHandler interactive) {
                var result = external.invoke(run.runRef().tenantId().value(), request,
                        () -> execute(() -> continuation.isEmpty() ? interactive.invoke(arguments, execution)
                                : interactive.resume(arguments, continuation.orElseThrow().inputResponses(),
                                        continuation.orElseThrow().requestState(), execution), execution));
                return interaction(result, handler instanceof PlatformMediaToolHandler, reference);
            }
            var result = external.invoke(run.runRef().tenantId().value(), request,
                    () -> handler instanceof com.skillforge.institution.execution.ProgramCodeToolHandler
                            ? executeProgram(() -> handler.execute(arguments, execution), execution)
                            : execute(() -> handler.execute(arguments, execution), execution));
            return completed(result, handler.isError(result) || handler instanceof PlatformMediaToolHandler && Boolean.TRUE.equals(result.get("isError")),
                    handler instanceof PlatformMediaToolHandler, reference);
        } catch (ExternalInvocationGuard.OutcomeUnknownException unknown) {
            boundaryFailure(dispatch,"platform_tool_outcome_unknown",unknown);
            return unknown(reference, call, "platform_tool_outcome_unknown");
        } catch (ExecutionSubmissionRejectedException rejected) {
            return failed(reference, ExecutionSubmissionRejectedException.REASON_CODE);
        } catch (ContextReferenceToolHandler.ArgumentsRejected rejected) {
            try { return completed(rejected.feedback(), true, false, reference); }
            catch (Exception failure) { return failed(reference, "context_reference_arguments_rejected"); }
        } catch (Exception failure) {
            boundaryFailure(dispatch,dispatched?"platform_tool_result_boundary_unknown":"tool_pre_dispatch_rejected",failure);
            return dispatched ? unknown(reference, call, "platform_tool_result_boundary_unknown")
                    : failed(reference, "tool_pre_dispatch_rejected");
        }
    }

    /** 只记录技术位置和封闭边界原因；异常正文、工具入参和业务结果不能进入诊断日志。 */
    private static void boundaryFailure(ProviderDispatch dispatch,String reason,Exception failure) {
        var source=java.util.Arrays.stream(failure.getStackTrace())
                .filter(frame->frame.getClassName().startsWith("com.skillforge.")&&frame.getLineNumber()>0)
                .limit(3).map(frame->frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" <- "));
        LOG.warn("平台工具结果边界失败 invocationId={} reason={} exceptionClass={} sourceLocation={}",
                dispatch.invocationId(),reason,failure.getClass().getName(),source.isEmpty()?"unknown":source);
    }

    private java.util.Optional<PlatformToolHandler> handler(ToolBinding binding){
        try{
            var matches=handlers.stream().filter(Objects::nonNull)
                    .filter(value->binding.providerRef().equals(value.toolName())).limit(2).toList();
            return matches.size()==1?java.util.Optional.of(matches.getFirst()):java.util.Optional.empty();
        }catch(RuntimeException rejected){return java.util.Optional.empty();}
    }

    /** 内建 Handler 只实现当前契约，冻结旧版本必须在产生外部效果前明确拒绝。 */
    private boolean currentContract(ToolBinding binding,ToolCall call)throws Exception{
        var entry=platformTools.entries().stream()
                .filter(value->value.name().equals(binding.toolName())).findFirst();
        if(entry.isEmpty())return true;
        var current=entry.orElseThrow();var target=call.target();
        return target.outputSchemaDeclared()
                && json.readTree(target.inputSchema().canonicalJson()).equals(json.valueToTree(current.parametersSchema()))
                && json.readTree(target.outputSchema().canonicalJson()).equals(json.valueToTree(current.outputSchema()))
                && target.effect() == current.effect()
                && target.idempotent()==current.idempotent()
                && target.confirmation() == current.confirmationPolicy()
                && target.timeout().equals(Duration.ofMillis(current.timeoutMs()))
                && target.modelInvocable()==current.modelInvocable()
                && target.sensitiveFields().equals(current.sensitiveFields());
    }

    /** 按Handler交互与媒体声明编码真实结果；等待不编码成成功正文。 */
    private ToolOutcome interaction(PlatformToolInteractionResult result,boolean media,ResultRef reference)throws Exception{
        if(result==null)throw new ExternalInvocationGuard.OutcomeUnknownException(
                "interactive platform tool returned no result",null);
        return switch(result){
            case PlatformToolInteractionResult.Completed value -> completed(value.output(),false,media,reference);
            case PlatformToolInteractionResult.Rejected value -> completed(value.output(),true,media,reference);
            case PlatformToolInteractionResult.InputRequired value ->
                    new ToolOutcome.InputRequired(value.inputRequests(),value.requestState());
        };
    }

    private ToolOutcome completed(Map<String,Object> result,boolean error,boolean media,ResultRef reference)throws Exception{
        if(result==null)throw new ExternalInvocationGuard.OutcomeUnknownException(
                "platform tool returned no result",null);
        var structured = new TreeMap<String,Object>(result);
        Object content = media ? structured.remove("content") : null;
        if (content != null && !(content instanceof List<?>)) throw new IllegalArgumentException("tool_media_content_invalid");
        var envelope = new CompletedToolResult(content == null ? List.of() : (List<?>) content,
                error, "complete", structured);
        return new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                json.writeValueAsString(envelope)),reference,List.of());
    }

    /** 固定工具回执使用具体契约；structuredContent 保留工具自身的动态业务字段。 */
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    private record CompletedToolResult(List<?> content, boolean isError, String resultType,
            Map<String,Object> structuredContent) { }

    private static <T> T execute(Execution<T> execution,
            PlatformToolExecutionContext context)throws Exception{
        var budget = com.skillforge.runtime.api.port.ExecutionBudgetScope.current()
                .map(com.skillforge.runtime.api.port.ExecutionBudgetScope.Binding::capture);
        var task=new FutureTask<T>(() -> {
            String previousTenant = TenantContext.getTenantId();
            TenantContext.setTenantId(context.run().runRef().tenantId().value());
            try (var scope = budget.map(com.skillforge.runtime.api.port.ExecutionBudgetScope::open).orElse(null)) {
                return execution.call();
            } finally {
                if (previousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(previousTenant);
            }
        });
        Thread worker=Thread.startVirtualThread(task);
        try{
            while(true){
                if(cancelled(context)||!Instant.now().isBefore(context.deadlineAt())){
                    task.cancel(true);throw new ExternalInvocationGuard.OutcomeUnknownException(
                            "platform tool completion is unknown",null);
                }
                long remaining=Math.max(1,Duration.between(Instant.now(),context.deadlineAt()).toNanos());
                try{
                    T value=task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(20)),TimeUnit.NANOSECONDS);
                    if(value==null)throw new ExternalInvocationGuard.OutcomeUnknownException(
                            "platform tool returned no result",null);
                    return value;
                }
                catch(TimeoutException waiting){/* 复核取消和冻结 deadline。 */}
                catch(ExecutionException failed){throw unwrap(failed);}
            }
        }finally{if(!task.isDone())task.cancel(true);if(worker.isAlive())worker.interrupt();}
    }

    /** 真实run_code必须留在父平台槽中服务RPC；冻结deadline和当前取消信号仍前后核验。 */
    private static <T> T executeProgram(Execution<T> operation, PlatformToolExecutionContext context) throws Exception {
        if (cancelled(context) || !Instant.now().isBefore(context.deadlineAt()))
            throw new ExternalInvocationGuard.OutcomeUnknownException("program parent scope is closed", null);
        T value = operation.call();
        if (value == null || cancelled(context) || !Instant.now().isBefore(context.deadlineAt()))
            throw new ExternalInvocationGuard.OutcomeUnknownException("program parent completion is unknown", null);
        return value;
    }

    @FunctionalInterface
    private interface Execution<T>{T call()throws Exception;}

    private static Exception unwrap(ExecutionException failed) {
        Throwable cause = failed.getCause();
        if (cause instanceof Exception exception) {
            return exception;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return failed;
    }

    private static boolean cancelled(PlatformToolExecutionContext context){
        try{return context.cancellationSignal().isCancellationRequested();}
        catch(RuntimeException unavailable){return true;}
    }
    private static void rejectTrustedContextArguments(Map<String,Object> arguments){
        if(arguments.keySet().stream().anyMatch(TRUSTED_CONTEXT_FIELDS::contains))
            throw new IllegalArgumentException("trusted platform tool context must not come from arguments");
    }
    /** 新派发失败复用本次 Attempt 结果身份，不读取旧许可。 */
    private static ToolOutcome failed(ResultRef reference,String reason){return new ToolOutcome.Failed(
            new PortFailure(reason,RetryMode.NONE,reference));}
    /** 已派发结果未知保留真实身份与工具幂等策略，不自动重发。 */
    private static ToolOutcome unknown(ResultRef reference,ToolCall call,String reason){return new ToolOutcome.OutcomeUnknown(
            new PortFailure(reason,call.target().idempotent()?RetryMode.IDEMPOTENT_BY_STABLE_KEY:RetryMode.NONE,reference));}
}
