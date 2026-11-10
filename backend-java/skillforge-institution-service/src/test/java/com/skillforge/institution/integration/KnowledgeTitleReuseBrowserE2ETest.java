package com.skillforge.institution.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 标题修订复用不可变内容和分块，同时保留新旧来源的真实读取与执行。 */
class KnowledgeTitleReuseBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-title-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("skillforge.elasticsearch-enabled", () -> false);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void titleOnlyEditReusesChunksAndRemainsCitable() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_TITLE_REUSE", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        boolean baseline = "baseline".equals(mode);
        login();
        Path evidence = Path.of("build/runtime-e2e/knowledge-title-reuse-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_TITLE_REUSE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, admin browser title-only PATCH, economical keyword index, published knowledge, local controlled HTTP model and Runtime. Baseline duplicates chunk sets/members, not embeddings. Runtime and citation acceptance use actual APIs; browser acceptance covers the title editor. Credentials, trace and video excluded. No external supplier or vector generation claim.");
        try {
            String sentinel = "titlereuse" + SnowflakeIds.nextId();
            String content = "标题修订验收原文 " + sentinel + "。正文与分块应保持同一份不可变内容。";
            var knowledge = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                    .publish(runId, sentinel, content, Map.of("indexing_mode", "economical", "retrieval_mode", "full_text"), evidence);
            write(evidence.resolve("knowledge.json"), knowledge);
            String documentId = knowledge.documentId();
            var original = observed(evidence, "document-before", "/api/v1/knowledge/documents/detail?docId=" + documentId, "GET", null, 200);
            String oldTitle = original.path("title").asText();
            String newTitle = "标题已修订 " + runId;
            var before = storage(documentId);
            write(evidence.resolve("storage-before.json"), before);
            var capacityBefore = observed(evidence, "capacity-before", "/api/v1/knowledge/storage/capacity", "GET", null, 200);
            assertEquals(1, before.path("versions").size());
            assertEquals(1, before.path("sets").size());
            assertEquals(1, before.path("chunks").size());
            assertEquals(0, before.path("embeddings").size());
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                    "test", "e2e/knowledge-title-reuse-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
            command.directory(admin.toFile());
            command.redirectErrorStream(true);
            command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_KNOWLEDGE_TITLE_REUSE", mode); env.put("E2E_KNOWLEDGE_ID", knowledge.kbId());
            env.put("E2E_DOCUMENT_ID", documentId); env.put("E2E_SOURCE_VERSION", knowledge.sourceVersion());
            env.put("E2E_OLD_TITLE", oldTitle); env.put("E2E_NEW_TITLE", newTitle); env.put("E2E_ORIGINAL_CONTENT", content);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture();
                write(evidence.resolve("storage-after-browser.json"), storage(documentId));
                assertFalse(process.isAlive(), "知识标题浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var current = observed(evidence, "document-after", "/api/v1/knowledge/documents/detail?docId=" + documentId, "GET", null, 200);
            String source = current.path("source_version").asText();
            assertNotEquals(knowledge.sourceVersion(), source);
            assertEquals(newTitle, current.path("title").asText());
            assertEquals(content, current.path("content").asText());
            var after = storage(documentId);
            write(evidence.resolve("storage-after.json"), after);
            var capacityAfter = observed(evidence, "capacity-after", "/api/v1/knowledge/storage/capacity", "GET", null, 200);
            assertEquals(2, after.path("versions").size());
            var oldVersion = before.path("versions").get(0);
            var newVersion = after.path("versions").get(1);
            assertEquals(source, newVersion.path("source_version").asText());
            assertEquals(oldVersion.path("content_id"), newVersion.path("content_id"));
            assertEquals(before.path("contents"), after.path("contents"));
            assertEquals(before.path("chunkBodies"), after.path("chunkBodies"));
            assertEquals(before.path("embeddings"), after.path("embeddings"));
            assertEquals(baseline ? 2 : 1, after.path("sets").size());
            assertEquals(baseline ? 2 : 1, after.path("chunks").size());
            if (baseline) {
                assertNotEquals(oldVersion.path("chunk_set_id"), newVersion.path("chunk_set_id"));
                assertNotEquals(after.path("chunks").get(0).path("id"), after.path("chunks").get(1).path("id"));
            } else {
                assertEquals(oldVersion.path("chunk_set_id"), newVersion.path("chunk_set_id"));
                assertEquals(before.path("sets"), after.path("sets"));
                assertEquals(before.path("chunks"), after.path("chunks"));
            }
            for (String key : List.of("content_bytes", "embedding_bytes", "chunk_bodies", "embeddings", "source_versions", "chunk_references")) {
                assertTrue(capacityBefore.path(key).isIntegralNumber(), "容量字段必须存在：" + key);
                assertTrue(capacityAfter.path(key).isIntegralNumber(), "容量字段必须存在：" + key);
            }
            for (String key : List.of("content_bytes", "embedding_bytes", "chunk_bodies", "embeddings"))
                assertEquals(capacityBefore.path(key), capacityAfter.path(key), key);
            assertEquals(capacityBefore.path("source_versions").asLong() + 1, capacityAfter.path("source_versions").asLong());
            assertEquals(capacityBefore.path("chunk_references").asLong() + (baseline ? 1 : 0), capacityAfter.path("chunk_references").asLong());
            var revision = observed(evidence, "revision-create", "/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", knowledge.kbId()), 200);
            String number = revision.path("revision").asText();
            observed(evidence, "revision-ready", "/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", knowledge.kbId(), "revision", number,
                    "payload", Map.of("source_versions", List.of(source), "policy", Map.of("model_invocable", true, "user_invocable", false,
                            "allowed_operators", List.of("KEYWORD"), "freshness_policy", "PINNED", "citation_required", true))), 200);
            var publication = observed(evidence, "revision-publish", "/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", knowledge.kbId(), "revision", number), 200);
            for (var version : Map.of(knowledge.sourceVersion(), oldTitle, source, newTitle).entrySet()) {
                var exact = observed(evidence, version.getKey().equals(source) ? "new-source" : "old-source",
                        "/api/v1/knowledge/documents/version?docId=" + documentId + "&sourceVersion=" + URLEncoder.encode(version.getKey(), StandardCharsets.UTF_8), "GET", null, 200);
                assertEquals(version.getKey(), exact.path("source_version").asText());
                assertEquals(version.getValue(), exact.path("title").asText());
                assertEquals(content, exact.path("content").asText());
            }
            agentId = observed(evidence, "agent-revise", "/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), 200).path("id").asText();
            var mount = observed(evidence, "mount", "/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                    "capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", Long.toString(knowledge.definitionId()),
                    "capability_version_id", publication.path("capability_version_id").asText(), "priority", 0,
                    "knowledge_config", Map.of("mode", "PINNED_REVISION", "pinned_revision_id", publication.path("id").asText()))), 201);
            String handle = "knowledge:" + mount.path("mounts").get(0).path("mount_id").asText();
            try (var model = new RuntimeExecutionWorkloadProvider(objectMapper)) {
                model.start();
                TenantContext.runWithTenant(tenantId, () -> configureTenantModel(model.endpoint()));
                observed(evidence, "agent-publish", "/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), 200);
                sessionId = observed(evidence, "session-create", "/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), 200).path("session_id").asText();
                var script = model.register("title-reuse-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
                script.requireKnowledge(handle, knowledge.kbId(), source, sentinel);
                try {
                    var accepted = observed(evidence, "input", "/api/v1/chat", "POST", chatPayload(script.prompt()), 202);
                    String actualRun = accepted.path("execution_id").asText();
                    assertFalse(actualRun.isBlank());
                    await().atMost(Duration.ofSeconds(120)).until(() -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status IN ('COMPLETED','FAILED','CANCELLED','TERMINATED')", tenantId, actualRun) == 1);
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
                    var events = queryRows(jdbc, tenantId, "SELECT event_id,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed' LIMIT 2", tenantId, actualRun);
                    write(evidence.resolve("completion.json"), events);
                    assertEquals(1, events.size());
                    var completion = objectMapper.readTree(events.getFirst().get("canonical_json").toString());
                    assertEquals(1, completion.path("evidence").size());
                    String citation = completion.path("evidence").get(0).path("citationId").asText();
                    var readable = observed(evidence, "citation", "/api/v1/chat/citations/content?session_id=" + sessionId
                            + "&message_id=" + URLEncoder.encode(events.getFirst().get("event_id").toString(), StandardCharsets.UTF_8) + "&citation_id=" + citation, "GET", null, 200);
                    assertEquals("no-store", objectMapper.readTree(Files.readString(evidence.resolve("citation.json"))).path("cacheControl").asText());
                    assertEquals(source, readable.path("source_version").asText());
                    assertEquals(content, readable.path("text").asText());
                    assertEquals(2, script.snapshot().requestCount());
                    assertEquals(2, script.snapshot().requests().size());
                    assertEquals(1, script.snapshot().verifiedKnowledgeItemIds().size());
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?", tenantId));
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE' AND status='SUCCEEDED'", tenantId, actualRun));
                    assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
                    write(evidence.resolve("result.json"), Map.of("mode", mode, "documentId", documentId, "oldSource", knowledge.sourceVersion(), "newSource", source,
                            "oldTitle", oldTitle, "newTitle", newTitle, "sessionId", sessionId, "runId", actualRun, "setsReused", !baseline));
                } finally { write(evidence.resolve("model.json"), script.snapshot()); }
            }
        } finally {
            write(evidence.resolve("runtime.json"), Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? ORDER BY created_second LIMIT 10", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 10", tenantId)));
            write(evidence.resolve("versions-final.json"), queryRows(jdbc, tenantId, "SELECT document_id,source_version,title,content_id,chunk_set_id FROM nw_knowledge_document_versions WHERE tenant_id=? ORDER BY id LIMIT 10", Long.valueOf(tenantId)));
        }
    }

    private JsonNode storage(String documentId) {
        long tenant = Long.parseLong(tenantId), document = Long.parseLong(documentId);
        return objectMapper.valueToTree(Map.of(
                "versions", queryRows(jdbc, tenantId, "SELECT source_version,title,content_id,chunk_set_id FROM nw_knowledge_document_versions WHERE tenant_id=? AND document_id=? ORDER BY id LIMIT 10", tenant, document),
                "sets", queryRows(jdbc, tenantId, "SELECT id FROM nw_knowledge_chunk_sets WHERE tenant_id=? AND document_id=? ORDER BY id LIMIT 10", tenant, document),
                "chunks", queryRows(jdbc, tenantId, "SELECT id,chunk_set_id,content_id,embedding_id,chunk_index FROM nw_knowledge_chunks WHERE tenant_id=? AND document_id=? ORDER BY id LIMIT 20", tenant, document),
                "contents", queryRows(jdbc, tenantId, "SELECT id,content FROM nw_knowledge_document_contents WHERE tenant_id=? AND document_id=? ORDER BY id LIMIT 10", tenant, document),
                "chunkBodies", queryRows(jdbc, tenantId, "SELECT id,content FROM nw_knowledge_chunk_contents WHERE tenant_id=? AND document_id=? ORDER BY id LIMIT 20", tenant, document),
                "embeddings", queryRows(jdbc, tenantId, "SELECT id,content_id,model_id,model_revision,dimensions FROM nw_knowledge_chunk_embeddings WHERE tenant_id=? ORDER BY id LIMIT 20", tenant)));
    }

    private JsonNode observed(Path evidence, String name, String path, String method, Map<String, Object> request, int status) throws Exception {
        var response = send(path, method, request, accessToken);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("path", path); receipt.put("method", method); receipt.put("request", request);
        receipt.put("status", response.statusCode()); receipt.put("body", response.body());
        receipt.put("cacheControl", response.headers().firstValue("Cache-Control").orElse(""));
        write(evidence.resolve(name + ".json"), receipt);
        assertEquals(status, response.statusCode(), response.body());
        return successData(response);
    }

    private void write(Path path, Object value) throws Exception {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
