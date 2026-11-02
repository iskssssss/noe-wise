package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.InstallAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.ToolMountConfigRequest;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.institution.service.AgentTemplateInstallationService;
import java.time.Instant;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;

/** 模板安装在真实 PostgreSQL 事务中的快照、幂等、回滚与租户隔离验收。 */
@Import(DeterministicModelCatalogConfiguration.class)
class AgentTemplateInstallationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TenantMapper tenantMapper;
    @Autowired private PlanMapper planMapper;
    @Autowired private TenantMemberMapper memberMapper;
    @Autowired private FoundationAccess foundation;
    @Autowired private FoundationOrganizationService organization;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private AgentTemplateService templates;
    @Autowired private AgentService agents;
    @Autowired private com.skillforge.core.agent.AgentPublicationService publications;
    @Autowired private com.skillforge.institution.memory.MemoryExtractionPolicyMapper extractionPolicy;
    @Autowired private AgentTemplateInstallationService installations;
    @Autowired private com.skillforge.institution.service.AgentManagementService management;
    @Autowired private com.skillforge.institution.service.CapabilityMountManagementService mounts;

    private String tenantA;
    private String tenantB;
    private String modelId;
    private String memberA;
    private String memberB;

    /** 每个测试使用真实已建制机构主体；并发线程及跨机构阶段另行显式绑定。 */
    @RegisterExtension final InvocationInterceptor authenticatedFixture = new InvocationInterceptor() {
        @Override public void interceptTestMethod(Invocation<Void> invocation,
                ReflectiveInvocationContext<Method> context, ExtensionContext extensionContext) throws Throwable {
            Throwable[] failure = new Throwable[1];
            foundation.withSubject(subject(tenantA), () -> {
                try { invocation.proceed(); } catch (Throwable error) { failure[0] = error; }
                return null;
            });
            if (failure[0] != null) throw failure[0];
        }
    };

    @Test
    void stableIdentityPublishesSnapshotsAndRestoresDraftWithoutMovingBindings() throws Exception {
        AgentTemplateDO template = publishedTemplate(List.of(), List.of());
        TenantContext.setTenantId(tenantA);
        var original = installations.install(request(template.getId(), "revision-" + SnowflakeIds.nextId(), List.of()));
        String originalId = original.agent().id().toString();
        var oldMount = mounts.read(Long.valueOf(tenantA), original.agent().id()).mounts().getFirst();
        var originalPublication = publications.requirePublished(Long.parseLong(tenantA), original.agent().id());
        assertThat(management.revise(originalId).id()).isEqualTo(original.agent().id());
        assertThat(management.revise(originalId).revision()).isEqualTo(1);
        management.updateAgent(originalId,
                new com.skillforge.core.agent.dto.request.UpdateAgentRequest("rewrite", null, "updated prompt", null, null, null,
                        Map.of("memory", Map.of("automatic_extraction", true)), null, null));
        assertThat(management.getAgent(originalId).hasDraftChanges()).isTrue();
        assertThat(publications.requirePublished(Long.parseLong(tenantA), original.agent().id()).agent().getSystemPrompt())
                .isEqualTo("template prompt");
        assertThat(extractionPolicy.automaticExtractionEnabled(tenantA, originalId)).isFalse();
        mounts.delete(Long.valueOf(tenantA), original.agent().id(), oldMount.mountId(), oldMount.revision());
        assertThat(publications.requirePublished(Long.parseLong(tenantA), original.agent().id()).mountSet().mounts()).hasSize(1);
        assertThatThrownBy(() -> management.deleteAgent(originalId)).hasMessageContaining("不能删除");

        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<com.skillforge.core.agent.dto.response.AgentPublishResponse> revision = () -> {
                TenantContext.setTenantId(tenantA);
                try {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("修订并发起点未释放");
                    return foundation.withSubject(subject(tenantA), () -> management.publish(originalId));
                } finally { TenantContext.clear(); }
            };
            var first = executor.submit(revision); var second = executor.submit(revision);
            try {
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally { start.countDown(); }
            var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.agentId()).isEqualTo(original.agent().id());
            assertThat(b.agentId()).isEqualTo(original.agent().id());
            assertThat(management.revisions(originalId).items()).extracting(item -> item.revision())
                    .containsExactly(3, 2, 1);
            assertThat(management.listAgents().items().stream().filter(item -> item.agentKey().equals(original.agent().agentKey())))
                    .hasSize(1);
            var latest = publications.requirePublished(Long.parseLong(tenantA), original.agent().id());
            assertThat(latest.agent().getSystemPrompt()).isEqualTo("updated prompt");
            assertThat(latest.mountSet().mounts()).isEmpty();
            assertThat(extractionPolicy.automaticExtractionEnabled(tenantA, originalId)).isTrue();
            assertThat(publications.revision(Long.parseLong(tenantA), original.agent().id(), 1).revisionId())
                    .isEqualTo(originalPublication.revisionId());
            management.restoreRevision(originalId, 1);
            assertThat(management.getAgent(originalId).systemPrompt()).isEqualTo("template prompt");
            assertThat(management.getAgent(originalId).mounts()).hasSize(1);
            assertThat(management.getAgent(originalId).hasDraftChanges()).isTrue();
            assertThat(publications.requirePublished(Long.parseLong(tenantA), original.agent().id()).revisionId())
                    .isEqualTo(latest.revisionId());
            assertThat(extractionPolicy.automaticExtractionEnabled(tenantA, originalId)).isTrue();
            management.publish(originalId);
            assertThat(publications.requirePublished(Long.parseLong(tenantA), original.agent().id()).agent().getSystemPrompt())
                    .isEqualTo("template prompt");
            assertThat(management.getAgent(originalId).revision()).isEqualTo(4);
            assertThat(extractionPolicy.automaticExtractionEnabled(tenantA, originalId)).isFalse();
            TenantContext.setTenantId(tenantB);
            foundation.withSubject(subject(tenantB), () -> {
                assertThatThrownBy(() -> management.revise(originalId))
                        .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                        .satisfies(error -> assertThat(((com.skillforge.core.shared.exception.ApiException) error).getCode()).isEqualTo("foundation.resource.denied"));
                assertThatThrownBy(() -> management.revisions(originalId))
                        .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                        .satisfies(error -> assertThat(((com.skillforge.core.shared.exception.ApiException) error).getCode()).isEqualTo("foundation.resource.denied"));
                assertThatThrownBy(() -> publications.revision(Long.parseLong(tenantB), original.agent().id(), 1))
                        .hasMessageContaining("不存在");
                return null;
            });
        }
    }

    @BeforeEach
    void setUp() {
        tenantA = createTenant(tenantMapper, planMapper, "template-install-a-" + SnowflakeIds.nextId());
        tenantB = createTenant(tenantMapper, planMapper, "template-install-b-" + SnowflakeIds.nextId());
        configureInstallationPlan();
        memberA = initializeAdministrator(tenantA);
        memberB = initializeAdministrator(tenantB);
        TenantContext.setTenantId(tenantA);
        modelId = PlatformModelFixtures.selectLlm(models, tenantA, "https://template.example/v1",
                "deepseek-flash", "key", "custom", "custom-openai-compatible", "custom-v1");
        TenantContext.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void installsPublishedTemplateAsTenantOwnedSnapshotAndReplaysSameRequest() {
        AgentTemplateDO template = publishedTemplate(List.of(), List.of());
        TenantContext.setTenantId(tenantA);
        InstallAgentTemplateRequest request = request(template.getId(), "install-replay-" + SnowflakeIds.nextId(), List.of());

        var first = installations.install(request);
        var second = installations.install(request);
        var readReceipt = installations.result(request.requestId());
        assertThat(readReceipt.requestId()).isEqualTo(request.requestId());
        assertThat(readReceipt.agentId()).isEqualTo(first.agent().id().toString());
        assertThat(installations.result("missing-request")).isNull();
        TenantContext.setTenantId(tenantB);
        foundation.withSubject(subject(tenantB), () -> { assertThat(installations.result(request.requestId())).isNull(); return null; });
        TenantContext.setTenantId(tenantA);
        var installed = agents.getById(first.agent().id());

        assertThat(second.agent().id()).isEqualTo(first.agent().id());
        assertThat(installed.getStatus()).isEqualTo("published");
        assertThat(installed.getTemplateId()).isEqualTo(template.getId());
        assertThat(installed.getModelConfigId()).isEqualTo(Long.valueOf(modelId));
        assertThat(installed.getIconUrl()).isEqualTo(template.getIconUrl());
        assertThat(installed.getExecutionConfig()).isEqualTo(template.getDefinition().executionConfig());
        assertThat(first.mounts().mounts()).hasSize(1);
        assertThat(queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_agents WHERE template_id = ?", template.getId())).isEqualTo(1);
        assertThat(queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_tool_deployments WHERE tenant_id = ?", Long.valueOf(tenantA))).isEqualTo(1);
        assertThat(queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_agent_template_installations WHERE tenant_id = ?", Long.valueOf(tenantA))).isEqualTo(1);
        assertThat(templates.getById(template.getId()).getInstallCount()).isEqualTo(1);

        template.setDefinition(new AgentTemplateDefinition("changed", "changed", modelId,
                AgentExecutionConfig.inherited(), List.of(), List.of(), List.of()));
        templates.updateById(template);
        assertThat(agents.getById(first.agent().id()).getSystemPrompt()).isEqualTo("template prompt");
    }

    /** 受控定义从首次发布起只能走授权部署，普通目录、详情和安装均不获得复制权。 */
    @Test
    void authorizedDefinitionRejectsIndependentLibraryAndInstallation() {
        AgentTemplateDO template=publishedTemplate(List.of(),List.of(), com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        TenantContext.setTenantId(tenantA);
        long agentsBefore=queryCount(jdbc,tenantA,"SELECT count(*) FROM nw_agents",new Object[0]);

        assertThat(templates.currentPublishedLibrary()).extracting(AgentTemplateDO::getId).doesNotContain(template.getId());
        assertThatThrownBy(()->management.templateDetail(template.getId()))
                .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                .hasMessageContaining("当前有效授权部署");
        assertThatThrownBy(()->installations.install(request(template.getId(),"managed-"+SnowflakeIds.nextId(),List.of())))
                .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                .hasMessageContaining("当前有效授权部署");
        assertThat(queryCount(jdbc,tenantA,"SELECT count(*) FROM nw_agents",new Object[0])).isEqualTo(agentsBefore);
    }

    /** 数据库约束防止绕过管理服务切换已发布源的分发策略。 */
    @Test
    void firstPublicationMakesDistributionPolicyImmutableInDatabase() {
        AgentTemplateDO template = publishedTemplate(List.of(), List.of());
        template.setDistributionPolicy(com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY);

        assertThatThrownBy(() -> templates.updateById(template))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(templates.getById(template.getId()).getDistributionPolicy())
                .isEqualTo(com.skillforge.core.agent.model.AgentDistributionPolicy.OPEN_INSTALL);
        assertThat(templates.currentPublished(template.getId()).getDistributionPolicy())
                .isEqualTo(com.skillforge.core.agent.model.AgentDistributionPolicy.OPEN_INSTALL);
    }

    @Test
    void invalidOptionalToolBindingRollsBackAgentBaseDeploymentCounterAndReceipt() {
        AgentTemplateDefinition.ResourceSlot slot = new AgentTemplateDefinition.ResourceSlot(
                "optional_tool", "可选工具", "机构选择的工具", CapabilityType.TOOL);
        AgentTemplateDO template = publishedTemplate(List.of(), List.of(slot));
        TenantContext.setTenantId(tenantA);
        ToolReference tool = platformTool();
        CapabilityMountRequest invalidMount = new CapabilityMountRequest(CapabilityType.TOOL,
                tool.definitionId(), tool.versionId(), null, new ToolMountConfigRequest("999999", 1L),
                null, null, 1, CapabilityMountStatus.ACTIVE);
        InstallAgentTemplateRequest request = request(template.getId(), "install-rollback-" + SnowflakeIds.nextId(),
                List.of(new InstallAgentTemplateRequest.ResourceBinding("optional_tool", invalidMount)));
        long agentsBefore = queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_agents", new Object[0]);
        long deploymentsBefore = queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_tool_deployments", new Object[0]);
        long receiptsBefore = queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_agent_template_installations", new Object[0]);
        int installsBefore = templates.getById(template.getId()).getInstallCount();

        assertThatThrownBy(() -> installations.install(request)).isInstanceOf(RuntimeException.class);

        assertThat(queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_agents", new Object[0])).isEqualTo(agentsBefore);
        assertThat(queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_tool_deployments", new Object[0])).isEqualTo(deploymentsBefore);
        assertThat(queryCount(jdbc, tenantA, "SELECT count(*) FROM nw_agent_template_installations", new Object[0])).isEqualTo(receiptsBefore);
        assertThat(templates.getById(template.getId()).getInstallCount()).isEqualTo(installsBefore);
    }

    @Test
    void rejectsRequestReuseWithDifferentPayloadAndAfterInstalledAgentDeletion() {
        AgentTemplateDO template = publishedTemplate(List.of(), List.of());
        TenantContext.setTenantId(tenantA);
        String requestId = "install-conflict-" + SnowflakeIds.nextId();
        var installed = installations.install(request(template.getId(), requestId, List.of()));

        assertThatThrownBy(() -> installations.install(new InstallAgentTemplateRequest(
                template.getId(), "different name", requestId, List.of(), List.of())))
                .hasMessageContaining("不同参数");
        agents.removeById(installed.agent().id());
        assertThatThrownBy(() -> installations.install(request(template.getId(), requestId, List.of())))
                .hasMessageContaining("已删除");
    }

    @Test
    void installationLedgerIsTenantIsolated() {
        AgentTemplateDO template = publishedTemplate(List.of(), List.of());
        TenantContext.setTenantId(tenantA);
        installations.install(request(template.getId(), "install-rls-" + SnowflakeIds.nextId(), List.of()));

        // 容器默认连接为超级用户；显式切换普通角色并保持同一事务，才会真实执行 RLS。
        jdbc.execute("CREATE ROLE template_receipt_reader NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO template_receipt_reader");
        jdbc.execute("GRANT SELECT ON nw_agent_template_installations TO template_receipt_reader");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                    var tenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                statement.execute("SET LOCAL ROLE template_receipt_reader");
                for (String tenantId : List.of(tenantA, tenantB)) {
                    tenant.setString(1, tenantId);
                    tenant.execute();
                    try (var rows = statement.executeQuery("SELECT count(*) FROM nw_agent_template_installations")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(tenantId.equals(tenantA) ? 1 : 0);
                    }
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            return null;
        });
    }

    private AgentTemplateDO publishedTemplate(
            List<AgentTemplateDefinition.BaseCapability> ignored, List<AgentTemplateDefinition.ResourceSlot> slots) {
        return publishedTemplate(ignored, slots, com.skillforge.core.agent.model.AgentDistributionPolicy.OPEN_INSTALL);
    }

    private AgentTemplateDO publishedTemplate(
            List<AgentTemplateDefinition.BaseCapability> ignored, List<AgentTemplateDefinition.ResourceSlot> slots,
            com.skillforge.core.agent.model.AgentDistributionPolicy distributionPolicy) {
        TenantContext.clear();
        ToolReference tool = platformTool();
        AgentTemplateDO template = new AgentTemplateDO();
        template.setDistributionPolicy(distributionPolicy);
        template.setId(SnowflakeIds.nextId());
        template.setTemplateKey("install-template-" + template.getId());
        template.setName("安装模板"); template.setVersion("1.0.0"); template.setCategory("test");
        template.setTags(List.of()); template.setDescription("integration"); template.setIconUrl("/template.svg");
        template.setAuthor("test"); template.setAuthorType("platform");
        template.setDefinition(new AgentTemplateDefinition("template prompt", "template role", modelId,
                new AgentExecutionConfig(2, 3, 4, 8192L, 300L),
                List.of(new AgentTemplateDefinition.BaseCapability(CapabilityType.TOOL, tool.definitionId(),
                        tool.versionId(), null, tool.name(), 1)), slots, List.of()));
        template.setPriceType("free"); template.setPriceCents(0); template.setInstallCount(0);
        template.setStatus("published"); template.setPublishedAt(Instant.now());
        template.setCreatedAt(Instant.now()); template.setUpdatedAt(Instant.now());
        templates.save(template);
        templates.appendPublication(template);
        return template;
    }

    private InstallAgentTemplateRequest request(Long templateId, String requestId,
            List<InstallAgentTemplateRequest.ResourceBinding> bindings) {
        return new InstallAgentTemplateRequest(templateId, "已安装模板", requestId, bindings, List.of());
    }

    private ToolReference platformTool() {
        PlatformToolCatalog.Entry entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(value -> value.name().equals("get_current_time")).findFirst().orElseThrow();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT d.id AS definition_id, v.id AS version_id
                  FROM nw_capability_definitions d JOIN nw_capability_versions v ON v.definition_id = d.id
                 WHERE d.tenant_id IS NULL AND d.capability_key = ? AND d.status = 'PUBLISHED'
                   AND v.status = 'PUBLISHED' AND v.content_hash = ?
                """, "tool:" + entry.name(), entry.contentHash());
        return new ToolReference(((Number) row.get("definition_id")).longValue(),
                ((Number) row.get("version_id")).longValue(), entry.name());
    }

    private record ToolReference(long definitionId, long versionId, String name) { }

    /** 本类独立套餐具有真实一个Agent名额，agents功能关系不继承其他测试的配置。 */
    private void configureInstallationPlan() {
        TenantContext.clear();
        PlanDO plan = new PlanDO();
        plan.setId(SnowflakeIds.nextId()); plan.setPlanCode("ti-" + plan.getId());
        plan.setDisplayName("模板安装验收套餐"); plan.setIsActive(true);
        plan.setQuotaApiCallsPerMonth(100); plan.setQuotaTokensIncluded(0); plan.setRateLimitPerMinute(120);
        plan.setSloAvailabilityTarget(new BigDecimal("0.999"));
        plan.setQuotaMaxAgents(1); plan.setQuotaMaxWorkflows(0); plan.setQuotaMaxKnowledgeBases(0);
        plan.setQuotaMaxMembers(1); plan.setQuotaMaxChannels(0); plan.setQuotaFileBytes(0L); plan.setQuotaMaxAdminTasks(0);
        plan.setCreatedAt(Instant.now()); plan.setUpdatedAt(plan.getCreatedAt());
        planMapper.insert(plan);
        jdbc.update("INSERT INTO nw_foundation_plan_features(plan_id,feature_code,enabled,updated_by) VALUES(?,'agents',true,'template-install-fixture')", plan.getId());
        for (String tenant : List.of(tenantA, tenantB)) {
            TenantDO update = new TenantDO(); update.setId(Long.valueOf(tenant)); update.setPlanId(plan.getId());
            tenantMapper.updateById(update);
        }
    }

    /** 正式初始化内置管理员角色与授权版本，不依赖历史role列或mock权限。 */
    private String initializeAdministrator(String tenant) {
        TenantContext.setTenantId(tenant);
        TenantMemberDO member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId());
        member.setTenantId(Long.valueOf(tenant)); member.setUsername("template-admin-" + member.getId());
        member.setRole("admin"); member.setPermissions(List.of()); member.setStatus("active"); member.setCreatedAt(Instant.now());
        memberMapper.insert(member);
        organization.initializeTenant(tenant, member.getId().toString());
        return member.getId().toString();
    }

    /** 跨租户及工作线程始终绑定所属机构的真实管理员。 */
    private FoundationAccess.Subject subject(String tenant) {
        return new FoundationAccess.Subject(false, tenant, tenant.equals(tenantA) ? memberA : memberB);
    }
}
