package com.skillforge.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.core.knowledge.PlatformKnowledgeModelProbe;
import com.skillforge.core.model.PlatformModelMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.settings.OperationalSettingsMapper;
import com.skillforge.platform.PlatformApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 平台服务在全新基础设施上的装配、认证和管理 API 集成验收。 */
@SpringBootTest(
        classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformAdminApiIntegrationTest {

    private static final long PLATFORM_MEMBER_ID = 991_001L;
    private static final String USERNAME = "platform-integration-admin";
    private static final String PASSWORD = "PlatformIntegration123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("skillforge_platform")
                    .withUsername("skillforge")
                    .withPassword("skillforge_test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("skillforge.secret-key",
                () -> "platform-integration-shared-model-secret");
        registry.add("sa-token.jwt-secret-key",
                () -> "platform-integration-jwt-secret-key");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext application;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CryptoService cryptoService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.skillforge.core.model.invocation.LlmService systemLlm;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime runtimeProvider;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.skillforge.core.agent.systemagent.SystemAgentRunner systemRunner;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void platformHostKeepsTrajectoryWithoutInstitutionCitationService() {
        assertThat(application.getBeansOfType(com.skillforge.core.chat.trajectory.TrajectoryProjectionService.class)).hasSize(1);
        assertThat(application.getBeansOfType(com.skillforge.core.chat.trajectory.ChatCitationReadService.class)).isEmpty();
    }

    @Test
    void platformDefinitionSaveResolvesCurrentSkillVersionAndActivation() throws Exception {
        String token=loginPlatformAdministrator(), root="/api/v1/admin/capabilities/skill-drafts";
        var skill=successData(request("POST",root+"/new",token,Map.of("capability_key","definition-version-skill",
                "name","definition-version-skill","description","test")));
        String id=skill.path("definition_id").asText();
        String content=java.util.Base64.getEncoder().encodeToString("---\nname: definition-version-skill\ndescription: skill\n---\nInstructions".getBytes(StandardCharsets.UTF_8));
        var first=successData(request("POST",root+"/save-and-publish",token,Map.of("request_id",java.util.UUID.randomUUID().toString(),
                "draft_id",skill.path("id").asText(),"revision",skill.path("revision").asLong(),"definition_revision",1,
                "version_key","v1","files",Map.of("SKILL.md",content),"model_invocable",true,"user_invocable",true,"activation_mode","ON_DEMAND")));
        assertThat(first.path("status").asText()).isEqualTo("SUCCEEDED");
        String oldVersion=first.path("publication").path("version_id").asText();
        var next=successData(request("POST",root,token,Map.of("definition_id",id,"base_version_id",oldVersion)));
        var second=successData(request("POST",root+"/save-and-publish",token,Map.of("request_id",java.util.UUID.randomUUID().toString(),
                "draft_id",next.path("id").asText(),"revision",next.path("revision").asLong(),
                "definition_revision",first.path("publication").path("definition_revision").asLong(),"version_key","v2",
                "files",Map.of("SKILL.md",content),"model_invocable",true,"user_invocable",true,"activation_mode","ALWAYS")));
        assertThat(second.path("status").asText()).isEqualTo("SUCCEEDED");
        var selected=Map.of("capability_type","SKILL","capability_definition_id",id,"capability_version_id",oldVersion,
                "skill_config",Map.of("activation_mode","ON_DEMAND","token_budget",1234),"platform_tool_name","old_tool","priority",1);
        var config=Map.of("system_prompt","test","role_description","test","capabilities",List.of(selected));
        String definitions="/api/v1/platform/agent-definitions";
        var created=successData(request("POST",definitions,token,Map.of("name","能力版本测试","distribution_policy","AUTHORIZED_DEPLOY","definition",config)));
        var detail=successData(request("GET",definitions+"/detail?definition_id="+created.path("definition_id").asText(),token,null));
        var actual=detail.path("definition").path("capabilities").get(0);
        assertThat(actual.path("capability_version_id").asText()).isEqualTo(second.path("publication").path("version_id").asText());
        assertThat(actual.path("skill_config").path("activation_mode").asText()).isEqualTo("ALWAYS");
        assertThat(actual.path("skill_config").path("token_budget").asLong()).isEqualTo(1234);
        assertThat(actual.path("platform_tool_name").isNull()).isTrue();
    }

    @Test
    void unsavedPlatformDefinitionPreviewDoesNotWriteOrGrantPublication() throws Exception {
        String token=loginPlatformAdministrator();
        Map<String,Object> modelRequest=Map.ofEntries(
                Map.entry("name","Preview fixture model"),Map.entry("kind","LLM"),Map.entry("enabled",true),
                Map.entry("provider_id","deepseek"),Map.entry("service_id","deepseek-api"),
                Map.entry("profile_version","2026-09-13"),Map.entry("protocol","openai-chat-completions"),
                Map.entry("authentication_scheme","bearer-api-key"),Map.entry("endpoint","https://api.deepseek.com"),
                Map.entry("model_id","deepseek-flash"),Map.entry("api_key","test-key"),
                Map.entry("supported_max_output_tokens",4096),Map.entry("context_window_tokens",32000),
                Map.entry("max_output_tokens",2048),Map.entry("reasoning","DISABLED"),Map.entry("timeout_seconds",30),
                Map.entry("input_price_per_million_cents","25"),Map.entry("output_price_per_million_cents","125"),Map.entry("currency","CNY"));
        var modelResponse=request("POST","/api/v1/admin/models",token,modelRequest);
        assertThat(modelResponse.statusCode()).as(modelResponse.body()).isEqualTo(201);
        String modelId=successData(modelResponse).path("id").asText();
        assertThat(request("POST","/api/v1/admin/models/publish",token,Map.of("id",modelId,"expected_revision",1)).statusCode()).isEqualTo(200);
        String root="/api/v1/platform/agent-definitions";
        var definition=Map.of("system_prompt","原提示", "role_description","客服", "model_config_id",modelId,
                "execution_config",Map.of(),"capabilities",List.of(),"resource_slots",List.of(),"delegation_slots",List.of());
        var created=request("POST",root,token,Map.of("name","平台预览测试","distribution_policy","AUTHORIZED_DEPLOY","definition",definition));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String id=successData(created).path("definition_id").asText();
        var before=successData(request("GET",root+"/detail?definition_id="+id,token,null));
        var savedPreview=successData(request("POST",root+"/preview",token,Map.of("definition_id",id,"expected_revision",before.path("draft_revision").asLong())));
        assertThat(savedPreview.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(savedPreview.path("impact").path("changes").path("added_resource_slots").isEmpty()).isTrue();
        assertThat(request("POST",root+"/preview",token,Map.of("definition_id",id,"expected_revision",999)).statusCode()).isEqualTo(409);
        var candidate=new java.util.HashMap<String,Object>(definition);
        candidate.put("system_prompt","当前内容 {{parameters.branch}}");
        candidate.put("resource_slots",List.of(Map.of("key","records","label","资料","description","","capability_type","KNOWLEDGE_SOURCE")));
        var command=Map.of("definition_id",id,"expected_revision",before.path("draft_revision").asLong(),"definition",candidate);
        var response=request("POST",root+"/preview-content",token,command);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var preview=successData(response);
        assertThat(preview.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(preview.path("impact").path("changes").path("added_parameter_keys").toString()).contains("branch");
        assertThat(preview.path("impact").path("changes").path("added_resource_slots").toString()).contains("records");
        assertThat(successData(request("GET",root+"/detail?definition_id="+id,token,null))).isEqualTo(before);
        assertThat(request("POST",root+"/preview-content",token,Map.of("definition_id",id,"expected_revision",999,"definition",candidate)).statusCode()).isEqualTo(409);
        candidate.put("model_config_id", "missing");
        var invalid=successData(request("POST",root+"/preview-content",token,command));
        assertThat(invalid.path("validation").path("valid").asBoolean()).isFalse();
        assertThat(invalid.path("impact").isNull()).isTrue();
    }

    @Test
    void platformSkillSaveAndPublishKeepsOriginalReceiptAndRollsBackInvalidContent() throws Exception {
        String token = loginPlatformAdministrator();
        String root = "/api/v1/admin/capabilities/skill-drafts";
        var created = successData(request("POST", root + "/new", token,
                Map.of("capability_key", "integration-skill", "name", "integration-skill", "description", "test")));
        String draftId = created.path("id").asText();
        String definitionId = created.path("definition_id").asText();
        String requestId = java.util.UUID.randomUUID().toString();
        String skill = java.util.Base64.getEncoder().encodeToString(
                "---\nname: integration-skill\ndescription: Integration skill\n---\nInstructions".getBytes(StandardCharsets.UTF_8));
        var command = Map.of("request_id", requestId, "draft_id", draftId, "revision", created.path("revision").asLong(),
                "definition_revision", 1, "version_key", "v1", "files", Map.of("SKILL.md", skill),
                "model_invocable", true, "user_invocable", true, "activation_mode", "ON_DEMAND", "required_capabilities", List.of());
        var preview = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + root + "/validate-content"))
                .header("X-Platform-Token", "Bearer " + token).header("Content-Type", "application/json")
                .header("If-Match", created.path("revision").asText())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(command))).build();
        var previewResponse = http.send(preview, HttpResponse.BodyHandlers.ofString());
        assertThat(previewResponse.statusCode()).as(previewResponse.body()).isEqualTo(200);
        assertThat(successData(previewResponse).path("valid").asBoolean()).isTrue();
        var unchanged = successData(request("GET", root + "/detail?draftId=" + draftId, token, null));
        assertThat(unchanged.path("revision").asLong()).isEqualTo(created.path("revision").asLong());
        assertThat(unchanged.path("files").size()).isZero();
        var response = request("POST", root + "/save-and-publish", token, command);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var receipt = successData(response);
        assertThat(receipt.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("definition_id").asText()).isEqualTo(definitionId);
        assertThat(receipt.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(successData(request("GET", root + "/save-and-publish/result?request_id=" + requestId, token, null))).isEqualTo(receipt);
        assertThat(successData(request("POST", root + "/save-and-publish", token, command))).isEqualTo(receipt);
        jdbc.execute("CREATE ROLE skill_publish_rls_test NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT SELECT ON nw_platform_skill_publish_receipts TO skill_publish_rls_test");
        new org.springframework.transaction.support.TransactionTemplate(
                application.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE skill_publish_rls_test");
            jdbc.execute("SELECT set_config('app.platform_admin','false',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_skill_publish_receipts", Long.class)).isZero();
            jdbc.execute("SELECT set_config('app.platform_admin','true',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_skill_publish_receipts", Long.class)).isEqualTo(1);
        });
        var changed = new java.util.HashMap<>(command);
        changed.put("version_key", "v2");
        assertThat(request("POST", root + "/save-and-publish", token, changed).statusCode()).isEqualTo(409);

        var second = successData(request("POST", root + "/new", token,
                Map.of("capability_key", "invalid-integration-skill", "name", "invalid-integration-skill", "description", "test")));
        var invalid = Map.of("request_id", java.util.UUID.randomUUID().toString(), "draft_id", second.path("id").asText(),
                "revision", second.path("revision").asLong(), "definition_revision", 1, "version_key", "v1",
                "files", Map.of(), "model_invocable", true, "user_invocable", true, "activation_mode", "ON_DEMAND", "required_capabilities", List.of());
        var rejected = successData(request("POST", root + "/save-and-publish", token, invalid));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("validation").path("valid").asBoolean()).isFalse();
        var saved = successData(request("GET", root + "/detail?draftId=" + second.path("id").asText(), token, null));
        assertThat(saved.path("revision").asLong()).isEqualTo(second.path("revision").asLong());
    }

    /** 本批月费用装配独立登录验收，不依赖模型、审核队列或系统Agent执行。 */
    @Test
    void modelMonthlyBillingIsAuthenticatedAndRetiredSalesRoutesAreAbsent() throws Exception {
        String token = loginPlatformAdministrator();
        String path = "/api/v1/platform/billing/monthly?tenant_id=900000000000000001&month=2026-08";
        HttpResponse<String> anonymous = request("GET", path, null, null);
        assertThat(anonymous.statusCode()).as(anonymous.body()).isEqualTo(401);
        HttpResponse<String> monthly = request("GET", path, token, null);
        assertThat(monthly.statusCode()).as(monthly.body()).isEqualTo(200);
        assertThat(successData(monthly).path("month").asText()).isEqualTo("2026-08");
        assertThat(successData(monthly).path("currency").asText()).isEqualTo("CNY");
        assertThat(successData(monthly).path("items").isArray()).isTrue();
        HttpResponse<String> oldRead = request("GET", "/api/v1/platform/billing/sales-prices", token, null);
        assertThat(oldRead.statusCode()).as(oldRead.body()).isEqualTo(404);
        HttpResponse<String> oldWrite = request("POST", "/api/v1/platform/billing/sales-prices", token, Map.of());
        assertThat(oldWrite.statusCode()).as(oldWrite.body()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.nw_billing_sales_prices')::text", String.class)).isNull();
    }

    /** 同类各入口都独立执行登录；只复用本类拥有的首位管理员，不依赖JUnit执行顺序。 */
    private String loginPlatformAdministrator() throws Exception {
        Long existing = jdbc.queryForObject("SELECT count(*) FROM nw_platform_members WHERE id=? AND username=?", Long.class,
                PLATFORM_MEMBER_ID, USERNAME);
        if (existing == null || existing == 0) {
            assertThat(application.getBean(com.skillforge.core.foundation.access.FoundationOrganizationService.class)
                    .initializePlatformAdministrator(Long.toString(PLATFORM_MEMBER_ID), () -> jdbc.update("""
                            INSERT INTO nw_platform_members(id,username,password_hash,display_name,status,created_at,updated_at)
                            VALUES(?,?,?,?,'active',now(),now())
                            """, PLATFORM_MEMBER_ID, USERNAME, passwordEncoder.encode(PASSWORD), "Platform integration admin"))).isTrue();
        }
        HttpResponse<String> login = request("POST", "/api/v1/platform/auth/login", null,
                Map.of("username", USERNAME, "password", PASSWORD));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String token = successData(login).path("access_token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 验证真实平台装配包含轨迹读取能力与共享 Mapper XML，且日志及租户插件只注册一次。
     * 无参数、无返回值；缺失语句或重复插件由断言失败报告，避免仅构建成功掩盖装配问题。
     */
    @Test
    void sharedPersistenceAssemblyLoadsStatementsAndSingleLoggingInterceptor() {
        var log = org.slf4j.LoggerFactory.getLogger(PlatformAdminApiIntegrationTest.class);
        log.debug("event=persistence_assembly_check_enter host=platform");
        var configuration = application.getBean(org.apache.ibatis.session.SqlSessionFactory.class)
                .getConfiguration();
        assertThat(configuration.getInterceptors().stream()
                .filter(com.skillforge.core.shared.config.MapperExecutionLoggingInterceptor.class::isInstance)
                .count()).isEqualTo(1L);
        assertThat(configuration.getInterceptors().stream()
                .filter(com.skillforge.core.foundation.config.TenantRlsInterceptor.class::isInstance)
                .count()).isEqualTo(1L);
        for (Class<?> capability : java.util.List.of(
                com.skillforge.core.chat.trajectory.TrajectoryConfiguration.class,
                com.skillforge.core.chat.trajectory.TrajectoryProjectionService.class,
                com.skillforge.core.chat.trajectory.TrajectoryListService.class,
                com.skillforge.core.knowledge.KnowledgeObservationAccess.class,
                com.skillforge.core.foundation.access.PlatformObservationAccess.class,
                com.skillforge.core.foundation.access.ObservationReadAudit.class)) {
            assertThat(application.getBeansOfType(capability)).as(capability.getName()).hasSize(1);
        }
        for (Class<?> mapper : java.util.List.of(
                com.skillforge.core.capability.SkillDraftMapper.class,
                com.skillforge.core.capability.CapabilityCatalogContextMapper.class,
                com.skillforge.core.chat.observation.SessionObservationMapper.class)) {
            assertThat(application.getBean(mapper)).isNotNull();
            assertThat(configuration.getMappedStatementNames())
                    .anyMatch(name -> name.startsWith(mapper.getName() + "."));
        }
        var observationStatements = java.util.Map.of(
                com.skillforge.core.chat.trajectory.TrajectoryMapper.class,
                java.util.List.of("productSession", "runs", "edges", "events", "attempts", "material", "readAt"),
                com.skillforge.core.chat.trajectory.TrajectoryListMapper.class,
                java.util.List.of("readAt", "candidates"),
                com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper.class,
                java.util.List.of("readable"),
                com.skillforge.core.foundation.access.PlatformObservationTargetMapper.class,
                java.util.List.of("targets"));
        observationStatements.forEach((mapper, statements) -> {
            assertThat(application.getBeansOfType(mapper)).as(mapper.getName()).hasSize(1);
            statements.forEach(statement -> assertThat(configuration.hasStatement(mapper.getName() + "." + statement))
                    .as(mapper.getName() + "." + statement).isTrue());
        });
        log.debug("event=persistence_assembly_check_exit host=platform mapperCount=7");
    }

    @Test
    void freshPlatformStartsAndProtectsModelAndSystemSettingsApis() throws Exception {
        assertThat(application.getBean(PlatformModelMapper.class)).isNotNull();
        assertThat(application.getBean(OperationalSettingsMapper.class)).isNotNull();
        assertThat(application.getBean(PlatformKnowledgeModelProbe.class)).isNotNull();
        var invocationMapperFactory = (org.mybatis.spring.mapper.MapperFactoryBean<?>)
                application.getBean("&externalInvocationMapper");
        assertThat(invocationMapperFactory.getSqlSessionFactory().getConfiguration().getInterceptors())
                .as("平台外部调用账本的实际Factory必须绑定数据库租户")
                .anyMatch(com.skillforge.core.foundation.config.TenantRlsInterceptor.class::isInstance);
        String ciphertext = cryptoService.encrypt("shared-model-credential");
        assertThat(cryptoService.decrypt(ciphertext)).isEqualTo("shared-model-credential");

        assertThat(request("GET", "/api/v1/admin/models", null, null).statusCode())
                .isEqualTo(401);
        assertThat(request("GET", "/api/v1/admin/system-settings", null, null).statusCode())
                .isEqualTo(401);

        String token = loginPlatformAdministrator();
        verifyAuditAndReviews(token);
        verifyTenantCreationAndScanRecovery(token);

        HttpResponse<String> emptyModels = request(
                "GET", "/api/v1/admin/models", token, null);
        assertThat(emptyModels.statusCode()).isEqualTo(200);
        assertThat(successData(emptyModels).isArray()).isTrue();

        HttpResponse<String> createdModel = request(
                "POST", "/api/v1/admin/models", token, Map.ofEntries(
                        Map.entry("name", "Integration embedding"),
                        Map.entry("kind", "EMBEDDING"),
                        Map.entry("enabled", true),
                        Map.entry("provider_id", "custom"),
                        Map.entry("service_id", "custom-embedding"),
                        Map.entry("profile_version", "custom-v1"),
                        Map.entry("protocol", "openai-embeddings"),
                        Map.entry("authentication_scheme", "bearer-api-key"),
                        Map.entry("endpoint", "http://127.0.0.1:11434/v1"),
                        Map.entry("model_id", "text-embedding-integration"),
                        Map.entry("api_key", "integration-model-key"),
                        Map.entry("timeout_seconds", 30),
                        Map.entry("dimensions", 1536),
                        Map.entry("currency", "CNY")));
        assertThat(createdModel.statusCode()).as(createdModel.body()).isEqualTo(201);
        JsonNode model = successData(createdModel);
        assertThat(model.path("id").asText()).isNotBlank();
        assertThat(model.path("credential_configured").asBoolean()).isTrue();
        assertThat(createdModel.body()).doesNotContain("integration-model-key");
        HttpResponse<String> modelDetail = request("GET", "/api/v1/admin/models/detail?id=" + model.path("id").asText(), token, null);
        assertThat(modelDetail.statusCode()).as(modelDetail.body()).isEqualTo(200);
        assertThat(modelDetail.body()).doesNotContain("integration-model-key", "encrypted_api_key", "ciphertext");
        assertThat(successData(modelDetail).path("credential_configured").asBoolean()).isTrue();
        assertThat(request("GET", "/api/v1/admin/models/detail?id=" + model.path("id").asText(), null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> modelsAfterCreate = request(
                "GET", "/api/v1/admin/models", token, null);
        assertThat(modelsAfterCreate.statusCode()).isEqualTo(200);
        assertThat(modelsAfterCreate.body()).contains(model.path("id").asText());

        HttpResponse<String> settings = request(
                "GET", "/api/v1/admin/system-settings", token, null);
        assertThat(settings.statusCode()).isEqualTo(200);
        JsonNode groups = successData(settings);
        JsonNode rateLimits = findGroup(groups, "rate_limits");
        assertThat(rateLimits.path("revision").asLong()).isEqualTo(1);

        HttpResponse<String> updated = request(
                "PUT", "/api/v1/admin/system-settings/update", token,
                Map.of("group", "rate_limits", "payload", Map.of("expected_revision", 1,
                        "values", Map.of("requests_per_minute_default", 321))));
        assertThat(updated.statusCode()).isEqualTo(200);
        JsonNode updatedGroup = successData(updated);
        assertThat(updatedGroup.path("revision").asLong()).isEqualTo(2);
        assertThat(updatedGroup.path("values")
                .path("requests_per_minute_default").asInt()).isEqualTo(321);
        var quota = findGroup(groups, "task_budget");
        assertThat(quota.path("values").size()).isEqualTo(15);
        assertThat(quota.path("derived_budgets").size()).isEqualTo(6);
        assertThat(request("GET", "/api/v1/admin/system-settings/context-policy", null, null).statusCode()).isEqualTo(401);
        assertThat(successData(request("GET", "/api/v1/admin/system-settings/context-policy", token, null)).path("version").asText())
                .isEqualTo("context-policy.v2");
        var quotaValues = objectMapper.convertValue(quota.path("values"), new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        quotaValues.put("standard_root_total_tokens", 120_000_000L);
        var quotaCommand = Map.of("group", "task_budget", "payload", Map.of("expected_revision", quota.path("revision").asLong(), "values", quotaValues));
        assertThat(request("PUT", "/api/v1/admin/system-settings/update", token, quotaCommand).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/api/v1/admin/system-settings/update", token, quotaCommand).statusCode()).isEqualTo(409);
        assertThat(request("GET", "/api/v1/admin/system-settings/history?group=task_budget", token, null).statusCode()).isEqualTo(200);
        var rollbackQuota = request("POST", "/api/v1/admin/system-settings/rollback", token,
                Map.of("group", "task_budget", "payload", Map.of("expected_revision", quota.path("revision").asLong() + 1,
                        "target_revision", quota.path("revision").asLong())));
        assertThat(rollbackQuota.statusCode()).isEqualTo(200);
        assertThat(successData(rollbackQuota).path("values")).isEqualTo(quota.path("values"));
        for (String removed : java.util.List.of("context", "run_budget"))
            assertThat(request("PUT", "/api/v1/admin/system-settings/update", token,
                    Map.of("group", removed, "payload", Map.of("expected_revision", 1, "values", Map.of()))).statusCode()).isEqualTo(404);
        quotaValues.put("standard_root_input_tokens", 1);
        assertThat(request("PUT", "/api/v1/admin/system-settings/update", token,
                Map.of("group", "task_budget", "payload", Map.of("expected_revision", quota.path("revision").asLong() + 2, "values", quotaValues))).statusCode()).isEqualTo(422);
        verifySystemAgents(token);
    }

    /** 真实分页、普通草稿排除、并发审核、理由持久化及 RLS 均走数据库与 HTTP。 */
    private void verifyTenantCreationAndScanRecovery(String token) throws Exception {
        var invalid = request("POST", "/api/v1/admin/tenants", token, Map.of(
                "tenant_name", " ", "tenant_slug", "ui-recovery", "admin_username", "admin", "admin_password", " "));
        assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(400);
        assertThat(objectMapper.readTree(invalid.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(objectMapper.readTree(invalid.body()).path("field_errors").has("admin_password")).isTrue();
        assertThat(objectMapper.readTree(invalid.body()).path("field_errors").has("tenant_name")).isTrue();
        // 仅触发字节上限；字符组成合法，避免同字段先返回另一条校验错误。
        String oversizedPassword = "A1" + "密".repeat(24);
        var oversized = request("POST", "/api/v1/admin/tenants", token, Map.of(
                "tenant_name", "测试", "tenant_slug", "ui-recovery", "admin_username", "admin", "admin_password", oversizedPassword));
        assertThat(oversized.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(oversized.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(objectMapper.readTree(oversized.body()).path("field_errors").path("admin_password").asText()).contains("72");
        assertThat(oversized.body()).doesNotContain(oversizedPassword);

        var command = Map.of("tenant_name", "  UI恢复机构  ", "tenant_slug", " ui-recovery ",
                "admin_username", " recovery-admin ", "admin_password", "recovery-password-123");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); return request("POST", "/api/v1/admin/tenants", token, command); });
            var second = pool.submit(() -> { gate.await(); return request("POST", "/api/v1/admin/tenants", token, command); });
            gate.countDown();
            var results = java.util.List.of(first.get(), second.get());
            assertThat(results.stream().map(HttpResponse::statusCode).toList()).as(results.toString()).containsExactlyInAnyOrder(201, 409);
        }
        var found = request("GET", "/api/v1/admin/tenants/find-by-slug?slug=ui-recovery", token, null);
        assertThat(found.statusCode()).as(found.body()).isEqualTo(200);
        var tenantId = successData(found).path("id").asLong();
        assertThat(successData(found).path("name").asText()).isEqualTo("UI恢复机构");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id = ?", Long.class, tenantId)).isEqualTo(1);
        assertThat(request("GET", "/api/v1/admin/tenants/find-by-slug?slug=ui-recovery", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/admin/tenants/find-by-slug?slug=missing-ui-recovery", token, null).statusCode()).isEqualTo(204);

        jdbc.update("UPDATE nw_tenants SET status='suspended', override_quota_api_calls_per_month=0 WHERE id=?", tenantId);
        String operation = java.util.UUID.randomUUID().toString();
        assertThat(request("GET", "/api/v1/platform/billing/result?operationId=" + operation, token, null).statusCode()).isEqualTo(204);
        JsonNode receipt;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); return request("POST", "/api/v1/platform/billing/process-states", token, Map.of("operation_id", operation)); });
            var second = pool.submit(() -> { gate.await(); return request("POST", "/api/v1/platform/billing/process-states", token, Map.of("operation_id", operation)); });
            gate.countDown();
            var firstResult = first.get(); var secondResult = second.get();
            assertThat(firstResult.statusCode()).as(firstResult.body()).isEqualTo(200);
            assertThat(secondResult.statusCode()).as(secondResult.body()).isEqualTo(200);
            receipt = successData(firstResult);
            assertThat(receipt).isEqualTo(successData(secondResult));
        }
        var recovered = request("GET", "/api/v1/platform/billing/result?operationId=" + operation, token, null);
        assertThat(successData(recovered)).isEqualTo(receipt);
        assertThat(receipt.path("completed_at").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_billing_scans WHERE operation_id=?", Long.class, operation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM nw_tenants WHERE id=?", String.class, tenantId)).isEqualTo("suspended");
        assertThat(application.getBean(com.skillforge.platform.billing.PlatformBillingScanService.class).find(PLATFORM_MEMBER_ID + 1, operation)).isNull();
        assertThat(request("POST", "/api/v1/platform/billing/process-states", null, Map.of("operation_id", operation)).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/v1/platform/billing/process-states", token, Map.of("operation_id", "invalid")).statusCode()).isEqualTo(400);

        String rolledBack = java.util.UUID.randomUUID().toString();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(application.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.executeWithoutResult(tx -> {
            application.getBean(com.skillforge.platform.billing.PlatformBillingScanService.class).process(PLATFORM_MEMBER_ID, rolledBack);
            tx.setRollbackOnly();
        });
        assertThat(request("GET", "/api/v1/platform/billing/result?operationId=" + rolledBack, token, null).statusCode()).isEqualTo(204);
    }

    private void verifyAuditAndReviews(String token) throws Exception {
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO nw_audit_logs(id, tenant_id, actor_id, action, payload) VALUES(?, 900000000000000001, 'review-operator', 'ui.integration', '{}'::jsonb)", 992000L + i);
        }
        var logs = request("GET", "/api/v1/platform/audit/logs?action=ui.integration&page=2&limit=2", token, null);
        assertThat(logs.statusCode()).as(logs.body()).isEqualTo(200);
        assertThat(successData(logs).path("total").asLong()).isEqualTo(3);
        assertThat(successData(logs).path("items").size()).isEqualTo(1);
        assertThat(request("GET", "/api/v1/platform/audit/logs?tenantId=bad", token, null).statusCode()).isEqualTo(400);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO nw_workflow_templates(id,workflow_id,name,version,type,status) VALUES(?, ?, ?, '1.0.0', 'state-machine', ?)",
                    993000L + i, "ui-review-" + i, "UI review " + i, i == 2 ? "draft" : "review");
        }
        var queue = request("GET", "/api/v1/marketplace/reviews?status=all&search=ui-review-&page=2&limit=1", token, null);
        assertThat(queue.statusCode()).as(queue.body()).isEqualTo(200);
        assertThat(successData(queue).path("total").asLong()).isEqualTo(2);
        assertThat(successData(queue).path("items").size()).isEqualTo(1);
        var pending = successData(queue).path("items").get(0);
        var command = Map.of("workflow_id", pending.path("id").asText(), "expected_updated_at", pending.path("updated_at").asText(), "reason", "需要完善安全声明");
        assertThat(request("POST", "/api/v1/marketplace/reviews/reject", null, command).statusCode()).isEqualTo(401);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return request("POST", "/api/v1/marketplace/reviews/reject", token, command); });
            var second = pool.submit(() -> { gate.await(); return request("POST", "/api/v1/marketplace/reviews/reject", token, command); });
            gate.countDown();
            assertThat(java.util.List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        var rejected = request("GET", "/api/v1/marketplace/reviews?status=rejected&search=ui-review-", token, null);
        assertThat(successData(rejected).path("total").asLong()).isEqualTo(1);
        assertThat(successData(rejected).path("items").get(0).path("reason").asText()).isEqualTo("需要完善安全声明");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_workflow_review_decisions", Long.class)).isEqualTo(1);
        jdbc.execute("CREATE ROLE ui_review_rls_test NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT SELECT ON nw_workflow_review_decisions TO ui_review_rls_test");
        new org.springframework.transaction.support.TransactionTemplate(application.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL ROLE ui_review_rls_test");
            jdbc.execute("SELECT set_config('app.platform_admin','false',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_workflow_review_decisions", Long.class)).isZero();
            jdbc.execute("SELECT set_config('app.platform_admin','true',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_workflow_review_decisions", Long.class)).isEqualTo(1);
        });
        var assets = application.getBean(com.skillforge.core.workflow.WorkflowAssetService.class);
        for (int i = 0; i < 2; i++) {
            String workflow = "ui-scan-" + i;
            jdbc.update("INSERT INTO nw_workflow_templates(id,workflow_id,name,version,type,status) VALUES(?, ?, ?, '1.0.0', 'state-machine', 'draft')", 994000L + i, workflow, workflow);
            assets.upsertAssets(null, workflow, Map.of("workflow.yaml", (i == 0 ? "name: safe" : "eval(unsafe)").getBytes(StandardCharsets.UTF_8)));
            jdbc.update("UPDATE nw_workflow_templates SET status='review' WHERE workflow_id=?", workflow);
            var item = successData(request("GET", "/api/v1/marketplace/reviews?search=" + workflow, token, null)).path("items").get(0);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> assets.upsertAssets(null, workflow, Map.of("workflow.yaml", "replacement".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class);
            var decision = request("POST", "/api/v1/marketplace/reviews/approve", token, Map.of("workflow_id", workflow, "expected_updated_at", item.path("updated_at").asText()));
            assertThat(decision.statusCode()).as(decision.body()).isEqualTo(i == 0 ? 200 : 422);
            assertThat(jdbc.queryForObject("SELECT status FROM nw_workflow_templates WHERE workflow_id=?", String.class, workflow)).isEqualTo(i == 0 ? "published" : "review");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_workflow_review_decisions WHERE template_id=?", Long.class, 994000L + i)).isEqualTo(i == 0 ? 1L : 0L);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> assets.deleteByWorkflow(workflow)).isInstanceOf(com.skillforge.core.shared.exception.ApiException.class);
        }
    }

    @Test void systemAgentCreationStoresInitialConfigAndOriginalReceipt() throws Exception {
        String token = loginPlatformAdministrator();
        Map<String,Object> modelRequest = Map.ofEntries(
                Map.entry("name", "创建测试模型"), Map.entry("kind", "LLM"), Map.entry("enabled", true),
                Map.entry("provider_id", "deepseek"), Map.entry("service_id", "deepseek-api"),
                Map.entry("profile_version", "2026-09-13"), Map.entry("protocol", "openai-chat-completions"),
                Map.entry("authentication_scheme", "bearer-api-key"), Map.entry("endpoint", "https://api.deepseek.com"),
                Map.entry("model_id", "deepseek-flash"), Map.entry("api_key", "test-key"),
                Map.entry("supported_max_output_tokens", 4096), Map.entry("context_window_tokens", 32000),
                Map.entry("max_output_tokens", 2048), Map.entry("reasoning", "DISABLED"), Map.entry("timeout_seconds", 30),
                Map.entry("input_price_per_million_cents", "25"), Map.entry("output_price_per_million_cents", "125"), Map.entry("currency", "CNY"));
        var receiptPayload = new java.util.HashMap<String,Object>(modelRequest);
        receiptPayload.put("name", "模型创建回执验收");
        String modelRequestId = java.util.UUID.randomUUID().toString();
        var modelCommand = Map.of("request_id", modelRequestId, "payload", receiptPayload);
        var createdWithReceipt = request("POST", "/api/v1/admin/models/create-with-receipt", token, modelCommand);
        assertThat(createdWithReceipt.statusCode()).as(createdWithReceipt.body()).isEqualTo(200);
        var modelReceipt = successData(createdWithReceipt);
        assertThat(createdWithReceipt.body()).doesNotContain("test-key");
        assertThat(modelReceipt.path("model_id").asText()).isNotBlank();
        assertThat(successData(request("POST", "/api/v1/admin/models/create-with-receipt", token, modelCommand))).isEqualTo(modelReceipt);
        assertThat(successData(request("GET", "/api/v1/admin/models/create-with-receipt/result?request_id=" + modelRequestId, token, null))).isEqualTo(modelReceipt);
        var changedPayload = new java.util.HashMap<String,Object>(receiptPayload);
        changedPayload.put("api_key", "different-test-key");
        assertThat(request("POST", "/api/v1/admin/models/create-with-receipt", token, Map.of("request_id", modelRequestId, "payload", changedPayload)).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_model_create_receipts WHERE request_id=?::uuid", Long.class, modelRequestId)).isEqualTo(1);
        var model = request("POST", "/api/v1/admin/models", token, modelRequest);
        assertThat(model.statusCode()).as(model.body()).isEqualTo(201);
        String modelId = successData(model).path("id").asText();
        assertThat(request("POST", "/api/v1/admin/models/publish", token, Map.of("id", modelId, "expected_revision", 1)).statusCode()).isEqualTo(200);
        String path = "/api/v1/admin/system-agents/create-with-receipt", key = java.util.UUID.randomUUID().toString();
        String agentKey = "agent-create-" + java.util.UUID.randomUUID();
        var payload = Map.of("agent_key", agentKey, "name", "创建时配置", "description", "测试", "execution_mode", "AGENT_RUN",
                "system_prompt", "整理输入", "model_config_id", modelId);
        var command = Map.of("request_id", key, "payload", payload);
        assertThat(request("GET", path + "/result?request_id=" + key, token, null).statusCode()).isEqualTo(204);
        var created = request("POST", path, token, command);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var receipt = successData(created); String id = receipt.path("agent_id").asText();
        var detail = successData(request("GET", "/api/v1/admin/system-agents/detail?agentId=" + id, token, null));
        assertThat(detail.path("draft").path("system_prompt").asText()).isEqualTo("整理输入");
        assertThat(detail.path("draft").path("model_config_id").asText()).isEqualTo(modelId);
        assertThat(detail.path("status").asText()).isEqualTo("draft");
        assertThat(successData(request("GET", path + "/result?request_id=" + key, token, null))).isEqualTo(receipt);
        assertThat(successData(request("POST", path, token, command))).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agents WHERE agent_key=?", Long.class, agentKey)).isEqualTo(1);
        assertThat(request("POST", path, token, Map.of("request_id", key, "payload", Map.of("agent_key", agentKey,
                "name", "其他输入", "execution_mode", "AGENT_RUN"))).statusCode()).isEqualTo(409);
        String badKey = java.util.UUID.randomUUID().toString();
        assertThat(request("POST", path, token, Map.of("request_id", badKey, "payload", Map.of("agent_key", "bad-model-" + badKey,
                "name", "无效模型", "execution_mode", "AGENT_RUN", "model_config_id", "1"))).statusCode()).isEqualTo(404);
        assertThat(request("GET", path + "/result?request_id=" + badKey, token, null).statusCode()).isEqualTo(204);
        String suffix = Long.toString(com.skillforge.core.shared.config.SnowflakeIds.nextId());
        String access = "/api/v1/admin/foundation/access";
        var role = request("POST", access + "/role", token, Map.of("code", "agent_create_" + suffix,
                "name", "仅创建智能体", "data_scope", "all", "permissions",
                List.of("foundation.system_agents.create", "foundation.system_agents.read"), "department_ids", List.of()));
        assertThat(role.statusCode()).as(role.body()).isEqualTo(200);
        String username = "agent-create-" + suffix;
        var member = request("POST", access + "/member", token, Map.of("username", username, "display_name", username,
                "password", PASSWORD, "role_ids", List.of(successData(role).path("id").asText())));
        assertThat(member.statusCode()).as(member.body()).isEqualTo(200);
        String limited = successData(request("POST", "/api/v1/platform/auth/login", null,
                Map.of("username", username, "password", PASSWORD))).path("access_token").asText();
        assertThat(request("GET", path + "/result?request_id=" + key, limited, null).statusCode()).isEqualTo(204);
        String deniedKey = java.util.UUID.randomUUID().toString();
        var denied = request("POST", path, limited, Map.of("request_id", deniedKey, "payload", Map.of(
                "agent_key", "limited-" + deniedKey, "name", "无更新权限", "execution_mode", "AGENT_RUN", "system_prompt", "提示")));
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        assertThat(request("GET", path + "/result?request_id=" + deniedKey, limited, null).statusCode()).isEqualTo(204);
        String basicKey = java.util.UUID.randomUUID().toString();
        var basic = request("POST", path, limited, Map.of("request_id", basicKey, "payload", Map.of(
                "agent_key", "limited-" + basicKey, "name", "仅创建", "execution_mode", "AGENT_RUN")));
        assertThat(basic.statusCode()).as(basic.body()).isEqualTo(200);
        verifySystemAgentCreationBrowser(modelId);
    }

    private void verifySystemAgentCreationBrowser(String modelId) throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("frontend-platform/package.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        var frontend = root.resolve("frontend-platform");
        var state = java.nio.file.Files.createDirectories(root.resolve(".agent-work-state"));
        var evidence = java.nio.file.Files.createTempDirectory(state, "system-agent-create-browser-");
        var viteLog = evidence.resolve("vite.log");
        var viteBuilder = new ProcessBuilder("node", frontend.resolve("node_modules/vite/bin/vite.js").toString(), "--host", "127.0.0.1", "--port", "0", "--strictPort");
        viteBuilder.directory(frontend.toFile()); viteBuilder.redirectErrorStream(true); viteBuilder.redirectOutput(viteLog.toFile());
        viteBuilder.environment().put("E2E_BACKEND_URL", "http://127.0.0.1:" + port);
        viteBuilder.environment().put("NO_COLOR", "1");
        var owned = new java.util.LinkedHashSet<ProcessHandle>();
        Process vite = viteBuilder.start(); owned.add(vite.toHandle());
        try {
            String origin = null; long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            while (System.nanoTime() < deadline && vite.isAlive()) {
                vite.descendants().forEach(owned::add);
                var text = java.nio.file.Files.readString(viteLog);
                var match = java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/").matcher(text);
                if (match.find()) { origin = "http://127.0.0.1:" + match.group(1); break; }
                Thread.sleep(100);
            }
            assertThat(origin).as("Vite 未就绪，日志：" + viteLog).isNotNull();
            var browser = new ProcessBuilder("node", frontend.resolve("node_modules/@playwright/test/cli.js").toString(),
                    "test", "--config=playwright.system-agent-create.config.ts");
            browser.directory(frontend.toFile()); browser.redirectErrorStream(true); browser.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = browser.environment(); env.put("E2E_BASE_URL", origin); env.put("E2E_ARTIFACT_DIR", evidence.toString());
            env.put("E2E_USERNAME", USERNAME); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_MODEL_ID", modelId);
            Process process = browser.start(); owned.add(process.toHandle());
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                process.descendants().forEach(owned::add); vite.descendants().forEach(owned::add);
                process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            process.descendants().forEach(owned::add);
            assertThat(process.isAlive()).as("浏览器超时：" + evidence).isFalse();
            assertThat(process.exitValue()).as("浏览器失败：" + evidence).isZero();
            var receipt = objectMapper.readTree(java.nio.file.Files.readString(evidence.resolve("system-agent-create.json")));
            var created = receipt.path("created").path("agent_id").asText();
            assertThat(jdbc.queryForObject("SELECT draft->>'system_prompt' FROM nw_platform_system_agents WHERE id=CAST(? AS bigint)", String.class, created)).isEqualTo("直接保存并发布的提示词");
            assertThat(jdbc.queryForObject("SELECT draft->>'model_config_id' FROM nw_platform_system_agents WHERE id=CAST(? AS bigint)", String.class, created)).isEqualTo(modelId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agents WHERE draft->>'name'='恢复系统助手'", Long.class)).isEqualTo(1);
        } finally {
            vite.descendants().forEach(owned::add);
            for (var handle : owned) if (handle.isAlive()) handle.destroy();
            for (var handle : owned) if (handle.isAlive()) handle.destroyForcibly();
            for (var handle : owned) if (handle.isAlive()) handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void verifySystemAgents(String token) throws Exception {
        assertThat(request("GET","/api/v1/admin/system-agents",null,null).statusCode()).isEqualTo(401);
        Map<String,Object> modelRequest=Map.ofEntries(
                Map.entry("name","System task model"),Map.entry("kind","LLM"),Map.entry("enabled",true),
                Map.entry("provider_id","deepseek"),Map.entry("service_id","deepseek-api"),
                Map.entry("profile_version","2026-09-13"),Map.entry("protocol","openai-chat-completions"),
                Map.entry("authentication_scheme","bearer-api-key"),Map.entry("endpoint","https://api.deepseek.com"),
                Map.entry("model_id","system-test"),Map.entry("api_key","test-key"),
                Map.entry("supported_max_output_tokens",4096),Map.entry("context_window_tokens",32000),
                Map.entry("max_output_tokens",2048),Map.entry("reasoning","DISABLED"),Map.entry("timeout_seconds",30),
                Map.entry("input_price_per_million_cents","25"),Map.entry("output_price_per_million_cents","125"),Map.entry("currency","CNY"));
        var modelResponse=request("POST","/api/v1/admin/models",token,modelRequest);
        assertThat(modelResponse.statusCode()).as(modelResponse.body()).isEqualTo(201);
        String modelId=successData(modelResponse).path("id").asText();
        assertThat(request("POST","/api/v1/admin/models/publish",token,Map.of("id",modelId,"expected_revision",1)).statusCode()).isEqualTo(200);
        var response=request("GET","/api/v1/admin/system-agents/detail?agentId=900000000000000003",token,null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var agent=successData(response);
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)agent.path("draft").deepCopy();
        config.put("model_config_id",modelId);
        response=request("PUT","/api/v1/admin/system-agents/update",token,
                Map.of("agent_id",agent.path("id").asText(),"expected_revision",agent.path("revision").asLong(),"config",config));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        agent=successData(response);
        assertThat(agent.path("published_version").isNull()).isTrue();
        var publish=Map.of("agent_id",agent.path("id").asText(),"expected_revision",agent.path("revision").asLong());
        response=request("POST","/api/v1/admin/system-agents/publish",token,publish);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        agent=successData(response);
        assertThat(agent.path("published_version").asLong()).isEqualTo(1);
        assertThat(request("POST","/api/v1/admin/system-agents/publish",token,publish).statusCode()).isEqualTo(409);
        var memoryContract=new MemoryExtractionCandidates();
        var extractionInput=memoryContract.typedInput(1L,List.of(new MemoryExtractionCandidates.Source(
                "debug-source","记住我喜欢深色",Instant.parse("2026-10-23T00:00:00Z"))),List.of());
        String debugInput=memoryContract.encodeInput(extractionInput);
        String extractionOutput=MemoryJsonCodec.encode(new MemoryExtractionCandidates.ExtractionOutput(
                extractionInput.schemaVersion(),List.of()));
        org.mockito.Mockito.when(systemLlm.generatePinned(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(Duration.class),
                org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(java.util.function.BooleanSupplier.class)))
                .thenReturn(new com.skillforge.core.model.invocation.LlmResult(extractionOutput,"test",null));
        var debug=Map.of("agent_id",agent.path("id").asText(),"source","PUBLISHED",
                "expected_revision",agent.path("revision").asLong(),"input",debugInput,"idempotency_key","debug-1");
        response=request("POST","/api/v1/admin/system-agents/debug",token,debug);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result=successData(response);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("input_tokens").isNull()).isTrue();
        var replay=request("POST","/api/v1/admin/system-agents/debug",token,debug);
        assertThat(successData(replay).path("id").asText()).isEqualTo(result.path("id").asText());
        org.mockito.Mockito.verify(systemLlm,org.mockito.Mockito.times(1)).generatePinned(
                org.mockito.ArgumentMatchers.eq("900000000000000001"),org.mockito.ArgumentMatchers.eq(modelId),
                org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(timeout -> !timeout.isNegative() && !timeout.isZero() && timeout.compareTo(Duration.ofSeconds(30)) <= 0),
                org.mockito.ArgumentMatchers.intThat(limit -> limit > 0),org.mockito.ArgumentMatchers.any(java.util.function.BooleanSupplier.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs WHERE agent_id=900000000000000003",Long.class)).isEqualTo(1);
        assertThat(request("GET","/api/v1/admin/system-agent-tools",token,null).statusCode()).isEqualTo(200);
        verifyGenericSystemAgent(token,modelId,modelRequest);
    }

    @Test
    void systemToolWritesReportOnlyCertainRejectionsWithoutDatabaseEffects() throws Exception {
        String token = loginPlatformAdministrator();
        String endpoint = "/api/v1/admin/system-agent-tools";
        String tool = jdbc.queryForObject("SELECT definition_id::text FROM nw_capability_versions WHERE tenant_id IS NULL "
                + "AND capability_type='TOOL' AND descriptor->>'tool_name'='calculate_expression' AND status='PUBLISHED'", String.class);
        var valid = Map.of("name", "拒绝契约测试", "tool_definition_id", tool, "provider_type", "PLATFORM",
                "provider_ref", "calculate_expression", "environment", "TEST", "active", true);
        long before = jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployments", Long.class);
        var invalidCreate = request("POST", endpoint, token, Map.of("name", "无效", "tool_definition_id", tool,
                "provider_type", "PLATFORM", "provider_ref", "unregistered", "environment", "TEST", "active", true));
        assertThat(invalidCreate.statusCode()).as(invalidCreate.body()).isEqualTo(422);
        assertThat(objectMapper.readTree(invalidCreate.body()).path("code").asText()).isEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployments", Long.class)).isEqualTo(before);
        String key = java.util.UUID.randomUUID().toString();
        var created = request("POST", endpoint, token, valid, key);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = successData(created).path("id").asText();
        var replay = request("POST", endpoint, token, valid, key);
        assertThat(successData(replay).path("id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployments", Long.class)).isEqualTo(before + 1);
        var receipt = successData(request("GET", endpoint + "/receipt?request_id=" + key, token, null));
        assertThat(receipt.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("result").path("id").asText()).isEqualTo(id);
        assertThat(successData(request("GET", endpoint + "/receipt?request_id=" + java.util.UUID.randomUUID(), token, null)).path("status").asText()).isEqualTo("UNKNOWN");
        var changed = new java.util.HashMap<>(valid);
        changed.put("name", "不同内容");
        assertThat(request("POST", endpoint, token, changed, key).statusCode()).isEqualTo(409);
        String parallelKey = java.util.UUID.randomUUID().toString();
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return request("POST", endpoint, token, valid, parallelKey); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return request("POST", endpoint, token, valid, parallelKey); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        var firstResult = first.get();
        var secondResult = second.get();
        assertThat(firstResult.statusCode()).as(firstResult.body()).isEqualTo(201);
        assertThat(secondResult.statusCode()).as(secondResult.body()).isEqualTo(201);
        assertThat(successData(firstResult).path("id").asText()).isEqualTo(successData(secondResult).path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployments", Long.class)).isEqualTo(before + 2);
        var original = jdbc.queryForMap("SELECT draft::text,draft_revision,current_revision,draft_changed FROM nw_platform_agent_tool_deployments WHERE id=CAST(? AS bigint)", id);
        var invalidUpdate = request("PUT", endpoint + "/update", token, Map.of("id", id, "expected_revision", 0, "payload", valid));
        assertThat(invalidUpdate.statusCode()).as(invalidUpdate.body()).isEqualTo(400);
        assertThat(objectMapper.readTree(invalidUpdate.body()).path("code").asText()).isEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        var staleUpdate = request("PUT", endpoint + "/update", token, Map.of("id", id, "expected_revision", 2, "payload", valid));
        assertThat(staleUpdate.statusCode()).as(staleUpdate.body()).isEqualTo(409);
        assertThat(objectMapper.readTree(staleUpdate.body()).path("code").asText()).isEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        var stalePublish = request("POST", endpoint + "/publish", token, Map.of("id", id, "expected_revision", 2));
        assertThat(stalePublish.statusCode()).as(stalePublish.body()).isEqualTo(409);
        assertThat(objectMapper.readTree(stalePublish.body()).path("code").asText()).isEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        var malformed = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint + "/publish"))
                .header("X-Platform-Token", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + id + "\",\"expected_revision\":\"invalid\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(400);
        assertThat(objectMapper.readTree(malformed.body()).path("code").asText()).isEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        String suffix = Long.toString(com.skillforge.core.shared.config.SnowflakeIds.nextId());
        String access = "/api/v1/admin/foundation/access";
        var roleResponse = request("POST", access + "/role", token, Map.of("code", "tool_read_" + suffix,
                "name", "系统工具只读", "data_scope", "all", "permissions", List.of("foundation.system_agent_tools.read"),
                "department_ids", List.of()));
        assertThat(roleResponse.statusCode()).as(roleResponse.body()).isEqualTo(200);
        String username = "tool-reader-" + suffix;
        var memberResponse = request("POST", access + "/member", token, Map.of("username", username,
                "display_name", username, "password", PASSWORD, "role_ids", List.of(successData(roleResponse).path("id").asText())));
        assertThat(memberResponse.statusCode()).as(memberResponse.body()).isEqualTo(200);
        var login = request("POST", "/api/v1/platform/auth/login", null, Map.of("username", username, "password", PASSWORD));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String reader = successData(login).path("access_token").asText();
        assertThat(request("GET", endpoint, reader, null).statusCode()).isEqualTo(200);
        assertThat(successData(request("GET", endpoint + "/receipt?request_id=" + key, reader, null)).path("status").asText()).isEqualTo("UNKNOWN");
        for (String credential : List.of("", reader)) {
            String selected = credential.isEmpty() ? null : credential;
            int deniedStatus = selected == null ? 401 : 403;
            for (var denied : List.of(request("POST", endpoint, selected, valid),
                    request("PUT", endpoint + "/update", selected, Map.of("id", id, "expected_revision", 1, "payload", valid)),
                    request("POST", endpoint + "/publish", selected, Map.of("id", id, "expected_revision", 1)))) {
                assertThat(denied.statusCode()).as(denied.body()).isEqualTo(deniedStatus);
                assertThat(objectMapper.readTree(denied.body()).path("code").asText()).isEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
            }
        }
        var anonymousRead = request("GET", endpoint, null, null);
        assertThat(anonymousRead.statusCode()).isEqualTo(401);
        assertThat(objectMapper.readTree(anonymousRead.body()).path("code").asText()).isNotEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        var unrelated = request("POST", "/api/v1/admin/system-agents/publish", null, Map.of());
        assertThat(unrelated.statusCode()).isEqualTo(401);
        assertThat(objectMapper.readTree(unrelated.body()).path("code").asText()).isNotEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
        assertThat(jdbc.queryForMap("SELECT draft::text,draft_revision,current_revision,draft_changed FROM nw_platform_agent_tool_deployments WHERE id=CAST(? AS bigint)", id)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployment_versions WHERE id=CAST(? AS bigint)", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployment_audits WHERE deployment_id=CAST(? AS bigint)", Long.class, id)).isZero();
        jdbc.execute("CREATE FUNCTION test_system_tool_publish_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test publish failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_system_tool_publish_failure BEFORE INSERT ON nw_platform_agent_tool_deployment_versions FOR EACH ROW EXECUTE FUNCTION test_system_tool_publish_failure()");
        try {
            String failureKey = java.util.UUID.randomUUID().toString();
            var failure = request("POST", endpoint + "/publish", token, Map.of("id", id, "expected_revision", 1), failureKey);
            assertThat(successData(request("GET", endpoint + "/receipt?request_id=" + failureKey, token, null)).path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(failure.statusCode()).as(failure.body()).isEqualTo(500);
            assertThat(objectMapper.readTree(failure.body()).path("code").asText()).isNotEqualTo("SYSTEM_AGENT_TOOL_WRITE_REJECTED");
            assertThat(jdbc.queryForMap("SELECT draft::text,draft_revision,current_revision,draft_changed FROM nw_platform_agent_tool_deployments WHERE id=CAST(? AS bigint)", id)).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployment_versions WHERE id=CAST(? AS bigint)", Long.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_agent_tool_deployment_audits WHERE deployment_id=CAST(? AS bigint)", Long.class, id)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_system_tool_publish_failure ON nw_platform_agent_tool_deployment_versions");
            jdbc.execute("DROP FUNCTION test_system_tool_publish_failure()");
        }
        var published = request("POST", endpoint + "/publish", token,
                Map.of("id", id, "expected_revision", 1), java.util.UUID.randomUUID().toString());
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        var options = successData(request("GET", "/api/v1/admin/system-agents/mount-options", token, null));
        assertThat(options.path("deployments")).anySatisfy(item -> {
            assertThat(item.path("id").asText()).isEqualTo(id);
            assertThat(item.path("tool_definition_id").asText()).isEqualTo(tool);
            assertThat(item.path("environment").asText()).isEqualTo("TEST");
        });
    }

    /** 仅替换外部供应商传输，受理、Session、Worker 和状态持久化均走真实装配。 */
    private void verifyGenericSystemAgent(String token,String modelId,Map<String,Object> modelWrite) throws Exception {
        var admissionFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        org.mockito.Mockito.doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            catch (Throwable failure) { admissionFailure.set(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure)); throw failure; }
        }).when(systemRunner).execute(org.mockito.ArgumentMatchers.any());
        String toolVersion=jdbc.queryForObject("SELECT id::text FROM nw_capability_versions WHERE tenant_id IS NULL AND capability_type='TOOL' AND descriptor->>'tool_name'='calculate_expression' AND status='PUBLISHED'",String.class);
        String toolDefinition=jdbc.queryForObject("SELECT definition_id::text FROM nw_capability_versions WHERE id=CAST(? AS bigint)",String.class,toolVersion);
        var deploymentResponse=request("POST","/api/v1/admin/system-agent-tools",token,Map.of(
                "name","计算测试","tool_definition_id",toolDefinition,"provider_type","PLATFORM",
                "provider_ref","calculate_expression","environment","TEST","active",true));
        assertThat(deploymentResponse.statusCode()).as(deploymentResponse.body()).isEqualTo(201);
        String deployment=successData(deploymentResponse).path("id").asText();
        assertThat(request("POST","/api/v1/admin/system-agent-tools/publish",token,Map.of("id",deployment,"expected_revision",1)).statusCode()).isEqualTo(200);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var modelRequests=new java.util.concurrent.CopyOnWriteArrayList<ModelProvider.InvocationRequest>();
        String nativeCallId="platform-calculation-call-1";
        org.mockito.Mockito.doAnswer(invocation -> {
            ModelProvider.InvocationRequest modelRequest=invocation.getArgument(1);
            modelRequests.add(modelRequest);
            return (java.util.concurrent.Flow.Publisher<ModelProvider.StreamEvent>) subscriber ->
                subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    boolean sent;
                    public void request(long count) {
                        if(sent || count<=0)return;
                        sent=true;
                        var usage=new ModelPort.ModelUsage(
                                java.util.OptionalLong.of(12),java.util.OptionalLong.of(8));
                        boolean toolRound=calls.getAndIncrement()==0;
                        var toolCalls=toolRound?List.of(new ModelProvider.ToolCall(nativeCallId,
                                modelRequest.tools().stream().filter(tool->tool.kind()==ModelPort.ModelToolKind.TOOL_CALL)
                                        .findFirst().orElseThrow().name(),"{\"expression\":\"1+2\"}")):List.<ModelProvider.ToolCall>of();
                        subscriber.onNext(new ModelProvider.StreamEvent.Completed(
                            new ModelProvider.InvocationResult.Completed(toolRound?null:"真实 Runtime 完成",
                                    toolCalls,modelRequest.modelId(),usage,toolRound?"tool_calls":"stop")));
                        subscriber.onComplete();
                    }
                    public void cancel(){sent=true;}
                });
        }).when(runtimeProvider).stream(org.mockito.ArgumentMatchers.eq("deepseek"), org.mockito.ArgumentMatchers.any());
        var created=request("POST","/api/v1/admin/system-agents",token,
                Map.of("agent_key","runtime-integration","name","通用系统测试","execution_mode","AGENT_RUN"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var agent=successData(created);
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)agent.path("draft").deepCopy();
        config.put("model_config_id",modelId).put("system_prompt","按要求回答").put("max_business_model_calls",3);
        config.putArray("mounts").addObject().put("capability_type","TOOL").put("capability_definition_id",toolDefinition)
                .put("priority",1).put("tool_deployment_id",deployment);
        var saved=request("PUT","/api/v1/admin/system-agents/update",token,
                Map.of("agent_id",agent.path("id").asText(),"expected_revision",1,"config",config));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        agent=successData(saved);
        var debug=Map.of("agent_id",agent.path("id").asText(),"source","DRAFT",
                "expected_revision",agent.path("revision").asLong(),"input","验证通用 Runtime","idempotency_key","runtime-debug-1");
        var response=request("POST","/api/v1/admin/system-agents/debug",token,debug);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var run=successData(response);
        if (admissionFailure.get() instanceof com.skillforge.runtime.api.RuntimeAccessException denied) {
            throw new AssertionError("Runtime admission: " + denied.reasonCode(), denied);
        }
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while("RUNNING".equals(run.path("status").asText()) && System.nanoTime()<deadline) {
            Thread.sleep(500);
            application.getBean(com.skillforge.core.agent.systemagent.SystemAgentExecution.class).recoverDebugRuns();
            var history=request("GET","/api/v1/admin/system-agents/runs?agentId="+agent.path("id").asText(),token,null);
            run=successData(history).get(0);
        }
        assertThat(run.path("status").asText()).as("%s; runtime=%s; events=%s; provider_calls=%s; admission=%s", run,
                jdbc.queryForList("SELECT status,phase,decisions FROM agent_runtime_execution_runs WHERE run_id=? LIMIT 1", "system-agent-run:" + run.path("id").asText()),
                jdbc.queryForList("SELECT type,canonical_json::jsonb->>'status' AS status,canonical_json::jsonb->>'reason' AS reason FROM agent_runtime_execution_events WHERE run_id=? ORDER BY event_sequence LIMIT 20", "system-agent-run:" + run.path("id").asText()),
                calls.get(), admissionFailure.get()).isEqualTo("COMPLETED");
        assertThat(run.path("output").asText()).contains("真实 Runtime 完成");
        verifyDebugResultLookup(token,agent.path("id").asText(),run);
        assertThat(modelRequests).hasSize(2).allSatisfy(modelRequest->{
            assertThat(modelRequest.modelId()).isEqualTo("system-test");
            assertThat(modelRequest.profile().serviceId()).isEqualTo("deepseek-api");
            assertThat(modelRequest.profile().profileVersion()).isEqualTo("2026-09-13");
            assertThat(modelRequest.response().format()).isInstanceOf(ModelPort.ResponseFormat.NativeConversation.class);
        });
        var declaredTools=modelRequests.getFirst().tools().stream()
                .filter(tool->tool.kind()==ModelPort.ModelToolKind.TOOL_CALL).toList();
        assertThat(declaredTools).hasSize(1);
        assertThat(modelRequests.getFirst().toolChoice()).isNotEqualTo(ModelProvider.ToolChoice.NONE);
        var continued=modelRequests.get(1);
        assertThat(continued.messages().stream().filter(message->message.role()==ModelProvider.Role.ASSISTANT)
                .flatMap(message->message.toolCalls().stream()).toList())
                .containsExactly(new ModelProvider.ToolCall(nativeCallId,declaredTools.getFirst().name(),"{\"expression\":\"1+2\"}"));
        var toolResults=continued.messages().stream().filter(message->message.role()==ModelProvider.Role.TOOL).toList();
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.getFirst().toolCallId()).isEqualTo(nativeCallId);
        var calculated=objectMapper.readTree(toolResults.getFirst().textContent());
        assertThat(calculated.path("success").asBoolean()).isTrue();
        assertThat(calculated.path("value").asText()).isEqualTo("3");
        var replay=request("POST","/api/v1/admin/system-agents/debug",token,debug);
        assertThat(successData(replay).path("id").asText()).isEqualTo(run.path("id").asText());
        org.mockito.Mockito.verify(runtimeProvider,org.mockito.Mockito.times(2)).stream(
                org.mockito.ArgumentMatchers.eq("deepseek"),org.mockito.ArgumentMatchers.any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_runtime_host_owners WHERE agent_id=?",Long.class,
                agent.path("id").asText())).isEqualTo(1);
        // 真实供应商报告保留原修订；最终计量仅追加 measured，不伪造 Long.MAX_VALUE 报告。
        // 幂等重放应保留每次调用的一条 measured 事实。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE agent_id=? AND call_point='system-agent.model' AND usage_event_kind='measured'",Long.class,
                Long.parseLong(agent.path("id").asText()))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(prompt_tokens) FROM nw_usage_events WHERE agent_id=? AND call_point='system-agent.model' AND usage_event_kind='measured'",Long.class,
                Long.parseLong(agent.path("id").asText()))).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT sum(completion_tokens) FROM nw_usage_events WHERE agent_id=? AND call_point='system-agent.model' AND usage_event_kind='measured'",Long.class,
                Long.parseLong(agent.path("id").asText()))).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE agent_id=? AND call_point='system-agent.model' AND usage_event_kind='usage_report' AND report_revision=?",Long.class,
                Long.parseLong(agent.path("id").asText()), Long.MAX_VALUE)).isZero();
        String month=java.time.YearMonth.from(java.time.OffsetDateTime.parse(run.path("created_at").asText())
                .atZoneSameInstant(com.skillforge.common.time.BusinessTime.ZONE)).toString();
        String monthlyPath="/api/v1/platform/billing/monthly?tenant_id=900000000000000001&month="+month;
        var monthly=successData(request("GET",monthlyPath,token,null));
        assertThat(monthly.path("model_calls").asLong()).isEqualTo(2);
        assertThat(monthly.path("missing_cost_count").asLong()).isZero();
        assertThat(new java.math.BigDecimal(monthly.path("total_amount").asText())).isEqualByComparingTo("0.000026");
        assertThat(monthly.path("items")).hasSize(2);
        for(var item:monthly.path("items"))assertThat(item.path("cost_status").asText()).isEqualTo("priced");
        var revisedPrice=new java.util.HashMap<String,Object>(modelWrite);
        revisedPrice.put("expected_revision",1);revisedPrice.put("api_key",null);
        revisedPrice.put("input_price_per_million_cents","2500");revisedPrice.put("output_price_per_million_cents","12500");
        var revised=request("PUT","/api/v1/admin/models/update",token,Map.of("id",modelId,"payload",revisedPrice));
        assertThat(revised.statusCode()).as(revised.body()).isEqualTo(200);
        assertThat(successData(revised).path("credential_configured").asBoolean()).isTrue();
        var republished=request("POST","/api/v1/admin/models/publish",token,Map.of("id",modelId,"expected_revision",2));
        assertThat(republished.statusCode()).as(republished.body()).isEqualTo(200);
        assertThat(successData(request("GET",monthlyPath,token,null))).isEqualTo(monthly);
        verifySystemAgentIsolation();
    }

    /** GET 只核对账本，不重新受理、恢复 Worker 或调用外部供应商。 */
    private void verifyDebugResultLookup(String token,String agentId,JsonNode run) throws Exception {
        String path="/api/v1/admin/system-agents/debug-result?agentId="+agentId+"&idempotencyKey=runtime-debug-1";
        var ledgerBefore=jdbc.queryForList("SELECT to_jsonb(r)::text AS receipt FROM nw_platform_system_agent_runs r ORDER BY id");
        int runnerCalls=org.mockito.Mockito.mockingDetails(systemRunner).getInvocations().size();
        int providerCalls=org.mockito.Mockito.mockingDetails(runtimeProvider).getInvocations().size();
        assertThat(request("GET",path,null,null).statusCode()).isEqualTo(401);
        assertThat(successData(request("GET",path,token,null))).isEqualTo(run);
        assertThat(successData(request("GET",path,token,null))).isEqualTo(run);
        assertThat(request("GET",path.replace("runtime-debug-1","missing-debug-key"),token,null).statusCode()).isEqualTo(404);
        assertThat(request("GET",path.replace(agentId,"900000000000000003"),token,null).statusCode()).isEqualTo(404);
        assertThat(request("GET",path.replace("runtime-debug-1","%20"),token,null).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForList("SELECT to_jsonb(r)::text AS receipt FROM nw_platform_system_agent_runs r ORDER BY id"))
                .isEqualTo(ledgerBefore);
        assertThat(org.mockito.Mockito.mockingDetails(systemRunner).getInvocations()).hasSize(runnerCalls);
        assertThat(org.mockito.Mockito.mockingDetails(runtimeProvider).getInvocations()).hasSize(providerCalls);

        // 在本测试独占 PG 中构造不可暴露的回执；不会派发模型或 Runtime。
        Long tenant=jdbc.queryForObject("SELECT id FROM nw_tenants WHERE id<>900000000000000001 ORDER BY id LIMIT 1",Long.class);
        jdbc.update("""
                INSERT INTO nw_platform_system_agent_runs
                    (id,tenant_id,agent_id,idempotency_key,input_fingerprint,version,debug,config,model_revision,input,status)
                SELECT 99100911,tenant_id,agent_id,'lookup-nondebug',input_fingerprint,version,false,config,model_revision,input,'COMPLETED'
                FROM nw_platform_system_agent_runs WHERE id=CAST(? AS bigint)
                """,run.path("id").asText());
        jdbc.update("""
                INSERT INTO nw_platform_system_agent_runs
                    (id,tenant_id,agent_id,idempotency_key,input_fingerprint,version,debug,config,model_revision,input,status)
                SELECT 99100912,?,agent_id,'lookup-other-tenant',input_fingerprint,version,true,config,model_revision,input,'COMPLETED'
                FROM nw_platform_system_agent_runs WHERE id=CAST(? AS bigint)
                """,tenant,run.path("id").asText());
        try {
            assertThat(request("GET",path.replace("runtime-debug-1","lookup-nondebug"),token,null).statusCode()).isEqualTo(404);
            assertThat(request("GET",path.replace("runtime-debug-1","lookup-other-tenant"),token,null).statusCode()).isEqualTo(404);
            var history=successData(request("GET","/api/v1/admin/system-agents/runs?agentId="+agentId,token,null));
            assertThat(history).hasSize(1);
            assertThat(history.get(0)).isEqualTo(run);
            assertThat(org.mockito.Mockito.mockingDetails(systemRunner).getInvocations()).hasSize(runnerCalls);
            assertThat(org.mockito.Mockito.mockingDetails(runtimeProvider).getInvocations()).hasSize(providerCalls);
        } finally {
            jdbc.update("DELETE FROM nw_platform_system_agent_runs WHERE id IN (99100911,99100912)");
        }
    }

    private void verifySystemAgentIsolation() {
        jdbc.execute("CREATE ROLE system_agent_rls_test NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT SELECT ON nw_platform_system_agent_runs,nw_runtime_host_owners TO system_agent_rls_test");
        new org.springframework.transaction.support.TransactionTemplate(
                application.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE system_agent_rls_test");
            jdbc.execute("SELECT set_config('app.platform_admin','false',true)");
            jdbc.execute("SELECT set_config('app.tenant_id','123456',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_runtime_host_owners",Long.class)).isZero();
            jdbc.execute("SELECT set_config('app.tenant_id','900000000000000001',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_runtime_host_owners",Long.class)).isEqualTo(1);
        });
    }

    /** 成功业务数据只从当前 HTTP 信封读取；请求级 trace_id 不参与幂等回执比较。 */
    private JsonNode successData(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        JsonNode envelope = objectMapper.readTree(response.body());
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        assertThat(envelope.path("trace_id").asText()).isNotBlank();
        assertThat(envelope.has("data")).isTrue();
        return envelope.path("data");
    }

    private JsonNode findGroup(JsonNode groups, String key) {
        for (JsonNode group : groups) {
            if (key.equals(group.path("key").asText())) {
                return group;
            }
        }
        throw new AssertionError("运营配置分组不存在: " + key);
    }

    private HttpResponse<String> request(
            String method, String path, String token, Object body) throws Exception {
        return request(method, path, token, body, java.util.UUID.randomUUID().toString());
    }

    private HttpResponse<String> request(
            String method, String path, String token, Object body, String requestId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (!method.equals("GET") && (path.startsWith("/api/v1/admin/system-agent-tools") || path.equals("/api/v1/platform/agent-definitions"))) {
            builder.header("Idempotency-Key", requestId);
        }
        if (token != null) {
            builder.header("X-Platform-Token", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
