package com.skillforge.platform.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.platform.PlatformApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 新库真实建制和随机端口的复杂平台治理链；不调用外部供应商或开发数据库。 */
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformGovernanceComplexHttpIntegrationTest {
    private static final String BASE = "/api/v1/admin/foundation/";
    private static final String USER = "synthetic-platform-governance";
    private static final String PASSWORD = "SyntheticPlatform123";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("platform_governance").withUsername("skillforge").withPassword("synthetic_test");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private HttpClient http;
    private String token;
    private String prefix;

    /** 正式初始化器创建平台管理员及真实角色；所有可变依赖均是本例容器。 */
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("skillforge.secret-key", () -> "synthetic-platform-governance-secret-32");
        registry.add("sa-token.jwt-secret-key", () -> "synthetic-platform-governance-jwt-32");
        registry.add("skillforge.platform-admin-username", () -> USER);
        registry.add("skillforge.platform-admin-password", () -> PASSWORD);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }

    /** 每例唯一业务编码；凭据通过正式HTTP登录签发。 */
    @BeforeEach
    void authenticatedFixture() throws Exception {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        prefix = "complex_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        token = login(USER);
    }

    /** 客户端池不跨案例保留。 */
    @AfterEach
    void closeClient() { if (http != null) http.close(); }

    /** 平台技能一步创建复用真实平台登录、双权限与公共 scope；不调用模型或发布。 */
    @Test
    void platformSkillCreationRequiresBothPermissionsAndCreatesOnlyPublicDraft() throws Exception {
        String path = "/api/v1/admin/capabilities/skill-drafts";
        long definitions = count("SELECT count(*) FROM nw_capability_definitions");
        long drafts = count("SELECT count(*) FROM nw_skill_editing_drafts");
        long versions = count("SELECT count(*) FROM nw_capability_versions");
        String authorized = null;
        var permissionSets = List.of(List.of("foundation.skills.create"),
                List.of("foundation.capabilities.create"),
                List.of("foundation.skills.create", "foundation.capabilities.create"));
        for (int index = 0; index < permissionSets.size(); index++) {
            String code = prefix + "_skill_" + index;
            String role = data(call("POST", BASE + "access/role", token, Map.of("code", code,
                    "name", "技能创建", "data_scope", "all", "permissions", permissionSets.get(index),
                    "department_ids", List.of()))).path("id").asText();
            data(call("POST", BASE + "access/member", token, Map.of("username", code,
                    "display_name", "技能成员", "password", PASSWORD, "role_ids", List.of(role))));
            String credential = login(code);
            if (index < 2) {
                error(call("POST", path + "/new", credential, Map.of("capability_key", code,
                        "name", "不得创建", "description", "缺少一个必要权限")),
                        403, "foundation.permission.denied");
                assertEquals(definitions, count("SELECT count(*) FROM nw_capability_definitions"));
                assertEquals(drafts, count("SELECT count(*) FROM nw_skill_editing_drafts"));
                assertEquals(versions, count("SELECT count(*) FROM nw_capability_versions"));
            } else {
                authorized = credential;
            }
        }
        assertNotNull(authorized);
        String key = prefix + "_public_skill";
        // JSON 中伪造租户归属不能把平台入口变成租户写入口。
        var body = Map.of("capability_key", key, "name", "平台空技能", "description", "仅公共工作草稿",
                "tenant_id", "900000000000000001");
        var response = call("POST", path + "/new", authorized, body);
        assertEquals(201, response.statusCode());
        var draft = data(response);
        long definitionId = draft.path("definition_id").asLong();
        long draftId = draft.path("id").asLong();
        assertTrue(definitionId > 0 && draftId > 0);
        assertEquals(1L, draft.path("revision").asLong());
        assertEquals("", draft.path("version_key").asText());
        assertEquals(0, draft.path("files").size());
        assertTrue(draft.path("published_version_id").isNull());
        assertEquals(definitions + 1, count("SELECT count(*) FROM nw_capability_definitions"));
        assertEquals(drafts + 1, count("SELECT count(*) FROM nw_skill_editing_drafts"));
        assertEquals(versions, count("SELECT count(*) FROM nw_capability_versions"));
        assertEquals(1L, count("SELECT count(*) FROM nw_capability_definitions WHERE id=? AND tenant_id IS NULL"
                + " AND capability_type='SKILL' AND status='DRAFT' AND default_version_id IS NULL AND capability_key=?", definitionId, key));
        assertEquals(1L, count("SELECT count(*) FROM nw_skill_editing_drafts WHERE id=? AND definition_id=?"
                + " AND tenant_id IS NULL AND base_version_id IS NULL AND published_version_id IS NULL", draftId, definitionId));
        assertEquals(draft, data(call("GET", path + "?definitionId=" + definitionId, token, null)));
        error(call("POST", path + "/new", authorized, body), 409, "CONFLICT");
        assertEquals(definitions + 1, count("SELECT count(*) FROM nw_capability_definitions"));
        assertEquals(drafts + 1, count("SELECT count(*) FROM nw_skill_editing_drafts"));
        assertEquals(versions, count("SELECT count(*) FROM nw_capability_versions"));
    }

    /** 新管理员依靠正式角色而非旧role列获得权限，HTTP凭据只在平台命名空间消费。 */
    @Test
    void bootstrapUsesRealRolesAndRejectsWrongCredentialHeaderWithoutWrites() throws Exception {
        var snapshot = data(call("GET", BASE + "access/snapshot", token, null));
        assertTrue(snapshot.path("permissions").toString().contains("foundation.models.create"));
        assertEquals(1L, count("SELECT count(*) FROM nw_foundation_member_roles WHERE scope_key='platform' AND member_id=(SELECT id FROM nw_platform_members WHERE username=?)", USER));
        List<String> before = rows("nw_foundation_dictionaries", "owner_key", "platform");
        var request = HttpRequest.newBuilder(uri(BASE + "catalog/dictionaries"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(15)).GET().build();
        error(http.send(request, HttpResponse.BodyHandlers.ofByteArray()), 401, "UNAUTHORIZED");
        data(call("GET", BASE + "catalog/dictionaries", token, null));
        assertEquals(before, rows("nw_foundation_dictionaries", "owner_key", "platform"));
    }

    /** 四级平台组织拒绝成环和陈旧写；最后管理员停用不改变账号/资料事实。 */
    @Test
    void accessFourLevelTreeAndLastAdministratorRemainConsistent() throws Exception {
        List<String> departments = new ArrayList<>();
        for (int level = 0; level < 4; level++) {
            var command = new LinkedHashMap<String, Object>();
            command.put("code", prefix + "_d" + level); command.put("name", "第" + level + "层");
            if (level > 0) command.put("parent_id", departments.get(level - 1));
            departments.add(data(call("POST", BASE + "access/department", token, command)).path("id").asText());
        }
        var before = rows("nw_foundation_departments", "scope_key", "platform");
        error(call("PUT", BASE + "access/department", token, Map.of("id", departments.get(0), "name", "成环",
                "parent_id", departments.get(3), "enabled", true, "lock_version", 1)), 400, "foundation.access.invalid");
        assertEquals(before, rows("nw_foundation_departments", "scope_key", "platform"));
        var edit = Map.of("id", departments.get(3), "name", "赢家", "parent_id", departments.get(2), "enabled", true, "lock_version", 1);
        data(call("PUT", BASE + "access/department", token, edit));
        var winner = rows("nw_foundation_departments", "scope_key", "platform");
        error(call("PUT", BASE + "access/department", token, edit), 409, "foundation.access.version_conflict");
        assertEquals(winner, rows("nw_foundation_departments", "scope_key", "platform"));
        String adminId = jdbc.queryForObject("SELECT id::text FROM nw_platform_members WHERE username=?", String.class, USER);
        var account = rows("nw_platform_members", "id", adminId);
        error(call("PUT", BASE + "access/member", token, Map.of("id", adminId, "display_name", "平台管理员", "status", "disabled", "lock_version", 1)),
                409, "foundation.access.last_administrator");
        assertEquals(account, rows("nw_platform_members", "id", adminId));
    }

    /** 普通平台成员可管理本人偏好，组织读取动作不能成为模型或机构写权限。 */
    @Test
    void readerPreferencesArePersonalAndDeniedWritesLeaveNoBusinessRows() throws Exception {
        String role = data(call("POST", BASE + "access/role", token, Map.of("code", prefix + "_reader", "name", "只读运营",
                "data_scope", "self", "permissions", List.of("foundation.access.read", "foundation.preferences.manage"), "department_ids", List.of()))).path("id").asText();
        String reader = prefix + "_reader";
        data(call("POST", BASE + "access/member", token, Map.of("username", reader, "display_name", "只读成员", "password", PASSWORD, "role_ids", List.of(role))));
        String credential = login(reader);
        assertEquals(count("SELECT count(*) FROM nw_platform_members"),
                data(call("GET", BASE + "access/members?page_size=100", credential, null)).path("total").asLong());
        var own = data(call("PUT", BASE + "catalog/preferences", credential, Map.of("theme", "dark", "locale", "zh-CN", "page_size", 50,
                "compact_navigation", true, "expected_revision", 0)));
        assertEquals("dark", own.path("theme").asText());
        assertEquals("system", data(call("GET", BASE + "catalog/preferences", token, null)).path("theme").asText());
        long models = count("SELECT count(*) FROM nw_platform_models");
        long tenants = count("SELECT count(*) FROM nw_tenants");
        error(call("POST", "/api/v1/admin/models", credential, embedding("forbidden", 0, true)), 403, "foundation.permission.denied");
        error(call("POST", "/api/v1/admin/tenants", credential, tenantCommand(prefix + "_denied")), 403, "foundation.permission.denied");
        assertEquals(models, count("SELECT count(*) FROM nw_platform_models")); assertEquals(tenants, count("SELECT count(*) FROM nw_tenants"));
    }

    /** 十二值的精确排序和四层导航，目录CAS/成环/父节点删除都保留完整赢家事实。 */
    @Test
    void platformDictionaryAndNavigationRejectStaleAndCyclicEditsAtomically() throws Exception {
        String dictionary = prefix + "_status";
        var create = Map.of("code", dictionary, "name", "平台业务阶段", "enabled", true, "expected_revision", 0);
        data(call("PUT", BASE + "catalog/dictionaries", token, create));
        for (int row = 0; row < 12; row++) data(call("PUT", BASE + "catalog/dictionary-values", token,
                Map.of("dictionary_code", dictionary, "code", "state_" + row, "label", "阶段," + row + "\n第二行", "sort_order", row,
                        "enabled", row % 3 != 0, "expected_revision", 0)));
        var values = data(call("GET", BASE + "catalog/dictionary-values?dictionary_code=" + dictionary, token, null));
        assertEquals(12, values.size());
        var options = data(call("GET", BASE + "catalog/dictionary-options?dictionary_code=" + dictionary, token, null));
        assertEquals(8, options.size());
        for (int i = 0; i < 8; i++) assertEquals("state_" + (i + 1 + i / 2), options.get(i).path("code").asText());
        error(call("PUT", BASE + "catalog/dictionaries", token, create), 409, "catalog_revision_conflict");
        assertEquals(values, data(call("GET", BASE + "catalog/dictionary-values?dictionary_code=" + dictionary, token, null)));
        for (int depth = 0; depth < 4; depth++) data(call("PUT", BASE + "catalog/navigation", token,
                navigation(prefix + "_n" + depth, depth == 0 ? null : prefix + "_n" + (depth - 1), 0)));
        var nodes = rows("nw_foundation_navigation_nodes", "owner_key", "platform");
        error(call("PUT", BASE + "catalog/navigation", token, navigation(prefix + "_n0", prefix + "_n3", 1)), 400, "catalog_invalid");
        error(call("DELETE", BASE + "catalog/navigation", token, Map.of("code", prefix + "_n0", "expected_revision", 1)), 400, "catalog_invalid");
        assertEquals(nodes, rows("nw_foundation_navigation_nodes", "owner_key", "platform"));
    }

    /** 三机构同名管理员合法；重复slug及非法材料不能产生半建制账号和权限。 */
    @Test
    void tenantOpeningIsAtomicAndDuplicateSlugKeepsOriginalGovernance() throws Exception {
        var first = createTenant(prefix + "_a"); var second = createTenant(prefix + "_b"); var third = createTenant(prefix + "_c");
        for (JsonNode tenant : List.of(first, second, third)) {
            assertEquals(1, tenant.path("member_count").asLong());
            assertEquals(1L, count("SELECT count(*) FROM nw_foundation_member_roles mr JOIN nw_foundation_roles r ON r.id=mr.role_id AND r.scope_key=mr.scope_key WHERE mr.scope_key=? AND r.administrator", tenant.path("id").asText()));
        }
        var accounts = rows("nw_tenant_members", "tenant_id", first.path("id").asText());
        var roles = rows("nw_foundation_roles", "scope_key", first.path("id").asText());
        error(call("POST", "/api/v1/admin/tenants", token, tenantCommand(prefix + "_a")), 409, "CONFLICT");
        assertEquals(accounts, rows("nw_tenant_members", "tenant_id", first.path("id").asText()));
        assertEquals(roles, rows("nw_foundation_roles", "scope_key", first.path("id").asText()));
        var invalid = new LinkedHashMap<String, Object>(tenantCommand(prefix + "_bad")); invalid.put("admin_password", "密".repeat(25));
        error(call("POST", "/api/v1/admin/tenants", token, invalid), 400, "VALIDATION_ERROR");
        assertEquals(0L, count("SELECT count(*) FROM nw_tenants WHERE slug=?", prefix + "_bad"));
        assertEquals(204, call("GET", "/api/v1/admin/tenants/find-by-slug?slug=" + prefix + "_bad", token, null).statusCode());
    }

    /** 状态切换只改变明确目标，丢失创建响应可只读恢复同一机构身份。 */
    @Test
    void tenantStatusAndLostResponseRecoveryPreserveIndependentInstitutions() throws Exception {
        var first = createTenant(prefix + "_a"); var second = createTenant(prefix + "_b"); var third = createTenant(prefix + "_c");
        String id = first.path("id").asText();
        var accounts = rows("nw_tenant_members", "tenant_id", id);
        assertEquals(id, data(call("GET", "/api/v1/admin/tenants/find-by-slug?slug=" + prefix + "_a", token, null)).path("id").asText());
        assertEquals("suspended", data(call("POST", "/api/v1/admin/tenants/suspend", token, Map.of("tenant_id", id))).path("status").asText());
        for (JsonNode tenant : List.of(second, third)) assertEquals("active", data(call("GET", "/api/v1/admin/tenants/detail?tenantId=" + tenant.path("id").asText(), token, null)).path("status").asText());
        assertEquals("active", data(call("POST", "/api/v1/admin/tenants/activate", token, Map.of("tenant_id", id))).path("status").asText());
        assertEquals(accounts, rows("nw_tenant_members", "tenant_id", id));
        assertEquals(1L, count("SELECT count(*) FROM nw_tenants WHERE slug=?", prefix + "_a"));
    }

    /** 套餐授权、显式拒绝及删除恢复继承；最大Long字节额度始终精确字符串。 */
    @Test
    void featureInheritanceAndTypedQuotaCasAreScopedAndExact() throws Exception {
        var first = createTenant(prefix + "_a"); var second = createTenant(prefix + "_b"); var third = createTenant(prefix + "_c");
        String feature = prefix + ".feature";
        data(call("PUT", BASE + "catalog/features", token, Map.of("code", feature, "name", "复杂授权", "description", "三个机构测试", "enabled", true, "expected_revision", 0)));
        data(call("PUT", BASE + "catalog/plan-features", token, Map.of("plan_id", first.path("plan_id").asText(), "feature_code", feature, "enabled", true, "expected_revision", 0)));
        String id = first.path("id").asText();
        var denied = Map.of("tenant_id", id, "feature_code", feature, "enabled", false, "expected_revision", 0);
        data(call("PUT", BASE + "catalog/tenant-features", token, denied));
        assertFalse(entitlements(id).path("features").toString().contains(feature));
        for (JsonNode tenant : List.of(second, third)) assertTrue(entitlements(tenant.path("id").asText()).path("features").toString().contains(feature));
        error(call("PUT", BASE + "catalog/tenant-features", token, denied), 409, "catalog_revision_conflict");
        var restore = new LinkedHashMap<String, Object>(); restore.put("tenant_id", id); restore.put("feature_code", feature); restore.put("enabled", null); restore.put("expected_revision", 1);
        data(call("PUT", BASE + "catalog/tenant-features", token, restore)); assertTrue(entitlements(id).path("features").toString().contains(feature));
        var initial = entitlements(id);
        var limits = new LinkedHashMap<String, Object>(); for (String key : List.of("agents", "workflows", "knowledge_bases", "members", "channels", "admin_tasks", "sub_institutions")) limits.put(key, null);
        limits.put("members", 12); limits.put("file_bytes", "9223372036854775807"); limits.put("sub_institutions", 3);
        var quota = Map.of("tenant_id", id, "limits", limits, "expected_revision", initial.path("revision").asLong());
        var winner = data(call("PUT", BASE + "catalog/tenant-quota", token, quota));
        assertEquals("9223372036854775807", winner.path("limits").path("file_bytes").asText());
        assertEquals(3, winner.path("limits").path("sub_institutions").asLong());
        assertEquals(3, winner.path("overrides").path("sub_institutions").asLong());
        error(call("PUT", BASE + "catalog/tenant-quota", token, quota), 409, "catalog_revision_conflict");
        var invalid = new LinkedHashMap<String, Object>(limits); invalid.put("file_bytes", "9223372036854775808");
        error(call("PUT", BASE + "catalog/tenant-quota", token, Map.of("tenant_id", id, "limits", invalid, "expected_revision", winner.path("revision").asLong())), 400, "catalog_invalid");
        assertEquals(winner, entitlements(id));
    }

    /** 三版模型发布冻结旧值，draft CAS、价格精度和所有公开查询的密钥屏蔽。 */
    @Test
    void modelVersionsFreezeCredentialsAndPricesWhileDraftConflictsHaveNoEffects() throws Exception {
        var created = data(call("POST", "/api/v1/admin/models", token, embedding(prefix, 0, true)));
        String id = created.path("id").asText();
        data(call("POST", "/api/v1/admin/models/publish", token, Map.of("id", id, "expected_revision", 1)));
        for (long revision = 1; revision <= 2; revision++) {
            var command = embedding(prefix + "_v" + revision, revision, false);
            var winner = data(call("PUT", "/api/v1/admin/models/update", token, Map.of("id", id, "payload", command)));
            assertEquals(revision + 1, winner.path("revision").asLong());
            assertEquals("9007199254740993", winner.path("price_per_quantity_cents").asText());
            var before = rows("nw_platform_models", "id", id);
            error(call("PUT", "/api/v1/admin/models/update", token, Map.of("id", id, "payload", command)), 409, "CONFLICT");
            assertEquals(before, rows("nw_platform_models", "id", id));
            data(call("POST", "/api/v1/admin/models/publish", token, Map.of("id", id, "expected_revision", revision + 1)));
        }
        var history = data(call("GET", "/api/v1/admin/models/history?id=" + id, token, null));
        assertEquals(3, history.size()); assertEquals(prefix, history.get(2).path("name").asText());
        assertEquals(1, history.get(2).path("revision").asLong());
        for (String path : List.of("/api/v1/admin/models", "/api/v1/admin/models/detail?id=" + id, "/api/v1/admin/models/history?id=" + id)) {
            String visible = data(call("GET", path, token, null)).toString();
            assertFalse(visible.contains("synthetic-model-key")); assertFalse(visible.contains("api_key_encrypted"));
        }
        var before = rows("nw_platform_model_versions", "model_id", id);
        var invalid = embedding("illegal-kind", 3, false); invalid.put("kind", "RERANK");
        error(call("PUT", "/api/v1/admin/models/update", token, Map.of("id", id, "payload", invalid)), 400, "VALIDATION_ERROR");
        assertEquals(before, rows("nw_platform_model_versions", "model_id", id));
    }

    /** 15项预算完整快照连续修订和回滚，错误预算/陈旧CAS不新增配置历史。 */
    @Test
    void operationalBudgetVersionsRollbackExactlyAndRejectIncoherentInput() throws Exception {
        var initial = group(data(call("GET", "/api/v1/admin/system-settings", token, null)), "task_budget");
        var values = json.convertValue(initial.path("values"), new TypeReference<LinkedHashMap<String, Object>>() { });
        assertEquals(15, values.size());
        long initialRevision = initial.path("revision").asLong();
        for (int step = 0; step < 3; step++) {
            values.put("standard_root_total_tokens", 120_000_000L + step);
            var command = Map.of("group", "task_budget", "payload", Map.of("expected_revision", initialRevision + step, "values", values));
            data(call("PUT", "/api/v1/admin/system-settings/update", token, command));
            var history = data(call("GET", "/api/v1/admin/system-settings/history?group=task_budget", token, null));
            error(call("PUT", "/api/v1/admin/system-settings/update", token, command), 409, "CONFLICT");
            assertEquals(history, data(call("GET", "/api/v1/admin/system-settings/history?group=task_budget", token, null)));
        }
        var current = group(data(call("GET", "/api/v1/admin/system-settings", token, null)), "task_budget");
        var history = data(call("GET", "/api/v1/admin/system-settings/history?group=task_budget", token, null));
        values.put("standard_root_input_tokens", 1);
        error(call("PUT", "/api/v1/admin/system-settings/update", token, Map.of("group", "task_budget", "payload",
                Map.of("expected_revision", current.path("revision").asLong(), "values", values))), 422, "VALIDATION_ERROR");
        assertEquals(history, data(call("GET", "/api/v1/admin/system-settings/history?group=task_budget", token, null)));
        var restored = data(call("POST", "/api/v1/admin/system-settings/rollback", token, Map.of("group", "task_budget", "payload",
                Map.of("expected_revision", current.path("revision").asLong(), "target_revision", initialRevision))));
        assertEquals(initial.path("values"), restored.path("values"));
        assertEquals("context-policy.v2", data(call("GET", "/api/v1/admin/system-settings/context-policy", token, null)).path("version").asText());
        if ("1".equals(System.getenv("SETTINGS_HISTORY_BROWSER"))) {
            var browserValues = json.convertValue(restored.path("values"), new TypeReference<LinkedHashMap<String, Object>>() { });
            browserValues.put("standard_root_total_tokens", 120_000_010L);
            var browserCurrent = data(call("PUT", "/api/v1/admin/system-settings/update", token,
                    Map.of("group", "task_budget", "payload", Map.of("expected_revision", restored.path("revision").asLong(), "values", browserValues))));
            var browserTenant = createTenant(prefix + "_browser");
            verifySettingsHistoryBrowser(initialRevision, browserCurrent.path("revision").asLong(), initial.path("values").path("standard_root_total_tokens").asText(), browserTenant.path("id").asText());
            assertEquals(initial.path("values"), group(data(call("GET", "/api/v1/admin/system-settings", token, null)), "task_budget").path("values"));
            var tenantAfter = data(call("GET", "/api/v1/admin/tenants/detail?tenantId=" + browserTenant.path("id").asText(), token, null));
            assertEquals("suspended", tenantAfter.path("status").asText());
            assertEquals(browserTenant.path("name"), tenantAfter.path("name"));
        }
    }

    private void verifySettingsHistoryBrowser(long targetRevision, long currentRevision, String targetTotal, String tenantId) throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("frontend-platform/package.json"))) root = root.getParent();
        assertNotNull(root);
        var frontend = root.resolve("frontend-platform");
        var evidence = java.nio.file.Files.createTempDirectory(java.nio.file.Files.createDirectories(root.resolve(".agent-work-state")), "settings-history-browser-");
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
                var match = java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/").matcher(java.nio.file.Files.readString(viteLog));
                if (match.find()) { origin = "http://127.0.0.1:" + match.group(1); break; }
                Thread.sleep(100);
            }
            assertNotNull(origin, "Vite 未就绪：" + viteLog);
            var browser = new ProcessBuilder("node", frontend.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "--config=playwright.settings-history-real.config.ts");
            browser.directory(frontend.toFile()); browser.redirectErrorStream(true); browser.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = browser.environment(); env.put("E2E_BASE_URL", origin); env.put("E2E_ARTIFACT_DIR", evidence.toString());
            env.put("E2E_USERNAME", USER); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_TARGET_REVISION", Long.toString(targetRevision));
            env.put("E2E_CURRENT_REVISION", Long.toString(currentRevision)); env.put("E2E_TARGET_TOTAL", targetTotal);
            env.put("E2E_TENANT_ID", tenantId);
            Process process = browser.start(); owned.add(process.toHandle());
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                process.descendants().forEach(owned::add); vite.descendants().forEach(owned::add);
                process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            process.descendants().forEach(owned::add);
            assertFalse(process.isAlive(), "浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), "浏览器失败：" + evidence);
        } finally {
            vite.descendants().forEach(owned::add);
            for (var handle : owned) if (handle.isAlive()) handle.destroy();
            for (var handle : owned) if (handle.isAlive()) handle.destroyForcibly();
            for (var handle : owned) if (handle.isAlive()) handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** 业务编码均唯一，管理员用户名跨机构相同以暴露作用域遗漏。 */
    private Map<String, Object> tenantCommand(String slug) { return Map.of("tenant_name", "复杂测试机构", "tenant_slug", slug, "admin_username", "same_admin", "admin_password", PASSWORD); }
    private JsonNode createTenant(String slug) throws Exception { return data(call("POST", "/api/v1/admin/tenants", token, tenantCommand(slug))); }
    private JsonNode entitlements(String id) throws Exception { return data(call("GET", BASE + "catalog/tenant-entitlements?tenant_id=" + id, token, null)); }

    /** 全量模型命令留空密钥用于验证同服务更新保留密文。 */
    private LinkedHashMap<String, Object> embedding(String name, long revision, boolean key) {
        var command = new LinkedHashMap<String, Object>();
        command.put("name", name); command.put("kind", "EMBEDDING"); command.put("enabled", true); command.put("provider_id", "openai");
        command.put("authentication_scheme", "bearer-api-key"); command.put("endpoint", "https://embedding.example/v1");
        command.put("model_id", "complex-embedding"); command.put("timeout_seconds", 30); command.put("dimensions", 1536); command.put("currency", "CNY");
        command.put("price_per_quantity_cents", "9007199254740993");
        if (key) command.put("api_key", "synthetic-model-key");
        if (revision > 0) command.put("expected_revision", revision);
        return command;
    }

    /** 菜单只保存受控字段，null父节点表示明确根。 */
    private Map<String, Object> navigation(String code, String parent, long revision) {
        var command = new LinkedHashMap<String, Object>(); command.put("code", code); command.put("parent_code", parent);
        command.put("title", "复杂菜单"); command.put("sort_order", 0); command.put("enabled", true); command.put("expected_revision", revision); return command;
    }

    /** 真正平台密码登录，敏感响应不输出到断言日志。 */
    private String login(String username) throws Exception { return data(call("POST", "/api/v1/platform/auth/login", null, Map.of("username", username, "password", PASSWORD))).path("access_token").asText(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    /** 发送正式平台凭据头和统一snake_case JSON。 */
    private HttpResponse<byte[]> call(String method, String path, String credential, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20));
        if (credential != null) builder.header("X-Platform-Token", "Bearer " + credential);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 成功必须有统一信封；不暴露认证、模型凭据材料。 */
    private JsonNode data(HttpResponse<byte[]> response) throws Exception {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP status=" + response.statusCode());
        var envelope = json.readTree(response.body()); assertEquals("OK", envelope.path("code").asText()); return envelope.path("data");
    }

    /** 精确领域错误和null数据，失败不能伪装成功或部分写入。 */
    private void error(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode()); var envelope = json.readTree(response.body());
        assertEquals(code, envelope.path("code").asText()); assertTrue(envelope.has("data")); assertTrue(envelope.path("data").isNull());
    }

    /** SQL表列是固定测试字面量，读取完整行用于拒绝后的不变性断言。 */
    private List<String> rows(String table, String column, String value) { return jdbc.queryForList("SELECT row_to_json(r)::text FROM " + table + " r WHERE " + column + "::text=? ORDER BY row_to_json(r)::text", String.class, value); }
    private long count(String query, Object... args) { return jdbc.queryForObject(query, Long.class, args); }
    private JsonNode group(JsonNode groups, String key) { for (JsonNode item : groups) if (key.equals(item.path("key").asText())) return item; throw new AssertionError("配置组不存在: " + key); }
}
