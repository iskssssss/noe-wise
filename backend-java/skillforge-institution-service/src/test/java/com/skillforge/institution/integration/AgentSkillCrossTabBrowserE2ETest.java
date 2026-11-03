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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 同一浏览器会话跨标签发布技能，再由真实 Agent 运行时消费该版本。 */
class AgentSkillCrossTabBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND =
            KnowledgeBrowserServer.startIsolated("agent-skill-cross-tab-");

    @DynamicPropertySource
    static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll
    static void closeFrontend() {
        FRONTEND.close();
    }

    @Test
    void publishedSkillAppearsInOriginalMountDrawerAndRuntime() throws Exception {
        login();
        String marker = "CROSS_TAB_SKILL_" + SnowflakeIds.nextId();
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body());
        agentId = successData(revised).path("id").asText();
        Path evidence = Path.of("build", "runtime-e2e", "agent-skill-cross-tab-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright");
        Files.createDirectories(browser);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path playwright = admin.resolve("node_modules/.bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
        assertTrue(Files.isRegularFile(playwright));
        FRONTEND.bindBackend(baseUrl());
        var command = new ProcessBuilder(playwright.toString(), "test", "e2e/agent-skill-cross-tab-real.spec.ts");
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        env.put("E2E_USERNAME", "runtime-e2e-admin");
        env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_AGENT_ID", agentId);
        env.put("E2E_SKILL_MARKER", marker);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId);
        env.remove("SDK4_LIVE_MODEL_KEY");
        env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture();
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture();
            assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            String output = Files.readString(evidence.resolve("playwright.log"));
            if (process.exitValue() != 0) {
                var state = Map.of(
                        "mounts", queryRows(jdbc, tenantId,
                                "SELECT id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)",
                                tenantId, agentId),
                        "runs", queryRows(jdbc, tenantId,
                                "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?",
                                tenantId, agentId));
                Files.writeString(evidence.resolve("skill-browser-failure-state.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
            assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence + "\n" + output);
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("skill-cross-tab.json")));
        String definition = facts.path("definition").asText();
        String version = facts.path("version").asText();
        String mount = facts.path("mount").path("mount_id").asText();
        assertEquals(agentId, facts.path("agentId").asText());
        assertFalse(definition.isBlank());
        assertFalse(version.isBlank());
        assertFalse(mount.isBlank());
        assertEquals(1, facts.path("skillPublishes").asInt());
        assertEquals(1, facts.path("mountPosts").asInt());
        String mode = System.getenv().getOrDefault("E2E_AGENT_SKILL_REFRESH_MODE", "candidate");
        assertEquals(mode, facts.path("mode").asText());
        assertEquals("baseline".equals(mode) ? 1 : 0, facts.path("manualRefreshClicks").asInt());
        assertEquals(1, countRuntime(
                "SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND capability_definition_id=CAST(? AS bigint) AND capability_version_id=CAST(? AS bigint) AND activation_mode='ALWAYS'",
                tenantId, agentId, mount, definition, version));
        String budgetMode = System.getenv("E2E_SKILL_BUDGET");
        boolean budgetExperiment = java.util.Set.of("baseline", "candidate").contains(budgetMode == null ? "" : budgetMode);
        String actualRun;
        if (budgetExperiment) {
            sessionId = facts.path("trialSession").asText(); actualRun = facts.path("trialRun").asText();
            assertFalse(sessionId.isBlank()); assertFalse(actualRun.isBlank());
            assertEquals("baseline".equals(budgetMode) ? 1 : facts.path("skillEstimate").asInt(), facts.path("mount").path("skill_config").path("token_budget").asInt());
        } else {
            var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, publication.statusCode(), publication.body());
            var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, session.statusCode(), session.body());
            sessionId = successData(session).path("session_id").asText(); actualRun = "run:" + runId;
        }
        try {
            var capture = budgetExperiment ? observeSseUntil(sessionId, null, actualRun, java.util.Set.of("execution.completed", "error"))
                    : postSse(chatPayload("请简短说明当前系统能力。"));
            assertEquals(200, capture.statusCode());
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertFalse(capture.events().contains("error"), capture.raw());
            assertEquals(1, countRuntime(
                    "SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'",
                    tenantId, actualRun, agentId, sessionId));
            var rows = queryRows(jdbc, tenantId,
                    "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED'",
                    tenantId, actualRun);
            assertEquals(1, rows.size());
            var row = rows.getFirst();
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(
                    properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var request = ModelRequestTestMaterials.decode(codec,
                    new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            boolean hasSkillBody = request.request().messages().stream().anyMatch(message ->
                    message.textContent().contains(marker) && message.textContent().contains("Frozen capability: " + definition + " @ " + version)
                            && message.textContent().contains("skill:" + mount));
            if (budgetExperiment) {
                assertEquals("candidate".equals(budgetMode), hasSkillBody, "冻结预算决定是否注入技能正文");
                var publishedSkill = successData(get("/api/v1/capabilities/versions/detail?versionId=" + version));
                long estimatedTokens = publishedSkill.path("descriptor").path("estimated_tokens").asLong();
                assertTrue(estimatedTokens > 1, publishedSkill.toString());
                Files.writeString(evidence.resolve("skill-budget-experiment.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "agentId", agentId, "runId", actualRun, "mountId", mount, "versionId", version, "tokenBudget", facts.path("mount").path("skill_config").path("token_budget").asInt(),
                        "estimatedTokens", estimatedTokens, "skillBodyInModel", hasSkillBody,
                        "userBudgetExplanationVisible", facts.path("userBudgetExplanationVisible").asBoolean(),
                        "modelMessages", request.request().messages())));
            } else assertTrue(hasSkillBody, "实际模型上下文必须包含同一挂载的精确版本与包入口正文");
            assertEquals(1, provider.requestCount());
            Files.writeString(evidence.resolve("skill-runtime.json"), objectMapper.writeValueAsString(Map.of(
                    "agentId", agentId, "runId", actualRun, "mountId", mount, "definitionId", definition,
                    "versionId", version, "skillContextVerified", hasSkillBody, "modelCalls", provider.requestCount())));
        } finally {
            var state = Map.of(
                    "runs", queryRows(jdbc, tenantId,
                            "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun),
                    "actions", queryRows(jdbc, tenantId,
                            "SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal", tenantId, actualRun),
                    "attempts", queryRows(jdbc, tenantId,
                            "SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
            Files.writeString(evidence.resolve("skill-runtime-state.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        }
    }
}
