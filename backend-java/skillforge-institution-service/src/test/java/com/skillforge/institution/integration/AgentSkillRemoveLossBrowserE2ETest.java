package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Skill removal response loss and publication boundary through real browser and Runtime. */
class AgentSkillRemoveLossBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-skill-remove-loss-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void removedSkillLostResponsePreservesPublishedRuntimeUntilPublication() throws Exception {
        login();
        String marker = "SKILL_OPTIONS_" + SnowflakeIds.nextId();
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
        Path evidence = Path.of("build/runtime-e2e/agent-skill-remove-loss-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("AGENT_SKILL_REMOVE_LOSS_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated service, PostgreSQL, browser and Runtime with controlled local HTTP model. Actual deletion returns 200 upstream then browser response is discarded. Draft and publication snapshots retained; fresh sessions tested before and after publication. No external supplier acceptance.", StandardCharsets.UTF_8);
        FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/agent-skill-remove-loss-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
        env.put("E2E_SKILL_MARKER", marker); env.put("E2E_AGENT_SKILL_REFRESH_MODE", "candidate"); env.put("E2E_USERNAME", "runtime-e2e-admin");
        env.put("E2E_SKILL_REMOVE_LOSS", System.getenv().getOrDefault("E2E_SKILL_REMOVE_LOSS", "baseline"));
        env.remove("E2E_SKILL_BUDGET"); env.remove("E2E_SKILL_RESOURCE_BUDGET");
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");

        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            boolean published = false;
            boolean checkedNotArrived = false;
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture();
                if (!published && Files.exists(browser.resolve("mounted.json"))) {
                    var response = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
                    Files.writeString(evidence.resolve("initial-publication.json"), response.body());
                    assertEquals(200, response.statusCode(), response.body());
                    published = true;
                    Files.writeString(browser.resolve("initial-published"), "ready");
                }
                if (!checkedNotArrived && Files.exists(browser.resolve("not-arrived-check.json"))) {
                    var pending = objectMapper.readTree(Files.readString(browser.resolve("not-arrived-check.json")));
                    assertEquals(1, pending.path("deletes").asInt());
                    assertEquals(0, pending.path("serverDeletes").asInt());
                    assertEquals(1, countRuntime("SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND revision=?",
                            tenantId, agentId, pending.path("mount").path("mount_id").asText(), pending.path("mount").path("revision").asLong()));
                    saveFacts(evidence, "not-arrived");
                    checkedNotArrived = true;
                    Files.writeString(browser.resolve("not-arrived-database-checked"), "checked");
                }
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture(); assertFalse(process.isAlive(), "browser timeout: " + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("result.json")));
        String definition = facts.path("definition").asText(), version = facts.path("version").asText();
        boolean notArrived = "not-arrived".equals(System.getenv("E2E_SKILL_REMOVE_LOSS"));
        assertEquals(notArrived ? 2 : 1, facts.path("deletes").asInt());
        assertEquals(1, facts.path("serverDeletes").asInt());
        if (!"baseline".equals(System.getenv().getOrDefault("E2E_SKILL_REMOVE_LOSS", "baseline"))) {
            assertTrue(facts.path("unsavedPromptPreserved").asBoolean());
            assertTrue(facts.path("checkReads").asInt() > 0);
        }
        assertEquals(0, countRuntime("SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)", tenantId, agentId));
        saveFacts(evidence, "after-delete");
        var draftDetail = objectMapper.readTree(Files.readString(evidence.resolve("after-delete-detail.json"))).path("data");
        assertTrue(draftDetail.path("has_draft_changes").asBoolean(), "Removal is a draft change until publication");
        if ("candidate".equals(System.getenv("E2E_SKILL_REMOVE_LOSS")))
            assertFalse(draftDetail.path("system_prompt").asText().contains("UNSAVED_REMOVE_"), "Read-only checking must not save the unsaved form");
        verifyRuntime(evidence, "before-publication", marker, definition, version, true);
        var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
        Files.writeString(evidence.resolve("removal-publication.json"), publication.body());
        assertEquals(200, publication.statusCode(), publication.body());
        saveFacts(evidence, "after-publication");
        verifyRuntime(evidence, "after-publication", marker, definition, version, false);
        assertEquals(2, provider.requestCount());
    }
    private void saveFacts(Path evidence, String phase) throws Exception {
        var detail = get("/api/v1/agents/detail?agentId=" + agentId);
        Files.writeString(evidence.resolve(phase + "-detail.json"), detail.body());
        assertEquals(200, detail.statusCode(), detail.body());
        Files.writeString(evidence.resolve(phase + "-database.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "agent", queryRows(jdbc, tenantId, "SELECT id,has_draft_changes FROM nw_agents WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint)", tenantId, agentId),
                "mounts", queryRows(jdbc, tenantId, "SELECT id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)", tenantId, agentId),
                "publications", queryRows(jdbc, tenantId, "SELECT id,revision,to_jsonb(p)::text AS publication FROM nw_agent_publications p WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) ORDER BY revision", tenantId, agentId))));
    }
    private void verifyRuntime(Path evidence, String phase, String marker, String definition, String version, boolean expected) throws Exception {
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        runId = "skill-remove-" + SnowflakeIds.nextId();
        var capture = postSse(chatPayload("Describe your capabilities briefly."));
        Files.writeString(evidence.resolve(phase + ".sse.txt"), capture.raw());
        assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
        String run = "run:" + runId;
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run));
        var rows = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED' ORDER BY a.action_ordinal", tenantId, run);
        assertEquals(1, rows.size()); var row = rows.getFirst();
        var properties = applicationContext.getBean(AgentRuntimeProperties.class);
        var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
        var request = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
        boolean actual = request.request().messages().stream().anyMatch(message -> message.textContent().contains(marker) && message.textContent().contains("Frozen capability: " + definition + " @ " + version));
        Files.writeString(evidence.resolve(phase + "-model-messages.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("run", run, "expectedSkill", expected, "actualSkill", actual, "messages", request.request().messages())));
        assertEquals(expected, actual);
    }
}
