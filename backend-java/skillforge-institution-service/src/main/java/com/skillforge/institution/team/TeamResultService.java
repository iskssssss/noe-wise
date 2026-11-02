package com.skillforge.institution.team;

import com.skillforge.institution.runtime.AgentRunBindings;

import com.skillforge.common.context.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.ArtifactMetadata;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.PortalCallKind;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存团队任务的不可变成果事实。
 *
 * <p>该表拥有成果正文；实际Run、选定Attempt及结果材料由新执行存储拥有，
 * Plan步骤完成事件由原计划领域拥有。成果引用不建立第二套任务生命周期。
 */
@Service
public class TeamResultService {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(TeamResultService.class);
    private static final int TEXT_LIMIT = 65_536;
    private final TeamResultMapper mapper;
    private final ObjectMapper json;
    private final ArtifactStorage artifacts;
    private final ArtifactAccess artifactAccess;
    private final TeamService teams;
    private final TeamRuntimeFacts runtimeFacts;
    /** Plan步骤的实际领域完成事件；不读取旧Runtime执行上下文或Run事实。 */
    private final com.skillforge.runtime.api.port.SessionLogPort planEvents;
    private final AgentRunBindings bindings;
    private final com.skillforge.core.chat.integration.publicread.PublicOutputGuard publicOutputs;

    /**
     * 装配本领域持久化和协作依赖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param mapper 领域持久化入口，使用调用方事务
     * @param json 领域序列化器
     * @param artifacts 产物存储能力
     * @param artifactAccess 产物访问权校验入口
     * @param teams 团队定义与发布版本服务
     * @param runtimeFacts 新主链准确已采纳结果的读取边界
     * @param planEvents 计划步骤的真实领域完成事件读取端口
     * @param bindings 不可变运行身份查询服务
     * @param publicOutputs 公开输出边界检查器
     */
    public TeamResultService(TeamResultMapper mapper, ObjectMapper json, ArtifactStorage artifacts,
            ArtifactAccess artifactAccess, TeamService teams, TeamRuntimeFacts runtimeFacts, com.skillforge.runtime.api.port.SessionLogPort planEvents,
            AgentRunBindings bindings, com.skillforge.core.chat.integration.publicread.PublicOutputGuard publicOutputs) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "TeamResultService", TenantContext.getTenantId());
        try {
            this.mapper = mapper;
            this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            this.artifacts = artifacts;
            this.artifactAccess = artifactAccess;
            this.teams = teams;
            this.runtimeFacts = Objects.requireNonNull(runtimeFacts);
            this.planEvents=Objects.requireNonNull(planEvents);
            this.bindings = bindings;
            this.publicOutputs = publicOutputs;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "TeamResultService", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "TeamResultService", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 由 submit_task_result 使用；身份、任务和命令标识只取可信 Runtime 上下文。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param submissions 待原子登记的成果批次
     * @param context 当前平台工具执行上下文
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional
    public List<Saved> submit(DagWork work, DagWork.Execution execution, List<Submission> submissions,
            PlatformToolExecutionContext context) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "submit", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            Objects.requireNonNull(context, "context");
            requireCurrentExecution(work, execution, context.run().runRef().runId(), true);
            if (!context.run().owner().equals(runOwner(work, execution))
                    || !context.run().agentId().equals(execution.binding().task().agentId())) {
                throw rejected("task_result_run_identity_mismatch");
            }
            if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt())) {
                throw rejected("task_result_submission_cancelled");
            }
            return persist(work, execution, submissions, new TeamTaskResult.Source("tool_attempt", "",
                            context.invocationId(), context.attemptId(), context.run().runRef().runId()),
                    context.invocationId(), context.attemptId(), context);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "submit", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "submit", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 返回真实登记且成功的输出；该内部事实读取不因成员随后撤权而改写历史。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public Map<String, DagWork.Output> outputs(DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "outputs", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            requireCurrentExecution(work, execution, execution.binding().runRef().runId(), false);
            Map<String, DagWork.Output> selected = new TreeMap<>();
            for (Stored row : rows(work, execution.binding().task().id(), execution.binding().runRef().runId())) {
                if (!"succeeded".equals(row.result().status())) continue;
                selected.put(row.result().outputName(),
                        new DagWork.Output(new ResultRef(row.result().resultId()), row.payloadSha256(), true));
            }
            return Map.copyOf(selected);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "outputs", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "outputs", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 当前用户只能看到仍有 Session 权限、文件仍可读且来源成员仍启用的成果。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public List<TeamPublicViews.Result> view(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "view", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            if (work.plan() == null) return List.of();
            String tenant = tenant(work);
            artifactAccess.authorizeCurrent(tenant, session(work));
            Map<String, ArtifactMetadata> available = availableArtifacts(work);
            List<TeamPublicViews.Result> visible = new ArrayList<>();
            for (Stored row : rows(work, null, null)) {
                DagWork.Execution execution = work.executions().get(row.result().taskId());
                if (execution == null) continue;
                if (execution.binding().task().executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY
                        && execution.outputs().values().stream().noneMatch(output->output.resultRef().value().equals(row.result().resultId()))) continue;
                try {
                    requireReadable(work,execution.binding().task().agentId().value());
                    requireVisibleArtifacts(row.result(), available);
                    visible.add(publicView(row,publicOutputs.capture(runOwner(work,execution))));
                } catch (ApiException | SecurityException revoked) {
                    // 成员、会话或文件权限撤销后不披露该条成果，也不改变不可变事实。
                }
            }
            for (var execution:work.executions().values()) {
                if (execution.binding().task().executorType()!=com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY || !execution.terminal() || execution.sourceEvent().isEmpty()) continue;
                requireReadable(work,work.coordinator().value());
                for (var name:execution.binding().task().outputs()) visible.add(publicView(primaryResult(work,execution,name),publicOutputs.capture(work.key().owner())));
            }
            return List.copyOf(visible);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "view", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "view", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 只解析冻结的输入引用；调用时重新检查目标成员、Session、摘要及文件可用性。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param input 绑定工作输入或规范输入标识
     * @param target 成果接收目标 Agent
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public String inputText(DagWork work, DagWork.BoundInput input, AgentId target) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "inputText", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            if (work.plan() == null) throw rejected("task_result_work_not_planned");
            String tenant = tenant(work);
            authorizeRecipient(work,target);
            authorizeOwner(work);
            boolean assigned = work.executions().values().stream()
                    .filter(execution -> execution.binding().task().agentId().equals(target))
                    .flatMap(execution -> execution.binding().inputs().values().stream())
                    .anyMatch(input::equals);
            if (!assigned) throw rejected("task_result_input_not_assigned");
            Stored stored = requireBoundResult(work, input);
            requireVisibleArtifacts(stored.result(), availableArtifacts(work));
            return resultText(stored);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "inputText", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "inputText", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 首次准入前验证冻结上游成果可以共享给目标成员；此时目标任务尚不存在 admission。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param input 绑定工作输入或规范输入标识
     * @param target 成果接收目标 Agent
     */
    @Transactional(readOnly = true)
    public void requireShare(DagWork work, DagWork.BoundInput input, AgentId target) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireShare", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            if (work.plan() == null) throw rejected("task_result_work_not_planned");
            String tenant = tenant(work);
            authorizeRecipient(work,target);
            authorizeOwner(work);
            boolean declared = work.plan().tasks().stream()
                    .filter(task -> task.agentId().equals(target))
                    .flatMap(task -> task.inputs().stream())
                    .anyMatch(value -> value.sourceTask().equals(input.sourceTask())
                            && value.output().equals(input.output()));
            if (!declared) throw rejected("task_result_input_not_declared");
            Stored stored = requireBoundResult(work, input);
            requireVisibleArtifacts(stored.result(), availableArtifacts(work));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireShare", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireShare", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 供原主助手读取完成结果；调用方先验证该父 Run，内容再按当前成员和文件授权过滤。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report(DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "report", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            requireCurrentExecution(work, execution, execution.binding().runRef().runId(), false);
            requireReadable(work,execution.binding().task().agentId().value());
            if (execution.binding().task().executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY) {
                return execution.binding().task().outputs().stream().map(name->{
                    var row=primaryResult(work,execution,name);
                    return Map.<String,Object>of("output",name,"status",row.result().status(),"content",resultText(row),
                            "result_id",row.result().resultId(),"digest",row.payloadSha256());
                }).toList();
            }
            var available = availableArtifacts(work);
            List<Map<String, Object>> visible = new ArrayList<>();
            for (Stored row : rows(work, execution.binding().task().id(), execution.binding().runRef().runId())) {
                requireVisibleArtifacts(row.result(), available);
                visible.add(Map.of("output", row.result().outputName(), "status", row.result().status(),
                        "content", resultText(row), "result_id", row.result().resultId(), "digest", row.payloadSha256()));
            }
            return List.copyOf(visible);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "report", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "report", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 从已核验Run完成事实登记成果；多输出格式不完整时返回空成果，由DAG判定失败。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param evidence 可信完成证据引用
     */
    @Transactional
    public void captureCompletion(DagWork work, DagWork.Execution execution, ResultRef evidence) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "captureCompletion", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            requireCurrentExecution(work, execution, execution.binding().runRef().runId(), false);
            var run = runtimeFacts.run(runOwner(work, execution), execution.binding().runRef());
            if (run.status() != RunStatus.COMPLETED
                    || !run.identity().agentId().equals(execution.binding().task().agentId())
                    || !evidence.equals(new ResultRef("execution-state:" + run.identity().runRef().runId() + ":" + run.revision())))
                throw rejected("task_result_run_evidence_mismatch");
            // 委派摘要可追加产物说明；成果正文只取同一Run已采纳的权威完成事实。
            String text = runtimeFacts.completedText(run);
            if (outputs(work, execution).keySet().containsAll(execution.binding().task().outputs())) return;
            if (execution.binding().task().outputs().size() == 1) {
                String output = execution.binding().task().outputs().iterator().next();
                boolean truncated = text.length() > TEXT_LIMIT;
                String content = truncated ? text.substring(0, TEXT_LIMIT) : text;
                var limitations = new ArrayList<String>();
                limitations.add("纯文本成果最多保留 65536 个字符，不包含私有推理或隐藏运行上下文");
                if (truncated) limitations.add("原始完成文本超过内容上限，本成果已截断");
                var submission = new Submission(output, execution.binding().task().title(), "text", "succeeded",
                        summarize(content), content, List.of(), limitations, null);
                String command = "runtime-capture:" + hash(evidence.value());
                persist(work, execution, List.of(submission), new TeamTaskResult.Source("run_completion", evidence.value(),
                        command, command, execution.binding().runRef().runId()), command, command, null);
                return;
            }
            com.fasterxml.jackson.databind.JsonNode root;
            try { root = json.readTree(text); } catch (JsonProcessingException invalid) { return; }
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("outputs").isObject()) return;
            var reported = root.path("outputs");
            Set<String> names = new HashSet<>(); reported.fieldNames().forEachRemaining(names::add);
            if (!names.equals(execution.binding().task().outputs())) return;
            List<Submission> submissions = new ArrayList<>();
            for (String name : names.stream().sorted().toList()) {
                if (!reported.path(name).isTextual() || reported.path(name).asText().isBlank()) return;
                String original = reported.path(name).asText().strip();
                String content = original.substring(0, Math.min(TEXT_LIMIT, original.length()));
                var limitations = List.of(original.length() > TEXT_LIMIT ? "文本超过 65536 字符，已截断" : "文本成果不包含私有运行上下文");
                submissions.add(new Submission(name, execution.binding().task().title(), "text", "succeeded",
                        summarize(content), content, List.of(), limitations, null));
            }
            String command = "runtime-capture:" + hash(evidence.value());
            persist(work, execution, submissions, new TeamTaskResult.Source("run_completion", evidence.value(),
                    command, command, execution.binding().runRef().runId()), command, command, null);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "captureCompletion", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "captureCompletion", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 解析并核对任务运行的真实所有者。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private com.skillforge.runtime.api.port.SessionOwner runOwner(DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "runOwner", TenantContext.getTenantId());
        try {
            var binding = bindings.find(execution.binding().runRef()).orElseThrow(() -> rejected("task_result_binding_missing"));
            if (execution.binding().task().executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY) {
                if (!execution.binding().runRef().equals(work.request().controlRun()) || !binding.owner().equals(work.key().owner())
                        || !binding.actor().equals(work.coordinator())) throw rejected("task_result_binding_mismatch");
                return binding.owner();
            }
            if (binding.role() != AgentRunBindings.Role.DELEGATE
                    || !binding.parentRunRef().equals(java.util.Optional.of(work.request().controlRun()))
                    || !binding.actor().equals(execution.binding().task().agentId())) throw rejected("task_result_binding_mismatch");
            return binding.owner();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "runOwner", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "runOwner", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 在同一事务内校验冲突并持久化不可变事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param submissions 待原子登记的成果批次
     * @param source 成果来源事实
     * @param invocationId 平台调用幂等标识
     * @param attemptId 本次尝试标识，用于核验重复提交
     * @param context 当前平台工具执行上下文
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private List<Saved> persist(DagWork work, DagWork.Execution execution, List<Submission> submissions,
            TeamTaskResult.Source source, String invocationId, String attemptId,
            PlatformToolExecutionContext context) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "persist", TenantContext.getTenantId());
        try {
            if (submissions == null || submissions.isEmpty() || submissions.size() > 32)
                throw rejected("task_result_outputs_rejected");
            String tenant = tenant(work);
            if ("tool_attempt".equals(source.kind())) {
                requireReadable(work,execution.binding().task().agentId().value());
                authorizeOwner(work);
            }
            mapper.lock(tenant + ":" + invocationId);
            // 锁住当前工作，令同一 invocation 的并发重放先观察完整的原子批次。
            String persistedStatus = mapper.workStatus(tenant, session(work), work.key().rootInputId().value(), execution.binding().task().id(), execution.binding().runRef().runId(), execution.binding().task().agentId().value()).stream().findFirst()
                    .orElseThrow(() -> rejected("task_result_work_missing"));
            if ("tool_attempt".equals(source.kind()) && !"ACTIVE".equals(persistedStatus)
                    || "run_completion".equals(source.kind()) && Set.of("CANCELLING", "CANCELLED").contains(persistedStatus))
                throw rejected("task_result_work_not_active");
            if ("run_completion".equals(source.kind()) && submissions.size() == 1) {
                String output = submissions.getFirst().outputName();
                List<Stored> existing = rows(work, execution.binding().task().id(), execution.binding().runRef().runId());
                for (int index = existing.size() - 1; index >= 0; index--) {
                    Stored row = existing.get(index);
                    if (output.equals(row.result().outputName()) && "succeeded".equals(row.result().status())) {
                        return List.of(new Saved(output, new ResultRef(row.result().resultId()),
                                row.payloadSha256(), row.result().status()));
                    }
                }
            }

            var scope=publicOutputs.capture(runOwner(work,execution));
            for(var submission:submissions) {
                var texts=new ArrayList<String>(List.of(submission.title(),submission.summary(),submission.content()));
                texts.addAll(submission.limitations());
                submission.representations().forEach(item->{texts.add(item.label());texts.add(item.path());});
                if(texts.stream().anyMatch(scope::rejects)) throw rejected(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.REJECTION_CODE);
            }
            Set<String> names = new HashSet<>();
            for (Submission submission : submissions) {
                if (!execution.binding().task().outputs().contains(submission.outputName()) || !names.add(submission.outputName()))
                    throw rejected("task_result_output_not_declared");
                if (!"operation".equals(submission.kind()) && submission.operationReceipt() != null)
                    throw rejected("task_result_operation_receipt_unexpected");
                if (!"operation".equals(submission.kind())
                        && submission.representations().stream().anyMatch(item -> "external".equals(item.role()))) {
                    if ("design".equals(submission.kind())) throw rejected("external_design_result_verifier_not_configured");
                    throw rejected("external_result_verifier_not_configured");
                }
            }

            Map<String, ArtifactMetadata> available = availableArtifacts(work);
            Set<ArtifactKey> permitted = permittedArtifacts(work, execution);
            List<Prepared> prepared = submissions.stream()
                    .map(submission -> prepare(work, execution, submission, available, permitted,
                            source, context))
                    .sorted(Comparator.comparing(value -> value.submission().outputName()))
                    .toList();
            String commandSha = hash(write(prepared.stream().map(Prepared::fingerprint).toList()));
            List<Stored> previous = invocationRows(tenant, invocationId);
            if (!previous.isEmpty()) return replay(previous, execution, attemptId, commandSha, prepared);

            Map<String, Object> authorization = Map.of(
                    "target_kind", work.key().owner().target().kind().name(),
                    "target_id", work.key().owner().target().id(),
                    "scope_run_id", work.request().controlRun().runId(),
                    "agent_id", execution.binding().task().agentId().value(),
                    "membership", "tool_attempt".equals(source.kind())
                            ? "enabled_at_registration" : "frozen_run_identity",
                    "session_access", "tool_attempt".equals(source.kind())
                            ? "owner_active_at_registration" : "trusted_run_completion");
            List<Saved> saved = new ArrayList<>();
            for (Prepared item : prepared) {
                String resultId = "team-result:" + java.util.UUID.randomUUID();
                TeamTaskResult result = new TeamTaskResult(TeamTaskResult.SCHEMA_ID, resultId,
                        execution.binding().task().id(), item.submission().outputName(), item.submission().title(),
                        item.submission().kind(), item.submission().status(), item.submission().summary(),
                        item.submission().content(), item.representations(), item.submission().limitations(),
                        item.operationReceipt(), item.source(), authorization);
                String payload = write(result);
                String payloadSha = hash(payload);
                mapper.insert(tenant, session(work), work.key().rootInputId().value(), result.taskId(), execution.binding().runRef().runId(), result.outputName(), result.resultId(), result.schemaId(), result.kind(), result.status(), payload, payloadSha, commandSha, result.source().eventRef().isBlank() ? null : result.source().eventRef(), invocationId, attemptId, write(authorization));
                saved.add(new Saved(result.outputName(), new ResultRef(result.resultId()), payloadSha, result.status()));
            }
            return saved.stream().sorted(Comparator.comparing(Saved::outputName)).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "persist", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "persist", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 验证成果载体并生成可持久化的规范表示。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param submission 待登记的一项成果声明
     * @param available 当前仍可访问的产物索引
     * @param permitted 当前授权允许的产物集合
     * @param registrationSource 可信成果登记来源
     * @param context 当前平台工具执行上下文
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Prepared prepare(DagWork work, DagWork.Execution execution, Submission submission,
            Map<String, ArtifactMetadata> available, Set<ArtifactKey> permitted,
            TeamTaskResult.Source registrationSource, PlatformToolExecutionContext context) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "prepare", TenantContext.getTenantId());
        try {
            VerifiedOperation operation = "operation".equals(submission.kind())
                    ? verifyOperationReceipt(work, execution, submission, context) : null;
            List<TeamTaskResult.Representation> representations = new ArrayList<>();
            for (RepresentationInput input : submission.representations()) {
                if ("external".equals(input.role())) {
                    verifyExternalRepresentation(operation, input);
                    representations.add(new TeamTaskResult.Representation(input.label(), input.mediaType(),
                            "", input.path(), input.role(), ""));
                    continue;
                }
                if (input.artifactId().isBlank() || input.path().isBlank())
                    throw rejected("task_result_artifact_reference_required");
                ArtifactMetadata artifact = available.get(input.artifactId());
                if (artifact == null || !(artifact.runId().equals(execution.binding().runRef().runId())
                        || permitted.contains(new ArtifactKey(input.artifactId(), input.path()))))
                    throw rejected("task_result_artifact_not_permitted");
                ArtifactMetadata.File file = artifact.files().stream().filter(value -> value.path().equals(input.path()))
                        .findFirst().orElseThrow(() -> rejected("task_result_artifact_path_missing"));
                representations.add(new TeamTaskResult.Representation(input.label(), input.mediaType(),
                        input.artifactId(), input.path(), input.role(), file.sha256()));
            }
            if (!Set.of("text", "operation").contains(submission.kind())
                    && !"failed".equals(submission.status()) && representations.isEmpty())
                throw rejected("task_result_representation_required");
            return new Prepared(submission, List.copyOf(representations),
                    operation == null ? registrationSource : operation.source(),
                    operation == null ? null : operation.receipt());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "prepare", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "prepare", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** external 表示只能逐值引用本次已核验公开观察中的地址。 */
    void verifyExternalRepresentation(VerifiedOperation operation, RepresentationInput input) {
        if (operation == null || !input.artifactId().isBlank()
                || !operation.publicUrls().contains(input.path())) {
            throw rejected("task_result_external_public_url_unverified");
        }
    }

    /** 只接受当前任务 Run 已完成的 Tool Action 账本事实，当前 submit 调用不能自证。 */
    VerifiedOperation verifyOperationReceipt(DagWork work, DagWork.Execution execution,
            Submission submission, PlatformToolExecutionContext context) {
        OperationReceiptInput input = submission.operationReceipt();
        if (input == null || context == null) throw rejected("operation_result_requires_trusted_receipt");
        if (input.invocationId().equals(context.invocationId())
                || input.attemptId().equals(context.attemptId())) {
            throw rejected("operation_result_current_attempt_forbidden");
        }
        ActionRef actionRef = new ActionRef(work.key().owner().sessionRef().tenantId(), input.actionRef());
        var expectedOwner = runOwner(work, execution);
        if (!context.run().owner().equals(expectedOwner) || !context.run().runRef().equals(execution.binding().runRef())
                || !context.run().agentId().equals(execution.binding().task().agentId())) throw rejected("operation_result_receipt_mismatch");
        final TeamRuntimeFacts.Operation actual;
        try {actual=runtimeFacts.operation(context.run(),actionRef,input.invocationId(),input.attemptId(),input.factRef());}
        catch(TeamRuntimeFacts.EvidenceRejected rejected){throw rejected(rejected.getMessage());}
        if (!actual.status().equals(submission.status())) throw rejected("operation_result_status_mismatch");
        if ("submit_task_result".equals(actual.toolName())) throw rejected("operation_result_submit_receipt_forbidden");
        Set<String> publicUrls = publicUrls(actual.observation());
        TeamTaskResult.OperationReceipt receipt = new TeamTaskResult.OperationReceipt(actual.toolName(),
                actionRef.actionId(), input.invocationId(), input.attemptId(), input.factRef(),
                actual.actionState(), actual.observation().schemaId(), actual.observation().canonicalJson(),
                publicUrls.stream().sorted().toList());
        TeamTaskResult.Source source = new TeamTaskResult.Source("action_receipt", input.factRef(),
                input.invocationId(), input.attemptId(), execution.binding().runRef().runId());
        return new VerifiedOperation(source, receipt, publicUrls);
    }

    /** 只承认公开 observation 中显式命名的 public_url/public_urls，不从普通 url 字段猜测。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param observation 可信执行状态观测
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Set<String> publicUrls(TypedPayload observation) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "publicUrls", TenantContext.getTenantId());
        try {
            try {
                Set<String> values = new java.util.TreeSet<>();
                collectPublicUrls(json.readTree(observation.canonicalJson()), values);
                if (values.size() > 32) throw rejected("operation_result_public_urls_exceeded");
                return Set.copyOf(values);
            } catch (JsonProcessingException invalid) {
                throw rejected("operation_result_public_observation_invalid");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "publicUrls", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "publicUrls", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 收集已验证回执公开地址。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param node 当前任务节点
     * @param values 待合并的领域值集合
     */
    private void collectPublicUrls(com.fasterxml.jackson.databind.JsonNode node, Set<String> values) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "collectPublicUrls", TenantContext.getTenantId());
        try {
            if (node == null) return;
            if (node.isArray()) {
                node.forEach(value -> collectPublicUrls(value, values));
                return;
            }
            if (!node.isObject()) return;
            node.fields().forEachRemaining(field -> {
                if ("public_url".equals(field.getKey())) {
                    values.add(publicUrl(field.getValue()));
                } else if ("public_urls".equals(field.getKey())) {
                    if (!field.getValue().isArray()) throw rejected("operation_result_public_url_invalid");
                    field.getValue().forEach(value -> values.add(publicUrl(value)));
                } else {
                    collectPublicUrls(field.getValue(), values);
                }
            });

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "collectPublicUrls", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "collectPublicUrls", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 验证单个公开地址。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param node 当前任务节点
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String publicUrl(com.fasterxml.jackson.databind.JsonNode node) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "publicUrl", TenantContext.getTenantId());
        try {
            if (node == null || !node.isTextual()) throw rejected("operation_result_public_url_invalid");
            String value = node.textValue();
            if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 2_000)
                throw rejected("operation_result_public_url_invalid");
            try {
                URI uri = URI.create(value);
                String host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()
                        || uri.getUserInfo() != null || uri.getFragment() != null || !host.contains(".")
                        || host.endsWith(".") || host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
                    throw rejected("operation_result_public_url_invalid");
                }
                String normalized = host.toLowerCase(java.util.Locale.ROOT);
                if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                        || normalized.endsWith(".local") || normalized.endsWith(".internal")
                        || normalized.endsWith(".test") || normalized.endsWith(".invalid")
                        || normalized.endsWith(".example")) {
                    throw rejected("operation_result_public_url_invalid");
                }
                return value;
            } catch (IllegalArgumentException invalid) {
                throw rejected("operation_result_public_url_invalid");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "publicUrl", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "publicUrl", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 计算当前执行允许引用的产物集合。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Set<ArtifactKey> permittedArtifacts(DagWork work, DagWork.Execution execution) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "permittedArtifacts", TenantContext.getTenantId());
        try {
            Set<ArtifactKey> permitted = new HashSet<>();
            for (DagWork.BoundInput input : execution.binding().inputs().values()) {
                Stored stored = requireBoundResult(work, input);
                for (TeamTaskResult.Representation representation : stored.result().representations()) {
                    if (!representation.artifactId().isBlank())
                        permitted.add(new ArtifactKey(representation.artifactId(), representation.path()));
                }
            }
            return Set.copyOf(permitted);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "permittedArtifacts", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "permittedArtifacts", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 核对重复调用的命令摘要及执行身份。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param previous 同一调用此前持久化结果
     * @param execution 当前任务执行事实
     * @param attemptId 本次尝试标识，用于核验重复提交
     * @param commandSha 规范命令摘要，用于拒绝内容不同的重放
     * @param prepared 已完成校验的成果批次
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private List<Saved> replay(List<Stored> previous, DagWork.Execution execution, String attemptId,
            String commandSha, List<Prepared> prepared) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "replay", TenantContext.getTenantId());
        try {
            Set<String> expected = prepared.stream().map(value -> value.submission().outputName())
                    .collect(java.util.stream.Collectors.toSet());
            boolean same = previous.size() == prepared.size()
                    && previous.stream().allMatch(row -> row.runId().equals(execution.binding().runRef().runId())
                            && row.result().taskId().equals(execution.binding().task().id())
                            && row.attemptId().equals(attemptId) && row.commandSha256().equals(commandSha))
                    && previous.stream().map(row -> row.result().outputName()).collect(java.util.stream.Collectors.toSet()).equals(expected);
            if (!same) throw rejected("task_result_invocation_conflict");
            return previous.stream().sorted(Comparator.comparing(row -> row.result().outputName()))
                    .map(row -> new Saved(row.result().outputName(), new ResultRef(row.result().resultId()),
                            row.payloadSha256(), row.result().status())).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "replay", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "replay", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 主 Agent 沿其真实绑定授权；成员接收者仍走目标成员授权。 */
    private void authorizeRecipient(DagWork work,AgentId target) {
        var binding=bindings.find(work.request().controlRun()).orElseThrow();
        if (!binding.owner().equals(work.key().owner())) throw rejected("task_result_scope_invalid");
        if (target.equals(work.coordinator())) bindings.authorize(binding);
        else bindings.authorizeTarget(binding,target.value());
    }

    /** 自身步骤的成果直接读取已冻结的完成事件；不制造第二套持久成果。 */
    private Stored primaryResult(DagWork work,DagWork.Execution execution,String outputName) {
        if (!execution.terminal() || execution.sourceEvent().isEmpty()
                || !execution.binding().task().outputs().contains(outputName)) throw rejected("primary_result_not_available");
        var ref=execution.sourceEvent().orElseThrow();
        var event=planEvents.findByEvent(work.key().owner(),new com.skillforge.runtime.api.EventId(ref.value()))
                .orElseThrow(()->rejected("primary_result_event_missing"));
        com.fasterxml.jackson.databind.JsonNode body;
        try { body=json.readTree(event.payload().canonicalJson()); }
        catch (JsonProcessingException invalid) { throw rejected("primary_result_event_invalid"); }
        if (!event.owner().equals(work.key().owner()) || !event.runRef().equals(java.util.Optional.of(work.request().controlRun()))
                || !event.agentId().equals(work.coordinator()) || !event.inputId().equals(work.key().rootInputId())
                || !"plan.step.finished".equals(event.eventType())
                || !work.key().rootInputId().value().equals(body.path("work_id").asText())
                || !execution.binding().task().id().equals(body.path("task_id").asText())
                || !execution.binding().admissionId().equals(body.path("admission_id").asText())
                || !execution.status().name().equals(body.path("outcome").asText())
                || !execution.phase().equals(body.path("summary").asText()) || execution.phase().isBlank()
                || !body.path("result_refs").isArray()) throw rejected("primary_result_event_mismatch");
        String summary=body.path("summary").asText();
        String content=summary;
        // 非成功条目仅为按输出名区分的展示身份，不对应已履约的 DagWork.Output。
        String resultId="step-status:"+ref.value()+"#"+java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(outputName.getBytes(StandardCharsets.UTF_8));
        if (execution.status()==DagWork.TaskStatus.COMPLETED) {
            var value=body.path("outputs").path(outputName);
            var bound=execution.outputs().get(outputName);
            if (!value.isTextual() || value.asText().isBlank() || bound==null || !bound.fulfilled()
                    || !bound.digest().equals(hash(value.asText()))) throw rejected("primary_result_output_mismatch");
            String expectedRef=ref.value()+"#"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(outputName.getBytes(StandardCharsets.UTF_8));
            if (!bound.resultRef().value().equals(expectedRef)) throw rejected("primary_result_reference_mismatch");
            content=value.asText();resultId=bound.resultRef().value();
        }
        String digest=hash(content);
        var result=new TeamTaskResult(TeamTaskResult.SCHEMA_ID,resultId,execution.binding().task().id(),outputName,
                execution.binding().task().title(),"text",execution.status()==DagWork.TaskStatus.COMPLETED?"succeeded":"failed",
                summary.substring(0,Math.min(8000,summary.length())),content.substring(0,Math.min(TEXT_LIMIT,content.length())),List.of(),
                List.of("主助手步骤结论；外部操作仅以已验证结果引用为依据"),null,
                new TeamTaskResult.Source("plan_step_completion",ref.value(),ref.value(),ref.value(),work.request().controlRun().runId()),Map.of());
        return new Stored(result,work.request().controlRun().runId(),digest,digest,"",event.occurredAt());
    }

    /**
     * 核对成果引用和输入快照摘要。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param input 绑定工作输入或规范输入标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Stored requireBoundResult(DagWork work, DagWork.BoundInput input) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireBoundResult", TenantContext.getTenantId());
        try {
            var source=work.executions().get(input.sourceTask());
            Stored stored=source!=null && source.binding().task().executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY
                    ? primaryResult(work,source,input.output()) : requireResult(work, input.resultRef());
            if (!stored.result().resultId().equals(input.resultRef().value())) throw rejected("task_result_input_reference_mismatch");
            if (source!=null && source.binding().task().executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY) {
                var scope=publicOutputs.capture(work.key().owner());
                if (scope.rejects(stored.result().summary()) || scope.rejects(stored.result().content())) throw rejected("primary_result_not_public");
            }
            if (!stored.result().taskId().equals(input.sourceTask())
                    || !stored.result().outputName().equals(input.output())
                    || !stored.payloadSha256().equals(input.digest())
                    || !"succeeded".equals(stored.result().status())) throw rejected("task_result_input_digest_mismatch");
            return stored;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireBoundResult", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireBoundResult", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取绑定工作内的成果，不允许跨工作引用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param reference 已持久化成果引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Stored requireResult(DagWork work, ResultRef reference) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireResult", TenantContext.getTenantId());
        try {
            return mapper.result(tenant(work), session(work), work.key().rootInputId().value(), reference.value()).stream().map(this::stored).toList()
                    .stream().findFirst().orElseThrow(() -> rejected("task_result_missing"));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireResult", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireResult", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取一次调用已登记的完整成果批次。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param invocation 平台调用幂等标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private List<Stored> invocationRows(String tenant, String invocation) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "invocationRows", TenantContext.getTenantId());
        try {
            return mapper.invocation(tenant, invocation).stream().map(this::stored).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "invocationRows", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "invocationRows", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 按工作及可选任务运行范围读取成果。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param task 计划内任务标识或定义
     * @param run 带租户的运行引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private List<Stored> rows(DagWork work, String task, String run) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "rows", TenantContext.getTenantId());
        try {
            return mapper.rows(tenant(work), session(work), work.key().rootInputId().value(), task, run).stream().map(this::stored).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "rows", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "rows", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 恢复成果并核对序列化摘要，损坏事实不得继续使用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param row Mapper 返回的领域数据行
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Stored stored(TeamResultMapper.Row row) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "stored", TenantContext.getTenantId());
        try {
            TeamTaskResult result = read(row.payload);
            String payloadSha = row.payloadSha256;
            if (!payloadSha.equals(hash(write(result)))) throw new IllegalStateException("team_result_payload_digest_mismatch");
            Instant created = row.createdAt;
            return new Stored(result, row.runId, payloadSha, row.commandSha256,
                    row.attemptId, created);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "stored", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "stored", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 收集当前仍就绪且未过期的产物。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Map<String, ArtifactMetadata> availableArtifacts(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "availableArtifacts", TenantContext.getTenantId());
        try {
            Map<String, ArtifactMetadata> available = new HashMap<>();
            Instant now = Instant.now();
            for (ArtifactMetadata artifact : artifacts.list(tenant(work), session(work))) {
                if ("ready".equals(artifact.status()) && now.isBefore(artifact.expiresAt())) available.put(artifact.id(), artifact);
            }
            return Map.copyOf(available);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "availableArtifacts", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "availableArtifacts", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 核验文件仍存在且摘要一致。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param result 已校验的团队成果
     * @param available 当前仍可访问的产物索引
     */
    private void requireVisibleArtifacts(TeamTaskResult result, Map<String, ArtifactMetadata> available) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireVisibleArtifacts", TenantContext.getTenantId());
        try {
            for (TeamTaskResult.Representation representation : result.representations()) {
                if (representation.artifactId().isBlank()) continue;
                ArtifactMetadata artifact = available.get(representation.artifactId());
                if (artifact == null || artifact.files().stream().noneMatch(file -> file.path().equals(representation.path())
                        && file.sha256().equals(representation.sha256()))) throw ArtifactStorageUnavailable.hidden();
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireVisibleArtifacts", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireVisibleArtifacts", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 过滤公开输出中的受限信息。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param row Mapper 返回的领域数据行
     * @param scope 公开输出或委派的可信范围
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private TeamPublicViews.Result publicView(Stored row,com.skillforge.core.chat.integration.publicread.PublicOutputGuard.Scope scope) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "publicView", TenantContext.getTenantId());
        try {
            var result=row.result();
            var texts=new ArrayList<String>(List.of(result.title(),result.summary(),result.content()));
            texts.addAll(result.limitations());
            result.representations().forEach(item->{texts.add(item.label());texts.add(item.path());});
            if(texts.stream().anyMatch(scope::rejects))return new TeamPublicViews.Result(result.resultId(),result.taskId(),
                    "协作结果","text","failed",com.skillforge.core.chat.integration.publicread.PublicOutputGuard.SAFE_MESSAGE,
                    "",List.of(),List.of(),row.createdAt().toString());
            return resultView(row);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "publicView", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "publicView", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 将内部成果转换为公开投影。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param row Mapper 返回的领域数据行
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static TeamPublicViews.Result resultView(Stored row) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "resultView", TenantContext.getTenantId());
        try {
            var result=row.result();
            return new TeamPublicViews.Result(result.resultId(),result.taskId(),result.title(),result.kind(),result.status(),
                    result.summary(),result.content(),result.representations(),result.limitations(),row.createdAt().toString());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "resultView", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "resultView", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 按成果类型生成下游可读正文。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param stored 已恢复且通过摘要核对的成果
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String resultText(Stored stored) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "resultText", TenantContext.getTenantId());
        try {
            TeamTaskResult result = stored.result();
            if ("operation".equals(result.kind())) return operationText(result);
            if (!"text".equals(result.kind())) return write(resultView(stored));
            StringBuilder text = new StringBuilder();
            text.append("成果：").append(result.title());
            if (!result.summary().isBlank()) text.append("\n摘要：").append(result.summary());
            text.append("\n限制：").append(String.join("；", result.limitations()));
            text.append("\n\n").append(result.content());
            return text.toString();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "resultText", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "resultText", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 仅依据已核验执行回执描述操作事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param result 已校验的团队成果
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String operationText(TeamTaskResult result) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "operationText", TenantContext.getTenantId());
        try {
            TeamTaskResult.OperationReceipt receipt = Objects.requireNonNull(result.operationReceipt());
            StringBuilder text = new StringBuilder();
            if (!result.summary().isBlank()) text.append("业务描述（未作为执行事实）：").append(result.summary()).append('\n');
            text.append("已核验操作结果：").append("SUCCEEDED".equals(receipt.actionState())?"执行成功":"执行失败");
            if (!receipt.publicUrls().isEmpty())
                text.append("\n已核验公开地址：").append(String.join("，", receipt.publicUrls()));
            return text.toString();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "operationText", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "operationText", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 核对任务执行身份及允许写入的状态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param execution 当前任务执行事实
     * @param runId 待核验的运行标识
     * @param requireRunning 是否要求任务仍在运行
     */
    private void requireCurrentExecution(DagWork work, DagWork.Execution execution, String runId,
            boolean requireRunning) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireCurrentExecution", TenantContext.getTenantId());
        try {
            Objects.requireNonNull(work, "work"); Objects.requireNonNull(execution, "execution");
            if (work.plan() == null
                    || work.executions().get(execution.binding().task().id()) == null
                    || !work.executions().get(execution.binding().task().id()).equals(execution)
                    || !execution.binding().runRef().runId().equals(runId)) throw rejected("task_result_execution_mismatch");
            if (requireRunning && (work.status() != DagWork.Status.ACTIVE
                    || execution.status() != DagWork.TaskStatus.RUNNING)) throw rejected("task_result_execution_not_running");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireCurrentExecution", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireCurrentExecution", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 依据绑定请求来源核验会话访问权。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     */
    private void authorizeOwner(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "authorizeOwner", TenantContext.getTenantId());
        try {
            artifactAccess.authorizeBound(tenant(work), session(work),
                    work.request().requesterId(), work.request().channelId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "authorizeOwner", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "authorizeOwner", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 核验成果读取范围和当前成员权限。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param agent 待校验或执行的 Agent 标识
     */
    private void requireReadable(DagWork work,String agent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireReadable", TenantContext.getTenantId());
        try {
            var binding=bindings.find(work.request().controlRun()).orElseThrow();
            if(!binding.owner().equals(work.key().owner()))throw rejected("task_result_scope_invalid");
            if (agent.equals(work.coordinator().value())) bindings.authorize(binding);
            else bindings.authorizeRead(binding,agent);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireReadable", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireReadable", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
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
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "tenant", TenantContext.getTenantId());
        try {
     return work.key().owner().sessionRef().tenantId().value();
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "tenant", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "tenant", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 读取工作所属会话标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static String session(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "session", TenantContext.getTenantId());
        try {
     return work.key().owner().sessionRef().sessionId();
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "session", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "session", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }


    /**
     * 反序列化内部持久化快照，格式损坏时失败。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param payload 内部序列化数据，不进入日志
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private TeamTaskResult read(String payload) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "read", TenantContext.getTenantId());
        try {
            try { return json.readValue(payload, TeamTaskResult.class); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("team_result_payload_invalid", invalid); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "read", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "read", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 生成稳定 JSON 快照供持久化与摘要计算使用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String write(Object value) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "write", TenantContext.getTenantId());
        try {
            try { return json.writeValueAsString(value); }
            catch (JsonProcessingException failure) { throw new IllegalStateException("team_result_payload_invalid", failure); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "write", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "write", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 计算稳定 SHA-256 摘要，避免依赖运行环境编码。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static String hash(String value) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "hash", TenantContext.getTenantId());
        try {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "hash", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "hash", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 限制公开摘要长度。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param text 待处理正文，不写入日志
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static String summarize(String text) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "summarize", TenantContext.getTenantId());
        try {
            String normalized = text.strip();
            return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "summarize", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "summarize", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 生成或登记受控拒绝结果。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param code 对外稳定错误码
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static ApiException rejected(String code) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "rejected", TenantContext.getTenantId());
        try {
            return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "rejected", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "rejected", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    public record Submission(String outputName, String title, String kind, String status, String summary,
            String content, List<RepresentationInput> representations, List<String> limitations,
            OperationReceiptInput operationReceipt) {
        public Submission {
            outputName = requireText(outputName, 240);
            title = requireText(title, 200);
            kind = requireText(kind, 40);
            status = requireText(status, 40);
            summary = optionalText(summary, 8_000);
            content = optionalText(content, TEXT_LIMIT);
            representations = List.copyOf(representations == null ? List.of() : representations);
            limitations = List.copyOf(limitations == null ? List.of() : limitations);
            if (!TeamTaskResult.KINDS.contains(kind) || !TeamTaskResult.STATUSES.contains(status)
                    || representations.size() > 32 || limitations.size() > 20)
                throw new IllegalArgumentException("invalid task result submission");
            limitations.forEach(value -> requireText(value, 2_000));
            if ("text".equals(kind) && (content.isBlank() || limitations.isEmpty()))
                throw new IllegalArgumentException("text result requires content limitations");
            if (!"text".equals(kind) && !content.isBlank())
                throw new IllegalArgumentException("inline content is reserved for text result");
            if ("operation".equals(kind) != (operationReceipt != null))
                throw new IllegalArgumentException("operation result requires receipt reference");
        }
    }

    public record OperationReceiptInput(String actionRef, String invocationId, String attemptId, String factRef) {
        public OperationReceiptInput {
            actionRef = requireText(actionRef, 240);
            invocationId = requireText(invocationId, 240);
            attemptId = requireText(attemptId, 240);
            factRef = requireText(factRef, 240);
        }
    }

    public record RepresentationInput(String label, String mediaType, String artifactId, String path, String role) {
        public RepresentationInput {
            label = requireText(label, 200);
            mediaType = requireText(mediaType, 200);
            artifactId = optionalText(artifactId, 100);
            path = optionalText(path, 2_000);
            role = requireText(role, 20);
            if (!TeamTaskResult.ROLES.contains(role)
                    || "external".equals(role) && (!artifactId.isBlank() || path.isBlank())
                    || !"external".equals(role) && artifactId.isBlank() != path.isBlank())
                throw new IllegalArgumentException("invalid result representation");
        }
    }

    public record Saved(String outputName, ResultRef resultRef, String digest, String status) { }

    private record Prepared(Submission submission, List<TeamTaskResult.Representation> representations,
            TeamTaskResult.Source source, TeamTaskResult.OperationReceipt operationReceipt) {
        Map<String, Object> fingerprint() {
            Map<String, Object> value = new TreeMap<>();
            value.put("content", submission.content()); value.put("kind", submission.kind());
            value.put("limitations", submission.limitations()); value.put("output_name", submission.outputName());
            value.put("operation_receipt", operationReceipt == null ? Map.of() : operationReceipt);
            value.put("representations", representations); value.put("status", submission.status());
            value.put("source", source); value.put("summary", submission.summary()); value.put("title", submission.title());
            return value;
        }
    }
    record VerifiedOperation(TeamTaskResult.Source source, TeamTaskResult.OperationReceipt receipt,
            Set<String> publicUrls) {
        VerifiedOperation {
            Objects.requireNonNull(source); Objects.requireNonNull(receipt);
            publicUrls = Set.copyOf(publicUrls);
        }
    }
    private record ArtifactKey(String artifactId, String path) { }
    private record Stored(TeamTaskResult result, String runId, String payloadSha256,
            String commandSha256, String attemptId, Instant createdAt) { }

    /** 将文件撤权统一映射为不可见，避免 view 通过差异暴露产物是否存在。 */
    private static final class ArtifactStorageUnavailable {
        private static ApiException hidden() { return new ApiException(HttpStatus.GONE, "产物不可用、无权限或已过期"); }
    }

    /**
     * 验证必填文本长度与首尾空白。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @param maximum 允许的最大文本长度
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static String requireText(String value, int maximum) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "requireText", TenantContext.getTenantId());
        try {
            if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > maximum)
                throw new IllegalArgumentException("result text is invalid");
            return value;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "requireText", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "requireText", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 规范可选文本并验证长度边界。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @param maximum 允许的最大文本长度
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static String optionalText(String value, int maximum) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamResultService", "optionalText", TenantContext.getTenantId());
        try {
            value = Objects.requireNonNullElse(value, "");
            if (!value.equals(value.trim()) || value.length() > maximum)
                throw new IllegalArgumentException("result text is invalid");
            return value;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamResultService", "optionalText", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamResultService", "optionalText", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
}
