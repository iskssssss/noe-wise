package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** 新主链真实HTTP组合；资源发布、当前IAM、PG账本、实际模板工具和公共等待控件均走生产入口。 */
@Tag("runtime-execution-composition")
@Tag("runtime-latency")
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class,DeterministicModelCatalogConfiguration.class,
        com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration.class})
class ChatExecutionCompositionE2ETest extends ChatExecutionChainE2ETest {
    @Test
    void workflowHumanReplyExecutesRealTemplateNodeAndContinuesOriginalParentAction() throws Exception {
        scenario(RuntimeExecutionCompositionProvider.Scenario.WORKFLOW,(provider,evidence,report)-> {
            mountWorkflow(provider,report);
            String parent="run:"+runId;report.put("run",parent);report.put("session",sessionId);
            var input=chatPayload(provider.prompt());
            var accepted=send("/api/v1/chat","POST",input,accessToken);
            assertEquals(202,accepted.statusCode(),accepted.body());assertEquals(parent,successData(accepted).path("execution_id").asText());
            var capture=observeSseUntil(sessionId,null,parent,Set.of("hitl.required","execution.completed","error"));save(evidence,"waiting",capture);
            var waiting=waiting(capture);assertEquals("workflow_input",waiting.path("interaction_type").asText());
            assertEquals(parent,waiting.path("execution_id").asText());assertFalse(waiting.path("workflow_execution_id").asText().isBlank());
            assertEquals(1,provider.parentCalls());
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND phase='WAITING' AND status='SUSPENDED'",tenantId,parent));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_executions WHERE tenant_id=? AND run_id=? AND execution_id=? AND state='WAITING_HITL'",
                    tenantId,parent,waiting.path("workflow_execution_id").asText()));
            String workflowAction=waiting.path("tool_wait").path("action_id").asText();
            long workflowGeneration=waiting.path("tool_wait").path("generation").asLong();
            var reconnect=observeSseUntil(sessionId,null,parent,Set.of("hitl.required","error"));
            assertEquals(waiting,waiting(reconnect),"重连读取原持久Workflow卡片");
            String replyId="workflow-human-"+runId;var reply=workflowReply(waiting,replyId);
            var resumed=send("/api/v1/chat","POST",reply,accessToken);assertEquals(202,resumed.statusCode(),resumed.body());
            assertFalse(successData(resumed).path("replayed").asBoolean());assertEquals(parent,successData(resumed).path("execution_id").asText());
            var complete=observeSseUntil(sessionId,null,parent,Set.of("execution.completed","error"));save(evidence,"complete",complete);
            // 首个完成断言前保存准确Action的各轮等待元数据；失败也保留原回复与后续dependency的区分，不记录payload。
            report.put("workflowWaitFacts",queryRows(jdbc,tenantId,"SELECT w.action_id,w.generation,w.kind,w.source_attempt,w.input_id,w.accepted_revision,w.continuation_attempt,w.consumed_revision,w.execution_id,w.execution_revision,"
                    +"t.run_id AS continuation_run_id,t.action_id AS continuation_action_id,t.continuation_generation,t.adopted AS continuation_adopted,t.result_kind AS continuation_result_kind "
                    +"FROM agent_runtime_execution_waits w LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=w.tenant_id AND t.run_id=w.run_id AND t.action_id=w.action_id AND t.attempt_id=w.continuation_attempt "
                    +"WHERE w.tenant_id=? AND w.run_id=? AND w.action_id=? ORDER BY w.generation",tenantId,parent,workflowAction));
            assertTrue(complete.events().contains("execution.completed"),complete.raw());assertFalse(complete.events().contains("error"),complete.raw());
            assertTrue(complete.raw().contains(provider.finalAnswer()),complete.raw());
            await("Workflow and actual node complete",()->countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,parent)==1);
            assertEquals(2,provider.parentCalls());assertEquals(0,provider.childCalls());assertEquals(1,provider.snapshot().get("verifiedResults"));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='WORKFLOW' AND action_id=? AND status='SUCCEEDED'",tenantId,parent,workflowAction));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='WORKFLOW'",tenantId,parent));
            assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND action_id=? AND adopted",tenantId,parent,workflowAction)>=2,
                    "原Workflow Action须使用准确continuation并采纳原结果");
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits w JOIN agent_runtime_execution_attempts t "
                    +"ON t.tenant_id=w.tenant_id AND t.run_id=w.run_id AND t.action_id=w.action_id AND t.attempt_id=w.continuation_attempt AND t.continuation_generation=w.generation "
                    +"WHERE w.tenant_id=? AND w.run_id=? AND w.action_id=? AND w.generation=? AND w.input_id=? AND w.kind='WORKFLOW' AND w.accepted_revision IS NOT NULL AND w.consumed_revision IS NULL AND t.adopted AND t.result_kind='SUCCEEDED'",
                    tenantId,parent,workflowAction,workflowGeneration,replyId),"原Workflow回复必须由同Action、同generation的准确continuation Attempt消费并采纳");
            var nodes=queryRows(jdbc,tenantId,"SELECT a.run_id,a.action_id,t.attempt_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref WHERE a.tenant_id=? AND a.kind='TOOL' AND a.run_id LIKE 'workflow-node-run:%'",tenantId);
            assertEquals(1,nodes.size(),nodes.toString());String nodeRun=nodes.getFirst().get("run_id").toString();
            assertTrue(actualRendered(objectMapper.readTree(nodes.getFirst().get("canonical_json").toString()),provider.rendered()),"原node选定结果必须是真实模板输出");
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_budget_runs n JOIN agent_runtime_budget_runs p ON p.tenant_id=n.tenant_id AND p.root_id=n.root_id WHERE n.tenant_id=? AND n.run_id=? AND n.role='MEMBER' AND p.run_id=?",tenantId,nodeRun,parent));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_executions WHERE tenant_id=? AND run_id=? AND state='COMPLETED'",tenantId,parent));
            var before=attempts(parent,nodeRun);var replay=send("/api/v1/chat","POST",reply,accessToken);
            assertEquals(202,replay.statusCode(),replay.body());assertTrue(successData(replay).path("replayed").asBoolean());
            assertEquals(200,get("/api/v1/chat/runs/status?session_id="+sessionId+"&run_id="+parent).statusCode());
            assertEquals(before,attempts(parent,nodeRun));assertEquals(2,provider.parentCalls());
            assertNoOldGraph(parent);assertNoOldGraph(nodeRun);report.put("nodeRun",nodeRun);report.put("workflowAction",workflowAction);report.put("runFacts",runFacts(parent,nodeRun));
        });
    }

    @Test
    void delegationChildHumanReplyCompletesSharedBudgetAndParentLocalDependencyWithoutRedispatch() throws Exception {
        scenario(RuntimeExecutionCompositionProvider.Scenario.DELEGATION,(provider,evidence,report)-> {
            String childAgent=configureDelegation(provider);String parent="run:"+runId;
            report.put("run",parent);report.put("session",sessionId);report.put("childAgent",childAgent);
            var accepted=send("/api/v1/chat","POST",chatPayload(provider.prompt()),accessToken);
            assertEquals(202,accepted.statusCode(),accepted.body());assertEquals(parent,successData(accepted).path("execution_id").asText());
            var durable=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
            await("original parent DELEGATION and exact child HUMAN waits committed",()-> {
                var rows=delegatedHumanWait(parent);
                assertTrue(rows.size()<=1,"本夹具只允许一个准确原委派等待，不选择最新或任意child");
                if(rows.isEmpty())return false;durable.set(rows.getFirst());return true;
            });
            // 持久业务条件成立后只读一次公开卡片；200和完整pending仍为独立产品断言，429不得吞掉或重试。
            var response=get("/api/v1/chat/work?session_id="+sessionId+"&work_id="+runId);assertEquals(200,response.statusCode(),response.body());
            var current=successData(response);assertEquals(1,current.path("tasks").size());
            var task=current.path("tasks").get(0);var pending=task.path("pending");String child=task.path("run_id").asText(),delegation=task.path("task_id").asText();
            assertTrue(pending.isObject());assertEquals(durable.get().get("child_run_id").toString(),child);assertEquals(durable.get().get("delegation_id").toString(),delegation);
            assertEquals(durable.get().get("action_id").toString(),pending.path("tool_wait").path("action_id").asText());
            assertEquals(durable.get().get("generation").toString(),pending.path("tool_wait").path("generation").asText());
            assertFalse(child.isBlank());assertFalse(delegation.isBlank());assertEquals("direct",task.path("task_kind").asText());
            assertEquals(childAgent,task.path("agent_id").asText());assertEquals(child,pending.path("execution_id").asText());
            assertEquals(provider.question(),pending.path("prompt").asText());assertEquals("input",pending.path("kind").asText());
            assertEquals(pending.path("tool_wait").path("action_id").asText()+":"+pending.path("tool_wait").path("generation").asText(),pending.path("pending_id").asText());
            assertEquals(1,provider.parentCalls());assertEquals(1,provider.childCalls());
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='SUSPENDED' AND phase='WAITING'",tenantId,parent));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_budget_runs c JOIN agent_runtime_budget_runs p ON p.tenant_id=c.tenant_id AND p.root_id=c.root_id WHERE c.tenant_id=? AND c.run_id=? AND c.role='MEMBER' AND p.run_id=? AND p.role='PRIMARY'",tenantId,child,parent));
            var control=Map.<String,Object>of("session_id",sessionId,"work_id",runId,"task_id",delegation,"operation","reply_input","input_id","child-human-"+runId,
                    "pending_id",pending.path("pending_id").asText(),"parameters_version",pending.path("parameters_version").asText(),"content",provider.reply());
            var replied=send("/api/v1/chat/work/controls","POST",control,accessToken);assertEquals(200,replied.statusCode(),replied.body());
            var complete=observeSseUntil(sessionId,null,parent,Set.of("execution.completed","error"));save(evidence,"complete",complete);
            assertTrue(complete.events().contains("execution.completed"),complete.raw());assertFalse(complete.events().contains("error"),complete.raw());assertTrue(complete.raw().contains(provider.finalAnswer()),complete.raw());
            await("original parent and child finish",()->countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id IN (?,?) AND status='COMPLETED' AND phase='FINISHED'",tenantId,parent,child)==2);
            assertEquals(2,provider.parentCalls());assertEquals(2,provider.childCalls());assertEquals(1,provider.snapshot().get("verifiedReplies"));assertEquals(1,provider.snapshot().get("verifiedResults"));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='DELEGATION' AND status='SUCCEEDED'",tenantId,parent));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts t JOIN agent_runtime_execution_actions a ON a.tenant_id=t.tenant_id AND a.run_id=t.run_id AND a.action_id=t.action_id WHERE t.tenant_id=? AND t.run_id=? AND a.kind='DELEGATION'",tenantId,parent));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND kind='DELEGATION' AND input_id IS NOT NULL AND continuation_attempt IS NULL AND consumed_revision IS NOT NULL",tenantId,parent));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND kind='HUMAN' AND input_id=? AND continuation_attempt IS NULL AND consumed_revision IS NOT NULL",tenantId,child,"child-human-"+runId));
            assertEquals(2,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'",tenantId,child));
            var before=attempts(parent,child);var replay=send("/api/v1/chat/work/controls","POST",control,accessToken);assertEquals(200,replay.statusCode(),replay.body());
            assertEquals(200,get("/api/v1/chat/work?session_id="+sessionId+"&work_id="+runId).statusCode());
            assertEquals(200,get("/api/v1/chat/runs/status?session_id="+sessionId+"&run_id="+parent).statusCode());
            assertEquals(before,attempts(parent,child));assertEquals(2,provider.parentCalls());assertEquals(2,provider.childCalls());
            assertNoOldGraph(parent);assertNoOldGraph(child);report.put("childRun",child);report.put("delegation",delegation);report.put("durableWaiting",durable.get());report.put("publicWaiting",current);report.put("runFacts",runFacts(parent,child));
        });
    }

    private void scenario(RuntimeExecutionCompositionProvider.Scenario scenario,CompositionCase test) throws Exception {
        var evidence=Path.of("build","reports","runtime-execution-composition",scenario.name().toLowerCase()+"-"+SnowflakeIds.nextId());Files.createDirectories(evidence);
        var report=new LinkedHashMap<String,Object>();report.put("outcome","FAILED");report.put("scenario",scenario.name());report.put("recoveryScope","real HTTP persisted waits; no process restart claim");
        try(var provider=new RuntimeExecutionCompositionProvider(objectMapper,scenario,"composition-"+SnowflakeIds.nextId())) {
            provider.start();TenantContext.setTenantId(tenantId);try{configureTenantModel(provider.endpoint());}finally{TenantContext.clear();}login();
            try{test.run(provider,evidence,report);assertEquals(List.of(),provider.snapshot().get("failures"));report.put("outcome","PASS");}
            finally{report.put("provider",provider.snapshot());}
        } catch(Exception|AssertionError failed){report.put("failureType",failed.getClass().getName());throw failed;}
        finally{Files.writeString(evidence.resolve("report.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_COMPOSITION_REPORT "+evidence.resolve("report.json").toAbsolutePath());}
    }

    private void mountWorkflow(RuntimeExecutionCompositionProvider provider,Map<String,Object> report) throws Exception {
        revise();long definition=0,version=0;String versionKey="";
        for(var item:successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED")))if("tool:template_render".equals(item.path("capability_key").asText()))definition=item.path("id").asLong();
        assertTrue(definition>0);var catalog=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(item->"template_render".equals(item.name())).findFirst().orElseThrow();
        for(var item:successData(get("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId="+definition)))if(catalog.publishedVersionKey().equals(item.path("version_key").asText())){version=item.path("id").asLong();versionKey=item.path("version_key").asText();}
        assertTrue(version>0);String deployment="composition-template-"+runId;
        var created=send("/api/v1/capabilities/deployments","POST",Map.of("deployment_key",deployment,"tool_version_id",version,"provider_type","PLATFORM","provider_ref","template_render","status","ACTIVE","environment","TEST"),accessToken);
        assertEquals(201,created.statusCode(),created.body());long revision=successData(created).path("revision").asLong();assertTrue(revision>0);
        String workflow="composition-workflow-"+runId;
        var draft=send("/api/v1/builder/workflows","POST",Map.of("workflow_id",workflow,"definition",workflowDefinition(provider,versionKey)),accessToken);assertEquals(200,draft.statusCode(),draft.body());
        var published=requestWithRevision("/api/v1/builder/workflows/publish",Map.of("workflow_id",workflow),successData(draft).path("revision").asText());assertEquals(200,published.statusCode(),published.body());
        var publication=successData(published);long capabilityVersion=publication.path("capability_version_id").asLong();assertTrue(capabilityVersion>0);
        var installed=send("/api/v1/builder/workflows/install","POST",Map.of("workflow_id",workflow,"payload",Map.of("config",Map.of(),"bindings",Map.of("capabilities",List.of(Map.of("slot","render_slot","capability",Map.of("type","TOOL","capability_id",Long.toString(definition),"version_id",Long.toString(version)),"tool_deployment_id",deployment,"tool_deployment_revision",revision)),"workflows",List.of(),"agents",List.of()))),accessToken);
        assertEquals(200,installed.statusCode(),installed.body());String instance=successData(installed).path("instance_id").asText();assertFalse(instance.isBlank());
        JsonNode actual=null;for(var item:successData(get("/api/v1/builder/workflows/instances")).path("items"))if(instance.equals(item.path("instance_id").asText()))actual=item;
        assertNotNull(actual);assertTrue(actual.path("id").asLong()>0);
        var detail=successData(get("/api/v1/capabilities/versions/detail?versionId="+capabilityVersion));
        var mounted=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","WORKFLOW","capability_definition_id",detail.path("definition_id").asText(),"capability_version_id",Long.toString(capabilityVersion),
                "workflow_config",Map.of("workflow_instance_id",actual.path("id").asText(),"workflow_instance_revision",publication.path("definition_version_id").asLong()),"priority",0)),accessToken);
        assertEquals(201,mounted.statusCode(),mounted.body());publishSession();report.put("workflow",publication);report.put("installation",actual);report.put("deployment",successData(created));
    }
    private Map<String,Object> workflowDefinition(RuntimeExecutionCompositionProvider provider,String version) {
        return Map.of("version","1.0","entry","start","meta",Map.of("display_name","Composition Workflow","capability",Map.of("model_invocable",true,"user_invocable",true,"input_schema",Map.of(),"output_schema",Map.of(),"supports_hitl",true,"export_fields",List.of("messages","rendered"),"timeout_ms",300000,"concurrency_safe",true),
                "capability_requirements",List.of(Map.of("slot","render_slot","type","tool","capability_id","tool:template_render","version_constraint",version)),"tools",List.of(Map.of("name","render","requirement","render_slot"))),
                "nodes",List.of(Map.of("id","start","type","start"),Map.of("id","ask","type","human_input","prompt","确认数量后继续","result_to","state.confirm","timeout_seconds",300,"card",Map.of("card_id","composition-confirm","type","confirm","title","确认数量","body","数量为27","actions",List.of(Map.of("id","confirm","label","确认","style","primary")))),
                        Map.of("id","render","type","tool","tool","render","args",Map.of("template",provider.template(),"data",Map.of("quantity","${state.confirm.quantity}")),"result_to","state.rendered"),Map.of("id","done","type","output","message","composition-workflow-done"),Map.of("id","end","type","end")),
                "edges",List.of(Map.of("from","start","to","ask"),Map.of("from","ask","to","render"),Map.of("from","render","to","done"),Map.of("from","done","to","end")));
    }
    private String configureDelegation(RuntimeExecutionCompositionProvider provider) throws Exception {
        var created=send("/api/v1/agents","POST",Map.of("agent_key","composition-child-"+runId,"name","Composition child","system_prompt",provider.childSystem(),"config",Map.of()),accessToken);
        assertEquals(200,created.statusCode(),created.body());String child=successData(created).path("id").asText();assertFalse(child.isBlank());
        assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",child),accessToken).statusCode());revise();
        var updated=send("/api/v1/agents/update","PATCH",Map.of("agent_id",agentId,"payload",Map.of("delegation_config",Map.of("enabled",true,"target_agent_ids",List.of(child)))),accessToken);
        assertEquals(200,updated.statusCode(),updated.body());publishSession();return child;
    }
    private void revise() throws Exception {var response=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,response.statusCode(),response.body());agentId=successData(response).path("id").asText();}
    private void publishSession() throws Exception {
        var published=send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,published.statusCode(),published.body());
        var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();assertFalse(sessionId.isBlank());
    }
    private HttpResponse<String> requestWithRevision(String path,Map<String,Object> body,String revision) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(baseUrl()+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+accessToken).header("Content-Type","application/json").header("If-Match",revision)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();return httpClient.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,Object> workflowReply(JsonNode waiting,String input) {
        var body=chatPayload("确认数量27");body.put("client_message_id",input);
        body.put("hitl_response",Map.of("execution_id",waiting.path("execution_id").asText(),"run_revision",waiting.path("run_revision").asText(),"workflow_input",Map.of("wait",Map.of("action_id",waiting.path("tool_wait").path("action_id").asText(),"generation",waiting.path("tool_wait").path("generation").asText()),
                "execution_ref",waiting.path("workflow_execution_id").asText(),"suspend_ref",waiting.path("suspend_ref").asText(),"action_key",waiting.path("resume_action_key").asText(),"execution_revision",waiting.path("execution_revision").asText(),
                "response",Map.of("schema_id","application/json","canonical_json","{\"action\":\"confirm\",\"quantity\":27}"))));return body;
    }
    private JsonNode waiting(SseCapture capture) throws Exception {
        String event=null;for(String line:capture.raw().split("\\R")){if(line.startsWith("event:"))event=line.substring(6).trim();else if(line.startsWith("data:")&&"hitl.required".equals(event))return objectMapper.readTree(line.substring(5).trim());}
        throw new AssertionError("缺少真实公共等待:"+capture.raw());
    }
    /** 原父身份、实际dependency-child关联和当前Human generation必须同一快照可见，不能猜最新子Run。 */
    private List<Map<String,Object>> delegatedHumanWait(String parent) {
        return queryRows(jdbc,tenantId,"SELECT d.delegation_id,d.child_run_id,cw.action_id,cw.generation FROM agent_runtime_delegations d "
                +"JOIN agent_runtime_execution_runs p ON p.tenant_id=d.tenant_id AND p.run_id=d.parent_run_id AND p.session_id=d.parent_session_id "
                +"AND p.target_kind=d.parent_target_kind AND p.target_id=d.parent_target_id AND p.end_user_id=d.parent_end_user_id AND p.agent_id=d.parent_agent_id "
                +"AND p.input_id=d.parent_causation_input_id AND p.trace_id=d.parent_trace_id AND p.span_id=d.parent_span_id AND p.root_input_id=d.parent_root_input_id "
                +"AND p.caused_by_event_id IS NOT DISTINCT FROM d.parent_caused_by_event_id AND p.identity_ref=d.parent_causation_identity_ref "
                +"JOIN agent_runtime_execution_waits pw ON pw.tenant_id=p.tenant_id AND pw.run_id=p.run_id AND pw.action_id=p.waiting_action_id AND pw.generation=p.waiting_generation "
                +"JOIN agent_runtime_execution_wait_children dc ON dc.tenant_id=pw.tenant_id AND dc.action_id=pw.action_id AND dc.generation=pw.generation AND dc.delegation_id=d.delegation_id AND dc.child_run_id=d.child_run_id "
                +"JOIN agent_runtime_execution_runs c ON c.tenant_id=d.tenant_id AND c.run_id=d.child_run_id AND c.input_id=d.child_input_id "
                +"JOIN agent_runtime_execution_waits cw ON cw.tenant_id=c.tenant_id AND cw.run_id=c.run_id AND cw.action_id=c.waiting_action_id AND cw.generation=c.waiting_generation "
                +"WHERE d.tenant_id=? AND d.parent_run_id=? AND d.parent_session_id=? AND d.parent_agent_id=? AND d.parent_causation_input_id=? AND d.parent_end_user_id=? "
                +"AND p.phase='WAITING' AND p.status='SUSPENDED' AND pw.kind='DELEGATION' AND pw.input_id IS NULL AND pw.consumed_revision IS NULL "
                +"AND c.phase='WAITING' AND c.status='SUSPENDED' AND cw.kind='HUMAN' AND cw.input_id IS NULL AND cw.consumed_revision IS NULL",
                tenantId,parent,sessionId,agentId,runId,endUserId);
    }
    private long attempts(String first,String second){return countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id IN (?,?)",tenantId,first,second);}
    private List<Map<String,Object>> runFacts(String first,String second){return queryRows(jdbc,tenantId,"SELECT run_id,status,phase,revision FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id IN (?,?) ORDER BY run_id",tenantId,first,second);}
    private boolean actualRendered(JsonNode node,String expected) throws Exception {
        if(node.isObject()&&node.path("success").asBoolean()&&expected.equals(node.path("rendered").asText()))return true;
        if(node.isObject()){var fields=node.fields();while(fields.hasNext()){var field=fields.next();if("canonicalJson".equals(field.getKey())&&field.getValue().isTextual()){if(actualRendered(objectMapper.readTree(field.getValue().textValue()),expected))return true;}else if(actualRendered(field.getValue(),expected))return true;}}
        else if(node.isArray())for(var child:node)if(actualRendered(child,expected))return true;return false;
    }
    private void assertNoOldGraph(String run){for(String table:List.of("agent_runtime_runs","agent_runtime_actions","agent_runtime_session_events","nw_runtime_session_model_operations","nw_runtime_session_tool_operations"))assertEquals(0,countRuntime("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",tenantId,run),table);}
    private static void await(String description,BooleanSupplier condition) throws Exception {long until=System.nanoTime()+Duration.ofSeconds(50).toNanos();while(System.nanoTime()<until){if(condition.getAsBoolean())return;Thread.sleep(25);}throw new AssertionError(description);}
    private static void save(Path evidence,String name,SseCapture capture) throws Exception {Files.writeString(evidence.resolve(name+".sse.txt"),capture.raw(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    @FunctionalInterface private interface CompositionCase {void run(RuntimeExecutionCompositionProvider provider,Path evidence,Map<String,Object> report) throws Exception;}
}
