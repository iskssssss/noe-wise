package com.skillforge.institution.runtime;

import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse.Collaboration;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.SessionCollaboration;
import com.skillforge.runtime.session.SessionGoal;
import com.skillforge.runtime.session.SessionHumanAssistance;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanAssistance;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanReceipt;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 产品Plan、持续目标和人工协助的领域入口；执行只进入唯一typed Gateway。 */
@Service
public class AgentRuntimeInboundGateway implements ChatRuntimeGateway {
    static final String CHAT_INTEGRATION_ID=AgentRuntimeHostContracts.CHAT_INTEGRATION_ID;
    static final ResultRef PREPARATION_PROFILE=AgentRuntimeHostContracts.PREPARATION_PROFILE;
    private final AgentRuntimeExecutionGateway executions;
    private final ExecutionStore store;
    private final AgentRunBindings bindings;
    private final SessionCollaboration collaboration;
    private final SessionHumanAssistance humans;
    private final SessionLogPort log;
    private final Clock clock;
    private final AgentRuntimeGoalDriver goals;
    private final DeliverableService deliverables;
    private final WorkflowPortal workflows;
    private final AgentRuntimeExecutionProjection projection;

    public AgentRuntimeInboundGateway(AgentRuntimeExecutionGateway executions,ExecutionStore store,AgentRunBindings bindings,
            SessionLogPort log,Clock clock,AgentRuntimeGoalDriver goals,DeliverableService deliverables,
            WorkflowPortal workflows,AgentRuntimeExecutionProjection projection) {
        this.executions=Objects.requireNonNull(executions);this.store=Objects.requireNonNull(store);this.bindings=Objects.requireNonNull(bindings);
        this.log=Objects.requireNonNull(log);this.clock=Objects.requireNonNull(clock);this.goals=Objects.requireNonNull(goals);
        this.deliverables=Objects.requireNonNull(deliverables);this.workflows=Objects.requireNonNull(workflows);this.projection=Objects.requireNonNull(projection);
        collaboration=new SessionCollaboration(log);humans=new SessionHumanAssistance(log);
    }
    @Override public ChatResult submit(Submission submission) {
        return executions.submit(submission);
    }
    public ChatResult cancelChat(SessionView session,String runId){return executions.cancelChat(session,runId);}
    public ChatResult chatRunStatus(SessionView session,String runId){return executions.chatRunStatus(session,runId);}
    public Collaboration collaboration(SessionView session) {
        var actor=executions.authorizeSession(session,input("collaboration"),"runtime.session.query");return publicCollaboration(collaboration.read(actor.owner()));
    }
    public Collaboration selectPlan(SessionView session,String commandId,boolean active) {
        var input=new InputId(commandId);var actor=executions.authorizeSession(session,input,"runtime.plan.select");
        collaboration.select(actor,bindings.currentPrimaryAgent(actor.owner()),commandId,active,trace(input),clock.instant());
        return publicCollaboration(collaboration.read(actor.owner()));
    }
    private static Collaboration publicCollaboration(com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.Snapshot value) {
        return new Collaboration(value.active(),value.pending(),value.selectedActive());
    }
    public Optional<AgentRuntimeGoalDriver.Status> goal(SessionView session) {
        var actor=executions.authorizeSession(session,input("goal-query"),"runtime.session.query");return goals.read(actor.owner()).map(value->publication(actor.owner(),value));
    }
    public AgentRuntimeGoalDriver.Status createGoal(SessionView session,String commandId,String objective,int maxRounds) {
        if(objective==null||objective.isBlank()||objective.length()>SessionGoal.MAX_OBJECTIVE_LENGTH||maxRounds<SessionGoal.MIN_ROUNDS||maxRounds>SessionGoal.MAX_ROUNDS)
            throw new ApiException(HttpStatus.BAD_REQUEST,"目标或轮次上限无效");
        var input=new InputId("goal-start:"+commandId);var actor=executions.authorizeSession(session,input,"runtime.session.query");
        var expected=new RunRef(actor.owner().sessionRef().tenantId(),"run:"+input.value());var prior=store.load(expected);
        if(prior.isEmpty()&&goals.read(actor.owner()).filter(value->value.goal().state()==SessionGoal.State.ACTIVE||value.goal().state()==SessionGoal.State.PAUSED).isPresent())
            throw new ApiException(HttpStatus.CONFLICT,"当前会话已有未结束目标");
        var callback=callback(session,actor.owner());
        var goalAgent=prior.map(value->value.identity().agentId()).orElseGet(()->bindings.currentPrimaryAgent(actor.owner()));
        try{return publication(actor.owner(),goals.createFromAdmission(actor.owner(),goalAgent,commandId,objective,maxRounds,callback,()->{
            var admitted=callback.submitRound(input,com.skillforge.prompts.ChatPrompts.goalRoundInstructions()+"\n"+objective);
            if(!admitted.accepted())throw new ApiException(HttpStatus.CONFLICT,"goal_admission_rejected");
            return owned(actor.owner(),admitted.runRef().orElseThrow()).orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"goal_initial_run_unavailable")).identity();
        }));}
        catch(RuntimeException failure){if(prior.isEmpty())try{callback.cancel(expected);}catch(RuntimeException cancellation){failure.addSuppressed(cancellation);}throw failure;}
    }
    public AgentRuntimeGoalDriver.Status controlGoal(SessionView session,String commandId,long revision,boolean resume) {
        var input=new InputId(commandId);var actor=executions.authorizeSession(session,input,"runtime.session.query");var owner=actor.owner();
        if(resume&&goals.read(owner).map(value->deliverables.completionView(owner,value.goal()).pending()).orElse(false))throw new ApiException(HttpStatus.CONFLICT,"goal_completion_pending");
        var primary=bindings.currentPrimaryAgent(owner);var callback=callback(session,owner);
        var status=resume?goals.resume(owner,primary,revision,commandId,input,trace(input),callback)
                :goals.pause(owner,primary,revision,commandId,input,trace(input),"user_paused",callback);
        return publication(owner,status);
    }
    private AgentRuntimeGoalDriver.Status publication(SessionOwner owner,AgentRuntimeGoalDriver.Status status) {
        var completion=deliverables.completionView(owner,status.goal());return new AgentRuntimeGoalDriver.Status(status.goal(),status.continuationEnabled(),completion.pending(),completion.publication());
    }
    private AgentRuntimeGoalDriver.Callback callback(SessionView session,SessionOwner owner) {
        return new AgentRuntimeGoalDriver.Callback() {
            public boolean completionPending(RunRef run){return inGoalTenant(()->deliverables.completionPending(run));}
            public Optional<AgentRuntimeGoalDriver.GoalRun> latestRun(RunRef reference) {
                return inGoalTenant(()->{
                executions.authorizeSession(session,input("goal-run-query"),"runtime.session.query");
                return owned(owner,reference).map(run->new AgentRuntimeGoalDriver.GoalRun(owner,reference,run.status()));
                });
            }
            public boolean idle() {
                return inGoalTenant(()->{
                executions.authorizeSession(session,input("goal-idle"),"runtime.session.query");long after=0;var seen=new HashSet<RunRef>();
                while(true){var page=store.events(owner.sessionRef(),after,100);for(var event:page){
                    if(event.sequence()<=after)throw new IllegalStateException("goal_event_sequence_invalid");after=event.sequence();
                    if(seen.add(event.run())&&owned(owner,event.run()).filter(run->!terminal(run.status())).isPresent())return false;
                }if(page.size()<100)return true;}
                });
            }
            public AgentRuntimeGoalDriver.RoundAdmission submitRound(InputId input,String prompt) {
                java.util.function.Supplier<AgentRuntimeGoalDriver.RoundAdmission> submit=()->{
                    var context=new LinkedHashMap<String,Object>();context.put("input_id",input.value());context.putAll(requester(session,owner));
                    var request=new ChatRequest(prompt,session.getSessionId(),session.getAgentId()==null?null:session.getAgentId().toString(),session.getTeamId(),null,null,null,null,input.value(),null,context);
                    var accepted=executions.submitGoalRound(new Submission(request,session,bindings.currentPrimaryAgent(owner).value(),owner.requireUser().value(),null));
                    var reference=new RunRef(owner.sessionRef().tenantId(),accepted.executionId());owned(owner,reference).orElseThrow(()->new IllegalStateException("goal_round_source_missing"));
                    return new AgentRuntimeGoalDriver.RoundAdmission(owner.sessionRef(),input,Optional.of(reference),true);
                };
                return inGoalTenant(()->input.value().startsWith("goal-round:")?bindings.withContinuationRequester(owner,submit):submit.get());
            }
            public void wake(RunRef reference) { inGoalTenant(()->{executions.wakeGoalRound(reference);return null;}); }
            public void cancel(RunRef reference) {inGoalTenant(()->{var run=owned(owner,reference);if(run.isPresent()&&!terminal(run.orElseThrow().status()))executions.cancelChat(session,reference.runId());return null;});}
            /** 调度线程不继承请求上下文；仅使用已受理 owner，并在回调结束恢复原租户。 */
            private <T> T inGoalTenant(java.util.function.Supplier<T> operation) {
                String tenant=owner.sessionRef().tenantId().value();
                String previous=com.skillforge.common.context.TenantContext.getTenantId();
                if(previous!=null&&!previous.equals(tenant))throw new SecurityException("database_tenant_scope_mismatch");
                try {
                    com.skillforge.common.context.TenantContext.setTenantId(tenant);
                    return operation.get();
                } finally {
                    if(previous==null)com.skillforge.common.context.TenantContext.clear();
                    else com.skillforge.common.context.TenantContext.setTenantId(previous);
                }
            }
        };
    }
    private Map<String,Object> requester(SessionView session,SessionOwner owner) {
        if(session.getChannelId()!=null)return Map.of("delegation_channel_id",session.getChannelId().toString());
        var member=com.skillforge.common.context.RequestContext.getUserId();return member!=null&&!member.isBlank()?Map.of("delegation_requester_id",member):bindings.requesterContext(owner);
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void requireWorkflowExecution(SessionView session,String executionId) {
        var actor=executions.authorizeSession(session,input("handoff-workflow"),"runtime.session.query");
        if(workflows.query(new WorkflowPortal.WorkflowQuery(actor,new ExecutionRef(actor.owner().sessionRef().tenantId(),executionId))).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND,"关联工作流执行不存在或不属于当前会话");
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public HumanReceipt humanAssistance(SessionView session,HumanAssistance command) {
        var actor=executions.authorizeSession(session,new InputId(command.commandId()),"runtime.session.human-assistance");
        var receipt=humans.receive(actor.owner(),bindings.currentPrimaryAgent(actor.owner()),command,trace(new InputId(command.commandId())),clock.instant());reconcileHuman(session);return receipt;
    }
    public Optional<HumanReceipt> humanReceipt(SessionView session,HumanAssistance command){return humans.receipt(TeamWorkService.owner(session),command);}
    public void reconcileHuman(SessionView session){projection.reconcileHuman(session,log);}
    private Optional<ExecutionStore.Run> owned(SessionOwner owner,RunRef reference){return store.load(reference).map(run->{if(!run.identity().owner().equals(owner)||!run.identity().runRef().equals(reference))throw new SecurityException("execution_session_owner_mismatch");return run;});}
    private static boolean terminal(RunStatus status){return Set.of(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.CANCELLED,RunStatus.TERMINATED).contains(status);}
    private static InputId input(String purpose){return new InputId(purpose+":"+UUID.randomUUID());}
    private static TraceEnvelope trace(InputId input){return new TraceEnvelope(input.value(),"span:"+input.value(),input,Optional.empty());}
}
