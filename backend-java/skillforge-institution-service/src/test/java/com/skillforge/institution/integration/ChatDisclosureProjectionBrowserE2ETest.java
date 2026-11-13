package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 实时、补投、重放与普通历史必须使用同一份当前公开输出。 */
class ChatDisclosureProjectionBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND =
            KnowledgeBrowserServer.startIsolated("chat-disclosure-projection-");

    @Autowired
    private SessionMailboxService mailbox;

    @DynamicPropertySource
    static void allowIsolatedBrowserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll
    static void closeIsolatedFrontend() {
        FRONTEND.close();
    }

    @Test
    @Tag("runtime-latency")
    @Timeout(300)
    void currentDisclosurePolicyAppliesToHistoryCursorReplayAndBrowser() throws Exception {
        login();
        String mode = disclosureMode();
        String rejected = "实时公开检查拒绝的合成正文-" + SnowflakeIds.nextId();
        String messageId = "disclosure-message-" + SnowflakeIds.nextId();
        String sentinel = "公开投影顺序哨兵-" + SnowflakeIds.nextId();
        Path evidence = Path.of("build", "runtime-e2e",
                "chat-disclosure-projection-" + fixtureRunId()).toAbsolutePath();
        Files.createDirectories(evidence);
        Path browserEvidence = evidence.resolve("playwright");
        Path ready = evidence.resolve("browser-ready");
        Path log = evidence.resolve("playwright.log");

        FRONTEND.bindBackend(baseUrl());
        Path playwright = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin")
                .resolve("node_modules/.bin").resolve(isWindows() ? "playwright.cmd" : "playwright");
        assertTrue(Files.isRegularFile(playwright), "Playwright 未安装");
        ProcessBuilder command = new ProcessBuilder(playwright.toString(), "test",
                "e2e/chat-disclosure-policy.spec.ts");
        command.directory(KnowledgeBrowserServer.repoRoot().resolve("frontend-admin").toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(log.toFile());
        Map<String, String> environment = command.environment();
        environment.put("E2E_BASE_URL", FRONTEND.baseUrl());
        environment.put("E2E_BACKEND_URL", baseUrl());
        environment.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        environment.put("E2E_USERNAME", "runtime-e2e-admin");
        environment.put("E2E_PASSWORD", PASSWORD);
        environment.put("E2E_SESSION_ID", sessionId);
        environment.put("E2E_REJECTED_CONTENT", rejected);
        environment.put("E2E_SAFE_CONTENT", PublicOutputGuard.SAFE_MESSAGE);
        environment.put("E2E_ORDER_SENTINEL", sentinel);
        environment.put("E2E_DISCLOSURE_MODE", mode);
        environment.put("E2E_READY_FILE", ready.toString());
        environment.put("E2E_ARTIFACT_DIR", browserEvidence.toString());
        Process browser = command.start();
        try {
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!Files.exists(ready) && browser.isAlive() && System.nanoTime() < readyDeadline)
                Thread.sleep(100);
            assertTrue(Files.exists(ready), "浏览器未建立历史与实时订阅；证据=" + evidence);

            appendOutput("rejected", messageId, 1, rejected, true);
            appendOutput("sentinel", "disclosure-sentinel-" + SnowflakeIds.nextId(), 1, sentinel, false);

            assertTrue(browser.waitFor(120, TimeUnit.SECONDS), "浏览器验收超时；证据=" + evidence);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, browser.exitValue(), output);
        } finally {
            if (browser.isAlive()) {
                browser.descendants().forEach(ProcessHandle::destroyForcibly);
                browser.destroyForcibly();
            }
        }

        var history = successData(send("/api/v1/chat/history?session_id=" + sessionId + "&limit=100",
                "GET", null, accessToken));
        var historicalMessage = StreamSupport.stream(history.path("messages").spliterator(), false)
                .filter(value -> messageId.equals(value.path("message_id").asText()))
                .findFirst().orElseThrow();
        assertEquals(PublicOutputGuard.SAFE_MESSAGE, historicalMessage.path("content").asText());
        assertFalse(history.toString().contains(rejected));

        var cursor = successData(send("/api/v1/chat/events?session_id=" + sessionId + "&limit=500",
                "GET", null, accessToken));
        var cursorMessage = StreamSupport.stream(cursor.path("events").spliterator(), false)
                .filter(value -> "message.delta".equals(value.path("event").asText()))
                .filter(value -> messageId.equals(value.path("data").path("message_id").asText()))
                .findFirst().orElseThrow();
        String cursorContent = cursorMessage.path("data").path("content").asText();

        var replayRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/chat/replay?session_id=" + sessionId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "text/event-stream")
                .GET().build();
        var replay = httpClient.send(replayRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains(messageId), replay.body());
        String replayBody = replay.body();

        Map<String, Object> serverEvidence = new LinkedHashMap<>();
        serverEvidence.put("mode", mode);
        serverEvidence.put("session_id", sessionId);
        serverEvidence.put("message_id", messageId);
        serverEvidence.put("history_content", historicalMessage.path("content").asText());
        serverEvidence.put("cursor_content", cursorContent);
        serverEvidence.put("replay_safe", replayBody.contains(PublicOutputGuard.SAFE_MESSAGE));
        serverEvidence.put("replay_rejected", replayBody.contains(rejected));
        Files.writeString(evidence.resolve("server-disclosure.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(serverEvidence));

        if ("baseline".equals(mode)) {
            assertEquals(rejected, cursorContent);
            assertTrue(replayBody.contains(rejected), replayBody);
            assertFalse(replayBody.contains(PublicOutputGuard.SAFE_MESSAGE), replayBody);
        } else {
            assertEquals(PublicOutputGuard.SAFE_MESSAGE, cursorContent);
            assertFalse(cursor.toString().contains(rejected));
            assertTrue(replayBody.contains(PublicOutputGuard.SAFE_MESSAGE), replayBody);
            assertFalse(replayBody.contains(rejected), replayBody);
        }
    }

    private void appendOutput(String suffix, String messageId, int version, String content, boolean rejected) {
        var data = new LinkedHashMap<String, Object>();
        data.put("message_id", messageId);
        data.put("message_version", version);
        data.put("role", "assistant");
        data.put("content", content);
        data.put("state", "completed");
        data.put("citations", List.of());
        if (rejected) data.put("unsafe", "api_key=synthetic-disclosure-projection");
        TenantContext.runWithTenant(tenantId, () -> mailbox.appendOutput(
                tenantId, sessionId, "fixture:" + suffix,
                "message.delta", Map.of("event", "message.delta", "data", data)));
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String disclosureMode() {
        String mode = System.getenv().getOrDefault("E2E_DISCLOSURE_MODE", "candidate");
        if (!"baseline".equals(mode) && !"candidate".equals(mode))
            throw new IllegalArgumentException("E2E_DISCLOSURE_MODE 只能是 baseline 或 candidate");
        return mode;
    }
}
