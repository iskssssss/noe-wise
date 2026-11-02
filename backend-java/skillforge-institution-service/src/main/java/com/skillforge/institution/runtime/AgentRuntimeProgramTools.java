package com.skillforge.institution.runtime;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.ProgramToolInvoker;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.identity.*;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.ObjectProvider;

/** ProgramCode的实际RPC子执行；独立来源/Run/租约/预算，仅借父调用已持有的物理槽。 */
public final class AgentRuntimeProgramTools implements ProgramToolInvoker,ProgramToolProgram.Sources,ExecutionCoordinator.Capabilities {
    public static final String SCHEMA="skillforge.runtime.execution.program-tool.v1";
    private static final Set<String> DIRECT=Set.of("run_code","ask_user","request_user_input","request_approval","create_goal","get_goal","update_goal",
            "submit_team_plan","submit_task_result","finalize_task_outputs","select_plan_mode","update_plan","workspace_list","workspace_read","workspace_write","workspace_shell");
    private final JdbcExecutionStore store;
    private final ExecutionBudgetPort budgets;
    private final JdbcIdentityPolicyPort identities;
    private final AgentRuntimeExecutionCatalog catalogs;
    private final AgentRuntimePublishedCapabilities capabilities;
    private final AgentRunBindings bindings;
    private final JdbcMcpBindings mcp;
    private final RegisteredExecutionProviderCalls providers;
    private final ObjectProvider<AgentRuntimeExecutionWorker> workers;
    private final AgentRuntimePayloadValidation payloads;
    private final AgentExecutionCodec codec;
    private final ObjectMapper json;
    private final CodecLimits limits;
    private final Map<RunRef,Frame> active=new ConcurrentHashMap<>();
    private final Map<RunRef,PlatformToolExecutionContext> parentScopes=new ConcurrentHashMap<>();
    public AgentRuntimeProgramTools(JdbcExecutionStore store,ExecutionBudgetPort budgets,JdbcIdentityPolicyPort identities,
            AgentRuntimeExecutionCatalog catalogs,AgentRuntimePublishedCapabilities capabilities,AgentRunBindings bindings,JdbcMcpBindings mcp,
            RegisteredExecutionProviderCalls providers,ObjectProvider<AgentRuntimeExecutionWorker> workers,AgentRuntimePayloadValidation payloads,
            ObjectMapper json,AgentRuntimeProperties properties) {
        this.store=Objects.requireNonNull(store);this.budgets=Objects.requireNonNull(budgets);this.identities=Objects.requireNonNull(identities);
        this.catalogs=Objects.requireNonNull(catalogs);this.capabilities=Objects.requireNonNull(capabilities);this.bindings=Objects.requireNonNull(bindings);
        this.mcp=Objects.requireNonNull(mcp);this.providers=Objects.requireNonNull(providers);this.workers=Objects.requireNonNull(workers);this.payloads=Objects.requireNonNull(payloads);
        this.json=com.skillforge.institution.runtime.SessionSubjectJson.configure(Objects.requireNonNull(json).copy()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        limits=new CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth());codec=new AgentExecutionCodec(limits);
    }
    public record Frozen(RunIdentity parent,RunIdentity child,ExecutionStore.ProgramChildSource source,ResultRef parentConfiguration,
            ResultRef parentRequest,String parentFingerprint,ResultRef capability,ExecutionStore.Material request,Instant deadline,Optional<TypedPayload> extraParameters) {
        public Frozen {Objects.requireNonNull(parent);Objects.requireNonNull(child);Objects.requireNonNull(source);Objects.requireNonNull(parentConfiguration);Objects.requireNonNull(parentRequest);
            Objects.requireNonNull(parentFingerprint);Objects.requireNonNull(capability);Objects.requireNonNull(request);Objects.requireNonNull(deadline);Objects.requireNonNull(extraParameters);}
    }
    public static ResultRef reference(RunRef run){return new ResultRef("program-tool-configuration:"+run.runId());}
    public static boolean owns(ExecutionStore.Run run){return reference(run.identity().runRef()).equals(run.configuration());}
    public boolean active(RunRef run){var frame=active.get(run);return frame!=null&&!frame.closed;}

    /** 只从产生父工具调用前的真实已采纳 MODEL 请求读取可见定义，不重建当前能力目录。 */
    private AgentExecutionCodec.ProgramTools visibleTools(ExecutionStore.DispatchResult.Registered parent) {
        ExecutionStore.Action model = null;
        long after = 0;
        boolean reached = false;
        while (!reached) {
            var page = store.actions(parent.run().identity().runRef(), after, 100);
            if (page.isEmpty()) break;
            for (var action : page) {
                if (!action.run().equals(parent.run().identity().runRef()) || action.ordinal() <= after)
                    throw rejected("program_child_source_rejected");
                after = action.ordinal();
                if (action.ordinal() >= parent.action().ordinal()) { reached = true; break; }
                if (action.kind() == ExecutionStore.Kind.MODEL) model = action;
            }
            if (page.size() < 100) break;
        }
        if (model == null) throw rejected("program_child_source_rejected");
        var attempt = store.attempt(model.run(), model.selectedAttempt()
                .orElseThrow(() -> rejected("program_child_source_rejected")))
                .orElseThrow(() -> rejected("program_child_source_rejected"));
        if (!attempt.run().equals(model.run()) || !attempt.action().equals(model.reference())
                || !attempt.adopted() || attempt.result().isEmpty()) throw rejected("program_child_source_rejected");
        var modelRequest = material(parent.run(), attempt.request());
        if(AgentExecutionCodec.MODEL_MANIFEST_SCHEMA.equals(modelRequest.content().schemaId())
                &&!AgentExecutionCodec.reference(modelRequest.content()).equals(modelRequest.reference()))throw rejected("program_child_source_rejected");
        return codec.programTools(modelRequest.content(),refs -> store.materials(parent.run().identity().runRef().tenantId(),refs));
    }

    public Frozen configurationMaterial(ExecutionStore.Run run) {
        if(!owns(run))throw rejected("program_child_source_rejected");var material=material(run,run.configuration());
        if(!SCHEMA.equals(material.content().schemaId())||!payloads.accepts(new TypedPayload("application/json",material.content().canonicalJson())))throw rejected("program_child_source_rejected");
        try {var frozen=json.readValue(material.content().canonicalJson(),Frozen.class);
            if(!frozen.child().equals(run.identity())||!childIdentity(frozen.parent(),frozen.source()).equals(run.identity())
                    ||!frozen.source().action().tenantId().equals(run.identity().runRef().tenantId())
                    ||!frozen.request().reference().equals(requestReference(run.identity().runRef()))||!encode(frozen).equals(material))throw rejected("program_child_source_rejected");
            var request=codec.decodeTool(frozen.request().content());if(request.continuation().isPresent())throw rejected("program_child_source_rejected");requireProgrammable(request);
            return frozen;
        }catch(java.io.IOException invalid){throw rejected("program_child_source_rejected");}
    }
    @Override public ProgramToolInvoker.Calls bind(PlatformToolExecutionContext context) {
        Objects.requireNonNull(context);
        return new ProgramToolInvoker.Calls() {
            private AgentExecutionCodec.ProgramTools declarations;
            @Override public TypedPayload invoke(int ordinal,String handle,TypedPayload arguments) {
                if(context.programChild()||context.workflowNode())throw rejected("program_recursion_forbidden");
                if(context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt()))throw rejected("program_scope_closed");
                var parent=parent(context);
                var candidate=declarations==null?visibleTools(parent):declarations;
                final ModelPort.ModelTool declaration;
                try { declaration=codec.requireProgramTool(candidate,handle); }
                catch(AgentExecutionCodec.ProgramToolRejected rejected) { throw new ProgramToolInvoker.AdmissionRejected(rejected.reasonCode()); }
                declarations=candidate;
                return AgentRuntimeProgramTools.this.invoke(context,parent,declaration,ordinal,handle,arguments);
            }
        };
    }

    private TypedPayload invoke(PlatformToolExecutionContext context,ExecutionStore.DispatchResult.Registered parent,
            ModelPort.ModelTool declaration,int ordinal,String handle,TypedPayload arguments) {
        var source=new ExecutionStore.ProgramChildSource(parent.action().reference(),parent.attempt().id(),ordinal);
        var child=childIdentity(parent.run().identity(),source);var previous=store.load(child.runRef());Frozen frozen;
        var normalized=arguments(arguments);
        if(previous.isPresent()) {
            frozen=configurationMaterial(previous.orElseThrow());var original=codec.decodeTool(frozen.request().content());
            if(!frozen.parent().equals(context.run())||!frozen.source().equals(source)||!original.target().handle().value().equals(declaration.toolHandle())||!original.wireName().equals(handle)||!original.arguments().equals(normalized))throw rejected("program_child_mismatch");
        } else {
            var catalog=catalogs.read(material(parent.run(),AgentRuntimeExecutionCatalog.reference(parent.run().identity().runRef())),parent.run().identity());
            var selected=SessionToolDirectory.requirePrepared(catalog.tools(),declaration.toolHandle());
            if (!selected.target().inputSchema().equals(declaration.inputSchema())) throw rejected("program_tool_contract_changed");
            var request=new AgentExecutionCodec.ToolRequest(selected.target(),selected.binding(),normalized,"program-call:"+child.runRef().runId(),handle);requireProgrammable(request);
            SessionRuntimePayloads.validateAgainstSchema(request.target().inputSchema(),normalized,limits);
            var requestMaterial=new ExecutionStore.Material(requestReference(child.runRef()),codec.encodeTool(request));
            var input=store.originalInput(context.run().runRef()).orElseThrow(()->rejected("program_child_source_rejected"));
            frozen=new Frozen(context.run(),child,source,parent.run().configuration(),parent.attempt().request(),parent.action().fingerprint(),parent.action().capability(),requestMaterial,
                    context.deadlineAt(),AgentRuntimeMcpExtraParameters.fromInbound(codec.frozenInput(input.content())));
        }
        var configuration=encode(frozen);var registration=budgets.find(parent.run().identity().runRef()).orElseThrow(()->rejected("program_prepare_rejected"));
        var admission=new ExecutionStore.Admission(parent.actor(),child,AgentExecutionCodec.digest(configuration.content().schemaId()+"\0"+configuration.content().canonicalJson()),configuration.reference(),
                frozen.request(),ProgramToolProgram.initial(child.runRef()),new ExecutionBudgetPort.Registration(child.runRef(),registration.root(),ExecutionBudgetPort.Role.MEMBER,new ExecutionBudgetPort.Policy.Enforced(registration.policy().requireEnforced())),List.of(),List.of(configuration));
        var prepared=prepared(parent);var acceptedFrozen=frozen;
        workers.getObject().observeReady(child.runRef());
        var admitted=store.admitProgramChild(new ExecutionStore.ProgramChildAdmission(parent,prepared,source,admission),(session,current,origin,candidate,existing)->{
            if(!current.identity().equals(acceptedFrozen.parent())||!candidate.run().equals(acceptedFrozen.child())||!origin.equals(acceptedFrozen.source()))throw rejected("program_child_source_rejected");
            if(!existing)requireNew(session,current,acceptedFrozen,parent.actor());
        });
        if(admitted.run().phase()!=ExecutionStore.Phase.FINISHED&&admitted.run().phase()!=ExecutionStore.Phase.UNCERTAIN)
            workers.getObject().driveProgramChild(context,source,child.runRef());
        return result(child.runRef(),frozen.request());
    }
    /** 原父Provider活动只组合停止信号；发送权仍由Store的parent/source与当前IAM核验。 */
    public <T> T withParentControl(RunRef child,PlatformToolExecutionContext parent,java.util.function.Supplier<T> work) {
        if(parentScopes.putIfAbsent(child,parent)!=null)throw rejected("program_scope_rejected");
        try{return work.get();}finally{parentScopes.remove(child,parent);}
    }
    /** Worker在借用原物理槽前核原持久来源；configuration不能单独赋予子调用身份。 */
    public void requireInline(ExecutionStore.Run parent,ExecutionStore.ProgramChildSource source,ExecutionStore.Run child) {
        var frozen=configurationMaterial(child);
        if(!frozen.parent().equals(parent.identity())||!frozen.source().equals(source)||!store.programChildSource(child.identity().runRef(),parent.identity().runRef(),source))throw rejected("program_child_source_rejected");
    }
    /** 资源选择仅沿真实program purpose父链读取原输入；实际效果Run/Action仍为调用中的child。 */
    public RunIdentity resourceOrigin(RunIdentity actual) {
        Objects.requireNonNull(actual);
        var persisted=store.load(actual.runRef()).orElseThrow(()->rejected("program_child_source_rejected"));
        if(!persisted.identity().equals(actual))throw rejected("program_child_source_rejected");
        return resourceOrigin(persisted);
    }
    /** 同次宿主准备消费已读取的准确Run；Program父链仍完整核验，不重复读取当前首个Run。 */
    public RunIdentity resourceOrigin(ExecutionStore.Run persisted) {
        Objects.requireNonNull(persisted);var actual=persisted.identity();var expected=actual;var run=persisted;var visited=new HashSet<RunRef>();
        for(int depth=0;depth<16;depth++) {
            if(!visited.add(expected.runRef()))throw rejected("program_child_source_rejected");
            if(!run.identity().equals(expected))throw rejected("program_child_source_rejected");
            if(!owns(run))return expected;
            var frozen=configurationMaterial(run);var parent=store.load(frozen.parent().runRef()).orElseThrow(()->rejected("program_child_source_rejected"));
            requireInline(parent,frozen.source(),run);
            if(!frozen.parent().owner().equals(actual.owner())||!frozen.parent().agentId().equals(actual.agentId())
                    ||!parent.identity().equals(frozen.parent())||!parent.configuration().equals(frozen.parentConfiguration())
                    ||!store.originalInput(run.identity().runRef()).equals(Optional.of(frozen.request())))throw rejected("program_child_source_rejected");
            var action=store.action(frozen.source().action()).orElseThrow(()->rejected("program_child_source_rejected"));
            var attempt=store.attempt(parent.identity().runRef(),frozen.source().attemptId()).orElseThrow(()->rejected("program_child_source_rejected"));
            var source=material(parent,frozen.parentRequest());var request=codec.decodeTool(source.content());
            if(!action.reference().equals(frozen.source().action())||!action.run().equals(parent.identity().runRef())||action.kind()!=ExecutionStore.Kind.TOOL
                    ||!attempt.id().equals(frozen.source().attemptId())||!attempt.run().equals(parent.identity().runRef())||!attempt.action().equals(action.reference())
                    ||!attempt.request().equals(frozen.parentRequest())||!action.capability().equals(frozen.capability())||!action.fingerprint().equals(frozen.parentFingerprint())
                    ||!AgentExecutionCodec.digest(source.content().schemaId()+"\0"+source.content().canonicalJson()).equals(frozen.parentFingerprint())
                    ||request.binding().providerType()!=ToolBinding.ProviderType.PLATFORM||!"run_code".equals(request.binding().toolName())||!"run_code".equals(request.binding().providerRef()))
                throw rejected("program_child_source_rejected");
            expected=parent.identity();run=parent;
        }
        throw rejected("program_child_source_rejected");
    }
    /** Provider仅从服务器原准入材料确认programChild，不消费模型参数中的布尔值。 */
    public void requireCall(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared) {
        var frozen=configurationMaterial(registered.run());
        if(!prepared.action().equals(ProgramToolProgram.action(registered.run().identity().runRef()))||!prepared.request().equals(frozen.request())
                ||!prepared.capability().equals(frozen.capability())||!store.programChildSource(registered.run().identity().runRef(),frozen.parent().runRef(),frozen.source()))throw rejected("program_child_source_rejected");
    }
    public void begin(RunRef run){if(active.putIfAbsent(run,new Frame())!=null)throw rejected("program_scope_rejected");}
    public void end(RunRef run){var frame=active.remove(run);if(frame==null)throw rejected("program_scope_rejected");frame.closed=true;}
    public void bindControl(RunRef run,ExecutionCoordinator.Control control){var frame=frame(run);if(frame.control!=null)throw rejected("program_scope_rejected");
        var snapshot=budgets.snapshot(run);if(!snapshot.registration().run().equals(run)||snapshot.registration().role()!=ExecutionBudgetPort.Role.MEMBER)throw rejected("program_scope_rejected");
        frame.confirmedActive=java.time.Duration.ofMillis(snapshot.runActiveMillis());frame.control=Objects.requireNonNull(control);}
    public CancellationSignal cancellation(ExecutionStore.Run run){var frame=frame(run.identity().runRef());if(frame.control==null)throw rejected("program_scope_rejected");
        return ()->{var parent=parentScopes.get(run.identity().runRef());return frame.closed||frame.control.cancelled()||frame.control.ownershipLost()
                ||!Instant.now().isBefore(frame.frozen.deadline())||parent!=null&&(parent.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(parent.deadlineAt()));};}
    public void confirmActivity(SqlSession session,RunRef run){var frame=active.get(run);if(frame==null)return;if(frame.closed||!(budgets instanceof JdbcExecutionBudgets jdbc))throw rejected("program_scope_rejected");
        jdbc.recordActivity(session,run,frame.interval,frame.startedAt,frame.startedAt.plusNanos(Math.max(0,System.nanoTime()-frame.startedNanos)));}
    @Override public ProgramToolProgram.Configuration configuration(ExecutionStore.Run run) {
        var frame=frame(run.identity().runRef());if(frame.configuration!=null)return frame.configuration;var frozen=configurationMaterial(run);
        if(!store.programChildSource(run.identity().runRef(),frozen.parent().runRef(),frozen.source()))throw rejected("program_child_source_rejected");
        var actor=identities.originalVerification(run.identity().owner(),run.actorVerificationRef()).orElseThrow(()->rejected("program_prepare_rejected"));
        var request=codec.decodeTool(frozen.request().content());frame.frozen=frozen;frame.binding=bindings.find(frozen.parent().runRef()).orElseThrow(()->rejected("program_prepare_rejected"));
        frame.tool=request.binding().providerType()==ToolBinding.ProviderType.MCP?Optional.of(mcp.find(run.identity().runRef().tenantId(),request.target()).orElseThrow(()->rejected("program_prepare_rejected"))):Optional.empty();
        frame.configuration=new ProgramToolProgram.Configuration(actor,frozen.capability(),frozen.request());return frame.configuration;
    }
    @Override public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared) {
        var frame=loaded(registered.run());requireCall(registered,prepared);var request=codec.decodeTool(prepared.request().content());
        Instant deadline=Instant.now().plus(request.target().timeout());if(frame.frozen.deadline().isBefore(deadline))deadline=frame.frozen.deadline();
        // 与真正Provider调用同线程打开/关闭；Control可从共享timer关闭，但不能跨线程释放ThreadLocal。
        try(var scope=ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(budgets,registered.run().identity().runRef(),()->ExecutionBudgetPort.ClosingSpec.TEXT,
                Optional::empty,Optional::empty,()->frame.confirmedActive.plusNanos(Math.max(0,System.nanoTime()-frame.startedNanos))))) {
            return providers.programTool(this,registered,prepared,request,frame.tool,frame.frozen.extraParameters(),new InvocationControl(deadline,cancellation(registered.run())));
        }
    }
    @Override public Optional<ExecutionCoordinator.CapabilityResult> observe(ExecutionStore.Run run,ExecutionStore.Prepared prepared,ExecutionStore.Attempt original){return Optional.empty();}
    public boolean allowed(SqlSession session,ExecutionStore.Run run,VerifiedIdentity actor,ExecutionStore.Prepared prepared) {
        var frame=loaded(run);if(!frame.configuration.actor().equals(actor)||prepared.kind()!=ExecutionStore.Kind.TOOL||!prepared.action().equals(ProgramToolProgram.action(run.identity().runRef()))
                ||!prepared.request().equals(frame.frozen.request())||!prepared.capability().equals(frame.frozen.capability()))return false;
        try {
            // 父锁与后续原派发事实读取必须共享 child 派发事务的完整借用作用域。
            return store.participate(session, run.identity().runRef().tenantId(), () -> {
                var parent=store.lockBorrowed(session,frame.frozen.parent().runRef());
                requireNew(session,parent,frame.frozen,actor);
                return true;
            });
        } catch(ExecutionStore.Rejection denied){return false;}
    }
    private void requireNew(SqlSession session,ExecutionStore.Run parent,Frozen frozen,VerifiedIdentity actor) {
        if(!parent.identity().equals(frozen.parent())||!parent.configuration().equals(frozen.parentConfiguration())||parent.cancelled()||parent.phase()!=ExecutionStore.Phase.AWAITING_RESULT||parent.lease().isEmpty()
                ||!Instant.now().isBefore(parent.lease().orElseThrow().leaseUntil())||!Instant.now().isBefore(frozen.deadline()))throw rejected("program_scope_closed");
        var action=store.action(frozen.source().action()).orElseThrow(()->rejected("program_child_source_rejected"));
        var attempt=store.attempt(parent.identity().runRef(),frozen.source().attemptId()).orElseThrow(()->rejected("program_child_source_rejected"));
        if(!action.reference().equals(frozen.source().action())||!action.run().equals(parent.identity().runRef())||!action.currentAttempt().equals(attempt.id())||action.kind()!=ExecutionStore.Kind.TOOL||!attempt.action().equals(action.reference())
                ||attempt.fence()!=parent.fence()||attempt.result().isPresent()||!attempt.request().equals(frozen.parentRequest())||!action.fingerprint().equals(frozen.parentFingerprint()))throw rejected("program_scope_closed");
        var request=codec.decodeTool(frozen.request().content());requireProgrammable(request);
        final SessionToolDirectory.Entry current;
        try{current=capabilities.requireCurrent(session,frozen.parent(),request.target());}catch(IllegalArgumentException denied){throw rejected("program_tool_contract_changed");}
        if(!current.binding().equals(request.binding()))throw rejected("program_tool_contract_changed");
        var registered=new ExecutionStore.DispatchResult.Registered(parent,action,attempt,actor);
        if(!store.participate(session,parent.identity().runRef().tenantId(),()->store.authorizeSend(registered,prepared(registered))))throw rejected("program_scope_closed");
        var resource=ToolPolicyResource.invocation(frozen.child(),request.binding(),request.target(),new IdempotencyKey(ProgramToolProgram.action(frozen.child().runRef()).actionId()));
        if(!(identities.authorize(session,new IdentityPolicyPort.AuthorizationRequest(actor,"tool.invoke",resource,frozen.child().trace().traceId(),frozen.child().trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed))throw rejected("program_prepare_rejected");
    }
    private ExecutionStore.DispatchResult.Registered parent(PlatformToolExecutionContext context) {
        var run=store.load(context.run().runRef()).orElseThrow(()->rejected("program_scope_rejected"));var action=store.action(new ActionRef(run.identity().runRef().tenantId(),context.invocationId())).orElseThrow(()->rejected("program_scope_rejected"));
        var attempt=store.attempt(run.identity().runRef(),context.attemptId()).orElseThrow(()->rejected("program_scope_rejected"));
        if(!run.identity().equals(context.run())||owns(run)||!context.callContext().identity().verificationRef().equals(run.actorVerificationRef())||!action.run().equals(run.identity().runRef())
                ||action.kind()!=ExecutionStore.Kind.TOOL||!action.currentAttempt().equals(attempt.id())||!attempt.action().equals(action.reference()))throw rejected("program_scope_rejected");
        var request=codec.decodeTool(material(run,attempt.request()).content());
        if(request.binding().providerType()!=ToolBinding.ProviderType.PLATFORM||!"run_code".equals(request.binding().toolName()))throw rejected("program_scope_rejected");
        return new ExecutionStore.DispatchResult.Registered(run,action,attempt,context.callContext().identity());
    }
    private ExecutionStore.Prepared prepared(ExecutionStore.DispatchResult.Registered parent){var action=parent.action();return new ExecutionStore.Prepared(action.reference(),action.ordinal(),action.kind(),action.target(),action.capability(),material(parent.run(),parent.attempt().request()),action.fingerprint());}
    private TypedPayload result(RunRef reference,ExecutionStore.Material original) {
        var run=store.load(reference).orElseThrow(()->rejected("program_child_mismatch"));var action=store.action(ProgramToolProgram.action(reference)).orElseThrow(()->rejected("program_tool_outcome_unknown"));
        var attempt=store.attempt(reference,action.selectedAttempt().orElseThrow(()->rejected("program_tool_outcome_unknown"))).orElseThrow(()->rejected("program_tool_outcome_unknown"));
        if(run.cancelled())throw rejected("program_tool_stopped");if(!attempt.adopted()||!attempt.request().equals(original.reference())||attempt.result().isEmpty())throw rejected("program_tool_outcome_unknown");
        var outcome=codec.decodeToolOutcome(material(run,attempt.result().orElseThrow()).content());
        if(outcome instanceof ToolOutcome.InputRequired)throw rejected("program_tool_requires_direct_call");if(outcome instanceof ToolOutcome.OutcomeUnknown)throw rejected("program_tool_outcome_unknown");
        var feedback=attempt.feedback().map(value->material(run,value));var safe=codec.safeToolFeedback(codec.decodeTool(original.content()),outcome);
        if(!safe.equals(feedback.map(ExecutionStore.Material::content)))throw rejected("program_tool_contract_changed");return feedback.orElseThrow(()->rejected("program_tool_contract_changed")).content();
    }
    private static void requireProgrammable(AgentExecutionCodec.ToolRequest request) {if(!request.target().modelInvocable()||request.target().confirmation()!=ToolConfirmationPolicy.NEVER||DIRECT.contains(request.binding().toolName())
            ||request.binding().toolName().startsWith("workspace_")||OutputControlTools.control(request.binding().toolName()))throw new ProgramToolInvoker.AdmissionRejected("program_tool_requires_direct_call");}
    private TypedPayload arguments(TypedPayload arguments) {if(!"application/json".equals(arguments.schemaId())||!payloads.accepts(arguments))throw rejected("program_prepare_rejected");try {var tree=json.readTree(arguments.canonicalJson());if(!tree.isObject())throw rejected("program_prepare_rejected");return new TypedPayload("application/json",json.writeValueAsString(json.convertValue(tree,Object.class)));}catch(java.io.IOException invalid){throw rejected("program_prepare_rejected");}}
    private ExecutionStore.Material encode(Frozen frozen){try {return new ExecutionStore.Material(reference(frozen.child().runRef()),new TypedPayload(SCHEMA,json.writeValueAsString(frozen)));}catch(java.io.IOException failed){throw rejected("program_prepare_rejected");}}
    private ExecutionStore.Material material(ExecutionStore.Run run,ResultRef reference){return store.material(run.identity().runRef().tenantId(),reference).orElseThrow(()->rejected("program_child_source_rejected"));}
    private static ResultRef requestReference(RunRef run){return new ResultRef("program-tool-request:"+run.runId());}
    public static RunIdentity childIdentity(RunIdentity parent,ExecutionStore.ProgramChildSource source) {String key=AgentExecutionCodec.digest(parent.runRef().tenantId().value()+"\0"+parent.runRef().runId()+"\0"+source.action().actionId()+"\0"+source.attemptId()+"\0"+source.ordinal());
        return new RunIdentity(parent.owner(),parent.agentId(),new InputId("program-tool-input:"+key),new RunRef(parent.runRef().tenantId(),"program-tool:"+key),
                new TraceEnvelope(parent.trace().traceId(),"program-tool-span:"+key,parent.trace().rootInputId(),parent.trace().causedByEventId()),parent.causationIdentityRef(),parent.requester(),parent.sourceMessageRef(),parent.originRef());}
    private Frame frame(RunRef run){var frame=active.get(run);if(frame==null||frame.closed)throw rejected("program_scope_closed");return frame;}
    private Frame loaded(ExecutionStore.Run run){var frame=frame(run.identity().runRef());if(frame.configuration==null)throw rejected("program_scope_rejected");return frame;}
    private static ExecutionStore.Rejection rejected(String reason){return new ExecutionStore.Rejection(reason);}
    private static final class Frame {final long startedNanos=System.nanoTime();final Instant startedAt=Instant.now();final String interval="program-tool-active:"+UUID.randomUUID();volatile boolean closed;volatile ExecutionCoordinator.Control control;java.time.Duration confirmedActive;Frozen frozen;ProgramToolProgram.Configuration configuration;AgentRunBindings.Binding binding;Optional<JdbcMcpBindings.Binding> tool=Optional.empty();}
}
