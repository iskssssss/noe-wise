package com.skillforge.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.platform.PlatformApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 系统任务与外部调用账本；实时供应商验收必须显式开启。 */
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SystemAgentModelOutcomeIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("system_agent_outcome").withUsername("skillforge").withPassword("test-only");
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
    final HttpClient http = HttpClient.newHttpClient();

    /** 真实供应商的系统用途调试；不宣称触发业务 Runtime 的压缩或冷却。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_CONTEXT_SUMMARY", matches = "1")
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void liveDeepSeekSummarizesThirtySevenSyntheticTurns() throws Exception {
        assertThat(organizations.initializePlatformAdministrator("991002", () -> jdbc.update("""
                INSERT INTO nw_platform_members(id,username,password_hash,display_name,status,created_at,updated_at)
                VALUES(991002,'summary-live-admin',?,'Summary live','active',now(),now())
                """, passwords.encode("SummaryLiveFixture123")))).isTrue();
        String token = data(request("POST", "/api/v1/platform/auth/login", null,
                Map.of("username", "summary-live-admin", "password", "SummaryLiveFixture123"))).path("access_token").asText();
        var config = Map.ofEntries(
                Map.entry("name", "Context summary live"), Map.entry("kind", "LLM"), Map.entry("enabled", true),
                Map.entry("provider_id", "deepseek"), Map.entry("service_id", "deepseek-api"),
                Map.entry("profile_version", "2026-09-13"), Map.entry("protocol", "openai-chat-completions"),
                Map.entry("authentication_scheme", "bearer-api-key"), Map.entry("endpoint", "https://api.deepseek.com"),
                Map.entry("model_id", "deepseek-flash"), Map.entry("api_key", System.getenv("DEEPSEEK_API_KEY")),
                Map.entry("supported_max_output_tokens", 8192), Map.entry("context_window_tokens", 65536),
                Map.entry("max_output_tokens", 8192), Map.entry("reasoning", "DISABLED"), Map.entry("timeout_seconds", 120),
                Map.entry("currency", "CNY"));
        String modelId = data(request("POST", "/api/v1/admin/models", token, config)).path("id").asText();
        data(request("POST", "/api/v1/admin/models/publish", token, Map.of("id", modelId, "expected_revision", 1)));
        String agentId = "900000000000000006";
        JsonNode agent = data(request("GET", "/api/v1/admin/system-agents/detail?agentId=" + agentId, token, null));
        var draft = (com.fasterxml.jackson.databind.node.ObjectNode) agent.path("draft").deepCopy();
        draft.put("model_config_id", modelId);
        agent = data(request("PUT", "/api/v1/admin/system-agents/update", token,
                Map.of("agent_id", agentId, "expected_revision", agent.path("revision").asLong(), "config", draft)));
        var input = json.createObjectNode().put("sourceDigest", "live-summary-fixture")
                .put("coveredMessages", 0).put("maxOutputTokens", 4096).put("remainingMillis", 120000)
                .put("root", "live-summary-fixture");
        input.putArray("currentContext").addObject().put("id", "current").put("role", "USER")
                .put("text", "请保留项目代号与交付日期，区分已确认和待确认事项。");
        var messages = input.putArray("messages");
        for (int i = 1; i <= 37; i++) {
            messages.addObject().put("id", "user-" + i).put("role", "USER").put("closed", true)
                    .put("text", i == 1 ? "项目代号为 LANTERN-731，已确认交付日期为 2027-05-18。" : "第 " + i + " 轮：请核对交付进展与待确认事项。");
            messages.addObject().put("id", "assistant-" + i).put("role", "ASSISTANT").put("closed", true)
                    .put("text", "第 " + i + " 轮状态已记录，后续按已确认资料处理。");
        }
        JsonNode result = data(request("POST", "/api/v1/admin/system-agents/debug", token,
                Map.of("agent_id", agentId, "source", "DRAFT", "expected_revision", agent.path("revision").asLong(),
                        "input", json.writeValueAsString(input), "idempotency_key", "summary-live-37")));
        assertThat(result.path("status").asText()).as("system agent status; error=%s", result.path("error_code").asText()).isEqualTo("COMPLETED");
        JsonNode summary = json.readTree(result.path("output").asText());
        assertThat(summary.path("coveredMessages").asInt()).isEqualTo(74);
        assertThat(summary.path("content").asText()).contains("LANTERN-731", "2027-05-18");
        String invocation = "system-agent:debug:" + agentId + ":" + result.path("id").asText();
        assertThat(jdbc.queryForObject("SELECT status FROM nw_external_invocations WHERE tenant_id=900000000000000001 AND invocation_id=?", String.class, invocation)).isEqualTo("SUCCEEDED");
    }

    @Test void unknownIsPersistedAndReplayDoesNotRedispatchWhileRejectionIsKnownFailure() throws Exception {
        var responseStatus = new AtomicInteger(503);
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(json.readTree(exchange.getRequestBody()));
                byte[] body = "{\"error\":{\"message\":\"controlled response\",\"type\":\"test_error\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus.get(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            assertThat(organizations.initializePlatformAdministrator("991001", () -> jdbc.update("""
                    INSERT INTO nw_platform_members(id,username,password_hash,display_name,status,created_at,updated_at)
                    VALUES(991001,'outcome-admin',?,'Outcome test','active',now(),now())
                    """, passwords.encode("PlatformIntegration123")))).isTrue();
            String token = data(request("POST", "/api/v1/platform/auth/login", null,
                    Map.of("username", "outcome-admin", "password", "PlatformIntegration123"))).path("access_token").asText();
            var modelRequest = Map.ofEntries(
                    Map.entry("name", "Controlled outcome model"), Map.entry("kind", "LLM"), Map.entry("enabled", true),
                    Map.entry("provider_id", "custom"), Map.entry("service_id", "custom-openai-compatible"),
                    Map.entry("profile_version", "custom-v1"), Map.entry("protocol", "openai-chat-completions"),
                    Map.entry("authentication_scheme", "bearer-api-key"),
                    Map.entry("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    Map.entry("model_id", "deepseek-flash"), Map.entry("api_key", "fixture-key"),
                    Map.entry("supported_max_output_tokens", 4096), Map.entry("context_window_tokens", 32000),
                    Map.entry("max_output_tokens", 2048), Map.entry("reasoning", "DISABLED"), Map.entry("timeout_seconds", 30),
                    Map.entry("input_price_per_million_cents", "25"), Map.entry("output_price_per_million_cents", "125"),
                    Map.entry("currency", "CNY"));
            String modelId = data(request("POST", "/api/v1/admin/models", token, modelRequest)).path("id").asText();
            data(request("POST", "/api/v1/admin/models/publish", token, Map.of("id", modelId, "expected_revision", 1)));
            String agentId = "900000000000000003";
            JsonNode agent = data(request("GET", "/api/v1/admin/system-agents/detail?agentId=" + agentId, token, null));
            var config = (com.fasterxml.jackson.databind.node.ObjectNode) agent.path("draft").deepCopy();
            config.put("model_config_id", modelId);
            agent = data(request("PUT", "/api/v1/admin/system-agents/update", token,
                    Map.of("agent_id", agentId, "expected_revision", agent.path("revision").asLong(), "config", config)));
            long revision = agent.path("revision").asLong();
            var contract = new MemoryExtractionCandidates();
            String input = contract.encodeInput(contract.typedInput(1L, List.of(new MemoryExtractionCandidates.Source(
                    "fixture-source", "fixture input", Instant.parse("2026-10-23T00:00:00Z"))), List.of()));
            var unknownRequest = Map.of("agent_id", agentId, "source", "DRAFT", "expected_revision", revision,
                    "input", input, "idempotency_key", "unknown-key");
            JsonNode unknown = data(request("POST", "/api/v1/admin/system-agents/debug", token, unknownRequest));
            assertThat(unknown.path("status").asText()).isEqualTo("FAILED");
            assertThat(unknown.path("error_code").asText()).isEqualTo("system_agent_outcome_unknown");
            assertLedger(agentId, unknown.path("id").asText(), "UNRESOLVED", "EXTERNAL_OUTCOME_UNKNOWN");
            assertThat(requests).hasSize(1);
            JsonNode replay = data(request("POST", "/api/v1/admin/system-agents/debug", token, unknownRequest));
            assertThat(replay.path("id").asText()).isEqualTo(unknown.path("id").asText());
            assertThat(replay.path("error_code").asText()).isEqualTo("system_agent_outcome_unknown");
            assertLedger(agentId, unknown.path("id").asText(), "UNRESOLVED", "EXTERNAL_OUTCOME_UNKNOWN");
            assertThat(requests).hasSize(1);
            JsonNode lookup = data(request("GET", "/api/v1/admin/system-agents/debug-result?agentId=" + agentId
                    + "&idempotencyKey=unknown-key", token, null));
            assertThat(lookup).isEqualTo(unknown);
            assertThat(requests).hasSize(1);

            responseStatus.set(400);
            var rejectedRequest = Map.of("agent_id", agentId, "source", "DRAFT", "expected_revision", revision,
                    "input", input, "idempotency_key", "rejected-key");
            JsonNode rejected = data(request("POST", "/api/v1/admin/system-agents/debug", token, rejectedRequest));
            assertThat(rejected.path("status").asText()).isEqualTo("FAILED");
            assertThat(rejected.path("error_code").asText()).isEqualTo("system_agent_execution_failed");
            assertThat(rejected.path("id").asText()).isNotEqualTo(unknown.path("id").asText());
            assertLedger(agentId, rejected.path("id").asText(), "FAILED", "EXTERNAL_CALL_FAILED");
            assertLedger(agentId, unknown.path("id").asText(), "UNRESOLVED", "EXTERNAL_OUTCOME_UNKNOWN");
            assertThat(requests).hasSize(2).allSatisfy(modelRequestBody -> {
                assertThat(modelRequestBody.path("model").asText()).isEqualTo("deepseek-flash");
                assertThat(modelRequestBody.path("stream").asBoolean(false)).isFalse();
            });
        } finally {
            server.stop(0);
        }
    }

    private void assertLedger(String agentId, String runId, String status, String error) {
        String invocation = "system-agent:debug:" + agentId + ":" + runId;
        var rows = jdbc.queryForList("SELECT status,error_code FROM nw_external_invocations WHERE tenant_id=900000000000000001 AND invocation_id=?", invocation);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("status", status).containsEntry("error_code", error);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs WHERE id=CAST(? AS bigint)", Long.class, runId)).isEqualTo(1);
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        return envelope.path("data");
    }
    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(180));
        if (token != null) builder.header("X-Platform-Token", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
