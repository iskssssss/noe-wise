package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离浏览器、机构服务和数据库验证原发布回执恢复。 */
@Tag("workflow-publish-browser")
class WorkflowPublishBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("workflow-publish-browser-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Test void browserRecoversOriginalPublicationAfterResponseLoss() throws Exception {
        login();
        var graph = Map.of("version", "1.0", "entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "reply", "type", "output", "message", "测试输出"),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(Map.of("from", "start", "to", "reply"), Map.of("from", "reply", "to", "end")),
                "meta", Map.of("name", "browser-publish", "display_name", "原名称", "version", "1.0.0",
                        "capability", Map.of("model_invocable", true, "user_invocable", true,
                                "input_schema", Map.of("type", "object", "properties", Map.of()),
                                "output_schema", Map.of("type", "object", "properties", Map.of()),
                                "supports_hitl", false, "export_fields", List.of(), "timeout_ms", 30000,
                                "concurrency_safe", false)));
        var created=send("/api/v1/builder/workflows","POST",Map.of("workflow_id","browser-publish","definition",graph),accessToken);
        assertEquals(200,created.statusCode(),created.body());
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("frontend-admin/package.json"))) root = root.getParent();
        assertNotNull(root);
        Path admin = root.resolve("frontend-admin"), evidence = FRONTEND.evidence();
        var command = new ProcessBuilder("node", admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.workflow-publish.config.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_USERNAME", "runtime-e2e-admin");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var result = objectMapper.readTree(Files.readString(evidence.resolve("workflow-publish.json")));
        String requestId = result.path("command").path("request_id").asText();
        assertEquals("SUCCEEDED", result.path("receipt").path("status").asText());
        assertEquals(1L, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_publish_receipts WHERE request_id=?::uuid", requestId));
        assertEquals(1L, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_definition_versions WHERE tenant_id=?", Long.valueOf(tenantId)));
        var savedDraft = httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/builder/workflows/draft?workflowId=browser-publish"))
                .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, savedDraft.statusCode(), savedDraft.body());
        assertEquals(result.path("command").path("definition"), objectMapper.readTree(savedDraft.body()).path("data").path("definition"));
        var savedLayout = httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/builder/workflows/draft/snapshot?workflowId=browser-publish"))
                .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, savedLayout.statusCode(), savedLayout.body());
        assertEquals(result.path("command").path("layout"), objectMapper.readTree(savedLayout.body()).path("data").path("layout"));
        assertEquals(1, result.path("posts").asInt());
        assertEquals(1, result.path("lookups").asInt());
        assertEquals("baseline".equals(System.getenv("E2E_PUBLISH_RECOVERY_MODE")) ? 1 : 0, result.path("manualChecks").asInt());
        exercisePublishedVersion(result.path("receipt").path("publication"), evidence);
    }

    /** 以浏览器回执中同一个发布版本装载工作流并执行实际 Agent Runtime。 */
    private void exercisePublishedVersion(com.fasterxml.jackson.databind.JsonNode publication, Path evidence) throws Exception {
        long definitionVersion=publication.path("definition_version_id").asLong();
        long capabilityVersion=publication.path("capability_version_id").asLong();
        assertTrue(definitionVersion>0);assertTrue(capabilityVersion>0);
        var installed=send("/api/v1/builder/workflows/install","POST",Map.of("workflow_id","browser-publish",
                "payload",Map.of("config",Map.of(),"bindings",Map.of("capabilities",List.of(),"workflows",List.of(),"agents",List.of()))),accessToken);
        assertEquals(200,installed.statusCode(),installed.body());
        String instance=successData(installed).path("instance_id").asText();assertFalse(instance.isBlank());
        com.fasterxml.jackson.databind.JsonNode actual=null;
        for(var item:successData(get("/api/v1/builder/workflows/instances")).path("items"))
            if(instance.equals(item.path("instance_id").asText()))actual=item;
        assertNotNull(actual);String instanceId=actual.path("id").asText();assertFalse(instanceId.isBlank());
        assertEquals(1,countRuntime("SELECT count(*) FROM nw_workflow_instances WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND definition_version_id=?",tenantId,instanceId,definitionVersion));
        var revise=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
        assertEquals(200,revise.statusCode(),revise.body());agentId=successData(revise).path("id").asText();
        var detail=successData(get("/api/v1/capabilities/versions/detail?versionId="+capabilityVersion));
        var mounted=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of(
                "capability_type","WORKFLOW","capability_definition_id",detail.path("definition_id").asText(),
                "capability_version_id",Long.toString(capabilityVersion),"workflow_config",Map.of(
                        "workflow_instance_id",instanceId,"workflow_instance_revision",definitionVersion),"priority",0)),accessToken);
        assertEquals(201,mounted.statusCode(),mounted.body());
        var published=send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken);
        assertEquals(200,published.statusCode(),published.body());
        var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);
        assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
        try(var model=new WorkflowModel()) {
            model.start();TenantContext.setTenantId(tenantId);
            try {configureTenantModel(model.endpoint());} finally {TenantContext.clear();}
            var accepted=send("/api/v1/chat","POST",chatPayload("运行已发布的工作流"),accessToken);
            assertEquals(202,accepted.statusCode(),accepted.body());
            String run=successData(accepted).path("execution_id").asText();assertFalse(run.isBlank());
            try {
                var events=observeSseUntil(sessionId,null,run,java.util.Set.of("execution.completed","error"));
                assertTrue(events.events().contains("execution.completed"),events.raw());
                assertFalse(events.events().contains("error"),events.raw());
                assertTrue(events.raw().contains("workflow-publish-runtime-finished"));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='WORKFLOW' AND status='SUCCEEDED'",tenantId,run));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_executions e JOIN nw_workflow_instances i ON i.tenant_id::text=e.tenant_id AND i.id::text=e.execution_unit_id WHERE e.tenant_id=? AND e.run_id=? AND e.state='COMPLETED' AND i.definition_version_id=?",tenantId,run,definitionVersion));
                Files.writeString(evidence.resolve("workflow-publish-runtime.json"),objectMapper.writeValueAsString(Map.of(
                        "runId",run,"definitionVersionId",Long.toString(definitionVersion),"capabilityVersionId",Long.toString(capabilityVersion),
                        "instanceId",instanceId,"workflowActions",1,"workflowState","COMPLETED","modelCalls",model.calls.get())));
            } finally {
                var state=Map.of("runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase,cancelled FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",tenantId,run),
                        "actions",queryRows(jdbc,tenantId,"SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal",tenantId,run),
                        "attempts",queryRows(jdbc,tenantId,"SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,run),
                        "workflows",queryRows(jdbc,tenantId,"SELECT execution_id,state FROM agent_runtime_executions WHERE tenant_id=? AND run_id=?",tenantId,run),"modelCalls",model.calls.get());
                Files.writeString(evidence.resolve("workflow-publish-runtime-state.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
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
            } else {message=Map.of("role","assistant","content","workflow-publish-runtime-finished");finish="stop";}
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
