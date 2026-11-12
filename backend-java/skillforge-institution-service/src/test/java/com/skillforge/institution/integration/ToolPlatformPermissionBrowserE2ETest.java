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

/** 有能力权限但无连接器读取权限时部署并运行平台工具。 */
class ToolPlatformPermissionBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("tool-platform-permission-");
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void platformDeploymentDoesNotRequireConnectorRead() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_TOOL_PLATFORM_PERMISSION", "candidate");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        login();
        String access = "/api/v1/tenant/foundation/access";
        var role = send(access + "/role", "POST", Map.of("code", "tool_limited_" + SnowflakeIds.nextId(),
                "name", "工具部署权限", "data_scope", "all", "permissions",
                List.of("foundation.capabilities.read", "foundation.capabilities.create"), "department_ids", List.of()), accessToken);
        assertEquals(200, role.statusCode(), role.body());
        var member = send(access + "/member", "POST", Map.of("username", "tool-limited", "display_name", "工具部署成员",
                "password", PASSWORD, "role_ids", List.of(successData(role).path("id").asText())), accessToken);
        assertEquals(200, member.statusCode(), member.body());
        var logged = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", fixtureTenantSlug(), "username", "tool-limited", "password", PASSWORD), null);
        assertEquals(200, logged.statusCode(), logged.body());
        String limitedToken = successData(logged).path("access_token").asText();
        assertEquals(403, send("/api/v1/connectors/instances", "GET", null, limitedToken).statusCode());
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
        String definition = "", version = "";
        for (var item : successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"))) {
            if ("tool:get_current_time".equals(item.path("capability_key").asText())) {
                definition = item.path("id").asText(); version = item.path("default_version_id").asText();
            }
        }
        assertFalse(definition.isBlank()); assertFalse(version.isBlank());
        Path evidence = Path.of("build/runtime-e2e/tool-platform-permission-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("TOOL_PLATFORM_PERMISSION_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离 Spring、PostgreSQL、浏览器与 Runtime。本地受控模型；基线以受限成员 API 创建部署作控制，候选由受限成员浏览器创建。管理员挂载发布并调用同一部署。", StandardCharsets.UTF_8);
        FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "e2e/tool-platform-permission-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_LIMITED_TOKEN", limitedToken);
        env.put("E2E_TOOL_VERSION", version); env.put("E2E_TOOL_PLATFORM_PERMISSION", mode);
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL"); env.remove("E2E_TOOL_CLOCK_RESULT"); env.remove("E2E_EXPRESSION_RESULT");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("receipt.json")));
        String key = facts.path("key").asText(); assertTrue(key.startsWith("tool-"));
        var deployment = get("/api/v1/capabilities/deployments/detail?deploymentKey=" + key);
        assertEquals(200, deployment.statusCode(), deployment.body()); long revision = successData(deployment).path("revision").asLong();
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?", tenantId, key));
        var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", definition, "capability_version_id", version,
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", revision), "priority", 0)), accessToken);
        assertEquals(201, mount.statusCode(), mount.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        try (var model = new ClockModel(objectMapper)) {
            model.start(); TenantContext.setTenantId(tenantId); try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            var capture = postSse(chatPayload("请查询当前时间并报告。"));
            Files.writeString(evidence.resolve("chat-sse.txt"), capture.raw());
            assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
            String run = "run:" + runId;
            var rows = queryRows(jdbc, tenantId, "SELECT a.kind,t.result_kind,t.adopted,req.canonical_json AS request,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL'", tenantId, run);
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("key", key, "revision", revision, "run", run, "session", sessionId, "toolActions", rows, "modelRequests", model.requests, "modelCalls", model.calls.get())));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, sessionId));
            assertEquals(1, rows.size()); assertEquals("SUCCEEDED", rows.getFirst().get("result_kind"));
            assertTrue(rows.getFirst().get("request").toString().contains(key));
            assertEquals(2, model.calls.get()); assertTrue(model.verifiedClockResult.get());
        }
    }
    private static final class ClockModel implements AutoCloseable {
        final ObjectMapper json; final HttpServer server; final AtomicInteger calls=new AtomicInteger(); final java.util.concurrent.atomic.AtomicBoolean verifiedClockResult=new java.util.concurrent.atomic.AtomicBoolean(); final java.util.concurrent.atomic.AtomicBoolean verifiedInvalidResult = new java.util.concurrent.atomic.AtomicBoolean(); final List<com.fasterxml.jackson.databind.JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        ClockModel(ObjectMapper json)throws Exception{this.json=json;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/chat/completions",this::respond);}
        void start(){server.start();}String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void respond(com.sun.net.httpserver.HttpExchange exchange)throws java.io.IOException {var request=json.readTree(exchange.getRequestBody()); requests.add(request.deepCopy()); int ordinal=calls.incrementAndGet();if(ordinal>2)throw new java.io.IOException("unexpected_model_call");if(ordinal==2){for(var input:request.path("messages")){if("tool".equals(input.path("role").asText())&&input.path("content").toString().contains("epoch_millis")&&input.path("content").toString().contains("Asia/Shanghai"))verifiedClockResult.set(true);}if (System.getenv("E2E_TOOL_CLOCK_RESULT") != null || System.getenv("E2E_EXPRESSION_RESULT") != null) { for(var input:request.path("messages")) if("tool".equals(input.path("role").asText()) && input.path("content").toString().contains(System.getenv("E2E_EXPRESSION_RESULT") != null ? "不能除以零" : "timezone 必须是有效的 IANA 时区") && input.path("content").toString().contains("false")) verifiedInvalidResult.set(true); if (!verifiedInvalidResult.get()) throw new java.io.IOException("invalid_result_not_returned_to_model"); } else if(!verifiedClockResult.get())throw new java.io.IOException("clock_result_not_returned_to_model");}String function=null;for(var tool:request.path("tools"))if(tool.path("function").path("parameters").path("properties").has(System.getenv("E2E_EXPRESSION_RESULT") != null ? "expression" : "timezone"))function=tool.path("function").path("name").asText();if(function==null)throw new java.io.IOException("clock_tool_missing");Object message=ordinal==1?Map.of("role","assistant","tool_calls",List.of(Map.of("id","clock-call","type","function","function",Map.of("name",function,"arguments",System.getenv("E2E_EXPRESSION_RESULT") != null ? "{\"expression\":\"1/0\"}" : System.getenv("E2E_TOOL_CLOCK_RESULT") == null ? "{}" : "{\"timezone\":\"Invalid/Unknown\"}")))):Map.of("role","assistant","content",System.getenv("E2E_EXPRESSION_RESULT") != null ? "已读取工具结果。" : "已读取时钟结果。");String finish=ordinal==1?"tool_calls":"stop",model=request.path("model").asText();var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);String body,type;if(!request.path("stream").asBoolean()){type="application/json";body=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage));}else{type="text/event-stream";Object delta=ordinal==1?Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","clock-call","type","function","function",Map.of("name",function,"arguments",System.getenv("E2E_EXPRESSION_RESULT") != null ? "{\"expression\":\"1/0\"}" : System.getenv("E2E_TOOL_CLOCK_RESULT") == null ? "{}" : "{\"timezone\":\"Invalid/Unknown\"}")))):message;String first=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",delta))));String last=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));body="data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n";}byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}
        @Override public void close(){server.stop(0);}
    }
}
