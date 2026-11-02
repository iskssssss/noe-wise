package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;

/** 生产Program Handler→真实Node sandbox→HTTP RPC→独立typed TOOL child；只有模型HTTP为确定夹具。 */
@Tag("runtime-program")
@Tag("runtime-latency")
@EnabledIfEnvironmentVariable(named="SKILLFORGE_RUNTIME_PROGRAM_E2E",matches="1")
@EnabledIfEnvironmentVariable(named="CODE_EXECUTION_SMOKE_ENDPOINT",matches=".+")
@EnabledIfEnvironmentVariable(named="CODE_EXECUTION_SMOKE_TOKEN",matches=".{32,}")
class ChatExecutionProgramE2ETest extends ChatExecutionChainE2ETest {
    /** 本候选验证普通同步RPC；覆盖基类专供交互验收的同名Handler替身。 */
    @org.springframework.test.context.bean.override.convention.TestBean(name="templateRenderToolHandler",methodName="ordinaryTemplateFixture")
    com.skillforge.core.capability.connector.TemplateRenderToolHandler programTemplateHandler;
    static com.skillforge.core.capability.connector.TemplateRenderToolHandler ordinaryTemplateFixture() {
        return new com.skillforge.core.capability.connector.TemplateRenderToolHandler();
    }
    private static final String RUNNER=Objects.toString(System.getenv("CODE_EXECUTION_SMOKE_ENDPOINT"),"").replaceAll("/+$","");
    private static final String RUNNER_TOKEN=Objects.toString(System.getenv("CODE_EXECUTION_SMOKE_TOKEN"),"");
    private static final Path ARTIFACTS=Path.of("build","runtime-e2e","typed-program",Long.toUnsignedString(System.nanoTime())).toAbsolutePath().normalize();
    private static final int PREVIEW_PORT=unusedPort();
    @DynamicPropertySource static void programRunner(DynamicPropertyRegistry properties) {
        properties.add("skillforge.code-execution.endpoint",()->RUNNER.isBlank()?"http://127.0.0.1:9":RUNNER);
        properties.add("skillforge.code-execution.service-token",()->RUNNER_TOKEN.isBlank()?"disabled-typed-program-token-0000000000":RUNNER_TOKEN);
        properties.add("skillforge.code-execution.artifact-root",ARTIFACTS::toString);
        properties.add("skillforge.code-execution.ticket-key",()->"typed-program-test-ticket-key-0123456789abcdef");
        properties.add("skillforge.code-execution.platform-origin",()->"http://localhost:"+PREVIEW_PORT);
        properties.add("skillforge.code-execution.download-origin",()->"http://127.0.0.1:"+PREVIEW_PORT);
        properties.add("skillforge.code-execution.preview-origin",()->"http://127.0.0.1:"+PREVIEW_PORT);
        properties.add("skillforge.code-execution.preview-port",()->PREVIEW_PORT);
        properties.add("agent-runtime.worker-lease-duration",()->"210s");
    }
    @Autowired OperationalSettingsService settings;
    @Autowired ExecutionStore executions;
    @Autowired ExecutionBudgetPort budgets;

    @Test @Timeout(300)
    void realSandboxRpcRendersWithIndependentChildAndSharedBudgetWithoutReplay() throws Exception {
        assertEquals(21,Runtime.version().feature());assertTrue(Set.of("http","https").contains(URI.create(RUNNER).getScheme()));
        assertFalse(applicationContext.getBean("templateRenderToolHandler") instanceof com.skillforge.core.capability.connector.PlatformInteractiveToolHandler,
                "程序RPC候选必须使用普通同步模板Handler，不能继承交互专用替身");
        var report=new LinkedHashMap<String,Object>();report.put("outcome","FAILED");report.put("tenant",tenantId);report.put("run","run:"+runId);
        var evidence=Files.createTempDirectory(Files.createDirectories(Path.of("build","reports").toAbsolutePath()),"runtime-program-");
        long policyActor=SnowflakeIds.nextId();jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",policyActor,"typed-program-policy-"+policyActor,"unused");
        var originalSandbox=settings.read("sandbox");var originalBudget=settings.read(ChatBudgetSettings.GROUP);
        Long sandboxRevision=null,budgetRevision=null;
        ProgramModel observed=null;
        try {
            var enabled=new LinkedHashMap<>(originalSandbox.values());enabled.put("enabled",true);sandboxRevision=settings.update("sandbox",originalSandbox.revision(),enabled,policyActor).revision();
            var limited=new LinkedHashMap<>(originalBudget.values());for(var profile:ChatBudgetSettings.Profile.values())limited.put(profile.field(ExecutionBudgetPort.Dimension.TOOLS),4);
            budgetRevision=settings.update(ChatBudgetSettings.GROUP,originalBudget.revision(),limited,policyActor).revision();
            login();mountProgramTools();String parent="run:"+runId;var reference=new RunRef(new TenantId(tenantId),parent);
            try(var provider=new ProgramModel(objectMapper,"program-"+runId)) {
                observed=provider;
                provider.start();TenantContext.runWithTenant(tenantId,()->configureTenantModel(provider.endpoint()));
                var request=chatPayload(provider.prompt());var admitted=send("/api/v1/chat","POST",request,accessToken);
                assertEquals(202,admitted.statusCode(),admitted.body());assertEquals(parent,successData(admitted).path("execution_id").asText());
                var lastRuntimeProbe=new java.util.concurrent.atomic.AtomicLong();
                var completed=observeSseUntil(sessionId,null,parent,Set.of("execution.completed","error"),()->{
                    assertTrue(provider.failures.isEmpty(),"本地Program模型夹具失败："+provider.failures);
                    long now=System.nanoTime();
                    if(now-lastRuntimeProbe.get()<Duration.ofMillis(250).toNanos())return;
                    lastRuntimeProbe.set(now);
                    executions.load(reference).ifPresent(current->{
                        if(current.phase()!=ExecutionStore.Phase.UNCERTAIN && !"FAILED".equals(current.status().name()))return;
                        var facts=new LinkedHashMap<String,Object>();
                        facts.put("phase",current.phase().name());facts.put("status",current.status().name());
                        facts.put("current_action",current.currentAction().map(value->value.actionId()).orElse("absent"));
                        facts.put("actions",executions.actions(reference,0,10).stream().map(action->Map.of(
                                "ordinal",action.ordinal(),"kind",action.kind().name(),"status",action.status().name())).toList());
                        report.put("terminalRuntime",facts);
                        throw new AssertionError("Program RPC父Run已持久失败/不确定，不重放原动作；有界诊断="+facts+"；证据目录="+evidence);
                    });
                });
                Files.writeString(evidence.resolve("completed.sse"),completed.raw(),StandardCharsets.UTF_8);
                assertTrue(completed.events().contains("execution.completed"),completed.raw());assertFalse(completed.events().contains("error"),completed.raw());
                assertTrue(completed.raw().contains(provider.answer()),completed.raw());assertEquals(2,provider.calls.get());assertEquals(1,provider.verified.get());assertTrue(provider.failures.isEmpty(),provider.failures.toString());
                var children=queryRows(jdbc,tenantId,"SELECT parent_action_id,parent_attempt_id,ordinal,child_run_id FROM agent_runtime_execution_program_child_runs WHERE tenant_id=? AND parent_run_id=? ORDER BY ordinal",tenantId,parent);
                assertEquals(3,children.size(),children.toString());var child=children.getFirst();
                String parentAction=child.get("parent_action_id").toString(),parentAttempt=child.get("parent_attempt_id").toString();
                var childRuns=new LinkedHashSet<String>();var childActions=new LinkedHashSet<String>();var childAttempts=new LinkedHashSet<String>();
                var allChildUsage=new ArrayList<Map<String,Object>>();
                var frozenUsage=TenantContext.callWithTenant(tenantId,()->applicationContext.getBean(com.skillforge.core.billing.export.PlatformUsageMapper.class).frozenSource(tenantId,parent,Long.valueOf(agentId)));
                var usageOrigin=TenantContext.callWithTenant(tenantId,()->applicationContext.getBean(com.skillforge.core.foundation.integration.IntegrationProvenanceService.class).resolveRun(tenantId,parent));
                for(int index=0;index<children.size();index++) {
                    var actualChild=children.get(index);String childRun=actualChild.get("child_run_id").toString();
                    assertTrue(childRuns.add(childRun));assertEquals(index+1,((Number)actualChild.get("ordinal")).intValue());
                    assertEquals(parentAction,actualChild.get("parent_action_id"));assertEquals(parentAttempt,actualChild.get("parent_attempt_id"));
                    assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,childRun));
                    assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?",tenantId,childRun));
                    assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,childRun));
                    var actual=queryRows(jdbc,tenantId,"SELECT a.action_id,t.attempt_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.attempt_id=a.selected_attempt JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL' AND a.status='SUCCEEDED' AND t.adopted AND t.result_kind='SUCCEEDED'",tenantId,childRun);
                    assertEquals(1,actual.size());assertTrue(childActions.add(actual.getFirst().get("action_id").toString()));assertTrue(childAttempts.add(actual.getFirst().get("attempt_id").toString()));
                    assertTrue(rendered(objectMapper.readTree(actual.getFirst().get("canonical_json").toString()),provider.rendered(index+1)),"选定原child结果须为该次真实模板值");
                    var childUsage=queryRows(jdbc,tenantId,"SELECT run_id,attempt_id,agent_revision_id,agent_revision,agent_definition_version,origin_kind,actor_ref,responsibility_ref FROM nw_usage_events WHERE tenant_id=CAST(? AS bigint) AND run_id=? LIMIT 20",tenantId,childRun);
                    assertFalse(childUsage.isEmpty(),"每个真实child必须形成自己的用量事实");
                    for(var usage:childUsage) {
                        assertEquals(childRun,usage.get("run_id"));assertEquals(actual.getFirst().get("attempt_id"),usage.get("attempt_id"));
                        assertEquals(frozenUsage.getAgentRevisionId(),usage.get("agent_revision_id"));assertEquals(frozenUsage.getAgentRevision(),usage.get("agent_revision"));
                        assertEquals(frozenUsage.getAgentDefinitionVersion(),usage.get("agent_definition_version"));
                        assertEquals(usageOrigin.originKind(),usage.get("origin_kind"));assertEquals(usageOrigin.actorRef(),usage.get("actor_ref"));assertEquals(usageOrigin.responsibilityRef(),usage.get("responsibility_ref"));
                    }
                    allChildUsage.addAll(childUsage);
                    var member=budgets.snapshot(new RunRef(reference.tenantId(),childRun));
                    assertEquals(reference,member.registration().root());assertEquals(ExecutionBudgetPort.Role.MEMBER,member.registration().role());
                    assertEquals(1,member.runConsumed().tools());assertEquals(0,member.runReserved().tools());
                }
                assertEquals(3,childRuns.size());assertEquals(3,childActions.size());assertEquals(3,childAttempts.size());
                assertFalse(childActions.contains(parentAction));assertFalse(childAttempts.contains(parentAttempt));
                report.put("childUsage",allChildUsage);
                var root=budgets.snapshot(reference);assertEquals(reference,root.registration().root());
                assertEquals(4,root.registration().policy().requireEnforced().root().tools());assertEquals(4,root.consumed().tools());
                assertEquals(1,root.runConsumed().tools());assertEquals(0,root.reserved().tools());assertEquals(0,root.runReserved().tools());
                String task=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(List.of(tenantId,parent,child.get("parent_action_id").toString(),child.get("parent_attempt_id").toString()))));
                var detail=httpClient.send(HttpRequest.newBuilder(URI.create(RUNNER+"/internal/executions/detail?taskId="+task)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+RUNNER_TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,detail.statusCode());var state=objectMapper.readTree(detail.body());assertEquals("completed",state.path("state").asText());assertEquals(0,state.path("exitCode").asInt(-1));
                var allRuns=new ArrayList<String>();allRuns.add(parent);allRuns.addAll(childRuns);
                var attemptsBefore=new LinkedHashMap<String,Long>();
                for(String actualRun:allRuns)attemptsBefore.put(actualRun,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,actualRun));
                var replay=send("/api/v1/chat","POST",request,accessToken);assertEquals(202,replay.statusCode(),replay.body());assertTrue(successData(replay).path("replayed").asBoolean());
                for(String actualRun:allRuns)assertEquals(attemptsBefore.get(actualRun).longValue(),countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,actualRun));
                assertEquals(3,countRuntime("SELECT count(*) FROM agent_runtime_execution_program_child_runs WHERE tenant_id=? AND parent_run_id=?",tenantId,parent));
                assertEquals(2,provider.calls.get());
                report.put("executionOutcome","PASS");report.put("children",children);report.put("sandboxTask",task);report.put("sandboxState",state.path("state").asText());report.put("rootTools",root.consumed().tools());report.put("childTools",3);

            }
        } finally {
            report.put("evidenceDirectory",evidence.toString());
            try {report.put("runtimeFacts",runtimeFacts("run:"+runId));}
            catch(RuntimeException diagnosticFailure) {report.put("runtimeFactsFailure",diagnosticFailure.getClass().getSimpleName());}
            if(observed!=null)report.put("model",observed.snapshot());boolean budgetRestored=budgetRevision==null;
            try {if(budgetRevision!=null)settings.update(ChatBudgetSettings.GROUP,budgetRevision,originalBudget.values(),policyActor);budgetRestored=true;}
            finally {boolean sandboxRestored=sandboxRevision==null;try{if(sandboxRevision!=null)settings.update("sandbox",sandboxRevision,originalSandbox.values(),policyActor);sandboxRestored=true;}
                finally {report.put("cleanup",budgetRestored&&sandboxRestored?"PASS":"FAILED");if(budgetRestored&&sandboxRestored)report.put("outcome",report.getOrDefault("executionOutcome","FAILED"));
                    Files.writeString(evidence.resolve("program-evidence.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardCharsets.UTF_8);}}
        }
    }

    /** 只保存标识、状态与引用，不把请求、响应或授权材料写入失败报告。 */
    private Map<String,Object> runtimeFacts(String parent) {
        var facts=new LinkedHashMap<String,Object>();
        var children=queryRows(jdbc,tenantId,"SELECT parent_run_id,parent_action_id,parent_attempt_id,ordinal,child_run_id FROM agent_runtime_execution_program_child_runs WHERE tenant_id=? AND parent_run_id=? ORDER BY ordinal LIMIT 10",tenantId,parent);
        facts.put("children",children);
        var runs=new ArrayList<String>();runs.add(parent);children.forEach(child->runs.add(child.get("child_run_id").toString()));
        var snapshots=new ArrayList<Map<String,Object>>();
        for(String run:runs) {
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("run",run);
            snapshot.put("state",queryRows(jdbc,tenantId,"SELECT status,phase,current_action_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",tenantId,run));
            snapshot.put("actions",queryRows(jdbc,tenantId,"SELECT action_id,action_ordinal,kind,status,current_attempt,selected_attempt FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal LIMIT 10",tenantId,run));
            snapshot.put("attempts",queryRows(jdbc,tenantId,"SELECT attempt_id,action_id,result_kind,result_ref,uncertainty_ref,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? ORDER BY attempt_id LIMIT 20",tenantId,run));
            snapshots.add(snapshot);
        }
        facts.put("runs",snapshots);return facts;
    }

    private void mountProgramTools() throws Exception {
        var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,revised.statusCode(),revised.body());agentId=successData(revised).path("id").asText();
        for(String name:List.of("run_code","template_render")) {
            var definitions=successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));long definition=0;for(var item:definitions)if(("tool:"+name).equals(item.path("capability_key").asText()))definition=item.path("id").asLong();assertTrue(definition>0);
            String versionKey=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(entry->name.equals(entry.name())).findFirst().orElseThrow().publishedVersionKey();
            var versions=successData(get("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId="+definition));long version=0;for(var item:versions)if(versionKey.equals(item.path("version_key").asText()))version=item.path("id").asLong();assertTrue(version>0);
            String deployment="typed-program-"+name+"-"+runId;var created=send("/api/v1/capabilities/deployments","POST",Map.of("deployment_key",deployment,"tool_version_id",version,"provider_type","PLATFORM","provider_ref",name,"status","ACTIVE","environment","TEST"),accessToken);assertEquals(201,created.statusCode(),created.body());
            var mounted=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","TOOL","capability_definition_id",Long.toString(definition),"capability_version_id",Long.toString(version),"tool_config",Map.of("tool_deployment_id",deployment,"tool_deployment_revision",successData(created).path("revision").asLong()),"priority",0)),accessToken);assertEquals(201,mounted.statusCode(),mounted.body());
        }
        var published=send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,published.statusCode(),published.body());
        var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
    }
    private boolean rendered(JsonNode value,String expected) throws IOException {
        if(value.isObject()&&value.path("success").asBoolean()&&expected.equals(value.path("rendered").asText()))return true;
        if(value.isObject()&&value.has("canonicalJson")&&value.get("canonicalJson").isTextual()&&rendered(objectMapper.readTree(value.get("canonicalJson").asText()),expected))return true;
        for(var child:value)if(rendered(child,expected))return true;return false;
    }
    private static int unusedPort(){try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}catch(IOException failed){throw new IllegalStateException(failed);}}

    private static final class ProgramModel implements AutoCloseable {
        private final ObjectMapper json;private final String key;private final HttpServer server;
        private String submittedCode;
        final AtomicInteger calls=new AtomicInteger(),verified=new AtomicInteger();final List<String> failures=new CopyOnWriteArrayList<>();final List<JsonNode> requests=new CopyOnWriteArrayList<>();
        ProgramModel(ObjectMapper json,String key)throws IOException {this.json=json;this.key=key;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/chat/completions",this::respond);}
        void start(){server.start();}String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}String prompt(){return "使用已授权程序调用模板工具并返回真实检查结果；标记 "+key;}String rendered(int ordinal){return key+"="+(26+ordinal);}String answer(){return "program-real-rpc-completed-"+key;}
        Map<String,Object> snapshot(){return Map.of("calls",calls.get(),"verifiedActualProgramResult",verified.get(),"requests",List.copyOf(requests),"failures",List.copyOf(failures));}
        private void respond(HttpExchange exchange)throws IOException {
            try {
                JsonNode request;try(var body=exchange.getRequestBody()){request=json.readTree(body);}requests.add(request.deepCopy());int ordinal=calls.incrementAndGet();String wire;Map<String,Object> arguments;
                if(ordinal==1) {
                    wire=null;for(var tool:request.path("tools")){var function=tool.path("function");var properties=function.path("parameters").path("properties");if(properties.has("code")&&!properties.has("language")&&!properties.has("mode")){if(wire!=null)throw new IOException("ambiguous_program_tool");wire=function.path("name").asText();}}
                    if(wire==null)throw new IOException("actual_program_tool_missing");
                    String templateName=null;for(var tool:request.path("tools")){var function=tool.path("function");if(function.path("parameters").path("properties").has("template")){if(templateName!=null)throw new IOException("ambiguous_template_tool");templateName=function.path("name").asText();}}
                    if(templateName==null)throw new IOException("actual_template_tool_missing");
                    String code="import {writeFile} from 'node:fs/promises';\nconst results=[];\nfor(let index=0;index<3;index++){\n"
                            +"const quantity=27+index;const actual=await tools.call("+json.writeValueAsString(templateName)+", {template:"+json.writeValueAsString(key+"={{quantity}}")+",data:{quantity}});\n"
                            +"if(actual.structuredContent?.success!==true||actual.structuredContent?.rendered!=="+json.writeValueAsString(key+"=")+"+quantity)throw new Error('actual template result mismatch');\n"
                            +"results.push({kind:'text',format:'plain',text:actual.structuredContent.rendered});}\n"
                            +"await writeFile('/workspace/output/result.json',JSON.stringify({results}));";
                    submittedCode=code;arguments=Map.of("code",code,"output_files",List.of());
                } else if(ordinal==2) {
                    String call=null;for(var message:request.path("messages"))if("assistant".equals(message.path("role").asText()))for(var item:message.path("tool_calls")){
                        var frozen=json.readTree(item.path("function").path("arguments").asText());if(submittedCode.equals(frozen.path("code").asText())){if(call!=null)throw new IOException("program_call_pair_ambiguous");call=item.path("id").asText();}}
                    if(call==null)throw new IOException("program_call_pair_missing");int matches=0;for(var message:request.path("messages"))if("tool".equals(message.path("role").asText())&&call.equals(message.path("tool_call_id").asText())){
                        var result=json.readTree(message.path("content").asText());var structured=result.path("structuredContent").isObject()?result.path("structuredContent"):result;
                        if(!structured.path("success").asBoolean()||structured.path("exit_code").asInt(-1)!=0||!containsResults(structured))throw new IOException("real_sandbox_result_missing");matches++;
                    }
                    if(matches!=1)throw new IOException("program_tool_pair_ambiguous");verified.incrementAndGet();wire=null;arguments=Map.of();
                } else throw new IOException("unexpected_program_model_call");
                var call=new LinkedHashMap<String,Object>();call.put("id",key+(ordinal==1?"-program":"-final"));call.put("type","function");if(wire!=null)call.put("function",Map.of("name",wire,"arguments",json.writeValueAsString(arguments)));
                var message=wire==null?Map.of("role","assistant","content",answer()):Map.of("role","assistant","tool_calls",List.of(call));String finish=wire==null?"stop":"tool_calls";var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);String model=request.path("model").asText("runtime-fixture");String response;
                if(request.path("stream").asBoolean()){if(wire!=null)call.put("index",0);response="data: "+json.writeValueAsString(Map.of("id",key,"object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",message))))+"\n\ndata: "+json.writeValueAsString(Map.of("id",key,"object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage))+"\n\ndata: [DONE]\n\n";send(exchange,"text/event-stream",response);}
                else send(exchange,"application/json",json.writeValueAsString(Map.of("id",key,"object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage)));
            }catch(Exception failed){
                String reason=failed.getMessage();
                // 仅记录本夹具自身封闭错误码，不把模型请求或工具正文复制进失败消息。
                failures.add(failed.getClass().getSimpleName()+":"+(reason!=null&&reason.matches("[a-z_]{1,96}")?reason:"fixture_failure"));
                exchange.sendResponseHeaders(500,-1);
            }finally{exchange.close();}
        }
        private boolean containsResults(JsonNode value){for(int ordinal=1;ordinal<=3;ordinal++)if(resultCount(value,rendered(ordinal))!=1)return false;return true;}
        private int resultCount(JsonNode value,String expected){int count=value.isObject()&&"text".equals(value.path("kind").asText())&&expected.equals(value.path("text").asText())?1:0;for(var child:value)count+=resultCount(child,expected);return count;}
        private void send(HttpExchange exchange,String type,String body)throws IOException {var bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}
        @Override public void close(){server.stop(0);}
    }
}
