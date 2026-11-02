package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真实机构 HTTP、持久执行与内建时钟；测试数据属于独立 PostgreSQL/Redis。 */
class ChatExecutionClockE2ETest extends ChatExecutionChainE2ETest {
    @Test @Tag("runtime-latency")
    void controlledModelCallsCurrentClockTwiceInOneRun() throws Exception {
        try (var model = new ClockModel(objectMapper)) {
            model.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            exercise(false);
            assertEquals(3, model.calls.get());
            assertEquals(2, model.observedToolReplies.get());
        }
    }

    @Test @Tag("runtime-latency") @Tag("live")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_CLOCK", matches = "1")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    void liveDeepSeekCallsCurrentClockTwiceInOneRun() throws Exception {
        TenantContext.setTenantId(tenantId);
        try {
            var models = applicationContext.getBean(com.skillforge.core.model.PlatformModelCatalogService.class);
            var model = models.create(new com.skillforge.core.model.ModelWrite(null, "Clock live fixture", "LLM", true,
                    "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                    "https://api.deepseek.com", "deepseek-flash", System.getenv("SDK4_LIVE_MODEL_KEY"),
                    8192, 65536, 8192, 0.2, "DISABLED", 120, null, null, null, null, null, "CNY"), "integration-fixture");
            models.publish(model.id(), model.revision(), "integration-fixture");
            models.select(tenantId, new com.skillforge.core.model.ModelSelections(model.id(), null, null));
        } finally { TenantContext.clear(); }
        exercise(true);
    }

    private void exercise(boolean live) throws Exception {
        login();
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
        if (live) {
            // 通用 live 夹具将上限压到 2 次模型和 1 次工具；本场景继承正常平台预算。
            var updated = send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId,
                    "payload", Map.of("execution_config", Map.of())), accessToken);
            assertEquals(200, updated.statusCode(), updated.body());
        }
        var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        long definition = 0;
        for (var item : definitions) if ("tool:get_current_time".equals(item.path("capability_key").asText())) definition = item.path("id").asLong();
        assertTrue(definition > 0);
        var clock = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor())
                .entries().stream().filter(item -> "get_current_time".equals(item.name())).findFirst().orElseThrow();
        var versions = successData(get("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition));
        long version = 0;
        for (var item : versions) if (clock.publishedVersionKey().equals(item.path("version_key").asText())) version = item.path("id").asLong();
        assertTrue(version > 0);
        String key = "clock-current-read-" + runId;
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "PLATFORM", "provider_ref", "get_current_time", "status", "ACTIVE", "environment", "TEST"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "TOOL", "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version),
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        var accepted = send("/api/v1/chat", "POST", chatPayload("请在同一任务先查询当前时间，然后再独立查询一次当前时间，两个调用均使用空参数，最后报告两次真实结果。若任何结果未知，停止且不要重试。"), accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body()); String actualRun = "run:" + runId;
        try {
            var capture = observeSseUntil(sessionId, null, actualRun, Set.of("execution.completed", "error"));
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
            var rows = queryRows(jdbc, tenantId, "SELECT a.action_ordinal,a.kind,a.target_handle,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result,feedback.canonical_json AS feedback FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref LEFT JOIN agent_runtime_execution_materials feedback ON feedback.tenant_id=t.tenant_id AND feedback.reference=t.feedback_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, actualRun);
            var tools = rows.stream().filter(row -> "TOOL".equals(row.get("kind"))).toList();
            assertEquals(2, tools.size(), rows.toString());
            assertNotEquals(tools.get(0).get("attempt_id"), tools.get(1).get("attempt_id"));
            var codec = new AgentExecutionCodec(new CodecLimits(8388608, 100000, 128));
            var first = codec.decodeTool(new TypedPayload(tools.get(0).get("request_schema").toString(), tools.get(0).get("request").toString()));
            var second = codec.decodeTool(new TypedPayload(tools.get(1).get("request_schema").toString(), tools.get(1).get("request").toString()));
            assertEquals(first.arguments(), second.arguments());
            assertEquals("{}", first.arguments().canonicalJson());
            assertEquals("get_current_time", first.binding().toolName());
            assertEquals("get_current_time", first.binding().providerRef());
            assertEquals(first.target(), second.target());
            for (var row : tools) {
                assertEquals("SUCCEEDED", row.get("result_kind")); assertEquals(Boolean.TRUE, row.get("adopted"));
                var outcome = codec.decodeToolOutcome(new TypedPayload(row.get("result_schema").toString(), row.get("result").toString()));
                var actual = assertInstanceOf(com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Succeeded.class, outcome);
                var observation = objectMapper.readTree(actual.observation().canonicalJson());
                assertTrue(observation.path("structuredContent").path("success").asBoolean());
                assertTrue(observation.path("structuredContent").path("epoch_millis").isIntegralNumber());
                assertNotNull(row.get("feedback"));
            }
            assertTrue(((Number) tools.get(0).get("action_ordinal")).longValue() < ((Number) tools.get(1).get("action_ordinal")).longValue());
            assertEquals(1, rows.stream().filter(row -> "MODEL".equals(row.get("kind"))
                    && ((Number)row.get("action_ordinal")).longValue() > ((Number)tools.get(0).get("action_ordinal")).longValue()
                    && ((Number)row.get("action_ordinal")).longValue() < ((Number)tools.get(1).get("action_ordinal")).longValue()).count());
            if (live) assertTrue(rows.stream().filter(row -> "MODEL".equals(row.get("kind"))).allMatch(row -> "deepseek-flash".equals(row.get("target_handle"))));
        } finally {
            var rows = queryRows(jdbc, tenantId, "SELECT a.action_ordinal,a.kind,a.target_handle,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result,feedback.canonical_json AS feedback FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref LEFT JOIN agent_runtime_execution_materials feedback ON feedback.tenant_id=t.tenant_id AND feedback.reference=t.feedback_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, actualRun);
            var state = queryRows(jdbc, tenantId, "SELECT status,phase,revision FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? LIMIT 1", tenantId, actualRun);
            var directory = Path.of(System.getenv().getOrDefault("SKILLFORGE_CLOCK_EVIDENCE_DIR", "build/reports/clock-e2e"));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(runId + ".json"), objectMapper.writeValueAsString(Map.of("run", actualRun, "state", state, "attempts", rows)), StandardCharsets.UTF_8);
        }
    }

    private static final class ClockModel implements AutoCloseable {
        final ObjectMapper json; final HttpServer server; final AtomicInteger calls = new AtomicInteger(), observedToolReplies = new AtomicInteger();
        ClockModel(ObjectMapper json) throws IOException {
            this.json=json; server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/chat/completions",this::respond);
        }
        void start() { server.start(); }
        String endpoint() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        void respond(HttpExchange exchange) throws IOException {
            var request=json.readTree(exchange.getRequestBody()); int ordinal=calls.incrementAndGet();
            if (ordinal>3) throw new IOException("unexpected_model_call");
            if (ordinal>1) {
                int found=0;
                for (var message: request.path("messages")) if ("tool".equals(message.path("role").asText()) && message.path("content").asText().contains("epoch_millis")) found++;
                if (found != ordinal-1) throw new IOException("clock_receipt_missing");
                observedToolReplies.incrementAndGet();
            }
            String function=null;
            for (var tool:request.path("tools")) if (tool.path("function").path("parameters").path("properties").has("timezone")) function=tool.path("function").path("name").asText();
            if (function==null) throw new IOException("clock_tool_missing");
            Object message = ordinal<3 ? Map.of("role","assistant","tool_calls",List.of(Map.of("id","clock-call-"+ordinal,"type","function","function",Map.of("name",function,"arguments","{}")))) : Map.of("role","assistant","content","已读取两次真实时钟结果。");
            String finish=ordinal<3?"tool_calls":"stop", model=request.path("model").asText(); var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);
            if (!request.path("stream").asBoolean()) { send(exchange,"application/json",json.writeValueAsString(Map.of("id","clock-model","object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage))); return; }
            Object delta=ordinal<3 ? Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","clock-call-"+ordinal,"type","function", "function",Map.of("name",function,"arguments","{}")))) : message;
            String first=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",delta))));
            String last=json.writeValueAsString(Map.of("id","clock-model","object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));
            send(exchange,"text/event-stream","data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n");
        }
        void send(HttpExchange exchange,String type,String body) throws IOException { byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type",type); exchange.sendResponseHeaders(200,bytes.length); try(var out=exchange.getResponseBody()){out.write(bytes);} }
        @Override public void close(){ server.stop(0); }
    }
}
