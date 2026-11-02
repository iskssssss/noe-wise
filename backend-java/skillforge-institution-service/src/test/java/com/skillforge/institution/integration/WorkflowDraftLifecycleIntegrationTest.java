package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.WorkflowBuilderSnapshotService;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.core.workflow.WorkflowInstallationDefinitionService;
import com.skillforge.core.workflow.dto.response.WorkflowBuilderSnapshotResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftDiffResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import com.skillforge.core.workflow.dto.response.WorkflowInstallResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationHistoryResponse;
import com.skillforge.core.workflow.dto.request.WorkflowInstallByWorkflowIdRequest;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowTemplateMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 真实登录与 Builder HTTP 草稿、发布、安装路径，配合 PostgreSQL 不可变修订断言。 */
@org.springframework.context.annotation.Import(KnowledgeEmbeddingFixture.Configuration.class)
class WorkflowDraftLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WorkflowDraftLifecycleService lifecycle;
    @Autowired
    private WorkflowBuilderSnapshotService snapshots;
    @Autowired
    private com.skillforge.core.workflow.WorkflowDraftPublicationService draftPublication;
    @Autowired
    private WorkflowInstallationDefinitionService installationDefinitions;
    @Autowired
    private WorkflowInstanceMapper instances;

    @Autowired
    private com.skillforge.core.workflow.WorkflowInstanceService instanceViews;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private PlanMapper planMapper;
    @Autowired
    private WorkflowDefinitionVersionMapper versions;
    @Autowired private com.skillforge.core.workflow.mapper.WorkflowContainerMapper containers;
    @Autowired
    private WorkflowInstallationMapper installations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformModelCatalogService models;
    @Autowired
    private KnowledgeService knowledge;
    @Autowired
    private KnowledgeBaseRevisionService knowledgeRevisions;
    @Autowired
    private TenantMemberMapper members;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private ObjectMapper json;
    @LocalServerPort
    private int port;

    @Autowired private com.skillforge.core.workflow.WorkflowAssetService assets;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private com.skillforge.institution.runtime.AgentRuntimeWorkflowBindingPublisher workflowBindingPublisher;
    @Autowired private WorkflowTemplateMapper templates;

    private static final String PASSWORD = "WorkflowBuilderE2e123";
    private static final String BUILDER = "/api/v1/builder/workflows";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String accessToken;
    private String tenantId;
    private String tenantSlug;
    private String knowledgeCapabilityKey = "fixture:knowledge";
    private KnowledgeEmbeddingFixture embeddingFixture;

    @BeforeEach
    void setUp() throws Exception {
        String slug = "draft-" + SnowflakeIds.nextId();
        tenantSlug = slug;
        tenantId = createTenant(tenantMapper, planMapper, slug);
        TenantContext.setTenantId(tenantId);
        var admin = new TenantMemberDO();
        admin.setId(SnowflakeIds.nextId()); admin.setTenantId(Long.parseLong(tenantId));
        admin.setUsername("builder-admin"); admin.setRole("admin"); admin.setStatus("active");
        admin.setPermissions(List.of()); admin.setPasswordHash(passwords.encode(PASSWORD));
        admin.setCreatedAt(Instant.now()); members.insert(admin);
        initializeTenantAdministrator(tenantId, admin.getId());
        var login = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", admin.getUsername(), "password", PASSWORD), null);
        assertEquals(200, login.statusCode(), login.body());
        accessToken = json.readTree(login.body()).path("data").path("access_token").asText();
        assertTrue(!accessToken.isBlank(), login.body());
    }

    @AfterEach
    void tearDown() {
        if (embeddingFixture != null) embeddingFixture.close();
        TenantContext.clear();
    }

    @Test
    void validatesCandidateContentWithoutCreatingOrChangingDraft() throws Exception {
        var candidate = definition("候选内容");
        var newValidation = send("POST", BUILDER + "/validate-content", Map.of("definition", candidate), null);
        assertEquals(200, newValidation.statusCode(), newValidation.body());
        assertEquals(0, json.readTree(newValidation.body()).path("data").path("revision").asLong());
        assertEquals(0, lifecycle.list(Long.parseLong(tenantId)).total());

        var created = createThroughHttp("candidate-flow", "原始内容");
        var beforeSnapshot = snapshots.getCurrent(Long.parseLong(tenantId), "candidate-flow");
        var command = Map.of("workflow_id", "candidate-flow", "definition", candidate);
        var validation = send("POST", BUILDER + "/draft/validate-content", command, created.revision());
        assertEquals(200, validation.statusCode(), validation.body());
        assertEquals(created.revision(), json.readTree(validation.body()).path("data").path("revision").asLong());
        var stale = send("POST", BUILDER + "/draft/validate-content", command, created.revision() + 1);
        assertEquals(409, stale.statusCode(), stale.body());
        var after = builder("GET", "/draft?workflowId=candidate-flow", null, null, WorkflowDraftResponse.class);
        assertEquals(created.revision(), after.revision());
        assertEquals(created.definition(), after.definition());
        assertEquals(beforeSnapshot, snapshots.getCurrent(Long.parseLong(tenantId), "candidate-flow"));
    }

    @Test
    void combinedPublicationHttpKeepsOriginalReceiptAndRejectsChangedCommand() throws Exception {
        long tenant = Long.parseLong(tenantId);
        var created = createThroughHttp("receipt-flow", "原始内容");
        String requestId = java.util.UUID.randomUUID().toString();
        var command = Map.of("request_id", requestId, "workflow_id", "receipt-flow", "revision", created.revision(),
                "definition", definition("组合发布"), "layout", Map.of("start", Map.of("x", 12)),
                "viewport", Map.of("zoom", 1));
        var first = send("POST", BUILDER + "/save-and-publish", command, null);
        assertEquals(200, first.statusCode(), first.body());
        var receipt = json.readTree(first.body()).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        var replay = send("POST", BUILDER + "/save-and-publish", command, null);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(receipt, json.readTree(replay.body()).path("data"));
        var read = send("GET", BUILDER + "/save-and-publish/result?workflow_id=receipt-flow&request_id=" + requestId, null, null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(receipt, json.readTree(read.body()).path("data"));
        var changed = new java.util.HashMap<>(command);
        changed.put("definition", definition("不同内容"));
        assertEquals(409, send("POST", BUILDER + "/save-and-publish", changed, null).statusCode());
        assertEquals(1, lifecycle.history(tenant, "receipt-flow").items().size());

        String rejectedId = java.util.UUID.randomUUID().toString();
        var bad = Map.of("request_id", rejectedId, "workflow_id", "receipt-flow", "revision", created.revision() + 1,
                "definition", Map.of(), "layout", Map.of(), "viewport", Map.of());
        var rejected = send("POST", BUILDER + "/save-and-publish", bad, null);
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertEquals("REJECTED", json.readTree(rejected.body()).path("data").path("status").asText());
        assertTrue(json.readTree(rejected.body()).path("data").path("error_detail").path("problems").get(0).has("object_ref"), rejected.body());
        assertEquals(created.revision() + 1, lifecycle.getCurrentDraft(tenant, "receipt-flow").revision());
        assertEquals(1, lifecycle.history(tenant, "receipt-flow").items().size());

        String admin = accessToken;
        String access = "/api/v1/tenant/foundation/access";
        var roleResponse = send("POST", access + "/role", Map.of("code", "workflow_read_" + SnowflakeIds.nextId(),
                "name", "工作流只读", "data_scope", "all", "permissions",
                List.of("foundation.workflows.read", "foundation.resource.workflow.read"), "department_ids", List.of()), null);
        assertEquals(200, roleResponse.statusCode(), roleResponse.body());
        String username = "workflow-reader-" + SnowflakeIds.nextId();
        var member = send("POST", access + "/member", Map.of("username", username, "display_name", username,
                "password", PASSWORD, "role_ids", List.of(json.readTree(roleResponse.body()).path("data").path("id").asText())), null);
        assertEquals(200, member.statusCode(), member.body());
        var login = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug,
                "username", username, "password", PASSWORD), null);
        assertEquals(200, login.statusCode(), login.body());
        accessToken = json.readTree(login.body()).path("data").path("access_token").asText();
        assertEquals(204, send("GET", BUILDER + "/save-and-publish/result?workflow_id=receipt-flow&request_id=" + requestId, null, null).statusCode());
        var denied = send("POST", BUILDER + "/save-and-publish", command, null);
        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals("WORKFLOW_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(denied.body()).path("code").asText());
        String otherSlug = "workflow-publish-other-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(tenantMapper, planMapper, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try { createTestTenantAdministrator(otherTenant, "workflow-other-admin", PASSWORD); }
        finally { TenantContext.setTenantId(tenantId); }
        var otherLogin = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", otherSlug,
                "username", "workflow-other-admin", "password", PASSWORD), null);
        assertEquals(200, otherLogin.statusCode(), otherLogin.body());
        accessToken = json.readTree(otherLogin.body()).path("data").path("access_token").asText();
        createThroughHttp("receipt-flow", "其他机构同标识");
        assertEquals(204, send("GET", BUILDER + "/save-and-publish/result?workflow_id=receipt-flow&request_id=" + requestId, null, null).statusCode());
        accessToken = admin;
        assertEquals(receipt, json.readTree(send("GET", BUILDER + "/save-and-publish/result?workflow_id=receipt-flow&request_id=" + requestId, null, null).body()).path("data"));
    }

    @Test
    void combinedPublicationRollsBackInvalidDraftAndLayoutAndPublishesOneRevision() throws Exception {
        long tenant = Long.parseLong(tenantId);
        var created = createThroughHttp("combined-flow", "原始内容");
        var before = snapshots.getCurrent(tenant, "combined-flow");
        var layout = Map.<String, Object>of("start", Map.of("x", 120, "y", 60));
        var viewport = Map.<String, Object>of("zoom", 1.2);

        assertThrows(ApiException.class, () -> draftPublication.saveAndPublish(
                tenant, "combined-flow", created.revision(), Map.of(), layout, viewport));
        assertEquals(created.revision(), lifecycle.getCurrentDraft(tenant, "combined-flow").revision());
        assertEquals(created.definition(), lifecycle.getCurrentDraft(tenant, "combined-flow").definition());
        assertEquals(before, snapshots.getCurrent(tenant, "combined-flow"));
        assertEquals(0, lifecycle.history(tenant, "combined-flow").items().size());

        var published = draftPublication.saveAndPublish(
                tenant, "combined-flow", created.revision(), definition("合并发布"), layout, viewport);
        assertEquals(created.revision() + 1, published.draftRevision());
        assertEquals(layout, snapshots.getCurrent(tenant, "combined-flow").layout());
        assertEquals(viewport, snapshots.getCurrent(tenant, "combined-flow").viewport());
        assertEquals(1, lifecycle.history(tenant, "combined-flow").items().size());
        assertThrows(ApiException.class, () -> draftPublication.saveAndPublish(
                tenant, "combined-flow", created.revision(), definition("旧修订"), layout, viewport));
        assertEquals(published.definitionVersionId(), lifecycle.history(tenant, "combined-flow").currentDefinitionVersionId());
    }

    @Test
    void savesWithRevisionCasAndPublishesAnImmutableVersion() throws Exception {
        long tenant = Long.parseLong(tenantId);
        WorkflowDraftResponse created = createThroughHttp("draft-flow", "知识检索");
        WorkflowDraftResponse saved = saveThroughHttp("draft-flow", created.revision(), "更新后的知识检索");
        WorkflowBuilderSnapshotResponse savedSnapshot = builder("PUT", "/draft/snapshot",
                Map.of("workflow_id", "draft-flow", "payload", Map.of(
                        "layout", Map.of("nodes", List.of(Map.of("id", "knowledge", "x", 200, "y", 80))),
                        "viewport", Map.of("zoom", 1.2, "panX", 30, "panY", 10))),
                saved.revision(), WorkflowBuilderSnapshotResponse.class);
        assertEquals(saved.revision(), savedSnapshot.draftRevision());

        var conflict = send("PUT", BUILDER + "/draft", saveCommand("draft-flow", "不能覆盖"), created.revision());
        assertEquals(409, conflict.statusCode(), conflict.body());
        var stalePublish = send("POST", BUILDER + "/publish", Map.of("workflow_id", "draft-flow"), created.revision());
        assertEquals(409, stalePublish.statusCode(), stalePublish.body());
        var current = builder("GET", "/draft?workflowId=draft-flow", null, null, WorkflowDraftResponse.class);
        assertEquals(saved.revision(), current.revision());
        assertEquals(saved.definition(), current.definition());

        WorkflowPublishResponse published = publishThroughHttp("draft-flow", saved.revision());
        WorkflowDefinitionVersionDO persisted = versions.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .eq(WorkflowDefinitionVersionDO::getId, published.definitionVersionId()));

        assertEquals(1L, published.definitionVersion());
        assertEquals(saved.revision(), published.draftRevision());
        assertTrue(published.capabilityVersionId() > 0);
        assertEquals(published.capabilityVersionId(), jdbc.queryForObject(
                "SELECT capability_version_id FROM nw_workflow_definition_versions WHERE id = ?",
                Long.class, published.definitionVersionId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM nw_capability_versions cv "
                        + "WHERE cv.id = ? AND cv.capability_type = 'WORKFLOW' "
                        + "AND cv.status = 'PUBLISHED' AND cv.version_key = 'definition:1'",
                Integer.class, published.capabilityVersionId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE nw_workflow_definition_versions SET capability_version_id = ? WHERE id = ?",
                SnowflakeIds.nextId(), published.definitionVersionId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE nw_workflow_definition_versions SET definition = CAST(? AS jsonb) WHERE id = ?",
                "{\"tampered\":true}", published.definitionVersionId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM nw_workflow_definition_versions WHERE id = ?", published.definitionVersionId()));
        WorkflowPublishResponse replay = publishThroughHttp("draft-flow", saved.revision());
        assertEquals(published.definitionVersionId(), replay.definitionVersionId());
        assertEquals(published.capabilityVersionId(), replay.capabilityVersionId());
        assertEquals("更新后的知识检索", ((List<Map<String, Object>>) persisted.getDefinition().get("nodes"))
                .get(1).get("name"));
        // 布局和视口独立保存，发布定义中不出现它们。
        assertTrue(!persisted.getDefinition().containsKey("viewport"));
        assertEquals(savedSnapshot, snapshots.getCurrent(tenant, "draft-flow"));
        WorkflowDraftResponse forked = saveThroughHttp("draft-flow", saved.revision(), "新工作草稿");
        assertEquals("draft", forked.status());
        assertEquals(saved.revision() + 1, forked.revision());
        assertEquals("新工作草稿", ((List<Map<String, Object>>) forked.definition().get("nodes"))
                .get(1).get("name"));
        WorkflowBuilderSnapshotResponse forkedSnapshot = snapshots.getCurrent(tenant, "draft-flow");
        assertEquals(forked.revision(), forkedSnapshot.draftRevision());
        assertEquals(savedSnapshot.layout(), forkedSnapshot.layout());
        WorkflowDraftResponse edited = saveThroughHttp("draft-flow", forked.revision(), "新草稿变更");
        assertEquals("新草稿变更", ((List<Map<String, Object>>) edited.definition().get("nodes")).get(1).get("name"));
        // 原发布对象的定义不因新草稿变更而变化。
        assertEquals("更新后的知识检索", ((List<Map<String, Object>>) persisted.getDefinition().get("nodes"))
                .get(1).get("name"));
        WorkflowDraftDiffResponse diff = lifecycle.diff(tenant, "draft-flow");
        assertEquals(1L, diff.baseDefinitionVersion());
        assertTrue(diff.changes().stream().anyMatch(change -> change.operation().equals("replace")
                && change.path().equals("/nodes/1/name")
                && change.beforeValue().equals("更新后的知识检索")
                && change.afterValue().equals("新草稿变更")));
        assertTrue(lifecycle.getCurrentDraft(tenant, "draft-flow").problems().isEmpty());
        WorkflowDraftListResponse listed = lifecycle.list(tenant);
        assertEquals(1, listed.total());
        assertEquals("draft-flow", listed.items().getFirst().workflowId());
        assertEquals(edited.revision(), listed.items().getFirst().revision());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_workflow_draft_revisions WHERE tenant_id = ?",
                Integer.class, tenant));
        var secondPublication = publishThroughHttp("draft-flow", edited.revision());
        assertEquals(2L, secondPublication.definitionVersion());
        assertEquals(2, lifecycle.history(tenant, "draft-flow").items().size());
        assertEquals(secondPublication.definitionVersionId(), lifecycle.history(tenant, "draft-flow").currentDefinitionVersionId());
        var history = builder("GET", "/history?workflowId=draft-flow", null, null,
                WorkflowPublicationHistoryResponse.class);
        assertEquals(2, history.items().size());
        var restored = builder("POST", "/draft/restore", Map.of("workflow_id", "draft-flow",
                "definition_version_id", published.definitionVersionId()), edited.revision(), WorkflowDraftResponse.class);
        assertEquals("draft", restored.status());
        assertEquals(secondPublication.definitionVersionId(), restored.activeDefinitionVersionId());
        assertEquals("更新后的知识检索", ((List<Map<String, Object>>) restored.definition().get("nodes")).get(1).get("name"));
        assertEquals(2, lifecycle.history(tenant, "draft-flow").items().size());
    }

    @Test
    void publicationSummaryDoesNotHydrateGraphsAndHttpDetailReadsExactAuthorizedVersion() throws Exception {
        long tenant = Long.parseLong(tenantId);
        var created = createThroughHttp("publication-history", "旧图");
        var saved = lifecycle.save(tenant, "publication-history", created.revision(), definition("旧图" + "x".repeat(100_000)));
        var first = publishThroughHttp("publication-history", saved.revision());
        var edited = lifecycle.save(tenant, "publication-history", saved.revision(), definition("新图"));
        var second = publishThroughHttp("publication-history", edited.revision());
        var observedVersions = org.mockito.Mockito.mock(WorkflowDefinitionVersionMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(versions));
        org.mockito.Mockito.doAnswer(invocation -> {
            List<WorkflowDefinitionVersionDO> rows = versions.selectList(invocation.getArgument(0));
            assertEquals(2, rows.size());
            assertTrue(rows.stream().allMatch(row -> row.getDefinition() == null), "数据库摘要不能把图加载到Java再丢弃");
            return rows;
        }).when(observedVersions).selectList(org.mockito.ArgumentMatchers.any());
        var observed = new WorkflowDraftLifecycleService(containers,
                org.mockito.Mockito.mock(com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper.class), observedVersions,
                new com.skillforge.core.workflow.WorkflowDraftValidator(),
                org.mockito.Mockito.mock(com.skillforge.core.capability.CapabilityProducerPublicationBridge.class));
        assertEquals(2, observed.history(tenant, "publication-history").items().size());
        var response = send("GET", BUILDER + "/history?workflowId=publication-history", null, null);
        assertEquals(200, response.statusCode());
        var items = json.readTree(response.body()).path("data").path("items");
        assertEquals(2, items.size());
        items.forEach(item -> assertTrue(!item.has("definition")));
        var detail = builder("GET", "/history/detail?workflowId=publication-history&definitionVersionId=" + first.definitionVersionId(),
                null, null, com.skillforge.core.workflow.dto.response.WorkflowPublicationDetailResponse.class);
        assertEquals(first.definitionVersionId(), detail.definitionVersionId());
        assertEquals(false, detail.current());
        assertEquals(saved.definition(), detail.definition());
        assertEquals(second.definitionVersionId(), lifecycle.history(tenant, "publication-history").currentDefinitionVersionId());
        createThroughHttp("other-publication-history", "其他容器");
        assertEquals(404, send("GET", BUILDER + "/history/detail?workflowId=other-publication-history&definitionVersionId="
                + first.definitionVersionId(), null, null).statusCode());
        assertEquals(404, send("GET", BUILDER + "/history/detail?workflowId=publication-history&definitionVersionId="
                + SnowflakeIds.nextId(), null, null).statusCode());
    }

    @Test
    void publishedWorkflowVersionsAreIsolatedAcrossTenants() {
        long tenantA = Long.parseLong(tenantId);
        long tenantB = Long.parseLong(createTenant(tenantMapper, planMapper,
                "draft-isolation-b-" + SnowflakeIds.nextId()));

        TenantContext.setTenantId(tenantId);
        WorkflowDraftResponse created = lifecycle.create(tenantA, "isolated-flow", definition("A"));
        WorkflowPublishResponse published = lifecycle.publish(tenantA, "isolated-flow", created.revision());

        TenantContext.setTenantId(Long.toString(tenantB));
        assertEquals(0, lifecycle.list(tenantB).total());
        assertThrows(ApiException.class, () -> lifecycle.getCurrentDraft(tenantB, "isolated-flow"));
        assertThrows(ApiException.class,
                () -> installationDefinitions.requirePublishedDefinitionVersion(tenantB, "isolated-flow"));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> lifecycle.publicationDetail(tenantB, "isolated-flow", published.definitionVersionId())).getStatus());
        var sameName = lifecycle.create(tenantB, "isolated-flow", definition("B"));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> lifecycle.publicationDetail(tenantB, sameName.workflowId(), published.definitionVersionId())).getStatus());

        TenantContext.setTenantId(tenantId);
        assertEquals(1, lifecycle.list(tenantA).total());
        assertEquals(published.definitionVersionId(), installationDefinitions
                .requirePublishedDefinitionVersion(tenantA, "isolated-flow"));
    }

    @Test
    void installsThenExplicitlyUpgradesTheInstanceToThePublishedImmutableVersion() throws Exception {
        long tenant = Long.parseLong(tenantId);
        embeddingFixture = new KnowledgeEmbeddingFixture(models, tenantId);
        var base = knowledge.createBase(tenantId, "安装绑定知识库", null);
        var document = knowledge.ingestProjectionDocument(tenantId, base.id(), "政策", "工作流安装测试政策正文");
        var draft = knowledgeRevisions.create(tenantId, base.id());
        knowledgeRevisions.markReady(tenantId, base.id(), draft.revision(), List.of(document.sourceVersion()),
                new KnowledgeCapabilityPolicyRequest(true, false, Set.of("VECTOR"), "PINNED", true));
        var source = knowledgeRevisions.publish(tenantId, base.id(), draft.revision());
        knowledgeCapabilityKey = "knowledge-base:" + base.id();
        var knowledgeCapabilityId = jdbc.queryForObject("SELECT definition_id FROM nw_capability_versions WHERE id = ?",
                Long.class, source.capabilityVersionId());
        var bindings = new WorkflowInstallationBindings(List.of(new WorkflowInstallationBindings.CapabilityBinding(
                "knowledge", new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, knowledgeCapabilityId.toString(),
                source.capabilityVersionId().toString()), null, null, source.revision())), List.of(), List.of());
        WorkflowDraftResponse created = createThroughHttp("installed-flow", "v1");
        WorkflowPublishResponse firstPublished = publishThroughHttp("installed-flow", created.revision());

        WorkflowInstallResponse firstInstall = installThroughHttp("installed-flow", "zh-CN", "prompt-v1", bindings);
        assertEquals("installed", firstInstall.status());
        assertEquals(tenantId, firstInstall.tenantId());
        assertEquals("installed-flow_" + tenant, firstInstall.instanceId());
        assertEquals(1L, firstInstall.installationRevision());
        WorkflowInstanceDO installed = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                .eq(WorkflowInstanceDO::getInstanceId, "installed-flow_" + tenant));
        assertEquals(firstPublished.definitionVersionId(), installed.getDefinitionVersionId());
        assertEquals(1L, installed.getInstallationRevision());
        var boundV1 = installationDefinitions.definition(tenant, installed.getDefinitionVersionId());
        assertEquals("v1", ((List<Map<String, Object>>) boundV1.get("nodes")).get(1).get("name"));

        WorkflowDraftResponse forked = builder("POST", "/draft/fork", Map.of("workflow_id", "installed-flow"),
                created.revision(), WorkflowDraftResponse.class);
        WorkflowDraftResponse changed = saveThroughHttp("installed-flow", forked.revision(), "v2");
        WorkflowPublishResponse secondPublished = publishThroughHttp("installed-flow", changed.revision());
        var nextExecution = installations.currentTarget(tenant, installed.getId());
        assertEquals(secondPublished.definitionVersionId(), nextExecution.definitionId());
        assertEquals(secondPublished.capabilityVersionId(), nextExecution.capabilityVersionId());
        assertEquals(1L, nextExecution.installationRevision());
        assertEquals(firstPublished.definitionVersionId(), installed.getDefinitionVersionId());
        assertEquals(secondPublished.definitionVersionId(), instanceViews.listBuilderInstances(tenant).stream().filter(item -> item.id().equals(installed.getId())).findFirst().orElseThrow().definitionVersionId());
        WorkflowInstallResponse secondInstall = installThroughHttp("installed-flow", "en-US", "prompt-v2", bindings);
        assertEquals("installed", secondInstall.status());
        assertEquals(firstInstall.instanceId(), secondInstall.instanceId());
        assertEquals(2L, secondInstall.installationRevision());

        WorkflowInstanceDO upgraded = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                .eq(WorkflowInstanceDO::getInstanceId, "installed-flow_" + tenant));
        assertEquals(secondPublished.definitionVersionId(), upgraded.getDefinitionVersionId());
        assertEquals(2L, upgraded.getInstallationRevision());
        assertEquals("zh-CN:prompt-v1", jdbc.queryForObject(
                "SELECT config->>'locale' || ':' || custom_prompt FROM nw_workflow_installation_revisions "
                        + "WHERE tenant_id = ? AND instance_id = ? AND installation_revision = 1",
                String.class, tenant, upgraded.getId()));
        assertEquals("en-US:prompt-v2", jdbc.queryForObject(
                "SELECT config->>'locale' || ':' || custom_prompt FROM nw_workflow_installation_revisions "
                        + "WHERE tenant_id = ? AND instance_id = ? AND installation_revision = 2",
                String.class, tenant, upgraded.getId()));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM nw_workflow_installation_revisions "
                        + "WHERE tenant_id = ? AND instance_id = ?", Integer.class, tenant, upgraded.getId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE nw_workflow_installation_revisions SET custom_prompt = 'tampered' "
                        + "WHERE tenant_id = ? AND instance_id = ? AND installation_revision = 1",
                tenant, upgraded.getId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM nw_workflow_installation_revisions "
                        + "WHERE tenant_id = ? AND instance_id = ? AND installation_revision = 1",
                tenant, upgraded.getId()));
        var boundV2 = installationDefinitions.definition(tenant, upgraded.getDefinitionVersionId());
        assertEquals("v2", ((List<Map<String, Object>>) boundV2.get("nodes")).get(1).get("name"));
        WorkflowDefinitionVersionDO original = versions.selectById(firstPublished.definitionVersionId());
        assertEquals("v1", ((List<Map<String, Object>>) original.getDefinition().get("nodes")).get(1).get("name"));
    }

    @Test
    void batchAssetUpsertPreservesIdentityCreationAndRollsBackAsOneDraftMutation() {
        // 资源写入以市场模板草稿行锁为边界；Builder 容器不创建此发行实体。
        var template = new WorkflowTemplateDO();
        template.setId(SnowflakeIds.nextId());
        template.setTenantId(Long.parseLong(tenantId));
        template.setWorkflowId("asset-batch");
        template.setName("资源批量写入");
        template.setVersion("1.0.0");
        template.setType("state-machine");
        template.setTags(List.of());
        template.setConfigSchema(List.of());
        template.setRequiredPermissions(List.of());
        template.setAuthorType("tenant");
        template.setPriceType("free");
        template.setPriceCents(0);
        template.setInstallCount(0);
        template.setStatus("draft");
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(template.getCreatedAt());
        templates.insert(template);
        assets.upsertAssets(tenantId, "asset-batch", Map.of("a.txt", new byte[]{1}, "b.txt", new byte[]{2}));
        var initial = assets.listByWorkflow("asset-batch").stream()
                .filter(row -> row.assetName().equals("a.txt")).findFirst().orElseThrow();
        assets.upsertAssets(tenantId, "asset-batch", Map.of("a.txt", new byte[]{3}, "c.txt", new byte[]{4}));
        var current = assets.listByWorkflow("asset-batch").stream()
                .filter(row -> row.assetName().equals("a.txt")).findFirst().orElseThrow();
        assertEquals(initial.id(), current.id());
        assertEquals(initial.createdAt(), current.createdAt());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{3}, current.content());
        assertEquals(1L, queryCount(jdbc, tenantId,
                "SELECT count(*) FROM nw_workflow_assets WHERE workflow_id=? AND asset_name=?", "asset-batch", "c.txt"));
        var before = queryRows(jdbc, tenantId, "SELECT updated_at FROM nw_workflow_templates WHERE workflow_id=?", "asset-batch");
        assertEquals(1, before.size(), "模板修订回滚断言必须基于实际草稿行");
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            assets.upsertAssets(tenantId, "asset-batch", Map.of("a.txt", new byte[]{9}, "rollback.txt", new byte[]{9}));
            throw new IllegalStateException("回滚整个资源批次");
        }));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{3}, assets.readAsset("asset-batch", "a.txt"));
        assertEquals(0L, queryCount(jdbc, tenantId,
                "SELECT count(*) FROM nw_workflow_assets WHERE workflow_id=? AND asset_name=?", "asset-batch", "rollback.txt"));
        assertEquals(before, queryRows(jdbc, tenantId, "SELECT updated_at FROM nw_workflow_templates WHERE workflow_id=?", "asset-batch"));
        assets.deleteByWorkflow("asset-batch");
        assertTrue(assets.listByWorkflow("asset-batch").isEmpty());
    }

    @Test
    void workflowOnboardingPersistsAndFreezesKnowledgeSlotFromHttp() throws Exception {
        long tenant = Long.parseLong(tenantId);
        embeddingFixture = new KnowledgeEmbeddingFixture(models, tenantId);
        var base = knowledge.createBase(tenantId, "接入知识库", null);
        var document = knowledge.ingestProjectionDocument(tenantId, base.id(), "政策", "接入绑定测试正文");
        var draft = knowledgeRevisions.create(tenantId, base.id());
        knowledgeRevisions.markReady(tenantId, base.id(), draft.revision(), List.of(document.sourceVersion()),
                new KnowledgeCapabilityPolicyRequest(true, false, Set.of("VECTOR"), "PINNED", true));
        var source = knowledgeRevisions.publish(tenantId, base.id(), draft.revision());
        long capabilityId = jdbc.queryForObject("SELECT definition_id FROM nw_capability_versions WHERE id = ?",
                Long.class, source.capabilityVersionId());
        knowledgeCapabilityKey = "knowledge-base:" + base.id();
        var created = createThroughHttp("onboard-knowledge", "知识槽工作流");
        var published = publishThroughHttp("onboard-knowledge", created.revision());
        var agentResponse = send("POST", "/api/v1/agents", Map.of("agent_key", "wf-knowledge-" + SnowflakeIds.nextId(),
                "name", "知识槽接入", "system_prompt", "test", "config", Map.of()), null);
        assertEquals(200, agentResponse.statusCode(), agentResponse.body());
        String agent = json.readTree(agentResponse.body()).path("data").path("id").asText();
        var command = Map.of("request_id", java.util.UUID.randomUUID().toString(), "agent_id", agent,
                "workflow_id", "onboard-knowledge", "expected_definition_version_id", Long.toString(published.definitionVersionId()),
                "mode", "INSTALL", "expected_installation_revision", 0, "config", Map.of("locale", "zh-CN"),
                "custom_prompt", "知识槽测试", "bindings", Map.of("capabilities", List.of(Map.of(
                        "slot", "knowledge", "capability", Map.of("type", "KNOWLEDGE_SOURCE",
                                "capability_id", Long.toString(capabilityId), "version_id", source.capabilityVersionId().toString()),
                        "knowledge_revision", source.revision())), "workflows", List.of(), "agents", List.of()));
        var response = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        assertEquals(200, response.statusCode(), response.body());
        var receipt = json.readTree(response.body()).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText(), response.body());
        long instanceId = Long.parseLong(receipt.path("instance_id").asText());
        var revision = installations.find(tenant, instanceId, receipt.path("installation_revision").asLong());
        var persisted = json.convertValue(revision.getBindings(), WorkflowInstallationBindings.class);
        assertEquals(1, persisted.capabilities().size());
        var slot = persisted.capabilities().getFirst();
        assertEquals("knowledge", slot.slot());
        assertEquals(Long.toString(capabilityId), slot.capability().capabilityId());
        assertEquals(source.capabilityVersionId().toString(), slot.capability().versionId());
        assertEquals(source.revision(), slot.knowledgeRevision());
        var current = installations.currentTarget(tenant, instanceId);
        var target = new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle("workflow:onboard-knowledge:" + instanceId),
                Long.toString(instanceId), current.definitionId(),
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.WORKFLOW,
                        Long.toString(current.capabilityId()), Long.toString(current.capabilityVersionId())),
                Duration.ofMillis(current.timeoutMs()), current.installationRevision());
        var frozen = workflowBindingPublisher.publish(new com.skillforge.runtime.api.TenantId(tenantId), target);
        var resolved = json.readValue(frozen.resolvedBindingsJson(), com.skillforge.core.workflow.runtime.WorkflowRuntimeBindings.class);
        assertEquals(1, resolved.knowledge().size());
        var frozenSlot = resolved.knowledge().getFirst();
        assertEquals("knowledge", frozenSlot.slot());
        assertEquals(Long.toString(capabilityId), frozenSlot.target().capability().capabilityId());
        assertEquals(source.capabilityVersionId().toString(), frozenSlot.target().capability().versionId());
        assertEquals(source.revision(), frozenSlot.target().descriptorRevision());
    }

    @Test
    void installationReceiptPreservesOriginalRevisionAndRollsBackDuplicateIdentity() throws Exception {
        var graph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "end", "type", "end")), "edges", List.of(Map.of("from", "start", "to", "end")),
                "meta", Map.of("capability", Map.of("model_invocable", true, "user_invocable", false,
                        "input_schema", Map.of(), "output_schema", Map.of(), "supports_hitl", false,
                        "export_fields", List.of(), "timeout_ms", 30000, "concurrency_safe", true)));
        var created = builder("POST", "", Map.of("workflow_id", "install-receipt-flow", "definition", graph), null, WorkflowDraftResponse.class);
        publishThroughHttp("install-receipt-flow", created.revision());
        String id = java.util.UUID.randomUUID().toString();
        String lookup = BUILDER + "/install-with-receipt/result?workflow_id=install-receipt-flow&request_id=" + id;
        assertEquals(204, send("GET", lookup, null, null).statusCode());
        var command = Map.of("request_id", id, "workflow_id", "install-receipt-flow", "payload", Map.of("config", Map.of()));
        var first = send("POST", BUILDER + "/install-with-receipt", command, null);
        assertEquals(200, first.statusCode(), first.body());
        var receipt = json.readTree(first.body()).path("data");
        assertEquals("1", receipt.path("installation_revision").asText());
        var again = send("POST", BUILDER + "/install-with-receipt", command, null);
        assertEquals(500, again.statusCode(), again.body());
        assertEquals(receipt, json.readTree(send("GET", lookup, null, null).body()).path("data"));
        String next = java.util.UUID.randomUUID().toString();
        var second = send("POST", BUILDER + "/install-with-receipt",
                Map.of("request_id", next, "workflow_id", "install-receipt-flow", "payload", Map.of("config", Map.of())), null);
        assertEquals(200, second.statusCode(), second.body());
        assertEquals("2", json.readTree(second.body()).path("data").path("installation_revision").asText());
        assertEquals(receipt, json.readTree(send("GET", lookup, null, null).body()).path("data"));
        String originalToken = accessToken;
        String access = "/api/v1/tenant/foundation/access";
        var role = send("POST", access + "/role", Map.of("code", "install_reader_" + SnowflakeIds.nextId(),
                "name", "安装回执读取", "data_scope", "all", "permissions", List.of("foundation.workflows.read", "foundation.resource.workflow.read"),
                "department_ids", List.of()), null);
        assertEquals(200, role.statusCode(), role.body());
        String roleId = json.readTree(role.body()).path("data").path("id").asText();
        var member = send("POST", access + "/member", Map.of("username", "other-install-reader", "display_name", "其他成员",
                "password", PASSWORD, "role_ids", List.of(roleId)), null);
        assertEquals(200, member.statusCode(), member.body());
        var otherLogin = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug,
                "username", "other-install-reader", "password", PASSWORD), null);
        assertEquals(200, otherLogin.statusCode(), otherLogin.body());
        accessToken = json.readTree(otherLogin.body()).path("data").path("access_token").asText();
        assertEquals(204, send("GET", lookup, null, null).statusCode());
        accessToken = originalToken;
        assertEquals(2, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_install_receipts WHERE tenant_id=?", Long.valueOf(tenantId)));
    }

    @Test
    void workflowOnboardingReceiptRequiresCurrentReadAccessAndOriginalActor() throws Exception {
        var graph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "end", "type", "end")), "edges", List.of(Map.of("from", "start", "to", "end")),
                "meta", Map.of("capability", Map.of("model_invocable", true, "user_invocable", false,
                        "input_schema", Map.of(), "output_schema", Map.of(), "supports_hitl", false,
                        "export_fields", List.of(), "timeout_ms", 30000, "concurrency_safe", true)));
        var created = builder("POST", "", Map.of("workflow_id", "receipt-flow", "definition", graph), null, WorkflowDraftResponse.class);
        var published = publishThroughHttp("receipt-flow", created.revision());
        var agentResponse = send("POST", "/api/v1/agents", Map.of("agent_key", "wf-receipt-" + SnowflakeIds.nextId(),
                "name", "回执测试", "system_prompt", "test", "config", Map.of()), null);
        assertEquals(200, agentResponse.statusCode(), agentResponse.body());
        String agent = json.readTree(agentResponse.body()).path("data").path("id").asText();
        String admin = accessToken;
        String access = "/api/v1/tenant/foundation/access";
        var readPermissions = List.of("foundation.agents.read", "foundation.workflows.read",
                "foundation.resource.agent.read", "foundation.resource.workflow.read");
        var writePermissions = new java.util.ArrayList<>(readPermissions);
        writePermissions.addAll(List.of("foundation.agents.create", "foundation.workflows.create",
                "foundation.resource.agent.manage", "foundation.resource.workflow.manage", "foundation.resource.workflow.execute"));
        var roleResponse = send("POST", access + "/role", Map.of("code", "wf_receipt_" + SnowflakeIds.nextId(),
                "name", "回执读写", "data_scope", "all", "permissions", writePermissions, "department_ids", List.of()), null);
        assertEquals(200, roleResponse.statusCode(), roleResponse.body());
        var role = json.readTree(roleResponse.body()).path("data");
        String username = "receipt-actor-" + SnowflakeIds.nextId();
        var member = send("POST", access + "/member", Map.of("username", username, "display_name", username,
                "password", PASSWORD, "role_ids", List.of(role.path("id").asText())), null);
        assertEquals(200, member.statusCode(), member.body());
        var login = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug,
                "username", username, "password", PASSWORD), null);
        assertEquals(200, login.statusCode(), login.body());
        accessToken = json.readTree(login.body()).path("data").path("access_token").asText();
        String actor = accessToken;
        String requestId = java.util.UUID.randomUUID().toString();
        var command = Map.of("request_id", requestId, "agent_id", agent, "workflow_id", "receipt-flow",
                "expected_definition_version_id", Long.toString(published.definitionVersionId()), "mode", "INSTALL",
                "expected_installation_revision", 0, "config", Map.of(),
                "bindings", Map.of("capabilities", List.of(), "workflows", List.of(), "agents", List.of()));
        var accepted = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        assertEquals(200, accepted.statusCode(), accepted.body());
        var receipt = json.readTree(accepted.body()).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText(), accepted.body());
        accessToken = admin;
        var updated = send("PUT", access + "/role", Map.of("id", role.path("id").asText(), "name", "仅回执读取",
                "data_scope", "all", "permissions", readPermissions, "department_ids", List.of(),
                "enabled", true, "lock_version", role.path("lock_version").asLong()), null);
        assertEquals(200, updated.statusCode(), updated.body());
        accessToken = actor;
        assertEquals(401, send("GET", "/api/v1/agents/workflows/onboard/result?requestId=" + requestId, null, null).statusCode());
        var relogin = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug,
                "username", username, "password", PASSWORD), null);
        assertEquals(200, relogin.statusCode(), relogin.body());
        actor = json.readTree(relogin.body()).path("data").path("access_token").asText();
        accessToken = actor;
        var read = send("GET", "/api/v1/agents/workflows/onboard/result?requestId=" + requestId, null, null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(receipt, json.readTree(read.body()).path("data"));
        assertEquals(403, send("POST", "/api/v1/agents/workflows/onboard", command, null).statusCode());
        accessToken = admin;
        assertEquals(204, send("GET", "/api/v1/agents/workflows/onboard/result?requestId=" + requestId, null, null).statusCode());
        String otherSlug = "receipt-other-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(tenantMapper, planMapper, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try { createTestTenantAdministrator(otherTenant, "other-receipt-admin", PASSWORD); }
        finally { TenantContext.setTenantId(tenantId); }
        var otherLogin = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", otherSlug,
                "username", "other-receipt-admin", "password", PASSWORD), null);
        assertEquals(200, otherLogin.statusCode(), otherLogin.body());
        accessToken = json.readTree(otherLogin.body()).path("data").path("access_token").asText();
        assertEquals(204, send("GET", "/api/v1/agents/workflows/onboard/result?requestId=" + requestId, null, null).statusCode());
        accessToken = admin;
        var readOnlyRole = json.readTree(updated.body()).path("data");
        var noObjectRead = send("PUT", access + "/role", Map.of("id", role.path("id").asText(), "name", "无工作流对象读取",
                "data_scope", "all", "permissions", List.of("foundation.agents.read", "foundation.workflows.read", "foundation.resource.agent.read"),
                "department_ids", List.of(), "enabled", true, "lock_version", readOnlyRole.path("lock_version").asLong()), null);
        assertEquals(200, noObjectRead.statusCode(), noObjectRead.body());
        var readDeniedLogin = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug,
                "username", username, "password", PASSWORD), null);
        assertEquals(200, readDeniedLogin.statusCode(), readDeniedLogin.body());
        accessToken = json.readTree(readDeniedLogin.body()).path("data").path("access_token").asText();
        assertEquals(403, send("GET", "/api/v1/agents/workflows/onboard/result?requestId=" + requestId, null, null).statusCode());
        assertEquals(1L, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_installation_revisions WHERE instance_id=?",
                Long.parseLong(receipt.path("instance_id").asText())));
    }

    @Test
    void workflowOnboardingIsAtomicAndOriginalReceiptIsReplayable() throws Exception {
        var graph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(Map.of("from", "start", "to", "end")),
                "meta", Map.of("capability", Map.of("model_invocable", true, "user_invocable", false,
                        "input_schema", Map.of(), "output_schema", Map.of(), "supports_hitl", false,
                        "export_fields", List.of(), "timeout_ms", 30000, "concurrency_safe", true)));
        var created = builder("POST", "", Map.of("workflow_id", "onboard-flow", "definition", graph), null, WorkflowDraftResponse.class);
        var published = publishThroughHttp("onboard-flow", created.revision());
        var agentResponse = send("POST", "/api/v1/agents", Map.of("agent_key", "wf-agent-" + SnowflakeIds.nextId(),
                "name", "工作流接入", "system_prompt", "test", "config", Map.of()), null);
        assertEquals(200, agentResponse.statusCode(), agentResponse.body());
        String agent = json.readTree(agentResponse.body()).path("data").path("id").asText();
        String requestId = java.util.UUID.randomUUID().toString();
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("request_id", requestId); command.put("agent_id", agent); command.put("workflow_id", "onboard-flow");
        command.put("expected_definition_version_id", Long.toString(published.definitionVersionId()));
        command.put("mode", "INSTALL"); command.put("expected_installation_revision", 0);
        command.put("config", Map.of()); command.put("bindings", Map.of("capabilities", List.of(), "workflows", List.of(), "agents", List.of()));
        var first = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        assertEquals(200, first.statusCode(), first.body());
        var success = json.readTree(first.body()).path("data");
        assertEquals("SUCCEEDED", success.path("status").asText(), first.body());
        assertTrue(success.path("instance_id").isTextual());
        assertEquals(1, success.path("installation_revision").asInt());
        var replay = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        assertEquals(success, json.readTree(replay.body()).path("data"));
        var result = send("GET", "/api/v1/agents/workflows/onboard/result?requestId=" + requestId, null, null);
        assertEquals(success, json.readTree(result.body()).path("data"));
        command.put("config", Map.of("changed", true));
        assertEquals(409, send("POST", "/api/v1/agents/workflows/onboard", command, null).statusCode());
        command.put("request_id", java.util.UUID.randomUUID().toString());
        command.put("mode", "UPDATE"); command.put("expected_installation_revision", 1);
        command.put("config", Map.of());
        command.put("mount_id", success.path("mount_id").asText()); command.put("expected_mount_revision", 99);
        var failed = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        assertEquals("REJECTED", json.readTree(failed.body()).path("data").path("status").asText(), failed.body());
        assertEquals(1L, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_installation_revisions WHERE instance_id=?",
                Long.parseLong(success.path("instance_id").asText())));
        command.put("request_id", java.util.UUID.randomUUID().toString());
        command.put("expected_mount_revision", success.path("mount_revision").asLong());
        var updated = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        var updatedReceipt = json.readTree(updated.body()).path("data");
        assertEquals("SUCCEEDED", updatedReceipt.path("status").asText(), updated.body());
        assertEquals(2, updatedReceipt.path("installation_revision").asInt());
        assertEquals(success.path("mount_id"), updatedReceipt.path("mount_id"));
        var secondAgent = send("POST", "/api/v1/agents", Map.of("agent_key", "wf-agent-" + SnowflakeIds.nextId(),
                "name", "复用工作流", "system_prompt", "test", "config", Map.of()), null);
        assertEquals(200, secondAgent.statusCode(), secondAgent.body());
        command.put("request_id", java.util.UUID.randomUUID().toString());
        command.put("agent_id", json.readTree(secondAgent.body()).path("data").path("id").asText());
        command.put("mode", "REUSE"); command.put("expected_installation_revision", 2);
        command.remove("mount_id"); command.remove("expected_mount_revision");
        var reused = send("POST", "/api/v1/agents/workflows/onboard", command, null);
        var reusedReceipt = json.readTree(reused.body()).path("data");
        assertEquals("SUCCEEDED", reusedReceipt.path("status").asText(), reused.body());
        assertEquals(success.path("instance_id"), reusedReceipt.path("instance_id"));
        assertEquals(2, reusedReceipt.path("installation_revision").asInt());
        assertEquals(2L, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_installation_revisions WHERE instance_id=?",
                Long.parseLong(success.path("instance_id").asText())));
        var instances = send("GET", BUILDER + "/instances", null, null);
        assertTrue(json.readTree(instances.body()).path("data").path("items").get(0).path("id").isTextual(), instances.body());

        String adminToken = accessToken;
        String access = "/api/v1/tenant/foundation/access";
        for (String scope : List.of("all", "self")) {
            var permissions = new java.util.ArrayList<>(List.of("foundation.workflows.create",
                    "foundation.resource.workflow.manage", "foundation.resource.agent.manage"));
            if (scope.equals("self")) permissions.add("foundation.agents.create");
            var roleResponse = send("POST", access + "/role", Map.of("code", "workflow_limited_" + SnowflakeIds.nextId(),
                    "name", "受限接入", "data_scope", scope, "permissions", permissions, "department_ids", List.of()), null);
            assertEquals(200, roleResponse.statusCode(), roleResponse.body());
            String role = json.readTree(roleResponse.body()).path("data").path("id").asText();
            String username = "limited-wf-" + SnowflakeIds.nextId();
            var memberResponse = send("POST", access + "/member", Map.of("username", username, "display_name", username,
                    "password", PASSWORD, "role_ids", List.of(role)), null);
            assertEquals(200, memberResponse.statusCode(), memberResponse.body());
            var login = send("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug,
                    "username", username, "password", PASSWORD), null);
            assertEquals(200, login.statusCode(), login.body());
            accessToken = json.readTree(login.body()).path("data").path("access_token").asText();
            String deniedId = java.util.UUID.randomUUID().toString();
            command.put("request_id", deniedId);
            var denied = send("POST", "/api/v1/agents/workflows/onboard", command, null);
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals(0L, queryCount(jdbc, tenantId,
                    "SELECT count(*) FROM nw_workflow_onboarding_receipts WHERE request_id=CAST(? AS uuid)", deniedId));
            accessToken = adminToken;
        }
        assertEquals(2L, queryCount(jdbc, tenantId, "SELECT count(*) FROM nw_workflow_installation_revisions WHERE instance_id=?",
                Long.parseLong(success.path("instance_id").asText())));
    }

    private WorkflowDraftResponse createThroughHttp(String workflowId, String name) throws Exception {
        return builder("POST", "", Map.of("workflow_id", workflowId, "definition", definition(name)),
                null, WorkflowDraftResponse.class);
    }

    private Map<String, Object> saveCommand(String workflowId, String name) {
        return Map.of("workflow_id", workflowId, "payload", Map.of("definition", definition(name)));
    }

    private WorkflowDraftResponse saveThroughHttp(String workflowId, long revision, String name) throws Exception {
        return builder("PUT", "/draft", saveCommand(workflowId, name), revision, WorkflowDraftResponse.class);
    }

    private WorkflowPublishResponse publishThroughHttp(String workflowId, long revision) throws Exception {
        return builder("POST", "/publish", Map.of("workflow_id", workflowId), revision, WorkflowPublishResponse.class);
    }

    private WorkflowInstallResponse installThroughHttp(String workflowId, String locale, String prompt,
            WorkflowInstallationBindings bindings) throws Exception {
        return builder("POST", "/install", Map.of("workflow_id", workflowId,
                "payload", new WorkflowInstallByWorkflowIdRequest(Map.of("locale", locale), prompt, bindings)),
                null, WorkflowInstallResponse.class);
    }

    private <T> T builder(String method, String path, Object body, Long revision, Class<T> responseType) throws Exception {
        var response = send(method, BUILDER + path, body, revision);
        assertEquals(200, response.statusCode(), response.body());
        var envelope = json.readTree(response.body());
        assertEquals("OK", envelope.path("code").asText(), response.body());
        assertTrue(!envelope.path("trace_id").asText().isBlank(), response.body());
        return json.treeToValue(envelope.path("data"), responseType);
    }

    private HttpResponse<String> send(String method, String path, Object body, Long revision) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (path.equals("/api/v1/agents/workflows/onboard")) request.header("Idempotency-Key", "workflow-receipt-key");
        if (revision != null) request.header("If-Match", "\"" + revision + "\"");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> definition(String name) {
        return Map.of("entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "knowledge", "type", "knowledge_query", "name", name,
                                "requirement", "knowledge", "query", "政策", "result_to", "evidence",
                                "budget", Map.of("max_items", 5, "max_tokens", 1000, "max_characters", 4000,
                                        "timeout_ms", 5000, "max_sources", 1))),
                "edges", List.of(Map.of("from", "start", "to", "knowledge")),
                "meta", Map.of("capability_requirements", List.of(Map.of("slot", "knowledge", "type", "knowledge_source",
                                "capability_id", knowledgeCapabilityKey, "version_constraint", "*", "required", true)),
                        "config_schema", List.of(Map.of("key", "locale", "type", "string", "default", "en-US")),
                        "capability", Map.of(
                        "model_invocable", true,
                        "user_invocable", false,
                        "input_schema", Map.of(),
                        "output_schema", Map.of(),
                        "supports_hitl", false,
                        "export_fields", List.of(),
                        "timeout_ms", 30_000,
                        "concurrency_safe", true)));
    }
}
