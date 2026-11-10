package com.skillforge.institution.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** 平台发布的固定模型预算须能安装，修正后的同一模板进入真实 Runtime。 */
class AgentTemplateBudgetBrowserE2ETest extends ChatExecutionChainE2ETest {
    @Autowired Environment environment;
    @Autowired PlatformModelCatalogService models;

    @Test void templateBudgetValidationPrecedesInstallation() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_TEMPLATE_BUDGET", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path root = KnowledgeBrowserServer.repoRoot();
        Path evidence = Path.of("build/runtime-e2e/template-budget-" + runId).toAbsolutePath(); Files.createDirectories(evidence);
        System.out.println("TEMPLATE_BUDGET_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated platform JVM and institution Spring share this test's PostgreSQL/Redis. Platform browser edits, validates and publishes; institution HTTP installs; one local controlled HTTP model/Runtime executes the installed publication. Runtime verified by HTTP and persisted materials, not final browser rendering. No external supplier; credentials/trace/video excluded.");
        String username = "template-budget-" + UUID.randomUUID().toString().substring(0, 8);
        ensureTestPlatformAdministrator(username, PASSWORD);
        var configured = models.create(new ModelWrite(null, "模板预算验收模型", "LLM", true, "custom", "custom-openai-compatible", "custom-v1",
                "openai-chat-completions", "bearer-api-key", provider.endpoint(), "runtime-fixture", "runtime-fixture-key",
                4096, 32768, 2048, 0.2, "DISABLED", 30, null, null, null, null, null, "CNY"), "template-budget-fixture");
        models.publish(configured.id(), configured.revision(), "template-budget-fixture");
        String marker = "TEMPLATE_BUDGET_PROMPT_" + runId;
        Path jar = root.resolve("backend-java/skillforge-platform-service/build/libs/skillforge-platform-service.jar");
        assertTrue(Files.isRegularFile(jar), "专用任务须先构建当前平台 bootJar");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path platformLog = evidence.resolve("platform.log");
        var platformCommand = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-jar", jar.toString(), "--server.port=0", "--server.address=127.0.0.1",
                "--agent-runtime.workers.auto-startup=false", "--spring.flyway.enabled=false", "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false");
        platformCommand.directory(root.resolve("backend-java/skillforge-platform-service").toFile()).redirectErrorStream(true).redirectOutput(platformLog.toFile());
        var env = platformCommand.environment();
        env.put("SPRING_DATASOURCE_URL", environment.getRequiredProperty("spring.datasource.url"));
        env.put("PLATFORM_DATASOURCE_USERNAME", environment.getRequiredProperty("spring.datasource.username"));
        env.put("PLATFORM_DATASOURCE_PASSWORD", environment.getRequiredProperty("spring.datasource.password"));
        env.put("REDIS_HOST", environment.getRequiredProperty("spring.data.redis.host")); env.put("REDIS_PORT", environment.getRequiredProperty("spring.data.redis.port"));
        env.put("REDIS_DATABASE", environment.getProperty("spring.data.redis.database", "0"));
        env.put("SECRET_KEY", environment.getRequiredProperty("skillforge.secret-key")); env.put("PLATFORM_SECRET_KEY", TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME", username); env.put("PLATFORM_ADMIN_PASSWORD", PASSWORD);
        Process platform = platformCommand.start();
        String[] platformUrl = { null };
        String[] definitionId = { null };
        try (var platformOwned = new KnowledgeBrowserProcesses(platform.toHandle())) {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() -> {
                platformOwned.capture(); assertTrue(platform.isAlive(), "平台进程退出：" + platformLog);
                var listening = Pattern.compile("Tomcat started on port (\\d+)").matcher(Files.readString(platformLog, StandardCharsets.UTF_8));
                if (!listening.find()) return false;
                platformUrl[0] = "http://127.0.0.1:" + listening.group(1);
                var health = httpClient.send(HttpRequest.newBuilder(URI.create(platformUrl[0] + "/actuator/health")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                return health.statusCode() == 200;
            });
            var authenticated = platformCall(platformUrl[0], null, "POST", "/api/v1/platform/auth/login", Map.of("username", username, "password", PASSWORD), null);
            assertEquals(200, authenticated.statusCode(), "平台登录失败");
            String token = successData(authenticated).path("access_token").asText(); assertFalse(token.isBlank());
            var createBody = Map.of("name", "模板发布预算验收", "price_type", "free", "price_cents", 0, "distribution_policy", "OPEN_INSTALL", "definition", Map.of(
                    "system_prompt", "请简短回答。" + marker, "role_description", "预算闭环验收助手", "model_config_id", configured.id(), "execution_config", Map.of("max_total_tokens", 20000),
                    "capabilities", List.of(), "resource_slots", List.of(), "delegation_slots", List.of()));
            var created = platformCall(platformUrl[0], token, "POST", "/api/v1/platform/agent-definitions", createBody, UUID.randomUUID().toString());
            Files.writeString(evidence.resolve("created-template.json"), created.body()); assertEquals(200, created.statusCode(), created.body());
            definitionId[0] = successData(created).path("definition_id").asText(); assertFalse(definitionId[0].isBlank());
            var fixture = new java.util.LinkedHashMap<String, Object>();
            fixture.put("mode", mode); fixture.put("platform", platformUrl[0]); fixture.put("institution", baseUrl());
            fixture.put("platformToken", token); fixture.put("institutionToken", accessToken); fixture.put("username", username); fixture.put("password", PASSWORD);
            fixture.put("definition", definitionId[0]); fixture.put("evidence", evidence.toString());
            var browserCommand = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), root.resolve("frontend-platform/e2e/template-budget-browser.mjs").toString());
            browserCommand.directory(root.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());
            browserCommand.environment().put("TEMPLATE_BUDGET_FIXTURE", objectMapper.writeValueAsString(fixture));
            browserCommand.environment().remove("SDK4_LIVE_MODEL_KEY"); browserCommand.environment().remove("E2E_LIVE_MODEL");
            Process browser = browserCommand.start();
            try (var owned = new KnowledgeBrowserProcesses(browser.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (browser.isAlive() && System.nanoTime() < deadline) { owned.capture(); platformOwned.capture(); browser.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(browser.isAlive(), "模板预算浏览器超时：" + evidence);
                assertEquals(0, browser.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
            }
            var result = objectMapper.readTree(Files.readString(evidence.resolve("result.json")));
            agentId = result.path("agent").asText(); assertFalse(agentId.isBlank());
            var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
            var input = chatPayload("请确认模板修正预算后的实际执行。");
            Files.writeString(evidence.resolve("runtime-input.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input));
            var captured = postSse(input); Files.writeString(evidence.resolve("runtime-sse.txt"), captured.raw());
            assertEquals(200, captured.statusCode()); assertTrue(captured.events().contains("execution.completed"), captured.raw());
            assertFalse(captured.events().contains("error"), captured.raw()); assertTrue(captured.raw().contains("fixture-answer-" + runId), captured.raw());
            assertEquals(1, provider.requestCount());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, agentId));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId));
            assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND state<>'SETTLED'", tenantId));
            var materials = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.kind='MODEL'", tenantId);
            assertEquals(1, materials.size()); var row = materials.getFirst();
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var request = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            Files.writeString(evidence.resolve("model-messages.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request.request().messages()));
            assertTrue(request.request().messages().stream().anyMatch(message -> message.role() == ModelRole.SYSTEM && message.textContent().contains(marker)));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "template", definitionId[0] == null ? List.of() : jdbc.queryForList("SELECT id,version,status,draft_revision,has_unpublished_changes,install_count,definition::text AS definition FROM nw_agent_templates WHERE id=CAST(? AS bigint)", definitionId[0]),
                    "publications", definitionId[0] == null ? List.of() : jdbc.queryForList("SELECT template_id,version FROM nw_agent_template_publications WHERE template_id=CAST(? AS bigint) ORDER BY version LIMIT 5", definitionId[0]),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? LIMIT 5", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 5", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 5", tenantId),
                    "providerCalls", provider.requestCount())));
        }
    }

    private HttpResponse<String> platformCall(String origin, String token, String method, String path, Map<String, ?> body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("X-Platform-Token", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return httpClient.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
