package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 删除前后真实检索与历史引用的隔离验收。 */
class KnowledgeSoftDeletedSourceBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-soft-deleted-source-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("skillforge.elasticsearch-enabled", () -> false);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void softDeletedPublishedSourceIsExcluded() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_SOFT_DELETE", "baseline");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/knowledge-soft-deleted-source-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_SOFT_DELETE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离 Spring、PostgreSQL、浏览器和 Runtime；模型为本地受控 HTTP，知识使用经济索引。", StandardCharsets.UTF_8);
        String common = "sharedquery" + SnowflakeIds.nextId();
        String targetTerm = "targetquery" + SnowflakeIds.nextId();
        String targetTitle = "待删除来源 " + runId;
        var target = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                .publish(runId, targetTerm, common + " " + targetTerm + " target document", Map.of("indexing_mode", "economical", "retrieval_mode", "full_text"), evidence);
        String boundary = "Fixture-" + SnowflakeIds.nextId();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"survivor.txt\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + common + " survivor document\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n保留来源\r\n--" + boundary + "--\r\n";
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
        try (var model = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start(); TenantContext.runWithTenant(tenantId, () -> configureTenantModel(model.endpoint()));
            var agentPublication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken); assertEquals(200, agentPublication.statusCode(), agentPublication.body());
            var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken); assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
            JsonNode before = execute(model, evidence, "before", handle, target.kbId(), targetSource, targetTerm);
            JsonNode oldCitation = before.path("citations").get(0);
            String citationPath = "/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id=" + URLEncoder.encode(before.path("message_id").asText(), StandardCharsets.UTF_8) + "&citation_id=" + oldCitation.path("citation_id").asText();
            var readableBefore = get(citationPath); assertEquals(200, readableBefore.statusCode(), readableBefore.body());
            assertEquals(targetSource, successData(readableBefore).path("source_version").asText());
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/knowledge-soft-deleted-source-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString()); env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_KNOWLEDGE_SOFT_DELETE", mode); env.put("E2E_KNOWLEDGE_ID", target.kbId()); env.put("E2E_TARGET_TITLE", targetTitle); env.put("E2E_TARGET_ID", target.documentId()); env.put("E2E_TARGET_SOURCE", targetSource); env.put("E2E_SURVIVOR_SOURCE", survivor); env.put("E2E_QUERY", common);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var oldResponse = get(citationPath);
            Files.writeString(evidence.resolve("old-citation.json"), objectMapper.writeValueAsString(Map.of("status", oldResponse.statusCode(), "body", oldResponse.body())));
            assertEquals(403, oldResponse.statusCode(), oldResponse.body());
            runId = Long.toString(SnowflakeIds.nextId());
            var nextSession = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, nextSession.statusCode(), nextSession.body()); sessionId = successData(nextSession).path("session_id").asText();
            JsonNode after = execute(model, evidence, "after", handle, target.kbId(), "candidate".equals(mode) ? survivor : targetSource, "candidate".equals(mode) ? common : targetTerm);
            assertEquals("candidate".equals(mode) ? survivor : targetSource, after.path("citations").get(0).path("source_version").asText());
            Files.writeString(evidence.resolve("facts.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("mode", mode, "targetDocument", target.documentId(), "targetSource", targetSource, "survivorDocument", survivorId, "survivorSource", survivor, "session", sessionId)));
        }
    }

    private JsonNode execute(RuntimeExecutionWorkloadProvider model, Path evidence, String name, String handle, String kb, String source, String query) throws Exception {
        var script = model.register("soft-delete-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
        script.requireKnowledge(handle, kb, source, query);
        try {
            var capture = postSse(chatPayload(script.prompt())); Files.writeString(evidence.resolve(name + "-sse.txt"), capture.raw());
            assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertFalse(script.snapshot().verifiedKnowledgeItemIds().isEmpty());
            JsonNode finalMessage = null;
            for (String line : capture.raw().split("\\R")) {
                if (!line.startsWith("data:")) continue;
                var event = objectMapper.readTree(line.substring(5));
                if (event.path("citations").isArray() && !event.path("citations").isEmpty()) finalMessage = event;
            }
            assertNotNull(finalMessage); assertEquals(source, finalMessage.path("citations").get(0).path("source_version").asText());
            return finalMessage;
        } finally { Files.writeString(evidence.resolve(name + "-model.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(script.snapshot())); }
    }
}
