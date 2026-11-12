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

/** 技能候选通过单次摘要读取后挂载并执行。 */
class AgentSkillOptionsBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-skill-options-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void skillMountUsesSinglePublishedOptions() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_AGENT_SKILL_OPTIONS", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        String marker = "SKILL_OPTIONS_" + SnowflakeIds.nextId();
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
        Path evidence = Path.of("build/runtime-e2e/agent-skill-options-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("AGENT_SKILL_OPTIONS_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离服务、数据库、浏览器、正式技能发布与 Agent Runtime；本地受控模型。基线真实目录响应200后丢弃，由 API 控制挂载；候选浏览器挂载。凭据及 trace 不落盘。", StandardCharsets.UTF_8);
        FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/agent-skill-options-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
        env.put("E2E_SKILL_MARKER", marker); env.put("E2E_AGENT_SKILL_OPTIONS", mode);
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("result.json")));
        String definition = facts.path("definition").asText(), version = facts.path("version").asText();
        assertFalse(definition.isBlank()); assertFalse(version.isBlank());
        String mount;
        if ("baseline".equals(mode)) {
            var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "SKILL", "capability_definition_id", definition, "capability_version_id", version, "skill_config", Map.of("token_budget", 8000), "priority", 0)), accessToken);
            Files.writeString(evidence.resolve("api-control-mount.json"), mounted.body()); assertEquals(201, mounted.statusCode(), mounted.body());
            mount = successData(mounted).path("mounts").get(0).path("mount_id").asText();
        } else mount = facts.path("mount").path("mount_id").asText();
        assertFalse(mount.isBlank());
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND capability_definition_id=CAST(? AS bigint) AND capability_version_id=CAST(? AS bigint) AND activation_mode='ALWAYS'", tenantId, agentId, mount, definition, version));
        var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
        Files.writeString(evidence.resolve("publication.json"), publication.body()); assertEquals(200, publication.statusCode(), publication.body());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        String run = "run:" + runId;
        try {
            var capture = postSse(chatPayload("请简短说明当前系统能力。"));
            Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
            assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, agentId, sessionId));
            var rows = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED' ORDER BY a.action_ordinal", tenantId, run);
            assertEquals(1, rows.size()); var row = rows.getFirst();
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var request = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            assertTrue(request.request().messages().stream().anyMatch(message -> message.textContent().contains(marker) && message.textContent().contains("Frozen capability: " + definition + " @ " + version) && message.textContent().contains("skill:" + mount)));
            assertEquals(1, provider.requestCount());
            Files.writeString(evidence.resolve("model-messages.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request.request().messages()));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("run", run, "session", sessionId, "mount", mount, "definition", definition, "version", version,
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.action_id,a.kind,a.status,t.result_kind,t.adopted FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal", tenantId, run))));
        }
    }
}
