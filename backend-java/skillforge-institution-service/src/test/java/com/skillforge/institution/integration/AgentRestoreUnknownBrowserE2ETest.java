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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class AgentRestoreUnknownBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-restore-unknown-");
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void lostRestoreResponseLeavesDraftAndPublishedRuntimeObservable() throws Exception {
        login(); String a = "RESTORE_A_" + SnowflakeIds.nextId(), b = "RESTORE_B_" + SnowflakeIds.nextId();
        String mode = System.getenv().getOrDefault("E2E_AGENT_RESTORE_UNKNOWN", "baseline");
        assertTrue(java.util.List.of("baseline", "candidate").contains(mode));
        Path evidence = Path.of("build/runtime-e2e/agent-restore-unknown-" + SnowflakeIds.nextId()).toAbsolutePath(), browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("AGENT_RESTORE_UNKNOWN_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离 Spring、PostgreSQL、真实浏览器、正式恢复 API 与 Runtime；模型为受控本地 HTTP。恢复上游成功后仅丢弃浏览器响应；候选通过原请求身份只读核对回执，发布恢复后的草稿并运行。凭据与 trace 不落盘。");
        assertEquals(200, send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED")); String definition = "", version = "";
        for (var item : definitions) if ("tool:get_current_time".equals(item.path("capability_key").asText())) { definition = item.path("id").asText(); version = item.path("default_version_id").asText(); }
        assertFalse(definition.isBlank()); String key = "restore-clock-" + SnowflakeIds.nextId();
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "get_current_time", "environment", "DEVELOPMENT", "status", "ACTIVE"), accessToken); assertEquals(201, deployment.statusCode(), deployment.body());
        var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "TOOL", "capability_definition_id", definition, "capability_version_id", version, "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken); assertEquals(201, mount.statusCode(), mount.body());
        assertEquals(200, send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId, "payload", Map.of("system_prompt", a)), accessToken).statusCode());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        int revisionA = successData(get("/api/v1/agents/detail?agentId=" + agentId)).path("published_revision").asInt();
        assertEquals(200, send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        assertEquals(200, send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId, "payload", Map.of("system_prompt", b)), accessToken).statusCode());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var before = get("/api/v1/agents/detail?agentId=" + agentId); assertEquals(b, successData(before).path("system_prompt").asText());
        int revisionB = successData(before).path("published_revision").asInt(); assertTrue(revisionB > revisionA);
        Files.writeString(evidence.resolve("before.json"), before.body());
        var beforeSet = get("/api/v1/agents/capability-mounts?agentId=" + agentId); assertEquals(200, beforeSet.statusCode()); Files.writeString(evidence.resolve("before-mount-set.json"), beforeSet.body());
        var beforeMounts = queryRows(jdbc, tenantId, "SELECT id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)", tenantId, agentId);
        assertEquals(1, beforeMounts.size()); Files.writeString(evidence.resolve("before-mounts.json"), objectMapper.writeValueAsString(beforeMounts));
        FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "e2e/agent-restore-unknown-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString()); env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId); env.put("E2E_RESTORE_A", a); env.put("E2E_RESTORE_B", b); env.put("E2E_RESTORE_REVISION", Integer.toString(revisionA)); env.put("E2E_ADMIN_TOKEN", accessToken); env.put("E2E_AGENT_RESTORE_UNKNOWN", mode); env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process child = command.start();
        try (var owned = new KnowledgeBrowserProcesses(child.toHandle())) { long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4); while (child.isAlive() && System.nanoTime() < deadline) { owned.capture(); child.waitFor(200, TimeUnit.MILLISECONDS); } owned.capture(); assertFalse(child.isAlive()); assertEquals(0, child.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser.log"))); }
        var after = get("/api/v1/agents/detail?agentId=" + agentId); assertEquals(200, after.statusCode()); Files.writeString(evidence.resolve("after-read.json"), after.body());
        var afterSet = get("/api/v1/agents/capability-mounts?agentId=" + agentId); assertEquals(200, afterSet.statusCode()); Files.writeString(evidence.resolve("after-mount-set.json"), afterSet.body());
        assertEquals(a, successData(after).path("system_prompt").asText());
        if ("candidate".equals(mode)) {
            assertTrue(successData(after).path("published_revision").asInt() > revisionB);
            assertFalse(successData(after).path("has_draft_changes").asBoolean());
            var firstRestoredSet = objectMapper.readTree(Files.readString(browser.resolve("first-restored-mount-set.json"))).path("data");
            var recoveredSet = objectMapper.readTree(Files.readString(browser.resolve("recovered-mount-set.json"))).path("data");
            assertEquals(1, firstRestoredSet.path("mounts").size());
            assertEquals(firstRestoredSet.path("generation"), recoveredSet.path("generation"));
            assertEquals(firstRestoredSet.path("mounts"), recoveredSet.path("mounts"));
            assertEquals(firstRestoredSet.path("generation"), successData(afterSet).path("generation"));
            assertEquals(firstRestoredSet.path("mounts"), successData(afterSet).path("mounts"));
        } else {
            assertEquals(revisionB, successData(after).path("published_revision").asInt());
            assertTrue(successData(after).path("has_draft_changes").asBoolean());
        }
        var afterMounts = queryRows(jdbc, tenantId, "SELECT id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)", tenantId, agentId);
        assertEquals(1, afterMounts.size());
        if ("candidate".equals(mode)) {
            var restoredMount = objectMapper.readTree(Files.readString(browser.resolve("first-restored-mount-set.json"))).path("data").path("mounts").get(0);
            assertEquals(afterMounts.getFirst().get("id").toString(), restoredMount.path("mount_id").asText());
            assertEquals(afterMounts.getFirst().get("capability_version_id").toString(), restoredMount.path("capability_version_id").asText());
        } else assertEquals(beforeMounts.getFirst().get("capability_definition_id"), afterMounts.getFirst().get("capability_definition_id"));
        Files.writeString(evidence.resolve("after-mounts.json"), objectMapper.writeValueAsString(afterMounts));
        var facts = objectMapper.readTree(Files.readString(browser.resolve("browser-result.json"))); assertEquals(1, facts.path("posts").asInt()); String run = facts.path("run").path("execution_id").asText(); assertFalse(run.isBlank());
        long completedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, agentId) == 0 && System.nanoTime() < completedDeadline) Thread.sleep(100);
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, agentId));
        var materials = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'", tenantId, run); assertEquals(1, materials.size());
        var properties = applicationContext.getBean(AgentRuntimeProperties.class); var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth())); var row = materials.getFirst();
        var request = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
        assertTrue(request.request().messages().stream().anyMatch(message -> message.role() == ModelRole.SYSTEM && message.textContent().contains("candidate".equals(mode) ? a : b)));
        assertTrue(request.request().messages().stream().noneMatch(message -> message.role() == ModelRole.SYSTEM && message.textContent().contains("candidate".equals(mode) ? b : a)));
        assertEquals(1, provider.requestCount()); Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("mode", mode, "run", run, "revisionA", revisionA, "revisionB", revisionB, "modelMessages", request.request().messages(), "modelCalls", provider.requestCount())));
    }
}
