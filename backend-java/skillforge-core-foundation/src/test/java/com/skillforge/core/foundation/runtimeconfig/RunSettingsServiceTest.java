package com.skillforge.core.foundation.runtimeconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 全部运行配置随 Run 冻结，新动作和恢复保留原发布配置。 */
class RunSettingsServiceTest {
    private static final List<String> CAPTURED_GROUPS = List.of(
            "prompts", "calls", "sandbox", "artifacts", "task_budget", "workspace");

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void repeatedCaptureReturnsOriginalSnapshotAndReadsTheFrozenRevision() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        Map<String, Long> revisions = new LinkedHashMap<>();
        long revision = 11;
        for (String group : CAPTURED_GROUPS) {
            revisions.put(group, revision);
            when(settings.read(group)).thenReturn(
                    new Snapshot(revision, Map.of("marker", revision)),
                    new Snapshot(revision + 100, Map.of("marker", revision + 100)));
            revision++;
        }
        RunSettingsService service = new RunSettingsService(mapper, settings, new ObjectMapper());

        RunSettingsService.FrozenConfig first = service.capture("7", "run-1", "81", 3, AgentExecutionConfig.inherited(), source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()));
        RunSettingsService.FrozenConfig replay = service.capture("7", "run-1", "99", 9, AgentExecutionConfig.inherited(), source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()));

        assertThat(first.modelId()).isEqualTo("81");
        assertThat(first.modelRevision()).isEqualTo(3);
        assertThat(first.groups()).containsAllEntriesOf(revisions).hasSameSizeAs(revisions);
        assertThat(replay).isEqualTo(first);
        CAPTURED_GROUPS.forEach(group -> verify(settings, times(1)).read(group));
        assertThat(service.requireFrozen("7", "run-1")).isEqualTo(first);

        Snapshot frozenContext = new Snapshot(15, Map.of("standard_root_business_models", 2048L));
        when(settings.readRevision("task_budget", 15)).thenReturn(frozenContext);

        assertThat(service.read("7", "run-1", "task_budget")).isSameAs(frozenContext);
        verify(settings).readRevision("task_budget", 15);
        assertThat(mapper.boundTenants).containsExactly("7", "7", "7", "7");
    }

    /** 同次准备直接复用已取值；下一Run重新捕获，持久材料与恢复入口均不夹带临时值。 */
    @Test void captureReusesOnlyItsOwnSnapshotsAndPersistsRevisionsWithoutTransientValues() throws Exception {
        var mapper=new InMemoryMapper();var settings=mock(OperationalSettingsService.class);
        var firstValue=new Snapshot(1,Map.of("marker","first-run-only"));
        var nextValue=new Snapshot(2,Map.of("marker","next-run-only"));
        for(String group:CAPTURED_GROUPS)when(settings.read(group)).thenReturn(firstValue,nextValue);
        var json=new ObjectMapper();var service=new RunSettingsService(mapper,settings,json);
        var first=service.capture("7","run-1","81",3,AgentExecutionConfig.inherited(),source->{
            assertThat(service.read(source,"task_budget")).isSameAs(firstValue);
            assertThat(service.read(source,"task_budget")).isSameAs(firstValue);
            assertThatThrownBy(()->source.snapshots().put("other",firstValue)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(()->service.read(source,"missing")).hasMessage("run_settings_group_missing");
            return new com.skillforge.runtime.api.ResultRef("profile-1");
        });
        assertThat(json.readTree(mapper.snapshots.get("7:run-1")).has("snapshots")).isFalse();
        assertThat(mapper.snapshots.get("7:run-1")).doesNotContain("first-run-only");
        var next=service.capture("7","run-2","81",3,AgentExecutionConfig.inherited(),source->{
            assertThat(service.read(source,"task_budget")).isSameAs(nextValue);
            return new com.skillforge.runtime.api.ResultRef("profile-2");
        });
        assertThat(first.groups()).containsEntry("task_budget",1L);assertThat(next.groups()).containsEntry("task_budget",2L);
        CAPTURED_GROUPS.forEach(group->verify(settings,times(2)).read(group));
        org.mockito.Mockito.verify(settings,org.mockito.Mockito.never()).readRevision(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());
        when(settings.readRevision("task_budget",1)).thenReturn(firstValue);
        assertThat(service.read(first,"task_budget")).isSameAs(firstValue);
        verify(settings).readRevision("task_budget",1);
        assertThatThrownBy(()->new RunSettingsService.CaptureConfig("81",3,Map.of("task_budget",2L),
                AgentExecutionConfig.inherited(),Map.of("task_budget",firstValue))).hasMessage("run_settings_capture_snapshot_mismatch");
    }

    @Test
    void newOperationsKeepAllRunSettingsAndNextRunAdoptsPublishedChanges() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        for (String group : CAPTURED_GROUPS) {
            when(settings.read(group)).thenReturn(new Snapshot(1, Map.of()), new Snapshot(2, Map.of()), new Snapshot(3, Map.of()));
        }
        RunSettingsService service = new RunSettingsService(mapper, settings, new ObjectMapper());
        var executionConfig = new AgentExecutionConfig(3, 4, 2, null, 90L);
        service.capture("7", "run", "81", 2, executionConfig, source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()));
        var first = service.captureOperation("7", "run", "model:1", "81", 3);
        var second = service.captureOperation("7", "run", "model:2", "81", 4);
        var rebuilt = new RunSettingsService(mapper, settings, new ObjectMapper());
        var retry = rebuilt.captureOperation("7", "run", "model:1", "81", 4);
        assertThat(first.modelRevision()).isEqualTo(2);
        assertThat(second.modelRevision()).isEqualTo(2);
        assertThat(first.executionConfig()).isEqualTo(executionConfig);
        assertThat(second.executionConfig()).isEqualTo(executionConfig);
        assertThat(first.groups()).containsEntry("task_budget", 1L);
        assertThat(second.groups()).containsEntry("task_budget", 1L);
        assertThat(retry).isEqualTo(first);
        verify(settings, times(1)).read("task_budget");
        CAPTURED_GROUPS.stream().filter(group -> !group.equals("task_budget"))
                .forEach(group -> verify(settings, times(1)).read(group));
        var next = rebuilt.capture("7", "next-run", "81", 4, executionConfig, source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()));
        assertThat(next.modelRevision()).isEqualTo(4);
        CAPTURED_GROUPS.forEach(group -> assertThat(next.groups()).containsEntry(group, 2L));
    }

    @Test
    void teamOperationsKeepTheRunConfigurationAcrossNewActionsAndRecovery() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        for (String group : CAPTURED_GROUPS) {
            when(settings.read(group)).thenReturn(new Snapshot(1, Map.of()), new Snapshot(2, Map.of()));
        }
        RunSettingsService service = new RunSettingsService(mapper, settings, new ObjectMapper());
        var original = service.capture("7", "team-run", "81", 3, AgentExecutionConfig.inherited(), source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()));
        var first = service.captureFrozenOperation("7", "team-run", "model:1", "任务角色");
        var second = service.captureFrozenOperation("7", "team-run", "model:2", "任务角色");
        var rebuilt = new RunSettingsService(mapper, settings, new ObjectMapper());
        assertThat(rebuilt.captureFrozenOperation("7", "team-run", "model:1", "任务角色")).isEqualTo(first);
        assertThat(second).isEqualTo(first);
        assertThat(first.modelId()).isEqualTo(original.modelId());
        assertThat(first.modelRevision()).isEqualTo(original.modelRevision());
        assertThat(first.groups()).isEqualTo(original.groups());
        assertThat(service.findOperation("7", "team-run", "model:1")).contains(first);
        CAPTURED_GROUPS.forEach(group -> verify(settings, times(1)).read(group));
        assertThatThrownBy(() -> rebuilt.captureFrozenOperation("7", "team-run", "model:1", "其他角色"))
                .hasMessage("operation_settings_snapshot_mismatch");
    }

    @Test
    void crossTenantCaptureIsRejectedBeforeStorageOrSettingsAccess() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        RunSettingsService service = new RunSettingsService(mapper, settings, new ObjectMapper());
        TenantContext.setTenantId("7");

        assertThatThrownBy(() -> service.capture("8", "run-1", "81", 3, AgentExecutionConfig.inherited(), source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("tenant_scope_mismatch");

        assertThat(mapper.boundTenants).isEmpty();
        assertThat(mapper.snapshots).isEmpty();
        verifyNoInteractions(settings);
    }

    @Test
    void agentBudgetIsFrozenWithTheRunAndItsRecoveredOperations() {
        InMemoryMapper mapper = new InMemoryMapper();
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        for (String group : CAPTURED_GROUPS) when(settings.read(group)).thenReturn(new Snapshot(1, Map.of()));
        var config = new AgentExecutionConfig(3, 4, 2, null, 90L);
        var changed = new AgentExecutionConfig(8, 9, 4, null, 180L);
        var service = new RunSettingsService(mapper, settings, new ObjectMapper());
        var first = service.capture("7", "agent-run", "81", 3, config, source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()));
        var rebuilt = new RunSettingsService(mapper, settings, new ObjectMapper());
        assertThat(rebuilt.capture("7", "agent-run", "99", 5, changed, source -> new com.skillforge.runtime.api.ResultRef("profile-" + source.modelRevision()))).isEqualTo(first);
        assertThat(rebuilt.captureFrozenOperation("7", "agent-run", "action-1", "role").executionConfig()).isEqualTo(config);
        assertThat(first.preparationProfile()).isEqualTo("profile-3");
        assertThat(rebuilt.captureFrozenOperation("7", "agent-run", "action-2", "role").preparationProfile())
                .isEqualTo(first.preparationProfile());

    }

    @Test
    void incompleteFreezeNeverPublishesAndOldFormatIsRejected() {
        var mapper = new InMemoryMapper();
        var settings = mock(OperationalSettingsService.class);
        for (String group : CAPTURED_GROUPS) when(settings.read(group)).thenReturn(new Snapshot(1, Map.of()));
        var service = new RunSettingsService(mapper, settings, new ObjectMapper());
        assertThatThrownBy(() -> service.capture("7", "run", "81", 3, AgentExecutionConfig.inherited(), source -> {
            throw new IllegalStateException("preparation_failed");
        })).hasMessage("preparation_failed");
        assertThat(mapper.snapshots).isEmpty();
        mapper.snapshots.put("7:old", "{\"modelId\":\"81\",\"modelRevision\":3,\"groups\":{},\"executionConfig\":{}}");
        assertThatThrownBy(() -> service.requireFrozen("7", "old")).hasMessage("run_settings_snapshot_invalid");
    }

    /** 精确冻结读取拒绝旧覆盖key，不用当前配置重建或把旧显式null静默解释为继承。 */
    @Test void frozenBudgetUsesBusinessCallNameAndRejectsOldKeysWithoutRewritingOriginalMaterial() {
        var mapper=new InMemoryMapper();var settings=mock(OperationalSettingsService.class);
        var json=new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var service=new RunSettingsService(mapper,settings,json);
        String prefix="{\"modelId\":\"81\",\"modelRevision\":3,\"groups\":{\"task_budget\":9},\"roleInstructions\":null,\"executionConfig\":";
        String suffix=",\"preparationProfile\":\"profile:9\"}";
        mapper.snapshots.put("7:explicit",prefix+"{\"max_business_model_calls\":3}"+suffix);
        mapper.snapshots.put("7:inherited",prefix+"{}"+suffix);
        assertThat(service.requireFrozen("7","explicit").executionConfig().maxBusinessModelCalls()).isEqualTo(3);
        assertThat(service.requireFrozen("7","inherited").executionConfig()).isEqualTo(AgentExecutionConfig.inherited());
        for(String old:List.of("{\"max_iterations\":3}","{\"max_iterations\":null}")) {
            String original=prefix+old+suffix;mapper.snapshots.put("7:old-budget",original);
            assertThatThrownBy(()->service.requireFrozen("7","old-budget")).hasMessage("run_settings_snapshot_invalid");
            assertThat(mapper.snapshots.get("7:old-budget")).isEqualTo(original);
        }
        verifyNoInteractions(settings);
    }

    private static final class InMemoryMapper implements RunSettingsMapper {
        private final Map<String, String> snapshots = new LinkedHashMap<>();
        private final List<String> boundTenants = new ArrayList<>();

        @Override
        public String bindTenant(String tenant) {
            boundTenants.add(tenant);
            return tenant;
        }

        @Override
        public String find(String tenant, String run) {
            return snapshots.get(tenant + ":" + run);
        }

        @Override
        public int insert(String tenant, String run, String snapshot) {
            return snapshots.putIfAbsent(tenant + ":" + run, snapshot) == null ? 1 : 0;
        }

        @Override
        public String findOperation(String tenant, String run, String operation) {
            return snapshots.get(tenant + ":" + run + ":" + operation);
        }

        @Override
        public int insertOperation(String tenant, String run, String operation, String snapshot) {
            return snapshots.putIfAbsent(tenant + ":" + run + ":" + operation, snapshot) == null ? 1 : 0;
        }
    }
}
