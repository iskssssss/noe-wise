package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.SaveTenantGrant;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离环境中验证渠道创建后直接完成一次会话。 */
class ChannelRuntimeBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("channel-runtime-browser-");
    @Autowired
    EntitlementMapper entitlements;
    @DynamicPropertySource
    static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", () -> FRONTEND.baseUrl() + ",http://localhost:3200");
    }
    @AfterAll
    static void closeFrontend() {
        FRONTEND.close();
    }

    @Test
    void createdChannelCanRunAgent() throws Exception {
        TenantContext.setTenantId(tenantId);
        try {
            long member = jdbc.queryForObject("SELECT id FROM nw_tenant_members WHERE tenant_id=? AND username=?",
                    Long.class, Long.valueOf(tenantId), "runtime-e2e-admin");
            withAuthenticatedMember(member, () -> {
                entitlements.insertTenantGrant(new SaveTenantGrant(tenantId, "channels", true, 0L), "channel-runtime-fixture");
                return null;
            });
        } finally {
            TenantContext.clear();
        }
        FRONTEND.bindBackend(baseUrl());
        var admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var evidence = FRONTEND.evidence();
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),
                admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.channel-runtime.config.ts");
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_BACKEND_URL", baseUrl());
        env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", tenantSlug);
        env.put("E2E_USERNAME", "runtime-e2e-admin");
        env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId);
        var process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture();
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture();
            assertFalse(process.isAlive(), "浏览器超时: " + evidence);
            assertEquals(0, process.exitValue(), "浏览器失败: " + evidence + "\n"
                    + Files.readString(evidence.resolve("playwright.log")));
        }
        var facts = objectMapper.readTree(Files.readString(evidence.resolve("channel-runtime-result.json")));
        assertEquals(1, facts.path("submissions").asInt());
        assertEquals(1, provider.requestCount());
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs "
                + "WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'",
                tenantId, facts.path("runId").asText()));
    }
}
