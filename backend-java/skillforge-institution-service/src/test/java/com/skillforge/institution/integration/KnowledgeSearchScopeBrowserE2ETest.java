package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class KnowledgeSearchScopeBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-search-scope-");
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void unpublishedPreviewAndPublishedRuntimeUseExplicitScope() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_SCOPE_MODE", "candidate");
        assertTrue(java.util.Set.of("baseline", "candidate").contains(mode));
        login(); String sentinel = "scopesentinel" + SnowflakeIds.nextId();
        Path evidence = Path.of("build/runtime-e2e/knowledge-search-scope-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_SEARCH_SCOPE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离机构服务、PostgreSQL、管理浏览器及真实 Runtime；模型是受控本地 HTTP。发布与挂载由正式 API 完成。凭据与 trace 不落盘。", StandardCharsets.UTF_8);
        try (var model = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start(); TenantContext.setTenantId(tenantId); try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            var created = send("/api/v1/knowledge/bases", "POST", Map.of("name", "范围验收" + runId, "description", "隔离检索范围", "indexing_mode", "economical", "retrieval_mode", "full_text"), accessToken);
            assertEquals(200, created.statusCode(), created.body()); String kb = successData(created).path("id").asText();
            String boundary = "SkillForge-" + UUID.randomUUID();
            String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"scope.txt\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n仅此文档包含 " + sentinel + "，作为检索与引用依据。\r\n--" + boundary + "--\r\n";
            var upload = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/knowledge/bases/documents/upload?kb_id=" + kb + "&request_id=" + UUID.randomUUID())).timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + accessToken).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, upload.statusCode(), upload.body()); Files.writeString(evidence.resolve("upload.json"), upload.body());
            var uploaded = successData(upload); assertEquals("SUCCEEDED", uploaded.path("status").asText());
            String doc = uploaded.path("document").path("document_id").asText(), source = uploaded.path("document").path("source_version").asText();
            assertFalse(doc.isBlank()); assertFalse(source.isBlank());
            var detail = get("/api/v1/knowledge/documents/detail?docId=" + doc); assertEquals("ready", successData(detail).path("status").asText());
            var base = get("/api/v1/knowledge/bases/detail?kbId=" + kb); assertTrue(successData(base).path("active_revision_id").isNull());
            Files.writeString(evidence.resolve("base-before.json"), base.body());
            FRONTEND.bindBackend(baseUrl()); browser("unpublished", mode, kb, source, sentinel, evidence, browser);
            var draft = send("/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", kb), accessToken); assertEquals(200, draft.statusCode()); String revision = successData(draft).path("revision").asText();
            var ready = send("/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", kb, "revision", revision, "payload", Map.of("source_versions", List.of(source), "policy", Map.of("model_invocable", true, "user_invocable", false, "allowed_operators", List.of("KEYWORD"), "freshness_policy", "PINNED", "citation_required", true))), accessToken); assertEquals(200, ready.statusCode(), ready.body());
            var published = send("/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", kb, "revision", revision), accessToken); assertEquals(200, published.statusCode(), published.body()); Files.writeString(evidence.resolve("publication.json"), published.body());
            browser("published", mode, kb, source, sentinel, evidence, browser);
            long version = successData(published).path("capability_version_id").asLong();
            long definition = successData(get("/api/v1/capabilities/versions/detail?versionId=" + version)).path("definition_id").asLong();
            var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken); assertEquals(200, revised.statusCode()); agentId = successData(revised).path("id").asText();
            var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "knowledge_config", Map.of("mode", "FOLLOW_ACTIVE"))), accessToken); assertEquals(201, mount.statusCode(), mount.body()); String mountId = successData(mount).path("mounts").get(0).path("mount_id").asText();
            var script = model.register("scope-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE); script.requireKnowledge("knowledge:" + mountId, kb, source, sentinel, sentinel);
            assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
            sessionId = successData(send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken)).path("session_id").asText();
            var capture = postSse(chatPayload(script.prompt())); Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
            assertTrue(capture.events().contains("execution.completed"), capture.raw()); assertTrue(capture.raw().contains(script.finalAnswer())); assertEquals(2, script.snapshot().requestCount()); assertFalse(script.snapshot().verifiedKnowledgeItemIds().isEmpty());
            com.fasterxml.jackson.databind.JsonNode message = null; for (String line : capture.raw().split("\\R")) if (line.startsWith("data:")) { var event = objectMapper.readTree(line.substring(5)); if (event.path("citations").isArray() && !event.path("citations").isEmpty()) message = event; }
            assertNotNull(message); var citation = message.path("citations").get(0); assertEquals(source, citation.path("source_version").asText());
            var cited = get("/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id=" + URLEncoder.encode(message.path("message_id").asText(), StandardCharsets.UTF_8) + "&citation_id=" + citation.path("citation_id").asText()); assertEquals(200, cited.statusCode(), cited.body()); assertTrue(successData(cited).path("text").asText().contains(sentinel)); Files.writeString(evidence.resolve("citation.json"), cited.body());
            var runs = queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?", tenantId, sessionId); assertEquals(1, runs.size()); assertEquals("COMPLETED", runs.getFirst().get("status")); assertEquals("FINISHED", runs.getFirst().get("phase")); Files.writeString(evidence.resolve("runtime-facts.json"), objectMapper.writeValueAsString(Map.of("runs", runs, "kb", kb, "source", source, "modelCalls", script.snapshot().requestCount())));
        }
    }
    private void browser(String stage, String mode, String kb, String source, String sentinel, Path evidence, Path browser) throws Exception {
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/knowledge-search-scope-real.spec.ts", "--output", evidence.resolve("playwright-" + stage).toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser-" + stage + ".log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString()); env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_KB_ID", kb); env.put("E2E_SOURCE_VERSION", source); env.put("E2E_SENTINEL", sentinel); env.put("E2E_KNOWLEDGE_SCOPE_MODE", mode); env.put("E2E_KNOWLEDGE_SCOPE_STAGE", stage); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start(); try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) { long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3); while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); } owned.capture(); assertFalse(process.isAlive()); assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser-" + stage + ".log"))); }
    }
}
