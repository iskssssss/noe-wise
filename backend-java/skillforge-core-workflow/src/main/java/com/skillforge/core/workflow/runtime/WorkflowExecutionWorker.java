package com.skillforge.core.workflow.runtime;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.core.workflow.execution.WorkflowModels;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import com.skillforge.runtime.adapter.jdbc.execution.*;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

/** 真实 Engine 的节点边界提交者；外部节点必须由新受控能力实现显式装配。 */
final class WorkflowExecutionWorker {
    private final JdbcExecutionLedgerPort ledger;
    private final JdbcWorkflowBindings bindings;
    private final JdbcWorkflowPolicy policy;
    private final java.util.function.Consumer<ExecutionRecord> registeredSources;
    private final JdbcWorkflowChildExecutions children;
    private final StateMachineEngine engine;
    private final WorkflowParser parser;
    private final SdkWorkflowPortal.ExternalNodes effects;
    private final BiPredicate<JdbcWorkflowBindings.Binding,TypedPayload> outputs;
    private final Clock clock;
    private final String workerId="sdk-workflow:"+UUID.randomUUID();

    WorkflowExecutionWorker(JdbcExecutionLedgerPort ledger,JdbcWorkflowBindings bindings,JdbcWorkflowPolicy policy,
            JdbcWorkflowChildExecutions children,
            StateMachineEngine engine,WorkflowParser parser,SdkWorkflowPortal.ExternalNodes effects,
            BiPredicate<JdbcWorkflowBindings.Binding,TypedPayload> outputs,Clock clock,java.util.function.Consumer<ExecutionRecord> registeredSources) {
        this.ledger=Objects.requireNonNull(ledger);this.bindings=Objects.requireNonNull(bindings);this.policy=Objects.requireNonNull(policy);
        this.registeredSources=Objects.requireNonNull(registeredSources);
        this.children=Objects.requireNonNull(children);
        this.engine=Objects.requireNonNull(engine);this.parser=Objects.requireNonNull(parser);this.effects=Objects.requireNonNull(effects);
        this.outputs=Objects.requireNonNull(outputs);this.clock=Objects.requireNonNull(clock);
    }
    ExecutionRecord execute(VerifiedIdentity identity,ExecutionRef ref,InvocationControl control) {
        return execute(identity,ref,control,null);
    }
    ExecutionRecord execute(VerifiedIdentity identity,ExecutionRef ref,InvocationControl control,SdkWorkflowPortal.RegisteredSource registered) {
        var current=read(identity,ref);
        if(!(current.cancelRequestId().isPresent()?policy.authorizeQuery(new WorkflowQuery(identity,ref))
                :policy.authorizeExecution(new WorkflowQuery(identity,ref))))throw denied();
        verifySource(current);
        if(terminal(current.state())||(current.state().isWaiting()&&current.cancelRequestId().isEmpty()))return current;
        var state=WorkflowCheckpointCodec.decode(current.checkpoint());
        var lease=ledger.acquireLease(new ExecutionLeaseRequest(identity,ref,workerId,state.target().timeout(),clock.instant()));
        if(lease instanceof ExecutionLeaseResult.Busy busy)return busy.execution();
        if(!(lease instanceof ExecutionLeaseResult.Acquired acquired))throw denied();
        return execute(identity,acquired.execution(),acquired.lease(),control,registered);
    }
    ExecutionRecord execute(VerifiedIdentity identity,ExecutionRecord starting,ExecutionLease lease,InvocationControl control) {
        return execute(identity,starting,lease,control,null);
    }
    ExecutionRecord execute(VerifiedIdentity identity,ExecutionRecord starting,ExecutionLease lease,InvocationControl control,SdkWorkflowPortal.RegisteredSource registered) {
        try {return executeLeased(identity,starting,lease,control,registered);}
        finally {ledger.release(lease);}
    }
    private ExecutionRecord executeLeased(VerifiedIdentity identity,ExecutionRecord starting,ExecutionLease lease,InvocationControl control,SdkWorkflowPortal.RegisteredSource registered) {
        verifySource(starting);
        var current=new AtomicReference<>(starting);
        var stored=WorkflowCheckpointCodec.decode(starting.checkpoint());
        var binding=bindings.find(starting.executionRef().tenantId(),stored.target()).orElseThrow(WorkflowExecutionWorker::denied);
        if(!starting.definitionVersion().equals(Long.toString(stored.target().instanceRevision()))
                ||!starting.executionUnitId().equals(stored.target().instanceId()))throw denied();
        if(!stored.snapshot().sessionRef().equals(starting.owner().owner().sessionRef()))throw denied();
        var nodeInputs=new SdkWorkflowPortal.NodeInputs(stored.target(),stored.snapshot(),stored.parameters(),
                stored.evidenceRefs(),binding.customPrompt(),binding,Optional.ofNullable(registered));
        check(identity,current,lease,control);
        if(current.get().cancelRequestId().isPresent())return cancel(identity,current,lease,control);
        if(starting.state().isWaiting())return starting;
        if(starting.state()==WorkflowExecutionState.QUEUED) {
            current.set(applied(ledger.transition(ExecutionCommands.transition(starting,lease,WorkflowExecutionState.RUNNING,
                    starting.checkpoint(),Optional.empty(),Optional.empty(),Optional.empty(),event(starting,"started"),clock.instant()))));
        }
        if(current.get().state()!=WorkflowExecutionState.RUNNING)throw denied();
        com.skillforge.core.workflow.execution.WorkflowModels.Workflow workflow;
        try {
            com.skillforge.core.workflow.execution.WorkflowDefinitionContract.validate(WorkflowCheckpointCodec.object(binding.definitionJson()));
            workflow=parser.parse(binding.workflowKey(),WorkflowCheckpointCodec.object(binding.definitionJson()));
        } catch (com.skillforge.core.workflow.execution.WorkflowDefinitionContract.HumanResponseDestinationRejected rejected) {
            // 已冻结定义的确定配置错误，尚未执行节点；不能作为 UNKNOWN 反复恢复。
            var prior=current.get();
            return applied(ledger.transition(ExecutionCommands.transition(prior,lease,WorkflowExecutionState.FAILED,
                    prior.checkpoint(),Optional.empty(),Optional.of(result(prior)),
                    Optional.of("workflow_result_destination_rejected"),event(prior,"configuration-failed"),clock.instant())));
        }
        var parameters=WorkflowCheckpointCodec.object(stored.parameters());
        var checkpoint=stored.engine().orElse(null);
        Resume resume=null;
        Map<String,Object> response=null;
        if(checkpoint!=null) {
            if(checkpoint.boundary()!=Boundary.PROGRESS&&checkpoint.boundary()!=Boundary.WAITING)throw denied();
            boolean next=checkpoint.boundary()==Boundary.PROGRESS;
            resume=new Resume(next?checkpoint.nextNodeId():checkpoint.nodeId(),
                    next || checkpoint.waitReason().filter(reason -> reason != NodeOutcome.WaitReason.HUMAN_INPUT).isPresent(),
                    checkpoint.state(),checkpoint.extracted(),checkpoint.messages(),checkpoint.cursor());
            if(!next&&checkpoint.waitReason().filter(reason->reason==NodeOutcome.WaitReason.HUMAN_INPUT).isPresent())
                response=stored.response();
        }
        String input=parameters.get("user_input") instanceof String text?text:"";
        var request=new Request(workflow,WorkflowCheckpointCodec.object(binding.configJson()),input,
                starting.owner().owner().sessionRef().sessionId(),response,resume,stored.target().instanceId(),
                starting.executionRef().tenantId().value(),List.of(),starting.executionRef().executionId(),
                WorkflowNodeSource.requesterVariables(starting.owner()),new StateMachineEngine.InitialState(parameterState(parameters),Map.of()));
        var executingNode=new AtomicReference<NodeExecutionContext>();
        var nodeResult=new AtomicReference<NodeOutcome>();
        try {
            engine.runControlled(request,new Hooks() {
                @Override public void beforeNode(String nodeId) {
                    check(identity,current,lease,control);
                    if(current.get().cancelRequestId().isPresent()) {cancel(identity,current,lease,control);throw new Cancelled();}
                }
                @Override public NodeOutcome executeExternalNode(NodeExecutionContext node) {
                    check(identity,current,lease,control);
                    if(current.get().cancelRequestId().isPresent()) {cancel(identity,current,lease,control);throw new Cancelled();}
                    executingNode.set(node);
                    var pending=WorkflowCheckpointCodec.decode(current.get().checkpoint());
                    var outcome="async_task".equals(node.node().type())?WorkflowAsyncTasks.execute(node,pending)
                            :effects.execute(identity,lease,current.get(),control,nodeInputs,node);
                    nodeResult.set(outcome);
                    return outcome;
                }
                @Override public void checkpoint(Checkpoint boundary) {
                    check(identity,current,lease,control);
                    if(current.get().cancelRequestId().isPresent()) {cancel(identity,current,lease,control);throw new Cancelled();}
                    var prior=current.get();var state=stored.checkpoint(boundary);
                    if(boundary.waitReason().filter(reason->reason==NodeOutcome.WaitReason.TOOL_INPUT).isPresent()) {
                        var outcome=Objects.requireNonNull(nodeResult.get());
                        var wait=outcome.toolInputWait().orElseThrow();
                        var node=Objects.requireNonNull(executingNode.get());
                        if(!wait.nodeRun().tenantId().equals(prior.executionRef().tenantId())||!node.node().id().equals(boundary.nodeId()))throw denied();
                        var key=new WorkflowNodeCallIdentity(prior.owner(),prior.executionRef(),node.node().id(),
                                node.callIdentity().visit(),node.callIdentity().call());
                        state=state.toolWait(new WorkflowCheckpointCodec.ToolWait(key.executionRef(),key.nodeId(),key.visit(),key.call(),wait.nodeRun(),wait.action(),
                                wait.generation(),wait.sourceResult()));
                    }
                    if(boundary.boundary()==Boundary.WAITING && executingNode.get()!=null
                            && "async_task".equals(executingNode.get().node().type())) {
                        var previous=WorkflowCheckpointCodec.decode(prior.checkpoint()).asyncWait();
                        var node=executingNode.get();
                        var wait=previous.filter(value->value.nodeId().equals(node.node().id())
                                &&value.visit()==node.callIdentity().visit()&&value.call()==node.callIdentity().call())
                                .orElseGet(()->WorkflowAsyncTasks.waiting(node,clock.instant()));
                        state=state.asyncWait(wait);
                    }
                    var payload=WorkflowCheckpointCodec.encode(state);
                    if(boundary.boundary()==Boundary.PROGRESS) {
                        current.set(applied(ledger.progress(ExecutionCommands.progress(prior,lease,payload,event(prior,"step"),clock.instant()))));return;
                    }
                    WorkflowExecutionState target;Optional<ExecutionWait> waiting=Optional.empty();Optional<String> reason=Optional.empty();
                    Optional<ResultRef> result=Optional.empty();
                    if(boundary.boundary()==Boundary.WAITING) {
                        target=boundary.waitReason().orElseThrow()==NodeOutcome.WaitReason.HUMAN_INPUT
                                ?WorkflowExecutionState.WAITING_HITL:WorkflowExecutionState.WAITING_EXTERNAL;
                        String key="workflow-wait:"+prior.executionRef().executionId()+":"+(prior.executionRevision()+1);
                        waiting=Optional.of(new ExecutionWait(key,key));
                        result=Optional.of(new ResultRef(key));
                    } else if(boundary.boundary()==Boundary.COMPLETED) {
                        boolean valid=outputs.test(binding,WorkflowCheckpointCodec.output(boundary,
                                WorkflowCheckpointCodec.exportFields(binding.descriptorJson())));
                        target=valid?WorkflowExecutionState.COMPLETED:WorkflowExecutionState.FAILED;
                        if(!valid)reason=Optional.of("workflow_output_schema_rejected");
                        result=Optional.of(result(prior));
                    } else {
                        target=WorkflowExecutionState.LOOP_TERMINATED;result=Optional.of(result(prior));
                        reason=Optional.of(loopTerminationReason(boundary.loopLimit().orElseThrow()).name());
                    }
                    current.set(applied(ledger.transition(ExecutionCommands.transition(prior,lease,target,payload,waiting,result,reason,event(prior,target.name()),clock.instant()))));
                }
            });
        } catch(com.skillforge.core.workflow.execution.node.WorkflowNodeFailure failure) {
            check(identity,current,lease,control);
            if(current.get().cancelRequestId().isPresent())cancel(identity,current,lease,control);
            else {
                var prior=current.get();
                var node=executingNode.get();
                var failedCheckpoint=prior.checkpoint();
                if(node!=null) {
                    var frozen=WorkflowCheckpointCodec.decode(prior.checkpoint());
                    var cursor=frozen.engine().orElseThrow().cursor();
                    failedCheckpoint=WorkflowCheckpointCodec.encode(frozen.checkpoint(new Checkpoint(Boundary.PROGRESS,
                            node.node().id(),node.node().id(),node.state(),node.extracted(),node.messages(),null,null,
                            Optional.empty(),Optional.empty(),cursor)));
                }
                current.set(applied(ledger.transition(ExecutionCommands.transition(prior,lease,WorkflowExecutionState.FAILED,
                        failedCheckpoint,Optional.empty(),Optional.of(result(prior)),Optional.of(failure.reason()),
                        event(prior,"node-failed"),clock.instant()))));
            }
        } catch(Cancelled committed) { /* 仅已提交取消终态的本地控制流。 */ }
        return current.get();
    }
    private void check(VerifiedIdentity identity,AtomicReference<ExecutionRecord> current,ExecutionLease lease,InvocationControl control) {
        if(control.cancellationSignal().isCancellationRequested()||!clock.instant().isBefore(control.deadlineAt()))throw denied();
        var actual=current.get();
        if(actual.lease().isEmpty()||!actual.lease().orElseThrow().equals(lease.fence())
                ||!clock.instant().isBefore(lease.fence().leaseUntil()))throw denied();
        // 正常Engine推进消费上个提交返回值；节点发送核当前IAM，下一checkpoint仍由lease/CAS拒绝并发写入。
    }
    private ExecutionRecord cancel(VerifiedIdentity identity,AtomicReference<ExecutionRecord> current,ExecutionLease lease,InvocationControl control) {
        var prior=current.get();
        if(!cancelChildren(identity,prior,control))return prior;
        if(!effects.cancel(identity,lease,prior,control))return prior;
        var cancelled=applied(ledger.transition(ExecutionCommands.transition(prior,lease,WorkflowExecutionState.CANCELLED,
                prior.checkpoint(),Optional.empty(),Optional.of(result(prior)),Optional.empty(),event(prior,"cancelled"),clock.instant())));
        current.set(cancelled);return cancelled;
    }

    /** 先向全部活动子执行提交取消意图，再等待它们各自停止外部调用；父执行不能抢先终态。 */
    private boolean cancelChildren(VerifiedIdentity identity,ExecutionRecord parent,InvocationControl control) {
        var state=WorkflowCheckpointCodec.decode(parent.checkpoint());
        if(state.engine().isEmpty())return true;
        var cursor=state.engine().orElseThrow().cursor();
        if(cursor.activeNode()==null)return true;
        var binding=bindings.find(parent.executionRef().tenantId(),state.target()).orElseThrow(WorkflowExecutionWorker::denied);
        var workflow=parser.parse(binding.workflowKey(),WorkflowCheckpointCodec.object(binding.definitionJson()));
        var node=workflow.nodes().stream().filter(value->value.id().equals(cursor.activeNode())).findFirst().orElseThrow();
        if(!Set.of("parallel","subgraph").contains(node.type()))return true;
        var key=new WorkflowNodeCallIdentity(parent.owner(),parent.executionRef(),node.id(),
                cursor.visits().get(node.id()),1);
        var preparation=children.find(identity,key);
        if(preparation.isEmpty())return true;
        boolean converged=true;
        for(var source:preparation.orElseThrow().children()) {
            var child=read(identity,source.command().executionRef());
            verifySource(child);
            if(terminal(child.state()))continue;
            if(child.cancelRequestId().isEmpty()) {
                String id="workflow-parent-cancel:"+child.executionRef().executionId();
                ledger.requestCancel(ExecutionCommands.cancel(child,identity,new InputId(id),new EventId(id),
                        parent.cancelRequestedAt().orElseThrow()));
                child=read(identity,child.executionRef());
            }
            if(!terminal(child.state())&&child.cancelRequestId().isEmpty())converged=false;
        }
        for(var source:preparation.orElseThrow().children()) {
            var child=read(identity,source.command().executionRef());
            if(terminal(child.state()))continue;
            if(child.cancelRequestId().isEmpty()) {converged=false;continue;}
            var updated=execute(identity,child.executionRef(),control);
            if(!terminal(updated.state()))converged=false;
        }
        return converged;
    }
    private ExecutionRecord read(VerifiedIdentity identity,ExecutionRef ref) {return ledger.find(new ExecutionLookup(identity,ref)).orElseThrow(WorkflowExecutionWorker::denied);}
    /** 白名单业务值进入 ctx_*，不覆盖引擎保留状态键。 */
    static Map<String,Object> parameterState(Map<String,Object> parameters) {
        var state=new LinkedHashMap<String,Object>();
        parameters.forEach((key,value)->state.put("ctx_"+key,value));
        return state;
    }
    void verifySource(ExecutionRecord current) {
        if(current.causativeActionRef().isEmpty()) {
            var child=children.source(current);
            if(child.isEmpty())throw denied();
            var initial=WorkflowCheckpointCodec.decode(child.orElseThrow().child().command().initialCheckpoint());
            var stored=WorkflowCheckpointCodec.decode(current.checkpoint());
            if(!initial.target().equals(stored.target())||!initial.snapshot().equals(stored.snapshot())
                    ||!initial.parameters().equals(stored.parameters())||!initial.evidenceRefs().equals(stored.evidenceRefs())
                    ||!current.owner().equals(child.orElseThrow().preparation().parent().owner())
                    ||initial.engine().isEmpty()||!initial.response().isEmpty())throw denied();
            return;
        }
        registeredSources.accept(current);
    }
    static ExecutionRecord applied(ExecutionWriteResult result) {
        if(result instanceof ExecutionWriteResult.Applied value)return value.execution();
        if(result instanceof ExecutionWriteResult.Replayed value)return value.execution();throw denied();
    }
    static boolean terminal(WorkflowExecutionState state) {return Set.of(WorkflowExecutionState.COMPLETED,WorkflowExecutionState.FAILED,WorkflowExecutionState.LOOP_TERMINATED,WorkflowExecutionState.CANCELLED).contains(state);}
    static LoopTerminationReason loopTerminationReason(LoopLimit limit) {
        return switch (limit) {
            case NODE_REVISIT -> LoopTerminationReason.WORKFLOW_NODE_REVISIT;
            case MAX_STEPS -> LoopTerminationReason.WORKFLOW_MAX_STEPS_REACHED;
        };
    }
    static EventId event(ExecutionRecord record,String kind) {return new EventId("workflow:"+record.executionRef().executionId()+":"+(record.executionRevision()+1)+":"+kind);}
    static ResultRef result(ExecutionRecord record) {return new ResultRef("workflow-result:"+record.executionRef().executionId()+":"+(record.executionRevision()+1));}
    private static IllegalStateException denied() {return new IllegalStateException("workflow_execution_not_permitted");}
    private static final class Cancelled extends RuntimeException {private static final long serialVersionUID=1L;}
}
