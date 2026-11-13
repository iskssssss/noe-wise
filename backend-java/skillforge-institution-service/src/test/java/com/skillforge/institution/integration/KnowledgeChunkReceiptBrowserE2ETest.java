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

/** 分块保存回执丢失时的实际状态。 */
class KnowledgeChunkReceiptBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-chunk-receipt-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("skillforge.elasticsearch-enabled", () -> false);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void observesLostChunkReceipt() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_CHUNK_RECEIPT", "baseline");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/knowledge-chunk-receipt-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_SOURCE_CHUNK_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离 Spring、PostgreSQL、浏览器、经济索引和 Runtime；模型为本地受控 HTTP。浏览器在真实分块写入后丢弃回执，发布、检索和引用走正式 API。凭据、trace 和 video 不落盘。", StandardCharsets.UTF_8);
        try {
            String sentinel = "chunkquery" + SnowflakeIds.nextId();
            String content = "甲".repeat(100) + "乙".repeat(30) + "丙".repeat(40);
            var knowledge = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                    .publish(runId, sentinel, content, Map.of("indexing_mode", "economical", "retrieval_mode", "full_text", "chunk_size", 100, "chunk_overlap", 0), evidence);
            write(evidence.resolve("knowledge.json"), knowledge);
            String documentId = knowledge.documentId();
            var original = observed(evidence, "document-before", "/api/v1/knowledge/documents/detail?docId=" + documentId, "GET", null, 200);
            String oldTitle = original.path("title").asText();
            var chunksBefore = observed(evidence, "chunks-before", "/api/v1/knowledge/documents/chunks?docId=" + documentId + "&page=1&pageSize=100", "GET", null, 200).path("items");
            assertEquals(2, chunksBefore.size());
            String firstChunk = chunksBefore.get(0).path("content").asText();
            String oldChunk = chunksBefore.get(1).path("content").asText();
            String editedChunk = firstChunk + "已保存分块标记" + sentinel;
            String secondDraft = oldChunk + "未保存分块草稿" + ("candidate".equals(mode) ? sentinel : "");
            String draft = "原文草稿标记" + content;
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                    "test", "e2e/knowledge-chunk-receipt-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
            command.directory(admin.toFile());
            command.redirectErrorStream(true);
            command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_KNOWLEDGE_CHUNK_RECEIPT", mode); env.put("E2E_KNOWLEDGE_ID", knowledge.kbId());
            env.put("E2E_DOCUMENT_ID", documentId); env.put("E2E_SOURCE_VERSION", knowledge.sourceVersion());
            env.put("E2E_OLD_TITLE", oldTitle); env.put("E2E_ORIGINAL_CONTENT", content);
            env.put("E2E_OLD_SECOND_CHUNK_ID", chunksBefore.get(1).path("id").asText());
            env.put("E2E_FIRST_CHUNK", firstChunk); env.put("E2E_SECOND_DRAFT", secondDraft); env.put("E2E_SOURCE_DRAFT", draft); env.put("E2E_OLD_CHUNK", oldChunk); env.put("E2E_EDITED_CHUNK", editedChunk);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture();
                assertFalse(process.isAlive(), "知识分块浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var current = observed(evidence, "document-after", "/api/v1/knowledge/documents/detail?docId=" + documentId, "GET", null, 200);
            String source = current.path("source_version").asText();
            assertNotEquals(knowledge.sourceVersion(), source);
            String expectedContent = current.path("content").asText();
            var chunksAfter = observed(evidence, "chunks-after", "/api/v1/knowledge/documents/chunks?docId=" + documentId + "&page=1&pageSize=100", "GET", null, 200).path("items");
            assertTrue(chunksAfter.toString().contains("已保存分块标记"));
            if ("candidate".equals(mode)) assertTrue(chunksAfter.toString().contains("未保存分块草稿"));

            var search = observed(evidence, "search", "/api/v1/knowledge/search", "POST", Map.of("kb_id", knowledge.kbId(), "query", sentinel, "source_version", source), 200);
            assertTrue(search.toString().contains("已保存分块标记"));
            if ("candidate".equals(mode)) assertTrue(search.toString().contains("未保存分块草稿"));
            var revision = observed(evidence, "revision-create", "/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", knowledge.kbId()), 200);
            String number = revision.path("revision").asText();
            observed(evidence, "revision-ready", "/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", knowledge.kbId(), "revision", number,
                    "payload", Map.of("source_versions", List.of(source), "policy", Map.of("model_invocable", true, "user_invocable", false,
                            "allowed_operators", List.of("KEYWORD"), "freshness_policy", "PINNED", "citation_required", true))), 200);
            var publication = observed(evidence, "revision-publish", "/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", knowledge.kbId(), "revision", number), 200);
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
                var script = model.register("chunk-receipt-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
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
                    int expectedCitations = "candidate".equals(mode) ? 2 : 1;
                    assertEquals(expectedCitations, completion.path("evidence").size());
                    StringBuilder citationTexts = new StringBuilder();
                    for (int index = 0; index < expectedCitations; index++) {
                        String citation = completion.path("evidence").get(index).path("citationId").asText();
                        String citationFile = index == 0 ? "citation" : "citation-" + index;
                        var readable = observed(evidence, citationFile, "/api/v1/chat/citations/content?session_id=" + sessionId
                                + "&message_id=" + URLEncoder.encode(events.getFirst().get("event_id").toString(), StandardCharsets.UTF_8) + "&citation_id=" + citation, "GET", null, 200);
                        assertEquals("no-store", objectMapper.readTree(Files.readString(evidence.resolve(citationFile + ".json"))).path("cacheControl").asText());
                        assertEquals(source, readable.path("source_version").asText());
                        assertTrue(readable.path("text").asText().contains(sentinel));
                        citationTexts.append(readable.path("text").asText());
                    }
                    assertTrue(citationTexts.toString().contains("已保存分块标记"));
                    if ("candidate".equals(mode)) assertTrue(citationTexts.toString().contains("未保存分块草稿"));
                    assertEquals(2, script.snapshot().requestCount());
                    assertEquals(2, script.snapshot().requests().size());
                    assertEquals(expectedCitations, script.snapshot().verifiedKnowledgeItemIds().size());
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?", tenantId));
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE' AND status='SUCCEEDED'", tenantId, actualRun));
                    assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
                    assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
                    write(evidence.resolve("result.json"), Map.of("mode", mode, "documentId", documentId, "oldSource", knowledge.sourceVersion(), "newSource", source,
                            "sessionId", sessionId, "runId", actualRun, "currentContent", expectedContent));
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
