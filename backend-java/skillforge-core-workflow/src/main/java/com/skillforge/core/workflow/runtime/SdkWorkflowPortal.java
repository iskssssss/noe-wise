package com.skillforge.core.workflow.runtime;

import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import com.skillforge.runtime.adapter.jdbc.execution.*;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.*;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;

/** SDK Workflow Provider 直接运行真实 Engine；原调用入口和节点状态只有新持久账本一条写路径。 */
public final class SdkWorkflowPortal implements WorkflowPortal {
    /** null只用于无外部事务的正常Engine入口；借用来源校验必须传原Store SqlSession。 */
    @FunctionalInterface public interface SourceVerifier {void require(org.apache.ibatis.session.SqlSession session,ExecutionRecord execution);}
    /** 仅本轮父动作真实Store登记回执；只在内存传递，不写入Engine checkpoint。 */
    public record RegisteredSource(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        public RegisteredSource {
            Objects.requireNonNull(registered);Objects.requireNonNull(prepared);
            if(prepared.kind()!=ExecutionStore.Kind.WORKFLOW||!registered.action().reference().equals(prepared.action())
                    ||!registered.attempt().request().equals(prepared.request().reference()))throw new IllegalArgumentException("workflow_registered_source_mismatch");
        }
    }
    /** 由原已提交调用及不可变绑定提供；保留 SDK EvidenceRef，不转换或读取旧知识类型。 */
    public record NodeInputs(FrozenWorkflowTarget target, CapabilitySnapshotRef snapshot,
            TypedPayload parameters, List<EvidenceRef> evidenceRefs, Optional<String> customPrompt,
            JdbcWorkflowBindings.Binding binding,Optional<RegisteredSource> registered) {
        public NodeInputs {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(parameters, "parameters");
            evidenceRefs = List.copyOf(evidenceRefs);
            Objects.requireNonNull(customPrompt, "customPrompt");
            Objects.requireNonNull(binding,"binding");
            Objects.requireNonNull(registered,"registered");
            if(!binding.target().equals(target)||!binding.customPrompt().equals(customPrompt))
                throw new IllegalArgumentException("workflow_node_binding_mismatch");
            if (new HashSet<>(evidenceRefs).size() != evidenceRefs.size()
                    || evidenceRefs.stream().anyMatch(ref -> !ref.sessionRef().equals(snapshot.sessionRef()))) {
                throw new IllegalArgumentException("workflow_node_evidence_scope_mismatch");
            }
        }
        public NodeInputs(FrozenWorkflowTarget target,CapabilitySnapshotRef snapshot,TypedPayload parameters,List<EvidenceRef> evidenceRefs,
                Optional<String> customPrompt,JdbcWorkflowBindings.Binding binding) {
            this(target,snapshot,parameters,evidenceRefs,customPrompt,binding,Optional.empty());
        }
    }

    public static final String EXTERNAL_SIGNAL_SCHEMA = com.skillforge.runtime.session.spi.SessionWorkflowWakeups.SIGNAL_SCHEMA;
    public static final String ASYNC_RESPONSE_SCHEMA = com.skillforge.runtime.session.spi.SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA;



    @FunctionalInterface public interface ExternalNodes {
        NodeOutcome execute(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,
                InvocationControl control,NodeInputs inputs,NodeExecutionContext node);
        default void requireScriptSource(ExecutionRecord execution,NodeInputs inputs,JdbcWorkflowScriptCalls.Preparation preparation,
                NodeExecutionContext node) {
            throw new IllegalArgumentException("workflow_script_source_not_configured");
        }
        default Optional<TypedPayload> observeExternal(VerifiedIdentity identity,ExecutionLease lease,
                ExecutionRecord execution,InvocationControl control,NodeInputs inputs,NodeExecutionContext node) {
            return Optional.empty();
        }
        default void requireExternalSignal(VerifiedIdentity identity,ExecutionRecord execution,
                TypedPayload signal,NodeInputs inputs,NodeExecutionContext node) {
            throw new IllegalArgumentException("workflow_external_signal_not_configured");
        }
        default void requireExternalSignalBorrowed(org.apache.ibatis.session.SqlSession session,VerifiedIdentity identity,ExecutionRecord execution,
                TypedPayload signal,NodeInputs inputs,NodeExecutionContext node) {throw new IllegalArgumentException("workflow_borrowed_signal_not_configured");}
        default boolean cancel(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,InvocationControl control) {
            return true;
        }
    }
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SdkWorkflowPortal.class);
    private final JdbcExecutionLedgerPort ledger;
    private final JdbcWorkflowBindings bindings;
    private final ExternalNodes nodes;
    private final WorkflowParser parser;
    private final JdbcWorkflowPolicy policy;
    private final JdbcWorkflowChildExecutions children;
    private final WorkflowExecutionWorker worker;
    private final Predicate<WorkflowDispatch> parameters;
    private final Predicate<WorkflowResume> responses;
    private final Clock clock;
    private final ExecutionStore executionStore;
    private final SourceVerifier registeredSourceVerifier;

    /** 来源与schema guard为必需生产依赖；外部node必须在自身原Action/Execution权限闭包内执行。 */
    public SdkWorkflowPortal(DataSource source,JdbcWorkflowBindings bindings,JdbcWorkflowPolicy policy,
            StateMachineEngine engine,WorkflowParser parser,ExternalNodes nodes,
            Predicate<WorkflowDispatch> parameters,Predicate<WorkflowResume> responses,
            BiPredicate<JdbcWorkflowBindings.Binding,TypedPayload> outputs,Clock clock) {
        this(source,bindings,policy,engine,parser,nodes,parameters,responses,outputs,clock,null,
                (session,record)->{throw new IllegalArgumentException("workflow_registered_boundary_required");});
    }
    public SdkWorkflowPortal(DataSource source,JdbcWorkflowBindings bindings,JdbcWorkflowPolicy policy,
            StateMachineEngine engine,WorkflowParser parser,ExternalNodes nodes,Predicate<WorkflowDispatch> parameters,Predicate<WorkflowResume> responses,
            BiPredicate<JdbcWorkflowBindings.Binding,TypedPayload> outputs,Clock clock,ExecutionStore executionStore,
            SourceVerifier registeredSourceVerifier) {
        this.ledger=new JdbcExecutionLedgerPort(Objects.requireNonNull(source));this.policy=Objects.requireNonNull(policy);
        this.bindings=Objects.requireNonNull(bindings);
        this.parameters=Objects.requireNonNull(parameters);this.responses=Objects.requireNonNull(responses);this.clock=Objects.requireNonNull(clock);
        this.executionStore=executionStore;
        this.registeredSourceVerifier=Objects.requireNonNull(registeredSourceVerifier);
        Objects.requireNonNull(nodes);
        this.nodes=nodes;
        this.parser=Objects.requireNonNull(parser);
        this.children=new JdbcWorkflowChildExecutions(source,this::requireChildSource);
        this.worker=new WorkflowExecutionWorker(ledger,bindings,policy,children,engine,parser,nodes,outputs,clock,record->registeredSourceVerifier.require(null,record));
    }

    /** 新链只接受当前Registered发送权；实际Execution create仍由原业务账本原子提交。 */
    public WorkflowResult dispatchRegistered(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,WorkflowDispatch request) {
        requireRegistered(registered,prepared,request.context(),PortalCallKind.WORKFLOW_DISPATCH);
        if(!parameters.test(request))return rejected(request.executionRef(),"workflow_parameters_rejected");
        var command=ExecutionCommands.create(request.executionRef(),registered.run().identity(),request.target().instanceId(),
                Long.toString(request.target().instanceRevision()),ExecutionMode.FOREGROUND,Optional.of(prepared.action()),Optional.empty(),
                WorkflowCheckpointCodec.encode(WorkflowCheckpointCodec.initial(request)),prepared.fingerprint(),
                // CREATE包含完整事件签名；重建Portal必须复用原Registered派发时点，不能用新时钟改变同一命令。
                new EventId("workflow-queued:"+request.executionRef().executionId()),registered.run().updatedAt());
        var result=ledger.create(command);
        if(result instanceof ExecutionWriteResult.Rejected denied)return new WorkflowResult.Failed(request.executionRef(),OptionalLong.empty(),denied.failure());
        // 账本已核实完全相同的CREATE；原Execution推进后返回当前事实，不能重放Engine或覆盖原终态。
        if(result instanceof ExecutionWriteResult.Conflict conflict)return registeredReplay(registered,prepared,request,conflict.current());
        var current=WorkflowExecutionWorker.applied(result);
        if(result instanceof ExecutionWriteResult.Replayed)return registeredReplay(registered,prepared,request,current);
        try {
            return result(worker.execute(registered.actor(),request.executionRef(),request.context().callContext().control(),new RegisteredSource(registered,prepared)));
        } catch (WorkflowExecutionWorker.CancellationSignalled signalled) {
            var parent=executionStore.load(registered.run().identity().runRef()).orElse(null);
            if(parent==null||!parent.identity().equals(registered.run().identity())||!parent.cancelled()
                    ||parent.status()!=RunStatus.CANCELLED||parent.phase()!=ExecutionStore.Phase.FINISHED)throw signalled;
            // 只清理已提交父取消的原Execution；此时原worker租约已由finally释放。
            try {
                var input=new InputId("workflow-parent-cancel:"+parent.identity().runRef().runId()+":"+prepared.action().actionId());
                var cleanup=new InvocationControl(clock.instant().plusSeconds(5),()->Thread.currentThread().isInterrupted());
                if(cancelCommittedParent(registered.actor(),request.executionRef(),input,cleanup))
                    return result(read(registered.actor(),request.executionRef()));
            } catch (RuntimeException unavailable) {
                LOG.warn("Workflow原派发取消清理暂缓 execution={} failureType={}",request.executionRef().executionId(),unavailable.getClass().getSimpleName(),unavailable);
            }
            try {
                // 其他清理者可能仍持有租约；只在本次调用内有界观察，不重复提交取消。
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while(!Thread.currentThread().isInterrupted()&&System.nanoTime()-until<0) {
                    if(!policy.authorizeQuery(new WorkflowQuery(registered.actor(),request.executionRef())))break;
                    var observed=read(registered.actor(),request.executionRef());
                    if(!observed.owner().equals(parent.identity())||!observed.causativeActionRef().equals(Optional.of(prepared.action())))break;
                    requireInitialSource(observed,request.target(),request.snapshot(),request.parameters(),request.evidenceRefs());
                    if(WorkflowExecutionWorker.terminal(observed.state())&&!Thread.currentThread().isInterrupted())return result(observed);
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25));
                }
            } catch (RuntimeException unavailable) {
                LOG.warn("Workflow原派发结果核对暂缓 execution={} failureType={}",request.executionRef().executionId(),unavailable.getClass().getSimpleName(),unavailable);
                return unknown(request.executionRef());
            }
            LOG.info("Workflow原派发取消清理尚未收敛 execution={}",request.executionRef().executionId());
            return unknown(request.executionRef());
        }
    }

    /** 同一原CREATE的重复读取仍核当前写权限与精确来源；不获取执行租约、不再次驱动任何节点。 */
    private WorkflowResult registeredReplay(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,
            WorkflowDispatch request,ExecutionRecord current) {
        if(!current.executionRef().equals(request.executionRef())||!current.owner().equals(registered.run().identity())
                ||current.mode()!=ExecutionMode.FOREGROUND||!current.causativeActionRef().equals(Optional.of(prepared.action()))
                ||current.delegationRef().isPresent()
                ||!policy.authorizeExecution(new WorkflowQuery(registered.actor(),current.executionRef())))
            throw new IllegalArgumentException("workflow_registered_replay_rejected");
        requireInitialSource(current,request.target(),request.snapshot(),request.parameters(),request.evidenceRefs());
        worker.verifySource(current);
        return result(current);
    }

    /** 恢复是真实Execution CAS；不重发原dispatch，也不覆盖原挂起结果。 */
    public WorkflowResult resumeRegistered(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,WorkflowResume request) {
        requireRegistered(registered,prepared,request.context(),PortalCallKind.WORKFLOW_RESUME);
        if(!responses.test(request))return rejected(request.executionRef(),"workflow_response_rejected");
        var current=read(registered.actor(),request.executionRef());
        worker.verifySource(current);
        if(WorkflowExecutionWorker.terminal(current.state()))return result(current);
        if(!current.owner().equals(registered.run().identity())||current.executionRevision()!=request.expectedRevision()
                ||!current.waiting().equals(Optional.of(new ExecutionWait(request.suspendRef(),request.actionKey()))))return rejected(request.executionRef(),"workflow_resume_conflict");
        if(current.state()==WorkflowExecutionState.WAITING_EXTERNAL) {
            if(EXTERNAL_SIGNAL_SCHEMA.equals(request.response().schemaId()))requireExternalSignal(registered.actor(),current,request.response());
            else if(ASYNC_RESPONSE_SCHEMA.equals(request.response().schemaId()))requireAsyncCallback(current,request.response());
            else throw new IllegalArgumentException("workflow_external_response_schema_rejected");
        }
        var acquisition=ledger.acquireLease(new ExecutionLeaseRequest(registered.actor(),request.executionRef(),"sdk-workflow-resume:"+UUID.randomUUID(),request.target().timeout(),clock.instant()));
        if(!(acquisition instanceof ExecutionLeaseResult.Acquired acquired))return unknown(request.executionRef());
        try {
            var state=WorkflowCheckpointCodec.decode(acquired.execution().checkpoint()).response(WorkflowCheckpointCodec.object(request.response()));
            var command=ExecutionCommands.resume(acquired.execution(),acquired.lease(),new ExecutionWait(request.suspendRef(),request.actionKey()),WorkflowCheckpointCodec.encode(state),
                    new EventId("workflow-resumed:"+registered.attempt().id()),new EventId("workflow-restarted:"+registered.attempt().id()),clock.instant());
            var changed=ledger.resume(command);
            if(changed instanceof ExecutionWriteResult.Rejected denied)return new WorkflowResult.Failed(request.executionRef(),OptionalLong.empty(),denied.failure());
            if(changed instanceof ExecutionWriteResult.Conflict)return rejected(request.executionRef(),"workflow_resume_conflict");
            var actual=WorkflowExecutionWorker.applied(changed);
            return result(worker.execute(registered.actor(),actual,acquired.lease(),request.context().callContext().control(),new RegisteredSource(registered,prepared)));
        } finally {ledger.release(acquired.lease());}
    }

    public WorkflowControlResult cancelRegistered(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,WorkflowCancel request) {
        requireRegistered(registered,prepared,request.context(),PortalCallKind.WORKFLOW_CANCEL);
        var current=read(registered.actor(),request.executionRef());worker.verifySource(current);
        if(current.state()==WorkflowExecutionState.CANCELLED)return new WorkflowControlResult.Replayed(snapshot(current));
        if(current.executionRevision()!=request.expectedRevision())return new WorkflowControlResult.Conflict(snapshot(current),failure(request.executionRef(),"workflow_cancel_conflict",RetryMode.NONE));
        var command=ExecutionCommands.cancel(current,registered.actor(),new InputId("workflow-cancel:"+registered.attempt().id()),
                new EventId("workflow-cancel-request:"+registered.attempt().id()),clock.instant());
        var written=ledger.requestCancel(command);
        if(written instanceof ExecutionCancelResult.Conflict conflict)return new WorkflowControlResult.Conflict(snapshot(conflict.current()),failure(request.executionRef(),"workflow_cancel_conflict",RetryMode.NONE));
        if(written instanceof ExecutionCancelResult.Rejected denied)return new WorkflowControlResult.Rejected(denied.failure());
        if(written instanceof ExecutionCancelResult.NotFound missing)return new WorkflowControlResult.Rejected(missing.failure());
        var actual=worker.execute(registered.actor(),request.executionRef(),request.context().callContext().control(),new RegisteredSource(registered,prepared));
        return actual.state()==WorkflowExecutionState.CANCELLED?new WorkflowControlResult.Applied(snapshot(actual)):
                new WorkflowControlResult.OutcomeUnknown(failure(request.executionRef(),"workflow_cancel_pending",RetryMode.RESULT_QUERYABLE));
    }

    /** 只清理已提交父取消所拥有的原Execution；此入口不登记Provider Attempt或授予任何节点执行权。 */
    public boolean cancelCommittedParent(VerifiedIdentity actor,ExecutionRef reference,InputId cancelInput,InvocationControl control) {
        if(executionStore==null)throw new IllegalStateException("workflow_execution_store_required");
        var current=read(actor,reference);worker.verifySource(current);
        var parent=executionStore.load(current.owner().runRef()).orElseThrow(()->new IllegalArgumentException("workflow_cancel_parent_missing"));
        var actionRef=current.causativeActionRef().orElseThrow(()->new IllegalArgumentException("workflow_cancel_root_source_required"));
        var action=executionStore.action(actionRef).orElseThrow(()->new IllegalArgumentException("workflow_cancel_action_missing"));
        var request=executionStore.material(reference.tenantId(),action.request()).orElseThrow(()->new IllegalArgumentException("workflow_cancel_request_missing"));
        if(!parent.identity().equals(current.owner())||!parent.cancelled()||parent.status()!=RunStatus.CANCELLED||parent.phase()!=ExecutionStore.Phase.FINISHED
                ||!actor.owner().equals(parent.identity().owner())||action.kind()!=ExecutionStore.Kind.WORKFLOW||!action.run().equals(parent.identity().runRef())
                ||!action.fingerprint().equals(com.skillforge.runtime.session.AgentExecutionCodec.digest(request.content().schemaId()+"\0"+request.content().canonicalJson())))
            throw new IllegalArgumentException("workflow_cancel_committed_source_rejected");
        var stored=WorkflowCheckpointCodec.decode(current.checkpoint());
        String actionKey=current.waiting().map(ExecutionWait::actionKey).orElse("workflow-parent-cancel:"+reference.executionId());
        var resource=com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource.cancelResource(parent.identity(),stored.snapshot(),stored.target(),reference,
                actionKey,current.executionRevision(),"parent_cancelled",actionRef,request.reference(),action.fingerprint());
        if(!policy.authorizeCommittedCancel(actor,parent,resource))throw new SecurityException("workflow_parent_cancel_unauthorized");
        if(WorkflowExecutionWorker.terminal(current.state()))return true;
        if(current.cancelRequestId().isEmpty()) {
            var changed=ledger.requestCancel(ExecutionCommands.cancel(current,actor,cancelInput,new EventId(cancelInput.value()),clock.instant()));
            if(changed instanceof ExecutionCancelResult.Conflict)return false;
            if(changed instanceof ExecutionCancelResult.Rejected||changed instanceof ExecutionCancelResult.NotFound)throw new IllegalArgumentException("workflow_parent_cancel_rejected");
        }
        // 无Registered只允许已保存cancelRequest分支，Worker在任何Engine节点前先完成原任务清理。
        var observed=read(actor,reference);if(observed.cancelRequestId().isEmpty())return WorkflowExecutionWorker.terminal(observed.state());
        return WorkflowExecutionWorker.terminal(worker.execute(actor,reference,control).state());
    }

    private void requireRegistered(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared,ProviderCallContext context,PortalCallKind kind) {
        var source=new RegisteredSource(registered,prepared);var dispatch=context.dispatch();
        if(executionStore==null||!registered.run().identity().equals(context.callContext().runIdentity())||!registered.actor().equals(context.callContext().identity())
                ||dispatch.callKind()!=kind||!dispatch.action().equals(prepared.action())||!dispatch.attemptId().equals(registered.attempt().id())
                ||!dispatch.requestRef().equals(prepared.request().reference())||!dispatch.fingerprint().equals(prepared.fingerprint())
                ||!executionStore.authorizeSend(source.registered(),source.prepared()))throw new IllegalArgumentException("workflow_registered_dispatch_rejected");
    }
    /** Host从原typed Action请求读到的不可变字段与当前Engine checkpoint精确配对。 */
    public static void requireInitialSource(ExecutionRecord execution,FrozenWorkflowTarget target,CapabilitySnapshotRef snapshot,
            TypedPayload parameters,List<EvidenceRef> evidenceRefs) {
        var state=WorkflowCheckpointCodec.decode(execution.checkpoint());
        if(!state.target().equals(target)||!state.snapshot().equals(snapshot)||!state.parameters().equals(parameters)
                ||!state.evidenceRefs().equals(evidenceRefs)||!execution.executionUnitId().equals(target.instanceId())
                ||!execution.definitionVersion().equals(Long.toString(target.instanceRevision())))throw new IllegalArgumentException("workflow_initial_source_mismatch");
    }

    /** 子执行来源必须能由原父节点和冻结安装完整重建，不能用任意创建命令冒充。 */
    private void requireChildSource(VerifiedIdentity identity,ExecutionRecord parent,
            JdbcWorkflowChildExecutions.Preparation preparation) {
        if(!identity.owner().equals(parent.owner().owner())
                ||!policy.authorizeExecution(new WorkflowQuery(identity,parent.executionRef())))
            throw new IllegalArgumentException("workflow_child_source_rejected");
        var source=sourceNode(parent,preparation.parent(),preparation.definitionRevision(),
                preparation.installationRevision(),preparation.sourceCheckpoint());
        var expected=WorkflowChildPlans.prepare(parent,source.node(),resolvedBindings(source.inputs().binding()),
                target->bindings.find(parent.executionRef().tenantId(),target).orElseThrow(),parser);
        if(!expected.equals(preparation))throw new IllegalArgumentException("workflow_child_source_rejected");
    }

    /** 准备一次组合调用的完整子集合；已有调用只返回原 journal，绝不重新生成目标或参数。 */
    public JdbcWorkflowChildExecutions.Preparation prepareChildren(VerifiedIdentity identity,ExecutionLease lease,
            ExecutionRecord parent,NodeExecutionContext node) {
        var key=new WorkflowNodeCallIdentity(parent.owner(),parent.executionRef(),node.node().id(),
                node.callIdentity().visit(),node.callIdentity().call());
        var existing=children.find(identity,key);
        if(existing.isPresent())return existing.orElseThrow();
        var source=WorkflowCheckpointCodec.decode(parent.checkpoint());
        var frozen=sourceNode(parent,key,source.target().instanceRevision(),source.target().installationRevision(),parent.checkpoint());
        var plan=WorkflowChildPlans.prepare(parent,frozen.node(),resolvedBindings(frozen.inputs().binding()),
                target->bindings.find(parent.executionRef().tenantId(),target).orElseThrow(),parser);
        return children.prepare(identity,lease,parent.executionRevision(),plan);
    }

    private static WorkflowRuntimeBindings resolvedBindings(JdbcWorkflowBindings.Binding binding) {
        try {
            return BindingsJson.MAPPER.readValue(binding.resolvedBindingsJson(),WorkflowRuntimeBindings.class);
        } catch(java.io.IOException invalid) {
            throw new IllegalArgumentException("workflow_child_source_rejected",invalid);
        }
    }

    private static final class BindingsJson {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void requireScriptSource(org.apache.ibatis.session.SqlSession session,VerifiedIdentity identity,ExecutionRecord current,JdbcWorkflowScriptCalls.Preparation preparation) {
        if(!identity.owner().equals(current.owner().owner()))throw new IllegalArgumentException("workflow_script_owner_rejected");
        var stored=WorkflowCheckpointCodec.decode(current.checkpoint());
        var binding=JdbcWorkflowBindings.findBorrowed(session,stored.target()).orElseThrow();
        var source=WorkflowNodeSource.rebuild(current,preparation.identity(),binding,parser);
        nodes.requireScriptSource(current,source.inputs(),preparation,source.node());
    }

    private SourceNode sourceNode(ExecutionRecord current,WorkflowNodeCallIdentity key,long definitionRevision,
            long installationRevision,TypedPayload sourceCheckpoint) {
        worker.verifySource(current);
        var stored=WorkflowCheckpointCodec.decode(current.checkpoint());
        var engine=stored.engine().orElseThrow();var cursor=engine.cursor();
        if(!key.owner().equals(current.owner())||!key.executionRef().equals(current.executionRef())
                ||!key.nodeId().equals(cursor.activeNode())||!Integer.valueOf(key.visit()).equals(cursor.visits().get(key.nodeId()))
                ||key.call()!=1||definitionRevision!=stored.target().instanceRevision()
                ||installationRevision!=stored.target().installationRevision())
            throw new IllegalArgumentException("workflow_node_source_mismatch");
        var binding=bindings.find(current.executionRef().tenantId(),stored.target()).orElseThrow();
        var original=WorkflowCheckpointCodec.decode(sourceCheckpoint);
        if(!original.target().equals(stored.target())||!original.snapshot().equals(stored.snapshot())
                ||!original.parameters().equals(stored.parameters()))throw new IllegalArgumentException("workflow_node_original_source_mismatch");
        var checkpoint=original.engine().orElseThrow();
        var workflow=parser.parse(binding.workflowKey(),WorkflowCheckpointCodec.object(binding.definitionJson()));
        var node=workflow.nodes().stream().filter(value->value.id().equals(key.nodeId())).findFirst().orElseThrow();
        var parameters=WorkflowCheckpointCodec.object(original.parameters());
        var businessState=new LinkedHashMap<String,Object>(checkpoint.state());
        var visits=StateMachineEngine.restoreActionVisits(businessState);
        var variables=StateMachineEngine.buildExtraVars(WorkflowNodeSource.requesterVariables(current.owner()),
                current.owner().owner().sessionRef().sessionId(),current.executionRef().executionId(),stored.target().instanceId(),visits);
        var nodeContext=new NodeExecutionContext(workflow,node,WorkflowCheckpointCodec.object(binding.configJson()),
                businessState,checkpoint.extracted(),parameters.get("user_input") instanceof String text?text:"",
                checkpoint.messages(),stored.target().instanceId(),current.executionRef().tenantId().value(),List.of(),
                current.executionRef().executionId(),null,current.owner().owner().sessionRef().sessionId(),
                variables,checkpoint.cursor().incomingEdge(),
                new com.skillforge.core.workflow.execution.node.NodeCallIdentity(key.executionRef().executionId(),key.nodeId(),key.visit(),key.call()));
        return new SourceNode(new NodeInputs(stored.target(),stored.snapshot(),stored.parameters(),
                stored.evidenceRefs(),binding.customPrompt(),binding),nodeContext);
    }
    private record SourceNode(NodeInputs inputs,NodeExecutionContext node) { }

    @Override public WorkflowResult dispatch(WorkflowDispatch request) {
        return rejected(request.executionRef(),"workflow_registered_dispatch_required");
    }
    @Override public WorkflowResult resume(WorkflowResume request) {
        return rejected(request.executionRef(),"workflow_registered_dispatch_required");
    }
    @Override public WorkflowRecoveredResult recoverResult(WorkflowResultQuery query) {
        if(!policy.authorizeQuery(new WorkflowQuery(query.context().callContext().identity(),query.executionRef())))
            return new WorkflowRecoveredResult.Rejected(failure(query.executionRef(),"workflow_query_rejected",RetryMode.NONE));
        var current=read(query.context().callContext().identity(),query.executionRef());worker.verifySource(current);
        return switch(result(current)) {
            case WorkflowResult.Suspended suspended->new WorkflowRecoveredResult.Suspended(suspended);
            case WorkflowResult.Completed completed->new WorkflowRecoveredResult.Completed(completed);
            case WorkflowResult.Failed failed->new WorkflowRecoveredResult.Failed(failed);
            case WorkflowResult.Terminated terminated->new WorkflowRecoveredResult.Terminated(terminated);
            case WorkflowResult.Cancelled cancelled->new WorkflowRecoveredResult.Cancelled(cancelled);
            default->new WorkflowRecoveredResult.Rejected(failure(query.executionRef(),"workflow_result_pending",RetryMode.RESULT_QUERYABLE));
        };
    }
    @Override public Optional<WorkflowSnapshot> query(WorkflowQuery query) {
        Objects.requireNonNull(query);
        if(!policy.authorizeQuery(query))return Optional.empty();
        return ledger.find(new ExecutionLookup(query.identity(),query.executionRef())).map(this::snapshot);
    }
    @Override public WorkflowControlResult cancel(WorkflowCancel request) {
        return new WorkflowControlResult.Rejected(failure(request.executionRef(),"workflow_registered_dispatch_required",RetryMode.NONE));
    }

    /** 后台执行只消费该 Execution 已冻结的时限，不重新读取当前定义或接受调用方声明。 */
    public Duration executionTimeout(VerifiedIdentity identity,ExecutionRef execution) {
        return WorkflowCheckpointCodec.decode(read(identity,execution).checkpoint()).target().timeout();
    }

    /** Queue/recovery handler 使用当前可信身份推进原QUEUED/RUNNING事实，不重新dispatch或创建Provider调用。 */
    public WorkflowResult executeQueued(VerifiedIdentity identity,ExecutionRef execution,InvocationControl control) {
        var current=read(identity,execution);worker.verifySource(current);return result(current);
    }
    /** 只查询已准备的后台调用；获取独立租约后记录观察，不在此处恢复 Engine 或创建调用。 */
    public Optional<TypedPayload> observeExternal(VerifiedIdentity identity,ExecutionRef ref,InvocationControl control) {
        var current=read(identity,ref);
        if(!policy.authorizeExecution(new WorkflowQuery(identity,ref))||current.cancelRequestId().isPresent()
                ||current.state()!=WorkflowExecutionState.WAITING_EXTERNAL)return Optional.empty();
        var state=WorkflowCheckpointCodec.decode(current.checkpoint());
        if(state.engine().flatMap(com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Checkpoint::waitReason)
                .filter(reason->reason==NodeOutcome.WaitReason.CALLBACK||reason==NodeOutcome.WaitReason.TOOL_INPUT).isEmpty())return Optional.empty();
        var claim=ledger.acquireLease(new ExecutionLeaseRequest(identity,ref,"workflow-observe:"+UUID.randomUUID(),Duration.ofSeconds(15),clock.instant()));
        if(!(claim instanceof ExecutionLeaseResult.Acquired acquired))return Optional.empty();
        try {
            if(acquired.execution().executionRevision()!=current.executionRevision())return Optional.empty();
            var source=waitingNode(acquired.execution());
            if("async_task".equals(source.node().node().type()))return WorkflowAsyncTasks.timeout(
                    WorkflowCheckpointCodec.decode(acquired.execution().checkpoint()),clock.instant());
            return nodes.observeExternal(identity,acquired.lease(),acquired.execution(),control,source.inputs(),source.node());
        } finally {ledger.release(acquired.lease());}
    }

    /** 回调注册只读取原 async_task 等待，不领取执行权，也不延长截止时间。 */
    public Optional<java.time.Instant> requireAsyncTaskWait(VerifiedIdentity identity,ExecutionRecord execution) {
        if(!policy.authorizeExecution(new WorkflowQuery(identity,execution.executionRef()))
                ||!execution.owner().owner().equals(identity.owner())
                ||execution.state()!=WorkflowExecutionState.WAITING_EXTERNAL||execution.cancelRequestId().isPresent()
                ||!read(identity,execution.executionRef()).equals(execution)
                ||!"async_task".equals(waitingNode(execution).node().node().type()))
            throw new IllegalArgumentException("workflow_async_wait_rejected");
        var state=WorkflowCheckpointCodec.decode(execution.checkpoint());
        if(WorkflowAsyncTasks.timeout(state,clock.instant()).isPresent())
            throw new IllegalArgumentException("workflow_async_wait_expired");
        return state.asyncWait().orElseThrow().deadlineAt();
    }

    public void requireExternalSignal(VerifiedIdentity identity,ExecutionRecord execution,TypedPayload signal) {
        if(!EXTERNAL_SIGNAL_SCHEMA.equals(signal.schemaId())||execution.state()!=WorkflowExecutionState.WAITING_EXTERNAL
                ||execution.cancelRequestId().isPresent()||!policy.authorizeExecution(new WorkflowQuery(identity,execution.executionRef())))
            throw new IllegalArgumentException("workflow_external_signal_rejected");
        var source=waitingNode(execution);
        if("async_task".equals(source.node().node().type())) {
            if(!WorkflowAsyncTasks.timeout(WorkflowCheckpointCodec.decode(execution.checkpoint()),clock.instant()).equals(Optional.of(signal)))
                throw new IllegalArgumentException("workflow_async_timeout_rejected");
        } else nodes.requireExternalSignal(identity,execution,signal,source.inputs(),source.node());
    }
    /** 仅证明原真实等待允许此响应；不恢复Engine、不创建Provider Attempt或响应journal。 */
    public void requireResumeResponse(VerifiedIdentity identity,ExecutionRecord execution,TypedPayload response) {
        if(!identity.owner().equals(execution.owner().owner())||!execution.state().isWaiting()||execution.cancelRequestId().isPresent()
                ||!policy.authorizeExecution(new WorkflowQuery(identity,execution.executionRef()))||!read(identity,execution.executionRef()).equals(execution))
            throw new IllegalArgumentException("workflow_resume_source_rejected");
        worker.verifySource(execution);
        if(execution.state()==WorkflowExecutionState.WAITING_EXTERNAL) {
            if(EXTERNAL_SIGNAL_SCHEMA.equals(response.schemaId()))requireExternalSignal(identity,execution,response);
            else if(ASYNC_RESPONSE_SCHEMA.equals(response.schemaId()))requireAsyncCallback(execution,response);
            else throw new IllegalArgumentException("workflow_external_response_schema_rejected");
        } else {
            var state=WorkflowCheckpointCodec.decode(execution.checkpoint());
            if(state.engine().flatMap(com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Checkpoint::waitReason)
                    .filter(reason->reason==NodeOutcome.WaitReason.HUMAN_INPUT).isEmpty())throw new IllegalArgumentException("workflow_human_wait_mismatch");
            WorkflowCheckpointCodec.object(response);
        }
    }
    public void requireResumeResponse(org.apache.ibatis.session.SqlSession session,VerifiedIdentity identity,ExecutionRecord execution,TypedPayload response) {
        var actual=JdbcExecutionLedgerPort.lockBorrowed(session,execution.executionRef()).execution();
        if(!actual.equals(execution)||!execution.state().isWaiting()||execution.cancelRequestId().isPresent()
                ||!policy.authorizeExecutionBorrowed(session,identity,execution))throw new IllegalArgumentException("workflow_resume_source_rejected");
        registeredSourceVerifier.require(session,execution);
        var state=WorkflowCheckpointCodec.decode(execution.checkpoint());
        if(execution.state()==WorkflowExecutionState.WAITING_HITL) {
            if(state.engine().flatMap(com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Checkpoint::waitReason)
                    .filter(reason->reason==NodeOutcome.WaitReason.HUMAN_INPUT).isEmpty())throw new IllegalArgumentException("workflow_human_wait_mismatch");
            WorkflowCheckpointCodec.object(response);return;
        }
        var key=WorkflowNodeSource.activeIdentity(execution).orElseThrow();
        var binding=JdbcWorkflowBindings.findBorrowed(session,state.target()).orElseThrow();
        var source=WorkflowNodeSource.rebuild(execution,key,binding,parser);
        if(ASYNC_RESPONSE_SCHEMA.equals(response.schemaId())) {
            if(!"async_task".equals(source.node().node().type()))throw new IllegalArgumentException("workflow_async_node_mismatch");
            WorkflowAsyncTasks.requireCallback(state,response,clock.instant());
        } else if(EXTERNAL_SIGNAL_SCHEMA.equals(response.schemaId())) {
            if("async_task".equals(source.node().node().type())) {
                if(!WorkflowAsyncTasks.timeout(state,clock.instant()).equals(Optional.of(response)))throw new IllegalArgumentException("workflow_async_timeout_rejected");
            } else nodes.requireExternalSignalBorrowed(session,identity,execution,response,source.inputs(),source.node());
        } else throw new IllegalArgumentException("workflow_external_response_schema_rejected");
    }
    private void requireAsyncCallback(ExecutionRecord execution,TypedPayload response) {
        if(!"async_task".equals(waitingNode(execution).node().node().type()))throw new IllegalArgumentException("workflow_async_node_mismatch");
        WorkflowAsyncTasks.requireCallback(WorkflowCheckpointCodec.decode(execution.checkpoint()),response,clock.instant());
    }
    private SourceNode waitingNode(ExecutionRecord execution) {
        var state=WorkflowCheckpointCodec.decode(execution.checkpoint());
        var engine=state.engine().orElseThrow();var cursor=engine.cursor();
        if(engine.waitReason().filter(reason->reason==NodeOutcome.WaitReason.CALLBACK||reason==NodeOutcome.WaitReason.TOOL_INPUT).isEmpty())
            throw new IllegalArgumentException("workflow_external_wait_mismatch");
        var key=new WorkflowNodeCallIdentity(execution.owner(),execution.executionRef(),cursor.activeNode(),
                cursor.visits().get(cursor.activeNode()),1);
        return sourceNode(execution,key,state.target().instanceRevision(),state.target().installationRevision(),execution.checkpoint());
    }
    private ExecutionRecord read(VerifiedIdentity identity,ExecutionRef ref) {return ledger.find(new ExecutionLookup(identity,ref)).orElseThrow();}
    private WorkflowSnapshot snapshot(ExecutionRecord record) {return new WorkflowSnapshot(record.executionRef(),WorkflowCheckpointCodec.decode(record.checkpoint()).target(),record.state(),record.executionRevision(),record.resultRef(),record.updatedAt());}
    private WorkflowResult result(ExecutionRecord record) {
        return switch(record.state()) {
            case COMPLETED->new WorkflowResult.Completed(record.executionRef(),completedOutput(record),record.executionRevision(),record.resultRef().orElseThrow());
            case FAILED->new WorkflowResult.Failed(record.executionRef(),OptionalLong.of(record.executionRevision()),new PortFailure(record.reasonCode().orElseThrow(),RetryMode.NONE,record.resultRef().orElseThrow()));
            case LOOP_TERMINATED->new WorkflowResult.Terminated(record.executionRef(),record.executionRevision(),LoopTerminationReason.valueOf(record.reasonCode().orElseThrow()),record.resultRef().orElseThrow());
            case CANCELLED->new WorkflowResult.Cancelled(record.executionRef(),record.executionRevision(),record.resultRef().orElseThrow());
            case WAITING_HITL, WAITING_EXTERNAL->new WorkflowResult.Suspended(record.executionRef(),record.waiting().orElseThrow().suspendRef(),record.executionRevision(),new ResultRef(record.waiting().orElseThrow().suspendRef()),
                record.state()==WorkflowExecutionState.WAITING_HITL ? com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL : com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL);
            default->new WorkflowResult.OutcomeUnknown(record.executionRef(),OptionalLong.of(record.executionRevision()),
                    failure(record.executionRef(),"workflow_execution_pending",RetryMode.RESULT_QUERYABLE));
        };
    }
    private TypedPayload completedOutput(ExecutionRecord record) {
        var state=WorkflowCheckpointCodec.decode(record.checkpoint());
        var binding=bindings.find(record.executionRef().tenantId(),state.target()).orElseThrow();
        return WorkflowCheckpointCodec.output(state.engine().orElseThrow(),
                WorkflowCheckpointCodec.exportFields(binding.descriptorJson()));
    }
    private static PortFailure failure(ExecutionRef ref,String reason,RetryMode retry) {return new PortFailure(reason,retry,new ResultRef("workflow:"+ref.executionId()));}
    private static WorkflowResult rejected(ExecutionRef ref,String reason) {return new WorkflowResult.Failed(ref,OptionalLong.empty(),failure(ref,reason,RetryMode.NONE));}
    private static WorkflowResult unknown(ExecutionRef ref) {return new WorkflowResult.OutcomeUnknown(ref,OptionalLong.empty(),failure(ref,"workflow_outcome_unknown",RetryMode.RESULT_QUERYABLE));}
}
