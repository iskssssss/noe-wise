package com.skillforge.institution.team;
import com.skillforge.institution.runtime.AgentRunBindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.media.SessionMediaService.ResolvedContent;
import com.skillforge.institution.media.SessionMediaService.TextContent;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.institution.runtime.AgentRuntimePlanDependencies;
import com.skillforge.institution.runtime.AgentDelegationHost;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.*;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.SessionCollaboration;
import com.skillforge.runtime.session.SessionDelegationEngine;
import com.skillforge.runtime.session.dag.DagCoordinator;
import com.skillforge.runtime.session.dag.DagDriver;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 主助手按需提交的工作室 DAG；每个任务通过原生委派创建独立子 Run。 */
@Service
public class TeamWorkService implements DagDriver.Runs, DagCoordinator.Access {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(TeamWorkService.class);
    private static final Set<String> CONTROL_OPERATIONS = Set.of(
            "supplement", "adjust", "cancel", "approve_plan", "approve_operation",
            "reject_operation", "reply_input", "workflow_response");

    private final JdbcDagWorkPort works;
    private final TeamService teams;
    private final TeamRuntimeBridge runs;
    private final TeamResultService results;
    private final TeamWorkInputs inputs;
    private final ArtifactAccess artifacts;
    private final TeamRecoveryMapper mapper;
    private final ObjectMapper json;
    private final AgentRunBindings bindings;
    private final SessionDelegationEngine delegations;
    private final SessionDelegations.Store delegationStore;
    private final ExecutionStore executions;
    private final AgentExecutionCodec executionCodec;
    private final ObjectProvider<AgentRuntimePlanDependencies> planDependencies;
    private final ObjectProvider<AgentDelegationHost> delegationHost;
    private final SessionCollaboration collaboration;
    private final DagCoordinator coordinator;
    private final DagDriver driver;
    private final SessionLogPort log;
    private final Clock clock;
    private final ThreadLocal<PlanScope> planScope = new ThreadLocal<>();
    private final Set<RunRef> changing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Store已在原业务事务核当前IAM；此窄作用域只复用该次真实父身份与已加载binding。 */
    public <T> T participatePlan(RunIdentity parent,AgentRunBindings.Binding binding,java.util.function.Supplier<T> work) {
        if(binding.role()!=AgentRunBindings.Role.PRIMARY||!binding.owner().equals(parent.owner())||!binding.actor().equals(parent.agentId()))throw invalid("计划来源身份不匹配");
        var previous=planScope.get();var current=new PlanScope(parent,binding);
        if(previous!=null&&!previous.equals(current))throw invalid("计划事务来源不一致");
        planScope.set(current);try{return work.get();}finally{if(previous==null)planScope.remove();else planScope.set(previous);}
    }
    private record PlanScope(RunIdentity parent,AgentRunBindings.Binding binding) { }

    /**
     * 装配本领域持久化和协作依赖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param works DAG 工作持久化端口
     * @param teams 团队定义与发布版本服务
     * @param runs 宿主运行事实与控制桥接
     * @param results 团队成果登记与读取服务
     * @param inputs 工作控制输入服务
     * @param artifacts 产物存储能力
     * @param mapper 领域持久化入口，使用调用方事务
     * @param json 领域序列化器
     * @param clock 工作编排使用的时钟
     * @param bindings 不可变运行身份查询服务
     * @param delegations 委派执行引擎
     * @param delegationStore 委派事实存储端口
     * @param log Runtime 持久事件读取端口
     * @param executions 新执行主链的准确Run和不可变输入材料
     * @param executionCodec 新链实际材料的严格编解码器
     */
    public TeamWorkService(
            JdbcDagWorkPort works,
            TeamService teams,
            TeamRuntimeBridge runs,
            TeamResultService results,
            TeamWorkInputs inputs,
            ArtifactAccess artifacts,
            TeamRecoveryMapper mapper,
            ObjectMapper json,
            Clock clock,
            AgentRunBindings bindings,
            SessionDelegationEngine delegations,
            SessionDelegations.Store delegationStore,
            SessionLogPort log,
            ExecutionStore executions, AgentExecutionCodec executionCodec,
            ObjectProvider<AgentRuntimePlanDependencies> planDependencies,
            ObjectProvider<AgentDelegationHost> delegationHost) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "TeamWorkService", TenantContext.getTenantId());
        try {
            this.works = works;
            this.teams = teams;
            this.runs = runs;
            this.results = results;
            this.inputs = inputs;
            this.artifacts = artifacts;
            this.mapper = mapper;
            this.json = json;
            this.bindings = bindings;
            this.delegations = delegations;
            this.delegationStore = delegationStore;
            this.executions = executions;
            this.executionCodec = executionCodec;
            this.planDependencies = planDependencies;
            this.delegationHost = delegationHost;
            this.collaboration = new SessionCollaboration(log);
            this.log = log;
            this.clock = clock;
            this.coordinator = new DagCoordinator(works, this, Duration.ofMinutes(10));
            this.driver = new DagDriver(works, coordinator, this, clock);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "TeamWorkService", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "TeamWorkService", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 与计划提交共用同一CAS保存的命令指纹；先核实当前父身份，再判断恢复。 */
    public boolean planSubmitAccepted(RunIdentity parent, String command, String requestFingerprint) {
        requirePrimary(parent);
        parentInput(parent);
        var existing = works.read(new DagWork.Key(parent.owner(), parent.causationInputId()));
        if (existing.isEmpty()) return false;
        requireParent(existing.orElseThrow(), parent);
        var prior = existing.orElseThrow().consumedEvents().get(command);
        if (prior == null) return false;
        if (!prior.equals(requestFingerprint)) throw new IllegalArgumentException("plan_command_conflict");
        return true;
    }

    /** 工具调用首次提交即创建 ACTIVE 工作；普通工作室消息不会创建 DAG。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param parent 父运行身份或绑定事实
     * @param command 独立幂等命令标识
     * @param plan 待校验的任务 DAG 计划
     * @param previousRevision 调用方观察到的计划版本
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    public DagWorkPort.Commit submitPlan(
            RunIdentity parent, String command, DagPlan plan, long previousRevision, String requestFingerprint) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "submitPlan", TenantContext.getTenantId());
        try {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(plan, "plan");
            var studio = requirePrimary(parent);
            ParentInput input = parentInput(parent);
            validateTasks(parent, studio, plan);
            var key = new DagWork.Key(parent.owner(), parent.causationInputId());
            var existing = works.read(key);
            if (existing.isPresent()) {
                requireParent(existing.orElseThrow(), parent);
                var prior = existing.orElseThrow().consumedEvents().get(command);
                if (prior != null) {
                    if (!prior.equals(requestFingerprint)) throw new IllegalArgumentException("plan_command_conflict");
                    return DagWorkPort.Commit.REPLAYED;
                }
            }
            boolean planMode = planMode(parent.owner());
            boolean approvalRequired = planMode || plan.approvalRequired()
                    || existing.map(work -> work.plan().approvalRequired()).orElse(false);
            if (plan.approvalRequired() != approvalRequired) {
                throw invalid("任务计划的审批要求不能取消");
            }
            if (existing.isPresent()) {
                DagWork work = existing.orElseThrow();
                requireParent(work, parent);
                return coordinator.revise(key, command, requestFingerprint, previousRevision, plan);
            }
            if (previousRevision != 0 || plan.revision() != 1) {
                return DagWorkPort.Commit.CONFLICT;
            }
            BoundRequester requester = requester(parent, input);
            artifacts.authorizeBound(parent.runRef().tenantId().value(),
                    parent.owner().sessionRef().sessionId(), requester.requesterId(), requester.channelId());
            var request = new DagWork.Request(
                    input.text(), Optional.empty(),
                    parent.runRef(), "", DagWork.Request.Stage.WORKING,
                    input.receivedAt(), approvalRequired, requester.requesterId(), requester.channelId());
            var work = new DagWork(key, parent.agentId(), 1, DagWork.Status.ACTIVE,
                    plan, 0, 0, Map.of(), Map.of(command, requestFingerprint), request);
            boolean created = works.create(work, command, requestFingerprint);
            if (!created) {
                var raced = works.read(key);
                if (raced.isPresent()) {
                    requireParent(raced.orElseThrow(), parent);
                    var prior = raced.orElseThrow().consumedEvents().get(command);
                    if (prior != null) {
                        if (!prior.equals(requestFingerprint)) throw new IllegalArgumentException("plan_command_conflict");
                        return DagWorkPort.Commit.REPLAYED;
                    }
                }
                throw new ApiException(HttpStatus.CONFLICT, "当前会话已有其他团队工作在执行");
            }
            return DagWorkPort.Commit.APPLIED;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "submitPlan", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "submitPlan", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 在可信原 Run 的任务轮次下读取计划。 */
    public Optional<DagWork> readForRun(RunIdentity parent) {
        var work = works.read(new DagWork.Key(parent.owner(), parent.causationInputId()));
        work.ifPresent(value -> requireParent(value, parent));
        return work;
    }

    public DagWorkPort.Commit startPrimary(DagWork.Key key, String taskId, String commandId,
            java.time.Instant now) {
        return coordinator.startPrimary(key, taskId, commandId, now);
    }

    public DagWorkPort.Commit finishPrimary(DagWork.Key key, DagCoordinator.Completion completion) {
        var committed = coordinator.finishPrimary(key, completion);
        if (committed == DagWorkPort.Commit.APPLIED && planScope.get() == null) notifyPlanChanged(require(key));
        return committed;
    }

    /** 自有Store事务由外层返回后通知；普通领域写入在本次提交之后通知原父Run。 */
    private void notifyPlanChanged(DagWork work) {
        if (planScope.get() == null) planDependencies.getObject().changed(work.request().controlRun());
    }

    /** 已完成审批的业务步骤已终态或确定阻塞，不表示主 Run 已完成最终汇总。 */
    public boolean stepsSettled(DagWork work) {
        return settled(work);
    }

    private void requirePrimaryResult(DagWork work, DagWork.Execution execution,
            DagCoordinator.Completion completion) {
        if (!completion.eventId().equals(completion.sourceEvent().value())
                || !completion.runRef().equals(work.request().controlRun()))
            throw invalid("主步骤结果事件身份不匹配");
        var event = log.findByEvent(work.key().owner(), new EventId(completion.sourceEvent().value()))
                .orElseThrow(() -> invalid("主步骤结果缺少持久来源"));
        if (!event.owner().equals(work.key().owner())
                || !event.runRef().equals(Optional.of(work.request().controlRun()))
                || !event.agentId().equals(work.coordinator())
                || !event.inputId().equals(work.key().rootInputId())
                || !"plan.step.finished".equals(event.eventType()))
            throw invalid("主步骤结果来源归属不匹配");
        JsonNode payload;
        try { payload = json.readTree(event.payload().canonicalJson()); }
        catch (java.io.IOException malformed) { throw invalid("主步骤结果来源格式无效"); }
        if (!payload.isObject()
                || !work.key().rootInputId().value().equals(payload.path("work_id").asText())
                || !execution.binding().task().id().equals(payload.path("task_id").asText())
                || !execution.binding().admissionId().equals(payload.path("admission_id").asText())
                || !completion.status().name().equals(payload.path("outcome").asText())
                || !payload.path("summary").isTextual() || payload.path("summary").asText().isBlank()
                || !completion.phase().equals(payload.path("summary").asText())
                || !payload.path("result_refs").isArray() || !payload.path("outputs").isObject())
            throw invalid("主步骤结果与持久来源不一致");
        for (var ref : payload.path("result_refs")) {
            if (!ref.isTextual() || ref.asText().isBlank()) throw invalid("主步骤真实结果引用无效");
        }
        var outputNames = new HashSet<String>();
        payload.path("outputs").fieldNames().forEachRemaining(outputNames::add);
        if (!outputNames.equals(completion.outputs().keySet())) throw invalid("主步骤输出名称与持久结果不一致");
        for (var entry : completion.outputs().entrySet()) {
            var content = payload.path("outputs").path(entry.getKey());
            if (!content.isTextual() || content.asText().isBlank()) throw invalid("主步骤输出内容无效");
            var output = entry.getValue();
            var ref = new ResultRef(completion.sourceEvent().value() + "#" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!output.resultRef().equals(ref) || !summaryDigest(content.asText()).equals(output.digest())
                    || output.fulfilled() != (completion.status() == DagWork.TaskStatus.COMPLETED))
                throw invalid("主步骤输出与持久结果不一致");
        }
    }

    /** 取消只发请求；原 Run 真正结束后才关闭主步骤，避免把在途副作用当成已取消。 */
    private void settlePrimary(DagWork work) {
        for (var execution : work.executions().values()) {
            if (execution.terminal() || execution.binding().task().executorType() != DagPlan.ExecutorType.PRIMARY) continue;
            if (work.status() == DagWork.Status.CANCELLING) requestCancel(work, execution);
            var run = runs.fact(work.key().owner(), work.request().controlRun()).orElseThrow();
            if (!Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TERMINATED)
                    .contains(run.status())) continue;
            var outcome = run.status() == RunStatus.CANCELLED ? DagWork.TaskStatus.CANCELLED : DagWork.TaskStatus.FAILED;
            long cursor = 0;
            while (true) {
                var page = executions.actions(work.request().controlRun(), cursor, 100);
                for (var action : page) {
                    if(action.ordinal()<=cursor||!action.run().equals(work.request().controlRun()))throw invalid("主步骤动作来源无效");
                    cursor = action.ordinal();
                    if (works.taskForAction(work.request().controlRun(), action.reference().actionId()).equals(Optional.of(execution.binding().task().id()))) {
                        var actual=executions.attempt(action.run(),action.currentAttempt()).orElseThrow();
                        if(!actual.run().equals(action.run())||!actual.action().equals(action.reference()))throw invalid("主步骤调用来源无效");
                        if(actual.result().isEmpty()||actual.resultKind().orElseThrow()==ExecutionStore.ResultKind.UNKNOWN)outcome = DagWork.TaskStatus.OUTCOME_UNKNOWN;
                    }
                }
                if (page.size() < 100) break;
            }
            String summary = outcome == DagWork.TaskStatus.CANCELLED ? "原主 Run 已取消，步骤执行已结束" : "原主 Run 已结束，步骤未提交完成结果";
            if (outcome == DagWork.TaskStatus.OUTCOME_UNKNOWN) summary = "原主 Run 已结束，步骤外部操作结果仍待核实";
            String eventId = "plan-primary-ended:" + execution.binding().admissionId();
            var payload = Map.of("work_id", work.key().rootInputId().value(),
                    "task_id", execution.binding().task().id(), "admission_id", execution.binding().admissionId(),
                    "summary", summary, "result_refs", List.of(), "outputs", Map.of(), "outcome", outcome.name());
            String encoded;
            try { encoded = json.writeValueAsString(payload); }
            catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
            var target = new com.skillforge.runtime.api.port.SessionProjectionTarget(work.key().owner(), work.coordinator(),
                    work.key().rootInputId(), Optional.of(work.request().controlRun()), Optional.empty(), Optional.empty(), Optional.empty());
            var draft = new com.skillforge.runtime.api.port.SessionEventDraft(new EventId(eventId), "plan.step.finished", "1",
                    fingerprint(payload), run.identity().trace(), new TypedPayload("skillforge.plan.step-finished.v1", encoded), clock.instant());
            if (log.append(new SessionLogPort.AppendSessionEvent(target, draft)) instanceof SessionLogPort.AppendResult.Conflict)
                throw invalid("主步骤收尾来源冲突");
            coordinator.finishPrimary(work.key(), new DagCoordinator.Completion(eventId, execution.binding().task().id(),
                    work.request().controlRun(), execution.permit().map(DagWork.Permit::fence).orElse(1L), outcome,
                    execution.usedTokens(), Map.of(), summary, new ResultRef(eventId)));
        }
    }

    private static String summaryDigest(String summary) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(summary.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * 读取会话协作审批模式。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param run 带租户的运行引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    public boolean planMode(RunRef run) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "planMode", TenantContext.getTenantId());
        try {
            return planMode(parent(run).owner());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "planMode", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "planMode", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取会话协作审批模式。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param owner 由可信入口确定的会话所有者
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private boolean planMode(SessionOwner owner) {
        if(owner.target().kind()==com.skillforge.runtime.api.SessionTarget.Kind.GROUP)return false;
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "planMode", TenantContext.getTenantId());
        try {
            var mode = collaboration.read(owner);
            return mode.active() || mode.pending() && mode.selectedActive();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "planMode", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "planMode", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 解析控制运行对应的工作输入标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param run 带租户的运行引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    public String workId(RunRef run) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "workId", TenantContext.getTenantId());
        try {
            return works.workForControl(run).map(key -> key.rootInputId().value())
                    .orElseGet(() -> parent(run).causationInputId().value());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "workId", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "workId", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 唯一Worker的五秒领域兜底，按已授权租户限量观察原委派和DAG，不创建另一调度器。 */
    public void recover(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("team_recovery_limit_invalid");
        TenantContext.runWithTenant(tenant.value(), () -> {
            for (var key : works.recoverable(tenant, limit)) {
                if (!key.owner().sessionRef().tenantId().equals(tenant)) throw new IllegalStateException("team_recovery_tenant_mismatch");
                try { tick(key); }
                catch (RuntimeException failure) {
                    DATABASE_LOG.warn("工作室 DAG 推进暂未完成 work={} reason={}",key.rootInputId().value(),failure.getClass().getSimpleName());
                }
            }
        });
    }

    /** 精确Run业务提交后才推进其原DAG；Plan自有事务由外层Store返回后通知。 */
    public void changed(RunRef run) {
        Objects.requireNonNull(run);
        if (planScope.get() != null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { changedNow(run); }
            });
        } else changedNow(run);
    }

    private void changedNow(RunRef run) {
        if (!changing.add(run)) return;
        try {
            TenantContext.runWithTenant(run.tenantId().value(), () ->
                    works.workForControl(run).or(() -> works.workForRun(run)).ifPresent(key -> {
                        if (!key.owner().sessionRef().tenantId().equals(run.tenantId())) throw new IllegalStateException("team_change_tenant_mismatch");
                        tick(key);
                    }));
        } catch (RuntimeException unavailable) {
            DATABASE_LOG.warn("工作室 DAG 定向推进暂缓 run={} failureType={}",run.runId(),unavailable.getClass().getSimpleName());
        } finally { changing.remove(run); }
    }

    /**
     * 推进工作并处理控制输入与运行终态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param key 带所有者身份的工作键
     */
    public void tick(DagWork.Key key) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "tick", TenantContext.getTenantId());
        try {
            DagWork work = require(key);
            if (work.status() == DagWork.Status.CANCELLED) {
                reconcileCancelledParent(work);
                settleCancelledInput(work);
                notifyPlanChanged(work);
                return;
            }
            if (closed(work)) return;
            // 原主 Run 已结束或请求取消后，工作不能继续准入，也不能遗留永久待派发任务。
            var parent = runs.fact(key.owner(), work.request().controlRun()).orElseThrow();
            boolean completedPlan = parent.status() == RunStatus.COMPLETED && settled(work);
            if (work.status() == DagWork.Status.ACTIVE && !completedPlan && (parent.cancelled()
                    || Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TERMINATED)
                    .contains(parent.status()))) {
                coordinator.cancel(key, "parent-ended:" + work.request().controlRun().runId());
            }
            work = require(key);
            if (work.status() == DagWork.Status.ACTIVE && unadmittedAuthorizationRevoked(work)) {
                coordinator.cancel(key, "authorization-revoked:" + work.request().controlRun().runId() + ":" + work.revision());
            }
            processInputs(require(key));
            settlePrimary(require(key));
            driver.tick(key);
            notifyPlanChanged(work);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "tick", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "tick", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** Work结案不替代Runtime取消；从原持久cancel事件恢复同一命令身份，覆盖无主步骤/普通HITL。 */
    private void reconcileCancelledParent(DagWork work) {
        var parent = runs.fact(work.key().owner(), work.request().controlRun()).orElseThrow();
        if (parent.cancelled() || Set.of(RunStatus.COMPLETED, RunStatus.FAILED,
                RunStatus.CANCELLED, RunStatus.TERMINATED).contains(parent.status())) return;
        runs.cancel(work, work.request().controlRun(), "plan-parent-cancel:" + originalCancelCommand(work));
    }

    /** 崩溃可发生于Work取消已提交、父取消或输入回执尚未提交之间；只收尾原cancel事件对应输入。 */
    private void settleCancelledInput(DagWork work) {
        var pending = inputs.pending(work);
        if (pending.isEmpty()) return;
        String original = originalCancelCommand(work);
        for (var input : pending) {
            var control = input.control();
            if ("ACCEPTED".equals(input.state()) && "cancel".equals(control.operation()) && control.taskId().isEmpty()
                    && original.equals("control:" + control.inputId()))
                inputs.applied(work, control.inputId(), "已纳入对应工作或执行");
        }
    }

    private String originalCancelCommand(DagWork work) {
        long cursor = 0;
        while (true) {
            var page = log.readAfter(new SessionLogPort.ReadSessionLog(work.key().owner(), cursor, 100), Set.of("work.cancel_requested"));
            for (var event : page) {
                cursor = event.sessionSequence();
                if (!event.inputId().equals(work.key().rootInputId())) continue;
                if (!event.owner().equals(work.key().owner()) || !event.agentId().equals(work.coordinator())
                        || !"1".equals(event.eventVersion()) || !"dag.work.v1".equals(event.payload().schemaId()))
                    throw new IllegalStateException("plan_cancel_source_invalid");
                var command = event.trace().causedByEventId().orElseThrow(() -> new IllegalStateException("plan_cancel_source_missing"));
                return command.value();
            }
            if (page.size() < 100) throw new IllegalStateException("plan_cancel_source_missing");
        }
    }

    /** 确定撤权不能留下永远无法准入的计划；沿用取消链，瞬时基础设施失败仍交给恢复重试。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private boolean unadmittedAuthorizationRevoked(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "unadmittedAuthorizationRevoked", TenantContext.getTenantId());
        try {
            if (work.plan() == null) return false;
            var pending = work.plan().effectiveTasks().stream()
                    .filter(task -> !work.executions().containsKey(task.id())).toList();
            if (pending.isEmpty()) return false;
            var binding = bindings.find(work.request().controlRun()).orElseThrow();
            if (binding.role() != AgentRunBindings.Role.PRIMARY || !binding.owner().equals(work.key().owner())
                    || !binding.actor().equals(work.coordinator())) throw new SecurityException("work_scope_mismatch");
            for (var task : pending) {
                try {
                    if (task.executorType() == DagPlan.ExecutorType.PRIMARY) bindings.authorize(binding);
                    else bindings.authorizeTarget(binding, task.agentId().value());
                }
                catch (SecurityException denied) { return true; }
                catch (ApiException denied) {
                    if (denied.getStatus().is4xxClientError()) return true;
                    throw denied;
                }
            }
            return false;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "unadmittedAuthorizationRevoked", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "unadmittedAuthorizationRevoked", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 验证并受理独立幂等控制输入。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param control 具有独立幂等键的控制输入
     */
    public void control(DagWork work, TeamWorkInputs.Control control) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "control", TenantContext.getTenantId());
        try {
            artifacts.authorizeCurrent(tenant(work), work.key().owner().sessionRef().sessionId());
            if (!CONTROL_OPERATIONS.contains(control.operation())) throw invalid("未知的团队控制操作");
            if (!control.taskId().isEmpty()
                    && (work.plan() == null || work.plan().tasks().stream()
                    .noneMatch(task -> task.id().equals(control.taskId())))) {
                throw invalid("任务不属于当前工作");
            }
            inputs.accept(work, control);
            processInputs(work);
            notifyPlanChanged(work);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "control", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "control", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 依次消费待处理控制输入。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param initial 初始工作事实
     */
    private void processInputs(DagWork initial) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "processInputs", TenantContext.getTenantId());
        try {
            for (var input : inputs.pending(initial)) {
                DagWork current = require(initial.key());
                try {
                    if (apply(current, input)) {
                        inputs.applied(current, input.control().inputId(), "已纳入对应工作或执行");
                    }
                } catch (ApiException | IllegalArgumentException rejected) {
                    inputs.rejected(current, input.control().inputId(), rejected.getMessage());
                }
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "processInputs", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "processInputs", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 将已校验控制操作应用于工作。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param input 绑定工作输入或规范输入标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private boolean apply(DagWork work, TeamWorkInputs.Input input) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "apply", TenantContext.getTenantId());
        try {
            TeamWorkInputs.Control control = input.control();
            String command = "control:" + control.inputId();
            if ("cancel".equals(control.operation()) && control.taskId().isEmpty()
                    && (work.status() == DagWork.Status.CANCELLING || work.status() == DagWork.Status.CANCELLED)) {
                if (!command.equals(originalCancelCommand(work))) throw invalid("取消输入与原取消事实不一致");
                runs.cancel(work, work.request().controlRun(), "plan-parent-cancel:" + command);
                return true;
            }
            if (closed(work)) throw invalid("历史工作已经结束，请在聊天中发送新消息");
            if ("approve_plan".equals(control.operation())) {
                if (work.plan() == null || control.planRevision() == null
                        || control.planRevision() != work.plan().revision()) {
                    throw invalid("待批准分工版本已变化");
                }
                return coordinator.approve(work.key(), command, control.planRevision())
                        != DagWorkPort.Commit.CONFLICT;
            }
            if ("cancel".equals(control.operation()) && control.taskId().isEmpty()) {
                boolean applied = coordinator.cancel(work.key(), command) != DagWorkPort.Commit.CONFLICT;
                if (applied) runs.cancel(require(work.key()), work.request().controlRun(), "plan-parent-cancel:" + command);
                return applied;
            }
            if (control.taskId().isEmpty()
                    && Set.of("supplement", "adjust").contains(control.operation())) {
                if (control.content().isBlank()) throw invalid("补充内容不能为空");
                // 整计划补充属于原控制 Run 的 steering，不要求已有步骤，也不构成审批。
                runs.supplement(work, work.request().controlRun(), control.inputId(), control.content());
                return true;
            }
            DagWork.Execution execution = work.executions().get(control.taskId());
            if ("cancel".equals(control.operation())) {
                if (execution != null) {
                    requestCancel(work, execution);
                    return true;
                }
                var revised = work.plan().tasks().stream().map(task -> task.id().equals(control.taskId())
                        ? new DagPlan.Task(task.id(), task.title(), task.agentId(), task.executorType(), task.objective(), task.context(), task.constraints(), task.expectedOutput(),
                                task.inputs(), task.outputs(), task.exclusiveResources(), true, task.replacesTaskId())
                        : task).toList();
                return coordinator.revise(work.key(), command, fingerprint(control), work.plan().revision(),
                        new DagPlan(work.plan().revision() + 1, work.plan().approvalRequired(), revised,
                                work.plan().title(), "取消未开始步骤"))
                        != DagWorkPort.Commit.CONFLICT;
            }
            if (execution == null) throw invalid("任务尚未开始，不能接收执行输入");
            RunRef child = execution.binding().runRef();
            if (Set.of("approve_operation", "reject_operation", "reply_input", "workflow_response")
                    .contains(control.operation())) {
                if (control.pendingId().isBlank() || control.parametersVersion().isBlank()) {
                    throw invalid("回复必须绑定待确认操作及其参数版本");
                }
                if (execution.status() == DagWork.TaskStatus.WAITING) {
                    var requested = coordinator.requestResume(work.key(), control.taskId(), command,
                            new ResultRef("input:" + control.inputId()));
                    if (requested == DagWorkPort.Commit.CONFLICT) return false;
                    work = require(work.key());
                    coordinator.advance(work.key(), "resume-permit:" + control.inputId(), input.receivedAt());
                    work = require(work.key());
                    execution = work.executions().get(control.taskId());
                }
                boolean earlyBatch = execution.status() == DagWork.TaskStatus.RUNNING
                        && runs.fact(work,child).filter(value -> !value.cancelled() && value.currentBatch().isPresent()
                                && (value.phase() == ExecutionStore.Phase.AWAITING_RESULT || value.phase() == ExecutionStore.Phase.WAITING
                                    || value.phase() == ExecutionStore.Phase.READY)).isPresent();
                if (execution.status() != DagWork.TaskStatus.RESERVED && !earlyBatch) {
                    throw invalid("任务尚未取得恢复执行许可");
                }
                String operation = switch (control.operation()) {
                    case "approve_operation" -> "approve";
                    case "reject_operation" -> "reject";
                    case "workflow_response" -> "workflow";
                    default -> "reply";
                };
                runs.respond(work, child, control.inputId(), operation,
                        control.pendingId(), control.parametersVersion(), control.content(), input.receivedAt());
                return true;
            }
            if (control.content().isBlank()) throw invalid("补充内容不能为空");
            if (execution.terminal()) throw invalid("历史任务不可原地复活，请在聊天中发送新消息");
            runs.supplement(work, child, control.inputId(), control.content());
            return true;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "apply", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "apply", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验控制入口确已受理请求。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     */
    @Override
    public void ensureAccepted(DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "ensureAccepted", TenantContext.getTenantId());
        try {
            // 当前Store已持root/父Run/DAG写锁；原RESERVED事实在外层提交后再由同一driver受理。
            if (planScope.get() != null) return;
            if (execution.binding().task().executorType() != DagPlan.ExecutorType.MEMBER)
                throw invalid("主 Agent 步骤不能通过委派启动");
            RunIdentity parent = parent(work);
            var existing = delegationStore.read(parent,delegationId(parent,execution));
            if (existing.isPresent()) {
                delegationHost.getObject().admitPlan(existing.orElseThrow(),work.key(),execution.binding(),execution.permit().orElseThrow());
                return;
            }
            var task = execution.binding().task();
            var target = delegations.targets(parent).stream()
                    .filter(value -> value.agentId().equals(task.agentId())).toList();
            if (target.size() != 1) {
                coordinator.stopBeforeDispatch(work.key(), task.id(),
                        "not-dispatched:" + execution.binding().admissionId(),
                        DagWork.TaskStatus.FAILED, "执行前成员授权不可用");
                return;
            }
            var host = delegationHost.getObject();
            var entry = delegations.start(parent, "dag:" + execution.binding().admissionId(),
                    target.getFirst(), host.planTask(work,execution.binding()), Optional.empty());
            if (!entry.childRun().equals(execution.binding().runRef())) {
                throw new IllegalStateException("dag_delegation_run_mismatch");
            }
            host.admitPlan(entry,work.key(),execution.binding(),execution.permit().orElseThrow());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "ensureAccepted", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "ensureAccepted", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取任务执行状态观测。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Override
    public Optional<DagCoordinator.Completion> observation(
            DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "observation", TenantContext.getTenantId());
        try {
            if (execution.binding().task().executorType() != DagPlan.ExecutorType.MEMBER) return Optional.empty();
            RunIdentity parent = parent(work);
            var entry = delegationStore.read(parent, delegationId(parent, execution));
            if (entry.isEmpty()) return Optional.empty();
            var delegation = entry.orElseThrow();
            if (delegation.state() == SessionDelegations.State.QUEUED) return Optional.empty();
            DagWork.TaskStatus status;
            String phase;
            Map<String, DagWork.Output> outputs = Map.of();
            switch (delegation.state()) {
                case RUNNING -> { status = DagWork.TaskStatus.RUNNING; phase = "成员正在执行"; }
                case WAITING -> { status = DagWork.TaskStatus.WAITING; phase = "等待该任务的输入或确认"; }
                case COMPLETED -> {
                    ResultRef evidence = evidence(delegation);
                    results.captureCompletion(work, execution, evidence);
                    outputs = results.outputs(work, execution);
                    status = outputs.keySet().containsAll(execution.binding().task().outputs())
                            ? DagWork.TaskStatus.COMPLETED : DagWork.TaskStatus.FAILED;
                    phase = status == DagWork.TaskStatus.COMPLETED ? "产出已保存" : "完成输出不符合声明格式";
                }
                case FAILED -> {
                    status = delegation.result().filter(value ->
                            "external_effect_outcome_unknown".equals(value.reason())).isPresent()
                            ? DagWork.TaskStatus.OUTCOME_UNKNOWN : DagWork.TaskStatus.FAILED;
                    phase = delegation.result().map(SessionDelegations.Result::reason).orElse("成员执行失败");
                }
                case CANCELLED -> { status = DagWork.TaskStatus.CANCELLED; phase = "执行已取消"; }
                default -> throw new IllegalStateException("delegation_state_unreachable");
            }
            long tokens = delegation.result().map(SessionDelegations.Result::usedTokens)
                    .orElse(execution.usedTokens());
            String event = "delegation-state:" + delegation.id() + ":" + delegation.revision();
            long fence = execution.permit().map(DagWork.Permit::fence).orElse(1L);
            return Optional.of(new DagCoordinator.Completion(event,
                    execution.binding().task().id(), execution.binding().runRef(), fence,
                    status, tokens, outputs, phase, evidence(delegation)));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "observation", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "observation", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 请求取消工作和关联执行。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     */
    @Override
    public void requestCancel(DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requestCancel", TenantContext.getTenantId());
        try {
            if (execution.binding().task().executorType() == DagPlan.ExecutorType.PRIMARY) {
                runs.cancel(work, work.request().controlRun(), "plan-primary-cancel:" + execution.binding().admissionId());
                return;
            }
            RunIdentity parent = parent(work);
            var entry = delegationStore.read(parent, delegationId(parent, execution));
            if (entry.isEmpty()) {
                coordinator.stopBeforeDispatch(work.key(), execution.binding().task().id(),
                        "cancel-undispatched:" + execution.binding().admissionId(),
                        DagWork.TaskStatus.CANCELLED, "任务在委派受理前已取消");
                return;
            }
            delegations.cancel(entry.orElseThrow(), "dag-cancel:" + execution.binding().admissionId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requestCancel", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requestCancel", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 依据可信运行事实完成任务。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Override
    public Optional<ResultRef> finish(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "finish", TenantContext.getTenantId());
        try {
            if (work.status() == DagWork.Status.ACTIVE) {
                var run = runs.fact(work.key().owner(), work.request().controlRun()).orElseThrow();
                if (!Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TERMINATED)
                        .contains(run.status())) return Optional.empty();
            }
            return settled(work) ? Optional.of(closure(work)) : Optional.empty();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "finish", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "finish", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 判断当前操作是否需要延期推进。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param phase 当前执行阶段
     * @param failure 执行失败分类或事实
     */
    @Override
    public void deferred(DagWork work, DagWork.Execution execution,
            String phase, RuntimeException failure) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "deferred", TenantContext.getTenantId());
        try {
            DATABASE_LOG.warn("工作室任务暂未完成 work={} task={} phase={} reason={}",
                    work.key().rootInputId().value(), execution.binding().task().id(),
                    phase, failure.getClass().getSimpleName());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "deferred", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "deferred", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 定位仍有效的任务执行事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param task 计划内任务标识或定义
     * @param bound 已经冻结的任务输入
     * @param resume 请求恢复的执行事实
     */
    @Override
    public void requireExecution(DagWork work, DagPlan.Task task,
            Map<String, DagWork.BoundInput> bound, boolean resume) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requireExecution", TenantContext.getTenantId());
        try {
            try {
                if (task.executorType() == DagPlan.ExecutorType.MEMBER && task.agentId().equals(work.coordinator())) {
                    throw new DagCoordinator.AdmissionDenied("primary_cannot_be_dag_delegate");
                }
                if (!resume && work.plan().approvalRequired()
                        && work.approvedRevision() != work.plan().revision()) {
                    throw new DagCoordinator.AdmissionDenied("plan_not_approved");
                }
                var binding=bindings.find(work.request().controlRun()).orElseThrow();
                if(!binding.owner().equals(work.key().owner()))throw new DagCoordinator.AdmissionDenied("parent_scope_mismatch");
                if (task.executorType() == DagPlan.ExecutorType.PRIMARY) {
                    if (!task.agentId().equals(work.coordinator()) || binding.role() != AgentRunBindings.Role.PRIMARY
                            || !binding.actor().equals(work.coordinator()))
                        throw new DagCoordinator.AdmissionDenied("primary_scope_mismatch");
                    bindings.authorize(binding);
                } else bindings.authorizeTarget(binding,task.agentId().value());
                for (var value : bound.values()) results.requireShare(work, value, task.agentId());
            } catch (DagCoordinator.AdmissionDenied denied) {
                throw denied;
            } catch (RuntimeException denied) {
                throw new DagCoordinator.AdmissionDenied("当前成员或输入授权不可用");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requireExecution", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requireExecution", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取绑定工作内的成果，不允许跨工作引用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param completion 可信运行完成事实
     */
    @Override
    public void requireResult(DagWork work, DagWork.Execution execution,
            DagCoordinator.Completion completion) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requireResult", TenantContext.getTenantId());
        try {
            if (execution.binding().task().executorType() == DagPlan.ExecutorType.PRIMARY) {
                requirePrimaryResult(work, execution, completion);
                return;
            }
            RunIdentity parent = parent(work);
            var entry = delegationStore.read(parent, delegationId(parent, execution)).orElseThrow();
            if (!entry.childRun().equals(completion.runRef())
                    || !entry.target().agentId().equals(execution.binding().task().agentId())
                    || !evidence(entry).equals(completion.sourceEvent())) {
                throw invalid("任务结果缺少真实委派来源");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requireResult", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requireResult", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验恢复条件与输入版本。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param controlResult 控制入口受理结果
     */
    @Override
    public void requireResume(DagWork work, DagWork.Execution execution, ResultRef controlResult) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requireResume", TenantContext.getTenantId());
        try {
            if (!controlResult.value().startsWith("input:")) {
                throw invalid("缺少原执行的恢复输入");
            }
            String inputId = controlResult.value().substring("input:".length());
            var control = inputs.pending(work).stream()
                    .map(TeamWorkInputs.Input::control)
                    .filter(value -> value.inputId().equals(inputId)
                            && value.taskId().equals(execution.binding().task().id()))
                    .findFirst().orElseThrow(() -> invalid("恢复输入未持久受理"));
            var pending = runs.pending(work, execution.binding().runRef())
                    .orElseThrow(() -> invalid("任务没有对应的待确认操作"));
            if (!pending.reference().equals(control.pendingId())) throw invalid("待确认操作已变化");
            try {long version=Long.parseLong(control.parametersVersion());if(version<1||!Long.toString(version).equals(control.parametersVersion()))throw invalid("等待版本无效");}
            catch(NumberFormatException malformed){throw invalid("等待版本无效");}

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requireResume", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requireResume", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验控制操作所需状态及权限。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param control 具有独立幂等键的控制输入
     * @param command 独立幂等命令标识
     */
    @Override
    public void requireControl(DagWork work, DagCoordinator.Control control, String command) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requireControl", TenantContext.getTenantId());
        try {
            if (control != DagCoordinator.Control.CANCEL) {
                bindings.authorize(bindings.find(work.request().controlRun()).orElseThrow());
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requireControl", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requireControl", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验工作闭合所需证据。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param result 已校验的团队成果
     */
    @Override
    public void requireClosure(DagWork work, ResultRef result) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requireClosure", TenantContext.getTenantId());
        try {
            if (!settled(work) || !closure(work).equals(result)) {
                throw invalid("团队工作尚未形成可验证的结果闭包");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requireClosure", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requireClosure", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 解析运行所有者。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param session 绑定会话标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    public static SessionOwner owner(SessionView session) {
        if(session.getGroupBindingId()!=null)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"group_execution_not_available","群执行尚未开放",false);
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "owner", TenantContext.getTenantId());
        try {
            return SessionOwner.user(
                    new SessionRef(new TenantId(session.getTenantId().toString()), session.getSessionId()),
                    session.getTeamId()==null?SessionTarget.agent(new AgentId(session.getAgentId().toString())):SessionTarget.team(session.getTeamId()), new EndUserId(session.getEndUserId()));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "owner", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "owner", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 核验当前运行是有权编排的主运行。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param parent 父运行身份或绑定事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private AgentRunBindings.Binding requirePrimary(RunIdentity parent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requirePrimary", TenantContext.getTenantId());
        try {
            var scope=planScope.get();if(scope!=null){if(!scope.parent().equals(parent))throw invalid("计划父执行不匹配");return scope.binding();}
            var binding = bindings.find(parent.runRef())
                    .orElseThrow(() -> invalid("仅获准的主 Agent 可提交分工"));
            if (binding.role() != AgentRunBindings.Role.PRIMARY
                    || !binding.owner().equals(parent.owner())
                    || !binding.actor().equals(parent.agentId())) {
                throw invalid("仅获准的主 Agent 可提交分工");
            }
            bindings.authorize(binding);
            var run = runs.fact(parent.owner(), parent.runRef()).orElseThrow();
            if (!run.identity().equals(parent)
                    || (run.status() != RunStatus.RUNNING && run.status() != RunStatus.SUSPENDED)) {
                throw invalid("主助手执行绑定失效");
            }
            return binding;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requirePrimary", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requirePrimary", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验计划成员和任务声明。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param parent 父运行身份或绑定事实
     * @param studio 冻结的团队协作范围
     * @param plan 待校验的任务 DAG 计划
     */
    private void validateTasks(RunIdentity parent, AgentRunBindings.Binding studio, DagPlan plan) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "validateTasks", TenantContext.getTenantId());
        try {
            for (var task : plan.tasks()) {
                if (task.executorType() == DagPlan.ExecutorType.PRIMARY) {
                    if (!task.agentId().equals(parent.agentId())) throw invalid("主步骤必须由当前主 Agent 执行");
                } else bindings.authorizeTarget(studio, task.agentId().value());
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "validateTasks", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "validateTasks", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取控制运行事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param run 带租户的运行引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private RunIdentity parent(RunRef run) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "parent", TenantContext.getTenantId());
        try {
            var scope=planScope.get();if(scope!=null){if(!scope.parent().runRef().equals(run))throw invalid("计划父执行不匹配");return scope.parent();}
            var binding = bindings.find(run).orElseThrow(() -> invalid("主 Agent 执行不存在"));
            if (binding.role() != AgentRunBindings.Role.PRIMARY) throw invalid("执行不是主 Agent");
            return runs.fact(binding.owner(), run).orElseThrow().identity();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "parent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "parent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取控制运行事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private RunIdentity parent(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "parent", TenantContext.getTenantId());
        try {
            RunIdentity parent = parent(work.request().controlRun());
            requireParent(work, parent);
            return parent;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "parent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "parent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 核验工作与父运行绑定一致。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param parent 父运行身份或绑定事实
     */
    private void requireParent(DagWork work, RunIdentity parent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requireParent", TenantContext.getTenantId());
        try {
            if (!work.key().owner().equals(parent.owner())
                    || !work.key().rootInputId().equals(parent.causationInputId())
                    || !work.request().controlRun().equals(parent.runRef())
                    || !work.coordinator().equals(parent.agentId())) {
                throw new IllegalStateException("dag_parent_binding_mismatch");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requireParent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requireParent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取父运行原始输入事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param parent 父运行身份或绑定事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private ParentInput parentInput(RunIdentity parent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "parentInput", TenantContext.getTenantId());
        try {
            var run = executions.load(parent.runRef()).orElseThrow();
            if (!run.identity().equals(parent)) {
                throw new IllegalStateException("dag_parent_input_mismatch");
            }
            var original=executions.material(parent.runRef().tenantId(),new ResultRef("execution-input:"+parent.runRef().runId())).orElseThrow();
            var frozen=executionCodec.frozenInput(original.content());
            try {
                FrozenInput input = json.readValue(frozen.canonicalJson(), FrozenInput.class);
                JsonNode extra = input.extraParameters();
                // 父目标沿用当前内容顺序；图片不成为文字，文本分片之间不新增分隔符。
                String text = input.content() == null ? "" : input.content().stream()
                        .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                        .map(TextContent::text).collect(Collectors.joining()).strip();
                if (text.isBlank()) throw invalid("父工作目标不能为空");
                return new ParentInput(text, extra.path("delegation_requester_id").asText(""),
                        extra.path("delegation_channel_id").asText(""), run.createdAt());
            } catch (java.io.IOException malformed) {
                throw new IllegalStateException("dag_parent_input_invalid", malformed);
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "parentInput", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "parentInput", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 解析经过入口认证覆盖的请求者信息。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param parent 父运行身份或绑定事实
     * @param input 绑定工作输入或规范输入标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private BoundRequester requester(RunIdentity parent, ParentInput input) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "requester", TenantContext.getTenantId());
        try {
            var scope=planScope.get();var binding=scope!=null&&scope.parent().equals(parent)?scope.binding():bindings.find(parent.runRef()).orElseThrow();
            if(!binding.owner().equals(parent.owner()))throw invalid("父工作身份不匹配");
            var requester=binding.scope().requester();
            if(!requester.requesterId().equals(input.requesterId())||!requester.channelId().equals(input.channelId()))
                throw invalid("父输入的认证身份不匹配");
            return new BoundRequester(requester.requesterId(),requester.channelId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "requester", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "requester", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 判断任务是否已结算。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private boolean settled(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "settled", TenantContext.getTenantId());
        try {
            return work.plan() != null && com.skillforge.runtime.session.dag.DagSettlement.settled(work);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "settled", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "settled", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 生成工作闭合状态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private ResultRef closure(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "closure", TenantContext.getTenantId());
        try {
            return new ResultRef("work-results:" + work.key().rootInputId().value() + ":" + work.revision());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "closure", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "closure", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 生成稳定委派关联标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param parent 父运行身份或绑定事实
     * @param execution 当前任务执行事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String delegationId(RunIdentity parent, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "delegationId", TenantContext.getTenantId());
        try {
            return SessionDelegationEngine.id(parent.runRef(),
                    "dag:" + execution.binding().admissionId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "delegationId", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "delegationId", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取任务完成证据。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param entry 已持久化的委派登记事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private ResultRef evidence(SessionDelegations.Entry entry) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "evidence", TenantContext.getTenantId());
        try {
            return entry.result().flatMap(value -> value.evidence().stream().findFirst())
                    .map(ResultRef::new)
                    .orElseGet(() -> new ResultRef(
                            "delegation-state:" + entry.id() + ":" + entry.revision()));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "evidence", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "evidence", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取领域事实，不存在时抛出业务异常。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param key 带所有者身份的工作键
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private DagWork require(DagWork.Key key) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "require", TenantContext.getTenantId());
        try {
            return works.read(key).orElseThrow(() -> invalid("团队工作不存在"));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "require", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "require", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 判断工作是否已终结。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static boolean closed(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "closed", TenantContext.getTenantId());
        try {
            return work.status() == DagWork.Status.COMPLETED
                    || work.status() == DagWork.Status.CANCELLED;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "closed", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "closed", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取所属租户标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static String tenant(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "tenant", TenantContext.getTenantId());
        try {
            return work.key().owner().sessionRef().tenantId().value();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "tenant", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "tenant", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 计算规范命令指纹供重放冲突校验。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param values 待合并的领域值集合
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String fingerprint(Object... values) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "fingerprint", TenantContext.getTenantId());
        try {
            try {
                byte[] payload = json.writeValueAsBytes(values);
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(payload));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
                throw new IllegalArgumentException("cannot fingerprint team plan", failure);
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "fingerprint", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "fingerprint", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 构造契约校验失败响应。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param message 公开错误说明
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static ApiException invalid(String message) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkService", "invalid", TenantContext.getTenantId());
        try {
            return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkService", "invalid", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkService", "invalid", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FrozenInput(List<ResolvedContent> content,
            @JsonProperty("extraParameters") JsonNode extraParameters) { }

    private record ParentInput(
            String text, String requesterId, String channelId,
            java.time.Instant receivedAt) { }

    private record BoundRequester(String requesterId, String channelId) { }
}
