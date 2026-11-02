package com.skillforge.institution.integration;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.institution.team.TeamResultService;
import com.skillforge.institution.team.TeamWorkInputs;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog;
import com.skillforge.runtime.session.spi.SessionDelegations;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.SessionExecutionPlans;
import com.skillforge.institution.runtime.AgentRuntimePlanDependencies;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 无协作者主 Run 的原生计划、审批等待、独立步骤结果与真实 PostgreSQL 恢复链。 */
@Import(CodeExecutionChatIntegrationTest.VerifiedModelProviderConfiguration.class)
class UnifiedPlanRuntimeIntegrationTest extends AbstractIntegrationTest {
    private static final Duration WAIT=Duration.ofSeconds(45);
    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"true");
        registry.add("skillforge.team.poll-ms",()->"3600000");
    }
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
    @Autowired private TeamRuntimeBridge runtime;
    @Autowired private JdbcDagWorkPort works;
    @Autowired private TeamWorkInputs inputs;
    @Autowired private TeamWorkService teamWork;
    @Autowired private TeamResultService results;
    @Autowired private com.skillforge.institution.team.TeamWorkView workView;
    @Autowired private SessionDelegations.Store delegationStore;
    @Autowired private ExecutionStore executions;
    @Autowired private AgentRuntimePlanDependencies planDependencies;
    @Autowired private SessionExecutionPlans planReplies;
    @Autowired private com.skillforge.runtime.api.port.SessionLogPort sessionLog;
    @Autowired private com.skillforge.institution.runtime.AgentRuntimeExecutionProjection chatProjection;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private ScriptedModel model;
    @BeforeEach void start() throws IOException {
        com.skillforge.common.context.RequestContext.setTraceId("unified-plan-"+java.util.UUID.randomUUID());
        model=new ScriptedModel(json);model.server.start();
    }
    @AfterEach void close() {
        if(model!=null)model.close();TenantContext.clear();com.skillforge.common.context.RequestContext.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @Timeout(120)
    void soloPrimaryPlanUsesOriginalRunAndIndependentNamedOutputs(boolean approval) throws Exception {
        var fixture=fixture();String inputId="input-"+fixture.suffix();model.configure(inputId,approval);
        if(approval)withAuthenticatedMember(fixture.requester().getId(),()->gateway.selectPlan(fixture.session(),"select-plan:"+inputId,true));
        var request=new ChatRequest("先核查资料，再输出报告",fixture.session().getSessionId(),fixture.coordinator().getId().toString(),null,
                null,null,null,null,inputId,null,Map.of("input_id",inputId,"end_user_id",fixture.user().getId(),"delegation_requester_id",fixture.requester().getId().toString()));
        var accepted=withAuthenticatedMember(fixture.requester().getId(),()->gateway.submit(new ChatRuntimeGateway.Submission(request,fixture.session(),fixture.coordinator().getId().toString(),fixture.user().getId(),null)));
        var owner=TeamWorkService.owner(fixture.session());var key=new DagWork.Key(owner,new InputId(inputId));
        var run=new RunRef(new TenantId(fixture.tenantId()),accepted.executionId());
        ExecutionStore.Waiting approvalWait=null;
        if(approval) {
            await(()->works.read(key).isPresent() && runtime.fact(owner,run).map(r->r.status()==RunStatus.SUSPENDED).orElse(false),"计划未持久挂起审批");
            var suspended=executions.load(run).orElseThrow();
            approvalWait=executions.waiting(run,suspended.waiting().orElseThrow()).orElseThrow();
            assertEquals(ExecutionStore.WaitKind.PLAN,approvalWait.kind()); assertEquals(run,approvalWait.run());
            var dependency=assertInstanceOf(ExecutionStore.PlanWait.class,approvalWait.internal().orElseThrow());
            assertEquals(inputId,dependency.planId());assertEquals(1,dependency.planRevision());
            assertTrue(approvalWait.acceptedResponse().isEmpty(),"未批准时不能接受或采纳Plan依赖响应");
            assertTrue(approvalWait.consumedRevision().isEmpty());assertTrue(approvalWait.continuationAttempt().isEmpty());
            var before=works.read(key).orElseThrow();assertTrue(before.executions().isEmpty());
            inputs.accept(before,new TeamWorkInputs.Control("wrong-"+inputId,"","approve_plan","",2L,"","",""));
            teamWork.tick(key);planDependencies.recover(run.tenantId(),100);
            assertTrue(works.read(key).orElseThrow().executions().isEmpty());
            assertTrue(executions.waiting(run,approvalWait.reference()).orElseThrow().acceptedResponse().isEmpty(),"错误批准不能唤醒原精确等待");
            inputs.accept(works.read(key).orElseThrow(),new TeamWorkInputs.Control("approve-"+inputId,"","approve_plan","",1L,"","",""));
            teamWork.tick(key);
        }
        await(()->{
            // 定时扫描已停用；通过正式恢复入口同时推进持久计划等待和 DAG。
            teamWork.recover(new TenantId(fixture.tenantId()),100);
            planDependencies.recover(new TenantId(fixture.tenantId()),100);
            return runtime.fact(owner,run).map(r->r.status()==RunStatus.COMPLETED).orElse(false);
        },"原主 Run 未完成计划及最终答复");
        if(approvalWait!=null) {
            var consumed=executions.waiting(run,approvalWait.reference()).orElseThrow();
            assertEquals(approvalWait.reference(),consumed.reference());assertEquals(approvalWait.internal(),consumed.internal());
            assertTrue(consumed.acceptedResponse().isPresent(),"审批解除必须保存原等待的准确响应");
            assertTrue(consumed.consumedRevision().isPresent());assertTrue(consumed.continuationAttempt().isEmpty(),"本地Plan响应不能登记Provider续跑Attempt");
            var acceptedResponse=consumed.acceptedResponse().orElseThrow();
            assertTrue(consumed.consumedRevision().getAsLong()>acceptedResponse.acceptedRevision());
            var saved=executions.material(run.tenantId(),acceptedResponse.response()).orElseThrow();
            var response=planReplies.decodeResponse(saved.content());
            assertTrue(json.readTree(response.value().canonicalJson()).path("wait_satisfied").asBoolean());
            assertTrue(response.state().executionApproved());
        }
        var work=works.read(key).orElseThrow();assertEquals(Set.of("S1","S2"),work.executions().keySet());
        var first=work.executions().get("S1");var second=work.executions().get("S2");
        assertEquals(DagWork.TaskStatus.COMPLETED,first.status());assertEquals(DagWork.TaskStatus.COMPLETED,second.status());
        assertEquals(run,first.binding().runRef());assertEquals(run,second.binding().runRef());
        assertNotEquals(first.binding().admissionId(),second.binding().admissionId());
        assertNotEquals(first.outputs().get("draft").resultRef(),first.outputs().get("sources").resultRef());
        assertNotEquals(first.outputs().get("draft").digest(),first.outputs().get("sources").digest());
        assertEquals(first.outputs().get("draft").resultRef(),second.binding().inputs().get("draft").resultRef());
        assertTrue(delegationStore.forSession(owner,1000).isEmpty(),"没有协作者时不能创建委派子 Run");
        var input=second.binding().inputs().get("draft");
        var received=withAuthenticatedMember(fixture.requester().getId(),()->results.inputText(work,input,new AgentId(fixture.coordinator().getId().toString())));
        assertTrue(received.contains("资料摘要"));assertFalse(received.contains("来源证据"),"不同命名产出不能串材料");
        var publicWork=withAuthenticatedMember(fixture.requester().getId(),()->workView.view(work));
        assertEquals(2,publicWork.tasks().size());assertTrue(publicWork.tasks().stream().allMatch(t->t.executorType().equals("PRIMARY")));
        assertEquals("finished",publicWork.stage());
        assertTrue(model.requests.stream().anyMatch(r->r.contains("plan_submit")));
        assertFalse(model.requests.getFirst().contains("delegation_targets"),"普通Agent没有配置委派时仍能使用计划");
        var durable=sessionLog.readAfter(new ReadSessionLog(owner,0,1000));
        assertTrue(durable.stream().anyMatch(e->e.eventType().equals("work.updated")));
        assertEquals(2,durable.stream().filter(e->e.eventType().equals("plan.step.finished")).count());
        var restored=works.read(key).orElseThrow();assertEquals(work.executions(),restored.executions());
        chatProjection.reconcile(fixture.session());chatProjection.reconcile(fixture.session());
        assertEquals(durable.stream().filter(e->e.eventType().equals("work.updated")).count(),queryCount(jdbc,fixture.tenantId(),
                "SELECT count(*) FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=? AND event_type='work.updated'",
                Long.parseLong(fixture.tenantId()),fixture.session().getSessionId()),"每次持久计划变更都可回放，重复投影不重复发布");
    }

    private void await(java.util.function.BooleanSupplier condition,String message) throws Exception {
        long deadline=System.nanoTime()+WAIT.toNanos();
        while(System.nanoTime()<deadline) {
            model.healthy();if(condition.getAsBoolean())return;Thread.sleep(50);
        }
        model.healthy();throw new AssertionError(message+"; model calls="+model.index.get());
    }
    private record Fixture(String suffix,String tenantId,TenantMemberDO requester,EndUserDO user,AgentDO coordinator,com.skillforge.core.chat.session.SessionView session) {}
    private Fixture fixture() {
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
        agents.insert(coordinator);
        { AgentDO candidate=coordinator; coordinator=withAuthenticatedMember(requester.getId(),()->publishAgent(candidate)); }

        SessionDO session = new SessionDO();
        session.setId(SnowflakeIds.nextId());
        session.setSessionId("team-dag-" + suffix);
        session.setTenantId(tenant);
        session.setEndUserId(user.getId());
        session.setAgentId(coordinator.getId());
        session.setMessageCount(0);
        session.setLastEventId(0L);
        session.setMetadata(Map.of());
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        sessions.insert(session);
        return new Fixture(suffix,tenantId,requester,user,coordinator, com.skillforge.institution.TestSessions.view(session));
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

    private static final class ScriptedModel implements AutoCloseable {
        private final ObjectMapper json;private final HttpServer server;private final ExecutorService executor=Executors.newCachedThreadPool();
        private final java.util.concurrent.CopyOnWriteArrayList<String> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<Throwable> failures=new ConcurrentLinkedQueue<>();
        private final AtomicInteger index=new AtomicInteger();private volatile List<Map<String,Object>> actions=List.of();
        ScriptedModel(ObjectMapper json) throws IOException {this.json=json;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",this::handle);}
        String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void configure(String work,boolean approval) {
            var first=task("S1",List.of(),List.of("draft","sources"));
            var second=task("S2",List.of(Map.of("name","draft","source_task","S1","output","draft")),List.of("answer"));
            var scripted=new java.util.ArrayList<Map<String,Object>>();
            scripted.add(action("plan_submit",Map.of("work_id","","expected_revision",0,"plan",Map.of("title","资料核查与报告","change_reason","","approval_required",approval,"tasks",List.of(first,second)))));
            if(approval)scripted.add(action("plan_wait",Map.of("work_id",work,"task_ids",List.of())));
            scripted.add(action("plan_start_step",Map.of("work_id",work,"expected_revision",1,"task_id","S1")));
            scripted.add(action("plan_finish_step",finish(work,"S1","完成资料核查",Map.of("draft","资料摘要","sources","来源证据"))));
            scripted.add(action("plan_start_step",Map.of("work_id",work,"expected_revision",1,"task_id","S2")));
            scripted.add(action("plan_finish_step",finish(work,"S2","报告核查完成",Map.of("answer","报告已核查"))));
            scripted.add(Map.of("content","报告已核查"));actions=List.copyOf(scripted);
        }
        private static Map<String,Object> finish(String work,String id,String summary,Map<String,String> outputs) {return Map.of("work_id",work,"expected_revision",1,"task_id",id,"outcome","completed","summary",summary,"result_refs",List.of(),"outputs",outputs);}
        private static Map<String,Object> task(String id,List<Map<String,String>> inputs,List<String> outputs) {
            var value=new LinkedHashMap<String,Object>();value.put("id",id);value.put("title",id.equals("S1")?"核查资料":"编写报告");
            value.put("executor",Map.of("type","primary","target_handle",""));value.put("task",Map.of("objective","完成"+id,"context","","constraints",List.of(),"expected_output","完成声明的产出"));
            value.put("inputs",inputs);value.put("outputs",outputs);value.put("exclusive_resources",List.of());value.put("cancelled",false);value.put("replaces_task_id","");return value;
        }
        private static Map<String,Object> action(String name,Map<String,Object> arguments) {
            var fields=new LinkedHashMap<String,Object>(arguments);
            if (!name.equals("ask_user")) fields.put("commentary","执行计划中的"+name+"操作");
            return Map.of("name",name,"arguments",fields);
        }
        private void requireFrozenSchema(JsonNode request,Map<String,Object> action) {
            var name=action.get("name");JsonNode declaration=null;
            for(var tool:request.path("tools")) if(tool.path("function").path("name").asText().equals(name)) declaration=tool.path("function").path("parameters");
            if(declaration==null)throw new AssertionError("模型夹具调用未声明的动作 "+name);
            requireFields(declaration,json.valueToTree(action.get("arguments")),name.toString());
        }
        private void requireFields(JsonNode schema,JsonNode value,String path) {
            if(value.isObject()) {
                for(var field:schema.path("required")) if(!value.has(field.asText()))throw new AssertionError("模型夹具缺少冻结必填字段 "+path+"/"+field.asText());
                var fields=value.fields();
                while(fields.hasNext()) {
                    var field=fields.next();var property=schema.path("properties").path(field.getKey());
                    if(property.isMissingNode() && schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean())throw new AssertionError("模型夹具字段未声明 "+path+"/"+field.getKey());
                    if(!property.isMissingNode())requireFields(property,field.getValue(),path+"/"+field.getKey());
                }
            } else if(value.isArray())for(var item:value)requireFields(schema.path("items"),item,path+"[]");
        }
        void healthy(){if(!failures.isEmpty())throw new AssertionError("受控模型失败",failures.peek());}
        void handle(HttpExchange exchange) throws IOException {
            try {
                var request=json.readTree(exchange.getRequestBody());boolean memory=request.path("messages").toString().contains("记忆提取器");
                Map<String,Object> message;String finish;Map<String,Object> tool=null;String id="memory";
                if(memory) {message=Map.of("role","assistant","content","{\"candidates\":[]}");finish="stop";}
                else {
                    healthy();requests.add(request.toString());int call=index.getAndIncrement();if(call>=actions.size())throw new IllegalStateException("unexpected model continuation "+call);
                    var action=actions.get(call);
                    if(action.containsKey("content")) {message=Map.of("role","assistant","content",action.get("content"));finish="stop";}
                    else {requireFrozenSchema(request,action);id="plan-"+call;tool=new LinkedHashMap<>();tool.put("id",id);tool.put("type","function");
                    tool.put("function",Map.of("name",action.get("name"),"arguments",json.writeValueAsString(action.get("arguments"))));
                    message=Map.of("role","assistant","tool_calls",List.of(tool));finish="tool_calls";}
                }
                var usage=Map.of("prompt_tokens",20,"completion_tokens",5,"total_tokens",25);byte[] response;
                if(request.path("stream").asBoolean()) {
                    if(tool!=null)tool.put("index",0);
                    var first=json.writeValueAsString(Map.of("id",id,"object","chat.completion.chunk","model","team-dag-fixture","choices",List.of(Map.of("index",0,"delta",message))));
                    var last=json.writeValueAsString(Map.of("id",id,"object","chat.completion.chunk","model","team-dag-fixture","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));
                    response=("data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                } else {response=json.writeValueAsBytes(Map.of("id",id,"object","chat.completion","model","team-dag-fixture","choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage));exchange.getResponseHeaders().set("Content-Type","application/json");}
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            } catch(Throwable failure) {failures.add(failure);exchange.sendResponseHeaders(500,-1);} finally {exchange.close();}
        }
        public void close(){server.stop(0);executor.shutdownNow();try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("模型线程未静止");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    }
}
