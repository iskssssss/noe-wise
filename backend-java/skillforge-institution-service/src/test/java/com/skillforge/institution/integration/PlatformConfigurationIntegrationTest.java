package com.skillforge.institution.integration;

import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.*;

/** 在隔离 PostgreSQL 上验证完整迁移、版本指针、冻结读取和数据库不可变约束。 */
class PlatformConfigurationIntegrationTest extends AbstractIntegrationTest {
    @Autowired PlatformModelCatalogService models;
    @Autowired OperationalSettingsService settings;
    @Autowired RunSettingsService runs;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired com.skillforge.institution.runtime.AgentRuntimeHostProvisioner host;
    @Autowired com.skillforge.runtime.starter.AgentRuntimeModelConfiguration preparation;
    @Autowired com.skillforge.runtime.api.port.ExecutionBudgetPort budgets;
    @Autowired com.skillforge.core.shared.config.SkillForgeProperties resources;

    /** 使用正式机构启动入口确认共享轨迹与宿主实时读权同时生效，不另建测试装配。 */
    @Test
    void trajectoryAssemblyLoadsCurrentReadCapabilitiesAndSingleRlsInterceptor() {
        var configuration = applicationContext.getBean(org.apache.ibatis.session.SqlSessionFactory.class)
                .getConfiguration();
        assertThat(configuration.getInterceptors().stream()
                .filter(com.skillforge.core.foundation.config.TenantRlsInterceptor.class::isInstance)
                .count()).isEqualTo(1L);
        assertThat(configuration.getInterceptors().stream()
                .filter(com.skillforge.core.shared.config.MapperExecutionLoggingInterceptor.class::isInstance)
                .count()).isEqualTo(1L);
        for (Class<?> capability : java.util.List.of(
                com.skillforge.core.chat.trajectory.TrajectoryConfiguration.class,
                com.skillforge.core.chat.trajectory.TrajectoryProjectionService.class,
                com.skillforge.core.chat.trajectory.TrajectoryListService.class,
                com.skillforge.core.knowledge.KnowledgeObservationAccess.class,
                com.skillforge.core.foundation.access.PlatformObservationAccess.class,
                com.skillforge.core.foundation.access.ObservationReadAudit.class)) {
            assertThat(applicationContext.getBeansOfType(capability)).as(capability.getName()).hasSize(1);
        }
        assertThat(applicationContext.getBeansOfType(com.skillforge.core.chat.trajectory.TrajectorySourceAuthorizer.class))
                .hasSize(1).containsValue(applicationContext.getBean(
                        com.skillforge.institution.runtime.InstitutionTrajectorySourceAuthorizer.class));
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
            assertThat(applicationContext.getBeansOfType(mapper)).as(mapper.getName()).hasSize(1);
            statements.forEach(statement -> assertThat(configuration.hasStatement(mapper.getName() + "." + statement))
                    .as(mapper.getName() + "." + statement).isTrue());
        });
    }

    @Test
    void freshDatabaseBootstrapPublishesRecommendedTaskRootDefaults() throws Exception {
        var expected = java.util.Map.ofEntries(
                java.util.Map.entry("standard_root_business_models", 256L),
                java.util.Map.entry("standard_root_tools", 1_024L),
                java.util.Map.entry("standard_root_knowledge", 256L),
                java.util.Map.entry("standard_root_total_tokens", 8_000_000L),
                java.util.Map.entry("standard_root_active_seconds", 7_200L),
                java.util.Map.entry("delegation_root_business_models", 1_024L),
                java.util.Map.entry("delegation_root_tools", 8_192L),
                java.util.Map.entry("delegation_root_knowledge", 1_024L),
                java.util.Map.entry("delegation_root_total_tokens", 32_000_000L),
                java.util.Map.entry("delegation_root_active_seconds", 14_400L),
                java.util.Map.entry("team_root_business_models", 2_048L),
                java.util.Map.entry("team_root_tools", 16_384L),
                java.util.Map.entry("team_root_knowledge", 2_048L),
                java.util.Map.entry("team_root_total_tokens", 64_000_000L),
                java.util.Map.entry("team_root_active_seconds", 28_800L));
        var snapshot = settings.read("task_budget");
        var persisted = applicationContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).readTree(
                jdbc.queryForObject("""
                        SELECT v.values::text FROM nw_platform_operational_setting_groups g
                        JOIN nw_platform_operational_setting_versions v
                          ON v.group_key=g.group_key AND v.revision=g.current_revision
                        WHERE g.group_key='task_budget'
                        """, String.class));
        assertThat(snapshot.values()).hasSize(15);
        assertThat(persisted.size()).isEqualTo(15);
        expected.forEach((key, value) -> {
            assertThat(snapshot.longValue(key)).as(key).isEqualTo(value);
            assertThat(persisted.path(key).longValue()).as("persisted " + key).isEqualTo(value);
        });
    }

    @Test void savesNewVersionsAndKeepsExistingRunOnOriginalModelAndPolicy() {
        String tenant=createTenant(tenants,plans,"platform-settings-"+java.util.UUID.randomUUID());
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash) VALUES(1,'configuration-test','unused') ON CONFLICT(id) DO NOTHING");
        var first=models.create(model(null,4096,"test-secret"),"1");
        models.publish(first.id(), first.revision(), "1");
        TenantContext.setTenantId(tenant);
        try {
            models.select(tenant,new ModelSelections(first.id(),null,null));
            assertThat(models.resolveLlm(tenant,null).maxOutputTokens()).isEqualTo(4096);
            var failedRun = new com.skillforge.runtime.api.RunRef(new com.skillforge.runtime.api.TenantId(tenant), "failed-freeze");
            assertThatThrownBy(() -> runs.capture(tenant, failedRun.runId(), first.id(), first.revision(),
                    com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), config -> {
                        var limits = com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.freeze(runs.read(config, "task_budget"),
                                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD,
                                com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY, config.executionConfig());
                        budgets.register(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(failedRun, failedRun,
                                com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY,
                                new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(limits)));
                        throw new IllegalStateException("preparation_failed");
                    })).hasMessage("preparation_failed");
            assertThat(budgets.find(failedRun)).isEmpty();
            assertThatThrownBy(() -> runs.requireFrozen(tenant, failedRun.runId())).hasMessage("run_settings_snapshot_missing");
            var original=capture(tenant,"frozen-run",first.id(),first.revision());
            var oldContext=runs.read(original,"task_budget");
            assertThat(jdbc.queryForObject("""
                    SELECT publication_source
                    FROM nw_platform_operational_setting_versions
                    WHERE group_key='task_budget' AND revision=?
                    """, String.class, oldContext.revision())).isEqualTo("operator");
            var reference = new com.skillforge.runtime.api.ResultRef(original.preparationProfile());
            var originalProfile = preparation.preparationProfile(new com.skillforge.runtime.api.TenantId(tenant), reference).orElseThrow();
            resources.setContextContentCharacters(2_000_000);
            var firstOperation=runs.captureOperation(tenant,"frozen-run","model:1",
                    first.id(),first.revision(),"首个动作角色");
            var changed=new LinkedHashMap<String,Object>(oldContext.values());
            changed.put("standard_root_business_models",4096);
            settings.update("task_budget",oldContext.revision(),changed,1);
            assertThat(jdbc.queryForObject("""
                    SELECT publication_source
                    FROM nw_platform_operational_setting_versions
                    WHERE group_key='task_budget' AND revision=?
                    """, String.class, oldContext.revision() + 1)).isEqualTo("operator");
            var second=models.update(first.id(),model(first.revision(),8192,null),"1");
            assertThat(models.resolveLlm(tenant,null).maxOutputTokens()).isEqualTo(4096);
            models.publish(second.id(), second.revision(), "1");
            assertThat(second.revision()).isEqualTo(2);
            assertThat(models.resolveLlm(tenant,null).maxOutputTokens()).isEqualTo(8192);
            var secondOperation=runs.captureOperation(tenant,"frozen-run","model:2",
                    first.id(),second.revision(),"第二个动作角色");
            var resumedFirstOperation=runs.captureOperation(tenant,"frozen-run","model:1",
                    first.id(),second.revision(),"不应覆盖原角色");
            var toolOperation=runs.captureTool(tenant,"frozen-run","tool-1");
            assertThat(firstOperation.modelRevision()).isEqualTo(first.revision());
            assertThat(firstOperation.roleInstructions()).isEqualTo("首个动作角色");
            assertThat(runs.read(firstOperation,"task_budget").values()).isEqualTo(oldContext.values());
            assertThat(secondOperation.modelRevision()).isEqualTo(first.revision());
            assertThat(secondOperation.roleInstructions()).isEqualTo("第二个动作角色");
            assertThat(runs.read(secondOperation,"task_budget").values()).isEqualTo(oldContext.values());
            assertThat(resumedFirstOperation).isEqualTo(firstOperation);
            assertThat(runs.findOperation(tenant,"frozen-run","model:1")).contains(firstOperation);
            assertThat(toolOperation.modelId()).isNull();
            assertThat(toolOperation.modelRevision()).isZero();
            assertThat(runs.read(toolOperation,"task_budget").values()).isEqualTo(oldContext.values());
            var nextMessage=capture(tenant,"next-message-in-existing-session",first.id(),second.revision());
            assertThat(nextMessage.modelRevision()).isEqualTo(second.revision());
            assertThat(runs.read(nextMessage,"task_budget").integer("standard_root_business_models")).isEqualTo(4096);
            assertThat(capture(tenant,"frozen-run",first.id(),second.revision())).isEqualTo(original);
            assertThat(runs.read(tenant,"frozen-run","task_budget").values()).isEqualTo(oldContext.values());
            var rebuilt = new RunSettingsService(applicationContext.getBean(com.skillforge.core.foundation.runtimeconfig.RunSettingsMapper.class), settings,
                    applicationContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class));
            assertThat(rebuilt.requireFrozen(tenant, "frozen-run")).isEqualTo(original);
            assertThat(preparation.preparationProfile(new com.skillforge.runtime.api.TenantId(tenant), reference)).contains(originalProfile);
            var nextProfile = preparation.preparationProfile(new com.skillforge.runtime.api.TenantId(tenant),
                    new com.skillforge.runtime.api.ResultRef(nextMessage.preparationProfile())).orElseThrow();
            assertThat(nextProfile.maxCharacters()).isEqualTo(2_000_000);
            assertThat(originalProfile.maxCharacters()).isEqualTo(32L * 1024 * 1024);
            assertThatThrownBy(() -> settings.update("task_budget", oldContext.revision(), changed, 1)).hasMessageContaining("版本冲突");
            assertThat(settings.rollback("task_budget", oldContext.revision() + 1, oldContext.revision(), 1).values()).isEqualTo(oldContext.values());
            assertThat(settings.list()).extracting(OperationalSettingsService.GroupView::key).doesNotContain("context", "run_budget");
            assertThatThrownBy(() -> settings.update("context", 1, java.util.Map.of(), 1)).hasMessageContaining("未知运营配置分组");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='nw_agents' AND column_name IN ('max_input_tokens','run_max_output_tokens')", Integer.class)).isZero();
            assertThat(models.readRevision(original.modelId(),original.modelRevision()).maxOutputTokens()).isEqualTo(4096);
            assertThat(models.rollback(first.id(),1,2,"1").revision()).isEqualTo(3);
            assertThat(models.current(first.id()).maxOutputTokens()).isEqualTo(8192);
            models.publish(first.id(), 3, "1");
            assertThat(models.current(first.id()).maxOutputTokens()).isEqualTo(4096);
            assertThatThrownBy(()->models.update(first.id(),model(1L,4096,null),"1"))
                    .hasMessageContaining("model_revision_conflict");
            assertThatThrownBy(()->jdbc.update("UPDATE nw_platform_model_versions SET config='{}'::jsonb WHERE model_id=?",Long.parseLong(first.id())))
                    .hasMessageContaining("configuration_version_immutable");
            assertThat(jdbc.queryForObject("SELECT config::text FROM nw_platform_model_versions WHERE model_id=? AND revision=1",String.class,Long.parseLong(first.id())))
                    .doesNotContain("test-secret");
            jdbc.execute("CREATE ROLE configuration_reader NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO configuration_reader");
            jdbc.execute("GRANT SELECT ON nw_host_run_settings,nw_host_operation_settings,nw_tenant_model_selections TO configuration_reader");
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                connection.setAutoCommit(false);
                try (var statement=connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE configuration_reader");
                    statement.execute("SELECT set_config('app.tenant_id','foreign-tenant',true)");
                    for (String table:java.util.List.of("nw_host_run_settings","nw_host_operation_settings","nw_tenant_model_selections")) {
                        try (var rows=statement.executeQuery("SELECT count(*) FROM " + table)) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isZero();
                        }
                    }
                } finally { connection.rollback(); connection.setAutoCommit(true); }
                return null;
            });
        } finally { resources.setContextContentCharacters(32 * 1024 * 1024); TenantContext.clear(); }
    }

    /** 真实预算与 Profile 先完成，再写入完整冻结引用；重复受理不再次构建。 */
    private RunSettingsService.FrozenConfig capture(String tenant, String run, String model, long revision) {
        return runs.capture(tenant, run, model, revision, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), config -> {
            var ref = new com.skillforge.runtime.api.RunRef(new com.skillforge.runtime.api.TenantId(tenant), run);
            var limits = com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.freeze(runs.read(config, "task_budget"),
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD,
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY, config.executionConfig());
            budgets.register(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(ref, ref,
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY,
                    new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(limits)));
            return host.provision(tenant);
        });
    }

    @Test
    void workerLedgerKeepsLocalTransactionsAndSettlesWholeTokenQuotaAcrossRebuild() {
        var tenant = new com.skillforge.runtime.api.TenantId("worker-quota-test");
        var run = new com.skillforge.runtime.api.RunRef(tenant, "standalone-ledger");
        var resources = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources(8, 4, 8, 4, 1_000, 1_000, 1_000);
        var limits = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Limits(
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD, resources, resources, resources, 3_600, 3_600, "budget-policy.v3:test");
        budgets.register(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(run, run,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY,
                new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(limits)));
        var demand = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Demand(run, "large-output", "request",
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.BUSINESS_MODEL,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources.call(com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 750), false);
        assertThat(budgets.reserve(demand)).isInstanceOf(com.skillforge.runtime.api.port.ExecutionBudgetPort.Reservation.Granted.class);
        assertThat(budgets.markSent(run, demand.attemptId())).isTrue();
        budgets.saveResult(run, demand.attemptId(), com.skillforge.runtime.api.port.ExecutionBudgetPort.Usage.reported(
                java.util.OptionalLong.of(50), java.util.OptionalLong.of(700)), "result");
        budgets.settle(run, demand.attemptId()); budgets.settle(run, demand.attemptId());
        var rebuilt = new com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets(applicationContext.getBean(javax.sql.DataSource.class));
        assertThat(rebuilt.snapshot(run).consumed().totalTokens()).isEqualTo(750);
        assertThat(rebuilt.snapshot(run).consumed().outputTokens()).isEqualTo(700);
        assertThat(rebuilt.snapshot(run).reserved().totalTokens()).isZero();
        var unknown = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Demand(run, "missing-usage", "missing",
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.BUSINESS_MODEL,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources.call(com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.BUSINESS_MODEL, 50, 50), false);
        assertThat(budgets.reserve(unknown)).isInstanceOf(com.skillforge.runtime.api.port.ExecutionBudgetPort.Reservation.Granted.class);
        assertThat(budgets.markSent(run, unknown.attemptId())).isTrue();
        budgets.saveResult(run, unknown.attemptId(), com.skillforge.runtime.api.port.ExecutionBudgetPort.Usage.unreported(), "unknown");
        rebuilt.recover(run);
        assertThat(rebuilt.snapshot(run).reserved().totalTokens()).isEqualTo(100);
        assertThat(budgets.markSent(run, unknown.attemptId())).isFalse();
    }

    private ModelWrite model(Long revision,int output,String key) {
        return new ModelWrite(revision,"平台模型","LLM",true,"deepseek","deepseek-api","2026-09-13",
                "openai-chat-completions","bearer-api-key","https://api.deepseek.com","deepseek-chat",key,
                8192,64000,output,0.5,"DEFAULT",60,null,null,null,null,null,"CNY");
    }
}
