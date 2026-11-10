package com.skillforge.institution.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URLEncoder;
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

/** 真实知识执行产生的引用可从管理会话记录读取，不重放原执行。 */
class SessionCitationBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("session-citation-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void sessionHistoryCanOpenRuntimeKnowledgeCitation() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_SESSION_CITATION", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/session-citation-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        System.out.println("SESSION_CITATION_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, economical keyword index, published knowledge, local controlled HTTP model, Runtime and admin browser. One original execution; citation/history reads do not replay it. No external supplier. Credentials, trace, video and automatic screenshots excluded.");
        try (var model = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start();
            TenantContext.runWithTenant(tenantId, () -> configureTenantModel(model.endpoint()));
            String sentinel = "sessioncitation" + SnowflakeIds.nextId();
            var knowledge = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                    .publish(runId, sentinel, "会话引用验收原文：" + sentinel + "。这段内容必须来自已发布的原始版本。",
                            Map.of("indexing_mode", "economical", "retrieval_mode", "full_text"), evidence);
            Files.writeString(evidence.resolve("knowledge.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(knowledge));
            var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, revised.statusCode(), revised.body());
            agentId = successData(revised).path("id").asText();
            var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                    "capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", Long.toString(knowledge.definitionId()),
                    "priority", 0, "knowledge_config", Map.of("mode", "FOLLOW_ACTIVE"))), accessToken);
            Files.writeString(evidence.resolve("mount.json"), mount.body());
            assertEquals(201, mount.statusCode(), mount.body());
            String handle = "knowledge:" + successData(mount).path("mounts").get(0).path("mount_id").asText();
            var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
            Files.writeString(evidence.resolve("agent-publication.json"), publication.body());
            assertEquals(200, publication.statusCode(), publication.body());
            var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, session.statusCode(), session.body());
            sessionId = successData(session).path("session_id").asText();
            String scriptKey = "session-citation-" + SnowflakeIds.nextId();
            var script = model.register(scriptKey, RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge(handle, knowledge.kbId(), knowledge.sourceVersion(), sentinel);
            try {
                var input = chatPayload(script.prompt());
                Files.writeString(evidence.resolve("input.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input));
                var accepted = send("/api/v1/chat", "POST", input, accessToken);
                Files.writeString(evidence.resolve("accepted.json"), accepted.body());
                assertEquals(202, accepted.statusCode(), accepted.body());
                String actualRun = successData(accepted).path("execution_id").asText();
                assertFalse(actualRun.isBlank());
                await().atMost(Duration.ofSeconds(120)).until(() -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status IN ('COMPLETED','FAILED','CANCELLED','TERMINATED')", tenantId, actualRun) == 1);
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
                var events = queryRows(jdbc, tenantId, "SELECT event_id,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun);
                assertEquals(1, events.size());
                String message = events.getFirst().get("event_id").toString();
                var completion = objectMapper.readTree(events.getFirst().get("canonical_json").toString());
                Files.writeString(evidence.resolve("completion.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(events));
                assertEquals(1, completion.path("evidence").size());
                String citation = completion.path("evidence").get(0).path("citationId").asText();
                assertFalse(citation.isBlank());
                String citationPath = "/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id="
                        + URLEncoder.encode(message, StandardCharsets.UTF_8) + "&citation_id=" + citation;
                var readable = get(citationPath);
                Files.writeString(evidence.resolve("citation-probe.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "path", citationPath, "status", readable.statusCode(), "cacheControl", readable.headers().firstValue("Cache-Control").orElse(""),
                        "body", objectMapper.readTree(readable.body()))));
                assertEquals(200, readable.statusCode(), readable.body());
                assertEquals("no-store", readable.headers().firstValue("Cache-Control").orElse(""));
                assertEquals(knowledge.sourceVersion(), successData(readable).path("source_version").asText());
                assertTrue(successData(readable).path("text").asText().contains(sentinel));
                Files.writeString(evidence.resolve("reference.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "mode", mode, "agentId", agentId, "sessionId", sessionId, "runId", actualRun, "messageId", message,
                        "citationId", citation, "sourceVersion", knowledge.sourceVersion(), "sentinel", sentinel)));
                FRONTEND.bindBackend(baseUrl());
                Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
                var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                        "test", "e2e/session-citation-real.spec.ts", "--output", browser.resolve("runner-output").toString());
                command.directory(admin.toFile());
                command.redirectErrorStream(true);
                command.redirectOutput(evidence.resolve("playwright.log").toFile());
                var env = command.environment();
                env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
                env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
                env.put("E2E_SESSION_CITATION", mode); env.put("E2E_SESSION_ID", sessionId);
                env.put("E2E_MESSAGE_ID", message); env.put("E2E_CITATION_ID", citation);
                env.put("E2E_SOURCE_VERSION", knowledge.sourceVersion()); env.put("E2E_SENTINEL", sentinel);
                env.put("E2E_EXPECTED_REPLY", "runtime-workload-completed-" + scriptKey);
                env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
                Process process = command.start();
                try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                    owned.capture();
                    assertFalse(process.isAlive(), "会话引用浏览器超时：" + evidence);
                    assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
                }
                assertEquals(2, script.snapshot().requestCount());
                assertEquals(2, script.snapshot().requests().size());
                assertEquals(1, script.snapshot().verifiedKnowledgeItemIds().size());
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?", tenantId));
                assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE' AND status='SUCCEEDED'", tenantId, actualRun));
                assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
                assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
            } finally {
                Files.writeString(evidence.resolve("model.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(script.snapshot()));
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? ORDER BY created_second LIMIT 10", tenantId),
                        "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId),
                        "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 10", tenantId))));
            }
        }
    }
}
