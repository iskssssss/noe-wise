package com.skillforge.institution.runtime;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.runtime.WorkflowNodeAdmissionGuard;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.identity.*;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.IdentityPolicyPort.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.WorkflowNodeProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** Workflow节点的新受理、来源、实际Provider材料；运行只交给共享有界Worker。 */
public final class AgentRuntimeWorkflowNodeExecutions implements WorkflowNodeProgram.Sources, ExecutionCoordinator.Capabilities {
    private final JdbcExecutionStore store;
    private final ExecutionBudgetPort budgets;
    private final JdbcIdentityPolicyPort identities;
    private final JdbcModelBindings models;
    private final JdbcMcpBindings mcp;
    private final JdbcKnowledgeBindings knowledge;
    private final WorkflowNodeRequests requests;
    private final WorkflowParser parser;
    private final RegisteredExecutionProviderCalls providers;
    private final AgentExecutionCodec codec;
    private final WorkflowNodeAdmissionGuard guard;
    private final RegisteredWorkflowScriptCalls scripts;
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<RunRef>> parentWake=new java.util.concurrent.atomic.AtomicReference<>();
    private final Map<RunRef,Frame> active = new ConcurrentHashMap<>();

    public AgentRuntimeWorkflowNodeExecutions(JdbcExecutionStore store, ExecutionBudgetPort budgets, JdbcIdentityPolicyPort identities,
            JdbcModelBindings models, JdbcMcpBindings mcp, JdbcKnowledgeBindings knowledge, WorkflowNodeRequests requests,
            WorkflowParser parser, RegisteredExecutionProviderCalls providers, AgentExecutionCodec codec,RegisteredWorkflowScriptCalls scripts) {
        this.store=Objects.requireNonNull(store);this.budgets=Objects.requireNonNull(budgets);this.identities=Objects.requireNonNull(identities);
        this.models=Objects.requireNonNull(models);this.mcp=Objects.requireNonNull(mcp);this.knowledge=Objects.requireNonNull(knowledge);
        this.requests=Objects.requireNonNull(requests);this.parser=Objects.requireNonNull(parser);this.providers=Objects.requireNonNull(providers);this.codec=Objects.requireNonNull(codec);
        guard=new WorkflowNodeAdmissionGuard(parser,requests::verifyBorrowed);
        this.scripts=Objects.requireNonNull(scripts);
    }
    public static void configure(Configuration configuration) {
        WorkflowNodeAdmissionGuard.configure(configuration); JdbcModelBindings.configure(configuration); JdbcSessionPreparations.configureProfiles(configuration);
        com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls.configure(configuration);
    }
    public static boolean owns(ExecutionStore.Run run) { return WorkflowNodeRequests.reference(run.identity().runRef()).equals(run.configuration()); }
    /** 计量保留实际节点Run，只将授权来源解析到其已持久冻结的父Run；普通Run不改归属。 */
    public RunIdentity resourceOrigin(RunIdentity actual) {
        Objects.requireNonNull(actual);
        var run=store.load(actual.runRef()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
        if(!run.identity().equals(actual))throw rejected();
        return resourceOrigin(run);
    }
    /** 已登记审计复用准确Run事实，普通Agent或委派不新增热路径Store读取。 */
    public RunIdentity resourceOrigin(ExecutionStore.Run run) {
        Objects.requireNonNull(run);var actual=run.identity();
        if(!owns(run))return actual;
        var frozen=requests.read(material(run,run.configuration()));
        if(!WorkflowNodeSource.runIdentity(frozen.node()).equals(actual))throw rejected();
        var parent=store.load(frozen.node().owner().runRef()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
        if(!parent.identity().equals(frozen.node().owner())||!parent.configuration().equals(frozen.profile())
                ||!parent.identity().owner().equals(actual.owner())||!parent.identity().agentId().equals(actual.agentId())
                ||!store.originalInput(actual.runRef()).equals(Optional.of(frozen.request())))throw rejected();
        return parent.identity();
    }

    public void bindParentWake(java.util.function.Consumer<RunRef> wake) {
        if(!parentWake.compareAndSet(null,Objects.requireNonNull(wake)))throw new IllegalStateException("workflow_parent_wake_already_bound");
    }
    /** 节点确定等待或终态只通知原父Run；业务恢复入口再核真实Execution和精确WorkflowWait。 */
    public void committed(RunRef reference) {
        var run=store.load(reference);if(run.isEmpty()||!owns(run.orElseThrow())||!Set.of(ExecutionStore.Phase.WAITING,ExecutionStore.Phase.FINISHED).contains(run.orElseThrow().phase()))return;
        var frozen=requests.read(material(run.orElseThrow(),run.orElseThrow().configuration()));var wake=parentWake.get();
        if(wake==null)throw new IllegalStateException("workflow_parent_wake_not_bound");wake.accept(frozen.node().owner().runRef());
    }
    public void recover(TenantId tenant,int limit) {
        if(limit<=0||limit>100)throw new IllegalArgumentException("workflow_node_recovery_limit_invalid");
        // 恢复候选由宿主mapper显式发现；此方法不新建连接、任务队列或派发节点。
        var recovery=recoveryCandidates;if(recovery==null)throw new IllegalStateException("workflow_node_recovery_not_bound");
        var wake=parentWake.get();if(wake==null)throw new IllegalStateException("workflow_parent_wake_not_bound");
        for(var parent:recovery.apply(tenant,limit))wake.accept(parent);
    }
    private volatile java.util.function.BiFunction<TenantId,Integer,java.util.List<RunRef>> recoveryCandidates;
    public synchronized void bindRecovery(java.util.function.BiFunction<TenantId,Integer,java.util.List<RunRef>> candidates) {
        if(recoveryCandidates!=null)throw new IllegalStateException("workflow_node_recovery_already_bound");recoveryCandidates=Objects.requireNonNull(candidates);
    }

    /** 仅当前父Registered能创建节点；已有节点只返回原Run，不重新求值原请求。 */
    public ExecutionStore.AdmissionResult admit(ExecutionStore.DispatchResult.Registered parent, ExecutionStore.Prepared prepared,
            ExecutionLease lease, WorkflowNodeSource.Source source, Optional<TypedPayload> extraParameters) {
        var identity=WorkflowNodeSource.runIdentity(source.identity());
        var existing=store.load(identity.runRef());
        if(existing.isPresent()) {
            var run=existing.orElseThrow();if(!owns(run)||!run.identity().equals(identity))throw rejected();
            return new ExecutionStore.AdmissionResult(run,false);
        }
        var frozen=requests.freeze(source,parent.run().configuration(),extraParameters);var configuration=requests.encode(frozen);
        var root=budgets.find(parent.run().identity().runRef()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
        var registration=new ExecutionBudgetPort.Registration(identity.runRef(),root.root(),ExecutionBudgetPort.Role.MEMBER,new ExecutionBudgetPort.Policy.Enforced(root.policy().requireEnforced()));
        var admission=new ExecutionStore.Admission(parent.actor(),identity,AgentExecutionCodec.digest(configuration.content().schemaId()+"\0"+configuration.content().canonicalJson()),
                configuration.reference(),frozen.request(),WorkflowNodeProgram.initial(identity.runRef()),registration,java.util.List.of(),java.util.List.of(configuration));
        return store.admitChild(new ExecutionStore.ChildAdmission(parent,prepared,new ExecutionStore.WorkflowNodeChild(source.identity(),lease,
                source.execution().executionRevision(),WorkflowNodeSource.checkpointFingerprint(source.execution().checkpoint())),admission),guard);
    }
    public void begin(RunRef run) {
        if(active.putIfAbsent(run,new Frame())!=null)throw new IllegalStateException("workflow_node_sources_already_active");
    }
    public void end(RunRef run) { var frame=active.remove(run);if(frame==null)throw new IllegalStateException("workflow_node_sources_not_active");frame.closed=true; }
    public void bindControl(RunRef run,ExecutionCoordinator.Control control) {
        var frame=frame(run);if(frame.control!=null)throw rejected();frame.control=Objects.requireNonNull(control);
    }
    public void confirmActivity(SqlSession session,RunRef run) {
        var frame=active.get(run);if(frame==null)return;if(frame.closed)throw rejected();
        if(!(budgets instanceof JdbcExecutionBudgets jdbc))throw rejected();
        jdbc.recordActivity(session,run,frame.interval,frame.startedAt,frame.startedAt.plusNanos(Math.max(0,System.nanoTime()-frame.startedNanos)));
    }
    @Override public WorkflowNodeProgram.Configuration configuration(ExecutionStore.Run run) {
        var frame=frame(run.identity().runRef());if(frame.configuration!=null)return frame.configuration;
        var frozen=requests.read(material(run,run.configuration()));
        if(!WorkflowNodeSource.runIdentity(frozen.node()).equals(run.identity())||!owns(run))throw rejected();
        var actor=identities.originalVerification(run.identity().owner(),run.actorVerificationRef()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
        JdbcModelBindings.Binding model=null;Optional<JdbcMcpBindings.Binding> tool=Optional.empty();JdbcKnowledgeBindings.Binding grounding=null;
        switch(frozen.kind()) {
            case MODEL -> model=models.findForOwner(codec.decodeModelRequest(frozen.request().content(), refs -> store.materials(run.identity().runRef().tenantId(), refs)).request().target().binding(),run.identity().agentId()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
            case TOOL -> {
                if(WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(frozen.request().content().schemaId()))break;
                var request=codec.decodeTool(frozen.request().content());
                if(request.binding().providerType()==ToolBinding.ProviderType.MCP)tool=Optional.of(mcp.find(run.identity().runRef().tenantId(),request.target()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected));
            }
            case KNOWLEDGE -> grounding=knowledge.find(run.identity().runRef().tenantId(),codec.decodeKnowledgeRequest(frozen.request().content()).target()).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
            default -> throw rejected();
        }
        frame.frozen=frozen;frame.model=model;frame.tool=tool;frame.knowledge=grounding;
        frame.configuration=new WorkflowNodeProgram.Configuration(actor,frozen.kind(),frozen.target(),frozen.capability(),frozen.request(),frozen.category(),frozen.resources());
        return frame.configuration;
    }
    @Override public ExecutionStore.Material material(ExecutionStore.Run run,ResultRef reference) {
        return store.material(run.identity().runRef().tenantId(),reference).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
    }
    @Override public Optional<TypedPayload> scriptFeedback(TypedPayload request,ToolOutcome actual){try{return requests.scriptFeedback(request,actual);}catch(WorkflowScriptNodeCodec.OutputRejected rejected){throw new WorkflowNodeProgram.ScriptOutputRejected();}}
    public CancellationSignal cancellation(ExecutionStore.Run run) {
        var frame=loaded(run);if(frame.control==null)throw rejected();
        return ()->frame.closed||frame.control.cancelled()||frame.control.ownershipLost();
    }
    /** 工具上下文的Workflow身份只来自已加载原节点；当前发送权仍由真实Store事务重验。 */
    public void requireCall(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared) {
        Objects.requireNonNull(registered);Objects.requireNonNull(prepared);
        var run=registered.run();var frame=loaded(run);var original=frame.frozen;var configuration=frame.configuration;
        if(!owns(run)||!WorkflowNodeSource.runIdentity(original.node()).equals(run.identity())
                ||configuration.kind()!=ExecutionStore.Kind.TOOL||prepared.kind()!=ExecutionStore.Kind.TOOL
                ||!registered.actor().equals(configuration.actor())||!prepared.target().equals(configuration.target())
                ||!prepared.capability().equals(configuration.capability())
                ||WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId()))throw rejected();
        var request=codec.decodeTool(prepared.request().content());var frozen=codec.decodeTool(original.request().content());
        if(!new AgentExecutionCodec.ToolRequest(request.target(),request.binding(),request.arguments(),request.callId(),request.wireName()).equals(frozen))throw rejected();
        if(request.continuation().isPresent()) {
            if(!registered.attempt().continuation().equals(Optional.of(request.continuation().orElseThrow().waiting())))throw rejected();
        } else if(registered.attempt().continuation().isPresent()||!prepared.request().equals(original.request()))throw rejected();
        if(!store.authorizeSend(registered,prepared))throw rejected();
    }
    @Override public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared) {
        var frame=loaded(registered.run());var signal=cancellation(registered.run());
        if(WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId()))return scripts.invoke(registered,prepared,frame.frozen,signal);
        return switch(prepared.kind()) {
            case MODEL -> {var request=codec.decodeModelRequest(prepared, refs -> store.materials(registered.run().identity().runRef().tenantId(), refs)).request();yield providers.model(registered,prepared,PortalCallKind.MODEL_GENERATE,request,frame.model,new InvocationControl(Instant.now().plus(request.target().timeout()),signal));}
            case TOOL -> {var request=codec.decodeTool(prepared.request().content());yield providers.workflowTool(this,registered,prepared,request,frame.tool,frame.frozen.extraParameters(),new InvocationControl(Instant.now().plus(request.target().timeout()),signal));}
            case KNOWLEDGE -> {var request=codec.decodeKnowledgeRequest(prepared.request().content());yield providers.knowledge(registered,prepared,request,frame.knowledge,new InvocationControl(Instant.now().plus(request.target().timeout()),signal));}
            default -> throw rejected();
        };
    }
    @Override public Optional<ExecutionCoordinator.CapabilityResult> observe(ExecutionStore.Run run,ExecutionStore.Prepared prepared,ExecutionStore.Attempt original) {
        if(!WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId()))return Optional.empty();
        var frame=loaded(run);
        return scripts.observe(run,prepared,original,frame.frozen,frame.configuration.actor(),cancellation(run));
    }
    /** 派发事务再次核真实Engine活动节点和当前IAM，不借Admission时的旧租约获得新发送权。 */
    public boolean allowed(SqlSession session,ExecutionStore.Run run,VerifiedIdentity actor,ExecutionStore.Prepared prepared) {
        var frame=loaded(run);var configuration=frame.configuration;
        if(!configuration.actor().equals(actor)||prepared.kind()!=configuration.kind()||!prepared.target().equals(configuration.target())||!prepared.capability().equals(configuration.capability()))return false;
        // 取消只锁父Run；派发在原连接锁同一真实父行，取消提交后节点不能借旧Execution继续发新效果。
        var parent=store.lockBorrowed(session,frame.frozen.node().owner().runRef());
        if(!parent.identity().equals(frame.frozen.node().owner())||parent.cancelled()||parent.phase()==ExecutionStore.Phase.FINISHED)return false;
        var actual=JdbcExecutionLedgerPort.lockBorrowed(session,frame.frozen.node().executionRef()).execution();
        if(actual.cancelRequestId().isPresent()||!Set.of(WorkflowExecutionState.RUNNING,WorkflowExecutionState.RESUMING,WorkflowExecutionState.WAITING_EXTERNAL).contains(actual.state()))return false;
        var frozen=frame.frozen;
        var target=sourceTarget(actual.checkpoint());
        var binding=JdbcWorkflowBindings.findBorrowed(session,target).orElseThrow(AgentRuntimeWorkflowNodeExecutions::rejected);
        var source=WorkflowNodeSource.rebuild(actual,frozen.node(),binding,parser);
        if(!requests.rebuildBorrowed(session,source,frozen).equals(frozen))return false;
        if(prepared.kind()!=ExecutionStore.Kind.TOOL&&!prepared.request().equals(frozen.request()))return false;
        boolean script=WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId());
        if(script&&!prepared.request().equals(frozen.request()))return false;
        if(prepared.kind()==ExecutionStore.Kind.TOOL&&!script) {
            var request=codec.decodeTool(prepared.request().content());var original=codec.decodeTool(frozen.request().content());
            if(!new AgentExecutionCodec.ToolRequest(request.target(),request.binding(),request.arguments(),request.callId(),request.wireName()).equals(original))return false;
        }
        var workflow=WorkflowPolicyResource.execution(new WorkflowPortal.WorkflowQuery(actor,actual.executionRef()),run.identity().agentId());
        if(!authorized(session,run,actor,"workflow.execute",workflow))return false;
        if(script)return true;
        final String action;final TypedPayload resource;
        switch(prepared.kind()) {
            case MODEL -> {var request=codec.decodeModelRequest(prepared).request();action="model.generate";resource=ModelPolicyResource.dispatch(run.identity(),request.target().binding(),request.requestedStrategy());}
            case TOOL -> {var request=codec.decodeTool(prepared.request().content());action="tool.invoke";resource=ToolPolicyResource.invocation(run.identity(),request.binding(),request.target(),new IdempotencyKey(prepared.action().actionId()));}
            case KNOWLEDGE -> {var request=codec.decodeKnowledgeRequest(prepared.request().content());action="knowledge.execute";resource=KnowledgePolicyResource.dispatch(run.identity(),request.snapshot(),request.target(),request.request(),request.sourceEventId(),new IdempotencyKey(prepared.action().actionId()),prepared.action(),prepared.request().reference(),prepared.fingerprint());}
            default -> {return false;}
        }
        return authorized(session,run,actor,action,resource);
    }
    private boolean authorized(SqlSession session,ExecutionStore.Run run,VerifiedIdentity actor,String action,TypedPayload resource) {
        return identities.authorize(session,new AuthorizationRequest(actor,action,resource,run.identity().trace().traceId(),run.identity().trace().spanId())) instanceof AuthorizationResult.Allowed;
    }
    private WorkflowPortal.FrozenWorkflowTarget sourceTarget(TypedPayload checkpoint) {
        // 共享Source重建器拥有checkpoint完整codec；这里仅取其强类型冻结目标，不自解JSON。
        return WorkflowNodeSource.target(checkpoint);
    }
    private Frame frame(RunRef run) {var frame=active.get(run);if(frame==null||frame.closed)throw rejected();return frame;}
    private Frame loaded(ExecutionStore.Run run) {var frame=frame(run.identity().runRef());if(frame.configuration==null)throw rejected();return frame;}
    private static ExecutionStore.Rejection rejected() {return new ExecutionStore.Rejection("workflow_node_execution_rejected");}
    private static final class Frame {
        final long startedNanos=System.nanoTime();final Instant startedAt=Instant.now();final String interval="workflow-node-active:"+UUID.randomUUID();
        volatile boolean closed;volatile ExecutionCoordinator.Control control;
        volatile WorkflowNodeProgram.Configuration configuration;WorkflowNodeRequests.Frozen frozen;
        JdbcModelBindings.Binding model;Optional<JdbcMcpBindings.Binding> tool=Optional.empty();JdbcKnowledgeBindings.Binding knowledge;
    }
}
