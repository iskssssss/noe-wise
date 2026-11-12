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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 可选资源目录读取失败不应丢弃模板详情或阻止空绑定安装。 */
class AgentTemplateResourcesBrowserE2ETest extends ChatRuntimeChannelE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-template-resources-");
    @Autowired AgentTemplateService templates;
    @Autowired PlatformModelCatalogService models;

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void optionalResourceReadFailureDoesNotBlockTemplateInstallation() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_AGENT_TEMPLATE_RESOURCES", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/agent-template-resources-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        if ("candidate".equals(System.getenv("E2E_TEMPLATE_SUMMARY"))) {
            var openapi = get("/v3/api-docs");
            assertEquals(200, openapi.statusCode());
            var schemas = objectMapper.readTree(openapi.body()).path("components").path("schemas");
            var definition = schemas.path("AgentTemplateItemResponse").path("properties").path("definition");
            assertTrue(definition.path("$ref").asText().endsWith("/TemplateSummary"), definition.toString());
            var summary = schemas.path("TemplateSummary").path("properties");
            assertTrue(summary.has("resource_slots"), summary.toString());
            assertFalse(summary.has("system_prompt"), summary.toString());
            Files.writeString(evidence.resolve("openapi-template-summary.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("definition", definition, "summary", summary)));
        }
        System.out.println("AGENT_TEMPLATE_RESOURCES_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "真实隔离 Spring/PostgreSQL/Redis、Vite、浏览器、API 与 Runtime。本地受控 HTTP 模型，无外部供应商。模板前置通过正式模板保存和发布快照服务准备。资源目录响应先读取真实 HTTP 200 再受控丢弃；模板详情与安装响应不伪造。基线使用独立真实 API 空绑定安装作控制，候选由界面安装；两者均在同一安装 Agent 中通过浏览器提交一次消息。凭据、trace、video 和自动截图不落盘。", StandardCharsets.UTF_8);
        String marker = "TEMPLATE_RESOURCES_PROMPT_" + runId;
        String modelId;
        TenantContext.setTenantId(tenantId);
        try { modelId = models.selections(tenantId).llmModelConfigId(); }
        finally { TenantContext.clear(); }
        assertFalse(modelId == null || modelId.isBlank());
        var template = new AgentTemplateDO();
        template.setId(SnowflakeIds.nextId());
        template.setTemplateKey("resources-browser-" + template.getId());
        template.setName("可选知识资源验收模板");
        template.setVersion("1.0.0");
        template.setCategory("test");
        template.setTags(List.of());
        template.setDescription("可选资源读取失败时仍可独立安装的验收模板");
        template.setAuthor("test");
        template.setAuthorType("platform");
        template.setOwnerKind("platform");
        template.setDistributionPolicy(AgentDistributionPolicy.OPEN_INSTALL);
        template.setDefinition(new AgentTemplateDefinition("请简短回答。" + marker, "可选资源验收助手", modelId, null, List.of(),
                List.of(new AgentTemplateDefinition.ResourceSlot("knowledge", "可选验收知识", "可不绑定", CapabilityType.KNOWLEDGE_SOURCE)), List.of()));
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
                "test", "e2e/agent-template-resources-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
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
        if (System.getenv("E2E_TEMPLATE_SUMMARY") != null)
            env.put("E2E_TEMPLATE_SUMMARY", System.getenv("E2E_TEMPLATE_SUMMARY"));
        env.put("E2E_AGENT_TEMPLATE_RESOURCES", mode);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId);
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
            assertEquals(1, provider.requestCount());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?", tenantId));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun, actualSession, installedAgent));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
            var materials = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'", tenantId, actualRun);
            assertEquals(1, materials.size());
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var material = materials.getFirst();
            var prepared = ModelRequestTestMaterials.decode(codec, new TypedPayload(material.get("schema_id").toString(), material.get("canonical_json").toString()), jdbc, tenantId);
            Files.writeString(evidence.resolve("model-messages.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(prepared.request().messages()));
            assertTrue(prepared.request().messages().stream().anyMatch(message -> message.role() == ModelRole.SYSTEM && message.textContent().contains(marker)));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "mode", mode, "providerCalls", provider.requestCount(), "providerResponseKinds", provider.responseKinds(),
                    "installations", queryRows(jdbc, tenantId, "SELECT request_id,template_id,agent_id FROM nw_agent_template_installations WHERE tenant_id=? AND template_id=? LIMIT 5", Long.valueOf(tenantId), template.getId()),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? LIMIT 5", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 5", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 5", tenantId))));
        }
    }
}
