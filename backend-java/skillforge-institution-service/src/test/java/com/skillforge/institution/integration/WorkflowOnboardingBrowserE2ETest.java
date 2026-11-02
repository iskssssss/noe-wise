package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.capability.SkillDraftContent;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import java.time.Instant;
import org.springframework.context.annotation.Import;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离浏览器、Spring 和 PG 验证工作流接入；不创建 Run 或调用模型。 */
@Tag("workflow-onboarding-browser")
@Import(DeterministicModelCatalogConfiguration.class)
class WorkflowOnboardingBrowserE2ETest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("workflow-onboard-browser-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeBaseRevisionService revisions;
    @Autowired SkillDraftService skillDrafts;
    @Autowired CapabilityCatalogService catalog;
    @Autowired PlatformModelCatalogService models;
    @Autowired AgentTemplateService templates;
    @Autowired com.skillforge.core.foundation.settings.OperationalSettingsService operationalSettings;
    @LocalServerPort int port;
    private com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot originalRate;
    private long fixtureRateRevision;
    private long fixtureRateActor;

    @BeforeEach void configureBrowserRatePolicy() {
        // 同一用户串行运行 14 个浏览器场景及后续 HTTP 检查；保留真实限流器并为隔离负载配置容量。
        originalRate = operationalSettings.read("rate_limits");
        fixtureRateActor = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                fixtureRateActor, "workflow-browser-policy-" + fixtureRateActor, "unused");
        fixtureRateRevision = operationalSettings.update("rate_limits", originalRate.revision(),
                Map.of("requests_per_minute_default", 600), fixtureRateActor).revision();
        assertEquals(600, operationalSettings.read("rate_limits").integer("requests_per_minute_default"));
    }

    @AfterEach void restoreBrowserRatePolicy() {
        if (fixtureRateRevision != 0) {
            operationalSettings.update("rate_limits", fixtureRateRevision, originalRate.values(), fixtureRateActor);
            assertEquals(originalRate.values(), operationalSettings.read("rate_limits").values());
        }
    }

    @Test void browserInstallsAndMountsWithoutPublishingAgent() throws Exception {
        String slug = "workflow-browser-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        String password = "WorkflowOnboardingBrowser123";
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "workflow-browser-admin", password); }
        finally { TenantContext.clear(); }
        var http = HttpClient.newHttpClient();
        var login = request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "workflow-browser-admin", "password", password), null, null);
        String token = login.path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        String kb = request(http, "POST", "/api/v1/knowledge/bases", Map.of("name", "浏览器知识", "indexing_mode", "economical"), token, null).path("data").path("id").asText();
        TenantContext.setTenantId(tenant);
        try {
            var document = knowledge.ingestProjectionDocument(tenant, Long.valueOf(kb), "资料", "可用于工作流验收的测试资料");
            var draftKnowledge = revisions.create(tenant, Long.valueOf(kb));
            revisions.markReady(tenant, Long.valueOf(kb), draftKnowledge.revision(), List.of(document.sourceVersion()),
                    new KnowledgeCapabilityPolicyRequest(true, false, Set.of("KEYWORD"), "PINNED", true));
            revisions.publish(tenant, Long.valueOf(kb), draftKnowledge.revision());
        } finally { TenantContext.clear(); }
        TenantContext.setTenantId(tenant);
        try {
            var source = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                    .filter(entry -> "get_current_time".equals(entry.name())).findFirst().orElseThrow();
            var descriptor = new java.util.LinkedHashMap<String, Object>(source.descriptor());
            descriptor.put("provider_requirements", List.of());
            var scope = CapabilityCatalogScope.tenant(Long.parseLong(tenant));
            var definition = catalog.createDefinition(scope, com.skillforge.core.capability.CapabilityType.TOOL,
                    "tool:browser-selection", "浏览器时间工具", source.description());
            var version = catalog.createDraftVersion(scope, definition.id(), "v1", "浏览器时间工具",
                    source.description(), "browser-selection-hash", descriptor);
            catalog.publishVersion(scope, definition.id(), version.id(), catalog.getDefinition(scope, definition.id()).revision());
        } finally { TenantContext.clear(); }
        var toolGraph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"),
                Map.of("id", "tool", "type", "tool", "tool", "pending", "args", Map.of(), "result_to", "state.result"),
                Map.of("id", "end", "type", "end")), "edges", List.of(Map.of("from", "start", "to", "tool"), Map.of("from", "tool", "to", "end")),
                "meta", Map.of("name", "工具选择验收"));
        request(http, "POST", "/api/v1/builder/workflows", Map.of("workflow_id", "browser-tool-flow", "definition", toolGraph), token, null);
        var graph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"),
                Map.of("id", "knowledge", "type", "knowledge_query", "name", "查询", "requirement", "knowledge", "query", "资料", "result_to", "evidence",
                        "budget", Map.of("max_items", 5, "max_tokens", 1000, "max_characters", 4000, "timeout_ms", 5000, "max_sources", 1))),
                "edges", List.of(Map.of("from", "start", "to", "knowledge")), "meta", Map.of("name", "浏览器工作流", "display_name", "浏览器工作流",
                        "capability_requirements", List.of(Map.of("slot", "knowledge", "label", "知识资料", "type", "knowledge_source", "capability_id", "knowledge-base:" + kb, "version_constraint", "*", "required", true)),
                        "config_schema", List.of(Map.of("key", "region", "type", "string", "label", "区域", "required", true)),
                        "capability", Map.of("model_invocable", true, "user_invocable", false, "input_schema", Map.of(), "output_schema", Map.of(),
                                "supports_hitl", false, "export_fields", List.of(), "timeout_ms", 30000, "concurrency_safe", true)));
        var draft = request(http, "POST", "/api/v1/builder/workflows", Map.of("workflow_id", "browser-flow", "definition", graph), token, null).path("data");
        request(http, "POST", "/api/v1/builder/workflows/publish", Map.of("workflow_id", "browser-flow"), token, draft.path("revision").asText());
        var agent = request(http, "POST", "/api/v1/agents", Map.of("agent_key", "browser-agent-" + SnowflakeIds.nextId(),
                "name", "工作流接入测试", "system_prompt", "test", "config", Map.of()), token, null).path("data").path("id").asText();
        assertFalse(agent.isBlank());
        String singleSlug = "single-target-" + SnowflakeIds.nextId();
        String singleTenant = createTenant(tenants, plans, singleSlug);
        TenantContext.setTenantId(singleTenant);
        try {
            createTestTenantAdministrator(singleTenant, "single-admin", password);
            PlatformModelFixtures.selectLlm(models, singleTenant, "http://127.0.0.1:1/v1", "deepseek-flash", "synthetic-unused-key", "custom", "custom-openai-compatible", "custom-v1");
        } finally { TenantContext.clear(); }
        String singleToken = request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", singleSlug,
                "username", "single-admin", "password", password), null, null).path("data").path("access_token").asText();
        request(http, "POST", "/api/v1/chat/end-users", Map.of("display_name", "回执测试用户"), singleToken, null);
        String singleAgent = request(http, "POST", "/api/v1/agents", Map.of("name", "唯一可用智能体", "system_prompt", "测试提示词", "config", Map.of()), singleToken, null).path("data").path("id").asText();
        request(http, "POST", "/api/v1/agents/publish", Map.of("agent_id", singleAgent), singleToken, null);
        String publishSlug = "agent-recovery-" + SnowflakeIds.nextId();
        String publishTenant = createTenant(tenants, plans, publishSlug);
        String modelId;
        TenantContext.setTenantId(publishTenant);
        try {
            createTestTenantAdministrator(publishTenant, "recovery-admin", password);
            modelId = PlatformModelFixtures.selectLlm(models, publishTenant, "http://127.0.0.1:1/v1", "deepseek-flash", "synthetic-unused-key", "custom", "custom-openai-compatible", "custom-v1");
        } finally { TenantContext.clear(); }
        var emptyTemplate = new AgentTemplateDO();
        emptyTemplate.setDistributionPolicy(com.skillforge.core.agent.model.AgentDistributionPolicy.OPEN_INSTALL);
        emptyTemplate.setId(SnowflakeIds.nextId()); emptyTemplate.setTemplateKey("empty-browser-" + emptyTemplate.getId());
        emptyTemplate.setName("知识槽验收模板"); emptyTemplate.setVersion("1.0.0"); emptyTemplate.setCategory("test");
        emptyTemplate.setTags(List.of()); emptyTemplate.setDescription("隔离验收"); emptyTemplate.setIconUrl("/template.svg");
        emptyTemplate.setAuthor("test"); emptyTemplate.setAuthorType("platform");
        emptyTemplate.setDefinition(new AgentTemplateDefinition("测试提示词", "测试角色", modelId, null, List.of(),
                List.of(new AgentTemplateDefinition.ResourceSlot("knowledge", "验收知识", "", com.skillforge.core.capability.CapabilityType.KNOWLEDGE_SOURCE)), List.of()));
        emptyTemplate.setPriceType("free"); emptyTemplate.setPriceCents(0); emptyTemplate.setInstallCount(0);
        emptyTemplate.setStatus("published"); emptyTemplate.setPublishedAt(Instant.now()); emptyTemplate.setCreatedAt(Instant.now()); emptyTemplate.setUpdatedAt(Instant.now());
        templates.save(emptyTemplate); templates.appendPublication(emptyTemplate);
        String publishToken = request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", publishSlug,
                "username", "recovery-admin", "password", password), null, null).path("data").path("access_token").asText();
        var knowledge = new RuntimeExecutionKnowledgeFixture(json, http, "http://127.0.0.1:" + port, publishToken);
        knowledge.publish("template-a", "template-sentinel-a");
        knowledge.publish("template-b", "template-sentinel-b");
        var institutionDefinition = Map.of("name", "机构保存校验验收", "price_type", "free", "price_cents", 0,
                "distribution_policy", "AUTHORIZED_DEPLOY", "definition", Map.of("system_prompt", "测试提示词",
                        "role_description", "测试角色", "model_config_id", modelId, "execution_config", Map.of(),
                        "capabilities", List.of(), "resource_slots", List.of(), "delegation_slots", List.of()));
        var definitionRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tenant/agent-definitions"))
                .header("Authorization", "Bearer " + publishToken).header("Content-Type", "application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(institutionDefinition))).build();
        var definitionResponse = http.send(definitionRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, definitionResponse.statusCode(), definitionResponse.body());
        String institutionDefinitionId = json.readTree(definitionResponse.body()).path("data").path("definition_id").asText();
        assertFalse(institutionDefinitionId.isBlank());
        String publishAgent = request(http, "POST", "/api/v1/agents", Map.of("name", "发布恢复验收", "system_prompt", "测试提示词", "config", Map.of()), publishToken, null).path("data").path("id").asText();
        String selectedSkill;
        TenantContext.setTenantId(tenant);
        try {
            var scope = CapabilityCatalogScope.tenant(Long.parseLong(tenant));
            String found = "";
            for (var item : List.of(Map.of("key", "browser-order", "description", "查询订单资料"),
                    Map.of("key", "browser-patient", "description", "查询患者资料"))) {
                var draftSkill = skillDrafts.createSkill(scope, item.get("key"), "同名服务技能", item.get("description"));
                var content = "---\nname: service-skill\ndescription: " + item.get("description") + "\n---\nProvide fixture guidance.";
                var saved = skillDrafts.save(scope, draftSkill.id(), draftSkill.revision(),
                        new SkillDraftContent("v1", Map.of("SKILL.md", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))),
                                true, true, SkillActivationMode.ON_DEMAND, null, List.of()));
                assertTrue(skillDrafts.validate(scope, saved.id(), saved.revision()).valid());
                skillDrafts.publish(scope, saved.id(), saved.revision(), catalog.getDefinition(scope, saved.definitionId()).revision());
                if ("browser-patient".equals(item.get("key"))) found = Long.toString(saved.definitionId());
            }
            selectedSkill = found;
        } finally { TenantContext.clear(); }
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("frontend-admin/package.json"))) root = root.getParent();
        assertNotNull(root);
        Path admin = root.resolve("frontend-admin"), evidence = FRONTEND.evidence();
        var command = new ProcessBuilder("node", admin.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "--config=playwright.workflow-onboarding.config.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_USERNAME", "workflow-browser-admin"); env.put("E2E_PASSWORD", password); env.put("E2E_AGENT_ID", agent);
        env.put("E2E_SKILL_DEFINITION_ID", selectedSkill);
        env.put("E2E_PUBLISH_SLUG", publishSlug); env.put("E2E_PUBLISH_AGENT_ID", publishAgent); env.put("E2E_SINGLE_SLUG", singleSlug);
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var creation = json.readTree(Files.readString(evidence.resolve("agent-create-recovery.json")));
        var creationReceipt = creation.path("receipt");
        String createPath = "http://127.0.0.1:" + port + "/api/v1/agents/create-with-receipt";
        var replay = HttpRequest.newBuilder(URI.create(createPath)).header("Authorization", "Bearer " + publishToken)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(creation.path("command").toString())).build();
        var replayed = http.send(replay, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, replayed.statusCode());
        assertEquals(creationReceipt, json.readTree(replayed.body()).path("data"));
        var changed = creation.path("command").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("payload")).put("name", "不同内容");
        var conflict = HttpRequest.newBuilder(URI.create(createPath)).header("Authorization", "Bearer " + publishToken)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(changed.toString())).build();
        var conflictResult = http.send(conflict, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, conflictResult.statusCode());
        assertEquals("AGENT_CREATE_IDENTITY_CONFLICT", json.readTree(conflictResult.body()).path("code").asText());
        String createResult = createPath + "/result?request_id=" + creationReceipt.path("request_id").asText();
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(createResult)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(204, http.send(HttpRequest.newBuilder(URI.create(createResult)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var createRows = queryRows(jdbc, publishTenant, "SELECT agent_id FROM nw_agent_create_receipts WHERE tenant_id=? AND request_id=?::uuid",
                Long.valueOf(publishTenant), creationReceipt.path("request_id").asText());
        assertEquals(1, createRows.size());
        assertEquals(creationReceipt.path("agent_id").asText(), createRows.getFirst().get("agent_id").toString());
        String readerRole = "agent_create_reader_" + SnowflakeIds.nextId();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + readerRole + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("GRANT USAGE ON SCHEMA public TO " + readerRole);
                statement.execute("GRANT SELECT ON public.nw_agent_create_receipts TO " + readerRole);
                statement.execute("SET LOCAL ROLE " + readerRole);
            }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                 var count = connection.prepareStatement("SELECT count(*) FROM public.nw_agent_create_receipts WHERE request_id=?::uuid")) {
                count.setString(1, creationReceipt.path("request_id").asText());
                bind.setString(1, publishTenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
                bind.setString(1, tenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            } finally { connection.rollback(); }
        }
        var publishReceipt = json.readTree(Files.readString(evidence.resolve("agent-publish-recovery.json")));
        var templateReceipt = json.readTree(Files.readString(evidence.resolve("agent-template-recovery.json")));
        String resultUrl = "http://127.0.0.1:" + port + "/api/v1/agents/install-template/result?request_id=" + templateReceipt.path("request_id").asText();
        var anonymousResult = http.send(HttpRequest.newBuilder(URI.create(resultUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymousResult.statusCode());
        var otherTenantResult = http.send(HttpRequest.newBuilder(URI.create(resultUrl)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, otherTenantResult.statusCode());
        var templateRows = queryRows(jdbc, publishTenant, "SELECT agent_id FROM nw_agent_template_installations WHERE tenant_id=? AND request_id=?",
                Long.valueOf(publishTenant), templateReceipt.path("request_id").asText());
        assertEquals(1, templateRows.size());
        assertEquals(templateReceipt.path("agent_id").asText(), templateRows.getFirst().get("agent_id").toString());
        var publishRows = queryRows(jdbc, publishTenant, "SELECT id FROM nw_agent_publications WHERE tenant_id=? AND agent_id=? AND request_id=?::uuid",
                Long.valueOf(publishTenant), Long.valueOf(publishAgent), publishReceipt.path("request_id").asText());
        assertEquals(1, publishRows.size());
        assertEquals(publishReceipt.path("publication_id").asText(), publishRows.getFirst().get("id").toString());
        var receipt = json.readTree(Files.readString(evidence.resolve("onboarding-receipt.json"))).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        var mounts = request(http, "GET", "/api/v1/agents/capability-mounts?agentId=" + agent, null, token, null).path("data").path("mounts");
        assertEquals(2, mounts.size());
        assertTrue(java.util.stream.StreamSupport.stream(mounts.spliterator(), false)
                .anyMatch(mount -> receipt.path("mount_id").equals(mount.path("mount_id"))));
        assertTrue(java.util.stream.StreamSupport.stream(mounts.spliterator(), false)
                .anyMatch(mount -> selectedSkill.equals(mount.path("capability_definition_id").asText())));
        var agentDetail = request(http, "GET", "/api/v1/agents/detail?agentId=" + agent, null, token, null).path("data");
        assertTrue(agentDetail.path("published_revision").isNull() || agentDetail.path("published_revision").isMissingNode());
        var rows = queryRows(jdbc, tenant, "SELECT workflow_instance_id, workflow_installation_revision FROM nw_agent_capability_mounts WHERE tenant_id=? AND agent_id=? AND workflow_instance_id IS NOT NULL",
                Long.valueOf(tenant), Long.valueOf(agent));
        assertEquals(1, rows.size());
        assertEquals(receipt.path("instance_id").asText(), rows.getFirst().get("workflow_instance_id").toString());
        assertEquals(receipt.path("installation_revision").asLong(), ((Number) rows.getFirst().get("workflow_installation_revision")).longValue());
    }
    private JsonNode request(HttpClient http, String method, String path, Object body, String token, String ifMatch) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        if (body != null) builder.header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.statusCode() + ": " + response.body());
        return json.readTree(response.body());
    }
}
