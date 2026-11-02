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
class MixedPlanRuntimeIntegrationTest extends AbstractIntegrationTest {
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

    @Autowired private com.skillforge.institution.runtime.InstitutionPlanHost planHost;

    @Test @Timeout(120)
    void primaryThenTwoRealMembersInParallelThenOriginalPrimary() throws Exception {
        var fixture=fixture(false);String workId="mixed-input-"+fixture.suffix();
        var request=new ChatRequest("先核查资料，并行调查与分析，再由主助手汇总",fixture.session().getSessionId(),fixture.coordinator().getId().toString(),null,
                null,null,null,null,workId,null,Map.of("input_id",workId,"end_user_id",fixture.user().getId(),"delegation_requester_id",fixture.requester().getId().toString()));
        var accepted=submit(fixture,new ChatRuntimeGateway.Submission(request,fixture.session(),fixture.coordinator().getId().toString(),fixture.user().getId(),null));
        model.awaitEntered(ControlledModel.CONTROL,WAIT);
        var owner=TeamWorkService.owner(fixture.session());var run=new RunRef(new TenantId(fixture.tenantId()),accepted.executionId());
        var parent=runtime.fact(owner,run).orElseThrow().identity();var key=new DagWork.Key(owner,new InputId(workId));
        var commandTasks=List.of(
                primaryTask("P0",List.of()),
                memberTask("T1",fixture.first(),List.of(new com.skillforge.runtime.api.port.PlanCommand.Input("brief","P0","answer"))),
                memberTask("T2",fixture.second(),List.of(new com.skillforge.runtime.api.port.PlanCommand.Input("brief","P0","answer"))),
                primaryTask("P3",List.of(new com.skillforge.runtime.api.port.PlanCommand.Input("research","T1","answer"),new com.skillforge.runtime.api.port.PlanCommand.Input("analysis","T2","answer"))));
        var submit=new com.skillforge.runtime.api.port.PlanCommand(com.skillforge.runtime.api.port.PlanCommand.Operation.SUBMIT,"",0,
                Optional.of(new com.skillforge.runtime.api.port.PlanCommand.Plan("核查、并行分析与汇总","",commandTasks)),"","","",List.of(),List.of());
        invoke(fixture,parent,"mixed-submit",submit);
        teamWork.tick(key);assertTrue(works.read(key).orElseThrow().executions().isEmpty(),"成员依赖PRIMARY结果，不能提前派发");
        invoke(fixture,parent,"mixed-start-P0",start(workId,"P0"));
        invoke(fixture,parent,"mixed-finish-P0",finish(workId,"P0","核查完成，供并行成员使用"));
        var concurrent=driveUntil(key,work->model.entered("T1")&&model.entered("T2")
                && work.executions().get("T1").status()==DagWork.TaskStatus.RUNNING
                && work.executions().get("T2").status()==DagWork.TaskStatus.RUNNING,"两个独立成员未同时进入真实模型屏障");
        assertFalse(concurrent.executions().containsKey("P3"));
        assertEquals(DagWork.TaskStatus.RUNNING,concurrent.executions().get("T1").status());
        assertEquals(DagWork.TaskStatus.RUNNING,concurrent.executions().get("T2").status());
        assertNativeDelegation(parent,concurrent,"T1");assertNativeDelegation(parent,concurrent,"T2");
        assertTrue(model.request("T1").contains("核查完成，供并行成员使用"));assertTrue(model.request("T2").contains("核查完成，供并行成员使用"));
        model.release("T1");model.release("T2");
        var settled=driveUntil(key,w->w.executions().get("T1").status()==DagWork.TaskStatus.COMPLETED && w.executions().get("T2").status()==DagWork.TaskStatus.COMPLETED,"并行成员结果未持久完成");
        invoke(fixture,parent,"mixed-start-P3",start(workId,"P3"));
        var summarizing=works.read(key).orElseThrow();var last=summarizing.executions().get("P3");
        assertEquals(run,last.binding().runRef());assertEquals(Set.of("research","analysis"),last.binding().inputs().keySet());
        var research=withAuthenticatedMember(fixture.requester().getId(),()->results.inputText(summarizing,last.binding().inputs().get("research"),parent.agentId()));
        var analysis=withAuthenticatedMember(fixture.requester().getId(),()->results.inputText(summarizing,last.binding().inputs().get("analysis"),parent.agentId()));
        assertTrue(research.contains("result-T1"));assertTrue(analysis.contains("result-T2"));
        invoke(fixture,parent,"mixed-finish-P3",finish(workId,"P3","已整合两个成员结果"));
        var beforeReply=works.read(key).orElseThrow();assertTrue(teamWork.stepsSettled(beforeReply));assertEquals(DagWork.Status.ACTIVE,beforeReply.status(),"成员和步骤结束不能替代主Run最终答复");
        assertEquals("summarizing",visibleWork(fixture,beforeReply).stage());
        model.release(ControlledModel.CONTROL);awaitParentStatus(owner,run,RunStatus.COMPLETED);teamWork.tick(key);
        var completed=driveUntil(key,w->w.status()==DagWork.Status.COMPLETED,"原主Run完成后工作未关闭");assertEquals(4,completed.executions().size());assertEquals(2,delegationStore.list(parent).size());
        assertEquals(run,completed.executions().get("P0").binding().runRef());assertEquals(run,completed.executions().get("P3").binding().runRef());
        assertEquals("finished",visibleWork(fixture,completed).stage());
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

    private static com.skillforge.runtime.api.port.PlanCommand start(String work,String task) {
        return new com.skillforge.runtime.api.port.PlanCommand(com.skillforge.runtime.api.port.PlanCommand.Operation.START_STEP,work,1,Optional.empty(),task,"","",List.of(),List.of());
    }
    private static com.skillforge.runtime.api.port.PlanCommand finish(String work,String task,String text) {
        return new com.skillforge.runtime.api.port.PlanCommand(com.skillforge.runtime.api.port.PlanCommand.Operation.FINISH_STEP,work,1,Optional.empty(),task,"completed",text,List.of(),List.of(),Map.of("answer",text));
    }
    private static com.skillforge.runtime.api.port.PlanCommand.Task primaryTask(String id,List<com.skillforge.runtime.api.port.PlanCommand.Input> inputs) {
        return new com.skillforge.runtime.api.port.PlanCommand.Task(id,"完成"+id,new com.skillforge.runtime.api.port.PlanCommand.Executor(com.skillforge.runtime.api.port.PlanCommand.ExecutorType.PRIMARY,""),
                new com.skillforge.runtime.api.port.DelegationCommand.Task("完成 "+id,"",List.of(),"独立任务结果"),inputs,Set.of("answer"),Set.of());
    }
    private static com.skillforge.runtime.api.port.PlanCommand.Task memberTask(String id,AgentDO member,List<com.skillforge.runtime.api.port.PlanCommand.Input> inputs) {
        return new com.skillforge.runtime.api.port.PlanCommand.Task(id,"完成"+id,new com.skillforge.runtime.api.port.PlanCommand.Executor(com.skillforge.runtime.api.port.PlanCommand.ExecutorType.MEMBER,"member:"+member.getId()),
                new com.skillforge.runtime.api.port.DelegationCommand.Task("完成 "+id,"",List.of(),"独立任务结果"),inputs,Set.of("answer"),Set.of());
    }

    private com.skillforge.institution.team.TeamPublicViews.Work visibleWork(Fixture fixture, DagWork work) {
        return withAuthenticatedMember(fixture.requester().getId(), () -> workView.view(work));
    }

    private com.skillforge.core.chat.dto.response.ChatResult submit(
            Fixture fixture, ChatRuntimeGateway.Submission submission) {
        return withAuthenticatedMember(fixture.requester().getId(), () -> gateway.submit(submission));
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
        throw new AssertionError("父 Run 未进入状态 " + expected);
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
            var message = Map.of("role", "assistant", "content", content);
            String finish = "stop";
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
