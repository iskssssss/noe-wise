package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 名称输入边界与保存发布、原发布继续执行的真实闭环。 */
class AgentNameValidationBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-name-validation-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void boundedAgentNameCanBePublishedAndUsedByRuntime() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_AGENT_NAME_VALIDATION", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        String marker = "NAME_BOUNDARY_PROMPT_" + SnowflakeIds.nextId();
        Path evidence = Path.of("build/runtime-e2e/agent-name-validation-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        System.out.println("AGENT_NAME_VALIDATION_EVIDENCE=" + evidence);
        var before = successData(get("/api/v1/agents/detail?agentId=" + agentId));
        Files.writeString(evidence.resolve("agent-before.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(before));
        var negativeRequests = new ArrayList<Map<String, Object>>();
        try {
            if ("candidate".equals(mode)) {
                record InvalidRequest(String path, String method, Map<String, Object> body, String field) {}
                for (String invalidName : List.of("A".repeat(129), "😀".repeat(129))) {
                    Map<String, Object> payload = Map.of("name", invalidName);
                    var cases = List.of(
                        new InvalidRequest("/api/v1/agents", "POST", payload, "name"),
                        new InvalidRequest("/api/v1/agents/create-with-receipt", "POST", Map.of("request_id", UUID.randomUUID().toString(), "payload", payload), "payload.name"),
                        new InvalidRequest("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId, "payload", payload), "payload.name"),
                        new InvalidRequest("/api/v1/agents/save-and-publish", "POST", Map.of("agent_id", agentId, "request_id", UUID.randomUUID().toString(), "payload", payload), "payload.name"));
                    for (var item : cases) {
                        var response = send(item.path(), item.method(), item.body(), accessToken);
                        var body = objectMapper.readTree(response.body());
                        negativeRequests.add(Map.of("path", item.path(), "method", item.method(), "request", item.body(), "status", response.statusCode(), "response", body));
                        assertEquals(400, response.statusCode(), response.body());
                        assertTrue(body.path("field_errors").has(item.field()), response.body());
                        if ("/api/v1/agents/create-with-receipt".equals(item.path()))
                            assertEquals("AGENT_CREATE_REJECTED", body.path("code").asText());
                    }
                    assertEquals(0, countRuntime("SELECT count(*) FROM nw_agents WHERE tenant_id=CAST(? AS bigint) AND name=?", tenantId, invalidName));
                }
                var unchanged = successData(get("/api/v1/agents/detail?agentId=" + agentId));
                assertEquals(before, unchanged, "拒绝输入不得修改本体或发布身份");
                var validCreate = send("/api/v1/agents", "POST", Map.of("name", "😀".repeat(128)), accessToken);
                Files.writeString(evidence.resolve("valid-unicode-create.json"), validCreate.body());
                assertEquals(200, validCreate.statusCode(), validCreate.body());
                assertEquals("😀".repeat(128), successData(validCreate).path("name").asText());
            }
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            var command = new ProcessBuilder(cli.toString(), "test", "e2e/agent-name-validation-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true);
            command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_USERNAME", "runtime-e2e-admin"); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_AGENT_ID", agentId); env.put("E2E_AGENT_NAME_VALIDATION", mode);
            env.put("E2E_NAME_PROMPT_MARKER", marker); env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture();
                assertFalse(process.isAlive(), "Agent 名称浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var facts = objectMapper.readTree(Files.readString(browser.resolve("receipt.json")));
            String actualRun = facts.path("runId").asText();
            assertFalse(actualRun.isBlank());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun, agentId));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
            assertEquals(1, provider.requestCount());
            var after = successData(get("/api/v1/agents/detail?agentId=" + agentId));
            Files.writeString(evidence.resolve("agent-after.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(after));
            if ("baseline".equals(mode)) assertEquals(before, after, "非法名称保存失败后原发布配置应保持");
            else {
                assertEquals("😀".repeat(128), after.path("name").asText());
                assertTrue(after.path("system_prompt").asText().contains(marker));
                assertTrue(after.path("published_revision").asInt() > before.path("published_revision").asInt());
                assertEquals(facts.path("publication").path("publication_id").asText(), after.path("published_revision_id").asText());
            }
            var modelRows = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'", tenantId, actualRun);
            assertEquals(1, modelRows.size());
            var material = modelRows.getFirst();
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var actual = ModelRequestTestMaterials.decode(codec, new TypedPayload(material.get("schema_id").toString(), material.get("canonical_json").toString()), jdbc, tenantId);
            String expectedPrompt = "candidate".equals(mode) ? marker : before.path("system_prompt").asText();
            assertTrue(actual.request().messages().stream().anyMatch(message -> message.role() == ModelPort.ModelRole.SYSTEM && message.textContent().contains(expectedPrompt)));
            assertEquals("candidate".equals(mode), actual.request().messages().stream().anyMatch(message -> message.textContent().contains(marker)));
            Files.writeString(evidence.resolve("model-request.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modelRows));
        } finally {
            Files.writeString(evidence.resolve("api-validation.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(negativeRequests));
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "agents", queryRows(jdbc, tenantId, "SELECT id,name,revision,published_revision_id,system_prompt FROM nw_agents WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint)", tenantId, agentId),
                    "publications", queryRows(jdbc, tenantId, "SELECT id,agent_id,revision,request_id FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) ORDER BY revision LIMIT 20", tenantId, agentId),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? ORDER BY created_second LIMIT 20", tenantId, agentId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND r.agent_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, agentId),
                    "providerCalls", provider.requestCount())));
        }
    }
}
