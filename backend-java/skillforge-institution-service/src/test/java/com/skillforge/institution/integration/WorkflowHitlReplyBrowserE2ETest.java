package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 测试会话直接启动工作流；人工等待、回复受理与浏览器均走真实链路。 */
class WorkflowHitlReplyBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("workflow-hitl-reply-browser-");
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.foundation.persistence.TenantMemberMapper receiptMembers;
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.foundation.persistence.TenantMapper receiptTenants;
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.billing.persistence.PlanMapper receiptPlans;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.security.crypto.password.PasswordEncoder receiptPasswords;

    @DynamicPropertySource
    static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll
    static void closeFrontend() {
        FRONTEND.close();
    }

    @Test
    void realWorkflowReplyAfterResponseInterruption() throws Exception {
        String workflow = "hitl-reply-" + SnowflakeIds.nextId();
        try (var model = new WorkflowHitlModel(objectMapper)) {
            model.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            login();
            var created = send("/api/v1/builder/workflows", "POST",
                    Map.of("workflow_id", workflow, "definition", definition()), accessToken);
            assertEquals(200, created.statusCode(), created.body());
            assertEquals(workflow, successData(created).path("workflow_id").asText());

            Path root = KnowledgeBrowserServer.repoRoot();
            Path admin = root.resolve("frontend-admin");
            Path playwright = admin.resolve("node_modules/.bin")
                    .resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            assertTrue(Files.isRegularFile(playwright));
            Path evidence = Path.of("build", "runtime-e2e", "workflow-hitl-reply-" + SnowflakeIds.nextId()).toAbsolutePath();
            Path browser = evidence.resolve("playwright");
            Files.createDirectories(browser);
            Path log = evidence.resolve("playwright.log");
            FRONTEND.bindBackend(baseUrl());
            var command = new ProcessBuilder(playwright.toString(), "test", "e2e/workflow-hitl-reply-real.spec.ts");
            command.directory(admin.toFile());
            command.redirectErrorStream(true);
            command.redirectOutput(log.toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
            env.put("E2E_USERNAME", "runtime-e2e-admin");
            env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_WORKFLOW_ID", workflow);
            env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.remove("SDK4_LIVE_MODEL_KEY");
            env.remove("E2E_LIVE_MODEL");
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
                String output = Files.exists(log) ? Files.readString(log) : "<no Playwright log>";
                String tail = output.length() > 6000 ? output.substring(output.length() - 6000) : output;
                assertEquals(0, process.exitValue(), "浏览器验收失败；证据=" + evidence + "\n" + tail);
            }
            var facts = objectMapper.readTree(Files.readString(browser.resolve("workflow-hitl-reply-candidate.json")));
            String run = facts.path("runId").asText();
            String input = facts.path("replyInputId").asText();
            assertFalse(run.isBlank());
            assertFalse(input.isBlank());
            assertEquals(1, facts.path("replyPosts").asInt());
            String testSession = facts.path("testSessionId").asText();
            assertFalse(testSession.isBlank());
            assertEquals("run:workflow-test:" + testSession, run);
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs r "
                    + "JOIN agent_runtime_execution_actions a ON a.tenant_id=r.tenant_id AND a.run_id=r.run_id "
                    + "JOIN agent_runtime_execution_waits w ON w.tenant_id=a.tenant_id AND w.run_id=a.run_id AND w.action_id=a.action_id "
                    + "WHERE r.tenant_id=? AND r.run_id=? AND r.input_id=? AND a.kind='WORKFLOW' AND a.action_ordinal=1 "
                    + "AND w.kind='WORKFLOW' AND w.input_id=? AND w.accepted_revision IS NOT NULL "
                    + "AND w.response_ref IS NOT NULL AND w.execution_id IS NOT NULL AND w.suspend_ref IS NOT NULL "
                    + "AND w.action_key IS NOT NULL AND w.execution_revision IS NOT NULL",
                    tenantId, run, "workflow-test:" + testSession, input), "原测试的工作流动作已受理这次回复");
            assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions "
                    + "WHERE tenant_id=? AND run_id=? AND kind='MODEL'", tenantId, run), "测试工作流直接调用，不经过模型决策");
            verifyReceiptHttp(testSession, run, input);
            System.out.println("WORKFLOW_HITL_REPLY_EVIDENCE " + evidence);
        }
    }

    private void verifyReceiptHttp(String testSession, String run, String input) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND phase='FINISHED'", tenantId, run) == 0
                && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND phase='FINISHED'", tenantId, run));
        var rows = queryRows(jdbc, tenantId, "SELECT action_id,generation,execution_id,suspend_ref,action_key,execution_revision,accepted_revision "
                + "FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND input_id=?", tenantId, run, input);
        assertEquals(1, rows.size());
        var wait = rows.getFirst();
        var identity = new LinkedHashMap<String, String>();
        identity.put("testSessionId", testSession);
        identity.put("inputId", input);
        identity.put("runId", run);
        identity.put("actionId", wait.get("action_id").toString());
        identity.put("generation", wait.get("generation").toString());
        identity.put("kind", "workflow");
        identity.put("executionId", wait.get("execution_id").toString());
        identity.put("suspendRef", wait.get("suspend_ref").toString());
        identity.put("actionKey", wait.get("action_key").toString());
        identity.put("executionRevision", wait.get("execution_revision").toString());

        var before = receiptState(run);
        var response = receiptGet(identity, accessToken);
        assertEquals(200, response.statusCode(), response.body());
        var receipt = successData(response);
        assertEquals(testSession, receipt.path("test_session_id").asText());
        var sessions = queryRows(jdbc, tenantId, "SELECT session_id FROM nw_workflow_test_sessions WHERE tenant_id=? AND id=?",
                Long.parseLong(tenantId), Long.parseLong(testSession));
        assertEquals(1, sessions.size());
        assertEquals(sessions.getFirst().get("session_id").toString(), receipt.path("session_id").asText());
        assertEquals(run, receipt.path("run_id").asText());
        assertEquals(input, receipt.path("input_id").asText());
        for (var field : Map.of("actionId", "action_id", "generation", "generation", "executionId", "execution_id",
                "suspendRef", "suspend_ref", "actionKey", "action_key", "executionRevision", "execution_revision").entrySet())
            assertEquals(identity.get(field.getKey()), receipt.path(field.getValue()).asText(), field.getKey());
        assertEquals("workflow", receipt.path("kind").asText());
        assertEquals(wait.get("accepted_revision").toString(), receipt.path("accepted_revision").asText());
        assertEquals(12, receipt.size(), "回执只返回约定身份和受理版本");
        assertFalse(response.body().contains("canonical_json"));
        for (String key : List.of("inputId", "runId", "actionId", "executionId", "suspendRef", "actionKey")) {
            var changed = new LinkedHashMap<>(identity);
            changed.put(key, identity.get(key) + "-other");
            assertEquals(204, receiptGet(changed, accessToken).statusCode(), key);
        }
        for (String key : List.of("generation", "executionRevision")) {
            var changed = new LinkedHashMap<>(identity);
            changed.put(key, Long.toString(Long.parseLong(identity.get(key)) + 1));
            assertEquals(204, receiptGet(changed, accessToken).statusCode(), key);
        }
        for (String key : List.of("inputId", "actionId", "kind", "executionId", "suspendRef", "actionKey")) {
            var changed = new LinkedHashMap<>(identity);
            changed.put(key, "");
            assertEquals(204, receiptGet(changed, accessToken).statusCode(), key);
        }
        var wrongKind = new LinkedHashMap<>(identity);
        wrongKind.put("kind", "human");
        assertEquals(204, receiptGet(wrongKind, accessToken).statusCode());
        var missingWorkflow = new LinkedHashMap<>(identity);
        missingWorkflow.remove("executionId");
        assertEquals(204, receiptGet(missingWorkflow, accessToken).statusCode());

        TenantContext.setTenantId(tenantId);
        String other, denied;
        try {
            other = receiptMember("receipt-other", true);
            denied = receiptMember("receipt-denied", false);
        } finally { TenantContext.clear(); }
        assertEquals(403, receiptGet(identity, other).statusCode());
        assertEquals(403, receiptGet(identity, denied).statusCode());
        String foreignSlug = "receipt-foreign-" + SnowflakeIds.nextId();
        String foreignTenant = createTenant(receiptTenants, receiptPlans, foreignSlug);
        TenantContext.setTenantId(foreignTenant);
        try { createTestTenantAdministrator(foreignTenant, "receipt-foreign", PASSWORD); }
        finally { TenantContext.clear(); }
        String foreign = receiptLogin(foreignSlug, "receipt-foreign");
        assertEquals(404, receiptGet(identity, foreign).statusCode());
        assertEquals(before, receiptState(run), "回执查询不得改变运行状态或追加动作、等待和事件");
    }

    private Map<String, Object> receiptState(String run) {
        var rows = queryRows(jdbc, tenantId, "SELECT r.phase,r.revision,"
                + "(SELECT count(*) FROM agent_runtime_execution_actions a WHERE a.tenant_id=r.tenant_id AND a.run_id=r.run_id) AS actions,"
                + "(SELECT count(*) FROM agent_runtime_execution_waits w WHERE w.tenant_id=r.tenant_id AND w.run_id=r.run_id) AS waits,"
                + "(SELECT count(*) FROM agent_runtime_execution_events e WHERE e.tenant_id=r.tenant_id AND e.run_id=r.run_id) AS events "
                + "FROM agent_runtime_execution_runs r WHERE r.tenant_id=? AND r.run_id=?", tenantId, run);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    private String receiptMember(String username, boolean readPermission) throws Exception {
        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenantId)); member.setUsername(username);
        member.setRole("member"); member.setPermissions(List.of()); member.setStatus("active");
        member.setPasswordHash(receiptPasswords.encode(PASSWORD)); member.setCreatedAt(java.time.Instant.now());
        receiptMembers.insert(member);
        jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenantId, member.getId(), username);
        long role = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,?,'all')", role, tenantId, "receipt_" + role, username);
        if (readPermission) jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenantId, role, "foundation.workflows.read");
        jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenantId, member.getId(), role);
        return receiptLogin(fixtureTenantSlug(), username);
    }

    private String receiptLogin(String slug, String username) throws Exception {
        var response = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", slug, "username", username, "password", PASSWORD), null);
        assertEquals(200, response.statusCode(), response.body());
        String token = successData(response).path("access_token").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private HttpResponse<String> receiptGet(Map<String, String> identity, String token) throws Exception {
        String query = identity.entrySet().stream().map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/builder/workflows/test-sessions/reply-receipt?" + query))
                .header("Authorization", "Bearer " + token).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
