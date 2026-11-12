package com.skillforge.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentRunner;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.platform.PlatformApplication;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.port.ModelPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 系统 Agent 挂载采用技能正式发布的激活方式，并执行到实际模型输入。 */
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"skillforge.platform-admin-username=", "skillforge.platform-admin-password="})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SystemAgentSkillActivationBrowserE2ETest {
    private static final String USERNAME = "skill-activation-admin";
    private static final String PASSWORD = "PlatformIntegration123";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("system_skill_activation").withUsername("skillforge").withPassword("test-only");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("skillforge.secret-key", () -> "platform-integration-shared-model-secret");
        registry.add("sa-token.jwt-secret-key", () -> "platform-integration-jwt-secret-key");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired FoundationOrganizationService organizations;
    @Autowired SystemAgentExecution execution;
    @MockitoSpyBean ModelProviderRuntime provider;
    @MockitoSpyBean SystemAgentRunner runner;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test void publishedSkillActivationControlsSystemAgentRuntime() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_SYSTEM_AGENT_SKILL_ACTIVATION", "candidate");
        assertThat(mode).isIn("baseline", "candidate");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("frontend-platform/package.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        Path evidence = Path.of("build/runtime-e2e/system-agent-skill-activation-" + UUID.randomUUID()).toAbsolutePath();
        Path browserEvidence = evidence.resolve("browser");
        Files.createDirectories(browserEvidence);
        System.out.println("SYSTEM_AGENT_SKILL_ACTIVATION_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "真实隔离 Spring/PostgreSQL/Redis、Vite、浏览器和管理 API，实际系统 Agent/Runtime 执行。仅 ModelProviderRuntime.stream 的外部模型传输由进程内受控 Publisher 替换，不是 HTTP 模型或外部供应商验收。记录实际 InvocationRequest 的 messages、模型身份、响应及计数；不保存 credential/Authorization。基线只观察一次调试的真实受理与拒绝原因，不把待核对伪装成完成。截图仅登录后；trace/video 关闭。");
        var calls = new AtomicInteger();
        var requests = new CopyOnWriteArrayList<ModelProvider.InvocationRequest>();
        var admissions = new CopyOnWriteArrayList<String>();
        String marker = "ALWAYS_SKILL_BODY_" + UUID.randomUUID().toString().replace("-", "");
        String reply = "SYSTEM_SKILL_COMPLETED_" + marker;
        org.mockito.Mockito.doAnswer(invocation -> {
            ModelProvider.InvocationRequest request = invocation.getArgument(1);
            requests.add(request);
            return (Flow.Publisher<ModelProvider.StreamEvent>) subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                boolean sent;
                @Override public void request(long count) {
                    if (sent || count <= 0) return;
                    sent = true;
                    request.beforeSend().run();
                    calls.incrementAndGet();
                    subscriber.onNext(new ModelProvider.StreamEvent.Completed(new ModelProvider.InvocationResult.Completed(
                            reply, List.of(), request.modelId(), new ModelPort.ModelUsage(java.util.OptionalLong.of(12), java.util.OptionalLong.of(8)), "stop")));
                    subscriber.onComplete();
                }
                @Override public void cancel() { sent = true; }
            });
        }).when(provider).stream(org.mockito.ArgumentMatchers.eq("deepseek"), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            catch (Throwable failure) {
                Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
                admissions.add(cause instanceof com.skillforge.runtime.api.RuntimeAccessException denied
                        ? denied.reasonCode() : cause.getClass().getSimpleName() + ":" + cause.getMessage());
                throw failure;
            }
        }).when(runner).execute(org.mockito.ArgumentMatchers.any());
        String agentId = null;
        var owned = new LinkedHashSet<ProcessHandle>();
        try {
            assertThat(organizations.initializePlatformAdministrator("991004", () -> jdbc.update("""
                    INSERT INTO nw_platform_members(id,username,password_hash,display_name,status,created_at,updated_at)
                    VALUES(991004,?,?,'Skill activation fixture','active',now(),now())
                    """, USERNAME, passwords.encode(PASSWORD)))).isTrue();
            String token = data(request("POST", "/api/v1/platform/auth/login", null, Map.of("username", USERNAME, "password", PASSWORD))).path("access_token").asText();
            var model = data(request("POST", "/api/v1/admin/models", token, Map.ofEntries(
                    Map.entry("name", "技能激活验收模型"), Map.entry("kind", "LLM"), Map.entry("enabled", true),
                    Map.entry("provider_id", "deepseek"), Map.entry("service_id", "deepseek-api"), Map.entry("profile_version", "2026-09-13"),
                    Map.entry("protocol", "openai-chat-completions"), Map.entry("authentication_scheme", "bearer-api-key"),
                    Map.entry("endpoint", "https://api.deepseek.com"), Map.entry("model_id", "system-test"), Map.entry("api_key", "synthetic-unused-fixture-key"),
                    Map.entry("supported_max_output_tokens", 4096), Map.entry("context_window_tokens", 32000), Map.entry("max_output_tokens", 2048),
                    Map.entry("reasoning", "DISABLED"), Map.entry("timeout_seconds", 30), Map.entry("currency", "CNY"))));
            var modelPublished = request("POST", "/api/v1/admin/models/publish", token, Map.of("id", model.path("id").asText(), "expected_revision", 1));
            data(modelPublished);
            Files.writeString(evidence.resolve("model-publication.json"), modelPublished.body());
            String drafts = "/api/v1/admin/capabilities/skill-drafts";
            var skill = data(request("POST", drafts + "/new", token, Map.of("capability_key", "always-browser-skill", "name", "始终激活验收技能", "description", "系统技能正文验收")));
            String content = "---\nname: always-browser-skill\ndescription: Always active browser fixture\n---\n" + marker;
            var skillWrite = Map.of("request_id", UUID.randomUUID().toString(), "draft_id", skill.path("id").asText(), "revision", skill.path("revision").asLong(),
                    "definition_revision", 1, "version_key", "v1", "files", Map.of("SKILL.md", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))),
                    "model_invocable", true, "user_invocable", true, "activation_mode", "ALWAYS");
            var publishedSkill = request("POST", drafts + "/save-and-publish", token, skillWrite);
            Files.writeString(evidence.resolve("skill-publication.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("request", skillWrite, "status", publishedSkill.statusCode(), "response", json.readTree(publishedSkill.body()))));
            assertThat(data(publishedSkill).path("status").asText()).isEqualTo("SUCCEEDED");
            var created = data(request("POST", "/api/v1/admin/system-agents", token, Map.of("agent_key", "skill-activation-browser", "name", "技能激活验收助手", "execution_mode", "AGENT_RUN")));
            agentId = created.path("id").asText();
            var config = (com.fasterxml.jackson.databind.node.ObjectNode) created.path("draft").deepCopy();
            config.put("model_config_id", model.path("id").asText()).put("system_prompt", "请依据已激活技能回答本次输入。").put("max_business_model_calls", 1);
            var prepared = request("PUT", "/api/v1/admin/system-agents/update", token, Map.of("agent_id", agentId, "expected_revision", created.path("revision").asLong(), "config", config));
            data(prepared);
            Files.writeString(evidence.resolve("prepared-agent.json"), prepared.body());
            Path frontend = root.resolve("frontend-platform");
            Path viteLog = evidence.resolve("vite.log");
            var viteBuilder = new ProcessBuilder("node", frontend.resolve("node_modules/vite/bin/vite.js").toString(), "--host", "127.0.0.1", "--port", "0", "--strictPort");
            viteBuilder.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
            viteBuilder.environment().put("E2E_BACKEND_URL", "http://127.0.0.1:" + port);
            viteBuilder.environment().put("NO_COLOR", "1");
            Process vite = viteBuilder.start();
            owned.add(vite.toHandle());
            String origin = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (System.nanoTime() < deadline && vite.isAlive()) {
                vite.descendants().forEach(owned::add);
                var match = Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/").matcher(Files.readString(viteLog));
                if (match.find()) {
                    origin = "http://127.0.0.1:" + match.group(1);
                    var health = http.send(HttpRequest.newBuilder(URI.create(origin)).version(HttpClient.Version.HTTP_1_1)
                            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (health.statusCode() == 200) break;
                    origin = null;
                }
                Thread.sleep(100);
            }
            assertThat(origin).as("Vite 未就绪：" + viteLog).isNotNull();
            var browser = new ProcessBuilder("node", frontend.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "--config=playwright.system-agent-skill-activation.config.ts");
            browser.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = browser.environment();
            env.put("E2E_BASE_URL", origin); env.put("E2E_ARTIFACT_DIR", browserEvidence.toString());
            env.put("E2E_USERNAME", USERNAME); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
            env.put("E2E_SKILL_ID", skill.path("definition_id").asText()); env.put("E2E_EXPECTED_REPLY", reply);
            env.put("E2E_SYSTEM_AGENT_SKILL_ACTIVATION", mode);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = browser.start(); owned.add(process.toHandle());
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                process.descendants().forEach(owned::add); vite.descendants().forEach(owned::add);
                if ("candidate".equals(mode)) execution.recoverDebugRuns();
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            process.descendants().forEach(owned::add);
            assertThat(process.isAlive()).as("浏览器超时：" + evidence).isFalse();
            assertThat(process.exitValue()).as("浏览器失败：%s\n%s", evidence, Files.readString(evidence.resolve("playwright.log"))).isZero();
            var versionsResponse = request("GET", "/api/v1/admin/system-agents/versions?agentId=" + agentId, token, null);
            Files.writeString(evidence.resolve("published-versions.json"), versionsResponse.body());
            var versions = data(versionsResponse);
            assertThat(versions.size()).isEqualTo(1);
            assertThat(versions.get(0).path("config").path("mounts").get(0).path("activation_mode").asText())
                    .isEqualTo("candidate".equals(mode) ? "ALWAYS" : "ON_DEMAND");
            var result = json.readTree(Files.readString(browserEvidence.resolve("browser-result.json")));
            String runtimeRun = "system-agent-run:" + result.path("debug").path("id").asText();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs WHERE agent_id=CAST(? AS bigint)", Long.class, agentId)).isEqualTo(1);
            if ("baseline".equals(mode)) {
                assertThat(calls.get()).isZero(); assertThat(requests).isEmpty();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_execution_runs WHERE run_id=?", Long.class, runtimeRun)).isZero();
                assertThat(admissions).anyMatch(reason -> reason.contains("skill_activation_rejected"));
            } else {
                assertThat(calls.get()).isEqualTo(1); assertThat(requests).hasSize(1);
                assertThat(requests.getFirst().messages()).anyMatch(message -> message.textContent().contains(marker));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_execution_runs WHERE run_id=? AND status='COMPLETED' AND phase='FINISHED'", Long.class, runtimeRun)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_execution_actions WHERE run_id=? AND kind='MODEL' AND status='SUCCEEDED'", Long.class, runtimeRun)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_execution_attempts WHERE run_id=? AND adopted AND result_kind='SUCCEEDED'", Long.class, runtimeRun)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_budget_attempts WHERE run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", Long.class, runtimeRun)).isEqualTo(1);
            }
        } finally {
            for (var handle : List.copyOf(owned)) if (handle.isAlive()) handle.descendants().forEach(owned::add);
            for (var handle : owned) if (handle.isAlive()) handle.destroy();
            for (var handle : owned) if (handle.isAlive()) handle.destroyForcibly();
            for (var handle : owned) if (handle.isAlive()) handle.onExit().get(10, TimeUnit.SECONDS);
            var modelEvidence = requests.stream().map(request -> Map.of("modelId", request.modelId(), "profile", request.profile(), "messages", request.messages(), "response", request.response(), "tools", request.tools(), "toolChoice", request.toolChoice())).toList();
            Files.writeString(evidence.resolve("model-observations.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("calls", calls.get(), "requests", modelEvidence, "reply", reply, "skillMarker", marker, "runnerFailures", admissions)));
            Files.writeString(evidence.resolve("runtime.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "agent", agentId == null ? List.of() : jdbc.queryForList("SELECT id,status,revision,published_version,draft::text AS draft FROM nw_platform_system_agents WHERE id=CAST(? AS bigint)", agentId),
                    "debugRuns", agentId == null ? List.of() : jdbc.queryForList("SELECT to_jsonb(r)::text AS value FROM nw_platform_system_agent_runs r WHERE agent_id=CAST(? AS bigint) LIMIT 5", agentId),
                    "runs", jdbc.queryForList("SELECT run_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id='900000000000000001' LIMIT 5"),
                    "actions", jdbc.queryForList("SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id='900000000000000001' LIMIT 10"),
                    "budgets", jdbc.queryForList("SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id='900000000000000001' LIMIT 10"))));
        }
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        var envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        return envelope.path("data");
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("X-Platform-Token", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
