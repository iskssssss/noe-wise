package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实关闭回执足以确认关闭，随后详情响应丢失不应改变该确定结果。 */
class ChatSessionCloseReceiptBrowserE2ETest extends ChatRuntimeChannelE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("session-close-receipt-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void confirmedCloseReceiptDoesNotRequireAnotherDetailRead() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_SESSION_CLOSE_RECEIPT", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        Path evidence = Path.of("build/runtime-e2e/session-close-receipt-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        System.out.println("SESSION_CLOSE_RECEIPT_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "真实隔离 Spring/PostgreSQL/Redis、浏览器、HTTP 模型与 Runtime。只提交一次消息。仅在成功关闭后，对浏览器发出的详情读取先取得真实上游响应再丢弃回包；不伪造关闭结果。模型为本地受控 HTTP provider，无外部供应商调用。模型请求/结果保留于 Runtime material，provider 仅记录计数与响应类型。凭据、trace、video 与自动截图不保存。", StandardCharsets.UTF_8);
        FRONTEND.bindBackend(baseUrl());
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),
                admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "e2e/session-close-receipt-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_SESSION_CLOSE_RECEIPT", mode);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId);
        env.remove("SDK4_LIVE_MODEL_KEY");
        env.remove("E2E_LIVE_MODEL");
        try {
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    owned.capture();
                    process.waitFor(200, TimeUnit.MILLISECONDS);
                }
                owned.capture();
                assertFalse(process.isAlive(), "关闭回执浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var accepted = objectMapper.readTree(Files.readString(browser.resolve("accepted.json"))).path("response").path("data");
            String actualSession = accepted.path("session_id").asText();
            String actualRun = accepted.path("execution_id").asText();
            assertFalse(actualSession.isBlank());
            assertFalse(actualRun.isBlank());
            login();
            var detail = get("/api/v1/chat/sessions/detail?sessionId=" + actualSession);
            Files.writeString(evidence.resolve("server-session-detail.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "status", detail.statusCode(), "body", objectMapper.readTree(detail.body()))));
            assertEquals(200, detail.statusCode(), detail.body());
            assertTrue(successData(detail).path("closed").asBoolean());
            var history = get("/api/v1/chat/history?session_id=" + actualSession);
            Files.writeString(evidence.resolve("server-history.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "status", history.statusCode(), "body", objectMapper.readTree(history.body()))));
            assertEquals(200, history.statusCode(), history.body());
            assertTrue(successData(history).path("messages").toString().contains("fixture-answer-" + runId));
            assertEquals(1, provider.requestCount());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?", tenantId));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun, actualSession));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "mode", mode, "providerCalls", provider.requestCount(), "providerResponseKinds", provider.responseKinds(),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? ORDER BY created_second LIMIT 5", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 10", tenantId))));
        }
    }
}
