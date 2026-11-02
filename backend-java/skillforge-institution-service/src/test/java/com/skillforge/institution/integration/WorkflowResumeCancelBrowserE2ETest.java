package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 对真实人工等待的原回复和续跑取消竞争取证。 */
class WorkflowResumeCancelBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("workflow-resume-cancel-browser-");
    @MockitoSpyBean StateMachineEngine engine;

    @DynamicPropertySource
    static void browserOrigin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll
    static void closeFrontend() { FRONTEND.close(); }

    @Test
    void realReplyThenCancelDuringResume() throws Exception {
        String workflow = "resume-cancel-" + SnowflakeIds.nextId();
        try (var model = new WorkflowHitlModel(objectMapper)) {
            model.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            login();
            var created = send("/api/v1/builder/workflows", "POST", Map.of("workflow_id", workflow, "definition", definition()), accessToken);
            assertEquals(200, created.statusCode(), created.body());

            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path playwright = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            assertTrue(Files.isRegularFile(playwright));
            Path evidence = Path.of("build", "runtime-e2e", "workflow-resume-cancel-" + SnowflakeIds.nextId()).toAbsolutePath();
            Path browser = evidence.resolve("playwright");
            Files.createDirectories(browser);
            Path gate = evidence.resolve("resume-entered-gate");
            org.mockito.Mockito.doAnswer(invocation -> {
                var request = (ControlledWorkflowExecution.Request) invocation.getArgument(0);
                if (!tenantId.equals(request.tenantId()) || request.checkpoint() == null) return invocation.callRealMethod();
                Files.writeString(gate, request.executionId(), StandardCharsets.UTF_8);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline && countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND cancelled", tenantId, request.sessionId()) == 0)
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                if (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND cancelled", tenantId, request.sessionId()) == 0)
                    throw new AssertionError("续跑进入后未观察到原会话取消");
                return invocation.callRealMethod();
            }).when(engine).runControlled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            Path log = evidence.resolve("playwright.log");
            FRONTEND.bindBackend(baseUrl());
            var command = new ProcessBuilder(playwright.toString(), "test", "e2e/workflow-resume-cancel-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(log.toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
            env.put("E2E_USERNAME", "runtime-e2e-admin"); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_WORKFLOW_ID", workflow); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_RESUME_CANCEL_GATE", gate.toString()); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            var process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                boolean finished = false;
                while (System.nanoTime() < deadline) {
                    owned.capture();
                    if (process.waitFor(200, TimeUnit.MILLISECONDS)) { finished = true; break; }
                }
                owned.capture();
                assertTrue(finished, "浏览器验收超时；证据=" + evidence);
                // 断言前保存原始事实，即使关闭未收敛也不重放回复或取消。
                saveState(evidence);
                String output = Files.exists(log) ? Files.readString(log) : "<no Playwright log>";
                String tail = output.length() > 6000 ? output.substring(output.length() - 6000) : output;
                assertEquals(0, process.exitValue(), "浏览器验收失败；证据=" + evidence + "\n" + tail);
            }
            var facts = objectMapper.readTree(Files.readString(browser.resolve("resume-cancel-browser.json")));
            assertEquals(1, facts.path("replyPosts").asInt()); assertEquals(1, facts.path("closePosts").asInt());
            String run = facts.path("runId").asText(), input = facts.path("inputId").asText();
            assertFalse(run.isBlank()); assertFalse(input.isBlank());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND input_id=? AND accepted_revision IS NOT NULL AND continuation_attempt IS NOT NULL", tenantId, run, input));
            assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, run));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits w JOIN agent_runtime_execution_attempts a ON a.tenant_id=w.tenant_id AND a.run_id=w.run_id AND a.action_id=w.action_id AND a.attempt_id=w.continuation_attempt AND a.continuation_generation=w.generation WHERE w.tenant_id=? AND w.run_id=? AND w.input_id=? AND w.accepted_revision IS NOT NULL AND a.result_kind='SUCCEEDED' AND NOT a.adopted", tenantId, run, input));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='CANCELLED' AND phase='FINISHED' AND lease_claim_id IS NULL", tenantId, run));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_executions WHERE tenant_id=? AND run_id=? AND state='CANCELLED' AND lease_claim_id IS NULL", tenantId, run));
        }
    }

    private void saveState(Path evidence) throws Exception {
        String sessions = "SELECT session_id FROM nw_workflow_test_sessions WHERE tenant_id=CAST(? AS bigint)";
        var state = Map.of(
            "tests", queryRows(jdbc, tenantId, "SELECT id,session_id,status FROM nw_workflow_test_sessions WHERE tenant_id=?", Long.parseLong(tenantId)),
            "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase,cancelled,lease_claim_id IS NOT NULL AS leased FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id IN (" + sessions + ")", tenantId, tenantId),
            "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,a.current_attempt FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.session_id IN (" + sessions + ")", tenantId, tenantId),
            "attempts", queryRows(jdbc, tenantId, "SELECT p.run_id,p.action_id,p.attempt_id,p.continuation_generation,p.result_kind,p.adopted FROM agent_runtime_execution_attempts p JOIN agent_runtime_execution_runs r ON r.tenant_id=p.tenant_id AND r.run_id=p.run_id WHERE r.tenant_id=? AND r.session_id IN (" + sessions + ")", tenantId, tenantId),
            "waits", queryRows(jdbc, tenantId, "SELECT w.run_id,w.action_id,w.generation,w.input_id,w.accepted_revision,w.continuation_attempt,w.execution_id,w.suspend_ref,w.action_key,w.execution_revision FROM agent_runtime_execution_waits w JOIN agent_runtime_execution_runs r ON r.tenant_id=w.tenant_id AND r.run_id=w.run_id WHERE r.tenant_id=? AND r.session_id IN (" + sessions + ")", tenantId, tenantId),
            "workflows", queryRows(jdbc, tenantId, "SELECT execution_id,run_id,session_id,state,cancel_request_id IS NOT NULL AS cancellation_requested,lease_claim_id IS NOT NULL AS leased FROM agent_runtime_executions WHERE tenant_id=? AND session_id IN (" + sessions + ")", tenantId, tenantId),
            "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.attempt_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.session_id IN (" + sessions + ")", tenantId, tenantId));
        Files.writeString(evidence.resolve("resume-cancel-state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> definition() {
        return Map.of("version", "1.0", "entry", "start", "meta", Map.of(
                "display_name", "隔离待办回复测试", "capability", Map.of(
                        "model_invocable", true, "user_invocable", true, "input_schema", Map.of(),
                        "output_schema", Map.of(), "supports_hitl", true, "export_fields", List.of(),
                        "timeout_ms", 300000, "concurrency_safe", true)),
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "ask", "type", "human_input", "prompt", "请确认隔离测试", "result_to", "state.confirm",
                                "timeout_seconds", 300, "card", Map.of("card_id", "hitl-reply-fixture", "type", "confirm",
                                        "title", "确认隔离测试", "body", "请确认后继续", "actions", List.of(Map.of(
                                                "id", "confirm", "label", "确认继续", "style", "primary")))),
                        Map.of("id", "done", "type", "output", "message", "隔离工作流已完成"),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(Map.of("from", "start", "to", "ask"), Map.of("from", "ask", "to", "done"),
                        Map.of("from", "done", "to", "end")));
    }

    private static final class WorkflowHitlModel implements AutoCloseable {
        private final ObjectMapper json;
        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();

        private WorkflowHitlModel(ObjectMapper json) throws IOException {
            this.json = json;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::respond);
        }
        private void start() { server.start(); }
        private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }

        private void respond(HttpExchange exchange) throws IOException {
            JsonNode request;
            try (var input = exchange.getRequestBody()) { request = json.readTree(input); }
            Map<String, Object> message;
            String finish;
            if (calls.incrementAndGet() == 1) {
                JsonNode handles = null;
                for (var tool : request.path("tools")) {
                    if ("run_workflow".equals(tool.path("function").path("name").asText()))
                        handles = tool.path("function").path("parameters").path("properties").path("handle").path("enum");
                }
                if (handles == null || !handles.isArray() || handles.size() != 1 || !handles.get(0).isTextual())
                    throw new IOException("隔离模型未收到唯一工作流身份");
                var args = json.writeValueAsString(Map.of("handle", handles.get(0).asText(), "commentary", "等待确认"));
                message = Map.of("role", "assistant", "content", "等待确认", "tool_calls", List.of(Map.of(
                        "id", "hitl-reply-fixture-call", "type", "function", "function", Map.of("name", "run_workflow", "arguments", args))));
                finish = "tool_calls";
            } else {
                message = Map.of("role", "assistant", "content", "隔离测试回复已处理");
                finish = "stop";
            }
            if (request.path("stream").asBoolean()) {
                var delta = new LinkedHashMap<>(message);
                if (message.containsKey("tool_calls")) {
                    @SuppressWarnings("unchecked")
                    var call = new LinkedHashMap<>((Map<String, Object>) ((List<?>) message.get("tool_calls")).getFirst());
                    call.put("index", 0);
                    delta.put("tool_calls", List.of(call));
                }
                String first = json.writeValueAsString(Map.of("id", "hitl-fixture", "object", "chat.completion.chunk",
                        "model", "hitl-fixture", "choices", List.of(Map.of("index", 0, "delta", delta))));
                String last = json.writeValueAsString(Map.of("id", "hitl-fixture", "object", "chat.completion.chunk",
                        "model", "hitl-fixture", "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)),
                        "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
                byte[] body = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            } else {
                byte[] body = json.writeValueAsBytes(Map.of("id", "hitl-fixture", "object", "chat.completion", "model", "hitl-fixture",
                        "choices", List.of(Map.of("index", 0, "finish_reason", finish, "message", message)),
                        "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            }
        }
    }
}
