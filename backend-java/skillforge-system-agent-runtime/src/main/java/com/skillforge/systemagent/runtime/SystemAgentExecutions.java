package com.skillforge.systemagent.runtime;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.identity.*;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.ObservedModelInvocationAudit;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.IdentityPolicyPort.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.core.SkillActivation;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.starter.ExecutionHostRegistry;
import com.skillforge.runtime.starter.ExecutionHostWorker;
import com.skillforge.runtime.starter.ExecutionHostScheduler;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.session.SqlSession;

/** 系统Agent宿主适配：复用唯一Program/Coordinator/Store和共享执行槽，拥有本宿主的冻结业务来源。 */
final class SystemAgentExecutions implements AgentExecutionProgram.Sources, ExecutionCoordinator.Capabilities,
        ExecutionCoordinator.Controls, ExecutionHostRegistry.Host, AutoCloseable {
    static final String CATALOG_SCHEMA = "skillforge.runtime.system-agent.catalog.v2";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SystemAgentExecutions.class);
    @Override public Optional<ExecutionBudgetPort.Snapshot> budgetSnapshot(ExecutionStore.Run run) {
        return Optional.of(budgets.snapshot(run.identity().runRef()));
    }

    private final JdbcExecutionStore store; private final JdbcExecutionBudgets budgets; private final JdbcIdentityPolicyPort identities;
    private final AgentRuntimeModelConfiguration profiles; private final JdbcModelBindings bindings; private final SessionTokenCounter tokens;
    private final SystemAgentCapabilitySource sources; private final SystemAgentRuntimeStore business;
    private final OpenAiModelPort models; private final SystemAgentModelInvocationAudit audit; private final SystemAgentPlatformToolProvider tools;
    private final AgentRuntimeProperties properties; private final ObjectMapper json; private final CoreRuntime core; private final AgentExecutionCodec codec;
    private final CodecLimits limits; private final AgentExecutionProgram program; private final ExecutionCoordinator<AgentExecutionProgram.Materials> coordinator;
    private final ConcurrentHashMap<RunRef, Frame> frames = new ConcurrentHashMap<>();
    private final ObjectProvider<ExecutionHostWorker> workers;
    private final ExecutionHostScheduler scheduler;
    private final Clock clock;
    private ScheduledFuture<?> heartbeat;
    private final AtomicBoolean closed = new AtomicBoolean();
    SystemAgentExecutions(JdbcExecutionStore store, JdbcExecutionBudgets budgets, JdbcIdentityPolicyPort identities,
            AgentRuntimeModelConfiguration profiles, JdbcModelBindings bindings, SessionTokenCounter tokens,
            SystemAgentCapabilitySource sources, SystemAgentRuntimeStore business, OpenAiModelPort models,
            SystemAgentModelInvocationAudit audit, SystemAgentPlatformToolProvider tools, AgentRuntimeProperties properties,
            ObjectMapper json, CoreRuntime core, ObjectProvider<ExecutionHostWorker> workers, ExecutionHostScheduler scheduler, Clock clock) {
        this.workers=Objects.requireNonNull(workers);this.scheduler=Objects.requireNonNull(scheduler);
        this.clock=Objects.requireNonNull(clock);
        this.store=Objects.requireNonNull(store); this.budgets=Objects.requireNonNull(budgets); this.identities=Objects.requireNonNull(identities);
        this.profiles=Objects.requireNonNull(profiles); this.bindings=Objects.requireNonNull(bindings); this.tokens=Objects.requireNonNull(tokens);
        this.sources=Objects.requireNonNull(sources); this.business=Objects.requireNonNull(business); this.models=Objects.requireNonNull(models);
        this.audit=Objects.requireNonNull(audit); this.tools=Objects.requireNonNull(tools); this.properties=Objects.requireNonNull(properties);
        this.json=Objects.requireNonNull(json).copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.core=Objects.requireNonNull(core); limits=new CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth());
        codec=new AgentExecutionCodec(limits); program=new AgentExecutionProgram(this,core,limits);
        coordinator=new ExecutionCoordinator<>(store,program,this,this,new ExecutionCoordinator.LocalPlans() {
            public <T> ExecutionCoordinator.LocalPlanResult<T> execute(Run run, ExecutionCoordinator.Step.PlanLocal command, java.util.function.BiFunction<Run,Material,ExecutionCoordinator.Adoption<T>> adopter) {
                throw new IllegalStateException("system_agent_plan_not_mounted");
            }
        });

    }

    /** 原业务来源、精确目录、预算和原输入一次冻结；重复受理不能重选模型或变更来源。 */
    Run admit(RunIdentity identity, VerifiedIdentity actor, SystemAgentModelProvisioner.Prepared prepared,
            SystemAgentCapabilitySource.SourceSnapshot source, String input) {
        requireOwner(identity.owner());
        if (!source.inputFingerprint().equals(SystemAgentCapabilitySource.inputFingerprint(input))) throw new IllegalArgumentException("system_agent_original_input_changed");
        authorize(actor,"session.execute",SessionPolicyResource.run(identity,Optional.empty()),identity.trace());
        var mounted=sources.tools(source,identity.owner()); var skills=sources.skillMounts(source);
        var available=source.mounts().stream().map(value -> new CapabilityRef(CapabilityType.valueOf(value.mount().capabilityType()),value.capabilityId(),value.capabilityVersionId())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var catalog=new Catalog(source,prepared.budget(),mounted,List.copyOf(skills),available);
        Material frozen=new Material(catalogRef(identity.runRef()),payload(CATALOG_SCHEMA,catalog));
        var message=new ModelMessage("system-agent-input:"+identity.causationInputId().value(),ModelRole.USER,input);
        var original=payload("system-agent-input.v1",Map.of("message",input));
        var inputMaterial=new Material(new ResultRef("execution-input:"+identity.runRef().runId()),codec.encodeInput(List.of(message),original));
        var context=program.initialContext(inputMaterial.reference()); var budget=prepared.budget();
        var resources=new ExecutionBudgetPort.Resources(budget.maxBusinessModelCalls(),0,budget.maxToolCalls(),budget.maxKnowledgeCalls(),budget.maxInputTokens(),budget.maxOutputTokens(),budget.maxTotalTokens());
        var limit=new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,resources,resources,resources,budget.maxWallTime().toSeconds(),budget.maxWallTime().toSeconds(),"system-agent-source:"+source.generation());
        var registration=new ExecutionBudgetPort.Registration(identity.runRef(),identity.runRef(),ExecutionBudgetPort.Role.PRIMARY,new ExecutionBudgetPort.Policy.Enforced(limit));
        var admitted=store.admit(new Admission(actor,identity,AgentExecutionCodec.digest(identity.runRef()+"\0"+frozen.content().canonicalJson()+"\0"+input),prepared.profile(),inputMaterial,context,registration,List.of(),List.of(frozen)));
        wake(identity.runRef()); return admitted.run();
    }

    /** 只读入口先验证当前完整owner和读取授权，恢复不会调用prepare/admit或另发外部请求。 */
    Optional<Run> query(AccessEnvelope access, RunRef reference) {
        var result=identities.verifyAccess(new AccessIdentityRequest(access.authenticationRef(),access.tenantId(),access.sessionTarget(),access.agentId(),access.subject(),access.channel(),access.source(),access.requestId(),access.traceId(),access.spanId()));
        if(!(result instanceof IdentityResult.Verified verified))throw new SecurityException("system_agent_query_identity_denied");
        var owner=verified.identity().owner(); requireOwner(owner);
        var resource=payload("skillforge.runtime.facade-access.v1",List.of(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId(),Long.toString(owner.requireUser().value()),"runtime.run.query","run",reference.runId(),"0",access.requestId().value()));
        if(!(identities.authorize(new AuthorizationRequest(verified.identity(),"runtime.run.query",resource,access.traceId(),access.spanId())) instanceof AuthorizationResult.Allowed))throw new SecurityException("system_agent_query_denied");
        return store.load(reference).map(run -> { if(!run.identity().owner().equals(owner))throw new SecurityException("system_agent_query_owner_mismatch"); return run; });
    }

    void cancel(Run run, VerifiedIdentity actor, InputId input) {
        var cancelled=store.cancel(new Cancel(actor,run.identity().runRef(),input,List.of()));
        if(cancelled.cancelled()){var frame=frames.get(run.identity().runRef());if(frame!=null)frame.cancelled=true;}
    }

    /** 最终输出来自该Run原完成事件；用量只聚合真实MODEL Attempt，未知分量保留null。 */
    LlmResult completed(Run run,String modelConfigId) {
        if(run.status()!=RunStatus.COMPLETED)throw new IllegalArgumentException("system_agent_result_not_completed");
        var terminal=store.event(run.identity().runRef(),AgentExecutionProgram.finishedEventId(run.identity().runRef())).orElseThrow(()->new IllegalStateException("system_agent_terminal_event_missing"));
        if(!terminal.run().equals(run.identity().runRef())||terminal.revision()!=run.revision()
                ||!terminal.event().id().equals(AgentExecutionProgram.finishedEventId(run.identity().runRef()))
                ||!terminal.event().type().equals(AgentExecutionProgram.COMPLETED_EVENT))throw new IllegalStateException("system_agent_terminal_event_mismatch");
        var content=read(terminal.event().data(),AgentExecutionProgram.COMPLETED_SCHEMA,com.fasterxml.jackson.databind.JsonNode.class);
        if(!content.path("status").asText().equals("COMPLETED"))throw new IllegalStateException("system_agent_terminal_status_mismatch");
        String output=content.path("text").asText();
        if(output==null||output.isBlank())throw new IllegalStateException("system_agent_runtime_output_empty");
        var modelKind=profile(run).callKind();
        long[] sums=new long[5];boolean[] complete={true,true,true,true,true};boolean any=false;long ordinal=0;
        while(true){var actions=store.actions(run.identity().runRef(),ordinal,100);if(actions.isEmpty())break;
            for(var action:actions){ordinal=action.ordinal();if(action.kind()!=Kind.MODEL)continue;any=true;
                var attempt=store.attempt(run.identity().runRef(),action.currentAttempt()).orElseThrow();
                if(attempt.result().isEmpty()){Arrays.fill(complete,false);continue;}

                var outcome=core.decodeModelOutcome(codec.decodeOutcome(material(run,attempt.result().orElseThrow()).content()),modelKind);
                if(!(outcome instanceof ModelResult.Completed actual)){Arrays.fill(complete,false);continue;}
                var usage=actual.usage();var fields=List.of(usage.inputTokens(),usage.cachedInputTokens(),usage.cacheWriteInputTokens(),usage.outputTokens(),usage.reasoningOutputTokens());
                for(int index=0;index<fields.size();index++)if(fields.get(index).isPresent())sums[index]=Math.addExact(sums[index],fields.get(index).getAsLong());else complete[index]=false;
            }if(actions.size()<100)break;
        }
        Long input=any&&complete[0]?sums[0]:null, outputTokens=any&&complete[3]?sums[3]:null;
        Long cached=any&&complete[1]?sums[1]:null, written=any&&complete[2]?sums[2]:null, reasoning=any&&complete[4]?sums[4]:null;
        boolean reported=input!=null||outputTokens!=null||cached!=null||written!=null||reasoning!=null;
        var usage=new LlmUsage(input,cached,written,outputTokens,reasoning,
                input!=null&&outputTokens!=null?Math.addExact(input,outputTokens):null,
                reported?UsageSource.PROVIDER_REPORTED:UsageSource.UNKNOWN,null);
        return new LlmResult(output,modelConfigId,usage);
    }

    @Override public AgentExecutionProgram.Configuration configuration(Run run,ResultRef input) {
        var frame=frame(run);if(frame.configuration!=null)return frame.configuration;
        var frozen=read(material(run,catalogRef(run.identity().runRef())).content(),CATALOG_SCHEMA,Catalog.class);
        var source=business.source(run.identity().owner(),run.identity().causationInputId()).orElseThrow();
        if(!sources.decode(source.sourceData).equals(frozen.source()))throw new IllegalStateException("system_agent_source_changed");
        var profile=profile(run);var model=bindings.findForOwner(profile.binding(),run.identity().agentId()).orElseThrow();
        if(!model.strategy().equals(profile.strategy()))throw new IllegalStateException("system_agent_model_strategy_changed");
        var actor=identities.originalVerification(run.identity().owner(),run.actorVerificationRef()).orElseThrow();var messages=new ArrayList<ModelMessage>();
        messages.add(new ModelMessage("system-agent-system",ModelRole.SYSTEM,profile.systemPrompt().render()));var originals=codec.decodeInput(material(run,input).content());
        if(originals.stream().anyMatch(value -> value.role()==ModelRole.SYSTEM))throw new IllegalStateException("system_agent_input_system_role");messages.addAll(originals);
        var policy=budgets.snapshot(run.identity().runRef()).registration().policy();policy.requireEnforced();
        var snapshot=new CapabilitySnapshotRef(run.identity().owner().sessionRef(),run.identity().causationInputId(),catalogRef(run.identity().runRef()).value(),frozen.source().generation());
        frame.binding=model;frame.catalog=frozen;
        var modelIdentities=frozen.tools().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(value->value.target().handle().value(),value->value.target().handle().value()));
        frame.configuration=new AgentExecutionProgram.Configuration(profile,model.target(),modelTools(profile,frozen.tools(),modelIdentities,frozen.skills()),frozen.tools(),
                LoopBudget.from(policy),snapshot,catalogRef(run.identity().runRef()),List.copyOf(messages),actor,List.of(),frozen.skills(),frozen.available(),List.of(),PlanPort.State.absent(),false);
        return frame.configuration;
    }
    @Override public Material material(Run run,ResultRef reference){return store.material(run.identity().runRef().tenantId(),reference).orElseThrow(()->new IllegalStateException("system_agent_material_missing"));}
    @Override public List<Optional<Material>> materials(Run run,List<ResultRef> references){return store.materials(run.identity().runRef().tenantId(),references);}

    @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration,List<ModelMessage> messages, List<com.skillforge.runtime.api.port.ModelPort.ModelTool> tools){return tokens.measure(configuration.profile().tokenizerProfileRef(),configuration.target(),messages,tools);}
    @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(Run run,AgentExecutionProgram.Configuration configuration,List<ModelMessage> messages){if(messages.stream().flatMap(value->value.contents().stream()).anyMatch(ModelContent.Image.class::isInstance))throw new IllegalStateException("system_agent_media_not_mounted");return new AgentExecutionModelMedia.Prepared(messages,List.of());}
    @Override public Duration elapsed(Run run){var frame=frame(run);return Duration.ofMillis(frame.baselineMillis).plusNanos(Math.max(0,System.nanoTime()-frame.startedNanos));}
    @Override public AgentExecutionTime.Sample sampleModelTime(Run run){return AgentExecutionTime.sample(clock);}

    /** 派发与当前IAM使用Store借用的同一事务，不从模型参数补造身份或工具挂载。 */
    boolean allowed(SqlSession session,Run run,VerifiedIdentity actor,Prepared prepared){
        var frame=frame(run);var configuration=frame.configuration;if(configuration==null||!configuration.actor().equals(actor))return false;
        String action;TypedPayload resource;
        if(prepared.kind()==Kind.MODEL){var request=codec.decodeModelRequest(prepared).request();if(!request.target().equals(configuration.target())||!request.requestedStrategy().equals(configuration.profile().strategy())||!prepared.capability().equals(configuration.target().capabilities().capabilityFactRef()))return false;
            action=configuration.profile().callKind()==PortalCallKind.MODEL_STREAM?"model.stream":"model.generate";resource=ModelPolicyResource.dispatch(run.identity(),request.target().binding(),request.requestedStrategy());
        }else if(prepared.kind()==Kind.TOOL){var request=codec.decodeTool(prepared.request().content());var mounted=SessionToolDirectory.requirePrepared(configuration.tools(),request.target().handle().value());
            if(!mounted.target().equals(request.target())||!mounted.binding().equals(request.binding())||!prepared.capability().equals(configuration.capabilityRef())||!sources.tools(frame.catalog.source(),run.identity().owner()).contains(mounted))return false;
            action="tool.invoke";resource=ToolPolicyResource.invocation(run.identity(),request.binding(),request.target(),new IdempotencyKey(prepared.action().actionId()));
        }else return false;
        return identities.authorize(session,new AuthorizationRequest(actor,action,resource,run.identity().trace().traceId(),run.identity().trace().spanId())) instanceof AuthorizationResult.Allowed;
    }
    boolean allowedControl(SqlSession session,Run run,VerifiedIdentity actor,JdbcExecutionStore.Control operation,InputId input){if(!actor.owner().equals(run.identity().owner()))return false;String action=operation==JdbcExecutionStore.Control.CANCEL?"session.cancel":"session.execute";return identities.authorize(session,new AuthorizationRequest(actor,action,SessionPolicyResource.run(run.identity(),operation==JdbcExecutionStore.Control.CANCEL?Optional.of(input):Optional.empty()),run.identity().trace().traceId(),run.identity().trace().spanId())) instanceof AuthorizationResult.Allowed;}
    void confirm(SqlSession session,RunRef run){var frame=frames.get(run);if(frame!=null&&!frame.released)budgets.recordActivity(session,run,frame.interval,frame.startedAt,frame.startedAt.plusNanos(Math.max(0,System.nanoTime()-frame.startedNanos)));}

    @Override public ExecutionCoordinator.CapabilityResult invoke(DispatchResult.Registered registered,Prepared prepared){
        var run=registered.run();var frame=frame(run);var control=new InvocationControl(Instant.now().plus(prepared.kind()==Kind.MODEL?frame.binding.target().timeout():codec.decodeTool(prepared.request().content()).target().timeout()),()->frame.cancelled()||frame.ownershipLost());
        var kind=prepared.kind()==Kind.MODEL?frame.configuration.profile().callKind():PortalCallKind.TOOL_INVOKE;
        var dispatch=new ProviderDispatch(run.identity().runRef(),prepared.action(),prepared.action().actionId(),registered.attempt().id(),kind,prepared.request().reference(),prepared.capability(),prepared.fingerprint(),registered.attempt().fence());
        var context=ProviderCallContext.dispatched(new PortCallContext(run.identity(),registered.actor(),control),dispatch);
        RegisteredExecutionCall.require(registered, prepared, context, prepared.kind());
        if(prepared.kind()==Kind.MODEL){
            var request=codec.decodeModelRequest(prepared, refs -> store.materials(run.identity().runRef().tenantId(), refs)).request();var call=new ModelCall(context,request.requestedStrategy(),request.target(),prepared.request().reference(),request.messages(),request.tools(),request.toolChoice(),request.response(),request.generation(),request.reasoning(),request.budgetCategory());
            if(!store.authorizeSend(registered,prepared))throw new IllegalStateException("system_agent_model_send_denied");
            var binding=frame.binding;var provider=binding.providerProfile();var transport=new OpenAiModelPort.Binding(binding.target(),binding.strategy(),binding.endpoint(),binding.encryptedCredential(),new OpenAiModelPort.ProviderProfile(provider.providerId(),provider.serviceId(),provider.profileVersion(),ModelProviderCatalog.ModelProtocol.fromId(provider.protocol()),ModelProviderCatalog.AuthenticationScheme.fromId(provider.authenticationScheme()),provider.dialectVersion()));
            var exact=new ObservedModelInvocationAudit(audit.dispatched(registered,prepared,binding));var model=models.forDispatch(call,transport,exact);ModelResult actual=kind==PortalCallKind.MODEL_STREAM?stream(model,call):model.generate(call);
            var status=actual instanceof ModelResult.Completed?ResultKind.SUCCEEDED:actual instanceof ModelResult.Failed?ResultKind.FAILED:ResultKind.UNKNOWN;
            var failedUsage=exact.observed().map(ObservedModelInvocationAudit.ObservedUsage::usage);
            var usage=actual instanceof ModelResult.Completed completed?ExecutionBudgetPort.Usage.reported(completed.usage().inputTokens(),completed.usage().outputTokens()):actual instanceof ModelResult.Failed&&failedUsage.isPresent()?ExecutionBudgetPort.Usage.reported(failedUsage.orElseThrow().inputTokens(),failedUsage.orElseThrow().outputTokens()):ExecutionBudgetPort.Usage.unreported();
            return new ExecutionCoordinator.CapabilityResult(status,result(registered,core.encodeOutcome(kind,actual)),Optional.empty(),Optional.empty(),usage);
        }
        if(prepared.kind()!=Kind.TOOL)throw new IllegalArgumentException("system_agent_capability_not_mounted");
        var request=codec.decodeTool(prepared.request().content());var call=new ToolCall(context,request.binding(),request.target(),request.arguments(),new IdempotencyKey(prepared.action().actionId()));
        var actual=request.continuation().isPresent()?tools.resume(call.binding(),new ToolContinuationCall(context,call.binding(),call.target(),call.arguments(),call.idempotencyKey(),request.continuation().orElseThrow().responses(),request.continuation().orElseThrow().requestState())):tools.invokeRegistered(registered,prepared,call,()->store.authorizeSend(registered,prepared));
        Optional<Material> feedback;try{feedback=codec.safeToolFeedback(request,actual).map(value->new Material(new ResultRef("execution-feedback:"+registered.attempt().id()),value));}catch(AgentExecutionCodec.ToolOutputRejected rejected){feedback=Optional.empty();}
        var status=actual instanceof ToolOutcome.Succeeded?ResultKind.SUCCEEDED:actual instanceof ToolOutcome.Failed?ResultKind.FAILED:actual instanceof ToolOutcome.InputRequired?ResultKind.INPUT_REQUIRED:ResultKind.UNKNOWN;
        return new ExecutionCoordinator.CapabilityResult(status,result(registered,core.portalResults(new SessionRuntimePayloads(),new SessionRuntimePayloads(),new SessionRuntimePayloads()).encodeTool(PortalCallKind.TOOL_INVOKE,actual)),feedback,Optional.empty(),ExecutionBudgetPort.Usage.unreported());
    }
    private ModelResult stream(OpenAiModelPort model, ModelCall call) {
        var result=new CompletableFuture<ModelResult>();var subscription=new java.util.concurrent.atomic.AtomicReference<Flow.Subscription>();
        model.stream(call).subscribe(new Flow.Subscriber<ModelStreamEvent>() {
            public void onSubscribe(Flow.Subscription value){subscription.set(value);value.request(Long.MAX_VALUE);}
            public void onNext(ModelStreamEvent event){switch(event){
                case ModelStreamEvent.Completed value -> finish(value.result());
                case ModelStreamEvent.Failed value -> finish(new ModelResult.Failed(value.failure(),value.retryAt()));
                case ModelStreamEvent.OutcomeUnknown value -> finish(new ModelResult.OutcomeUnknown(value.failure()));
                default -> { }
            }}
            private void finish(ModelResult value){if(!result.complete(value))result.obtrudeException(new IllegalStateException("system_agent_model_multiple_terminals"));}
            public void onError(Throwable error){result.completeExceptionally(error);}
            public void onComplete(){if(!result.isDone())result.completeExceptionally(new IllegalStateException("system_agent_model_stream_terminal_missing"));}
        });
        try{return result.get(Math.addExact(call.target().timeout().toMillis(),1000),TimeUnit.MILLISECONDS);}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("system_agent_model_stream_interrupted",stopped);}
        catch(ExecutionException|TimeoutException failure){throw new IllegalStateException("system_agent_model_stream_unresolved",failure);}
        finally{if(subscription.get()!=null)subscription.get().cancel();}
    }
    private Material result(DispatchResult.Registered registered,PortalOutcomeSnapshot actual){return new Material(new ResultRef("execution-result:"+registered.attempt().id()),codec.encodeOutcome(actual));}

    /** 仅公开该宿主真正消费的业务工具与Skill，不声明不存在的计划或交互入口。 */
    static List<ModelTool> modelTools(SessionPreparations.Profile profile,List<SessionToolDirectory.Entry> tools,Map<String,String> identities,List<SkillActivation.Mount> skills) {
        return AgentExecutionProgram.modelTools(profile,tools,identities,List.of(),skills,List.of(),List.of()).stream()
                .filter(tool->tool.kind()==ModelToolKind.TOOL_CALL||NativeAgentTools.find(tool.wireName())
                        .filter(control->Set.of("activate_skill","read_skill_resource").contains(control.actionType())).isPresent()).toList();
    }

    @Override public ExecutionCoordinator.Control open(Run run){var frame=new Frame(run,budgets.snapshot(run.identity().runRef()).runActiveMillis());if(frames.putIfAbsent(run.identity().runRef(),frame)!=null)throw new IllegalStateException("system_agent_frame_exists");return frame;}
    @Override public String id(){return "platform-system-agent";}
    @Override public boolean owns(Run run){
        if(!run.identity().runRef().tenantId().value().equals(SystemAgentRuntimeContracts.TENANT_ID)||!run.identity().runRef().runId().startsWith("system-agent-run:")||!run.identity().owner().sessionRef().sessionId().startsWith("system-agent:"))return false;
        var frozen=store.material(run.identity().runRef().tenantId(),catalogRef(run.identity().runRef()));if(frozen.isEmpty()||!frozen.orElseThrow().content().schemaId().equals(CATALOG_SCHEMA))return false;
        var catalog=read(frozen.orElseThrow().content(),CATALOG_SCHEMA,Catalog.class);
        var original=business.source(run.identity().owner(),run.identity().causationInputId());
        var profile=profiles.preparationProfile(run.identity().runRef().tenantId(),run.configuration());
        return run.identity().runRef().runId().equals("system-agent-run:"+catalog.source().runId())
                &&run.identity().owner().sessionRef().sessionId().equals("system-agent:"+catalog.source().runId())
                &&run.identity().causationInputId().value().equals("system-agent-input:"+catalog.source().runId())
                &&run.identity().owner().requireUser().value()==SystemAgentRuntimeContracts.END_USER_ID&&original.isPresent()
                && sources.decode(original.orElseThrow().sourceData).equals(catalog.source())&&profile.isPresent()
                &&profile.orElseThrow().reference().equals(run.configuration())
                &&profile.orElseThrow().systemPrompt().modules().stream().anyMatch(value->value.sourceVersion().equals(catalog.source().promptSourceRef()));
    }
    @Override public ExecutionStore store(){return store;}
    @Override public synchronized void begin(RunRef run){if(closed.get())throw new IllegalStateException("system_agent_closed");if(heartbeat==null)heartbeat=scheduler.scheduleWithFixedDelay(this::renew,Duration.ofNanos(Math.max(1,Math.min(Duration.ofSeconds(10).toNanos(),properties.getWorkerLeaseDuration().toNanos()/3))));}
    @Override public void end(RunRef run){ }
    @Override public ExecutionCoordinator.Outcome drive(Claim claim){return coordinator.drive(claim);}
    @Override public void committed(Run run){ }
    @Override public void recover(TenantId tenant,int limit){ }
    @Override public void cancelled(RunRef run){var frame=frames.get(run);if(frame!=null)frame.cancelled=true;}
    boolean wake(RunRef run){return !closed.get()&&workers.getObject().wake(run);}
    private void renew(){for(var frame:frames.values())synchronized(frame){if(frame.released||frame.cancelled||frame.lost)continue;try{var run=TenantContext.callWithTenant(frame.run.tenantId().value(),()->store.renew(frame.run,frame.lease,properties.getWorkerLeaseDuration()));if(run.cancelled())frame.cancelled=true;else if(run.lease().isEmpty()||run.fence()!=frame.lease.fencingToken()||!run.lease().orElseThrow().claimId().equals(frame.lease.claimId()))frame.lost=true;else frame.lease=run.lease().orElseThrow();}catch(RuntimeException failure){frame.lost=true;}}}
    @Override public synchronized void close(){if(!closed.compareAndSet(false,true))return;if(heartbeat!=null)heartbeat.cancel(false);frames.values().forEach(frame->{synchronized(frame){frame.lost=true;}});}
    private SessionPreparations.Profile profile(Run run){return profiles.preparationProfile(run.identity().runRef().tenantId(),run.configuration()).orElseThrow(()->new IllegalStateException("system_agent_profile_missing"));}
    private Frame frame(Run run){var frame=frames.get(run.identity().runRef());if(frame==null||frame.released)throw new IllegalStateException("system_agent_frame_missing");return frame;}
    private void authorize(VerifiedIdentity actor,String action,TypedPayload resource,TraceEnvelope trace){if(!(identities.authorize(new AuthorizationRequest(actor,action,resource,trace.traceId(),trace.spanId())) instanceof AuthorizationResult.Allowed))throw new SecurityException("system_agent_execution_denied");}
    private static void requireOwner(SessionOwner owner){if(!owner.sessionRef().tenantId().value().equals(SystemAgentRuntimeContracts.TENANT_ID)||owner.requireUser().value()!=SystemAgentRuntimeContracts.END_USER_ID||!owner.sessionRef().sessionId().startsWith("system-agent:"))throw new SecurityException("system_agent_owner_rejected");}
    private static ResultRef catalogRef(RunRef run){return new ResultRef("system-agent-catalog:"+run.runId());}
    private TypedPayload payload(String schema,Object value){try{return new TypedPayload(schema,json.writeValueAsString(value));}catch(Exception failure){throw new IllegalArgumentException("system_agent_payload_invalid",failure);}}
    private <T>T read(TypedPayload payload,String schema,Class<T> type){if(!payload.schemaId().equals(schema))throw new IllegalArgumentException("system_agent_schema_invalid");try{return json.readValue(payload.canonicalJson(),type);}catch(Exception failure){throw new IllegalArgumentException("system_agent_payload_invalid",failure);}}
    record Catalog(SystemAgentCapabilitySource.SourceSnapshot source,com.skillforge.runtime.starter.AgentRuntimeAdmissionConfiguration.Budget budget,List<SessionToolDirectory.Entry> tools,List<SkillActivation.Mount> skills,Set<CapabilityRef> available){Catalog{Objects.requireNonNull(source);Objects.requireNonNull(budget);tools=List.copyOf(tools);skills=List.copyOf(skills);available=Set.copyOf(available);}}
    private final class Frame implements ExecutionCoordinator.Control{private final RunRef run;private final String interval="system-agent-activity:"+UUID.randomUUID();private final Instant startedAt=Instant.now();private final long startedNanos=System.nanoTime();private final long baselineMillis;private LeaseFence lease;private volatile boolean cancelled,lost,released;private AgentExecutionProgram.Configuration configuration;private JdbcModelBindings.Binding binding;private Catalog catalog;private Frame(Run run,long baseline){this.run=run.identity().runRef();lease=run.lease().orElseThrow();baselineMillis=baseline;}public boolean cancelled(){return cancelled||released;}public boolean ownershipLost(){return lost||released;}public synchronized void close(){if(released)return;released=true;frames.remove(run,this);}}
}
