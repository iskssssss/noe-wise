package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 唯一工作流接入及同一安装版本的真实 Runtime 验收。 */
class WorkflowCrossTabBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("workflow-cross-tab-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend(){FRONTEND.close();}

    @Test void publishedWorkflowAppearsAfterManualRefreshAndRuns() throws Exception {
        login();
        try(var model=new WorkflowModel()) {
            model.start();TenantContext.setTenantId(tenantId);
            try {configureTenantModel(model.endpoint());} finally {TenantContext.clear();}
            var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
            assertEquals(200,revised.statusCode(),revised.body());agentId=successData(revised).path("id").asText();
            Path evidence=Path.of("build","runtime-e2e","workflow-cross-tab-"+SnowflakeIds.nextId()).toAbsolutePath();
            Path browser=evidence.resolve("playwright");Files.createDirectories(browser);
            Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path playwright=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name","").toLowerCase().contains("win")?"playwright.cmd":"playwright");
            assertTrue(Files.isRegularFile(playwright));FRONTEND.bindBackend(baseUrl());
            var command=new ProcessBuilder(playwright.toString(),"test","e2e/workflow-cross-tab-real.spec.ts");
            command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
            env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_AGENT_ID",agentId);
            env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
            Process process=command.start();
            try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
                while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
                owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
                String output=Files.readString(evidence.resolve("playwright.log"));
                assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+output);
            }
            var facts=objectMapper.readTree(Files.readString(browser.resolve("workflow-cross-tab.json")));
            var receipt=facts.path("receipt");String instance=receipt.path("instance_id").asText();
            long installation=receipt.path("installation_revision").asLong();String mount=receipt.path("mount_id").asText();
            String definitionVersion=facts.path("definitionVersionId").asText();assertFalse(definitionVersion.isBlank());
            String mode=System.getenv().getOrDefault("E2E_WORKFLOW_CROSS_TAB_MODE","candidate");
            assertEquals(mode,facts.path("mode").asText());
            assertEquals("baseline".equals(mode)?1:0,facts.path("manualRefreshClicks").asInt());assertEquals(1,facts.path("publishPosts").asInt());
            assertEquals(1,facts.path("onboardingPosts").asInt());assertFalse(instance.isBlank());assertTrue(installation>0);assertFalse(mount.isBlank());
            assertEquals(1,countRuntime("SELECT count(*) FROM nw_agent_capability_mounts m JOIN nw_workflow_installation_revisions i ON i.tenant_id=m.tenant_id AND i.instance_id=m.workflow_instance_id AND i.installation_revision=m.workflow_installation_revision WHERE m.tenant_id=CAST(? AS bigint) AND m.agent_id=CAST(? AS bigint) AND m.workflow_instance_id=CAST(? AS bigint) AND m.workflow_installation_revision=? AND i.definition_version_id=CAST(? AS bigint)",tenantId,agentId,instance,installation,definitionVersion));
            var published=send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,published.statusCode(),published.body());
            var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);
            assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
            var accepted=send("/api/v1/chat","POST",chatPayload("运行刚接入的唯一工作流"),accessToken);
            assertEquals(202,accepted.statusCode(),accepted.body());String run=successData(accepted).path("execution_id").asText();assertFalse(run.isBlank());
            try {
                var events=observeSseUntil(sessionId,null,run,Set.of("execution.completed","error"));
                assertTrue(events.events().contains("execution.completed"),events.raw());assertFalse(events.events().contains("error"),events.raw());
                assertTrue(events.raw().contains("onboarding-runtime-finished"));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='WORKFLOW' AND status='SUCCEEDED'",tenantId,run));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_executions e JOIN nw_workflow_installation_revisions i ON i.tenant_id::text=e.tenant_id AND i.instance_id::text=e.execution_unit_id WHERE e.tenant_id=? AND e.run_id=? AND e.state='COMPLETED' AND i.instance_id=CAST(? AS bigint) AND i.installation_revision=? AND i.definition_version_id=CAST(? AS bigint) AND (e.checkpoint_canonical_json::jsonb->'target'->>'instanceId')=? AND (e.checkpoint_canonical_json::jsonb->'target'->>'installationRevision')=? AND (e.checkpoint_canonical_json::jsonb->'target'->>'instanceRevision')=?",tenantId,run,instance,installation,definitionVersion,instance,Long.toString(installation),definitionVersion));
                Files.writeString(evidence.resolve("onboard-runtime.json"),objectMapper.writeValueAsString(Map.of("runId",run,"agentId",agentId,"instanceId",instance,"installationRevision",installation,"definitionVersionId",definitionVersion,"workflowState","COMPLETED","modelCalls",model.calls.get())));
            } finally {
                var state=Map.of("runs",queryRows(jdbc,tenantId,"SELECT status,phase,cancelled FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",tenantId,run),
                        "actions",queryRows(jdbc,tenantId,"SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal",tenantId,run),
                        "attempts",queryRows(jdbc,tenantId,"SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,run),
                        "workflows",queryRows(jdbc,tenantId,"SELECT execution_id,state,execution_unit_id,definition_version FROM agent_runtime_executions WHERE tenant_id=? AND run_id=?",tenantId,run),"modelCalls",model.calls.get());
                Files.writeString(evidence.resolve("onboard-runtime-state.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
        }
    }
    /** 本机合成模型仅选择所挂载工作流，不访问外部模型。 */
    private final class WorkflowModel implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger calls=new AtomicInteger();
        private WorkflowModel() throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",this::respond);
        }
        private void start(){server.start();}
        private String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        @Override public void close(){server.stop(0);}
        private void respond(HttpExchange exchange) throws IOException {
            var request=objectMapper.readTree(exchange.getRequestBody());
            String requestedModel=request.path("model").asText();
            Map<String,Object> message;String finish;
            if(calls.incrementAndGet()==1) {
                com.fasterxml.jackson.databind.JsonNode handles=null;
                for(var tool:request.path("tools"))if("run_workflow".equals(tool.path("function").path("name").asText()))
                    handles=tool.path("function").path("parameters").path("properties").path("handle").path("enum");
                if(handles==null||!handles.isArray()||handles.size()!=1||!handles.get(0).isTextual())throw new IOException("唯一工作流身份缺失");
                var args=objectMapper.writeValueAsString(Map.of("handle",handles.get(0).asText(),"commentary","运行工作流"));
                message=Map.of("role","assistant","tool_calls",List.of(Map.of("id","publish-workflow","type","function","function",Map.of("name","run_workflow","arguments",args))));
                finish="tool_calls";
            } else {message=Map.of("role","assistant","content","onboarding-runtime-finished");finish="stop";}
            byte[] body;
            if(request.path("stream").asBoolean()) {
                var delta=new LinkedHashMap<>(message);
                if(message.containsKey("tool_calls")) {
                    @SuppressWarnings("unchecked") var call=new LinkedHashMap<>((Map<String,Object>)((List<?>)message.get("tool_calls")).getFirst());
                    call.put("index",0);delta.put("tool_calls",List.of(call));
                }
                var first=objectMapper.writeValueAsString(Map.of("id","fixture","object","chat.completion.chunk","model",requestedModel,"choices",List.of(Map.of("index",0,"delta",delta))));
                var last=objectMapper.writeValueAsString(Map.of("id","fixture","object","chat.completion.chunk","model",requestedModel,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18)));
                body=("data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            } else {
                body=objectMapper.writeValueAsBytes(Map.of("id","fixture","object","chat.completion","model",requestedModel,"choices",List.of(Map.of("index",0,"finish_reason",finish,"message",message)),"usage",Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18)));
                exchange.getResponseHeaders().set("Content-Type","application/json");
            }
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        }
    }

}
