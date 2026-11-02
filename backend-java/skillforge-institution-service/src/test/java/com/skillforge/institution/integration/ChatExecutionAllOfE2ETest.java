package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真实应用装配和隔离数据；工具与受控模型均只监听回环地址。 */
class ChatExecutionAllOfE2ETest extends ChatExecutionChainE2ETest {
    private static final Map<String, Object> INPUT = Map.of(
            "type", "object", "properties", Map.of("id", Map.of("type", "string", "minLength", 2)),
            "required", List.of("id"), "additionalProperties", false,
            "allOf", List.of(Map.of("type", "object"), Map.of("required", List.of("id"))));
    private static final Map<String, Object> OUTPUT = Map.of(
            "type", "object", "properties", Map.of("answer", Map.of("type", "string")),
            "required", List.of("answer"), "additionalProperties", false,
            "allOf", List.of(Map.of("type", "object"), Map.of("required", List.of("answer"))));
    private static final Map<String, Object> OUTPUT_BRANCH_REJECT = Map.of(
            "type", "object", "properties", Map.of("answer", Map.of("type", "string")),
            "required", List.of("answer"), "additionalProperties", false,
            "allOf", List.of(Map.of("properties", Map.of("answer", Map.of("minLength", 20)))));

    @Test @Tag("runtime-latency")
    void controlledAllOfRejectsInvalidBeforeDispatchAndAdoptsActualResult() throws Exception {
        try (var fixture = new Fixture(objectMapper, true, false)) {
            fixture.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(fixture.modelEndpoint()); } finally { TenantContext.clear(); }
            exercise(fixture, false);
            assertEquals(3, fixture.modelCalls.get());
            assertTrue(fixture.invalidObservedBeforeDispatch);
        }
    }

    /** 外部成功回执保留；仅 allOf 分支不满足时不得把结果回注模型或重派工具。 */
    @Test @Tag("runtime-latency")
    void controlledAllOfOutputBranchRejectsActualResultWithoutRedispatch() throws Exception {
        try (var fixture = new Fixture(objectMapper, true, true)) {
            fixture.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(fixture.modelEndpoint()); } finally { TenantContext.clear(); }
            exercise(fixture, false);
            assertEquals(2, fixture.modelCalls.get());
            assertTrue(fixture.invalidObservedBeforeDispatch);
        }
    }

    /** 独立显式开关：仅 deepseek-flash，任何未知结果不重发。 */
    @Test @Tag("runtime-latency") @Tag("live")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_ALLOF", matches = "1")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    void liveDeepSeekAllOfWithLocalReadOnlyMcp() throws Exception {
        try (var fixture = new Fixture(objectMapper, false, false)) {
            fixture.start();
            exercise(fixture, true);
        }
    }

    private void exercise(Fixture fixture, boolean live) throws Exception {
        login();
        var revise = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revise.statusCode()); agentId = successData(revise).path("id").asText();
        var connector = send("/api/v1/connectors/instances", "POST", Map.of("executor_type", "mcp", "name", "allOf fixture",
                "base_url", fixture.mcpEndpoint(), "auth_type", "none", "headers", Map.of("MCP-Protocol-Version", RuntimeExecutionMcpWaitServer.VERSION)), accessToken);
        assertEquals(200, connector.statusCode(), connector.body());
        String providerRef = successData(connector).path("instance_id").asText();
        String key = "allof-" + SnowflakeIds.nextId();
        var descriptor = Map.of("input_schema", INPUT, "output_schema", fixture.output(), "effect", "READ_ONLY", "idempotent", true,
                "timeout_ms", 30000, "tool_name", "fixture_lookup", "model_invocable", true, "user_invocable", false, "confirmation_policy", "NEVER");
        var definitionResponse = send("/api/v1/capabilities/definitions", "POST", Map.of("capability_type", "TOOL", "capability_key", key,
                "name", "allOf lookup", "description", "只读获取固定验收结果"), accessToken);
        assertEquals(201, definitionResponse.statusCode(), definitionResponse.body());
        long definition = successData(definitionResponse).path("id").asLong();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(descriptor)));
        var draft = send("/api/v1/capabilities/definitions/versions", "POST", Map.of("definition_id", definition,
                "payload", Map.of("version_key", "v1", "name", "allOf lookup", "description", "输入 id 查询固定答案",
                        "content_hash", hash, "descriptor", descriptor)), accessToken);
        assertEquals(201, draft.statusCode(), draft.body()); long version = successData(draft).path("id").asLong();
        var validated = send("/api/v1/capabilities/definitions/versions/validate", "POST", Map.of("definition_id", definition, "version_id", version), accessToken);
        assertTrue(successData(validated).path("valid").asBoolean(), validated.body());
        var publish = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/capabilities/definitions/versions/publish"))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .header("If-Match", successData(validated).path("definition_revision").asText())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("definition_id", definition, "version_id", version)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, publish.statusCode(), publish.body());
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "MCP", "provider_ref", providerRef, "environment", "TEST", "status", "ACTIVE"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "tool_config", Map.of("tool_deployment_id", key,
                        "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken);
        assertEquals(201, mount.statusCode(), mount.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        sessionId = successData(session).path("session_id").asText();
        var accepted = send("/api/v1/chat", "POST", chatPayload("请调用只读工具查询 id=ok 的固定答案，然后报告结果。"), accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        String actualRun = "run:" + runId;
        try {
        var capture = observeSseUntil(sessionId, null, actualRun, Set.of("execution.completed", "error"));
        if (fixture.invalidOutput) {
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertTrue(capture.raw().contains("\"reason\":\"tool_output_invalid\""), capture.raw());
            assertEquals(1, fixture.toolCalls.get());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='FAILED' AND phase='FINISHED'", tenantId, actualRun));
            var failureRows = queryRows(jdbc, tenantId, "SELECT t.result_kind,t.adopted,t.feedback_ref,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL'", tenantId, actualRun);
            assertEquals(1, failureRows.size()); var failure = failureRows.getFirst();
            assertEquals("SUCCEEDED", failure.get("result_kind")); assertEquals(Boolean.TRUE, failure.get("adopted")); assertNull(failure.get("feedback_ref"));
            var outcome = new AgentExecutionCodec(new CodecLimits(1048576,100000,100)).decodeToolOutcome(new TypedPayload("skillforge.runtime.execution.portal-outcome.v1",failure.get("canonical_json").toString()));
            assertInstanceOf(com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Succeeded.class, outcome);
            assertTrue(failure.get("canonical_json").toString().contains("fixture-answer"));
            return;
        }
        assertTrue(capture.events().contains("execution.completed"), capture.raw());
        assertEquals(1, fixture.toolCalls.get(), "只允许一次实际本地只读工具调用");
        assertEquals("ok", fixture.lastId);
        var modelRows = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' ORDER BY a.action_ordinal LIMIT 20", tenantId, actualRun);
        var modelCodec = new AgentExecutionCodec(new CodecLimits(1048576, 100000, 100));
        var modelRequest = ModelRequestTestMaterials.decode(modelCodec, new TypedPayload(modelRows.getFirst().get("schema_id").toString(), modelRows.getFirst().get("canonical_json").toString()), jdbc, tenantId);
        var exposed = modelRequest.request().tools().stream().filter(tool -> tool.inputSchema().canonicalJson().contains("allOf")).toList();
        assertEquals(1, exposed.size());
        assertEquals(objectMapper.valueToTree(INPUT), objectMapper.readTree(exposed.getFirst().inputSchema().canonicalJson()));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts t JOIN agent_runtime_execution_actions a ON a.tenant_id=t.tenant_id AND a.action_id=t.action_id WHERE t.tenant_id=? AND t.run_id=? AND a.kind='TOOL' AND t.result_kind='SUCCEEDED' AND t.adopted", tenantId, actualRun));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL'", tenantId, actualRun));
        var rows = queryRows(jdbc, tenantId, "SELECT req.schema_id,req.canonical_json,feedback.canonical_json AS feedback FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref JOIN agent_runtime_execution_materials feedback ON feedback.tenant_id=t.tenant_id AND feedback.reference=t.feedback_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL'", tenantId, actualRun);
        assertEquals(1, rows.size());
        var codec = new AgentExecutionCodec(new CodecLimits(1048576, 100000, 100));
        var request = codec.decodeTool(new TypedPayload(rows.getFirst().get("schema_id").toString(), rows.getFirst().get("canonical_json").toString()));
        assertEquals(objectMapper.valueToTree(INPUT), objectMapper.readTree(request.target().inputSchema().canonicalJson()));
        assertEquals(objectMapper.valueToTree(OUTPUT), objectMapper.readTree(request.target().outputSchema().canonicalJson()));
        assertEquals("fixture-answer", objectMapper.readTree(rows.getFirst().get("feedback").toString()).path("structuredContent").path("answer").asText());
        assertTrue(capture.raw().contains("fixture-answer"));
        if (live) assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND target_handle='deepseek-flash'", tenantId, actualRun) >= 1);
        } finally {
            // 测试容器会销毁；结束前保留本次原 Run、Attempt 和引用材料，UNKNOWN 不重放。
            var facts = queryRows(jdbc, tenantId, "SELECT a.action_ordinal,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,result.schema_id AS result_schema,result.canonical_json AS result,feedback.canonical_json AS feedback FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref LEFT JOIN agent_runtime_execution_materials result ON result.tenant_id=t.tenant_id AND result.reference=t.result_ref LEFT JOIN agent_runtime_execution_materials feedback ON feedback.tenant_id=t.tenant_id AND feedback.reference=t.feedback_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, actualRun);
            var state = queryRows(jdbc, tenantId, "SELECT status,phase,revision FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? LIMIT 1", tenantId, actualRun);
            var directory = Path.of(System.getenv().getOrDefault("SKILLFORGE_ALLOF_EVIDENCE_DIR", "build/reports/allof-e2e"));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(runId + ".json"), objectMapper.writeValueAsString(Map.of("run",actualRun,"state",state,"attempts",facts,"toolCalls",fixture.toolCalls.get())), StandardCharsets.UTF_8);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json; final HttpServer server; final boolean controlled, invalidOutput;
        final AtomicInteger modelCalls = new AtomicInteger(), toolCalls = new AtomicInteger();
        volatile boolean invalidObservedBeforeDispatch; volatile String lastId;
        Fixture(ObjectMapper json, boolean controlled, boolean invalidOutput) throws IOException {
            this.json=json; this.controlled=controlled; this.invalidOutput=invalidOutput; server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/mcp", this::mcp); server.createContext("/chat/completions", this::model);
        }
        void start() { server.start(); }
        String modelEndpoint() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        String mcpEndpoint() { return modelEndpoint()+"/mcp"; }
        Map<String,Object> output() { return invalidOutput ? OUTPUT_BRANCH_REJECT : OUTPUT; }
        void mcp(HttpExchange exchange) throws IOException {
            var request=json.readTree(exchange.getRequestBody()); var method=request.path("method").asText();
            if ("notifications/initialized".equals(method)) { exchange.sendResponseHeaders(202,-1); exchange.close(); return; }
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion",RuntimeExecutionMcpWaitServer.VERSION,"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","allof-fixture","version","1"));
                case "tools/list" -> Map.of("tools",List.of(Map.of("name","fixture_lookup","description","查询固定答案","inputSchema",INPUT,"outputSchema",output(),"annotations",Map.of("readOnlyHint",true,"idempotentHint",true))));
                case "tools/call" -> {
                    toolCalls.incrementAndGet(); lastId=request.path("params").path("arguments").path("id").asText();
                    yield Map.of("resultType","complete","content",List.of(),"isError",false,"structuredContent",Map.of("answer","fixture-answer"));
                }
                default -> throw new IOException("unexpected_mcp_method");
            };
            reply(exchange,"application/json",json.writeValueAsString(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result)));
        }
        void model(HttpExchange exchange) throws IOException {
            if (!controlled) throw new IOException("live_model_must_not_use_fixture");
            var request=json.readTree(exchange.getRequestBody()); int ordinal=modelCalls.incrementAndGet();
            String function=null;
            for (var tool : request.path("tools")) if (tool.path("function").path("parameters").path("properties").has("id")) function=tool.path("function").path("name").asText();
            if (function==null || ordinal>3) throw new IOException("model_fixture_unexpected_request");
            if (ordinal==2) invalidObservedBeforeDispatch=toolCalls.get()==0 && request.path("messages").toString().contains("tool_arguments_invalid");
            var message=ordinal<3 ? Map.of("role","assistant","tool_calls",List.of(Map.of("id","allof-call-"+ordinal,"type","function","function",Map.of("name",function,"arguments",ordinal==1?"{}":"{\"id\":\"ok\"}"))))
                    : Map.of("role","assistant","content","fixture-answer");
            String finish=ordinal<3?"tool_calls":"stop"; String model=request.path("model").asText();
            var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);
            if (!request.path("stream").asBoolean()) { reply(exchange,"application/json",json.writeValueAsString(Map.of("id","fixture","object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage))); return; }
            var delta=ordinal<3 ? Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","allof-call-"+ordinal,"type","function","function",Map.of("name",function,"arguments",ordinal==1?"{}":"{\"id\":\"ok\"}")))) : message;
            String first=json.writeValueAsString(Map.of("id","fixture","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",delta))));
            String last=json.writeValueAsString(Map.of("id","fixture","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));
            reply(exchange,"text/event-stream","data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n");
        }
        void reply(HttpExchange exchange,String type,String body) throws IOException { byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type",type); exchange.sendResponseHeaders(200,bytes.length); try(var out=exchange.getResponseBody()){out.write(bytes);} }
        @Override public void close() { server.stop(0); }
    }
}
