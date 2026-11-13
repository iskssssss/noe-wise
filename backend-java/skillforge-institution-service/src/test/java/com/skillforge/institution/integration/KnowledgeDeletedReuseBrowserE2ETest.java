package com.skillforge.institution.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 同一 Run 在来源删除后的重复知识查询。 */
class KnowledgeDeletedReuseBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-deleted-reuse-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("skillforge.elasticsearch-enabled", () -> false);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Test void repeatedQueryAfterDelete() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_DELETED_REUSE", "deleted");
        assertTrue(List.of("deleted", "survivor").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/knowledge-deleted-reuse-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_DELETED_REUSE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "本地受控模型第二次请求已到达后等待浏览器删除；故该次请求在删除前派发，第三次请求在删除后派发。真实 Runtime、数据库及页面；不调用外部模型。", StandardCharsets.UTF_8);
        String common = "sharedquery" + SnowflakeIds.nextId();
        String targetTerm = "targetquery" + SnowflakeIds.nextId();
        String survivorTerm = "survivorquery" + SnowflakeIds.nextId();
        String targetTitle = "待删除来源 " + runId;
        var target = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                .publish(runId, targetTerm, common + " " + targetTerm + " target document", Map.of("indexing_mode", "economical", "retrieval_mode", "full_text"), evidence);
        String boundary = "Fixture-" + SnowflakeIds.nextId();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"survivor.txt\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + common + " " + survivorTerm + " survivor document\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n保留来源\r\n--" + boundary + "--\r\n";
        var upload = httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/api/v1/knowledge/bases/documents/upload?kb_id=" + target.kbId() + "&request_id=" + java.util.UUID.randomUUID()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, upload.statusCode(), upload.body());
        String survivorId = successData(upload).path("document").path("document_id").asText(); assertFalse(survivorId.isBlank());
        String survivor = successData(get("/api/v1/knowledge/documents/detail?docId=" + survivorId)).path("source_version").asText(); assertFalse(survivor.isBlank());
        var revisedTarget = send("/api/v1/knowledge/documents/update", "PATCH", Map.of("doc_id", target.documentId(), "payload", Map.of("title", targetTitle, "content", target.documentText(), "expected_source_version", target.sourceVersion())), accessToken);
        assertEquals(200, revisedTarget.statusCode(), revisedTarget.body());
        String targetSource = successData(revisedTarget).path("source_version").asText();
        var revision = send("/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", target.kbId()), accessToken);
        assertEquals(200, revision.statusCode(), revision.body()); String number = successData(revision).path("revision").asText();
        var ready = send("/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", target.kbId(), "revision", number, "payload", Map.of("source_versions", List.of(targetSource, survivor), "policy", Map.of("model_invocable", true, "user_invocable", false, "allowed_operators", List.of("KEYWORD"), "freshness_policy", "PINNED", "citation_required", true))), accessToken);
        assertEquals(200, ready.statusCode(), ready.body());
        var published = send("/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", target.kbId(), "revision", number), accessToken);
        assertEquals(200, published.statusCode(), published.body()); var publication = successData(published);
        agentId = successData(send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken)).path("id").asText();
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", Long.toString(target.definitionId()), "capability_version_id", publication.path("capability_version_id").asText(), "priority", 0, "knowledge_config", Map.of("mode", "PINNED_REVISION", "pinned_revision_id", publication.path("id").asText()))), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body()); String handle = "knowledge:" + successData(mounted).path("mounts").get(0).path("mount_id").asText();
        String query = "survivor".equals(mode) ? survivorTerm : targetTerm;
        String expectedSource = "survivor".equals(mode) ? survivor : targetSource;
        try (var model = new GatedModel(objectMapper, evidence, handle, query)) {
            model.start(); TenantContext.runWithTenant(tenantId, () -> configureTenantModel(model.endpoint()));
            var agentPublication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken); assertEquals(200, agentPublication.statusCode(), agentPublication.body());
            var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken); assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/knowledge-deleted-reuse-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString()); env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_KNOWLEDGE_ID", target.kbId()); env.put("E2E_TARGET_TITLE", targetTitle); env.put("E2E_TARGET_ID", target.documentId()); env.put("E2E_TARGET_SOURCE", targetSource); env.put("E2E_SURVIVOR_SOURCE", survivor); env.put("E2E_QUERY", common);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                await().atMost(Duration.ofSeconds(90)).until(() -> { owned.capture(); return Files.exists(browser.resolve("ready")) || !process.isAlive(); });
                assertTrue(Files.exists(browser.resolve("ready")), "浏览器未就绪：" + evidence);
                var run = CompletableFuture.supplyAsync(() -> { try { return postSse(chatPayload("请查询指定知识并回答。")); } catch (Exception e) { throw new RuntimeException(e); } });
                List<Map<String, Object>> knowledgeBefore;
                try {
                    assertTrue(model.second.await(90, TimeUnit.SECONDS), "第二次模型请求未到达");
                    String firstToolResult = knowledgeToolResult(model.request(2), handle, query);
                    assertTrue(firstToolResult.contains(query) && firstToolResult.contains(expectedSource), "首次检索正文未进入第二次请求");
                    knowledgeBefore = knowledgeFacts("run:" + runId);
                    assertEquals(1, knowledgeBefore.size());
                    Files.writeString(evidence.resolve("knowledge-before-delete.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(knowledgeBefore));
                    Files.writeString(browser.resolve("delete-now"), "go");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(80);
                    while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                    owned.capture(); assertFalse(process.isAlive(), "浏览器删除超时：" + evidence);
                    assertEquals(0, process.exitValue(), Files.readString(evidence.resolve("playwright.log")));
                    assertEquals(1, countRuntime("SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND deleted_at IS NOT NULL", tenantId, target.documentId()));
                    assertEquals(1, countRuntime("SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND deleted_at IS NULL", tenantId, survivorId));
                } finally { model.release.countDown(); }
                var capture = run.get(120, TimeUnit.SECONDS);
                Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
                assertEquals(200, capture.statusCode());
                String actualRun = "run:" + runId;
                var runs = queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun);
                var actions = queryRows(jdbc, tenantId, "SELECT action_id,kind,status,action_ordinal FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal", tenantId, actualRun);
                var attempts = queryRows(jdbc, tenantId, "SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? ORDER BY action_id", tenantId, actualRun);
                var budgets = queryRows(jdbc, tenantId, "SELECT attempt_id,category,state FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? ORDER BY attempt_id", tenantId, actualRun);
                var amounts = queryRows(jdbc, tenantId, "SELECT a.attempt_id,a.dimension,a.reserved,a.reported,a.settled FROM agent_runtime_budget_attempt_amounts a JOIN agent_runtime_budget_attempts b ON a.tenant_id=b.tenant_id AND a.attempt_id=b.attempt_id WHERE b.tenant_id=? AND b.run_id=? ORDER BY a.attempt_id,a.dimension", tenantId, actualRun);
                var events = queryRows(jdbc, tenantId, "SELECT type,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type IN ('execution.completed','execution.failed','error') ORDER BY event_sequence", tenantId, actualRun);
                var facts = new java.util.LinkedHashMap<String, Object>();
                facts.put("mode", mode); facts.put("runId", actualRun); facts.put("targetDocument", target.documentId()); facts.put("targetSource", targetSource);
                facts.put("survivorDocument", survivorId); facts.put("survivorSource", survivor); facts.put("modelCalls", model.calls.get());
                facts.put("runs", runs); facts.put("actions", actions); facts.put("attempts", attempts); facts.put("budgets", budgets); facts.put("amounts", amounts); facts.put("events", events);
                Files.writeString(evidence.resolve("facts.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
                assertEquals(knowledgeBefore, knowledgeFacts(actualRun), "已提交知识动作与预算事实必须保留");
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE' AND status='SUCCEEDED'", tenantId, actualRun));
                if ("deleted".equals(mode)) {
                    assertEquals(2, model.calls.get(), "删除后不得派发第三次模型请求");
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL'", tenantId, actualRun));
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='FAILED'", tenantId, actualRun));
                    var terminal = queryRows(jdbc, tenantId, "SELECT canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed' LIMIT 2", tenantId, actualRun);
                    assertEquals(1, terminal.size());
                    var terminalBody = objectMapper.readTree(terminal.getFirst().get("canonical_json").toString());
                    assertEquals("FAILED", terminalBody.path("status").asText());
                    assertEquals("knowledge_source_unavailable", terminalBody.path("reason").asText());
                } else {
                    assertEquals(3, model.calls.get());
                    String repeatedToolResult = knowledgeToolResult(model.request(3), handle, query);
                    assertTrue(repeatedToolResult.contains(query) && repeatedToolResult.contains(expectedSource), "重复查询的配对工具结果缺失");
                    assertTrue(capture.events().contains("execution.completed"), capture.raw());
                    var terminal = queryRows(jdbc, tenantId, "SELECT canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed' LIMIT 2", tenantId, actualRun);
                    assertEquals(1, terminal.size());
                    var terminalBody = objectMapper.readTree(terminal.getFirst().get("canonical_json").toString());
                    assertEquals("COMPLETED", terminalBody.path("status").asText());
                    assertEquals(1, terminalBody.path("evidence").size());
                    assertEquals(expectedSource, terminalBody.path("evidence").get(0).path("sourceVersion").asText());
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
                }
            } finally { model.release.countDown(); }
        }
    }

    private List<Map<String, Object>> knowledgeFacts(String run) {
        return queryRows(jdbc, tenantId, "SELECT a.action_id,a.status,t.attempt_id,t.result_kind,t.adopted,b.category,b.state FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt LEFT JOIN agent_runtime_budget_attempts b ON b.tenant_id=t.tenant_id AND b.run_id=t.run_id AND b.attempt_id=t.attempt_id WHERE a.tenant_id=? AND a.run_id=? AND a.kind='KNOWLEDGE'", tenantId, run);
    }

    private String knowledgeToolResult(JsonNode request, String handle, String query) throws Exception {
        String lastCallId = null;
        for (JsonNode message : request.path("messages")) {
            if (!"assistant".equals(message.path("role").asText())) continue;
            for (JsonNode call : message.path("tool_calls")) {
                if (!"knowledge_query".equals(call.path("function").path("name").asText())) continue;
                var args = objectMapper.readTree(call.path("function").path("arguments").asText());
                if (handle.equals(args.path("knowledge_handle").asText()) && query.equals(args.path("query").asText())) lastCallId = call.path("id").asText();
            }
        }
        assertNotNull(lastCallId, "模型请求缺少相同知识查询");
        for (JsonNode message : request.path("messages"))
            if ("tool".equals(message.path("role").asText()) && lastCallId.equals(message.path("tool_call_id").asText())) return message.path("content").asText();
        fail("模型请求缺少该查询配对的工具结果"); return "";
    }

    private static final class GatedModel implements AutoCloseable {
        final ObjectMapper json; final Path evidence; final String handle, query; final HttpServer server;
        final CountDownLatch second = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(); final java.util.concurrent.ConcurrentHashMap<Integer, JsonNode> requests = new java.util.concurrent.ConcurrentHashMap<>();
        GatedModel(ObjectMapper json, Path evidence, String handle, String query) throws Exception {
            this.json = json; this.evidence = evidence; this.handle = handle; this.query = query;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.createContext("/chat/completions", this::respond);
        }
        void start() { server.start(); }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        JsonNode request(int ordinal) { return requests.get(ordinal); }
        void respond(HttpExchange exchange) throws java.io.IOException {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody()); int ordinal = calls.incrementAndGet(); requests.put(ordinal, request);
                Files.writeString(evidence.resolve("model-request-" + ordinal + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(request));
                if (ordinal == 2) { second.countDown(); if (!release.await(100, TimeUnit.SECONDS)) throw new java.io.IOException("gate timeout"); }
                if (ordinal > 3) throw new java.io.IOException("unexpected request");
                boolean tool = ordinal < 3;
                var function = Map.of("name", "knowledge_query", "arguments", json.writeValueAsString(Map.of("knowledge_handle", handle, "query", query)));
                var message = tool ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call-" + ordinal, "type", "function", "function", function)))
                        : Map.of("role", "assistant", "content", "已完成查询。[[cite:" + lastCitation(request) + "]]");
                String model = request.path("model").asText("runtime-fixture");
                String first = json.writeValueAsString(Map.of("id", "gated-" + ordinal, "object", "chat.completion.chunk", "model", model, "choices", List.of(Map.of("index", 0, "delta", message))));
                String last = json.writeValueAsString(Map.of("id", "gated-" + ordinal, "object", "chat.completion.chunk", "model", model, "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", tool ? "tool_calls" : "stop")), "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
                byte[] body = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8"); exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            finally { exchange.close(); }
        }
        private String lastCitation(JsonNode request) throws java.io.IOException {
            String callId = null;
            for (JsonNode message : request.path("messages")) {
                if (!"assistant".equals(message.path("role").asText())) continue;
                for (JsonNode call : message.path("tool_calls")) {
                    if (!"knowledge_query".equals(call.path("function").path("name").asText())) continue;
                    var arguments = json.readTree(call.path("function").path("arguments").asText());
                    if (handle.equals(arguments.path("knowledge_handle").asText()) && query.equals(arguments.path("query").asText()))
                        callId = call.path("id").asText();
                }
            }
            if (callId == null || callId.isBlank()) throw new java.io.IOException("knowledge call missing");
            for (JsonNode message : request.path("messages")) {
                if (!"tool".equals(message.path("role").asText()) || !callId.equals(message.path("tool_call_id").asText())) continue;
                var bundle = json.readTree(message.path("content").asText());
                String citation = bundle.path("items").path(0).path("citationId").asText();
                if (!citation.matches("k[0-9a-f]{16}")) throw new java.io.IOException("knowledge citation missing");
                return citation;
            }
            throw new java.io.IOException("knowledge result missing");
        }
        public void close() { release.countDown(); server.stop(0); }
    }
}
