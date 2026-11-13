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
class WorkflowToolOptionsBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("workflow-tool-options-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend(){FRONTEND.close();}

    @Test void workflowToolOptionsRemainUsableWhenVersionReadFails() throws Exception {
        login();
        Path evidence=Path.of("build/runtime-e2e/workflow-tool-options-"+SnowflakeIds.nextId()).toAbsolutePath();
        Path browser=evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("WORKFLOW_TOOL_OPTIONS_EVIDENCE="+evidence);
        var definitions=successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        String mode=System.getenv().getOrDefault("E2E_WORKFLOW_TOOL_OPTIONS","baseline");
        assertTrue(List.of("baseline","candidate").contains(mode));
        var tools=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        var versions=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        var deployments=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for(String name:List.of("template_render","get_current_time")) {
            com.fasterxml.jackson.databind.JsonNode found=null;
            for(var item:definitions) if(("tool:"+name).equals(item.path("capability_key").asText())) found=item;
            assertNotNull(found); String version=found.path("default_version_id").asText(); assertFalse(version.isBlank());
            var detail=successData(get("/api/v1/capabilities/versions/detail?versionId="+version));
            tools.put(name,found); versions.put(name,detail);
            String key="workflow-option-"+name+"-"+SnowflakeIds.nextId();
            var created=send("/api/v1/capabilities/deployments","POST",Map.of("deployment_key",key,"tool_version_id",version,
                    "provider_type","PLATFORM","provider_ref",name,"status","ACTIVE","environment","DEVELOPMENT"),accessToken);
            assertEquals(201,created.statusCode(),created.body());deployments.put(name,successData(created));
        }
        var meta=Map.of("name","workflow-tool-options","display_name","工具依赖验收","version","1.0.0",
                "capability",Map.of("model_invocable",true,"user_invocable",true,"input_schema",Map.of("type","object","properties",Map.of()),
                    "output_schema",Map.of("type","object","properties",Map.of()),"supports_hitl",false,"export_fields",List.of(),"timeout_ms",30000,"concurrency_safe",true),
                "capability_requirements",List.of(
                    Map.of("slot","render_slot","type","tool","capability_id","tool:template_render","version_constraint","*","label","渲染工具","required",true),
                    Map.of("slot","clock_slot","type","tool","capability_id","tool:get_current_time","version_constraint","*","label","时间工具","required",true)),
                "tools",List.of(Map.of("name","render","requirement","render_slot")));
        var graph=Map.of("version","1.0","entry","start","meta",meta,
                "nodes",List.of(Map.of("id","start","type","start"),
                    Map.of("id","render","type","tool","tool","render","args",Map.of("template","result {{value}}","data",Map.of("value","实际工具输出")),"result_to","state.rendered"),
                    Map.of("id","answer","type","output","message","工具依赖工作流已执行"),Map.of("id","end","type","end")),
                "edges",List.of(Map.of("from","start","to","render"),Map.of("from","render","to","answer"),Map.of("from","answer","to","end")));
        var draft=send("/api/v1/builder/workflows","POST",Map.of("workflow_id","workflow-tool-options","definition",graph),accessToken);
        assertEquals(200,draft.statusCode(),draft.body());
        var request=HttpRequest.newBuilder(URI.create(baseUrl()+"/api/v1/builder/workflows/publish"))
                .header("Authorization","Bearer "+accessToken).header("Content-Type","application/json")
                .header("If-Match",successData(draft).path("revision").asText())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("workflow_id","workflow-tool-options")))).build();
        var published=httpClient.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,published.statusCode(),published.body());
        var publication=successData(published);
        Files.writeString(evidence.resolve("publication.json"),published.body());
        Files.writeString(evidence.resolve("tools.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("definitions",tools,"deployments",deployments)));
        FRONTEND.bindBackend(baseUrl()); Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command=new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test","e2e/workflow-tool-options-real.spec.ts","--output",evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env=command.environment(); env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
        env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_PASSWORD",PASSWORD);
        env.put("E2E_WORKFLOW_TOOL_OPTIONS",mode);
        for(String name:List.of("template_render","get_current_time")) {
            String prefix="template_render".equals(name)?"E2E_RENDER_":"E2E_CLOCK_";
            env.put(prefix+"LABEL",versions.get(name).path("name").asText()+" · "+versions.get(name).path("version_key").asText());
            env.put(prefix+"VERSION",tools.get(name).path("default_version_id").asText());
            env.put(prefix+"DEPLOYMENT",deployments.get(name).path("deployment_key").asText());
        }
        env.put("E2E_RENDER_DEFINITION",tools.get("template_render").path("id").asText());
        env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
        Process process=command.start();
        try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
            while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
            owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
            assertEquals(0,process.exitValue(),evidence+"\n"+Files.readString(evidence.resolve("playwright.log")));
        }
        var bindings=new java.util.ArrayList<Map<String,Object>>();
        for(var entry:Map.of("render_slot","template_render","clock_slot","get_current_time").entrySet()) {
            var def=tools.get(entry.getValue());var deployment=deployments.get(entry.getValue());
            bindings.add(Map.of("slot",entry.getKey(),"capability",Map.of("type","TOOL","capability_id",def.path("id").asText(),"version_id",def.path("default_version_id").asText()),
                    "tool_deployment_id",deployment.path("deployment_key").asText(),"tool_deployment_revision",deployment.path("revision").asLong()));
        }
        String instance;
        if ("baseline".equals(mode)) {
            var installed=send("/api/v1/builder/workflows/install-with-receipt","POST",Map.of("request_id",java.util.UUID.randomUUID().toString(),"workflow_id","workflow-tool-options",
                    "payload",Map.of("config",Map.of(),"custom_prompt","","bindings",Map.of("capabilities",bindings,"workflows",List.of(),"agents",List.of()))),accessToken);
            Files.writeString(evidence.resolve("install-control.json"),installed.body());assertEquals(200,installed.statusCode(),installed.body());
            instance=successData(installed).path("instance_id").asText();
        } else {
            var captured=objectMapper.readTree(Files.readString(browser.resolve("install-receipt.json")));
            var commandBody=captured.path("command"); var receipt=captured.path("receipt");
            assertEquals("workflow-tool-options",receipt.path("workflow_id").asText());
            assertEquals(commandBody.path("request_id").asText(),receipt.path("request_id").asText());
            assertFalse(receipt.path("request_id").asText().isBlank());
            assertTrue(receipt.path("installation_revision").asLong()>0);
            var chosen=commandBody.path("payload").path("bindings").path("capabilities");assertEquals(2,chosen.size());
            for(var expected:bindings) {
                var slot=expected.get("slot").toString();
                com.fasterxml.jackson.databind.JsonNode selected=null;
                for(var item:chosen) if(slot.equals(item.path("slot").asText())) selected=item;
                assertNotNull(selected); var cap=(Map<?,?>)expected.get("capability");
                assertEquals(cap.get("capability_id").toString(),selected.path("capability").path("capability_id").asText());
                assertEquals(cap.get("version_id").toString(),selected.path("capability").path("version_id").asText());
                assertEquals(expected.get("tool_deployment_id").toString(),selected.path("tool_deployment_id").asText());
                assertEquals(((Number)expected.get("tool_deployment_revision")).longValue(),selected.path("tool_deployment_revision").asLong());
            }
            instance=receipt.path("instance_id").asText();
        }
        assertFalse(instance.isBlank());
        com.fasterxml.jackson.databind.JsonNode actual=null;for(var row:successData(get("/api/v1/builder/workflows/instances")).path("items")) if(instance.equals(row.path("instance_id").asText()))actual=row;
        assertNotNull(actual);String version=publication.path("capability_version_id").asText();
        var detail=successData(get("/api/v1/capabilities/versions/detail?versionId="+version));
        assertEquals(200,send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken).statusCode());
        var mount=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","WORKFLOW","capability_definition_id",detail.path("definition_id").asText(),"capability_version_id",version,
                "workflow_config",Map.of("workflow_instance_id",actual.path("id").asText(),"workflow_instance_revision",publication.path("definition_version_id").asText()),"priority",0)),accessToken);
        Files.writeString(evidence.resolve("mount.json"),mount.body());assertEquals(201,mount.statusCode(),mount.body());
        try(var model=new WorkflowModel()) {
            model.start();TenantContext.runWithTenant(tenantId,()->configureTenantModel(model.endpoint()));
            assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken).statusCode());
            var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);
            assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
            var accepted=send("/api/v1/chat","POST",chatPayload("运行工具依赖工作流"),accessToken);assertEquals(202,accepted.statusCode(),accepted.body());
            String run=successData(accepted).path("execution_id").asText();var events=observeSseUntil(sessionId,null,run,Set.of("execution.completed","error"));
            Files.writeString(evidence.resolve("runtime-sse.txt"),events.raw());
            assertTrue(events.events().contains("execution.completed"),events.raw());
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='WORKFLOW' AND status='SUCCEEDED'",tenantId,run));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_executions WHERE tenant_id=? AND run_id=? AND state='COMPLETED'",tenantId,run));
            var toolResults=queryRows(jdbc,tenantId,"SELECT m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref WHERE a.tenant_id=? AND a.kind='TOOL' AND a.run_id LIKE 'workflow-node-run:%'",tenantId);
            Files.writeString(evidence.resolve("tool-results.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolResults));
            assertEquals(1,toolResults.size());
            assertTrue(actualRendered(objectMapper.readTree(toolResults.getFirst().get("canonical_json").toString())));
            Files.writeString(evidence.resolve("runtime.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("run",run,"instance",instance,"modelCalls",model.calls.get(),
                "actions",queryRows(jdbc,tenantId,"SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal",tenantId,run),
                "workflows",queryRows(jdbc,tenantId,"SELECT execution_id,state,execution_unit_id FROM agent_runtime_executions WHERE tenant_id=? AND run_id=?",tenantId,run))));
        }
    }
    private boolean actualRendered(com.fasterxml.jackson.databind.JsonNode node) throws Exception {
        if (node.isObject() && node.path("success").asBoolean() && "result 实际工具输出".equals(node.path("rendered").asText())) return true;
        if (node.isObject()) {
            var fields=node.fields();
            while (fields.hasNext()) {
                var field=fields.next();
                if ("canonicalJson".equals(field.getKey()) && field.getValue().isTextual()) {
                    if (actualRendered(objectMapper.readTree(field.getValue().textValue()))) return true;
                } else if (actualRendered(field.getValue())) return true;
            }
        } else if (node.isArray()) for (var child:node) if (actualRendered(child)) return true;
        return false;
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
