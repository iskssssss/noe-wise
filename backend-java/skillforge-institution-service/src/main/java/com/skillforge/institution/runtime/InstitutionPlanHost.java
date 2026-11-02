package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.institution.team.TeamWorkInputs;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.dag.DagCoordinator;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 通用计划的可信宿主；计划、步骤与 Action 归属均复用持久 DAG 和 SessionLog。 */
@Service
public final class InstitutionPlanHost implements PlanPort {
    private final JdbcDagWorkPort works;
    private final ObjectProvider<TeamWorkService> service;
    private final ObjectProvider<AgentDelegationHost> delegates;
    private final AgentRunBindings bindings;
    private final TeamWorkInputs inputs;
    private final JdbcSessionLogPort log;
    private final ObjectMapper json;
    private final Clock clock;
    private final ExecutionStore executions;
    private final AgentExecutionCodec executionCodec;
    /** 仅同一 Run 本地计划采纳边界有效；当前身份已由执行存储同事务校验。 */
    private final ThreadLocal<PlanScope> scope = new ThreadLocal<>();
    /** 只复用同一次本地命令已完成当前披露核验的目录，作用域退出即清除。 */
    private final ThreadLocal<List<com.skillforge.runtime.session.spi.SessionDelegations.Target>> visibleTargets = new ThreadLocal<>();

    /** 原模型实际包含的补充输入位置，不能用计划执行时的新输入替换。 */
    private record PlanScope(SqlSession session, RunIdentity run, AgentRunBindings.Binding binding, long supplementalIncludedThrough) {}

    /**
     * 同一 SqlSession 内协调计划 DAG 与业务事件；调用方拥有提交和回滚。
     * binding 来自当前已加载配置，当前执行权限必须先由外层执行存储检查。
     */
    public <T> T participate(SqlSession session, RunIdentity run, AgentRunBindings.Binding binding,
                             long supplementalIncludedThrough, java.util.function.Supplier<T> operation) {
        Objects.requireNonNull(session); Objects.requireNonNull(run); Objects.requireNonNull(binding); Objects.requireNonNull(operation);
        if (supplementalIncludedThrough < 0 || !binding.owner().equals(run.owner()) || !binding.actor().equals(run.agentId()))
            throw new SecurityException("plan_scope_not_owned");
        if (scope.get() != null) throw new IllegalStateException("plan_scope_nested");
        scope.set(new PlanScope(session, run, binding, supplementalIncludedThrough));
        try {
            return works.participate(session, run.runRef().tenantId(), () ->
                    log.participate(session, run.runRef().tenantId(), operation));
        } finally { visibleTargets.remove(); scope.remove(); }
    }

    public InstitutionPlanHost(JdbcDagWorkPort works, ObjectProvider<TeamWorkService> service,
            ObjectProvider<AgentDelegationHost> delegates, AgentRunBindings bindings,
            TeamWorkInputs inputs, JdbcSessionLogPort log, ObjectMapper json, Clock clock,
            ExecutionStore executions, AgentExecutionCodec executionCodec) {
        this.works=works; this.service=service; this.delegates=delegates; this.bindings=bindings;
        this.inputs=inputs; this.log=log; this.json=com.skillforge.institution.runtime.SessionSubjectJson.configure(json.copy()).enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); this.clock=clock;
        this.executions=Objects.requireNonNull(executions); this.executionCodec=Objects.requireNonNull(executionCodec);
    }

    @Override public Reply execute(RunIdentity run, String operationKey, PlanCommand command) {
        authorize(run);
        if(binding(run).role()!=AgentRunBindings.Role.PRIMARY)
            throw invalid("plan_member_uses_inherited_step");
        if(command.operation()==PlanCommand.Operation.SUBMIT) {
            var old=current(run);
            String submitEventId="plan-submit-accepted:"+operationKey;
            String requestFingerprint=requestFingerprint(command);
            var accepted=log.findByEvent(run.owner(),new EventId(submitEventId));
            if(accepted.isPresent()) {
                var event=accepted.orElseThrow();
                if(!event.eventType().equals("plan.submit.accepted") || !event.runRef().equals(Optional.of(run.runRef()))
                        || !body(event).path("request_fingerprint").asText().equals(requestFingerprint)) throw invalid("plan_command_conflict");
                var submitted = require(run, run.causationInputId().value());
                return body(event).path("approval_required").asBoolean()
                        && submitted.plan().revision() == command.expectedRevision() + 1
                        ? waitFor(run, operationKey, submitted, List.of()) : reply(run, submitted);
            }
            if (service.getObject().planSubmitAccepted(run, "plan-submit:" + operationKey, requestFingerprint)) {
                var submitted = require(run, run.causationInputId().value());
                return submitted.plan().approvalRequired() && submitted.plan().revision() == command.expectedRevision() + 1
                        ? waitFor(run, operationKey, submitted, List.of()) : reply(run, submitted);
            }
            if(!command.workId().isEmpty() && !command.workId().equals(run.causationInputId().value())) throw invalid("plan_not_owned");
            var supplied=command.plan().orElseThrow();
            if(command.expectedRevision()>0 && supplied.changeReason().isBlank()) throw invalid("plan_change_reason_required");
            if (old.isPresent() && old.orElseThrow().plan().revision() != command.expectedRevision())
                return rejected(run, old.orElseThrow(), "plan_revision_conflict");
            if (old.isPresent() && supplied.tasks().stream().anyMatch(t -> old.orElseThrow().executions().containsKey(t.id())))
                return rejected(run, old.orElseThrow(), "plan_admitted_task_immutable");
            var candidates=supplied.tasks().stream().anyMatch(t->t.executor().type()==PlanCommand.ExecutorType.MEMBER)
                    ? targets(run) : List.<com.skillforge.runtime.session.spi.SessionDelegations.Target>of();
            var tasks=supplied.tasks().stream().map(t->{
                var primary=t.executor().type()==PlanCommand.ExecutorType.PRIMARY;
                var agent=primary ? run.agentId() : candidates.stream().filter(v->v.handle().equals(t.executor().targetHandle()))
                        .findFirst().orElseThrow(()->invalid("plan_member_not_authorized")).agentId();
                return new DagPlan.Task(t.id(),t.title(),agent,primary?DagPlan.ExecutorType.PRIMARY:DagPlan.ExecutorType.MEMBER,
                        t.task().objective(),t.task().context(),t.task().constraints(),t.task().expectedOutput(),
                        t.inputs().stream().map(i->new DagPlan.Input(i.name(),i.sourceTask(),i.output())).toList(),
                        t.outputs(),t.exclusiveResources(),t.cancelled(),t.replacesTaskId());
            }).toList();
            var mergedTasks = new ArrayList<DagPlan.Task>();
            old.ifPresent(work -> work.plan().tasks().stream().filter(t -> work.executions().containsKey(t.id()))
                    .map(t -> work.executions().get(t.id()).binding().task()).forEach(mergedTasks::add));
            mergedTasks.addAll(tasks);
            boolean approval=supplied.approvalRequired() || service.getObject().planMode(run.runRef()) || old.map(w->w.plan().approvalRequired()).orElse(false);
            var plan=new DagPlan(command.expectedRevision()+1,approval,mergedTasks,supplied.title(),supplied.changeReason());
            applied(service.getObject().submitPlan(run,"plan-submit:"+operationKey,plan,command.expectedRevision(),requestFingerprint));
            // 已校验的受理事件是原提交事实；恢复时不能用新时刻重写同一事件并制造日志冲突。
            if(accepted.isEmpty()) append(run,submitEventId,"plan.submit.accepted",Map.of("work_id",run.causationInputId().value(),
                    "request_fingerprint",requestFingerprint,"approval_required",approval));
            var submitted = require(run,run.causationInputId().value());
            // 受理的精确版本已要求审批；同一次原生动作直接复用持久等待，不再让模型决定“等待”。
            return approval && submitted.plan().revision() == plan.revision()
                    ? waitFor(run, operationKey, submitted, List.of()) : reply(run, submitted);
        }
        var work=require(run,command.workId());
        return switch(command.operation()) {
            case GET -> reply(run, work);
            case START_STEP -> {
                // 模型选择的版本/任务/执行者属于可纠正命令输入；返回原计划快照，不将拒绝变成驱动恢复异常。
                if (command.expectedRevision() != work.plan().revision())
                    yield rejected(run, work, "plan_revision_conflict");
                var task = work.plan().tasks().stream().filter(value -> value.id().equals(command.taskId())).findFirst();
                if (task.isEmpty()) yield rejected(run, work, "plan_step_not_found");
                if (task.orElseThrow().executorType() != DagPlan.ExecutorType.PRIMARY)
                    yield rejected(run, work, "plan_primary_step_required");
                // 真正的准入仍由DagCoordinator核验审批、依赖、scope、资源和permit；不捕获权限或技术异常。
                var result = service.getObject().startPrimary(work.key(),command.taskId(),"plan-start:"+operationKey,clock.instant());
                var current = require(run,command.workId());
                yield result == DagWorkPort.Commit.CONFLICT ? rejected(run, current, "plan_step_not_ready") : reply(run, current);
            }
            case FINISH_STEP -> finish(run,operationKey,command,work);
            case WAIT -> waitFor(run, operationKey, work, command.waitTaskIds());
            default -> throw invalid("plan_operation_invalid");
        };
    }

    /** SUBMIT 与显式 WAIT 使用完全相同的来源、幂等及恢复条件。 */
    private Reply waitFor(RunIdentity run, String operationKey, DagWork work, List<String> taskIds) {
        var prior = log.findByEvent(run.owner(), new EventId("plan-wait:" + operationKey));
        if (prior.isPresent()) {
            var data = body(prior.orElseThrow());
            if (!data.path("work_id").asText().equals(work.key().rootInputId().value())
                    || !data.path("task_ids").equals(json.valueToTree(taskIds))) throw invalid("plan_command_conflict");
            return pollWait(run, "delegation-wait:" + operationKey)
                    .orElseGet(() -> new Reply(snapshot(run, work), Optional.of("delegation-wait:" + operationKey)));
        }
        for (var id : taskIds)
            if (work.plan().tasks().stream().noneMatch(task -> task.id().equals(id)))
                return rejected(run, work, "plan_step_not_found");
        if (taskIds.stream().anyMatch(id -> waitsForPrimary(work, id, new HashSet<>())))
            return rejected(run, work, "plan_wait_self_deadlock");
        if (ready(work, taskIds)) return waitSatisfiedReply(run, work);
        append(run, "plan-wait:" + operationKey, "plan.wait.requested", Map.of("work_id", work.key().rootInputId().value(),
                "plan_revision", work.plan().revision(), "task_ids", taskIds,
                "supplemental_included_through", modelIncludedThrough(run)));
        return new Reply(snapshot(run, work), Optional.of("delegation-wait:" + operationKey));
    }

    private Reply finish(RunIdentity run,String operationKey,PlanCommand command,DagWork work) {
        var execution=work.executions().get(command.taskId());
        if(execution==null) return rejected(run, work, "plan_step_not_started");
        if(execution.binding().task().executorType()!=DagPlan.ExecutorType.PRIMARY)
            return rejected(run, work, "plan_member_finish_forbidden");
        if(command.expectedRevision()!=execution.binding().planRevision() && command.expectedRevision()!=work.plan().revision())
            return rejected(run, work, "plan_revision_conflict");
        String eventId="plan-finish:"+operationKey;
        var prior=log.findByEvent(run.owner(),new EventId(eventId));
        long fence=prior.map(event->body(event).path("fence").asLong(0))
                .orElseGet(()->execution.permit().map(DagWork.Permit::fence)
                        .orElseThrow(()->invalid("plan_step_not_running")));
        if(fence<1) throw invalid("plan_finish_fence_missing");
        List<ResultRef> resultRefs=command.resultRefs();
        if(prior.isPresent() && command.outcome().equals("completed") && resultRefs.isEmpty()) {
            // 重放沿首次终结事实，不能把后来新增的 Action 结果拼入原收尾。
            resultRefs=json.convertValue(body(prior.orElseThrow()).path("result_refs"),new TypeReference<List<String>>() {})
                    .stream().map(ResultRef::new).toList();
        }
        if(prior.isEmpty()) {
            var evidence = stepEvidence(run.owner(), run.runRef(), execution.binding().task().id());
            if(command.outcome().equals("completed") && resultRefs.isEmpty())
                resultRefs=evidence.allowed().stream().sorted().map(ResultRef::new).toList();
            try { validateEvidence(command, resultRefs, evidence); }
            catch (IllegalArgumentException rejected) {
                // 完成尚未写入任何事实；直接返回同一次核验的可选引用，避免额外模型查询。
                return new Reply(Map.of("status", "rejected", "reason", rejected.getMessage(),
                        "work_id", command.workId(), "plan_revision", work.plan().revision(),
                        "current_primary_step_evidence", evidenceView(execution.binding(), evidence)), Optional.empty());
            }
        }
        var status=switch(command.outcome()) {
            case "completed" -> DagWork.TaskStatus.COMPLETED;
            case "failed" -> DagWork.TaskStatus.FAILED;
            default -> DagWork.TaskStatus.OUTCOME_UNKNOWN;
        };
        if(!execution.binding().task().outputs().containsAll(command.outputs().keySet()))
            return rejected(run, work, "plan_output_undeclared");
        if(status==DagWork.TaskStatus.COMPLETED && !command.outputs().keySet().equals(execution.binding().task().outputs()))
            return rejected(run, work, "plan_step_outputs_incomplete");
        append(run,eventId,"plan.step.finished",Map.of("work_id",command.workId(),"task_id",command.taskId(),
                "admission_id",execution.binding().admissionId(),"fence",fence,"summary",command.summary(),
                "result_refs",resultRefs.stream().map(ResultRef::value).toList(),"outcome",status.name(),"outputs",command.outputs()));
        var outputs=new LinkedHashMap<String,DagWork.Output>();
        if(status==DagWork.TaskStatus.COMPLETED) for(String name:execution.binding().task().outputs())
            outputs.put(name,new DagWork.Output(new ResultRef(eventId+"#"+Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(name.getBytes(StandardCharsets.UTF_8))),hash(command.outputs().get(name)),true));
        applied(service.getObject().finishPrimary(work.key(),new DagCoordinator.Completion(eventId,command.taskId(),run.runRef(),
                fence,status,0,outputs,command.summary(),new ResultRef(eventId))));
        return reply(run, require(run,command.workId()));
    }

    /** 只接受本步骤真实成功 Action 的持久结果；模型声明不能充当外部成功依据。 */
    private void validateEvidence(PlanCommand command, List<ResultRef> resultRefs, StepEvidence evidence) {
        if(command.outcome().equals("completed") && evidence.unknown()) throw invalid("plan_step_outcome_unknown");
        if(resultRefs.stream().anyMatch(r->!evidence.allowed().contains(r.value()))) throw invalid("plan_result_not_owned_or_succeeded");
        if(evidence.required() && command.outcome().equals("completed") && resultRefs.isEmpty()) throw invalid("plan_result_evidence_required");
    }

    private record StepEvidence(Set<String> allowed, boolean required, boolean unknown) { }

    private Map<String,Object> evidenceView(DagWork.Binding binding, StepEvidence evidence) {
        return Map.of("task_id", binding.task().id(), "admission_id", binding.admissionId(),
                "admitted_plan_revision", binding.planRevision(),
                "successful_result_refs", evidence.allowed().stream().sorted().toList(),
                "requires_result_refs", evidence.required(), "has_unknown_outcome", evidence.unknown());
    }

    /** 模型可选引用与完成门禁使用同一份宿主核验结果，不能用成果ID替代Action结果。 */
    private StepEvidence stepEvidence(SessionOwner owner, RunRef run, String taskId) {
        var stored=executions.load(run).orElseThrow(()->invalid("plan_run_missing"));
        if(!stored.identity().owner().equals(owner)) throw new SecurityException("plan_run_not_owned");
        var allowed=new HashSet<String>();
        boolean required=false; boolean unknown=false;
        long cursor=0;
        while(true) {
            var page=executions.actions(run,cursor,100);
            for(var action:page) {
                cursor=action.ordinal();
                if(!works.taskForAction(run,action.reference().actionId()).equals(Optional.of(taskId))) continue;
                required=true;
                // 在途或结果未知都不能被步骤完成掩盖；只有已选定且已采纳的准确成功结果可引用。
                if(action.status()==ExecutionStore.ActionStatus.UNKNOWN || action.status()==ExecutionStore.ActionStatus.DISPATCHED) unknown=true;
                if(action.status()!=ExecutionStore.ActionStatus.SUCCEEDED || action.selectedAttempt().isEmpty()) continue;
                var attempt=executions.attempt(run,action.selectedAttempt().orElseThrow()).orElseThrow(()->invalid("plan_result_missing"));
                if(!attempt.action().equals(action.reference()) || !attempt.adopted()
                        || !attempt.resultKind().equals(Optional.of(ExecutionStore.ResultKind.SUCCEEDED))) continue;
                var reference=attempt.result().orElseThrow(()->invalid("plan_result_missing"));
                var material=executions.material(run.tenantId(),reference).orElseThrow(()->invalid("plan_result_missing"));
                if(action.kind()==ExecutionStore.Kind.TOOL && !successfulTool(material.content())) continue;
                allowed.add(reference.value());
            }
            if(page.size()<100) break;
        }
        return new StepEvidence(Set.copyOf(allowed),required,unknown);
    }

    /** 工具调用成功与业务成功分开判断；只采纳保存的原成功envelope，不从展示事件猜测。 */
    private boolean successfulTool(TypedPayload result) {
        var outcome=executionCodec.decodeToolOutcome(result);
        if(!(outcome instanceof ToolPortal.ToolOutcome.Succeeded succeeded)) return false;
        try {
            var envelope=json.readTree(succeeded.observation().canonicalJson());
            var success=envelope.path("structuredContent").path("success");
            return !envelope.path("isError").asBoolean(false) && !(success.isBoolean() && !success.booleanValue());
        } catch(java.io.IOException invalid) { throw invalid("plan_result_not_owned_or_succeeded"); }
    }

    @Override public Optional<Reply> pollWait(RunIdentity run,String waitHandle) {
        authorize(run);
        if(!waitHandle.startsWith("delegation-wait:")) throw invalid("plan_wait_invalid");
        var event=log.findByEvent(run.owner(),new EventId("plan-wait:"+waitHandle.substring("delegation-wait:".length())));
        if(event.isEmpty()) return Optional.empty();
        var source=event.orElseThrow();
        if(!source.eventType().equals("plan.wait.requested") || !source.runRef().equals(Optional.of(run.runRef()))) throw invalid("plan_wait_not_owned");
        var data=body(source); var work=require(run,data.path("work_id").asText());
        var ids=new ArrayList<String>(); data.path("task_ids").forEach(n->ids.add(n.asText()));
        // 以本次模型准备为界，覆盖纠正在模型调用期间、wait 落盘之前到达的竞态。
        var includedThrough = data.path("supplemental_included_through");
        if (!includedThrough.isIntegralNumber() || includedThrough.asLong() < 0)
            throw invalid("plan_wait_steering_source_invalid");
        long afterSequence = includedThrough.asLong();
        // 新纠正只结束本次计划等待，让原 Run 消费 steering 并修订；审批事实不变。
        boolean corrected = !executions.inputs(run.runRef(), afterSequence, false, 1).isEmpty();
        // 修订需要重新读取并决定新的等待条件，不让旧条件永久挂起。
        return corrected || work.plan().revision()!=data.path("plan_revision").asLong() || ready(work,ids) ? Optional.of(waitSatisfiedReply(run, work)) : Optional.empty();
    }

    private long modelIncludedThrough(RunIdentity run) {
        var current = scope.get();
        if (current == null || !current.run().equals(run)) throw invalid("plan_model_source_scope_missing");
        return current.supplementalIncludedThrough();
    }

    @Override public State state(RunIdentity run) {
        authorize(run);
        var inherited=inherited(run);
        if(inherited.isPresent()) return new State(true,true,true,Optional.of(inherited.orElseThrow().binding().task().id()),true);
        return current(run).map(w->new State(true,active(w).isPresent() || approved(w),service.getObject().stepsSettled(w),active(w).map(e->e.binding().task().id())))
                .orElseGet(State::absent);
    }

    /** 首次载入复用宿主已核实的原绑定；只读取必要 DAG 状态，不重复来源授权。 */
    public State loadedState(RunIdentity run, AgentRunBindings.Binding binding) {
        if (!binding.owner().equals(run.owner()) || !binding.actor().equals(run.agentId()))
            throw new SecurityException("plan_run_not_owned");
        if (scope.get() != null) throw new IllegalStateException("plan_scope_nested");
        scope.set(new PlanScope(null, run, binding, 0));
        try { return state(run); }
        finally { scope.remove(); }
    }

    /**
     * 每次真实派发同事务核当前业务计划，允许只读调查及模型生成计划控制决定。
     * 只有当前已开始的主步骤绑定真实能力 Action；纯控制和计划前调查不伪造步骤归属。
     */
    public void requireDispatch(RunIdentity run, ActionRef action, ExecutionStore.Kind kind,
                                boolean readOnly, boolean finalizeOutputs, boolean delegateStart) {
        authorize(run);
        if (!action.tenantId().equals(run.runRef().tenantId())) throw new SecurityException("plan_action_tenant_invalid");
        if (kind == ExecutionStore.Kind.MODEL) return;
        if (binding(run).role() == AgentRunBindings.Role.DELEGATE) {
            // MEMBER admission先于可领取的子Run提交；没有该精确关联才是直接委派，不能套用PRIMARY会话计划模式。
            if (works.workForRun(run.runRef()).isEmpty()) return;
            if (inherited(run).isEmpty()) throw invalid("plan_member_step_not_active");
            return;
        }
        var work = current(run);
        if (work.isEmpty()) {
            if (!readOnly && service.getObject().planMode(run.runRef())) throw invalid("plan_required_before_execution");
            return;
        }
        var current = work.orElseThrow();
        if (delegateStart) throw invalid("delegate_must_use_plan_step");
        if (finalizeOutputs) { requireFinalizationReady(run); return; }
        var step = active(current);
        if (!readOnly && (!approved(current) || step.isEmpty())) throw invalid("plan_step_not_started_or_approved");
        if (step.isPresent() && !works.bindPrimaryAction(current.key(), step.orElseThrow().binding().task().id(),
                step.orElseThrow().binding().admissionId(), run.runRef(), action.actionId()))
            throw invalid("plan_action_binding_conflict");
    }

    /** 成果冻结只采用已批准且全部成功的主计划；终态失败、取消或阻塞不能等同于交付完成。 */
    public void requireFinalizationReady(RunIdentity run) {
        authorize(run);
        // 成员成果先冻结于其私有会话，不能等待父步骤完成后再提供该步骤所需文件。
        if (inherited(run).isPresent()) return;
        current(run).ifPresent(work -> {
            if (!approved(work) || !Set.of(DagWork.Status.ACTIVE, DagWork.Status.COMPLETED).contains(work.status()))
                throw invalid("plan_not_approved_for_finalization");
            for (var task : work.plan().effectiveTasks()) {
                var execution = work.executions().get(task.id());
                if (task.cancelled() || execution == null || execution.status() != DagWork.TaskStatus.COMPLETED)
                    throw invalid("plan_steps_not_complete_before_finalization");
            }
        });
    }

    @Override public void bindAction(RunIdentity run,ActionRef action) {
        authorize(run);
        if(!action.tenantId().equals(run.runRef().tenantId())) throw new SecurityException("plan_action_tenant_invalid");
        if(inherited(run).isPresent()) return;
        var work=current(run);
        if(work.isEmpty()) return;
        var w=work.orElseThrow(); var step=active(w).orElseThrow(()->invalid("plan_primary_step_required"));
        if(!action.tenantId().equals(run.runRef().tenantId()) || !works.bindPrimaryAction(w.key(),step.binding().task().id(),
                step.binding().admissionId(),run.runRef(),action.actionId())) throw invalid("plan_action_binding_conflict");
    }

    private Optional<DagWork> current(RunIdentity run) {
        return works.read(new DagWork.Key(run.owner(),run.causationInputId())).filter(w->w.request().controlRun().equals(run.runRef()));
    }
    private Optional<DagWork.Execution> inherited(RunIdentity run) {
        var binding=binding(run);
        if(binding.role()!=AgentRunBindings.Role.DELEGATE) return Optional.empty();
        return works.workForRun(run.runRef()).flatMap(works::read).flatMap(w->{
            if(!binding.parentRunRef().equals(Optional.of(w.request().controlRun()))) throw new SecurityException("plan_parent_binding_invalid");
            return w.executions().values().stream().filter(e->e.binding().runRef().equals(run.runRef())
                    && e.binding().task().executorType()==DagPlan.ExecutorType.MEMBER && e.binding().task().agentId().equals(run.agentId())
                    && !e.terminal()).findFirst();
        });
    }
    private DagWork require(RunIdentity run,String id) {
        var w=works.read(new DagWork.Key(run.owner(),new InputId(id))).orElseThrow(()->invalid("plan_missing"));
        if(!w.request().controlRun().equals(run.runRef()) || !w.coordinator().equals(run.agentId())) throw invalid("plan_not_owned");
        return w;
    }
    private void authorize(RunIdentity run) {
        var binding=binding(run);
        if(!binding.owner().equals(run.owner()) || !binding.actor().equals(run.agentId())) throw new SecurityException("plan_run_not_owned");
        if (scope.get() == null) bindings.authorize(binding);
    }
    /** 同一采纳边界复用已载入绑定；外部产品查询仍读取真实当前归属。 */
    private AgentRunBindings.Binding binding(RunIdentity run) {
        var current = scope.get();
        if (current != null) {
            if (!current.run().equals(run)) throw new SecurityException("plan_scope_run_mismatch");
            return current.binding();
        }
        return bindings.find(run.runRef()).orElseThrow(()->new SecurityException("plan_run_binding_missing"));
    }
    /** 新命令重新核当前成员可见性；同一命令的业务回执复用已经得到的目录。 */
    private List<com.skillforge.runtime.session.spi.SessionDelegations.Target> targets(RunIdentity run) {
        var current = scope.get();
        if (current == null) return delegates.getObject().targets(run);
        if (!current.run().equals(run)) throw new SecurityException("plan_scope_run_mismatch");
        var observed = visibleTargets.get();
        if (observed == null) {
            observed = List.copyOf(delegates.getObject().targets(run, current.binding()));
            visibleTargets.set(observed);
        }
        return observed;
    }
    private static Optional<DagWork.Execution> active(DagWork w) {
        return w.executions().values().stream().filter(e->e.binding().task().executorType()==DagPlan.ExecutorType.PRIMARY && !e.terminal()).findFirst();
    }
    private static boolean approved(DagWork w) { return !w.plan().approvalRequired() || w.plan().revision()==w.approvedRevision(); }
    private static boolean complete(DagWork w) { return w.status()==DagWork.Status.COMPLETED || w.status()==DagWork.Status.CANCELLED; }
    private static boolean ready(DagWork w,List<String> ids) {
        if(complete(w)) return true;
        if(!approved(w)) return false;
        return ids.stream().allMatch(id->terminalOrBlocked(w,id,new HashSet<>()));
    }
    private static boolean terminalOrBlocked(DagWork w,String id,Set<String> seen) {
        if(!seen.add(id)) return false;
        var t=w.plan().task(id); var e=w.executions().get(id);
        if(t.cancelled() || e!=null && e.terminal()) return true;
        return t.inputs().stream().anyMatch(i->{var parent=w.executions().get(i.sourceTask());
            return parent!=null && parent.terminal() && parent.status()!=DagWork.TaskStatus.COMPLETED
                    || parent==null && terminalOrBlocked(w,i.sourceTask(),seen);});
    }
    private static boolean waitsForPrimary(DagWork w,String id,Set<String> seen) {
        if(!seen.add(id)) return false;
        var task=w.plan().task(id); var e=w.executions().get(id);
        if(task.cancelled() || e!=null && e.terminal()) return false;
        return task.executorType()==DagPlan.ExecutorType.PRIMARY
                || task.inputs().stream().anyMatch(i->waitsForPrimary(w,i.sourceTask(),seen));
    }
    /** 等待操作已经返回；是否仍待审批始终以approval_pending为准，纠正也可以结束原等待。 */
    private Reply waitSatisfiedReply(RunIdentity run, DagWork work) {
        var value=snapshot(run, work);value.put("wait_satisfied",true);
        return new Reply(value,Optional.empty());
    }
    private Reply rejected(RunIdentity run, DagWork work, String reason) {
        var value = snapshot(run, work); value.put("status", "rejected"); value.put("reason", reason);
        return new Reply(value, Optional.empty());
    }
    private Reply reply(RunIdentity run, DagWork work) { return new Reply(snapshot(run, work),Optional.empty()); }
    /** 模型计划视图只使用本次取得的业务工作事实，不重建页面或运行时来源图。 */
    private Map<String,Object> snapshot(RunIdentity run, DagWork work) {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("work_id", work.key().rootInputId().value());
        snapshot.put("plan_revision", work.plan().revision());
        snapshot.put("approved_revision", work.approvedRevision() == 0 ? null : work.approvedRevision());
        snapshot.put("title", work.plan().title());
        snapshot.put("status", work.status().name().toLowerCase(Locale.ROOT));
        var currentScope = scope.get();
        snapshot.put("control_inputs", currentScope == null ? inputs.recent(work) : inputs.recentBorrowed(currentScope.session(), work));
        snapshot.put("tasks", work.plan().tasks().stream().map(task -> {
            var execution = work.executions().get(task.id());
            return new PlanTask(task.id(), task.title(), task.executorType().name(), task.agentId().value(),
                    execution == null ? task.cancelled() ? "cancelled" : "pending" : execution.status().name().toLowerCase(Locale.ROOT),
                    execution == null ? "" : execution.phase(), execution == null ? Map.of() : execution.outputs());
        }).toList());
        boolean approved=work.plan()!=null && approved(work);
        snapshot.put("approval_pending",work.plan()!=null && work.plan().approvalRequired() && !approved);
        snapshot.put("immutable_task_ids", work.plan().tasks().stream()
                .filter(t -> work.executions().containsKey(t.id())).map(DagPlan.Task::id).toList());
        snapshot.put("dependency_outputs", work.plan().tasks().stream()
                .map(t -> Map.of("task_id", t.id(), "outputs", t.outputs().stream().sorted().toList())).toList());
        var pendingTasks = work.plan().tasks().stream().filter(t -> !work.executions().containsKey(t.id())).toList();
        var targets = pendingTasks.stream().anyMatch(t -> t.executorType() == DagPlan.ExecutorType.MEMBER)
                ? targets(run) : List.<com.skillforge.runtime.session.spi.SessionDelegations.Target>of();
        snapshot.put("pending_tasks", pendingTasks.stream().map(task -> {
            String handle = task.executorType() == DagPlan.ExecutorType.PRIMARY ? "" : targets.stream()
                    .filter(target -> target.agentId().equals(task.agentId())).map(com.skillforge.runtime.session.spi.SessionDelegations.Target::handle)
                    .findFirst().orElseThrow(() -> invalid("plan_member_not_authorized"));
            var value = new LinkedHashMap<String,Object>();
            value.put("id", task.id()); value.put("title", task.title());
            value.put("executor", Map.of("type", task.executorType().name().toLowerCase(Locale.ROOT), "target_handle", handle));
            value.put("task", Map.of("objective", task.objective(), "context", task.context(), "constraints", task.constraints(), "expected_output", task.expectedOutput()));
            value.put("inputs", task.inputs().stream().map(i -> Map.of("name", i.name(), "source_task", i.sourceTask(), "output", i.output())).toList());
            value.put("outputs", task.outputs().stream().sorted().toList()); value.put("exclusive_resources", task.exclusiveResources().stream().sorted().toList());
            value.put("cancelled", task.cancelled()); value.put("replaces_task_id", task.replacesTaskId());
            return value;
        }).toList());
        active(work).ifPresent(execution -> {
            var binding = execution.binding();
            var evidence = stepEvidence(work.key().owner(), work.request().controlRun(), binding.task().id());
            snapshot.put("current_primary_step_evidence", evidenceView(binding, evidence));
        });
        return snapshot;
    }
    /** 当前计划任务的业务进度；页面合并版本与页面私有信息不进入模型材料。 */
    private record PlanTask(String id, String title, String executor, String agentId, String status,
                            String phase, Map<String, DagWork.Output> outputs) {}
    private JsonNode body(CommittedSessionEvent event) {
        try { return json.readTree(event.payload().canonicalJson()); } catch(java.io.IOException failure) { throw new IllegalStateException("plan_event_invalid",failure); }
    }
    private void append(RunIdentity run,String id,String type,Map<String,Object> value) {
        try {
            String payload=json.writeValueAsString(new TreeMap<>(value));
            var draft=new SessionEventDraft(new EventId(id),type,"1",hash(payload),run.trace(),new TypedPayload("skillforge."+type+".v1",payload),clock.instant());
            if(log.append(new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(run.owner(),run.agentId(),run.causationInputId(),
                    Optional.of(run.runRef()),Optional.empty(),Optional.empty(),Optional.empty()),draft)) instanceof SessionLogPort.AppendResult.Conflict)
                throw invalid("plan_command_conflict");
        } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private String requestFingerprint(PlanCommand command) {
        com.fasterxml.jackson.databind.node.ObjectNode value = json.valueToTree(command);
        var tasks = value.path("plan").path("tasks");
        // record集合没有顺序语义；跨进程重放不能依赖Set.copyOf的迭代顺序。
        if (tasks.isArray()) for (var task : tasks) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) task;
            for (String field : List.of("outputs", "exclusiveResources")) {
                var ordered = new TreeSet<String>();
                object.path(field).forEach(item -> ordered.add(item.asText()));
                object.set(field, json.valueToTree(ordered));
            }
        }
        return hash(value.toString());
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void applied(DagWorkPort.Commit result) { if(result==DagWorkPort.Commit.CONFLICT) throw invalid("plan_revision_conflict"); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
