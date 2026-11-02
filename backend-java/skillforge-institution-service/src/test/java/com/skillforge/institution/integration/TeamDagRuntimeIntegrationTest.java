package com.skillforge.institution.integration;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.institution.team.TeamResultService;
import com.skillforge.institution.team.TeamService;
import com.skillforge.institution.team.TeamWorkInputs;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.SessionDelegationEngine;
import com.skillforge.runtime.session.spi.SessionDelegations;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真实 Team、原生委派、Runtime worker 与 JDBC 共同推进持久 DAG 的组合验收。 */
@Import(DeterministicModelCatalogConfiguration.class)
class TeamDagRuntimeIntegrationTest extends AbstractIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void runtimeWorkers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("skillforge.team.poll-ms", () -> "3600000");
    }

    @Autowired private javax.sql.DataSource dataSource;
    private org.apache.ibatis.session.SqlSessionFactory sqlSessions;
    @Autowired private com.skillforge.runtime.session.AgentExecutionCodec executionCodec;
    @Autowired private ApplicationContext application;
    @Autowired private AgentRuntimeProperties runtimeProperties;
    @Autowired private ObjectMapper json;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private TenantMemberMapper members;
    @Autowired private EndUserMapper users;
    @Autowired private AgentMapper agents;
    @Autowired private com.skillforge.institution.service.AgentManagementService agentManagement;
    @Autowired private com.skillforge.core.agent.AgentPublicationService agentPublications;
    @Autowired private SessionMapper sessions;
    @Autowired private PlatformModelCatalogService modelCatalog;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private AgentRuntimeInboundGateway gateway;
    @Autowired private TeamService teams;
    @Autowired private TeamRuntimeBridge runtime;
    @Autowired private com.skillforge.institution.team.TeamRuntimeFacts runtimeFacts;
    @Autowired private JdbcDagWorkPort works;
    @Autowired private ExecutionStore executions;
    @Autowired private ExecutionBudgetPort budgets;
    @Autowired private TeamWorkInputs inputs;
    @MockitoSpyBean private TeamWorkService teamWork;
    @Autowired private TeamResultService results;
    @Autowired private com.skillforge.institution.team.TeamWorkView workView;
    @Autowired private SessionDelegationEngine delegations;
    @Autowired private SessionDelegations.Store delegationStore;
    @Autowired private AgentRunBindings runBindings;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.skillforge.institution.runtime.AgentRuntimeExecutionProjection chatProjection;
    @MockitoSpyBean private AgentRuntimeModelInvocationAudit modelAudit;

    @Autowired private com.skillforge.institution.runtime.InstitutionPlanHost planHost;

    private ControlledModel model;
    private final ConcurrentLinkedQueue<Throwable> deferredFailures = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startModel() throws IOException {
        // 本夹具直接拥有提交；必须使用真正关闭autoCommit的JDBC事务，不能借Spring管理事务工厂。
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "dag-plan-integration", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        JdbcDagWorkPort.configure(configuration);
        TeamWorkInputs.configure(configuration);
        sqlSessions = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        // 测试直接调用 Gateway，显式提供由真实 HTTP 过滤器负责的追踪上下文。
        com.skillforge.common.context.RequestContext.setTraceId("delegation-test-" + java.util.UUID.randomUUID());
        model = new ControlledModel(json);
        // 保留真实deferred处理，仅保存第一次实际异常，超时断言附上原始拒绝原因。
        org.mockito.Mockito.doAnswer(invocation -> {
            if (deferredFailures.isEmpty()) deferredFailures.add(invocation.getArgument(3));
            return invocation.callRealMethod();
        }).when(teamWork).deferred(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        // 异步模型回调的持久化失败直接反馈给测试，避免被模型屏障超时掩盖。
        org.mockito.Mockito.doAnswer(invocation -> {
            try { return RuntimeAuditFailures.capture((com.skillforge.runtime.adapter.model.ModelInvocationAudit) invocation.callRealMethod(), model.failures); }
            catch (Throwable failure) {
                model.failures.add(failure);
                throw failure;
            }
        }).when(modelAudit).dispatched(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        model.start();
    }

    @AfterEach
    void releaseResources() {
        if (model != null) model.close();
        TenantContext.clear();
        com.skillforge.common.context.RequestContext.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Timeout(120)
    void approvedDependencyChainUsesNativeDelegationRuns(boolean studio) throws Exception {
        Fixture fixture = fixture(studio);
        withAuthenticatedMember(fixture.requester().getId(),()->gateway.selectPlan(fixture.session(),"select-plan:"+fixture.suffix(),true));
        var request = new ChatRequest(
                "先完成 T1，再让 T2 使用 T1 的结果",
                fixture.session().getSessionId(), studio ? null : fixture.coordinator().getId().toString(),
                studio ? fixture.team().id() : null,
                null, null, null, null, "input-" + fixture.suffix(), null,
                Map.of(
                        "input_id", "input-" + fixture.suffix(),
                        "end_user_id", fixture.user().getId(),
                        "delegation_requester_id", fixture.requester().getId().toString()));
        var accepted = submit(fixture, new ChatRuntimeGateway.Submission(
                request, fixture.session(), fixture.coordinator().getId().toString(),
                fixture.user().getId(), null));
        assertEquals("queued", accepted.status());
        model.awaitEntered(ControlledModel.CONTROL, WAIT);

        SessionOwner owner = TeamWorkService.owner(fixture.session());
        RunRef parentRun = new RunRef(new TenantId(fixture.tenantId()), accepted.executionId());
        RunIdentity parent = runtime.fact(owner, parentRun).orElseThrow().identity();
        assertRunBudgetUsesAgentSettings(owner, parent.causationInputId(), parentRun, parent.agentId());

        DagPlan plan = plan(fixture);
        var nativeTasks = plan.tasks().stream().map(task ->
                new com.skillforge.runtime.api.port.PlanCommand.Task(
                        task.id(), task.title(), new com.skillforge.runtime.api.port.PlanCommand.Executor(com.skillforge.runtime.api.port.PlanCommand.ExecutorType.MEMBER, "member:" + task.agentId().value()),
                        new com.skillforge.runtime.api.port.DelegationCommand.Task(task.objective(), task.context(), task.constraints(), task.expectedOutput()),
                        task.inputs().stream().map(input -> new com.skillforge.runtime.api.port.PlanCommand.Input(input.name(), input.sourceTask(), input.output())).toList(),
                        task.outputs(), task.exclusiveResources())).toList();
        var reply = invoke(fixture, parent, "submit-plan:" + fixture.suffix(),
                new com.skillforge.runtime.api.port.PlanCommand(com.skillforge.runtime.api.port.PlanCommand.Operation.SUBMIT, "", 0,
                        Optional.of(new com.skillforge.runtime.api.port.PlanCommand.Plan("集成测试计划", "", nativeTasks)), "", "", "", List.of(), List.of()));
        assertTrue(reply.value().containsKey("work_id"));
        DagWork.Key key = new DagWork.Key(owner, parent.causationInputId());
        DagWork work = works.read(key).orElseThrow();
        assertEquals(0, work.approvedRevision());

        teamWork.tick(key);
        assertTrue(works.read(key).orElseThrow().executions().isEmpty(),
                "未批准分工不能准入任务");

        inputs.accept(work, approval("approve-wrong-" + fixture.suffix(), 2));
        teamWork.tick(key);
        work = works.read(key).orElseThrow();
        assertEquals(0, work.approvedRevision());
        assertTrue(work.executions().isEmpty(), "过期或错误版本批准不能准入任务");

        inputs.accept(work, approval("approve-exact-" + fixture.suffix(), 1));
        teamWork.tick(key);
        work = works.read(key).orElseThrow();
        assertEquals(1, work.approvedRevision());
        assertTrue(work.executions().containsKey("T1"));

        DagWork runningT1 = driveUntil(key, ignored -> model.entered("T1"), "T1 未进入真实模型调用");
        assertFalse(runningT1.executions().containsKey("T2"), "T2 不能越过未完成的 T1");
        assertNativeDelegation(parent, runningT1, "T1");
        // C06：同一确认的传输重放与另一条重复确认都不能产生第二个业务准入。
        var firstBinding = runningT1.executions().get("T1").binding();
        var firstDelegation = delegationStore.list(parent).getFirst();
        var exactConfirmation = approval("approve-exact-" + fixture.suffix(), 1);
        var firstReceipt = inputs.accept(runningT1, exactConfirmation);
        var replayReceipt = inputs.accept(runningT1, exactConfirmation);
        assertEquals(firstReceipt, replayReceipt, "同 input_id 同内容必须返回原批准回执");
        inputs.accept(runningT1, approval("approve-again-" + fixture.suffix(), 1));
        teamWork.tick(key);
        var afterRepeatedApproval = works.read(key).orElseThrow();
        assertEquals(1, afterRepeatedApproval.approvedRevision());
        assertEquals(firstBinding, afterRepeatedApproval.executions().get("T1").binding());
        assertEquals(1, afterRepeatedApproval.executions().size(), "重复批准不能提前准入依赖任务");
        assertEquals(1, delegationStore.list(parent).size());
        assertEquals(firstDelegation.childRun(), delegationStore.list(parent).getFirst().childRun());
        assertEquals(firstDelegation.childInput(), delegationStore.list(parent).getFirst().childInput());
        assertEquals(1, model.calls("T1"));
        assertEquals(0, model.calls("T2"));
        assertTrue(model.request("T1").contains("private-first"));
        assertFalse(model.request("T1").contains("private-coordinator"));
        assertFalse(model.request("T1").contains("private-second"));
        assertFalse(json.readTree(model.request("T1")).path("tools").toString().contains("submit_plan"), "子成员不暴露递归委派操作");

        model.release("T1");
        DagWork runningT2 = driveUntil(key, ignored -> model.entered("T2"), "T1 完成后 T2 未启动");
        assertEquals(DagWork.TaskStatus.COMPLETED, runningT2.executions().get("T1").status());
        assertTrue(model.request("T2").contains("result-T1"), "T2 必须读取 T1 的冻结成果");
        assertNativeDelegation(parent, runningT2, "T2");

        model.release("T2");
        DagWork completed = driveUntil(key, teamWork::stepsSettled,
                "两节点步骤尚未收尾");
        assertEquals(DagWork.Status.ACTIVE,completed.status(),"原主Run仍在汇总，工作不能提前完成");
        assertEquals(DagWork.TaskStatus.COMPLETED, completed.executions().get("T2").status());
        assertTrue(results.report(completed, completed.executions().get("T2"))
                .getFirst().get("content").toString().endsWith("\n\nresult-T2"));
        assertTrue(teamWork.finish(completed).isEmpty(),"原主Run完成前不能提交工作结果闭包");
        assertEquals(RunStatus.RUNNING, runtime.fact(owner, parentRun).orElseThrow().status(),
                "子任务闭包不能替换或提前终结父 Run");
        var summarizing = visibleWork(fixture, completed);
        assertEquals(parentRun.runId(), summarizing.runId());
        assertEquals(parent.causationInputId().value(), summarizing.inputId());
        assertEquals("summarizing", summarizing.stage());

        // 计划由夹具在MODEL在途时提交；父模型须经正式plan_get采纳当前已完成状态后再作最终回答。
        model.planWorkId = parent.causationInputId().value();
        model.release(ControlledModel.CONTROL);
        awaitParentStatus(owner, parentRun, RunStatus.COMPLETED);
        assertEquals(2, model.calls(ControlledModel.CONTROL), "父模型先读取当前计划，再提交最终答复");
        var closed=driveUntil(key,value->value.status()==DagWork.Status.COMPLETED,"原主Run完成后工作未关闭");
        assertEquals(new ResultRef("work-results:"+key.rootInputId().value()+":"+closed.revision()),teamWork.finish(closed).orElseThrow());
        var finished = visibleWork(fixture, closed);
        assertEquals("finished", finished.stage());
        assertTrue(finished.runRevision() > summarizing.runRevision());
        assertEquals(2, delegationStore.list(parent).size());
        assertEquals(1, model.calls("T1"));
        assertEquals(1, model.calls("T2"));
        for (var entry : delegationStore.list(parent)) {
            String childSession = runBindings.find(entry.childRun()).orElseThrow().owner().sessionRef().sessionId();
            assertTrue(childSession.length() > 64, "覆盖完整委派 Session 身份入账");
            assertEquals(1, queryCount(jdbc, fixture.tenantId(), """
                    SELECT count(*) FROM nw_usage_events
                     WHERE tenant_id=? AND session_id=? AND run_id=? AND event_type='llm.chat' AND tokens>0
                    """, Long.parseLong(fixture.tenantId()), childSession, entry.childRun().runId()));
        }
    }

    @Test
    @Timeout(90)
    void ordinaryQuestionDoesNotCreatePlanOrDelegateAndKeepsPrivatePromptsIsolated() throws Exception {
        Fixture fixture = fixture(false);
        var request = new ChatRequest("直接说明你的职责", fixture.session().getSessionId(),
                fixture.coordinator().getId().toString(), null, null, null, null, null,
                "plain-" + fixture.suffix(), null,
                Map.of("delegation_requester_id", fixture.requester().getId().toString()));
        var accepted = submit(fixture, new ChatRuntimeGateway.Submission(request, fixture.session(),
                fixture.coordinator().getId().toString(), fixture.user().getId(), null));
        SessionOwner owner = TeamWorkService.owner(fixture.session());
        RunRef run = new RunRef(new TenantId(fixture.tenantId()), accepted.executionId());
        model.awaitEntered(ControlledModel.CONTROL, WAIT);
        var parent = runtime.fact(owner, run).orElseThrow().identity();
        assertTrue(delegationStore.list(parent).isEmpty());
        assertTrue(works.read(new DagWork.Key(owner, parent.causationInputId())).isEmpty());
        assertTrue(model.request(ControlledModel.CONTROL).contains("private-coordinator"));
        assertFalse(model.request(ControlledModel.CONTROL).contains("private-first"));
        assertFalse(model.request(ControlledModel.CONTROL).contains("private-second"));
        model.release(ControlledModel.CONTROL);
        awaitParentStatus(owner, run, RunStatus.COMPLETED);
        assertTrue(delegationStore.list(parent).isEmpty());
    }

    @Test
    @Timeout(90)
    void privateFinalOutputFailsPersistentlyWithoutAnotherModelOrNewRunOnReplay() throws Exception {
        Fixture fixture=fixture(false);
        var request=new ChatRequest("说明结果",fixture.session().getSessionId(),fixture.coordinator().getId().toString(),
                null,null,null,null,null,"public-check-"+fixture.suffix(),null,
                Map.of("delegation_requester_id",fixture.requester().getId().toString()));
        var submission=new ChatRuntimeGateway.Submission(request,fixture.session(),fixture.coordinator().getId().toString(),fixture.user().getId(),null);
        var accepted=submit(fixture, submission);var owner=TeamWorkService.owner(fixture.session());
        var run=new RunRef(new TenantId(fixture.tenantId()),accepted.executionId());
        model.awaitEntered(ControlledModel.CONTROL,WAIT);
        String privateOutput = "Authorization: Bearer private-test-" + fixture.suffix();
        model.outputs.put(ControlledModel.CONTROL, privateOutput);
        model.release(ControlledModel.CONTROL);awaitParentStatus(owner,run,RunStatus.FAILED);
        var terminal=awaitCompletedEvent(owner,run);
        var rejected=json.readTree(terminal.event().data().canonicalJson());
        assertEquals("FAILED",rejected.path("status").asText());
        assertEquals(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.REJECTION_CODE,rejected.path("reason").asText());
        assertFalse(rejected.path("text").asText().contains(privateOutput));
        assertEquals(1,model.calls(ControlledModel.CONTROL));
        var history=chatProjection.read(fixture.session());
        assertTrue(history.messages().stream().noneMatch(message->message.content().contains(privateOutput)));
        var replay=submit(fixture, submission);assertEquals(accepted.executionId(),replay.executionId());
        assertEquals(1,model.calls(ControlledModel.CONTROL));
    }

    @Test
    @Timeout(90)
    void businessTechnicalIdentifiersRemainPublicWhenTheyAreNotPrivateRuntimeFacts() throws Exception {
        Fixture fixture=fixture(false);
        var request=new ChatRequest("解释订单API",fixture.session().getSessionId(),fixture.coordinator().getId().toString(),
                null,null,null,null,null,"technical-check-"+fixture.suffix(),null,
                Map.of("delegation_requester_id",fixture.requester().getId().toString()));
        var accepted=submit(fixture, new ChatRuntimeGateway.Submission(request,fixture.session(),fixture.coordinator().getId().toString(),fixture.user().getId(),null));
        var owner=TeamWorkService.owner(fixture.session());var run=new RunRef(new TenantId(fixture.tenantId()),accepted.executionId());
        model.awaitEntered(ControlledModel.CONTROL,WAIT);
        String technical="GET /api/orders/{id} 返回业务编号 a025596e-167a-4c25-a214-f610874c545f；字段 id 和 order_id。";
        model.outputs.put(ControlledModel.CONTROL,technical);model.release(ControlledModel.CONTROL);
        awaitParentStatus(owner,run,RunStatus.COMPLETED);
        assertEquals(technical,model.response(ControlledModel.CONTROL),"真实模型HTTP正文必须是指定业务说明");
        assertEquals(technical,runtimeFacts.completedText(runtime.fact(owner,run).orElseThrow()),"已采纳的真实模型Respond必须保留业务正文");
        var finalEvent=awaitCompletedEvent(owner,run);
        assertEquals(technical,json.readTree(finalEvent.event().data().canonicalJson()).path("text").asText(),
                "原final事件必须保留真实业务正文");
        var messages=awaitPublicFinal(fixture.session(),run,technical);
        assertTrue(messages.stream().anyMatch(message->message.content().equals(technical)
                        && run.runId().equals(message.metadata().get("run_id"))),
                ()->"公开final正文必须精确保留业务UUID和API字段；actual="+messages.stream().map(message->message.content()).toList());
        assertEquals(1,model.calls(ControlledModel.CONTROL));
    }

    /** 模拟已认证请求的线程上下文；成员、会话和成果授权仍由真实数据库校验。 */
    private com.skillforge.institution.team.TeamPublicViews.Work visibleWork(Fixture fixture, DagWork work) {
        return withAuthenticatedMember(fixture.requester().getId(), () -> workView.view(work));
    }

    private com.skillforge.core.chat.dto.response.ChatResult submit(
            Fixture fixture, ChatRuntimeGateway.Submission submission) {
        return withAuthenticatedMember(fixture.requester().getId(), () -> gateway.submit(submission));
    }

    /** 手工驱动计划仍借用真实事务与该次已派发MODEL冻结的补充输入边界。 */
    private com.skillforge.runtime.api.port.PlanPort.Reply invoke(Fixture fixture, RunIdentity run, String key,
            com.skillforge.runtime.api.port.PlanCommand command) {
        return withAuthenticatedMember(fixture.requester().getId(), () -> {
            var modelAction = executions.actions(run.runRef(), 0, 100).stream()
                    .filter(action -> action.kind() == ExecutionStore.Kind.MODEL).reduce((first, last) -> last).orElseThrow();
            var attempt = executions.attempt(run.runRef(), modelAction.currentAttempt()).orElseThrow();
            var request = executions.material(run.runRef().tenantId(), attempt.request()).orElseThrow();
            long includedThrough = executionCodec.decodeModelRequest(request.content(), refs -> executions.materials(run.runRef().tenantId(), refs)).supplementalIncludedThrough();
            var binding = runBindings.find(run.runRef()).orElseThrow();
            try (var sql = sqlSessions.openSession(false)) {
                assertFalse(sql.getConnection().getAutoCommit(), "计划夹具必须在真实事务中原子写入DAG与SessionLog");
                try (var statement = sql.getConnection().prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    statement.setString(1, run.runRef().tenantId().value());
                    statement.execute();
                }
                var reply = planHost.participate(sql, run, binding, includedThrough,
                        () -> teamWork.participatePlan(run, binding, () -> planHost.execute(run, key, command)));
                sql.commit();
                return reply;
            } catch (java.sql.SQLException failure) {
                throw new AssertionError("计划测试事务失败", failure);
            }
        });
    }

    private Fixture fixture(boolean studio) {
        requireRunningRuntimeWorkers(application, runtimeProperties);
        String suffix = Long.toString(SnowflakeIds.nextId());
        String tenantId = createTenant(tenants, plans, "team-dag-" + suffix);
        TenantContext.setTenantId(tenantId);
        long tenant = Long.parseLong(tenantId);
        PlatformModelFixtures.selectLlm(modelCatalog, tenantId, model.endpoint(),
                "team-dag-fixture", "team-dag-fixture-key",
                "custom", "custom-openai-compatible", "custom-v1");
        provisioner.provision(tenantId);

        TenantMemberDO requester = new TenantMemberDO();
        requester.setId(SnowflakeIds.nextId());
        requester.setTenantId(tenant);
        requester.setUsername("team-dag-" + suffix);
        requester.setRole("admin");
        requester.setPermissions(List.of());
        requester.setStatus("active");
        requester.setCreatedAt(Instant.now());
        members.insert(requester);
        initializeTenantAdministrator(tenantId, requester.getId());

        EndUserDO user = new EndUserDO();
        user.setId(SnowflakeIds.nextId());
        user.setTenantId(tenant);
        user.setDisplayName("Team DAG fixture");
        user.setStatus("active");
        user.setFirstSeenAt(Instant.now());
        user.setLastActiveAt(Instant.now());
        user.setCreatedAt(Instant.now());
        users.insert(user);

        AgentDO coordinator = agent(tenant, suffix, "coordinator", "协调员");
        AgentDO first = agent(tenant, suffix, "first", "成员一");
        AgentDO second = agent(tenant, suffix, "second", "成员二");
        coordinator.setDelegationConfig(new com.skillforge.core.agent.model.AgentDelegationConfig(
                true, List.of(first.getId().toString(), second.getId().toString())));
        agents.insert(coordinator);
        agents.insert(first);
        agents.insert(second);
        // 成员须拥有正式发布快照；状态字段本身不能构成Team或新Run可执行契约。
        { AgentDO candidate = first; first = withAuthenticatedMember(requester.getId(), () -> publishAgent(candidate)); }
        { AgentDO candidate = second; second = withAuthenticatedMember(requester.getId(), () -> publishAgent(candidate)); }
        { AgentDO candidate = coordinator; coordinator = withAuthenticatedMember(requester.getId(), () -> publishAgent(candidate)); }

        TeamDefinition saved = teams.save(tenantId, null, new TeamDefinition(
                null, "DAG 组合验收", "验证原生委派与依赖准入", "draft", 0,
                List.of(member(coordinator), member(first), member(second)),
                coordinator.getId().toString()));
        TeamDefinition published = teams.publish(tenantId, saved.id());

        SessionDO session = new SessionDO();
        session.setId(SnowflakeIds.nextId());
        session.setSessionId("team-dag-" + suffix);
        session.setTenantId(tenant);
        session.setEndUserId(user.getId());
        session.setOriginKind("native");
        session.setActorRef("member:" + requester.getId());
        session.setResponsibilityRef("tenant:" + tenant);
        if (studio) session.setTeamId(published.id());
        else session.setAgentId(coordinator.getId());
        session.setMessageCount(0);
        session.setLastEventId(0L);
        session.setMetadata(Map.of());
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        sessions.insert(session);
        return new Fixture(suffix, tenantId, requester, user, coordinator, first, second, published, com.skillforge.institution.TestSessions.view(session));
    }

    private AgentDO agent(long tenant, String suffix, String key, String name) {
        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId());
        agent.setTenantId(tenant);
        agent.setAgentKey("team-dag-" + key + "-" + suffix);
        agent.setName(name);
        agent.setSystemPrompt("按任务要求直接返回确定性文本结果。private-" + key);
        agent.setRoleDescription("Team DAG 组合验收成员");

        agent.setConfig(Map.of());
        agent.setStatus("draft");
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        return agent;
    }

    /** 通过正式发布服务形成机构Agent发布指针，并在Team组装前复验发布配置。 */
    private AgentDO publishAgent(AgentDO draft) {
        agentManagement.publish(draft.getId().toString());
        var publication=agentPublications.requirePublished(draft.getTenantId(),draft.getId());
        assertEquals("published",publication.agent().getStatus());
        assertEquals(1,publication.revision());
        assertEquals(publication.revisionId(),publication.agent().getPublishedRevisionId());
        assertEquals(draft.getSystemPrompt(),publication.agent().getSystemPrompt());
        return publication.agent();
    }

    private static TeamDefinition.Member member(AgentDO agent) {
        return new TeamDefinition.Member(
                agent.getId().toString(), "", agent.getName(), agent.getRoleDescription(), List.of());
    }

    private static DagPlan plan(Fixture fixture) {
        return new DagPlan(1, true, List.of(
                task("T1", new AgentId(fixture.first().getId().toString()), List.of()),
                task("T2", new AgentId(fixture.second().getId().toString()),
                        List.of(new DagPlan.Input("from-t1", "T1", "result")))));
    }

    private static DagPlan.Task task(String id, AgentId agent, List<DagPlan.Input> dependencies) {
        return new DagPlan.Task(id, id, agent, "完成 " + id + " 并返回 result",
                dependencies, Set.of("result"), Set.of());
    }

    private static TeamWorkInputs.Control approval(String inputId, long revision) {
        return new TeamWorkInputs.Control(
                inputId, "", "approve_plan", "", revision, "", "", "");
    }

    private void assertNativeDelegation(RunIdentity parent, DagWork work, String taskId) {
        DagWork.Execution execution = work.executions().get(taskId);
        String operation = "dag:" + execution.binding().admissionId();
        String delegationId = SessionDelegationEngine.id(parent.runRef(), operation);
        RunRef expectedRun = SessionDelegationEngine.childRun(parent.runRef(), operation);
        assertEquals(expectedRun, execution.binding().runRef());

        SessionDelegations.Entry entry = delegationStore.read(parent, delegationId).orElseThrow();
        assertEquals(parent, entry.parent());
        assertEquals(expectedRun, entry.childRun());
        assertEquals(execution.binding().task().agentId(), entry.target().agentId());

        AgentRunBindings.Binding binding = runBindings.find(expectedRun).orElseThrow();
        assertEquals(AgentRunBindings.Role.DELEGATE, binding.role());
        assertEquals(entry.target().agentId(), binding.actor());
        assertEquals("delegation:" + delegationId, binding.owner().sessionRef().sessionId());
        assertEquals(parent.owner().target().kind() == com.skillforge.runtime.api.SessionTarget.Kind.TEAM
                ? parent.owner().target() : com.skillforge.runtime.api.SessionTarget.agent(entry.target().agentId()), binding.owner().target());
        assertEquals(studioScope(parent), binding.scope().studio().isPresent());
        assertEquals(java.util.Optional.of(parent.runRef()), binding.parentRunRef());
        assertRunBudgetUsesAgentSettings(
                binding.owner(), entry.childInput(), expectedRun, execution.binding().task().agentId());
    }

    private static boolean studioScope(RunIdentity parent) {
        return parent.owner().target().kind() == com.skillforge.runtime.api.SessionTarget.Kind.TEAM;
    }

    private void assertRunBudgetUsesAgentSettings(
            SessionOwner owner, InputId input, RunRef run, AgentId actor) {
        var admitted = executions.load(run).orElseThrow();
        assertEquals(owner, admitted.identity().owner()); assertEquals(input, admitted.identity().causationInputId());
        assertEquals(actor, admitted.identity().agentId());
        var expected = provisioner.prepareExecutionRun(run.tenantId().value(), actor.value(), run.runId());
        assertEquals(expected.profile(), admitted.configuration());
        assertEquals(expected.budget(), budgets.find(run).orElseThrow(), "团队执行必须沿用目标Agent原冻结预算与根账");
    }

    private DagWork driveUntil(DagWork.Key key, Predicate<DagWork> condition, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        DagWork current = works.read(key).orElseThrow();
        while (System.nanoTime() < deadline) {
            model.assertHealthy();
            delegations.tick(key.owner().sessionRef().tenantId(), 100);
            teamWork.tick(key);
            current = works.read(key).orElseThrow();
            if (condition.test(current)) return current;
            Thread.sleep(25);
        }
        throw new AssertionError(failureMessage + "; work=" + current
                + "; modelCalls=" + model.callSnapshot(), deferredFailures.peek());
    }

    private void awaitParentStatus(SessionOwner owner, RunRef run, RunStatus expected)
            throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            model.assertHealthy();
            var fact = runtime.fact(owner, run);
            if (fact.isPresent() && fact.orElseThrow().status() == expected) return;
            Thread.sleep(25);
        }
        var current = runtime.fact(owner, run).orElseThrow();
        var terminal = executions.event(run, AgentExecutionProgram.finishedEventId(run));
        throw new AssertionError("父 Run 未进入状态 " + expected + "; actual=" + current.status()
                + "; phase=" + current.phase() + "; modelCalls=" + model.callSnapshot()
                + "; terminal=" + terminal.map(event -> event.event().data().canonicalJson()).orElse("missing"));
    }

    /** Run终态与唯一完成事件同事务保存，精确核对当前Run及其提交revision。 */
    private ExecutionStore.SavedEvent awaitCompletedEvent(SessionOwner owner, RunRef run) {
        var current=runtime.fact(owner,run).orElseThrow();
        var saved=executions.event(run,AgentExecutionProgram.finishedEventId(run)).orElseThrow();
        assertEquals(run,saved.run()); assertEquals(current.revision(),saved.revision());
        assertEquals(AgentExecutionProgram.COMPLETED_EVENT,saved.event().type());
        assertEquals(AgentExecutionProgram.COMPLETED_SCHEMA,saved.event().data().schemaId());
        return saved;
    }

    /** 等待同一 Run 的公开正文，成功条件仍是原始业务文本的精确相等。 */
    private List<ChatMessageRecord> awaitPublicFinal(com.skillforge.core.chat.session.SessionView session, RunRef run, String expected)
            throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        List<ChatMessageRecord> messages = List.of();
        while (System.nanoTime() < deadline) {
            model.assertHealthy();
            messages = chatProjection.read(session).messages();
            if (messages.stream().anyMatch(message -> expected.equals(message.content())
                    && run.runId().equals(message.metadata().get("run_id")))) return messages;
            Thread.sleep(25);
        }
        throw new AssertionError("公开final正文必须精确保留业务UUID和API字段；actual="
                + messages.stream().map(ChatMessageRecord::content).toList());
    }

    private record Fixture(
            String suffix,
            String tenantId,
            TenantMemberDO requester,
            EndUserDO user,
            AgentDO coordinator,
            AgentDO first,
            AgentDO second,
            TeamDefinition team,
            com.skillforge.core.chat.session.SessionView session) {
    }

    /** 受控本地 OpenAI-compatible 模型；屏障位于真实 HTTP 请求内。 */
    private static final class ControlledModel implements AutoCloseable {
        private static final String CONTROL = "CONTROL";
        private static final Set<String> BLOCKED = Set.of(CONTROL, "T1", "T2");

        private final ObjectMapper json;
        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> released = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<String, String> requests = new ConcurrentHashMap<>();
        private final Map<String, String> responseContents = new ConcurrentHashMap<>();
        private final Map<String,String> outputs=new ConcurrentHashMap<>();
        private volatile String planWorkId;
        private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        private ControlledModel(ObjectMapper json) throws IOException {
            this.json = json;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newFixedThreadPool(4, task -> {
                Thread thread = new Thread(task, "team-dag-model");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            for (String label : BLOCKED) {
                entered.put(label, new CountDownLatch(1));
                released.put(label, new CountDownLatch(1));
                calls.put(label, new AtomicInteger());
            }
        }

        private void start() {
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void awaitEntered(String label, Duration timeout) throws Exception {
            if (!entered.get(label).await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                assertHealthy();
                throw new AssertionError(label + " 未进入模型端点；calls=" + callSnapshot());
            }
            assertHealthy();
        }

        private boolean entered(String label) {
            return entered.get(label).getCount() == 0;
        }

        private int calls(String label) {
            return calls.get(label).get();
        }

        private String request(String label) {
            return requests.getOrDefault(label, "");
        }

        private String response(String label) {
            return responseContents.get(label.toLowerCase(Locale.ROOT));
        }

        private void release(String label) {
            released.get(label).countDown();
        }

        private Map<String, Integer> callSnapshot() {
            Map<String, Integer> snapshot = new LinkedHashMap<>();
            calls.forEach((label, count) -> snapshot.put(label, count.get()));
            return Map.copyOf(snapshot);
        }

        private void assertHealthy() {
            Throwable failure = failures.peek();
            if (failure != null) throw new AssertionError("受控模型端点失败", failure);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                String label = label(request);
                if ("MEMORY".equals(label)) {
                    write(exchange, request, json.writeValueAsString(Map.of("candidates", List.of())), "memory");
                    return;
                }
                AtomicInteger callCount = calls.get(label);
                if (callCount == null) {
                    throw new IllegalStateException("unexpected model request: " + request.path("messages"));
                }
                callCount.incrementAndGet();
                requests.put(label, request.toString());
                entered.get(label).countDown();
                // CONTROL覆盖整条编排链，最终由测试Timeout与close释放，不能套用单阶段等待预算。
                if (CONTROL.equals(label)) released.get(label).await();
                else if (!released.get(label).await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError(label + " 模型屏障未释放");
                }
                write(exchange, request, outputs.getOrDefault(label,"result-"+label), label.toLowerCase());
            } catch (Throwable failure) {
                failures.add(failure);
                byte[] body = ("fixture failure: " + failure.getClass().getSimpleName())
                        .getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(500, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // 原请求可能已因测试失败而关闭。
                }
            } finally {
                exchange.close();
            }
        }

        private String label(JsonNode request) {
            String messages = request.path("messages").toString();
            if (messages.contains("记忆提取器")) return "MEMORY";
            if (messages.contains("委派目标：完成 T1")) return "T1";
            if (messages.contains("委派目标：完成 T2")) return "T2";
            return CONTROL;
        }

        private void write(HttpExchange exchange, JsonNode request, String content, String id)
                throws IOException {
            responseContents.put(id,content);
            boolean streaming = request.path("stream").asBoolean();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content);
            String finish = "stop";
            if ("control".equals(id) && planWorkId != null && calls.get(CONTROL).get() == 1) {
                message.put("content", "");
                message.put("tool_calls", List.of(Map.of("index", 0, "id", "read-current-plan", "type", "function",
                        "function", Map.of("name", "plan_get", "arguments", json.writeValueAsString(Map.of("work_id", planWorkId))))));
                finish = "tool_calls";
            }
            byte[] body = streaming
                    ? stream(message, finish, id).getBytes(StandardCharsets.UTF_8)
                    : json.writeValueAsBytes(Map.of(
                            "id", "team-dag-" + id,
                            "object", "chat.completion",
                            "model", "team-dag-fixture",
                            "choices", List.of(Map.of(
                                    "index", 0,
                                    "message", message,
                                    "finish_reason", finish)),
                            "usage", usage()));
            exchange.getResponseHeaders().set(
                    "Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }

        private String stream(Map<String, ?> message, String finish, String id) throws IOException {
            String first = json.writeValueAsString(Map.of(
                    "id", "team-dag-" + id,
                    "object", "chat.completion.chunk",
                    "model", "team-dag-fixture",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", message))));
            Map<String, Object> finalChoice = new LinkedHashMap<>();
            finalChoice.put("index", 0);
            finalChoice.put("delta", Map.of());
            finalChoice.put("finish_reason", finish);
            String last = json.writeValueAsString(Map.of(
                    "id", "team-dag-" + id,
                    "object", "chat.completion.chunk",
                    "model", "team-dag-fixture",
                    "choices", List.of(finalChoice),
                    "usage", usage()));
            return "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
        }

        private static Map<String, Object> usage() {
            return Map.of("prompt_tokens", 20, "completion_tokens", 5, "total_tokens", 25);
        }

        @Override
        public void close() {
            released.values().forEach(CountDownLatch::countDown);
            server.stop(0);
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("模型端点线程未静止");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待模型端点清理时被中断", interrupted);
            }
        }
    }
}
