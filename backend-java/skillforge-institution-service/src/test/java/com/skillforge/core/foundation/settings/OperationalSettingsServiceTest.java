package com.skillforge.core.foundation.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.application.CryptoService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 运营配置版本、回滚、敏感值与注册校验测试。 */
class OperationalSettingsServiceTest {
    private static final OperationalSettingsRegistry REGISTRY = new OperationalSettingsRegistry(java.util.List.of(new com.skillforge.core.foundation.runtimeconfig.RuntimeBudgetOperationalSettings(), new FoundationOperationalSettings(), new com.skillforge.core.chat.contract.ChatOperationalSettings(), new com.skillforge.core.capability.connector.ExecutionOperationalSettings(), new com.skillforge.core.billing.settings.BillingOperationalSettings(), new com.skillforge.core.knowledge.KnowledgeSettingsContributor()));

    @Test
    void startupPublishesDefaultsAndRuntimeReadsNeverWriteThem() {
        var mapper = org.mockito.Mockito.spy(new InMemoryMapper());
        var service = service(mapper);
        org.mockito.Mockito.clearInvocations(mapper);

        var snapshot = service.read("task_budget");
        assertThat(service.readRevision("task_budget", snapshot.revision())).isEqualTo(snapshot);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertVersion(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertHead(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedStartupRetainsPublishedRevisionAndMissingRuntimeGroupFailsWithoutRepair() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var original = service.read("task_budget");
        var values = new LinkedHashMap<>(original.values());
        values.put("standard_root_business_models", 21);
        service.update("task_budget", original.revision(), values, 7);
        service.afterSingletonsInstantiated();
        assertThat(service.read("task_budget").revision()).isEqualTo(2);
        mapper.heads.remove("task_budget");
        assertThatThrownBy(() -> service.read("task_budget")).hasMessageContaining("当前版本不存在");
        assertThat(mapper.heads).doesNotContainKey("task_budget");
    }

    /** 历史读取只访问指定不可变版本，当前指针或当前版本损坏不能替换原冻结值。 */
    @Test void frozenRevisionReadsOnlyItsExactRowWithoutDependingOnTheCurrentHead(){
        var mapper=org.mockito.Mockito.spy(new InMemoryMapper());var service=service(mapper);
        var original=service.read("task_budget");var changed=new LinkedHashMap<>(original.values());
        changed.put("standard_root_business_models",21);service.update("task_budget",1,changed,7);
        mapper.heads.remove("task_budget");mapper.versions.get("task_budget").remove(2L);
        org.mockito.Mockito.clearInvocations(mapper);
        assertThat(service.readRevision("task_budget",1)).isEqualTo(original);
        org.mockito.Mockito.verify(mapper).selectVersion("task_budget",1);
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        assertThatThrownBy(()->service.readRevision("task_budget",2)).isInstanceOfSatisfying(
                com.skillforge.core.shared.exception.ApiException.class,failure->assertThat(failure.getStatus().value()).isEqualTo(404));
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).selectCurrent(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void workspaceLifecycleDefaultsAreSeparateAndBounded() {
        var group = REGISTRY.require("workspace");
        assertThat(group.defaults()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "enabled", false, "checkpoint_interval_seconds", 300L,
                "local_idle_hours", 24L, "snapshot_history", 10L));
        assertThat(group.fields()).doesNotContainKeys("execution_timeout_seconds", "memory_limit", "cpu_limit");
        assertThat(service(new InMemoryMapper()).read("workspace").bool("enabled")).isFalse();
        for (Map<String, Object> change : List.<Map<String, Object>>of(Map.of("checkpoint_interval_seconds", 0),
                Map.of("local_idle_hours", 0), Map.of("snapshot_history", 101), Map.of("enabled", "true"))) {
            var values = new LinkedHashMap<>(group.defaults());
            values.putAll(change);
            assertThatThrownBy(() -> OperationalSettingsRegistry.normalize(group, values, null))
                    .isInstanceOf(OperationalSettingsRegistry.InvalidSettingsException.class);
        }
    }

    @Test
    void onlyTaskQuotaIsEditableAndRemovedGroupsAreRejected() {
        var service = service(new InMemoryMapper());
        var budget = service.get("task_budget");
        assertThat(budget.values()).hasSize(15);
        assertThat(budget.derivedBudgets()).hasSize(6);
        assertThatThrownBy(() -> service.get("context")).hasMessageContaining("未知运营配置分组");
        assertThatThrownBy(() -> service.get("run_budget")).hasMessageContaining("未知运营配置分组");
        assertThat(service.get("memory").values()).doesNotContainKey("profile_summary_batch_size");
        assertThat(service.get("sandbox").values()).doesNotContainKeys("docker_control_timeout_seconds", "workspace_cleanup_interval_minutes");
        assertThat(service.get("artifacts").values()).doesNotContainKey("cleanup_interval_minutes");
        assertThat(service.get("knowledge").values()).doesNotContainKeys("embedding_timeout_seconds", "rerank_timeout_seconds");
    }

    @Test
    void removedKnowledgeTimeoutFieldsCannotOverrideTheFrozenModelPolicy() {
        var knowledge = REGISTRY.require("knowledge");
        assertThat(knowledge.defaults()).hasSize(9);
        for (var removed : List.of("embedding_timeout_seconds", "rerank_timeout_seconds")) {
            var values = new LinkedHashMap<>(knowledge.defaults());
            values.put(removed, 120L);
            assertThatThrownBy(() -> OperationalSettingsRegistry.normalize(knowledge, values, null))
                    .isInstanceOf(OperationalSettingsRegistry.InvalidSettingsException.class)
                    .hasMessageContaining(removed);
        }
    }

    @Test
    void retiredKnowledgeHistoryExposesOnlyMetadataAndCannotBeReadOrRolledBack() throws Exception {
        var mapper = org.mockito.Mockito.spy(new InMemoryMapper());
        var service = service(mapper);
        var supported = REGISTRY.require("knowledge").defaults();
        var retiredValues = new LinkedHashMap<>(supported);
        retiredValues.put("embedding_timeout_seconds", 47L);
        retiredValues.put("rerank_timeout_seconds", 19L);
        var oldRow = mapper.selectVersion("knowledge", 1);
        oldRow.setValues(Map.copyOf(retiredValues));
        oldRow.setActorId(7L);
        var originalTime = oldRow.getCreatedAt();
        mapper.insertVersion("knowledge", 2, supported, null);
        mapper.heads.get("knowledge").setCurrentRevision(2);
        org.mockito.Mockito.clearInvocations(mapper);

        var history = service.history("knowledge");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).contractStatus()).isEqualTo(OperationalSettingsService.VersionContractStatus.SUPPORTED);
        assertThat(history.get(0).values()).isEqualTo(supported);
        var retired = history.get(1);
        assertThat(retired.revision()).isEqualTo(1);
        assertThat(retired.createdAt()).isEqualTo(originalTime);
        assertThat(retired.actorId()).isEqualTo("7");
        assertThat(retired.contractStatus()).isEqualTo(OperationalSettingsService.VersionContractStatus.RETIRED);
        assertThat(retired.values()).isEmpty();
        var wire = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(retired);
        assertThat(wire.path("contract_status").asText()).isEqualTo("RETIRED");
        assertThat(wire.has("contractStatus")).isFalse();
        assertThat(wire.path("values").size()).isZero();

        for (Runnable rejected : List.<Runnable>of(() -> service.readRevision("knowledge", 1),
                () -> service.rollback("knowledge", 2, 1, 7))) {
            assertThatThrownBy(rejected::run).isInstanceOfSatisfying(
                    com.skillforge.core.shared.exception.ApiException.class, failure -> {
                        assertThat(failure.getStatus().value()).isEqualTo(409);
                        assertThat(failure.getCode()).isEqualTo("knowledge_settings_revision_retired");
                        assertThat(failure.isRetryable()).isFalse();
                    });
        }
        assertThat(service.read("knowledge").values()).isEqualTo(supported);
        assertThat(mapper.selectVersion("knowledge", 1).getValues()).isEqualTo(retiredValues);
        assertThat(mapper.selectCurrent("knowledge").getCurrentRevision()).isEqualTo(2);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertVersion(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).compareAndSetHead(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(mapper.auditCount).isZero();
    }

    @Test
    void otherMalformedKnowledgeHistoryIsNotMisclassifiedAsRetired() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var supported = REGISTRY.require("knowledge").defaults();
        var corrupt = new LinkedHashMap<>(supported);
        corrupt.put("unrecognized_private_field", "must-not-be-exposed");
        mapper.selectVersion("knowledge", 1).setValues(Map.copyOf(corrupt));
        mapper.insertVersion("knowledge", 2, supported, null);
        mapper.heads.get("knowledge").setCurrentRevision(2);
        assertThatThrownBy(() -> service.history("knowledge"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("快照字段不符合当前契约");
        assertThatThrownBy(() -> service.readRevision("knowledge", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("快照字段不符合当前契约");
    }

    @Test
    void updatesAndRollsBackByCreatingImmutableSnapshots() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService service = service(mapper);
        var original = service.read("task_budget");
        Map<String, Object> changed = new LinkedHashMap<>(original.values());
        changed.put("standard_root_business_models", 20);

        var updated = service.update("task_budget", original.revision(), changed, 7);

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(original.integer("standard_root_business_models")).isEqualTo(256);
        assertThat(service.read("task_budget").integer("standard_root_business_models")).isEqualTo(20);
        assertThat(service.readRevision("task_budget", 1).integer("standard_root_business_models")).isEqualTo(256);

        var rolledBack = service.rollback("task_budget", 2, 1, 7);

        assertThat(rolledBack.revision()).isEqualTo(3);
        assertThat(service.read("task_budget").integer("standard_root_business_models")).isEqualTo(256);
        assertThat(service.history("task_budget")).extracting(
                OperationalSettingsService.VersionView::revision).containsExactly(3L, 2L, 1L);
        assertThat(mapper.auditCount).isEqualTo(2);
    }

    @Test
    void encryptsSecretsAndNeverReturnsThemToManagementViews() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService service = service(mapper);
        var original = service.read("billing");
        Map<String, Object> changed = new LinkedHashMap<>(original.values());
        changed.put("exchange_rate_api_key", "top-secret");

        service.update("billing", 1, changed, 7);

        assertThat(service.read("billing").text("exchange_rate_api_key")).isEqualTo("top-secret");
        assertThat(service.get("billing").values()).doesNotContainKeys(
                "exchange_rate_api_key", "usage_reconciliation_api_key");
        assertThat(service.history("billing").getFirst().values()).doesNotContainKeys(
                "exchange_rate_api_key", "usage_reconciliation_api_key");
        assertThat(mapper.selectVersion("billing", 2).getValues().get("exchange_rate_api_key"))
                .isInstanceOf(Map.class).asString().doesNotContain("top-secret");
    }

    @Test
    void rejectsUnknownFieldsBudgetRelationsAndPromptVariables() {
        OperationalSettingsRegistry.GroupDefinition budget =
                REGISTRY.require("task_budget");
        Map<String, Object> invalidBudget = new LinkedHashMap<>(budget.defaults());
        invalidBudget.put("max_input_tokens", 1L);

        assertThatThrownBy(() -> OperationalSettingsRegistry.normalize(
                budget, invalidBudget, null)).hasMessageContaining("max_input_tokens");

        OperationalSettingsRegistry.GroupDefinition prompts =
                REGISTRY.require("prompts");
        Map<String, Object> invalidPrompt = new LinkedHashMap<>(prompts.defaults());
        invalidPrompt.put("chat_summary_prompt", "{{tenant_id}}");
        assertThatThrownBy(() -> OperationalSettingsRegistry.normalize(
                prompts, invalidPrompt, null)).hasMessageContaining("模板变量");

        Map<String, Object> supplied = new LinkedHashMap<>(prompts.defaults());
        supplied.put("unknown_key", true);
        assertThatThrownBy(() -> OperationalSettingsRegistry.normalize(
                prompts, supplied, null)).hasMessageContaining("未知配置字段");
    }

    private OperationalSettingsService service(InMemoryMapper mapper) {
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setSecretKey("synthetic-operational-settings-test-key-32");
        var service = new OperationalSettingsService(mapper, new CryptoService(properties), REGISTRY);
        service.afterSingletonsInstantiated();
        return service;
    }

    private static final class InMemoryMapper implements OperationalSettingsMapper {
        private final Map<String, OperationalSettingHeadRow> heads = new LinkedHashMap<>();
        private final Map<String, Map<Long, OperationalSettingVersionRow>> versions =
                new LinkedHashMap<>();
        private int auditCount;

        @Override
        public List<OperationalSettingHeadRow> selectCurrents(List<String> keys) {
            return keys.stream().map(heads::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public List<OperationalSettingVersionRow> selectVersions(List<OperationalSettingHeadRow> captured) {
            return captured.stream().map(head -> selectVersion(head.getGroupKey(), head.getCurrentRevision()))
                    .filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public OperationalSettingHeadRow selectCurrent(String groupKey) {
            return heads.get(groupKey);
        }
        @Override
        public OperationalSettingHeadRow lockCurrentForShare(String groupKey) { return heads.get(groupKey); }

        @Override
        public OperationalSettingVersionRow selectVersion(String groupKey, long revision) {
            return versions.getOrDefault(groupKey, Map.of()).get(revision);
        }

        @Override
        public List<OperationalSettingVersionRow> selectHistory(String groupKey) {
            List<OperationalSettingVersionRow> rows = new ArrayList<>(
                    versions.getOrDefault(groupKey, Map.of()).values());
            rows.sort(Comparator.comparingLong(OperationalSettingVersionRow::getRevision).reversed());
            return rows;
        }

        @Override
        public int insertVersion(String groupKey, long revision,
                Map<String, Object> values, Long actorId) {
            Map<Long, OperationalSettingVersionRow> group = versions.computeIfAbsent(
                    groupKey, ignored -> new LinkedHashMap<>());
            if (group.containsKey(revision)) {
                return 0;
            }
            OperationalSettingVersionRow row = new OperationalSettingVersionRow();
            row.setGroupKey(groupKey);
            row.setRevision(revision);
            row.setValues(Map.copyOf(values));
            row.setActorId(actorId);
            row.setCreatedAt(Instant.now());
            group.put(revision, row);
            return 1;
        }

        @Override
        public int insertHead(String groupKey, long revision, Long actorId) {
            if (heads.containsKey(groupKey)) {
                return 0;
            }
            OperationalSettingHeadRow row = new OperationalSettingHeadRow();
            row.setGroupKey(groupKey);
            row.setCurrentRevision(revision);
            row.setUpdatedBy(actorId);
            row.setUpdatedAt(Instant.now());
            heads.put(groupKey, row);
            return 1;
        }

        @Override
        public int compareAndSetHead(
                String groupKey, long expectedRevision, long newRevision, long actorId) {
            OperationalSettingHeadRow head = heads.get(groupKey);
            if (head == null || head.getCurrentRevision() != expectedRevision) {
                return 0;
            }
            head.setCurrentRevision(newRevision);
            head.setUpdatedBy(actorId);
            head.setUpdatedAt(Instant.now());
            return 1;
        }

        @Override
        public int insertAudit(String groupKey, String operation, long fromRevision,
                long toRevision, Long targetRevision, long actorId, Map<String, Object> payload) {
            auditCount++;
            return 1;
        }
    }
}
