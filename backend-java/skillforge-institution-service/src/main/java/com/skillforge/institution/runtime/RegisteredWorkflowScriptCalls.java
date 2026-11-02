package com.skillforge.institution.runtime;

import com.skillforge.runtime.session.RegisteredExecutionCall;

import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 真实脚本任务适配器只接受新Node Registered；唯一submit仍由原任务ledger拥有。 */
public final class RegisteredWorkflowScriptCalls {
    private final JdbcExecutionLedgerPort executions;
    private final JdbcWorkflowBindings bindings;
    private final WorkflowParser parser;
    private final AgentRuntimeWorkflowScripts scripts;
    private final WorkflowScriptNodeCodec scriptCodec;
    private final RegisteredExecutionProviderCalls providers;
    public RegisteredWorkflowScriptCalls(JdbcExecutionLedgerPort executions,JdbcWorkflowBindings bindings,WorkflowParser parser,
            AgentRuntimeWorkflowScripts scripts,WorkflowScriptNodeCodec scriptCodec,RegisteredExecutionProviderCalls providers) {
        this.executions=executions;this.bindings=bindings;this.parser=parser;this.scripts=scripts;this.scriptCodec=scriptCodec;this.providers=providers;
    }
    public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,
            WorkflowNodeRequests.Frozen frozen,CancellationSignal signal) {
        var request=scriptCodec.read(prepared.request().content());var run=registered.run();
        var control=new InvocationControl(request.deadlineAt(),signal);
        var dispatch=new ProviderDispatch(run.identity().runRef(),prepared.action(),prepared.action().actionId(),registered.attempt().id(),PortalCallKind.TOOL_INVOKE,
                prepared.request().reference(),prepared.capability(),prepared.fingerprint(),registered.attempt().fence());
        RegisteredExecutionCall.require(registered,prepared,ProviderCallContext.dispatched(new PortCallContext(run.identity(),registered.actor(),control),dispatch),ExecutionStore.Kind.TOOL);
        ExecutionLeaseResult.Acquired acquired=null;
        while(acquired==null) {
            if(signal.isCancellationRequested()||!Instant.now().isBefore(request.deadlineAt()))throw new IllegalStateException("workflow_script_source_lease_unavailable");
            var claim=executions.acquireLease(new ExecutionLeaseRequest(registered.actor(),frozen.node().executionRef(),"workflow-node-script:"+UUID.randomUUID(),
                    Duration.between(Instant.now(),request.deadlineAt()).plusSeconds(5),Instant.now()));
            if(claim instanceof ExecutionLeaseResult.Acquired value)acquired=value;
            else if(claim instanceof ExecutionLeaseResult.Rejected)throw new IllegalArgumentException("workflow_script_execution_rejected");
            else try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("workflow_script_lease_interrupted",interrupted);}
        }
        try {
            var current=acquired.execution();var target=WorkflowNodeSource.target(current.checkpoint());
            var binding=bindings.find(current.executionRef().tenantId(),target).orElseThrow();
            var source=WorkflowNodeSource.rebuild(current,frozen.node(),binding,parser);
            var original=scripts.find(registered.actor(),frozen.node()).orElseThrow();scriptCodec.require(prepared.request().content(),original.preparation());
            scripts.requireFrozenSource(current,source.inputs(),original.preparation(),source.node());
            JdbcWorkflowScriptCalls.Entry entry=original;
            while(entry.state()==JdbcWorkflowScriptCalls.State.PREPARED||entry.state()==JdbcWorkflowScriptCalls.State.DISPATCHED) {
                try{scripts.execute(registered.actor(),acquired.lease(),current,control,source.inputs(),source.node());}
                catch(WorkflowNodeFailure localOutput){
                    entry=scripts.find(registered.actor(),frozen.node()).orElseThrow();
                    if(entry.state()==JdbcWorkflowScriptCalls.State.PREPARED) {
                        var failed=new ToolOutcome.Failed(new PortFailure(localOutput.reason(),RetryMode.NONE,new ResultRef("workflow-script-rejection:"+request.taskId())));
                        return result(registered,prepared,request.taskId(),failed);
                    }
                }
                entry=scripts.find(registered.actor(),frozen.node()).orElseThrow();
                if(entry.state()!=JdbcWorkflowScriptCalls.State.PREPARED&&entry.state()!=JdbcWorkflowScriptCalls.State.DISPATCHED)break;
                if(signal.isCancellationRequested()||!Instant.now().isBefore(request.deadlineAt()))
                    return result(registered,prepared,request.taskId(),new ToolOutcome.OutcomeUnknown(new PortFailure("workflow_script_result_unresolved",RetryMode.RESULT_QUERYABLE,new ResultRef("workflow-script-status:"+request.taskId()))));
            }
            com.skillforge.runtime.api.TypedPayload actual;
            if(entry.state()==JdbcWorkflowScriptCalls.State.COMPLETED)actual=scriptCodec.status(scripts.status(entry));
            else {
                try{actual=scriptCodec.status(scripts.status(entry));}
                catch(IllegalArgumentException rejected){actual=scriptCodec.rejection(entry.result().orElseThrow());}
            }
            return result(registered,prepared,request.taskId(),new ToolOutcome.Succeeded(actual,new ResultRef("workflow-script-status:"+request.taskId()),List.of()));
        } finally {executions.release(acquired.lease());}
    }
    /** 原Attempt恢复只有读取权；不构造Registered，不提交、重发或取消原沙箱任务。 */
    public Optional<ExecutionCoordinator.CapabilityResult> observe(ExecutionStore.Run run,ExecutionStore.Prepared prepared,
            ExecutionStore.Attempt original,WorkflowNodeRequests.Frozen frozen,VerifiedIdentity actor,CancellationSignal signal) {
        var request=scriptCodec.read(prepared.request().content());
        if(!WorkflowNodeSource.runIdentity(frozen.node()).equals(run.identity())||run.status()!=RunStatus.RUNNING||run.cancelled()
                ||run.phase()!=ExecutionStore.Phase.AWAITING_RESULT||run.lease().isEmpty()||run.currentBatch().isPresent()
                ||!run.currentAction().equals(Optional.of(prepared.action()))||prepared.kind()!=ExecutionStore.Kind.TOOL
                ||!prepared.target().equals(frozen.target())||!prepared.capability().equals(frozen.capability())||!prepared.request().equals(frozen.request())
                ||!prepared.fingerprint().equals(com.skillforge.runtime.session.AgentExecutionCodec.digest(prepared.request().content().schemaId()+"\0"+prepared.request().content().canonicalJson()))
                ||!original.run().equals(run.identity().runRef())||!original.action().equals(prepared.action())||original.fence()>run.fence()
                ||!original.request().equals(prepared.request().reference())||original.continuation().isPresent()||original.result().isPresent()
                ||original.uncertainty().isPresent()||original.adopted()||!actor.owner().equals(run.identity().owner())
                ||!actor.verificationRef().equals(run.actorVerificationRef())
                ||original.externalOperation().filter(value->!value.equals(request.taskId())).isPresent())
            throw new IllegalArgumentException("workflow_script_observation_source_rejected");
        if(signal.isCancellationRequested())return Optional.empty();
        var observationDeadline=Instant.now().plusSeconds(5);
        var claim=executions.acquireLease(new ExecutionLeaseRequest(actor,frozen.node().executionRef(),"workflow-node-script-observation:"+UUID.randomUUID(),
                Duration.ofSeconds(10),Instant.now()));
        if(!(claim instanceof ExecutionLeaseResult.Acquired acquired))return Optional.empty();
        try {
            var current=acquired.execution();
            var binding=bindings.find(current.executionRef().tenantId(),WorkflowNodeSource.target(current.checkpoint())).orElseThrow();
            var source=WorkflowNodeSource.rebuild(current,frozen.node(),binding,parser);
            var entry=scripts.find(actor,frozen.node()).orElseThrow();
            scriptCodec.require(prepared.request().content(),entry.preparation());
            if(!scriptCodec.reference(entry.preparation(),source.node()).equals(prepared.request().content()))
                throw new IllegalArgumentException("workflow_script_observation_contract_rejected");
            return scripts.queryOriginal(actor,acquired.lease(),current,new InvocationControl(observationDeadline,signal),source.inputs(),source.node())
                    .map(observed->result(original,prepared,request.taskId(),new ToolOutcome.Succeeded(actual(observed),new ResultRef("workflow-script-status:"+request.taskId()),List.of())));
        } finally {executions.release(acquired.lease());}
    }
    private com.skillforge.runtime.api.TypedPayload actual(JdbcWorkflowScriptCalls.Entry entry) {
        if(entry.state()==JdbcWorkflowScriptCalls.State.COMPLETED)return scriptCodec.status(scripts.status(entry));
        try{return scriptCodec.status(scripts.status(entry));}
        catch(IllegalArgumentException rejected){return scriptCodec.rejection(entry.result().orElseThrow());}
    }
    private ExecutionCoordinator.CapabilityResult result(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,String task,ToolOutcome actual) {
        return result(registered.attempt(),prepared,task,actual);
    }
    private ExecutionCoordinator.CapabilityResult result(ExecutionStore.Attempt attempt,ExecutionStore.Prepared prepared,String task,ToolOutcome actual) {
        var raw=providers.encodeToolResult(attempt,actual);
        Optional<ExecutionStore.Material> feedback=Optional.empty();
        try { feedback=scriptCodec.feedback(prepared.request().content(),actual).map(value->new ExecutionStore.Material(new ResultRef("execution-feedback:"+attempt.id()),value)); }
        catch(WorkflowScriptNodeCodec.OutputRejected rejected) { /* 原Status已保存；只有本地输出不可采纳。 */ }
        var kind=actual instanceof ToolOutcome.Succeeded?ExecutionStore.ResultKind.SUCCEEDED:actual instanceof ToolOutcome.Failed?ExecutionStore.ResultKind.FAILED:ExecutionStore.ResultKind.UNKNOWN;
        return new ExecutionCoordinator.CapabilityResult(kind,raw,feedback,Optional.of(task),ExecutionBudgetPort.Usage.unreported());
    }
}
