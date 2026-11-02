package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.security.HmacSignatureVerifier;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.skillforge.core.shared.config.SkillForgeProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 平台管理主体、机构直授部署、真实模型与私有会话/用量的双宿主 HTTP 组合验收。 */
@Import(DeterministicModelCatalogConfiguration.class)
class MultiInstitutionPlatformHttpIntegrationTest extends AbstractIntegrationTest {
    @Autowired private PlanMapper plans;
    @Autowired private com.skillforge.core.foundation.persistence.TenantMapper tenantMapper;
    @Autowired private SkillForgeProperties properties;
    private int platformPort;
    private String platformToken;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private AgentRunBindings bindings;
    @Autowired private IntegrationProvenanceService provenance;
    @Autowired private JdbcIdentityPolicyPort identities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @LocalServerPort private int port;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 真实启动 Worker；调用只发到本测试拥有的 loopback Provider，不访问外部供应商。 */
    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry properties) {
        properties.add("agent-runtime.workers.auto-startup",()->"true");
    }

    /** 测试服务调用留下的线程身份在任何失败路径均清理。 */
    @AfterEach
    void clearIdentity() { TenantContext.clear(); ServicePrincipalContext.clear(); ManagementContext.clear(); }

    @Test
    @org.junit.jupiter.api.Tag("platform-deployment-browser")
    void browserRetainsUnknownOrdinaryInstitutionCreation() throws Exception {
        String slug = "tenant-recovery-" + SnowflakeIds.nextId();
        Path root = repositoryRoot();
        Path evidence = Files.createTempDirectory(Files.createDirectories(root.resolve(".agent-work-state")), "platform-tenant-recovery-");
        Process platform = startPlatform(root, evidence);
        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
                assertThat(platform.isAlive()).isTrue(); platformToken = login();
            });
            Path frontend = root.resolve("frontend-platform"), viteLog = evidence.resolve("vite.log");
            var viteBuilder = new ProcessBuilder("node", frontend.resolve("node_modules/vite/bin/vite.js").toString(), "--host", "127.0.0.1", "--port", "0", "--strictPort");
            viteBuilder.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
            viteBuilder.environment().put("E2E_BACKEND_URL", "http://127.0.0.1:" + platformPort);
            Process vite = viteBuilder.start();
            try (var owned = new KnowledgeBrowserProcesses(vite.toHandle())) {
                String origin = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                while (System.nanoTime() < deadline && vite.isAlive()) {
                    owned.capture();
                    var match = java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/").matcher(Files.readString(viteLog));
                    if (match.find()) { origin = "http://127.0.0.1:" + match.group(1); break; }
                    Thread.sleep(100);
                }
                assertThat(origin).as("平台前端未就绪：" + viteLog).isNotNull();
                var browser = new ProcessBuilder("node", frontend.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "--config=playwright.tenant-create-recovery.config.ts");
                browser.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
                var env = browser.environment(); env.put("E2E_BASE_URL", origin); env.put("E2E_ARTIFACT_DIR", evidence.toString());
                env.put("E2E_USERNAME", "gi14-http-admin"); env.put("E2E_PASSWORD", "synthetic-gi14-password"); env.put("E2E_NEW_TENANT_SLUG", slug);
                jdbc.execute("CREATE FUNCTION test_browser_tenant_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.username = 'browser-rollback' THEN RAISE EXCEPTION 'browser fixture failure'; END IF; RETURN NEW; END $$");
                jdbc.execute("CREATE TRIGGER test_browser_tenant_failure BEFORE INSERT ON nw_tenant_members FOR EACH ROW EXECUTE FUNCTION test_browser_tenant_failure()");
                try {
                    Process process = browser.start();
                    try (var browserOwned = new KnowledgeBrowserProcesses(process.toHandle())) {
                        deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                        while (process.isAlive() && System.nanoTime() < deadline) { browserOwned.capture(); owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                        assertThat(process.isAlive()).as("浏览器验收超时：" + evidence).isFalse();
                        assertThat(process.exitValue()).as("浏览器验收失败：" + evidence).isZero();
                    }
                } finally {
                    jdbc.execute("DROP TRIGGER test_browser_tenant_failure ON nw_tenant_members");
                    jdbc.execute("DROP FUNCTION test_browser_tenant_failure()");
                }
                var rollbackResult = json.readTree(Files.readString(evidence.resolve("tenant-create-rollback.json")));
                String recoveredSlug = rollbackResult.path("slug").asText();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenants WHERE slug=?", Long.class, recoveredSlug)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenant_members m JOIN nw_tenants t ON t.id=m.tenant_id WHERE t.slug=? AND m.username='browser-recovered'", Long.class, recoveredSlug)).isEqualTo(1);
                var result = json.readTree(Files.readString(evidence.resolve("tenant-create-recovery.json")));
                assertThat(result.path("slug").asText()).isEqualTo(slug);
                assertThat(result.path("posts").asInt()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenants WHERE slug=?", Long.class, slug)).isEqualTo(1);
                String requestId = result.path("requestId").asText();
                long tenantId = Long.parseLong(result.path("tenantId").asText());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=? AND username='fixture-admin'", Long.class, tenantId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_tenant_create_receipts WHERE request_id=CAST(? AS uuid) AND status='SUCCEEDED' AND tenant_id=?", Long.class, requestId, tenantId)).isEqualTo(1);
                var duplicate = Map.of("request_id", requestId, "payload", Map.of("tenant_name", "重复机构", "tenant_slug", slug + "-duplicate", "admin_username", "fixture-admin", "admin_password", "FixturePassword123"));
                platformRequest("POST", "/api/v1/admin/tenants/create-with-receipt", duplicate, null, 409);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenants WHERE slug=?", Long.class, slug + "-duplicate")).isZero();
                String rejectedId = UUID.randomUUID().toString();
                var rejected = Map.of("request_id", rejectedId, "payload", Map.of("tenant_name", "冲突机构", "tenant_slug", slug, "admin_username", "fixture-admin", "admin_password", "FixturePassword123"));
                assertThat(platformRequest("POST", "/api/v1/admin/tenants/create-with-receipt", rejected, null, 200).path("status").asText()).isEqualTo("REJECTED");
                assertThat(platformRequest("GET", "/api/v1/admin/tenants/create-with-receipt/result?request_id=" + rejectedId, null, null, 200).path("status").asText()).isEqualTo("REJECTED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_tenant_create_receipts WHERE request_id=CAST(? AS uuid) AND status='REJECTED' AND tenant_id IS NULL", Long.class, rejectedId)).isEqualTo(1);
                String suffix = Long.toString(SnowflakeIds.nextId());
                JsonNode readerRole = platformRequest("POST", "/api/v1/admin/foundation/access/role", Map.of(
                    "code", "tenant_receipt_reader_" + suffix, "name", "回执读取测试", "data_scope", "all",
                    "permissions", List.of("foundation.tenants.read"), "department_ids", List.of()), null, 200);
                String readerName = "tenant-receipt-reader-" + suffix, readerPassword = "ReaderPassword123";
                platformRequest("POST", "/api/v1/admin/foundation/access/member", Map.of("username", readerName,
                    "display_name", "回执读取测试", "password", readerPassword, "role_ids", List.of(readerRole.path("id").asText())), null, 200);
                String readerToken = loginAs(readerName, readerPassword).path("access_token").asText();
                var otherActorRead = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + platformPort
                    + "/api/v1/admin/tenants/create-with-receipt/result?request_id=" + requestId))
                    .header("X-Platform-Token", "Bearer " + readerToken).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(otherActorRead.statusCode()).isEqualTo(204);

                String failedId = UUID.randomUUID().toString(), failedSlug = slug + "-rollback";
                jdbc.execute("CREATE FUNCTION test_tenant_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.username = 'rollback-check' THEN RAISE EXCEPTION 'fixture failure'; END IF; RETURN NEW; END $$");
                jdbc.execute("CREATE TRIGGER test_tenant_receipt_failure BEFORE INSERT ON nw_tenant_members FOR EACH ROW EXECUTE FUNCTION test_tenant_receipt_failure()");
                try {
                    var failed = Map.of("request_id", failedId, "payload", Map.of("tenant_name", "回滚机构", "tenant_slug", failedSlug, "admin_username", "rollback-check", "admin_password", "FixturePassword123"));
                    platformRequest("POST", "/api/v1/admin/tenants/create-with-receipt", failed, null, 500);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenants WHERE slug=?", Long.class, failedSlug)).isZero();
                    assertThat(platformRequest("GET", "/api/v1/admin/tenants/create-with-receipt/result?request_id=" + failedId, null, null, 200).path("status").asText()).isEqualTo("FAILED");
                } finally {
                    jdbc.execute("DROP TRIGGER test_tenant_receipt_failure ON nw_tenant_members");
                    jdbc.execute("DROP FUNCTION test_tenant_receipt_failure()");
                }
            }
        } finally { stop(platform); }
    }

    @Test
    @org.junit.jupiter.api.Tag("platform-deployment-browser")
    void browserLoadsDeploymentImmediatelyAfterSelectingTenant() throws Exception {
        String tenant = createTenant(tenantMapper, plans, "browser-deployment-" + SnowflakeIds.nextId());
        jdbc.update("UPDATE nw_tenants SET name=? WHERE id=?", "浏览器部署机构", Long.valueOf(tenant));
        Path root = repositoryRoot();
        Path evidence = Files.createTempDirectory(Files.createDirectories(root.resolve(".agent-work-state")), "platform-deployment-browser-");
        Process platform = startPlatform(root, evidence);
        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
                assertThat(platform.isAlive()).isTrue();
                platformToken = login();
            });
            String model;
            TenantContext.setTenantId(tenant);
            try {
                model = PlatformModelFixtures.selectLlm(models, tenant, "https://api.deepseek.com", "deepseek-flash",
                        "fixture-key", "deepseek", "deepseek-api", "2026-09-13");
            } finally { TenantContext.clear(); }
            var template = platformRequest("POST", "/api/v1/platform/agent-definitions", Map.of(
                    "name", "浏览器授权助手", "price_type", "free", "price_cents", 0,
                    "distribution_policy", "AUTHORIZED_DEPLOY", "definition", definition(model, "测试模板")),
                    UUID.randomUUID().toString(), 200);
            String definitionId = template.path("definition_id").asText();
            platformRequest("POST", "/api/v1/platform/agent-definitions/publish", Map.of(
                    "definition_id", definitionId, "expected_revision", template.path("draft_revision").asLong()),
                    UUID.randomUUID().toString(), 200);
            // 机构已有另一模板的有效授权，不能误认为当前模板也已获授权。
            var otherTemplate = platformRequest("POST", "/api/v1/platform/agent-definitions", Map.of(
                    "name", "已有授权的其他助手", "price_type", "free", "price_cents", 0,
                    "distribution_policy", "AUTHORIZED_DEPLOY", "definition", definition(model, "其他模板")),
                    UUID.randomUUID().toString(), 200);
            String otherDefinitionId = otherTemplate.path("definition_id").asText();
            platformRequest("POST", "/api/v1/platform/agent-definitions/publish", Map.of(
                    "definition_id", otherDefinitionId, "expected_revision", otherTemplate.path("draft_revision").asLong()),
                    UUID.randomUUID().toString(), 200);
            platformRequest("POST", "/api/v1/platform/agent-grants", Map.of("definition_id", otherDefinitionId,
                    "target_tenant_id", tenant, "actions", List.of("deploy", "execute"), "allow_regrant", false,
                    "remaining_depth", 0), UUID.randomUUID().toString(), 200);
            Path frontend = root.resolve("frontend-platform"), viteLog = evidence.resolve("vite.log");
            var viteBuilder = new ProcessBuilder("node", frontend.resolve("node_modules/vite/bin/vite.js").toString(), "--host", "127.0.0.1", "--port", "0", "--strictPort");
            viteBuilder.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
            viteBuilder.environment().put("E2E_BACKEND_URL", "http://127.0.0.1:" + platformPort);
            Process vite = viteBuilder.start();
            try (var owned = new KnowledgeBrowserProcesses(vite.toHandle())) {
                String origin = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                while (System.nanoTime() < deadline && vite.isAlive()) {
                    owned.capture();
                    var match = java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/").matcher(Files.readString(viteLog));
                    if (match.find()) { origin = "http://127.0.0.1:" + match.group(1); break; }
                    Thread.sleep(100);
                }
                assertThat(origin).as("平台前端未就绪：" + viteLog).isNotNull();
                var browser = new ProcessBuilder("node", frontend.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "--config=playwright.deployment-real.config.ts");
                browser.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
                var env = browser.environment(); env.put("E2E_BASE_URL", origin); env.put("E2E_ARTIFACT_DIR", evidence.toString());
                env.put("E2E_USERNAME", "gi14-http-admin"); env.put("E2E_PASSWORD", "synthetic-gi14-password");
                env.put("E2E_TENANT_ID", tenant); env.put("E2E_INSTITUTION_URL", "http://127.0.0.1:" + port);
                env.put("E2E_DEFINITION_ID", definitionId);
                Process process = browser.start();
                try (var browserOwned = new KnowledgeBrowserProcesses(process.toHandle())) {
                    deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (process.isAlive() && System.nanoTime() < deadline) { browserOwned.capture(); owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                    assertThat(process.isAlive()).as("浏览器验收超时：" + evidence).isFalse();
                    assertThat(process.exitValue()).as("浏览器验收失败：" + evidence).isZero();
                }
                var association = json.readTree(Files.readString(evidence.resolve("onboarding-associate.json")));
                assertThat(association.path("tenant_id").asText()).isEqualTo(tenant);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_external_institution_mappings WHERE tenant_id=? AND integration_id=? AND namespace=? AND external_institution_ref=?",
                        Long.class, Long.valueOf(tenant), association.path("integration_id").asText(), "browser-onboarding", "browser-onboarding-institution")).isEqualTo(1L);
                assertThat(queryCount(jdbc, tenant, "SELECT count(*) FROM nw_service_principals WHERE tenant_id=? AND integration_id=?",
                        Long.valueOf(tenant), association.path("integration_id").asText())).isZero();
                var createdSkill = json.readTree(Files.readString(evidence.resolve("platform-skill-create-receipt.json")));
                var creationCommand = createdSkill.path("command");
                var creationReceipt = createdSkill.path("receipt");
                var replay = platformRequest("POST", "/api/v1/admin/capabilities/skill-drafts/create-with-receipt", creationCommand, null, 200);
                assertThat(replay).isEqualTo(creationReceipt);
                var changed = creationCommand.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("payload")).put("name", "不同内容");
                platformRequest("POST", "/api/v1/admin/capabilities/skill-drafts/create-with-receipt", changed, null, 409);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_skill_create_receipts WHERE tenant_id IS NULL AND request_id=CAST(? AS uuid)", Long.class,
                        creationReceipt.path("request_id").asText())).isEqualTo(1);
            }
        } finally { stop(platform); }
    }

    @Test
    @org.junit.jupiter.api.Tag("platform-deployment-browser")
    void institutionBrowserReusesGrantedDefinitionDirectory() throws Exception {
        String slug = "browser-auto-definition-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenantMapper, plans, slug), password = "SyntheticBrowser123";
        TenantContext.setTenantId(tenant);
        String model;
        try {
            createTestTenantAdministrator(tenant, "browser-deployment-admin", password);
            model = PlatformModelFixtures.selectLlm(models, tenant, "https://api.deepseek.com", "deepseek-flash",
                    "fixture-key", "deepseek", "deepseek-api", "2026-09-13");
        } finally { TenantContext.clear(); }
        Path root = repositoryRoot();
        Path evidence = Files.createTempDirectory(Files.createDirectories(root.resolve(".agent-work-state")), "institution-deployment-browser-");
        Process platform = startPlatform(root, evidence);
        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
                assertThat(platform.isAlive()).isTrue(); platformToken = login();
            });
            var definition = platformRequest("POST", "/api/v1/platform/agent-definitions", Map.of(
                    "name", "浏览器授权助手", "price_type", "free", "price_cents", 0,
                    "distribution_policy", "AUTHORIZED_DEPLOY", "definition", definition(model, "测试模板")), UUID.randomUUID().toString(), 200);
            String definitionId = definition.path("definition_id").asText();
            platformRequest("POST", "/api/v1/platform/agent-definitions/publish", Map.of("definition_id", definitionId,
                    "expected_revision", definition.path("draft_revision").asLong()), UUID.randomUUID().toString(), 200);
            platformRequest("POST", "/api/v1/platform/agent-grants", Map.of("definition_id", definitionId,
                    "target_tenant_id", tenant, "actions", List.of("deploy", "execute"), "allow_regrant", false,
                    "remaining_depth", 0), UUID.randomUUID().toString(), 200);
            try (var frontend = KnowledgeBrowserServer.startIsolated("institution-deployment-ui-")) {
                frontend.bindBackend("http://127.0.0.1:" + port);
                Path admin = root.resolve("frontend-admin");
                var browser = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                        "test", "--config=playwright.agent-deployment-real.config.ts");
                browser.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
                var env = browser.environment(); env.put("E2E_BASE_URL", frontend.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
                env.put("E2E_INSTITUTION_URL", "http://127.0.0.1:" + port); env.put("E2E_TENANT_SLUG", slug);
                env.put("E2E_PASSWORD", password); env.put("E2E_DEFINITION_ID", definitionId);
                Process process = browser.start();
                try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                    owned.capture(); assertThat(process.isAlive()).as("浏览器验收超时：" + evidence).isFalse();
                    assertThat(process.exitValue()).as("浏览器验收失败：" + evidence).isZero();
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_agent_deployments WHERE tenant_id=? AND definition_id=?",
                    Long.class, Long.valueOf(tenant), Long.valueOf(definitionId))).isEqualTo(1L);
        } finally { stop(platform); }
    }

    /** 平台JWT建立管理主体，管理HMAC开通机构，执行HMAC只进入固定机构运行域。 */
    @Test
    void governsInstitutionsStreamsActualRunsAndExportsTheirIndependentUsage() throws Exception {
        ensureFreePlan(plans);
        String namespace="platform-http-"+SnowflakeIds.nextId();
        Path root=repositoryRoot();
        Path evidence=root.resolve("backend-java/skillforge-institution-service/build/reports/platform-http/"+namespace);
        Files.createDirectories(evidence);
        Process platform=startPlatform(root,evidence);
        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(()->{
                assertThat(platform.isAlive()).as("平台子进程应存活，诊断见platform.log").isTrue();
                platformToken=login();
            });
            var runtimeContract=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(runtimeContract.statusCode()).isEqualTo(200);
            Files.writeString(evidence.resolve("institution-openapi.json"),runtimeContract.body());
            var platformContract=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+platformPort+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(platformContract.statusCode()).isEqualTo(200);
            Files.writeString(evidence.resolve("platform-openapi.json"),platformContract.body());
            JsonNode impactSchema=json.readTree(platformContract.body()).path("components").path("schemas").path("AgentDefinitionImpactResponse");
            assertThat(impactSchema.path("properties").path("definition_id").path("type").asText()).isEqualTo("string");
            Key provisioner=management(namespace,List.of(),List.of(),Set.of("platform.institutions.provision"));
            JsonNode tenantA=provision(provisioner,namespace,"institution-a");
            JsonNode tenantB=provision(provisioner,namespace,"institution-b");
            JsonNode platformTenant=provision(provisioner,namespace,"platform-business");
            JsonNode foreignTenant=provision(provisioner,namespace,"other-institution");
            assertThat(provision(provisioner,namespace,"institution-a").path("tenant_id").asText())
                    .isEqualTo(tenantA.path("tenant_id").asText());
            request(provisioner,"POST","/api/v1/platform/institutions/provision",
                    Map.of("namespace",namespace,"external_institution_ref","institution-a","name","不同开通内容",
                            "default_plan_code","free"),namespace+":institution-a",409);
            String tenantId=tenantA.path("tenant_id").asText();
            rejectsCurrentJwtAndMachineScopeWithdrawals(namespace,tenantId);
            try (var provider=new NativeChatOpenAiProvider(json,namespace)) {
                provider.start();
                TenantContext.setTenantId(tenantId);
                String model;
                try { model=PlatformModelFixtures.selectLlm(models,tenantId,provider.endpoint(),"platform-http-model",
                        "fixture-key","custom","custom-openai-compatible","custom-v1"); }
                finally { TenantContext.clear(); }
                Map<String,Object> createDefinition=Map.of(
                    "name",namespace+":公共助手","price_type","free","price_cents",0,"distribution_policy","AUTHORIZED_DEPLOY",
                    "definition",definition(model,"机构问答助手"));
                JsonNode definition=platformRequest("POST","/api/v1/platform/agent-definitions",createDefinition,namespace+":definition",200);
                String definitionId=definition.path("definition_id").asText();
                JsonNode published=platformRequest("POST","/api/v1/platform/agent-definitions/publish",Map.of(
                    "definition_id",definitionId,"expected_revision",definition.path("draft_revision").asLong()),namespace+":publish-v1",200);
                assertThat(published.path("version").asText()).isEqualTo("1.0.0");
                JsonNode personalTenant=platformRequest("POST","/api/v1/admin/tenants",Map.of(
                    "tenant_name","个人验收空间","tenant_slug",namespace+"-personal","plan_code","free",
                    "admin_username","personal-admin","admin_password","PersonalFixture1234"),null,201);
                assertThat(personalTenant.path("member_count").asLong()).isEqualTo(1);
                assertThat(personalTenant.path("status").asText()).isEqualTo("active");
                List<String> ownedTenants=List.of(tenantId,tenantB.path("tenant_id").asText(),platformTenant.path("tenant_id").asText(),personalTenant.path("id").asText());
                Set<String> scopes=Set.of("platform.institutions.read","platform.institution_credentials.issue",
                    "platform.agent_grants.create","platform.agent_grants.revoke","platform.agent_deployments.create",
                    "platform.agent_deployments.read","platform.usage.events.read");
                Key govA=management(namespace,ownedTenants,List.of(definitionId),scopes);
                Key govB=management(namespace,List.of(foreignTenant.path("tenant_id").asText()),List.of(definitionId),scopes);
                Key execA=execution(govA,tenantA);
                Key execB=execution(govA,tenantB);
                Key execForeign=execution(govB,foreignTenant);
                JsonNode store=scope(execA,"store-a");
                scope(execA,"store-b");
                request(govB,"POST","/api/v1/platform/institution-credentials/issue",
                    Map.of("tenant_id",tenantId,"integration_id",tenantA.path("integration_id").asText(),
                        "scopes",List.of("sessions.write")),"foreign-credential",403);
                request(govA,"POST","/api/v1/open-platform/end-users/ensure",Map.of("external_user_ref","patient-a"),null,401);
                request(execA,"GET","/api/v1/platform/institutions",null,null,401);
                JsonNode grant=grant(govA,tenantId,definitionId);
                var first=deploy(govA,tenantA,definitionId,grant,"doctor-a");
                var second=deploy(govA,tenantA,definitionId,grant,"doctor-b");
                for(JsonNode institution:List.of(tenantB,platformTenant)) {
                    JsonNode ownGrant=grant(govA,institution.path("tenant_id").asText(),definitionId);
                    deploy(govA,institution,definitionId,ownGrant,"doctor-a");
                    deploy(govA,institution,definitionId,ownGrant,"doctor-b");
                }
                assertThat(first.path("ready").asBoolean()).isTrue();
                assertThat(deploy(govA,tenantA,definitionId,grant,"doctor-a").path("agent_id")).isEqualTo(first.path("agent_id"));
                request(govB,"POST","/api/v1/platform/agent-deployments",
                    deployment(tenantA,definitionId,grant,"rogue"),"rogue-deployment",403);
                long countBefore=jdbc.queryForObject("SELECT count(*) FROM nw_tenants",Long.class);
                request(govA,"POST","/api/v1/integration/tenants/provision",Map.of("name","removed"),"removed",401);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenants",Long.class)).isEqualTo(countBefore);
                verifiesPersonalTenantNativeRun(namespace,model,personalTenant,definitionId,govA,govB,execA,execB);
            long user=endUser(execA,"patient-a");
            assertThat(endUser(execA,"patient-a")).isEqualTo(user);
            assertThat(endUser(execB,"patient-a")).isNotEqualTo(user);
            String session=session(execA,first.path("agent_id").asLong(),user,"room-a","store-a");
            assertThat(session(execA,first.path("agent_id").asLong(),user,"room-a","store-a")).isEqualTo(session);
            String secondSession=session(execA,second.path("agent_id").asLong(),user,"room-a","store-a");
            assertThat(secondSession).isNotEqualTo(session);
            request(execForeign,"GET","/api/v1/open-platform/sessions/events?sessionId="+session,null,null,404);
            request(execA,"POST","/api/v1/open-platform/sessions/messages",
                    message(session,"不可信身份",Map.of("tenant_id",foreignTenant.path("tenant_id").asText())),"bad-context",422);

            String messageKey="k".repeat(256);
            JsonNode receipt=request(execA,"POST","/api/v1/open-platform/sessions/messages",
                    message(session,"机构问答",Map.of("business_order","appointment-a")),messageKey,200);
            String runId=receipt.path("run_id").asText();
            assertThat(runId).isNotBlank().hasSizeLessThanOrEqualTo(240);
            assertThat(receipt.path("input_id").asText()).hasSizeLessThanOrEqualTo(128);
            JsonNode replay=request(execA,"POST","/api/v1/open-platform/sessions/messages",
                    message(session,"机构问答",Map.of("business_order","appointment-a")),messageKey,200);
            assertThat(replay.path("run_id").asText()).isEqualTo(runId);
            request(execA,"POST","/api/v1/open-platform/sessions/messages",
                    message(session,"不同输入",Map.of()),messageKey,409);
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(()->{
                JsonNode events=request(execA,"GET","/api/v1/open-platform/sessions/events?sessionId="+session,null,null,200);
                assertThat(events.path("events").toString()).contains("execution.completed","fixture-answer-");
                boolean finalSeen=false;
                for (JsonNode event:events.path("events")) {
                    JsonNode data=event.path("data");
                    if(event.path("event").asText().startsWith("message.") && "assistant".equals(data.path("role").asText())
                            && runId.equals(data.path("run_id").asText()) && "completed".equals(data.path("state").asText())) {
                        assertThat(data.path("phase").asText()).isEqualTo("final");finalSeen=true;
                    }
                }
                assertThat(finalSeen).as("正式终态回答必须带有final阶段").isTrue();
            });
            assertThat(request(execA,"GET","/api/v1/open-platform/sessions/status?sessionId="+session+"&runId="+runId,
                    null,null,200).path("status").asText()).isEqualTo("completed");
            assertThat(request(execA,"GET","/api/v1/open-platform/sessions/history?sessionId="+session,
                    null,null,200).path("messages").toString()).contains("fixture-answer-");
            request(execB,"GET","/api/v1/open-platform/sessions/history?sessionId="+session,null,null,404);
            assertThat(request(execA,"GET","/api/v1/open-platform/sessions/deliverables?sessionId="+session,null,null,200).isArray()).isTrue();
            request(execForeign,"GET","/api/v1/open-platform/sessions/deliverables?sessionId="+session,null,null,404);
            var source=provenance.resolveRun(tenantId,runId);
            assertThat(source.originKind()).isEqualTo("external");
            assertThat(source.integrationId()).isEqualTo(tenantA.path("integration_id").asText());
            assertThat(source.businessScopeId()).isEqualTo(store.path("business_scope_id").asText());
            RunRef originalRun=new RunRef(new TenantId(tenantId),runId);
            assertThat(bindings.find(originalRun).orElseThrow().member().agentDefinitionVersion()).isEqualTo("1.0.0");
            JsonNode events=request(execA,"GET","/api/v1/open-platform/sessions/events?sessionId="+session,null,null,200);
            String cursor=events.path("events").get(0).path("event_id").asText();
            assertThat(cursor).isNotBlank();
            disconnectStream(execA,session,cursor);
            JsonNode recovered=request(execA,"GET","/api/v1/open-platform/sessions/events?sessionId="+session+"&cursor="+cursor,null,null,200);
            assertThat(recovered.path("events").toString()).doesNotContain("\"event_id\":\""+cursor+"\"");
            assertThat(recovered.path("events").toString()).contains("fixture-answer-");

            JsonNode detail=platformRequest("GET","/api/v1/platform/agent-definitions/detail?definition_id="+definitionId,null,null,200);
            Map<String,Object> updateDefinition=Map.of(
                "definition_id",definitionId,"expected_revision",detail.path("draft_revision").asLong(),
                "payload",Map.of("definition",definition(model,"新版问答角色")));
            JsonNode updated=platformRequest("PUT","/api/v1/platform/agent-definitions/update",updateDefinition,namespace+":update-v2",200);
            platformRequest("POST","/api/v1/platform/agent-definitions/publish",Map.of(
                "definition_id",definitionId,"expected_revision",updated.path("draft_revision").asLong()),namespace+":publish-v2",200);
            assertThat(platformRequest("POST","/api/v1/platform/agent-definitions",createDefinition,namespace+":definition",200))
                    .as("后续编辑/发布后创建重试仍返回原安全回执").isEqualTo(definition);
            assertThat(platformRequest("PUT","/api/v1/platform/agent-definitions/update",updateDefinition,namespace+":update-v2",200))
                    .as("更新重试不能因当前发布变化重组原响应").isEqualTo(updated);
            assertThat(platformRequest("POST","/api/v1/platform/agent-definitions/publish",Map.of(
                    "definition_id",definitionId,"expected_revision",definition.path("draft_revision").asLong()),namespace+":publish-v1",200))
                    .as("旧发布幂等键必须返回原发布，不受新版本或CAS变化影响").isEqualTo(published);
            JsonNode next=request(execA,"POST","/api/v1/open-platform/sessions/messages",message(secondSession,"新版问答",Map.of()),"message-b",200);
            assertThat(bindings.find(new RunRef(new TenantId(tenantId),next.path("run_id").asText()))
                    .orElseThrow().member().agentDefinitionVersion()).isEqualTo("1.1.0");
            assertThat(bindings.find(originalRun).orElseThrow().member().agentDefinitionVersion()).isEqualTo("1.0.0");
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(()->{
                JsonNode nextEvents=request(execA,"GET","/api/v1/open-platform/sessions/events?sessionId="+secondSession,
                        null,null,200);
                assertThat(nextEvents.path("events").toString()).contains("execution.completed","fixture-answer-");
            });
            await().atMost(Duration.ofSeconds(90)).untilAsserted(()->{
                JsonNode exported=request(govA,"GET","/api/v1/platform/usage/events?tenant_id="+tenantId+"&limit=100",null,null,200);
                assertThat(exported.path("items").toString()).contains(runId,"llm.chat","1.0.0");
                for (JsonNode item:exported.path("items")) {
                    assertThat(item.path("tenant_id").asText()).isEqualTo(tenantId);
                    assertThat(item.has("application_id")).isFalse();
                }
            });
            JsonNode foreignUsage=request(govB,"GET","/api/v1/platform/usage/events?limit=100",null,null,200);
            assertThat(foreignUsage.path("items").toString()).doesNotContain(runId);
            verifiesDefinitionImpactAndRecovery(namespace,model,tenantA,tenantB,first,session,execA,govA,grant);
            var owner=bindings.find(originalRun).orElseThrow().owner();
            var verifications=queryRows(jdbc,tenantId,"""
                    SELECT v.verification_ref FROM nw_runtime_identity_verifications v
                    JOIN nw_runtime_authentication_facts a ON a.tenant_id=v.tenant_id AND a.authentication_ref=v.authentication_ref
                    WHERE a.tenant_id=? AND a.session_id=? AND a.principal_kind='SERVICE'
                      AND a.integration_id=? AND a.channel='open-platform' LIMIT 1
                    """,tenantId,session,tenantA.path("integration_id").asText());
            assertThat(verifications).hasSize(1);
            var originalIdentity=identities.originalVerification(owner,
                    new ResultRef(verifications.getFirst().get("verification_ref").toString())).orElseThrow();
            var authorization=new AuthorizationRequest(originalIdentity,"session.claim",SessionPolicyResource.claim(owner),"test-trace","test-span");
            assertThat(identities.authorize(authorization)).isInstanceOf(AuthorizationResult.Allowed.class);
            request(govA,"POST","/api/v1/platform/agent-grants/revoke",Map.of("grant_id",grant.path("grant_id").asText(),
                "expected_revision",grant.path("revision").asLong()),namespace+":revoke",200);
            assertThat(identities.authorize(authorization)).isInstanceOf(AuthorizationResult.Denied.class);
            Map<String,Object> beforeRejectedInput=executionFactCounts(tenantId);
            request(execA,"POST","/api/v1/open-platform/sessions/messages",message(session,"撤权后拒绝",Map.of()),"revoked",403);
            assertThat(executionFactCounts(tenantId)).as("当前撤权拒绝不能写入认证、Run、输入、公开消息或用量事实")
                    .isEqualTo(beforeRejectedInput);
            assertThat(bindings.find(originalRun).orElseThrow().member().agentDefinitionVersion()).isEqualTo("1.0.0");
            }
        } finally { stop(platform); }
    }

    /** 普通个人Tenant沿真实本地账号和原生入口执行，来源与费用不借用门诊或平台业务责任域。 */
    private void verifiesPersonalTenantNativeRun(String namespace,String model,JsonNode tenant,String definitionId,
            Key governor,Key foreignGovernor,Key clinicA,Key clinicB)throws Exception {
        String personalTenantId=tenant.path("id").asText();
        TenantContext.setTenantId(personalTenantId);
        try { models.select(personalTenantId,new ModelSelections(model,null,null)); }
        finally { TenantContext.clear(); }
        JsonNode personalGrant=grant(governor,personalTenantId,definitionId);
        // 原生部署没有外部映射；显式null不能由缺失integration误转为空字符串。
        var payload=new java.util.LinkedHashMap<String,Object>();
        payload.put("definition_id",definitionId);payload.put("grant_id",personalGrant.path("grant_id").asText());
        payload.put("integration_id",null);payload.put("external_agent_ref",namespace+":personal-agent");
        payload.put("name","个人助手");payload.put("resource_bindings",List.of());
        payload.put("delegation_bindings",List.of());payload.put("parameters",Map.of());
        JsonNode deployment=request(governor,"POST","/api/v1/platform/agent-deployments",
            Map.of("tenant_id",personalTenantId,"payload",payload),namespace+":personal-deploy",200);
        assertThat(deployment.path("tenant_id").asText()).isEqualTo(personalTenantId);
        assertThat(deployment.path("integration_id").isNull()).isTrue();
        assertThat(deployment.path("ready").asBoolean()).isTrue();
        String agentId=deployment.path("agent_id").asText();
        JsonNode login=institutionRequest(null,"POST","/api/v1/auth/login",Map.of(
            "tenant_slug",tenant.path("slug").asText(),"username","personal-admin","password","PersonalFixture1234"),200);
        assertThat(login.path("tenant_id").asText()).isEqualTo(personalTenantId);
        assertThat(login.path("mfa_state").asText()).isEqualTo("full");
        String token=login.path("access_token").asText();
        assertThat(token).isNotBlank();
        var securitySession=queryRows(jdbc,personalTenantId,
            "SELECT member_id FROM nw_foundation_security_sessions WHERE scope_key=? AND id=? AND state='full' AND revoked_at IS NULL",
            "tenant:"+personalTenantId,Long.parseLong(login.path("session_id").asText()));
        assertThat(securitySession).hasSize(1);
        String actor="member:"+securitySession.getFirst().get("member_id");
        JsonNode user=institutionRequest(token,"POST","/api/v1/chat/end-users",Map.of("display_name","个人空间使用者"),200);
        long endUserId=Long.parseLong(user.path("id").asText());
        JsonNode session=institutionRequest(token,"POST","/api/v1/chat/sessions",Map.of(
            "agent_id",agentId,"end_user_id",endUserId),200);
        String sessionId=session.path("session_id").asText();
        assertThat(sessionId).isNotBlank();
        JsonNode accepted=institutionRequest(token,"POST","/api/v1/chat",Map.of(
            "session_id",sessionId,"agent_id",agentId,"client_message_id",namespace+":personal-input",
            "content",List.of(Map.of("type","text","text","个人空间问答")),
            "context",Map.of("end_user_id",endUserId),"references",List.of()),202);
        assertThat(accepted.path("session_id").asText()).isEqualTo(sessionId);
        String runId=accepted.path("execution_id").asText();
        assertThat(runId).isNotBlank();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(()->{
            JsonNode events=institutionRequest(token,"GET","/api/v1/chat/events?session_id="+sessionId+"&limit=100",null,200);
            boolean completed=false,finalSeen=false;
            for(JsonNode event:events.path("events")) {
                JsonNode data=event.path("data");
                if(!runId.equals(data.path("run_id").asText()))continue;
                if("execution.completed".equals(event.path("event").asText()))completed=true;
                if("assistant".equals(data.path("role").asText())&&"completed".equals(data.path("state").asText())) {
                    assertThat(data.path("phase").asText()).isEqualTo("final");finalSeen=true;
                }
            }
            assertThat(completed).as("个人原生Run须真实完成").isTrue();
            assertThat(finalSeen).as("个人原生Run须交付final回答").isTrue();
        });
        assertThat(institutionRequest(token,"GET","/api/v1/chat/history?session_id="+sessionId,null,200)
            .path("messages").toString()).contains("fixture-answer-");
        var sessionSource=provenance.resolveSession(personalTenantId,sessionId);
        assertThat(sessionSource.originKind()).isEqualTo("native");
        assertThat(sessionSource.actorRef()).isEqualTo(actor);
        assertThat(sessionSource.responsibilityRef()).isEqualTo("tenant:"+personalTenantId);
        assertThat(sessionSource.integrationId()).isNull();
        var runSource=provenance.resolveRun(personalTenantId,runId);
        assertThat(runSource.originKind()).isEqualTo("native");
        assertThat(runSource.actorRef()).isEqualTo(actor);
        assertThat(runSource.responsibilityRef()).isEqualTo("tenant:"+personalTenantId);
        assertThat(runSource.integrationId()).isNull();
        assertThat(runSource.agentId()).isEqualTo(Long.valueOf(agentId));
        assertThat(runSource.deploymentId()).isEqualTo(Long.valueOf(deployment.path("deployment_id").asText()));
        assertThat(runSource.deploymentRevision()).isEqualTo(deployment.path("deployment_revision").asLong());
        assertThat(runSource.grantId()).isEqualTo(Long.valueOf(personalGrant.path("grant_id").asText()));
        var frozen=bindings.find(new RunRef(new TenantId(personalTenantId),runId)).orElseThrow();
        assertThat(frozen.actor().value()).isEqualTo(agentId);
        assertThat(frozen.member().agentDefinitionVersion()).isEqualTo("1.0.0");
        var authorization=frozen.scope().authorizations().get(agentId);
        assertThat(authorization).isNotNull();
        assertThat(authorization.deploymentId()).isEqualTo(runSource.deploymentId());
        assertThat(authorization.deploymentRevision()).isEqualTo(runSource.deploymentRevision());
        // 两种读取主体共用稳定事实身份，授权绑定的next_cursor不参与跨视图比较。
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(()->{
            JsonNode own=institutionRequest(token,"GET","/api/v1/admin/usage/events?run_id="+runId+"&limit=100",null,200);
            JsonNode platform=request(governor,"GET","/api/v1/platform/usage/events?tenant_id="+personalTenantId+"&run_id="+runId+"&limit=100",null,null,200);
            var platformFacts=new java.util.LinkedHashMap<String,JsonNode>();
            for(JsonNode item:platform.path("items"))platformFacts.put(item.path("event_id").asText(),item);
            assertThat(own.path("items").isEmpty()).isFalse();
            assertThat(own.path("items").findValuesAsText("event_id")).containsExactlyInAnyOrderElementsOf(platformFacts.keySet());
            for(JsonNode item:own.path("items")) {
                assertThat(item.path("tenant_id").asText()).isEqualTo(personalTenantId);
                assertThat(item.path("origin_kind").asText()).isEqualTo("native");
                assertThat(item.path("actor_ref").asText()).isEqualTo(actor);
                assertThat(item.path("responsibility_ref").asText()).isEqualTo("tenant:"+personalTenantId);
                assertThat(item.path("deployment_id").asText()).isEqualTo(deployment.path("deployment_id").asText());
                JsonNode same=platformFacts.get(item.path("event_id").asText());
                for(String field:List.of("event_id","source_event_id","run_id","tenant_id","deployment_id","agent_id"))
                    assertThat(same.path(field)).as("同一用量事实字段 %s",field).isEqualTo(item.path(field));
            }
        });
        for(Key other:List.of(clinicA,clinicB))
            assertThat(request(other,"GET","/api/v1/open-platform/usage/events?run_id="+runId+"&limit=100",null,null,200).path("items").isEmpty()).isTrue();
        assertThat(request(foreignGovernor,"GET","/api/v1/platform/usage/events?run_id="+runId+"&limit=100",null,null,200).path("items").isEmpty()).isTrue();
        request(clinicA,"GET","/api/v1/open-platform/sessions/history?sessionId="+sessionId,null,null,404);
    }

    /** GI-01：精确影响、原回执、缺槽新Run、下架及恢复共同消费同一稳定定义/部署。 */
    private void verifiesDefinitionImpactAndRecovery(String namespace,String model,JsonNode tenant,JsonNode otherTenant,JsonNode deployment,
            String session,Key execution,Key governor,JsonNode grant)throws Exception {
        String definitionId=deployment.path("definition_id").asText(),tenantId=tenant.path("tenant_id").asText();
        JsonNode current=platformRequest("GET","/api/v1/platform/agent-definitions/detail?definition_id="+definitionId,null,null,200);
        assertThat(current.path("version").asText()).isEqualTo("1.1.0");
        var required=new java.util.LinkedHashMap<String,Object>(definition(model,"资料助手 {{parameters.department}}"));
        required.put("resource_slots",List.of(Map.of("key","clinic_knowledge","label","门诊资料","description","本地已授权资料","capability_type","KNOWLEDGE_SOURCE")));
        required.put("delegation_slots",List.of(Map.of("key","specialist","label","专科协作","description","本机构协作角色")));
        Map<String,Object> edit=Map.of("definition_id",definitionId,"expected_revision",current.path("draft_revision").asLong(),
                "payload",Map.of("definition",required));
        JsonNode draft=platformRequest("PUT","/api/v1/platform/agent-definitions/update",edit,namespace+":impact-draft",200);
        Key reader=management(namespace,List.of(tenantId),List.of(definitionId),Set.of("platform.agent_definitions.read"));
        String impactPath="/api/v1/platform/agent-definitions/impact?definition_id="+definitionId+"&draft_revision="+draft.path("draft_revision").asLong();
        Map<String,Object> beforePreview=executionFactCounts(tenantId);
        JsonNode impact=request(reader,"GET",impactPath,null,null,200);
        assertThat(impact.path("definition_id").asText()).isEqualTo(definitionId);
        assertThat(impact.path("draft_revision").asLong()).isEqualTo(draft.path("draft_revision").asLong());
        assertThat(impact.path("current_published_revision").asText()).isEqualTo("1.1.0");
        assertThat(impact.path("changes").path("added_resource_slots").get(0).path("key").asText()).isEqualTo("clinic_knowledge");
        assertThat(impact.path("changes").path("added_delegation_slots").get(0).path("key").asText()).isEqualTo("specialist");
        assertThat(impact.path("changes").path("added_parameter_keys").toString()).contains("department");
        assertThat(impact.path("visible_deployment_count").asInt()).isEqualTo(2);
        assertThat(impact.path("deployments")).hasSize(2);
        for(JsonNode affected:impact.path("deployments")) {
            assertThat(affected.path("tenant_id").asText()).isEqualTo(tenantId);
            assertThat(affected.path("deployment_revision").asLong()).isPositive();
            assertThat(affected.path("current_declarations_satisfied").asBoolean()).isTrue();
            assertThat(affected.path("draft_declarations_satisfied").asBoolean()).isFalse();
            assertThat(affected.path("draft_issues").toString()).contains("binding_required","parameter_required");
            assertThat(affected.has("parameters")).isFalse();
            assertThat(affected.has("resource_bindings")).isFalse();
            assertThat(affected.has("delegation_bindings")).isFalse();
        }
        assertThat(executionFactCounts(tenantId)).as("impact只读不能创建Run、输入、费用或公开消息").isEqualTo(beforePreview);
        String otherTenantId=otherTenant.path("tenant_id").asText();
        Key twoTenantReader=management(namespace,List.of(tenantId,otherTenantId),List.of(definitionId),Set.of("platform.agent_definitions.read"));
        JsonNode multiImpact=request(twoTenantReader,"GET",impactPath,null,null,200);
        assertThat(multiImpact.path("visible_deployment_count").asInt()).isEqualTo(4);
        for(JsonNode affected:multiImpact.path("deployments"))
            assertThat(affected.path("tenant_id").asText()).isIn(tenantId,otherTenantId);
        assertThat(platformRequest("GET","/api/v1/platform/agent-definitions/detail?definition_id="+definitionId,null,null,200).path("draft_revision"))
                .as("多机构只读预览后平台读取上下文必须恢复").isEqualTo(draft.path("draft_revision"));
        request(reader,"POST","/api/v1/platform/agent-definitions/publish",Map.of("definition_id",definitionId,
                "expected_revision",draft.path("draft_revision").asLong()),namespace+":reader-cannot-publish",403);
        JsonNode newer=platformRequest("PUT","/api/v1/platform/agent-definitions/update",Map.of("definition_id",definitionId,
                "expected_revision",draft.path("draft_revision").asLong(),"payload",Map.of("description","影响预览后继续编辑")),namespace+":after-impact",200);
        platformRequest("POST","/api/v1/platform/agent-definitions/publish",Map.of("definition_id",definitionId,
                "expected_revision",draft.path("draft_revision").asLong()),namespace+":stale-impact-publish",409);
        assertThat(platformRequest("GET","/api/v1/platform/agent-definitions/detail?definition_id="+definitionId,null,null,200).path("version").asText()).isEqualTo("1.1.0");
        platformRequest("PUT","/api/v1/platform/agent-definitions/update",Map.of("definition_id",definitionId,
                "expected_revision",current.path("draft_revision").asLong(),"payload",Map.of("description","同键不同请求")),namespace+":impact-draft",409);
        JsonNode changed=platformRequest("POST","/api/v1/platform/agent-definitions/publish",Map.of("definition_id",definitionId,
                "expected_revision",newer.path("draft_revision").asLong()),namespace+":publish-required-slots",200);
        assertThat(changed.path("version").asText()).isEqualTo("1.2.0");
        JsonNode notReady=request(governor,"GET","/api/v1/platform/agent-deployments?tenant_id="+tenantId,null,null,200);
        boolean stable=false;
        for(JsonNode item:notReady.path("items")) if(item.path("deployment_id").equals(deployment.path("deployment_id"))) {
            stable=true;
            assertThat(item.path("agent_id")).isEqualTo(deployment.path("agent_id"));
            assertThat(item.path("deployment_revision")).isEqualTo(deployment.path("deployment_revision"));
            assertThat(item.path("ready").asBoolean()).isFalse();
            assertThat(item.path("issues").toString()).contains("binding_required","parameter_required");
        }
        assertThat(stable).isTrue();
        Map<String,Object> beforeNotReady=executionFactCounts(tenantId);
        request(execution,"POST","/api/v1/open-platform/sessions/messages",message(session,"缺槽不得回退旧发布",Map.of()),namespace+":missing-slots",422);
        Map<String,Object> afterNotReady=executionFactCounts(tenantId);
        // 当前身份已成功验证并独立提交安全事实；声明未就绪只阻止其后的业务受理。
        var expectedNotReady=new java.util.LinkedHashMap<>(beforeNotReady);
        expectedNotReady.put("authentication_facts",((Number)beforeNotReady.get("authentication_facts")).longValue()+1);
        assertThat(afterNotReady).as("缺槽拒绝只保留合法认证，不产生新Run、绑定、材料、公开消息或计量").isEqualTo(expectedNotReady);
        Map<String,Object> deprecate=Map.of("definition_id",definitionId,"expected_revision",newer.path("draft_revision").asLong());
        JsonNode deprecated=platformRequest("POST","/api/v1/platform/agent-definitions/deprecate",deprecate,namespace+":deprecate",200);
        request(governor,"POST","/api/v1/platform/agent-deployments",deployment(tenant,definitionId,grant,"after-deprecation"),namespace+":disabled-deploy",403);
        Map<String,Object> beforeDisabled=executionFactCounts(tenantId);
        request(execution,"POST","/api/v1/open-platform/sessions/messages",message(session,"下架不受理",Map.of()),namespace+":disabled-message",403);
        assertThat(executionFactCounts(tenantId)).isEqualTo(beforeDisabled);
        Map<String,Object> restore=Map.of("definition_id",definitionId,"expected_revision",deprecated.path("draft_revision").asLong(),"published_revision","1.0.0");
        JsonNode restored=platformRequest("POST","/api/v1/platform/agent-definitions/restore",restore,namespace+":restore",200);
        assertThat(restored.path("version").asText()).as("恢复草稿不回退当前发布指针").isEqualTo("1.2.0");
        assertThat(platformRequest("POST","/api/v1/platform/agent-definitions/restore",restore,namespace+":restore",200)).isEqualTo(restored);
        assertThat(platformRequest("POST","/api/v1/platform/agent-definitions/deprecate",deprecate,namespace+":deprecate",200)).isEqualTo(deprecated);
        platformRequest("POST","/api/v1/platform/agent-definitions/restore",Map.of("definition_id",definitionId,
                "expected_revision",deprecated.path("draft_revision").asLong(),"published_revision","1.1.0"),namespace+":restore",409);
        Map<String,Object> beforeRepublish=executionFactCounts(tenantId);
        JsonNode republished=platformRequest("POST","/api/v1/platform/agent-definitions/publish",Map.of("definition_id",definitionId,
                "expected_revision",restored.path("draft_revision").asLong()),namespace+":republish",200);
        assertThat(republished.path("version").asText()).isEqualTo("1.3.0");
        assertThat(executionFactCounts(tenantId)).as("历史恢复和重新发布不能重放任何旧Run或发送积压输出").isEqualTo(beforeRepublish);
        JsonNode history=platformRequest("GET","/api/v1/platform/agent-definitions/history?definition_id="+definitionId,null,null,200);
        assertThat(history).hasSize(4);
        JsonNode ready=request(governor,"GET","/api/v1/platform/agent-deployments?tenant_id="+tenantId,null,null,200);
        for(JsonNode item:ready.path("items"))assertThat(item.path("ready").asBoolean()).isTrue();
        assertThat(platformRequest("POST","/api/v1/platform/agent-definitions/restore",restore,namespace+":restore",200))
                .as("后续重新发布仍读回原恢复草稿回执").isEqualTo(restored);
        JsonNode explicit=request(execution,"POST","/api/v1/open-platform/sessions/messages",message(session,"显式调用恢复发布",Map.of()),namespace+":after-restore",200);
        String restoredRun=explicit.path("run_id").asText();
        assertThat(bindings.find(new RunRef(new TenantId(tenantId),restoredRun)).orElseThrow().member().agentDefinitionVersion()).isEqualTo("1.3.0");
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(()->{
            JsonNode status=request(execution,"GET","/api/v1/open-platform/sessions/status?sessionId="+session+"&runId="+restoredRun,null,null,200);
            assertThat(status.path("status").asText()).isEqualTo("completed");
        });
    }

    /**
     * A73：保持真实会话和签名凭据有效，仅收窄当前权威动作；同一JWT和同一HMAC身份后续均403。
     * 使用独立非管理员角色和管理主体，不更改平台seed管理员，也不触碰其他场景的授权。
     */
    private void rejectsCurrentJwtAndMachineScopeWithdrawals(String namespace,String tenantId)throws Exception {
        String suffix=Long.toString(SnowflakeIds.nextId());
        JsonNode role=platformRequest("POST","/api/v1/admin/foundation/access/role",Map.of(
            "code","gi14_reader_"+suffix,"name","A73用量读取员","data_scope","all",
            "permissions",List.of("platform.usage.events.read"),"department_ids",List.of()),null,200);
        assertThat(role.path("built_in").asBoolean()).isFalse();
        assertThat(role.path("administrator").asBoolean()).isFalse();
        String username="gi14-reader-"+suffix,password="A73-fixture-password-"+suffix;
        JsonNode member=platformRequest("POST","/api/v1/admin/foundation/access/member",Map.of(
            "username",username,"display_name","A73专用读取成员","password",password,
            "role_ids",List.of(role.path("id").asText())),null,200);
        JsonNode login=loginAs(username,password);
        String sameJwt=login.path("access_token").asText();
        assertThat(sameJwt).isNotBlank();
        assertThat(login.path("expires_in").asInt()).isPositive();
        assertThat(login.path("permissions").toString()).contains("platform.usage.events.read");
        String usage="/api/v1/platform/usage/events?tenant_id="+tenantId;
        String personCursor=platformRequestAs(sameJwt,"GET",usage,null,null,200).path("next_cursor").asText();
        assertThat(personCursor).isNotBlank();
        String sessionId=login.path("session_id").asText();
        assertThat(sessionId).isNotBlank();
        assertThat(activePlatformSession(sessionId,member.path("id").asText())).isTrue();

        Map<String,Object> institution=jdbc.queryForMap("SELECT id,name,status,plan_id FROM nw_tenants WHERE id=?",Long.parseLong(tenantId));
        long institutionCount=jdbc.queryForObject("SELECT count(*) FROM nw_tenants",Long.class);
        long usageCount=usageFactCount(tenantId);
        // 正式角色更新会同时撤销安全会话。此处在测试权威库只改动作与CAS版本，专门证明当前权限边界，
        // 而不是把token撤销产生的401冒充为有效JWT的403。
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.execute("SELECT set_config('app.tenant_id','',true)");
            long scopeVersion=jdbc.queryForObject("SELECT authorization_version FROM nw_foundation_access_scopes WHERE scope_key='platform' FOR UPDATE",Long.class);
            assertThat(jdbc.update("DELETE FROM nw_foundation_role_permissions WHERE scope_key='platform' AND role_id=? AND permission_code='platform.usage.events.read'",
                role.path("id").asLong())).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nw_foundation_roles SET lock_version=lock_version+1,updated_at=now() WHERE scope_key='platform' AND id=? AND lock_version=? AND NOT built_in AND NOT administrator",
                role.path("id").asLong(),role.path("lock_version").asLong())).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nw_foundation_access_scopes SET authorization_version=authorization_version+1 WHERE scope_key='platform' AND authorization_version=?",scopeVersion)).isEqualTo(1);
        });
        platformRequestAs(sameJwt,"GET",usage+"&cursor="+java.net.URLEncoder.encode(personCursor,StandardCharsets.UTF_8),null,null,403);
        assertThat(activePlatformSession(sessionId,member.path("id").asText())).as("同JWT仍有未过期且未撤销的真实安全会话").isTrue();
        assertNoInstitutionOrUsageChange(tenantId,institution,institutionCount,usageCount);

        Key sameMachine=management(namespace,List.of(tenantId),List.of(),Set.of("platform.usage.events.read"));
        String machineCursor=request(sameMachine,"GET",usage,null,null,200).path("next_cursor").asText();
        assertThat(machineCursor).isNotBlank();
        String hash=TokenHash.sha256Hex(sameMachine.apiKey());
        Map<String,Object> credential=jdbc.queryForMap("SELECT principal_id,key_hash,credential_version,revision FROM nw_platform_management_principals WHERE key_hash=?",hash);
        long principal=((Number)credential.get("principal_id")).longValue();
        long revision=((Number)credential.get("revision")).longValue();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.execute("SELECT set_config('app.tenant_id','',true)");
            long current=jdbc.queryForObject("SELECT revision FROM nw_platform_management_principals WHERE principal_id=? FOR UPDATE",Long.class,principal);
            assertThat(current).isEqualTo(revision);
            assertThat(jdbc.update("DELETE FROM nw_platform_management_scopes WHERE principal_id=? AND action='platform.usage.events.read'",principal)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nw_platform_management_principals SET revision=revision+1,updated_at=now() WHERE principal_id=? AND revision=? AND status='active'",principal,revision)).isEqualTo(1);
        });
        request(sameMachine,"GET",usage+"&cursor="+java.net.URLEncoder.encode(machineCursor,StandardCharsets.UTF_8),null,null,403);
        Map<String,Object> narrowed=jdbc.queryForMap("SELECT key_hash,credential_version,revision,status FROM nw_platform_management_principals WHERE principal_id=?",principal);
        assertThat(narrowed.get("key_hash")).isEqualTo(credential.get("key_hash"));
        assertThat(narrowed.get("credential_version")).isEqualTo(credential.get("credential_version"));
        assertThat(((Number)narrowed.get("revision")).longValue()).isEqualTo(revision+1);
        assertThat(narrowed.get("status")).isEqualTo("active");
        assertNoInstitutionOrUsageChange(tenantId,institution,institutionCount,usageCount);
    }

    /** 权限变化不等于token过期；保持同一账号的原始完整会话仍有效。 */
    private boolean activePlatformSession(String sessionId,String memberId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM nw_foundation_security_sessions WHERE scope_key='platform' AND id=? AND member_id=? AND state='full' AND revoked_at IS NULL AND expires_at>now())",
            Boolean.class,Long.parseLong(sessionId),Long.parseLong(memberId)));
    }

    /** 读取目标机构真实Usage事实数量，事务级RLS绑定由基类拥有并恢复。 */
    private long usageFactCount(String tenantId) {
        return ((Number)queryRows(jdbc,tenantId,"SELECT count(*) AS count FROM nw_usage_events WHERE tenant_id=?",Long.parseLong(tenantId)).getFirst().get("count")).longValue();
    }

    /** 在当前Tenant的真实RLS事务读取拒绝前后的完整业务分母；安全拒绝审计不计为非法副作用。 */
    private Map<String,Object> executionFactCounts(String tenantId) {
        return queryRows(jdbc,tenantId,"""
                SELECT (SELECT count(*) FROM nw_runtime_authentication_facts WHERE tenant_id=?) AS authentication_facts,
                    (SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?) AS runs,
                    (SELECT count(*) FROM agent_runtime_execution_materials WHERE tenant_id=?) AS materials,
                    (SELECT count(*) FROM nw_runtime_agent_run_bindings WHERE tenant_id=?) AS run_bindings,
                    (SELECT count(*) FROM nw_chat_messages WHERE tenant_id::text=?) AS public_messages,
                    (SELECT count(*) FROM nw_usage_events WHERE tenant_id::text=?) AS usage_facts
                """,tenantId,tenantId,tenantId,tenantId,tenantId,tenantId).getFirst();
    }

    /** 授权拒绝只能产生安全审计，不能开通/修改机构或产生业务计量。 */
    private void assertNoInstitutionOrUsageChange(String tenantId,Map<String,Object> institution,long institutionCount,long usageCount) {
        assertThat(jdbc.queryForMap("SELECT id,name,status,plan_id FROM nw_tenants WHERE id=?",Long.parseLong(tenantId))).isEqualTo(institution);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenants",Long.class)).isEqualTo(institutionCount);
        assertThat(usageFactCount(tenantId)).isEqualTo(usageCount);
    }

    /** 真实平台人员JWT创建精确scope/目标的独立机器，秘密只在首次签发时读取。 */
    private Key management(String namespace,List<String> tenants,List<String> definitions,Set<String> scopes)throws Exception {
        JsonNode issued=platformRequest("POST","/api/v1/platform/management-principals",Map.of(
            "name","测试管理主体","scopes",scopes,"target_tenant_ids",tenants,
            "target_definition_ids",definitions,"target_namespaces",List.of(namespace)),UUID.randomUUID().toString(),200);
        assertThat(issued.path("management_key").asText()).isNotBlank();
        return new Key(issued.path("management_key").asText(),issued.path("signing_secret").asText(),true);
    }
    /** 同一namespace和外部机构标识全局唯一，幂等键由平台受理而非请求字段提供。 */
    private JsonNode provision(Key key,String namespace,String ref)throws Exception {
        return request(key,"POST","/api/v1/platform/institutions/provision",Map.of("namespace",namespace,
            "external_institution_ref",ref,"name",ref,"default_plan_code","free"),namespace+":"+ref,200);
    }
    /** 门店范围由固定机构执行凭据创建；body不提供Tenant或integration。 */
    private JsonNode scope(Key key,String ref)throws Exception {
        return request(key,"POST","/api/v1/open-platform/business-scopes/ensure",Map.of(
            "scope_type","store","external_ref",ref,"display_name",ref),"scope:"+ref,200);
    }
    /** 平台签发固定Tenant/integration的执行身份，不能携带管理动作。 */
    private Key execution(Key gov,JsonNode institution)throws Exception {
        JsonNode issued=request(gov,"POST","/api/v1/platform/institution-credentials/issue",Map.of(
            "tenant_id",institution.path("tenant_id").asText(),"integration_id",institution.path("integration_id").asText(),
            "scopes",Set.of("sessions.write","sessions.read","sessions.events.read","end_users.write","business.scopes.write","artifacts.read","usage.read")),
            "credential:"+institution.path("tenant_id").asText(),200);
        return new Key(issued.path("api_key").asText(),issued.path("signing_secret").asText(),false);
    }
    /** 平台直接授权真实机构，授权不能由外部应用名称或部署正文推导。 */
    private JsonNode grant(Key key,String tenant,String definition)throws Exception {
        return request(key,"POST","/api/v1/platform/agent-grants",Map.of("definition_id",definition,
            "target_tenant_id",tenant,"actions",List.of("deploy","execute"),"allow_regrant",false,"remaining_depth",0),"grant:"+tenant,200);
    }
    /** 同一外部Agent身份在同机构/集成内稳定部署，平台代部署复用机构所属服务。 */
    private JsonNode deploy(Key key,JsonNode tenant,String definition,JsonNode grant,String external)throws Exception {
        return request(key,"POST","/api/v1/platform/agent-deployments",deployment(tenant,definition,grant,external),
            "deploy:"+tenant.path("tenant_id").asText()+":"+external,200);
    }
    /** 身份外层仅选管理目标，绑定仍由所属机构授权核验。 */
    private Map<String,Object> deployment(JsonNode tenant,String definition,JsonNode grant,String external) {
        return Map.of("tenant_id",tenant.path("tenant_id").asText(),"payload",Map.of("definition_id",definition,
            "grant_id",grant.path("grant_id").asText(),"integration_id",tenant.path("integration_id").asText(),
            "external_agent_ref",external,"name",external,"resource_bindings",List.of(),"delegation_bindings",List.of(),"parameters",Map.of()));
    }
    /** EndUser 同机构与 integration 幂等注册，返回真实内部用户。 */
    private long endUser(Key key,String ref)throws Exception {
        JsonNode result=request(key,"POST","/api/v1/open-platform/end-users/ensure",Map.of("external_user_ref",ref,
                "display_name","场景用户"),null,200).path("end_user_id");
        assertThat(result.isTextual()).as("宿主ID必须以十进制字符串传输").isTrue();
        long user=Long.parseLong(result.asText());
        assertThat(result.asText()).isEqualTo(Long.toString(user));
        return user;
    }
    /** 同聊天室按 Agent 稳定映射会话，门店必须已获授权。 */
    private String session(Key key,long agent,long user,String room,String store)throws Exception {
        return request(key,"POST","/api/v1/open-platform/sessions",Map.of("external_user_ref","patient-a",
                "external_conversation_ref",room,"agent_id",Long.toString(agent),"end_user_id",Long.toString(user),
                "business_scope_type","store","business_scope_ref",store),null,200).path("session_id").asText();
    }
    private Map<String,Object> message(String session,String text,Map<String,Object> context) {
        return Map.of("session_id",session,"payload",Map.of("content",List.of(Map.of("type","text","text",text)),
                "context",context,"references",List.of()));
    }
    /** 定义只引用真实启用模型，全部发布行为经平台HTTP完成。 */
    private Map<String,Object> definition(String model,String prompt) {
        return Map.of("system_prompt",prompt,"role_description","机构助手","model_config_id",model,
            "execution_config",Map.of(),"capabilities",List.of(),"resource_slots",List.of(),"delegation_slots",List.of());
    }
    /** HMAC 覆盖完整 request target 和实际 UTF-8 正文；断言不输出一次性密钥或个人信息。 */
    private JsonNode request(Key key,String method,String target,Object value,String idempotency,int expected)throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var builder=signed(key,method,target,body).header("Content-Type","application/json");
        if(idempotency!=null)builder.header("Idempotency-Key",idempotency);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        JsonNode envelope=json.readTree(response.body());
        assertThat(response.statusCode()).as("HTTP %s %s code=%s message=%s",method,target,
                envelope.path("code").asText(),envelope.path("message").asText()).isEqualTo(expected);
        return expected==200?json.readTree(response.body()).path("data"):json.readTree(response.body());
    }
    /** 真实 SSE 读取一个持久事件后主动断开，恢复由公开游标接口完成。 */
    private void disconnectStream(Key key,String session,String cursor)throws Exception {
        String target="/api/v1/open-platform/sessions/stream?sessionId="+session+"&cursor="+cursor;
        var response=http.send(signed(key,"GET",target,"").header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            String line;boolean event=false;
            while((line=reader.readLine())!=null)if(line.startsWith("data:")){event=true;break;}
            assertThat(event).as("SSE须输送真实持久事件").isTrue();
        }
    }
    private HttpRequest.Builder signed(Key key,String method,String target,String body) {
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=HmacSignatureVerifier.sign(key.secret(),HmacSignatureVerifier.canonical(timestamp,method,target,body));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+targetPort(target)+target)).timeout(Duration.ofSeconds(20))
                .header(key.management()?"X-Management-Key":"X-Api-Key",key.apiKey()).header("X-Timestamp",timestamp).header("X-Signature",signature);
    }
    /** 密钥只用于本测试的请求头；toString 禁止输出秘密。 */
    private record Key(String apiKey,String secret,boolean management) { @Override public String toString(){return "test-key[redacted]";} }
    /** 平台路径进入平台宿主，精确代部署路径仍由机构宿主承载。 */
    private int targetPort(String target) { return target.startsWith("/api/v1/platform/") && !target.startsWith("/api/v1/platform/agent-deployments")?platformPort:port; }
    /** 真平台JWT只用于当前平台管理HTTP，身份不写入报告。 */
    private JsonNode platformRequest(String method,String target,Object value,String idempotency,int expected)throws Exception {
        return platformRequestAs(platformToken,method,target,value,idempotency,expected);
    }
    /** 对指定原JWT发出真实HTTP，撤权探针不隐式重新登录或替换凭据。 */
    private JsonNode platformRequestAs(String token,String method,String target,Object value,String idempotency,int expected)throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+platformPort+target)).timeout(Duration.ofSeconds(20))
            .header("Content-Type","application/json").header("X-Platform-Token","Bearer "+token);
        if(idempotency!=null)builder.header("Idempotency-Key",idempotency);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        JsonNode envelope=json.readTree(response.body());
        assertThat(response.statusCode()).as("平台HTTP %s %s code=%s",method,target,envelope.path("code").asText()).isEqualTo(expected);
        return envelope.path("data");
    }
    /** 原生机构人员只用本地登录JWT，明确JSON读取避免误选同路径SSE订阅；不输出令牌或凭据。 */
    private JsonNode institutionRequest(String token,String method,String target,Object value,int expected)throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+target)).timeout(Duration.ofSeconds(20))
            .header("Content-Type","application/json").header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        JsonNode envelope=json.readTree(response.body());
        assertThat(response.statusCode()).as("机构人员HTTP %s %s code=%s",method,target,envelope.path("code").asText()).isEqualTo(expected);
        return envelope.path("data");
    }
    /** 启动当前bootJar，所有依赖明确绑定本类拥有的容器。 */
    private Process startPlatform(Path root, Path evidence) throws IOException {
        Path jar=root.resolve("backend-java/skillforge-platform-service/build/libs/skillforge-platform-service.jar");
        assertThat(jar).as("先构建当前候选的platform bootJar").exists();
        platformPort=freePort(); int port=platformPort;
        String username="gi14-http-admin",password="synthetic-gi14-password";
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--server.port=" + port,
                "--agent-runtime.workers.auto-startup=false", "--spring.flyway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/skillforge-platform-service").toFile());
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve("platform.log").toFile());
        var environment = builder.environment();
        environment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        // 平台专用Datasource后处理器使用这两个权威键，不能继承开发库身份。
        environment.put("PLATFORM_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("PLATFORM_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("REDIS_HOST", REDIS.getHost());
        environment.put("REDIS_PORT", REDIS.getMappedPort(6379).toString());
        environment.put("REDIS_DATABASE", "0");
        environment.put("SECRET_KEY", properties.getSecretKey());
        environment.put("PLATFORM_SECRET_KEY", TEST_PLATFORM_JWT_SECRET);
        environment.put("PLATFORM_ADMIN_USERNAME", username);
        environment.put("PLATFORM_ADMIN_PASSWORD", password);
        Process result = builder.start();

        return result;
    }

    /** 实际平台登录完成才允许后续管理主体创建。 */
    private String login()throws Exception {
        return loginAs("gi14-http-admin","synthetic-gi14-password").path("access_token").asText();
    }
    /** 正式密码认证生成真实安全会话，返回值仅在当前测试进程使用。 */
    private JsonNode loginAs(String username,String password)throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+platformPort+"/api/v1/platform/auth/login"))
            .timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username",username,"password",password)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result=json.readTree(response.body()).path("data");
        assertThat(result.path("access_token").asText()).isNotBlank();return result;
    }
    /** 只释放本测试创建的监听端口，不复用开发服务端口。 */
    private static int freePort()throws IOException { try(var socket=new ServerSocket(0)){return socket.getLocalPort();} }
    /** 仅停止当前测试拥有的进程，并等待退出避免遗留后台JVM。 */
    private static void stop(Process process)throws InterruptedException {
        process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();}
    }
    /** 向上查找受版本控制的仓库根目录。 */
    private static Path repositoryRoot() { Path path=Path.of("").toAbsolutePath();while(path!=null&&!Files.exists(path.resolve("tools/integration-scenario/scenario.py")))path=path.getParent();if(path==null)throw new IllegalStateException("repository root missing");return path; }
}
