package com.skillforge.institution.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.institution.attachment.SessionAttachmentService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import com.skillforge.runtime.session.spi.SessionDelegations.*;
import com.skillforge.runtime.starter.*;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Agent 提供委派配置，工作室可收窄范围；子执行、等待及幂等由 Runtime 管理。 */
@Service
public final class AgentDelegationHost implements Host {
    private final com.skillforge.institution.artifact.DelegationDeliverableFacts deliverableFacts;
    private final SessionAttachmentService attachments;
    private final AgentRunBindings bindings;
    private final com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore execution;
    private final AgentRuntimeTrustedAccess trusted;
    private final AgentRuntimeHostProvisioner provisioner;
    private final ServicePrincipalMapper principals;
    private final JdbcIdentityPolicyPort identities;
    private final com.skillforge.runtime.adapter.jdbc.session.JdbcSessionDelegations store;
    private final AgentExecutionProgram program;
    private final AgentExecutionCodec codec;
    private final SessionExecutionDelegations delegationCodec;
    private final AgentRuntimeExecutionCatalog catalog;
    private final String workerId;
    private final ObjectMapper json;
    private final Clock clock;
    private final com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort works;
    private final com.skillforge.institution.team.TeamResultService results;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionWorker> worker;
    private final org.springframework.beans.factory.ObjectProvider<SessionDelegationEngine> engine;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionSteering> steering;
    private final ExecutionBudgetPort budgets;

    public AgentDelegationHost(AgentRunBindings bindings, com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore execution,
            AgentRuntimeTrustedAccess trusted, AgentRuntimeHostProvisioner provisioner,
            ServicePrincipalMapper principals, JdbcIdentityPolicyPort identities,
            com.skillforge.runtime.adapter.jdbc.session.JdbcSessionDelegations store, ObjectMapper json, Clock clock,
            AgentExecutionProgram program, AgentExecutionCodec codec, AgentRuntimeExecutionCatalog catalog,
            AgentRuntimeProperties properties,
            com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort works,
            com.skillforge.institution.team.TeamResultService results,
            org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionWorker> worker,
            org.springframework.beans.factory.ObjectProvider<SessionDelegationEngine> engine,
            org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionSteering> steering,
            ExecutionBudgetPort budgets,
            com.skillforge.institution.artifact.DelegationDeliverableFacts deliverableFacts, SessionAttachmentService attachments) {
        this.deliverableFacts = deliverableFacts;
        this.attachments = Objects.requireNonNull(attachments);
        this.bindings = bindings; this.execution = execution; this.trusted = trusted;
        this.provisioner = provisioner; this.principals = principals; this.identities = identities;
        this.program = program; this.codec = codec; this.catalog = catalog;
        this.workerId = properties.getWorkerId();
        this.delegationCodec = new SessionExecutionDelegations(new com.skillforge.runtime.json.CodecLimits(
                properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
        this.store = store; this.json = com.skillforge.institution.runtime.SessionSubjectJson.configure(json.copy()).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); this.clock = clock;
        this.works = works; this.results = results; this.worker = worker; this.engine = engine;
        this.steering = Objects.requireNonNull(steering);
        this.budgets = Objects.requireNonNull(budgets);
    }

    @Override public List<Target> targets(RunIdentity parent) {
        return targets(parent, bindings.find(parent.runRef()).orElseThrow(() -> new SecurityException("delegation_parent_missing")));
    }
    /** 同一本地命令复用已加载冻结范围；每次目录披露仍执行当前scope及成员IAM。 */
    public List<Target> targets(RunIdentity parent, AgentRunBindings.Binding current) {
        requireParent(parent, current);
        return current.scope().directory().values().stream().filter(member -> !member.agentId().equals(parent.agentId().value()))
                .filter(member -> accessible(current, member.agentId()))
                .sorted(Comparator.comparing(com.skillforge.institution.team.TeamDefinition.Member::agentId))
                .map(member -> new Target(targetHandle(member.agentId()), new AgentId(member.agentId()), member.name(), member.roleDescription(),
                        member.capabilities().stream().map(capability -> new Capability(capability.type(), capability.name(), capability.description())).toList(),
                        bindings.targetSnapshot(current,member.agentId()))).toList();
    }

    /** 仅投影已加载原Run的冻结目录上界，不查询当前IAM；实际披露和派发仍由targets/requireDispatch复核。 */
    static List<String> frozenTargetHandles(AgentRunBindings.Binding binding) {
        Objects.requireNonNull(binding);
        var frozen = binding.scope().delegation();
        if (binding.role() != AgentRunBindings.Role.PRIMARY || !frozen.enabled()) return List.of();
        return binding.scope().directory().values().stream().map(com.skillforge.institution.team.TeamDefinition.Member::agentId)
                .filter(agent -> !agent.equals(binding.actor().value()) && frozen.targetAgentIds().contains(agent))
                .map(AgentDelegationHost::targetHandle).sorted().toList();
    }

    /** 委派目录、native枚举和派发选择共同使用完整原生句柄，不在消费方截断或重建。 */
    private static String targetHandle(String agentId) { return "member:" + agentId; }

    @Override public EmptyTargetReason emptyTargetReason(RunIdentity parent) {
        var binding = bindings.find(parent.runRef()).orElseThrow(() -> new SecurityException("delegation_parent_missing"));
        requireParent(parent, binding);
        return bindings.emptyTargetReason(binding);
    }

    @Override public void requireAccess(RunIdentity parent, Target target) {
        var binding = bindings.find(parent.runRef()).orElseThrow(() -> new SecurityException("studio_parent_missing"));
        requireParent(parent, binding);
        var matches = targets(parent).stream().filter(value -> value.equals(target)).toList();
        if (matches.size() != 1 || parent.agentId().equals(target.agentId())) throw new SecurityException("studio_target_not_authorized");
    }
    private void requireParent(RunIdentity parent, AgentRunBindings.Binding binding) {
        if (binding.role() != AgentRunBindings.Role.PRIMARY || !binding.owner().equals(parent.owner()) || !binding.actor().equals(parent.agentId()))
            throw new SecurityException("studio_delegation_scope_invalid");
        try { bindings.authorize(binding); }
        catch (ApiException denied) { if (!denied.getStatus().is4xxClientError()) throw denied; throw new SecurityException("studio_access_revoked"); }
    }
    private boolean accessible(AgentRunBindings.Binding binding, String agentId) {
        try { bindings.authorizeTarget(binding,agentId); return true; }
        catch (SecurityException denied) { return false; }
        catch (ApiException denied) { if (!denied.getStatus().is4xxClientError()) throw denied; return false; }
    }
    @Override public void requireReadAccess(RunIdentity parent,Target target) {
        var binding=bindings.find(parent.runRef()).orElseThrow();requireParent(parent,binding);
        var member=binding.scope().directory().get(target.agentId().value());
        if(member==null||parent.agentId().equals(target.agentId())||!target.handle().equals(targetHandle(member.agentId()))
                ||!bindings.targetSnapshot(binding,member.agentId()).equals(target.snapshot()))throw new SecurityException("delegation_target_not_owned");
        bindings.authorizeRead(binding,member.agentId());
    }
    @Override public void requireDirectExecution(RunIdentity parent) {
        var work = works.workForControl(parent.runRef()).flatMap(works::read);
        if (work.isPresent()) throw new IllegalArgumentException("delegate_must_use_plan_step");
    }

    @Override public Optional<Map<String, Object>> workResult(RunIdentity parent, String workId) {
        var work = works.read(new com.skillforge.runtime.api.dag.DagWork.Key(parent.owner(), new InputId(workId)))
                .orElseThrow(() -> new IllegalArgumentException("studio_work_not_owned"));
        if (!work.request().controlRun().equals(parent.runRef())) throw new SecurityException("studio_work_not_owned");
        if (work.status() != com.skillforge.runtime.api.dag.DagWork.Status.COMPLETED
                && work.status() != com.skillforge.runtime.api.dag.DagWork.Status.CANCELLED) return Optional.empty();
        var binding = bindings.find(parent.runRef()).orElseThrow(); requireParent(parent, binding);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (var task : work.plan() == null ? List.<com.skillforge.runtime.api.dag.DagPlan.Task>of() : work.plan().effectiveTasks()) {
            var execution = work.executions().get(task.id());
            boolean visible;
            try { bindings.authorizeRead(binding,task.agentId().value());visible=true; } catch(SecurityException | ApiException denied) { visible=false; }
            var item = new LinkedHashMap<String, Object>(); item.put("task_id", task.id());
            item.put("status", execution == null ? task.cancelled() ? "cancelled" : "blocked" : execution.status().name().toLowerCase(Locale.ROOT));
            item.put("reason", visible ? execution == null ? "依赖未完成或任务已取消" : execution.phase() : "成员授权已撤销");
            item.put("results", visible && execution != null ? results.report(work, execution) : List.of());
            tasks.add(item);
        }
        return Optional.of(Map.of("work_id", workId, "status", work.status().name().toLowerCase(Locale.ROOT), "tasks", tasks));
    }

    public static SessionOwner childOwner(Entry entry) {
        var parent = entry.parent().owner();
        var target=switch(parent.target().kind()) {
            case AGENT -> SessionTarget.agent(entry.target().agentId());
            case TEAM -> parent.target();
            case GROUP -> parent.target();
        };
        return new SessionOwner(new SessionRef(parent.sessionRef().tenantId(), "delegation:" + entry.id()), parent.subject(), target);
    }
    /** 当前DELEGATION派发的唯一能力入口，不在扫描中重新执行START。 */
    public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        var request = delegationCodec.decodeRequest(prepared.request().content());
        if (prepared.kind() != ExecutionStore.Kind.DELEGATION || !registered.action().reference().equals(prepared.action())
                || !registered.attempt().request().equals(prepared.request().reference())
                || !registered.action().fingerprint().equals(prepared.fingerprint())) throw new IllegalArgumentException("delegation_dispatch_invalid");
        var parent = registered.run().identity();
        SessionDelegationEngine.Reply reply;
        try {
            reply = engine.getObject().execute(parent, prepared.action().actionId(), request.command());
            var starts = new LinkedHashSet<String>();
            if (request.command().operation() == DelegationCommand.Operation.START
                    || request.command().operation() == DelegationCommand.Operation.SEND) {
                var accepted = reply.value().get("delegation_id"); if (accepted instanceof String id) starts.add(id);
                reply.waiting().ifPresent(wait -> starts.addAll(wait.delegationIds()));
            }
            for (var id : starts) {
                var entry = store.read(parent, id).orElseThrow();
                if (!SessionDelegations.terminal(entry.state()) && entry.cancelKey().isEmpty()) {
                    admit(entry, registered, prepared);
                    engine.getObject().tick(entry);
                }
            }
            if (reply.waiting().isPresent()) reply = engine.getObject().resume(parent, prepared.action().actionId());
        } catch (SecurityException denied) {
            reply = new SessionDelegationEngine.Reply(Map.of("status", "rejected", "reason", "delegation_access_revoked"), Optional.empty());
        } catch (IllegalArgumentException rejected) {
            reply = new SessionDelegationEngine.Reply(Map.of("status", "rejected", "reason", "delegation_not_authorized_or_invalid"), Optional.empty());
        }
        var feedback = new TypedPayload("application/json", write(reply.value()));
        Optional<SessionExecutionDelegations.Dependency> dependency = reply.waiting().map(wait ->
                new SessionExecutionDelegations.Dependency(wait.id(), wait.delegationIds(), wait.delegationIds().stream()
                        .map(id -> store.read(parent, id).orElseThrow().childRun()).toList()));
        var result = delegationCodec.encodeResult(new SessionExecutionDelegations.Result(feedback, dependency));
        return new ExecutionCoordinator.CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,
                new ExecutionStore.Material(new ResultRef("execution-result:" + registered.attempt().id()), result),
                Optional.of(new ExecutionStore.Material(new ResultRef("execution-feedback:" + registered.attempt().id()), feedback)),
                Optional.empty(), ExecutionBudgetPort.Usage.unreported());
    }

    /** Store派发事务中的当前IAM，目录只能收窄父Run已冻结范围。 */
    public boolean requireDispatch(org.apache.ibatis.session.SqlSession session, ExecutionStore.Run run,
            VerifiedIdentity actor, ExecutionStore.Prepared prepared) {
        return requireDispatch(session, run, actor, prepared, bindings.find(run.identity().runRef()).orElseThrow());
    }
    public boolean requireDispatch(org.apache.ibatis.session.SqlSession session, ExecutionStore.Run run,
            VerifiedIdentity actor, ExecutionStore.Prepared prepared, AgentRunBindings.Binding binding) {
        if (prepared.kind() != ExecutionStore.Kind.DELEGATION || !actor.owner().equals(run.identity().owner())
                || !prepared.capability().equals(new ResultRef("agent-run-binding:" + run.identity().runRef().runId() + ":mounts"))) return false;
        var command = delegationCodec.decodeRequest(prepared.request().content()).command();
        try {
            var parent = run.identity();
            requireParent(parent, binding);
            if (command.operation() == DelegationCommand.Operation.START) {
                var selected = binding.scope().directory().values().stream().filter(member -> targetHandle(member.agentId()).equals(command.targetHandle())).findFirst().orElseThrow();
                bindings.authorizeTarget(binding, selected.agentId());
            } else for (var id : command.delegationIds()) {
                var entry = store.requireEntry(session, parent, id);
                var member = binding.scope().directory().get(entry.target().agentId().value());
                if (member == null || !bindings.targetSnapshot(binding, member.agentId()).equals(entry.target().snapshot())) return false;
                if (command.operation() == DelegationCommand.Operation.SEND) bindings.authorizeTarget(binding, member.agentId());
                else if (command.operation() != DelegationCommand.Operation.CANCEL) bindings.authorizeRead(binding, member.agentId());
            }
            return identities.authorize(session, new IdentityPolicyPort.AuthorizationRequest(actor, "session.execute",
                    com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource.run(parent, Optional.empty()),
                    parent.trace().traceId(), parent.trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
        } catch (SecurityException | NoSuchElementException denied) { return false; }
    }

    private void admit(Entry entry, ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        requireAccess(entry.parent(), entry.target());
        var child = childAdmission(entry, false);
        worker.getObject().observeReady(child.run().runRef());
        execution.admitChild(new ExecutionStore.ChildAdmission(registered, prepared, new ExecutionStore.DelegatedChild(entry.id()), child),
                (session, parent, origin, admission) -> {
                    if (!(origin instanceof ExecutionStore.DelegatedChild delegated) || !parent.identity().equals(entry.parent())) throw new SecurityException("delegation_parent_mismatch");
                    var persisted = store.requireEntry(session, parent.identity(), delegated.delegationId());
                    requireEntry(persisted,entry,admission);
                    if (persisted.cancelKey().isPresent() || SessionDelegations.terminal(persisted.state())) throw new SecurityException("delegation_child_admission_rejected");
                    requireAccess(parent.identity(), persisted.target());
                    requireChildAuthorization(session,child);
                });
        worker.getObject().wake(entry.childRun());
    }

    /** 已批准Plan的真实业务准入可创建原MEMBER子Run；不伪造DELEGATION Provider派发。 */
    public ExecutionStore.AdmissionResult admitPlan(Entry entry, DagWork.Key work, DagWork.Binding binding, DagWork.Permit permit) {
        var previous = fact(entry); if(previous.isEmpty()) requireAccess(entry.parent(),entry.target());
        var child = childAdmission(entry, previous.isPresent());
        worker.getObject().observeReady(child.run().runRef());
        var result = execution.admitPlanChild(new ExecutionStore.PlanChildAdmission(entry.parent().runRef(),new ExecutionStore.PlanChildSource(work,binding,permit),child),
                (session,parent,source,admission,existing) -> {
                    if(!parent.identity().equals(entry.parent()))throw new SecurityException("plan_member_parent_mismatch");
                    var actual=works.requirePlanChild(session,parent.identity(),source.work(),source.binding(),source.permit(),existing);
                    var persisted=store.requireEntry(session,parent.identity(),entry.id());requireEntry(persisted,entry,admission);
                    if(!persisted.operationKey().equals("dag:"+actual.admissionId())||!persisted.childRun().equals(actual.runRef())
                            ||!persisted.target().agentId().equals(actual.task().agentId())
                            ||!persisted.task().objective().equals(actual.task().objective())||!persisted.task().constraints().equals(actual.task().constraints()))
                        throw new SecurityException("plan_member_source_mismatch");
                    if(!existing) {
                        if(persisted.cancelKey().isPresent()||SessionDelegations.terminal(persisted.state()))throw new SecurityException("plan_member_cancelled");
                        var currentWork=works.participate(session,work.owner().sessionRef().tenantId(),()->works.read(work).orElseThrow());
                        if(!persisted.task().equals(planTask(currentWork,actual)))throw new SecurityException("plan_member_task_mismatch");
                        requireAccess(parent.identity(),persisted.target());requireChildAuthorization(session,admission);
                    }
                });
        if(result.created()) { engine.getObject().tick(entry);worker.getObject().wake(entry.childRun()); }return result;
    }

    /** 唯一Plan成员任务正文组装；输入只取真实已分配成果，冻结Entry与guard使用同一合同。 */
    public Task planTask(DagWork work, DagWork.Binding binding) {
        var task=binding.task();var context=new StringBuilder(task.context());
        for(var input:binding.inputs().entrySet()) {
            if(!context.isEmpty())context.append('\n');context.append("获准输入 ").append(input.getKey()).append("：").append(results.inputText(work,input.getValue(),task.agentId()));
        }
        String output;
        if(task.outputs().size()==1)output=com.skillforge.prompts.DelegationPrompts.singleOutput(task.outputs().iterator().next());
        else {var fields=new LinkedHashMap<String,String>();task.outputs().stream().sorted().forEach(name->fields.put(name,com.skillforge.prompts.DelegationPrompts.outputValue()));
            output=com.skillforge.prompts.DelegationPrompts.multipleOutputs(write(Map.of("outputs",fields)));}
        return new Task(task.objective(),context.toString(),task.constraints(),task.expectedOutput()+"\n"+output);
    }

    private static void requireEntry(Entry persisted,Entry expected,ExecutionStore.Admission admission) {
        if(!persisted.id().equals(expected.id())||!persisted.parent().equals(expected.parent())||!persisted.target().equals(expected.target())||!persisted.task().equals(expected.task())
                ||!persisted.childRun().equals(admission.run().runRef())||!persisted.childInput().equals(admission.run().causationInputId())
                ||!childOwner(persisted).equals(admission.run().owner())||!persisted.target().agentId().equals(admission.run().agentId()))throw new SecurityException("delegation_child_admission_rejected");
    }
    private void requireChildAuthorization(org.apache.ibatis.session.SqlSession session,ExecutionStore.Admission child) {
        if(!(identities.authorize(session,new IdentityPolicyPort.AuthorizationRequest(child.identity(),"session.execute",
                com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource.run(child.run(),Optional.empty()),child.run().trace().traceId(),child.run().trace().spanId()))
                instanceof IdentityPolicyPort.AuthorizationResult.Allowed))throw new SecurityException("delegation_child_unauthorized");
    }

    private ExecutionStore.Admission childAdmission(Entry entry,boolean frozenOnly) {
        var inputRef = new ResultRef("execution-input:" + entry.childRun().runId());
        if(frozenOnly) {
            var original=fact(entry).orElseThrow();var originalInput=execution.material(entry.childRun().tenantId(),inputRef).orElseThrow();
            var frozen=codec.frozenInput(originalInput.content());
            var actor=identities.originalVerification(original.identity().owner(),original.identity().causationIdentityRef()).orElseThrow();
            return new ExecutionStore.Admission(actor,original.identity(),digest(frozen.canonicalJson()),original.configuration(),originalInput,program.initialContext(inputRef),
                    budgets.find(entry.childRun()).orElseThrow(),List.of(AgentExecutionProgram.admissionEvent(original.identity())),
                    List.of(execution.material(entry.childRun().tenantId(),AgentRuntimeExecutionCatalog.reference(entry.childRun())).orElseThrow()));
        }
        var binding = bindings.freezeChild(entry); var owner = binding.owner();
        var previous = execution.load(entry.childRun());
        var input = previous.map(ignored -> execution.material(entry.childRun().tenantId(), inputRef).orElseThrow());
        var frozen = input.map(value -> codec.frozenInput(value.content())).orElseGet(() -> {
            var context = attachments.delegationContext(entry.parent());
            return new TypedPayload("input.v1", AgentRuntimeInboundPayloads.inputText(taskInput(entry, context), attachmentMetadata(context)));
        });
        var current = verify(entry,owner, binding.actor(), trace(entry, entry.childInput()), entry.childInput());
        var identity = previous.map(ExecutionStore.Run::identity).orElseGet(() -> new RunIdentity(owner, binding.actor(), entry.childInput(),
                entry.childRun(), trace(entry, entry.childInput()), current.verificationRef(),entry.parent().requester(),entry.parent().sourceMessageRef(),entry.parent().originRef()));
        var originalInput = input.orElseGet(() -> new ExecutionStore.Material(inputRef, codec.encodeInput(
                List.of(new ModelPort.ModelMessage("input:" + entry.childInput().value(), ModelPort.ModelRole.USER, taskInput(entry, extra(frozen)))), frozen)));
        var frozenCatalog = previous.map(ignored -> execution.material(entry.childRun().tenantId(), AgentRuntimeExecutionCatalog.reference(entry.childRun())).orElseThrow())
                .orElseGet(() -> catalog.freeze(identity, binding, entry.createdAt(), frozen));
        var preparation = provisioner.prepareExecutionRun(entry.childRun().tenantId().value(), binding.actor().value(), entry.childRun().runId(),
                binding, catalog.read(frozenCatalog, identity));
        return new ExecutionStore.Admission(current, identity, digest(frozen.canonicalJson()), preparation.profile(), originalInput,
                program.initialContext(inputRef), preparation.budget(), List.of(AgentExecutionProgram.admissionEvent(identity)), List.of(frozenCatalog));
    }

    /** 真实结构化Run的精确归属，不读取旧Run/checkpoint/input来源图。 */
    public Optional<ExecutionStore.Run> fact(Entry entry) {
        var found = execution.load(entry.childRun());
        found.ifPresent(run -> {
            if (!run.identity().owner().equals(childOwner(entry)) || !run.identity().agentId().equals(entry.target().agentId())
                    || !run.identity().causationInputId().equals(entry.childInput())) throw new SecurityException("delegation_child_owner_mismatch");
        });
        return found;
    }
    @Override public Optional<Result> result(Entry entry) {
        var found = fact(entry);
        if (found.isEmpty()) return entry.cancelKey().map(key -> new Result(State.CANCELLED, "", "cancelled_before_execution", TokenUsage.known(0), List.of()));
        var run = found.orElseThrow();
        if (run.phase() == ExecutionStore.Phase.UNCERTAIN || run.phase() == ExecutionStore.Phase.FINISHED && unresolved(run)) {
            var usage = execution.usage(entry.childRun());
            return Optional.of(new Result(State.FAILED, "", "external_effect_outcome_unknown", new TokenUsage(usage.reportedTokens(), false),
                    List.of("execution-state:" + entry.childRun().runId() + ":" + run.revision())));
        }
        if (run.phase() != ExecutionStore.Phase.FINISHED) return Optional.empty();
        var publication = run.status() == RunStatus.COMPLETED ? deliverableFacts.read(entry.parent(), run.identity())
                : Optional.of(new com.skillforge.institution.artifact.DelegationDeliverableFacts.Published(List.of(), ""));
        if (publication.isEmpty()) return Optional.empty();
        var completion = completion(run);
        var state = run.status() == RunStatus.COMPLETED ? State.COMPLETED : run.status() == RunStatus.CANCELLED ? State.CANCELLED : State.FAILED;
        var summary = completion.path("text").asText(); var reason = completion.path("reason").asText();
        var evidence = new ArrayList<String>(); evidence.add("execution-state:" + entry.childRun().runId() + ":" + run.revision());
        var published = publication.orElseThrow(); evidence.addAll(published.evidence());
        if (!published.summary().isEmpty()) summary += "\n\n" + published.summary();
        var usage = execution.usage(entry.childRun());
        return Optional.of(new Result(state, summary, reason, new TokenUsage(usage.reportedTokens(), usage.complete()), evidence));
    }
    /** 取消只终止继续推进；已派发未确定的真实效果不能因此被改报为确定取消。 */
    private boolean unresolved(ExecutionStore.Run run) {
        if (run.currentBatch().isPresent()) return execution.batch(run.currentBatch().orElseThrow()).stream()
                .anyMatch(entry -> entry.attempt().result().isEmpty() || entry.attempt().resultKind().equals(Optional.of(ExecutionStore.ResultKind.UNKNOWN)));
        if (run.currentAction().isEmpty()) return false;
        var action = execution.action(run.currentAction().orElseThrow()).orElseThrow();
        if (!action.run().equals(run.identity().runRef())) throw new SecurityException("delegation_action_owner_mismatch");
        var attempt = execution.attempt(run.identity().runRef(), action.currentAttempt()).orElseThrow();
        return attempt.result().isEmpty() || attempt.resultKind().equals(Optional.of(ExecutionStore.ResultKind.UNKNOWN));
    }
    private com.fasterxml.jackson.databind.JsonNode completion(ExecutionStore.Run run) {
        long after = 0; com.fasterxml.jackson.databind.JsonNode completed = null;
        while (true) {
            var page = execution.events(run.identity().owner().sessionRef(), after, 100);
            for (var event : page) if (event.run().equals(run.identity().runRef()) && AgentExecutionProgram.COMPLETED_EVENT.equals(event.event().type())
                    && AgentExecutionProgram.COMPLETED_SCHEMA.equals(event.event().data().schemaId())) {
                try {
                    var value = json.readTree(event.event().data().canonicalJson());
                    if (!run.status().name().equals(value.path("status").asText()) || !run.identity().agentId().value().equals(value.path("agentId").asText())
                            || !run.identity().causationInputId().value().equals(value.path("inputId").asText())) throw new IllegalStateException("delegation_completion_conflict");
                    if (completed != null) throw new IllegalStateException("delegation_completion_duplicate"); completed = value;
                } catch (java.io.IOException invalid) { throw new IllegalStateException("delegation_completion_invalid", invalid); }
            }
            if (page.size() < 100) break; after = page.getLast().sequence();
        }
        return Optional.ofNullable(completed).orElseThrow(() -> new IllegalStateException("delegation_completion_missing"));
    }
    @Override public State state(Entry entry) {
        return fact(entry).map(run -> run.phase() == ExecutionStore.Phase.WAITING ? State.WAITING : State.RUNNING).orElse(State.QUEUED);
    }
    @Override public void send(Entry entry, String inputId, String message) {
        requireAccess(entry.parent(), entry.target());
        var child = fact(entry).orElseThrow(() -> new IllegalStateException("delegation_child_missing"));
        var input = new InputId(inputId);
        var actor = verify(entry,childOwner(entry), entry.target().agentId(), trace(entry, input), input);
        steering.getObject().offer(child, actor, input, message);
    }
    @Override public void cancel(Entry entry, String inputId) {
        var found = fact(entry); if (found.isEmpty() || found.orElseThrow().phase() == ExecutionStore.Phase.FINISHED) return;
        var run = found.orElseThrow(); if (run.cancelled()) return;
        var input = new InputId(inputId); var actor = verify(entry,childOwner(entry), entry.target().agentId(), trace(entry, input), input);
        execution.cancel(new ExecutionStore.Cancel(actor, entry.childRun(), input, List.of(cancelEvent(run))));
        worker.getObject().cancelled(entry.childRun());
    }
    @Override public boolean parentCancelled(RunIdentity parent) {
        return execution.load(parent.runRef()).map(run -> {
            if (!run.identity().equals(parent)) throw new SecurityException("delegation_parent_identity_mismatch");
            return run.cancelled() || run.phase() == ExecutionStore.Phase.FINISHED;
        }).orElse(true);
    }

    /** 系统只提供唤醒信号，候选正文必须在同事务由真实Entry/Run重新证明。 */
    @Override public boolean wake(Wait wait, List<Entry> results) {
        var found = execution.load(wait.parent().runRef()); if (found.isEmpty()) return false;
        var parent = found.orElseThrow(); if (!parent.identity().equals(wait.parent())) throw new SecurityException("delegation_wait_parent_mismatch");
        if (parent.cancelled() || parent.phase() == ExecutionStore.Phase.FINISHED) return true;
        if (parent.phase() != ExecutionStore.Phase.WAITING || parent.waiting().isEmpty()) return false;
        var reference = parent.waiting().orElseThrow(); var waiting = execution.waiting(parent.identity().runRef(), reference).orElseThrow();
        if (waiting.kind() != ExecutionStore.WaitKind.DELEGATION || !(waiting.internal().orElseThrow() instanceof ExecutionStore.DelegationWait dependency)
                || !dependency.businessWaitId().equals(wait.id()) || !dependency.delegationIds().equals(wait.delegationIds())
                || !dependency.children().equals(results.stream().map(Entry::childRun).toList())) return false;
        for (var entry : results) requireReadAccess(wait.parent(), entry.target());
        var value = new TypedPayload("application/json", write(Map.of("results", results.stream().map(SessionDelegationEngine::publicEntry).toList())));
        var response = delegationCodec.encodeResponse(value); var input = new InputId("delegation-response:" + digest(reference.action().actionId() + ":" + reference.generation()));
        var actor = verifySystem(parent.identity(), input);
        var material = new ExecutionStore.Material(new ResultRef("execution-response:" + parent.identity().runRef().runId() + ":" + input.value()), response);
        execution.acceptDependency(new ExecutionStore.Resume(actor, parent.identity().runRef(), reference, parent.revision(), input, material,
                digest(response.canonicalJson()), Map.of(), List.of(resolved(parent, input, reference))),
                (session, current, actualWait, children, actualResponse) -> {
                    var entries = store.requireWait(session, wait);
                    if (!current.identity().equals(wait.parent()) || !actualWait.reference().equals(reference)
                            || !entries.stream().map(Entry::childRun).toList().equals(children.stream().map(child -> child.identity().runRef()).toList()))
                        throw new SecurityException("delegation_wait_dependency_mismatch");
                    for (int index = 0; index < entries.size(); index++) {
                        var entry = entries.get(index); var child = children.get(index); var result = entry.result().orElseThrow();
                        if (!SessionDelegations.terminal(entry.state()) || !child.identity().owner().equals(childOwner(entry))
                                || !child.identity().agentId().equals(entry.target().agentId())
                                || child.phase() != ExecutionStore.Phase.FINISHED && !(child.phase() == ExecutionStore.Phase.UNCERTAIN
                                    && result.state() == State.FAILED && "external_effect_outcome_unknown".equals(result.reason())))
                            throw new SecurityException("delegation_wait_not_terminal");
                        if (child.phase() == ExecutionStore.Phase.FINISHED) {
                            var expected = child.status() == RunStatus.COMPLETED ? State.COMPLETED
                                    : child.status() == RunStatus.CANCELLED ? State.CANCELLED : State.FAILED;
                            if (result.state() != expected && !(result.state() == State.FAILED
                                    && "external_effect_outcome_unknown".equals(result.reason()))) throw new SecurityException("delegation_wait_result_state_mismatch");
                        }
                        requireReadAccess(current.identity(), entry.target());
                    }
                    var exact = delegationCodec.encodeResponse(new TypedPayload("application/json", write(Map.of("results", entries.stream().map(SessionDelegationEngine::publicEntry).toList()))));
                    if (!actualResponse.content().equals(exact)) throw new SecurityException("delegation_wait_result_conflict");
                    store.confirmDelivery(session, wait);
                });
        worker.getObject().wake(parent.identity().runRef()); return true;
    }
    private ExecutionStore.Event resolved(ExecutionStore.Run parent, InputId input, ExecutionStore.WaitRef reference) {
        var original = AgentExecutionProgram.resolved(parent, input, reference);
        try {
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.data().canonicalJson());
            value.put("dependencyKind", "delegation");
            return new ExecutionStore.Event(original.id(), original.type(), new TypedPayload(original.data().schemaId(), write(value)));
        } catch (java.io.IOException malformed) { throw new IllegalStateException("delegation_resolution_invalid", malformed); }
    }

    /** 已提交子Run或父等待触发有界领域协调；真实执行仍只通过Worker定向领取。 */
    public void committed(RunRef run) {
        var binding = bindings.find(run); if (binding.isEmpty()) return;
        if (binding.orElseThrow().role() == AgentRunBindings.Role.DELEGATE) {
            var child = execution.load(run).orElseThrow(); var parent = execution.load(binding.orElseThrow().parentRunRef().orElseThrow()).orElseThrow();
            var id = child.identity().owner().sessionRef().sessionId().substring("delegation:".length());
            store.read(parent.identity(), id).ifPresent(engine.getObject()::tick);
            engine.getObject().changed(parent.identity());
        } else {
            execution.load(run).ifPresent(parent -> engine.getObject().changed(parent.identity()));
        }
    }
    public void recover(TenantId tenant, int limit) { engine.getObject().tick(tenant, limit); }

    private ExecutionStore.Event cancelEvent(ExecutionStore.Run run) {
        var data = new LinkedHashMap<String, Object>(); data.put("text", ""); data.put("reason", "cancelled"); data.put("status", RunStatus.CANCELLED.name());
        data.put("resultRef", null); data.put("inputId", run.identity().causationInputId().value()); data.put("rootInputId", run.identity().trace().rootInputId().value());
        data.put("traceId", run.identity().trace().traceId()); data.put("spanId", run.identity().trace().spanId()); data.put("agentId", run.identity().agentId().value()); data.put("evidence", List.of());
        return new ExecutionStore.Event(new EventId("delegation-cancelled:" + digest(run.identity().runRef().runId())), AgentExecutionProgram.COMPLETED_EVENT,
                new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, write(data)));
    }
    /** 群子Run续用父范围的真实固定服务身份，不换为Chat默认集成。 */
    private VerifiedIdentity verify(Entry entry,SessionOwner owner,AgentId actor,TraceEnvelope trace,InputId input) {
        if(owner.target().kind()!=SessionTarget.Kind.GROUP)return verify(owner,actor,trace,input);
        var binding=bindings.find(entry.parent().runRef()).orElseThrow(()->new SecurityException("group_parent_binding_missing"));
        var principal=binding.scope().requester().servicePrincipal().orElseThrow(()->new SecurityException("group_service_identity_missing"));
        var current=principals.selectOne(new QueryWrapper<ServicePrincipalDO>().eq("tenant_id",Long.parseLong(principal.tenantId().value()))
                .eq("principal_id",principal.principalId()).eq("integration_id",principal.integrationId()).eq("status","active"));
        if(current==null||current.getScopes()==null||!current.getScopes().contains("groups.triggers.write"))
            throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("group_principal_authorization_revoked");
        var fresh=new PrincipalRef.ServicePrincipal(principal.tenantId(),principal.principalId(),principal.integrationId(),current.getCredentialVersion());
        var envelope=trusted.serviceAccess(fresh,owner,actor,"open-platform","delegation",new RequestId(input.value()),trace.traceId(),trace.spanId());
        return switch(identities.verify(new IdentityPolicyPort.IdentityRequest(envelope.authenticationRef(),owner,envelope.channel(),envelope.source(),
                envelope.requestId(),Optional.of(input),trace.traceId(),trace.spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified value -> value.identity();
            case IdentityPolicyPort.IdentityResult.Rejected value -> throw new SecurityException(value.failure().reasonCode());
        };
    }

    private VerifiedIdentity verify(SessionOwner owner, AgentId actor, TraceEnvelope trace, InputId input) {
        var envelope = access(owner, actor, input.value());
        return switch (identities.verify(new IdentityPolicyPort.IdentityRequest(envelope.authenticationRef(), owner, envelope.channel(), envelope.source(),
                envelope.requestId(), Optional.of(input), trace.traceId(), trace.spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified verified -> verified.identity();
            case IdentityPolicyPort.IdentityResult.Rejected rejected -> throw new SecurityException(rejected.failure().reasonCode());
        };
    }
    /** 自动依赖结果使用真实Worker系统主体，保留原Run因果身份，不冒充原用户回复。 */
    private VerifiedIdentity verifySystem(RunIdentity parent, InputId input) {
        provisioner.provisionAccess(parent.runRef().tenantId().value());
        var authentication = identities.registerTrusted(new PrincipalRef.SystemPrincipal(parent.runRef().tenantId(), workerId),
                parent.owner(), "chat", "delegation", Duration.ofHours(1));
        return switch (identities.verify(new IdentityPolicyPort.IdentityRequest(authentication, parent.owner(), "chat", "delegation",
                new RequestId(input.value()), Optional.of(input), parent.trace().traceId(), parent.trace().spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified verified -> verified.identity();
            case IdentityPolicyPort.IdentityResult.Rejected rejected -> throw new SecurityException(rejected.failure().reasonCode());
        };
    }
    private AccessEnvelope access(SessionOwner owner, AgentId actor, String operation) {
        String tenant = owner.sessionRef().tenantId().value(); provisioner.provisionAccess(tenant);
        var row = principals.selectOne(new QueryWrapper<ServicePrincipalDO>().eq("tenant_id", Long.parseLong(tenant))
                .eq("integration_id", AgentRuntimeHostContracts.CHAT_INTEGRATION_ID).eq("status", "active").last("LIMIT 1"));
        if (row == null) throw new IllegalStateException("studio_runtime_principal_missing");
        return trusted.serviceAccess(new PrincipalRef.ServicePrincipal(new TenantId(tenant), row.getPrincipalId(), AgentRuntimeHostContracts.CHAT_INTEGRATION_ID, row.getCredentialVersion()),
                owner, actor, "chat", "request", new RequestId(operation), operation, "span:" + operation);
    }
    private static TraceEnvelope trace(Entry entry, InputId input) {
        return new TraceEnvelope(entry.parent().trace().traceId(), "span:" + input.value(), entry.parent().trace().rootInputId(), Optional.of(new EventId(entry.parent().causationInputId().value())));
    }
    static String taskInput(Entry entry, Map<String, Object> context) {
        var task = entry.task(); var text = com.skillforge.prompts.DelegationPrompts.taskInput(task.objective(), task.context(), task.constraints(), task.expectedOutput());
        if (context.isEmpty()) return text;
        try { return text + "\n受管附件引用：" + new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(attachmentMetadata(context))
                + "\n附件清单是可用来源，不代表正文已经读取。"; }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("delegation_attachment_metadata_invalid", failure); }
    }
    static Map<String, Object> attachmentMetadata(Map<String, Object> context) {
        return new ObjectMapper().convertValue(context, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
    private Map<String, Object> extra(TypedPayload input) {
        try { return json.convertValue(json.readTree(input.canonicalJson()).path("extraParameters"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("delegation_input_invalid", failure); }
    }
    private static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (java.io.IOException failure) { throw new IllegalStateException("studio_delegation_snapshot_invalid", failure); } }
}
