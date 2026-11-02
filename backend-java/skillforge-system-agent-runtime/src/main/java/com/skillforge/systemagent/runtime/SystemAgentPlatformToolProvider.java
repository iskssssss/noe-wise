package com.skillforge.systemagent.runtime;

import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.InvocationControl;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolContinuationCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.RegisteredExecutionCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;

/** 平台系统智能体独立宿主的共享 Platform Tool Provider。 */
final class SystemAgentPlatformToolProvider {
    private static final TypeReference<Map<String,Object>> OBJECT=new TypeReference<>(){};
    private static final Set<String> TRUSTED_CONTEXT_FIELDS=Set.of(
            "tenant", "tenant_id", "tenantId", "end_user_id", "endUserId",
            "user", "user_id", "userId", "subject", "subject_id", "subjectId",
            "principal", "principal_ref", "principalRef", "session", "session_id", "sessionId",
            "session_ref", "sessionRef", "run", "run_id", "runId", "run_ref", "runRef",
            "input", "input_id", "inputId", "invocation", "invocation_id", "invocationId",
            "attempt", "attempt_id", "attemptId", "deadline", "deadline_at", "deadlineAt",
            "cancellation", "cancellation_signal", "cancellationSignal",
            "permit", "action_permit", "actionPermit");
    private final ObjectProvider<PlatformToolHandler> handlers;

    private final ExternalInvocationGuard external;
    private final ObjectMapper json;

    SystemAgentPlatformToolProvider(ObjectProvider<PlatformToolHandler> handlers,
            ExternalInvocationGuard external){
        this.handlers=Objects.requireNonNull(handlers);
        this.external=Objects.requireNonNull(external);
        this.json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public ToolBinding.ProviderType providerType(){return ToolBinding.ProviderType.PLATFORM;}

    public boolean supports(ToolBinding binding,ToolCall call){
        if(!binding.equals(call.binding())||binding.providerType()!=providerType()
                ||!binding.providerRef().equals(binding.toolName())
                ||!call.target().providerRequirements().equals(Set.of("platform_handler:"+binding.toolName())))return false;
        return handler(binding).isPresent();
    }

    public ToolOutcome invokeRegistered(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared, ToolCall call, java.util.function.BooleanSupplier authorize){
        var binding = call.binding();
        RegisteredExecutionCall.require(registered, prepared, call.context(), ExecutionStore.Kind.TOOL);
        boolean dispatched=false;
        try{
            if(!binding.equals(call.binding()))return failed(call,"tool_binding_changed");
            if(!supports(binding,call))return failed(call,"platform_tool_not_mounted");

            var handler=handler(binding).orElseThrow();
            Map<String,Object> arguments=json.readValue(call.arguments().canonicalJson(),OBJECT);
            rejectTrustedContextArguments(arguments);

            if(!authorize.getAsBoolean())return failed(call,"tool_policy_denied");
            var fact=call.context().dispatch();var run=call.context().callContext().runIdentity();
            var trace=run.trace();
            Instant deadline=Instant.now().plus(call.target().timeout());
            if(call.context().callContext().control().deadlineAt().isBefore(deadline))
                deadline=call.context().callContext().control().deadlineAt();
            var executionContext=new PlatformToolExecutionContext(
                    new PortCallContext(run, call.context().callContext().identity(),
                            new InvocationControl(deadline, call.context().callContext().control().cancellationSignal())),
                    fact.invocationId(), fact.attemptId());
            var request=new ExternalInvocationRequest(fact.invocationId(),fact.attemptId(),
                    ExternalOperationType.TOOL_CALL,"platform","tool:"+call.target().deploymentId(),
                    call.idempotencyKey().value(),new TraceContext(run.causationInputId().value(),
                    trace.rootInputId().value(),trace.traceId(),trace.spanId(),fact.requestRef().value(),
                    trace.causedByEventId().map(value->value.value()).orElse(null)),
                    Map.of("deploymentId",call.target().deploymentId(),"revision",call.target().deploymentRevision(),
                            "toolHandle",call.target().handle().value()));
            dispatched=true;
            Map<String,Object> result=external.invoke(run.runRef().tenantId().value(),request,
                    ()->{
                        var value=execute(handler,arguments,executionContext);
                        if(value==null)throw new ExternalInvocationGuard.OutcomeUnknownException(
                                "platform tool returned no result",null);
                        return value;
                    });
            var envelope=new TreeMap<String,Object>();envelope.put("content",List.of());
            envelope.put("resultType","complete");envelope.put("structuredContent",result);
            return new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                    json.writeValueAsString(envelope)),resultRef(call),List.of());
        }catch(ExternalInvocationGuard.OutcomeUnknownException unknown){
            return unknown(call,"platform_tool_outcome_unknown");
        }catch(Exception rejected){
            return dispatched?unknown(call,"platform_tool_result_boundary_unknown"):
                    failed(call,"tool_pre_dispatch_rejected");
        }
    }

    public ToolOutcome resume(ToolBinding binding,ToolContinuationCall call){
        if(!binding.equals(call.binding()))throw new IllegalArgumentException("tool_binding_changed");
        var base=new ToolCall(call.context(),call.binding(),call.target(),call.arguments(),call.idempotencyKey());
        return failed(base,"platform_tool_continuation_unsupported");
    }

    private java.util.Optional<PlatformToolHandler> handler(ToolBinding binding){
        try{
            var matches=handlers.stream().filter(Objects::nonNull)
                    .filter(value->binding.providerRef().equals(value.toolName())).limit(2).toList();
            return matches.size()==1?java.util.Optional.of(matches.getFirst()):java.util.Optional.empty();
        }catch(RuntimeException rejected){return java.util.Optional.empty();}
    }

    private static Map<String,Object> execute(PlatformToolHandler handler,Map<String,Object> arguments,
            PlatformToolExecutionContext context)throws Exception{
        var task=new FutureTask<Map<String,Object>>(
                ()->handler.execute(arguments,context));
        Thread worker=Thread.ofPlatform().daemon(true).name("system-agent-platform-tool").start(task);
        try{
            while(true){
                if(cancelled(context)||!Instant.now().isBefore(context.deadlineAt())){
                    task.cancel(true);throw new ExternalInvocationGuard.OutcomeUnknownException(
                            "platform tool completion is unknown",null);
                }
                long remaining=Math.max(1,Duration.between(Instant.now(),context.deadlineAt()).toNanos());
                try{return task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(20)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException waiting){/* 复核取消和冻结 deadline。 */}
                catch(ExecutionException failed){throw unwrap(failed);}
            }
        }finally{if(!task.isDone())task.cancel(true);if(worker.isAlive())worker.interrupt();}
    }

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
    private static ResultRef resultRef(ToolCall call){return new ResultRef("platform-result:"+
            call.context().dispatch().invocationId());}
    private static ToolOutcome failed(ToolCall call,String reason){return new ToolOutcome.Failed(
            new PortFailure(reason,RetryMode.NONE,resultRef(call)));}
    private static ToolOutcome unknown(ToolCall call,String reason){return new ToolOutcome.OutcomeUnknown(
            new PortFailure(reason,call.target().idempotent()?RetryMode.IDEMPOTENT_BY_STABLE_KEY:RetryMode.NONE,
                    resultRef(call)));}
}
