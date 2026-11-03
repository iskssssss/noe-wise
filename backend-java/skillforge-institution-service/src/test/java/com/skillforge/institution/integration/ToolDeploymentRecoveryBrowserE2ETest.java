package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离的部署结果未知浏览器与同部署运行时验收。 */
class ToolDeploymentRecoveryBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("tool-deployment-recovery-");
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void lostCreateResponseLeavesOriginalResultUnknownAndRuntimeUsesDeployment() throws Exception {
        if (System.getenv("E2E_TOOL_PROVIDER_BINDING") != null) { providerBindingJourney(); return; }
        login();
        var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
        assertEquals(200,revised.statusCode(),revised.body()); agentId=successData(revised).path("id").asText();
        var definitions=successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        String definition="",version="";
        for(var item:definitions) if("tool:get_current_time".equals(item.path("capability_key").asText())) {definition=item.path("id").asText();version=item.path("default_version_id").asText();}
        assertFalse(definition.isBlank()); assertFalse(version.isBlank());
        Path evidence=Path.of("build","runtime-e2e","tool-deployment-recovery-"+SnowflakeIds.nextId()).toAbsolutePath(),browser=evidence.resolve("playwright"); Files.createDirectories(browser);
        Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin"),cli=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name","").toLowerCase().contains("win")?"playwright.cmd":"playwright");
        FRONTEND.bindBackend(baseUrl()); var command=new ProcessBuilder(cli.toString(),"test","e2e/tool-deployment-recovery-real.spec.ts"); command.directory(admin.toFile()); command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_PASSWORD",PASSWORD);env.remove("E2E_DEPLOYMENT_KEY");env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
        Process process=command.start();try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+Files.readString(evidence.resolve("playwright.log")));}
        var facts=objectMapper.readTree(Files.readString(browser.resolve("browser-receipt.json")));String key=facts.path("key").asText();assertTrue(key.startsWith("tool-"));assertEquals(1,facts.path("posts").asInt());
        String mode=System.getenv().getOrDefault("E2E_TOOL_DEPLOYMENT_MODE","candidate"),responseMode=System.getenv().getOrDefault("E2E_TOOL_DEPLOYMENT_RESPONSE","disconnect");assertEquals(mode,facts.path("mode").asText());assertEquals(responseMode,facts.path("responseMode").asText());if("candidate".equals(mode)){assertTrue(facts.path("receiptAfterReopen").asBoolean());assertEquals(0,facts.path("authRefreshes").asInt());assertFalse(facts.path("requestId").asText().isBlank());}
        var deployment=get("/api/v1/capabilities/deployments/detail?deploymentKey="+key);assertEquals(200,deployment.statusCode(),deployment.body());long revision=successData(deployment).path("revision").asLong();assertTrue(revision>0);if("candidate".equals(mode))assertEquals(successData(deployment).path("id").asText(),facts.path("upstreamReceipt").path("deployment_id").asText());assertEquals(1,countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?",tenantId,key));
        var mount=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","TOOL","capability_definition_id",definition,"capability_version_id",version,"tool_config",Map.of("tool_deployment_id",key,"tool_deployment_revision",revision),"priority",0)),accessToken);assertEquals(201,mount.statusCode(),mount.body());
        assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken).statusCode());var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
        try(var model=new ClockModel(objectMapper)) {model.start();TenantContext.setTenantId(tenantId);try{configureTenantModel(model.endpoint());}finally{TenantContext.clear();}var capture=postSse(chatPayload("请查询当前时间并报告。"));assertEquals(200,capture.statusCode());assertTrue(capture.events().contains("execution.completed"),capture.raw());String run="run:"+runId;assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND status='COMPLETED'",tenantId,run,sessionId));var rows=queryRows(jdbc,tenantId,"SELECT a.kind,t.result_kind,req.canonical_json AS request FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL'",tenantId,run);assertEquals(1,rows.size());assertEquals("SUCCEEDED",rows.getFirst().get("result_kind"));assertTrue(rows.getFirst().get("request").toString().contains(key));Files.writeString(evidence.resolve("runtime.json"),objectMapper.writeValueAsString(Map.of("key",key,"revision",revision,"definition",definition,"version",version,"sessionId",sessionId,"runId",run,"toolSucceeded",true)));}
    }

    private void providerBindingJourney() throws Exception {
        String mode = System.getenv("E2E_TOOL_PROVIDER_BINDING");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        login();
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body());
        agentId = successData(revised).path("id").asText();
        var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        String definition = "", version = "";
        for (var item : definitions) if ("tool:get_current_time".equals(item.path("capability_key").asText())) {
            definition = item.path("id").asText(); version = item.path("default_version_id").asText();
        }
        assertFalse(definition.isBlank()); assertFalse(version.isBlank());
        Path evidence = Path.of("build", "runtime-e2e", "tool-provider-binding-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        if ("candidate".equals(mode)) {
            long before = countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint)", tenantId);
            Map<String, Object> invalid = Map.of("tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "template_render", "environment", "DEVELOPMENT");
            var rejected = send("/api/v1/capabilities/deployments", "POST", invalid, accessToken);
            Files.writeString(evidence.resolve("mismatched-api-rejection.json"), rejected.body());
            assertEquals(422, rejected.statusCode(), rejected.body());
            assertEquals(before, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint)", tenantId));
            var receiptRejected = send("/api/v1/capabilities/deployments/create-with-receipt", "POST", Map.of("request_id", UUID.randomUUID().toString(), "payload", invalid), accessToken);
            assertEquals(200, receiptRejected.statusCode(), receiptRejected.body());
            assertEquals("REJECTED", successData(receiptRejected).path("status").asText());
            assertEquals(before, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint)", tenantId));
            Files.writeString(evidence.resolve("mismatched-receipt-rejection.json"), receiptRejected.body());
        }
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
        FRONTEND.bindBackend(baseUrl());
        var command = new ProcessBuilder(cli.toString(), "test", "e2e/tool-deployment-recovery-real.spec.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
        env.remove("E2E_DEPLOYMENT_KEY"); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("browser-receipt.json")));
        String key = facts.path("key").asText();
        assertFalse(key.isBlank()); assertEquals(mode, facts.path("bindingMode").asText()); assertEquals(1, facts.path("posts").asInt());
        var deployment = get("/api/v1/capabilities/deployments/detail?deploymentKey=" + key);
        assertEquals(200, deployment.statusCode(), deployment.body());
        var detail = successData(deployment); long revision = detail.path("revision").asLong();
        assertEquals("baseline".equals(mode) ? "template_render" : "get_current_time", detail.path("provider_ref").asText());
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?", tenantId, key));
        var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "TOOL", "capability_definition_id", definition, "capability_version_id", version,
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", revision), "priority", 0)), accessToken);
        assertEquals(201, mount.statusCode(), mount.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        try (var model = new ClockModel(objectMapper)) {
            model.start(); TenantContext.setTenantId(tenantId);
            try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            String run = "run:" + runId;
            try {
                boolean completed = false;
                if ("baseline".equals(mode)) {
                    var rejected = send("/api/v1/chat", "POST", chatPayload("请查询当前时间并报告。"), accessToken);
                    Files.writeString(evidence.resolve("chat-rejection.json"), rejected.body());
                    assertEquals(500, rejected.statusCode(), rejected.body());
                    assertEquals("INTERNAL_ERROR", objectMapper.readTree(rejected.body()).path("code").asText());
                    assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?", tenantId, sessionId));
                } else {
                    var capture = postSse(chatPayload("请查询当前时间并报告。"));
                    Files.writeString(evidence.resolve("stream.txt"), capture.raw());
                    assertEquals(200, capture.statusCode());
                    completed = capture.events().contains("execution.completed");
                    assertTrue(completed, capture.raw());
                }
                var tools = queryRows(jdbc, tenantId, "SELECT a.action_id,a.status,t.result_kind,t.adopted,req.canonical_json AS request,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL'", tenantId, run);
                Files.writeString(evidence.resolve("tool-results.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
                if ("baseline".equals(mode)) {
                    assertTrue(tools.stream().noneMatch(row -> "SUCCEEDED".equals(row.get("result_kind"))), tools.toString());
                    assertEquals(0, model.calls.get(), "错误配对在 Runtime 目录准备时即阻断");
                } else {
                    assertTrue(completed);
                    assertEquals(1, tools.size()); assertEquals("SUCCEEDED", tools.getFirst().get("result_kind"));
                    String result = tools.getFirst().get("result").toString();
                    assertTrue(result.contains("epoch_millis"), result); assertTrue(result.contains("Asia/Shanghai"), result);
                    assertEquals(2, model.calls.get());
                    assertTrue(model.verifiedClockResult.get(), "真实时钟结果必须回传下一轮模型");
                }
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writeValueAsString(Map.of("key", key, "bindingMode", mode, "sessionId", sessionId, "runId", run, "modelCalls", model.calls.get(), "toolAttempts", tools.size(), "clockResultReturnedToModel", model.verifiedClockResult.get())));
            } finally {
                var state = Map.of("runs", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run),
                        "actions", queryRows(jdbc, tenantId, "SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal", tenantId, run),
                        "attempts", queryRows(jdbc, tenantId, "SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, run));
                Files.writeString(evidence.resolve("runtime-state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
        }
    }

    private static final class ClockModel implements AutoCloseable {
        final ObjectMapper json; final HttpServer server; final AtomicInteger calls=new AtomicInteger(); final java.util.concurrent.atomic.AtomicBoolean verifiedClockResult=new java.util.concurrent.atomic.AtomicBoolean();
        ClockModel(ObjectMapper json)throws Exception{this.json=json;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/chat/completions",this::respond);}
        void start(){server.start();}String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void respond(com.sun.net.httpserver.HttpExchange exchange)throws java.io.IOException {var request=json.readTree(exchange.getRequestBody());int ordinal=calls.incrementAndGet();if(ordinal>2)throw new java.io.IOException("unexpected_model_call");if(ordinal==2){for(var input:request.path("messages")){if("tool".equals(input.path("role").asText())&&input.path("content").toString().contains("epoch_millis")&&input.path("content").toString().contains("Asia/Shanghai"))verifiedClockResult.set(true);}if(!verifiedClockResult.get())throw new java.io.IOException("clock_result_not_returned_to_model");}String function=null;for(var tool:request.path("tools"))if(tool.path("function").path("parameters").path("properties").has("timezone"))function=tool.path("function").path("name").asText();if(function==null)throw new java.io.IOException("clock_tool_missing");Object message=ordinal==1?Map.of("role","assistant","tool_calls",List.of(Map.of("id","clock-call","type","function","function",Map.of("name",function,"arguments","{}")))):Map.of("role","assistant","content","已读取时钟结果。");String finish=ordinal==1?"tool_calls":"stop",model=request.path("model").asText();var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);String body,type;if(!request.path("stream").asBoolean()){type="application/json";body=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage));}else{type="text/event-stream";Object delta=ordinal==1?Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","clock-call","type","function","function",Map.of("name",function,"arguments","{}")))):message;String first=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",delta))));String last=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));body="data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n";}byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}
        @Override public void close(){server.stop(0);}
    }
}
