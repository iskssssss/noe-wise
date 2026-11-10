package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 完整包导入后在线编辑文本，不改变非文本资源和 Runtime 冻结读取的原字节。 */
class SkillPackageEncodingBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("skill-package-encoding-");
    private static final String DRAFTS = "/api/v1/capabilities/skill-drafts";
    private static final String IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void packageResourceEncodingSurvivesTextEditAndRuntime() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_SKILL_PACKAGE_ENCODING", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/skill-package-encoding-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("SKILL_PACKAGE_ENCODING_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, ZIP import, browser text editing/publication, Agent mount/publication and local controlled HTTP model/Runtime. No external supplier. Original image bytes checked in published package and actual read_skill_resource result. Browser trace/video/automatic screenshots disabled; credentials excluded.");
        String marker = "PACKAGE_ENCODING_" + SnowflakeIds.nextId();
        String guide = "已在线修改的指南：" + marker;
        byte[] image = Base64.getDecoder().decode(IMAGE);
        Files.write(evidence.resolve("original-image.png"), image);
        var created = send(DRAFTS + "/new", "POST", Map.of("capability_key", "package-encoding-" + SnowflakeIds.nextId(),
                "name", "文件编码验收技能", "description", "文本编辑保留原始资源"), accessToken);
        Files.writeString(evidence.resolve("created.json"), created.body()); assertEquals(201, created.statusCode(), created.body());
        var draft = successData(created);
        String definition = draft.path("definition_id").asText();
        var files = new LinkedHashMap<String, byte[]>();
        files.put("SKILL.md", ("---\nname: package-encoding-skill\ndescription: Read the requested frozen package resource\n---\nRead assets/image.png using read_skill_resource when asked.\n").getBytes(StandardCharsets.UTF_8));
        files.put("references/guide.md", "原始指南正文".getBytes(StandardCharsets.UTF_8));
        files.put("assets/image.png", image);
        var archive = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            for (var entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        Files.write(evidence.resolve("original.zip"), archive.toByteArray());
        String boundary = "PackageEncoding" + SnowflakeIds.nextId();
        var multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"skill.zip\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(archive.toByteArray()); multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var imported = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + DRAFTS + "/import-zip?draft_id=" + draft.path("id").asText()))
                .timeout(Duration.ofSeconds(90)).header("Authorization", "Bearer " + accessToken)
                .header("If-Match", draft.path("revision").asText()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve("imported.json"), imported.body()); assertEquals(200, imported.statusCode(), imported.body());
        assertEquals(IMAGE, successData(imported).path("files").path("assets/image.png").asText());
        FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "e2e/skill-package-encoding-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_SKILL_PACKAGE_ENCODING", mode);
        env.put("E2E_SKILL_DEFINITION", definition); env.put("E2E_IMAGE_BASE64", IMAGE); env.put("E2E_GUIDE_TEXT", guide);
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        try {
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "技能文件浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var result = objectMapper.readTree(Files.readString(browser.resolve("result.json")));
            String version = result.path("version").asText(); assertFalse(version.isBlank());
            var published = get(DRAFTS + "/version?versionId=" + version);
            Files.writeString(evidence.resolve("published-package.json"), published.body()); assertEquals(200, published.statusCode(), published.body());
            var publishedFiles = successData(published).path("files");
            assertArrayEquals(image, Base64.getDecoder().decode(publishedFiles.path("assets/image.png").asText()));
            assertEquals(guide, new String(Base64.getDecoder().decode(publishedFiles.path("references/guide.md").asText()), StandardCharsets.UTF_8));
            assertEquals(3, publishedFiles.size());
            var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
            var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                    "capability_type", "SKILL", "capability_definition_id", definition, "capability_version_id", version,
                    "skill_config", Map.of("activation_mode", "ALWAYS", "token_budget", 8000), "priority", 0, "status", "ACTIVE")), accessToken);
            Files.writeString(evidence.resolve("mount.json"), mounted.body()); assertEquals(201, mounted.statusCode(), mounted.body());
            String handle = "skill:" + successData(mounted).path("mounts").get(0).path("mount_id").asText();
            try (var model = new ResourceModel(handle, definition, version, marker)) {
                TenantContext.runWithTenant(tenantId, () -> configureTenantModel(model.endpoint()));
                var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
                Files.writeString(evidence.resolve("agent-publication.json"), publication.body()); assertEquals(200, publication.statusCode(), publication.body());
                var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
                assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
                try {
                    var input = chatPayload("请读取已挂载技能中的 assets/image.png，并确认读取结果。");
                    Files.writeString(evidence.resolve("runtime-input.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input));
                    var capture = postSse(input); Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
                    assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
                    assertFalse(capture.events().contains("error"), capture.raw()); assertTrue(capture.raw().contains(marker), capture.raw());
                    assertEquals(List.of(), model.failures); assertEquals(2, model.calls.get());
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, agentId));
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId));
                    assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND state<>'SETTLED'", tenantId));
                } finally {
                    Files.writeString(evidence.resolve("model-http.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("requests", model.requests, "responses", model.responses, "failures", model.failures)));
                }
            }
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? ORDER BY created_second LIMIT 10", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 10", tenantId))));
        }
    }

    /** 第二次模型请求必须含原 callId 对应的真实资源结果，才返回成功正文。 */
    private final class ResourceModel implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger calls = new AtomicInteger();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> responses = new CopyOnWriteArrayList<>(), failures = new CopyOnWriteArrayList<>();
        final String handle, definition, version, marker;
        ResourceModel(String handle, String definition, String version, String marker) throws IOException {
            this.handle = handle; this.definition = definition; this.version = version; this.marker = marker;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers); server.createContext("/", this::respond); server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void respond(HttpExchange exchange) throws IOException {
            try {
                JsonNode request;
                try (var input = exchange.getRequestBody()) { request = objectMapper.readTree(input); }
                requests.add(request.deepCopy()); int ordinal = calls.incrementAndGet();
                assertTrue(ordinal <= 2, "不应出现额外模型请求");
                String frozen = "Frozen capability: " + definition + " @ " + version;
                assertTrue(request.path("messages").toString().contains(frozen));
                Map<String, Object> message;
                if (ordinal == 1) {
                    assertTrue(request.path("tools").toString().contains("read_skill_resource"));
                    assertFalse(request.path("messages").toString().contains(IMAGE), "二进制资源应按请求读取");
                    message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "package-resource", "type", "function", "function",
                            Map.of("name", "read_skill_resource", "arguments", objectMapper.writeValueAsString(Map.of("skill_handle", handle, "path", "assets/image.png", "commentary", "读取冻结包中的原始资源"))))));
                } else {
                    var results = new java.util.ArrayList<String>();
                    for (var item : request.path("messages")) if ("tool".equals(item.path("role").asText()) && "package-resource".equals(item.path("tool_call_id").asText())) results.add(item.path("content").asText());
                    assertEquals(1, results.size()); String result = results.getFirst();
                    assertTrue(result.contains("LOADED") && result.contains(frozen) && result.contains("Path: assets/image.png") && result.contains("Encoding: BASE64"), result);
                    String content = result.substring(result.indexOf(" bytes\n\n", result.indexOf("### Loaded resource")) + " bytes\n\n".length());
                    assertEquals(IMAGE, content); assertArrayEquals(Base64.getDecoder().decode(IMAGE), Base64.getDecoder().decode(content));
                    message = Map.of("role", "assistant", "content", marker);
                }
                String finish = ordinal == 1 ? "tool_calls" : "stop";
                var usage = Map.of("prompt_tokens", 30, "completion_tokens", 5, "total_tokens", 35);
                String response;
                if (request.path("stream").asBoolean()) {
                    var delta = new LinkedHashMap<>(message);
                    if (ordinal == 1) {
                        @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) delta.get("tool_calls");
                        var indexed = new LinkedHashMap<>(items.getFirst()); indexed.put("index", 0); delta.put("tool_calls", List.of(indexed));
                    }
                    String first = objectMapper.writeValueAsString(Map.of("id", "package-model", "object", "chat.completion.chunk", "model", request.path("model").asText(), "choices", List.of(Map.of("index", 0, "delta", delta))));
                    String last = objectMapper.writeValueAsString(Map.of("id", "package-model", "object", "chat.completion.chunk", "model", request.path("model").asText(), "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
                    response = "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                } else {
                    response = objectMapper.writeValueAsString(Map.of("id", "package-model", "object", "chat.completion", "model", request.path("model").asText(), "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)), "usage", usage));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                }
                responses.add(response); byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
                failures.add(failure.toString()); exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        }
        @Override public void close() throws InterruptedException {
            server.stop(0); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "模型线程未结束");
        }
    }
}
