package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证已选可选资源可以取消并以空绑定安装执行。 */
class AgentTemplateToolOptionsBrowserE2ETest extends ChatRuntimeChannelE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-template-tool-options-");
    @Autowired AgentTemplateService templates;
    @Autowired PlatformModelCatalogService models;

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void toolSlotUsesAvailableDeploymentWithoutVersionFanout() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_TEMPLATE_TOOL_MODE", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/agent-template-tool-options-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        System.out.println("AGENT_TEMPLATE_TOOL_OPTIONS_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离 Spring、数据库、浏览器和 Runtime，使用本地受控模型与真实平台工具。基线通过独立 API 安装指定工具槽，候选由浏览器选择并安装。", StandardCharsets.UTF_8);
        var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        var versionIds = new LinkedHashMap<String, String>();
        var definitionIds = new LinkedHashMap<String, String>();
        var names = new LinkedHashMap<String, String>();
        var deploymentKeys = new LinkedHashMap<String, String>();
        for (String tool : List.of("get_current_time", "json_query")) {
            for (var item : definitions) if (("tool:" + tool).equals(item.path("capability_key").asText())) {
                definitionIds.put(tool, item.path("id").asText()); versionIds.put(tool, item.path("default_version_id").asText()); names.put(tool, item.path("name").asText());
            }
            assertTrue(definitionIds.containsKey(tool));
            String key = "template-slot-" + tool + "-" + SnowflakeIds.nextId();
            var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", versionIds.get(tool), "provider_type", "PLATFORM", "provider_ref", tool, "status", "ACTIVE", "environment", "DEVELOPMENT"), accessToken);
            assertEquals(201, deployment.statusCode(), deployment.body()); deploymentKeys.put(tool, key);
        }
        Files.writeString(evidence.resolve("tool-fixtures.json"), objectMapper.writeValueAsString(Map.of("definitions", definitionIds, "versions", versionIds, "deployments", deploymentKeys)));
        String reply = "TEMPLATE_CLOCK_" + SnowflakeIds.nextId();
        try (var clockModel = new ClockModel(reply)) {
        clockModel.server.start(); TenantContext.setTenantId(tenantId);
        try { configureTenantModel("http://127.0.0.1:" + clockModel.server.getAddress().getPort()); } finally { TenantContext.clear(); }
        String marker = "TEMPLATE_RESOURCES_PROMPT_" + runId;
        String modelId;
        TenantContext.setTenantId(tenantId);
        try { modelId = models.selections(tenantId).llmModelConfigId(); }
        finally { TenantContext.clear(); }
        assertFalse(modelId == null || modelId.isBlank());
        var template = new AgentTemplateDO();
        template.setId(SnowflakeIds.nextId());
        template.setTemplateKey("resources-browser-" + template.getId());
        template.setName("可选工具资源验收模板");
        template.setVersion("1.0.0");
        template.setCategory("test");
        template.setTags(List.of());
        template.setDescription("工具资源目录验收模板");
        template.setAuthor("test");
        template.setAuthorType("platform");
        template.setOwnerKind("platform");
        template.setDistributionPolicy(AgentDistributionPolicy.OPEN_INSTALL);
        template.setDefinition(new AgentTemplateDefinition("请简短回答。" + marker, "可选资源验收助手", modelId, null, List.of(),
                List.of(new AgentTemplateDefinition.ResourceSlot("tool", "可选验收工具", "可不绑定", CapabilityType.TOOL)), List.of()));
        template.setPriceType("free");
        template.setPriceCents(0);
        template.setInstallCount(0);
        template.setStatus("published");
        template.setDraftRevision(1L);
        template.setHasUnpublishedChanges(false);
        template.setPublishedAt(Instant.now());
        template.setCreatedAt(template.getPublishedAt());
        template.setUpdatedAt(template.getPublishedAt());
        assertTrue(templates.save(template));
        templates.appendPublication(template);
        Files.writeString(evidence.resolve("template-fixture.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(template));
        FRONTEND.bindBackend(baseUrl());
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "e2e/agent-template-tool-options-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_ADMIN_TOKEN", accessToken);
        env.put("E2E_TEMPLATE_ID", template.getId().toString());
        env.put("E2E_TEMPLATE_NAME", template.getName());
        env.put("E2E_TEMPLATE_PROMPT_MARKER", marker);
        env.put("E2E_TOOL_DEFINITION", definitionIds.get("get_current_time"));
        env.put("E2E_DROP_DEFINITION", definitionIds.get("json_query"));
        env.put("E2E_TOOL_NAME", names.get("get_current_time"));
        env.put("E2E_TOOL_DEPLOYMENT", deploymentKeys.get("get_current_time"));
        env.put("E2E_TEMPLATE_TOOL_MODE", mode);
        env.put("E2E_EXPECTED_REPLY", reply);
        env.remove("SDK4_LIVE_MODEL_KEY");
        env.remove("E2E_LIVE_MODEL");
        try {
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture();
                assertFalse(process.isAlive(), "模板可选资源浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var result = objectMapper.readTree(Files.readString(browser.resolve("browser-result.json")));
            String installedAgent = result.path("agentId").asText();
            String actualRun = result.path("runId").asText();
            String actualSession = result.path("sessionId").asText();
            assertFalse(installedAgent.isBlank());
            assertFalse(actualRun.isBlank());
            assertEquals(1, countRuntime("SELECT count(*) FROM nw_agent_template_installations WHERE tenant_id=? AND template_id=? AND agent_id=?", Long.valueOf(tenantId), template.getId(), Long.valueOf(installedAgent)));
            assertEquals(2, clockModel.requests.size());
            assertTrue(clockModel.clockObserved, "实际工具结果须进入下一轮模型");
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun, actualSession, installedAgent));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='SUCCEEDED'", tenantId, actualRun));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "mode", mode, "modelCalls", clockModel.requests.size(), "modelRequests", clockModel.requests,
                    "installations", queryRows(jdbc, tenantId, "SELECT request_id,template_id,agent_id FROM nw_agent_template_installations WHERE tenant_id=? AND template_id=? LIMIT 5", Long.valueOf(tenantId), template.getId()),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? LIMIT 5", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 5", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 5", tenantId))));
        }
        }
    }
    private final class ClockModel implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile boolean clockObserved;

        ClockModel(String reply) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = objectMapper.readTree(exchange.getRequestBody()); requests.add(request.deepCopy());
                    int ordinal = requests.size();
                    if (ordinal > 2) throw new java.io.IOException("unexpected_directory_model_call");
                    String name = "";
                    for (var tool : request.path("tools")) {
                        if (tool.path("function").path("parameters").path("properties").has("timezone"))
                            name = tool.path("function").path("name").asText();
                    }
                    if (name.isBlank()) throw new java.io.IOException("clock_not_mounted");
                    Object delta;
                    String finish;
                    if (ordinal == 1) {
                        delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "directory-clock",
                                "type", "function", "function", Map.of("name", name, "arguments", "{\"timezone\":\"Asia/Shanghai\"}"))));
                        finish = "tool_calls";
                    } else {
                        for (var message : request.path("messages")) {
                            if ("tool".equals(message.path("role").asText()) && "directory-clock".equals(message.path("tool_call_id").asText())) {
                                var result = objectMapper.readTree(message.path("content").asText());
                                clockObserved = result.findPath("epoch_millis").isIntegralNumber()
                                        && "Asia/Shanghai".equals(result.findPath("timezone").asText());
                            }
                        }
                        if (!clockObserved) throw new java.io.IOException("clock_result_not_returned");
                        delta = Map.of("role", "assistant", "content", reply); finish = "stop";
                    }
                    var chunk = Map.of("id", "directory-model", "object", "chat.completion.chunk", "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
                    byte[] body = ("data: " + objectMapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                }
            });
        }
        @Override public void close() { server.stop(0); }
    }
}
