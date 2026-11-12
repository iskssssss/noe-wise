package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 已发布配置未变时不产生伪草稿，并使用相同发布版本运行。 */
class AgentNoopSaveBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-noop-save-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void unchangedPublishedAgentDoesNotNeedDraftSave() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_AGENT_NOOP_SAVE", "candidate");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/agent-noop-save-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("AGENT_NOOP_SAVE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离 Spring、PostgreSQL、真实管理浏览器和 Runtime；模型为受控本地 HTTP。候选额外发送完整同值配置 API 控制，不将独立 API 请求归因于浏览器。凭据、trace 与 video 不落盘。", java.nio.charset.StandardCharsets.UTF_8);
        var beforeResponse = get("/api/v1/agents/detail?agentId=" + agentId); assertEquals(200, beforeResponse.statusCode());
        var before = successData(beforeResponse); Files.writeString(evidence.resolve("before.json"), beforeResponse.body());
        assertEquals("published", before.path("status").asText()); assertFalse(before.path("has_draft_changes").asBoolean());
        long revision = before.path("published_revision").asLong(); assertTrue(revision > 0);
        String prompt = before.path("system_prompt").asText(); assertFalse(prompt.isBlank());
        FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/agent-noop-save-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
        env.put("E2E_AGENT_NOOP_SAVE", mode); env.put("E2E_PUBLISHED_REVISION", Long.toString(revision));
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
        }
        var afterResponse = get("/api/v1/agents/detail?agentId=" + agentId); assertEquals(200, afterResponse.statusCode());
        var after = successData(afterResponse); Files.writeString(evidence.resolve("after-browser.json"), afterResponse.body());
        assertEquals("baseline".equals(mode), after.path("has_draft_changes").asBoolean());
        assertEquals(revision, after.path("published_revision").asLong()); assertEquals(prompt, after.path("system_prompt").asText());
        if ("candidate".equals(mode)) {
            var payload = objectMapper.createObjectNode();
            payload.put("name", before.path("name").asText());
            payload.put("role_description", before.path("role_description").asText(""));
            payload.put("system_prompt", prompt);
            payload.put("model_config_id", before.path("model_config_id").asText(""));
            payload.set("execution_config", before.path("execution_config").deepCopy());
            payload.set("delegation_config", before.path("delegation_config").deepCopy());
            var config = before.path("config").isObject()
                    ? ((com.fasterxml.jackson.databind.node.ObjectNode) before.path("config")).deepCopy()
                    : objectMapper.createObjectNode();
            var memory = config.path("memory").isObject()
                    ? ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("memory")).deepCopy()
                    : objectMapper.createObjectNode();
            memory.put("automatic_extraction", before.path("config").path("memory").path("automatic_extraction").asBoolean(false));
            config.set("memory", memory);
            payload.set("config", config);
            Files.writeString(evidence.resolve("api-same-value-request.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
            var control = send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId, "payload", payload), accessToken);
            Files.writeString(evidence.resolve("api-same-value.json"), control.body()); assertEquals(200, control.statusCode(), control.body());
            var afterControl = get("/api/v1/agents/detail?agentId=" + agentId); assertEquals(200, afterControl.statusCode());
            Files.writeString(evidence.resolve("after-api-control.json"), afterControl.body());
            assertEquals(revision, successData(afterControl).path("published_revision").asLong());
            assertFalse(successData(afterControl).path("has_draft_changes").asBoolean());
        }
        String run = "run:" + runId;
        var capture = postSse(chatPayload("请简短回答配置验证问题。"));
        Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
        assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, agentId, sessionId));
        var rows = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED'", tenantId, run);
        assertEquals(1, rows.size()); var row = rows.getFirst();
        var properties = applicationContext.getBean(AgentRuntimeProperties.class);
        var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
        var actual = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
        assertTrue(actual.request().messages().stream().anyMatch(message -> message.role() == ModelRole.SYSTEM && message.textContent().contains(prompt)));
        assertEquals(1, provider.requestCount());
        var publications = queryRows(jdbc, tenantId, "SELECT id,revision FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) ORDER BY revision", tenantId, agentId);
        Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("agent", agentId, "session", sessionId, "run", run, "publications", publications, "modelCalls", provider.requestCount(), "modelMessages", actual.request().messages())));
        assertEquals(1, publications.size()); assertEquals(revision, ((Number) publications.getFirst().get("revision")).longValue());
    }
}
