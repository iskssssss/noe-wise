package com.skillforge.core.foundation.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** 从实际配置注册表装配预算，验证角色额度、长任务校验和显式限制。 */
class ChatBudgetConfigurationTest {
    private static final OperationalSettingsRegistry REGISTRY = new OperationalSettingsRegistry(java.util.List.of(new com.skillforge.core.foundation.runtimeconfig.RuntimeBudgetOperationalSettings()));
    private static final long REVISION = 7;

    @Test
    void recommendedRootDefaultsKeepExplicitPrimaryAndMemberShares() {
        var definition = REGISTRY.require(ChatBudgetSettings.GROUP);
        var values = OperationalSettingsRegistry.normalize(definition, definition.defaults(), null);
        var snapshot = new OperationalSettingsService.Snapshot(REVISION, values);
        var standard = resources(256, 128, 1_024, 256, 8_000_000);
        var delegation = resources(1_024, 512, 8_192, 1_024, 32_000_000);
        var team = resources(2_048, 1_024, 16_384, 2_048, 64_000_000);
        for (var expected : java.util.List.of(
                new Expected(ExecutionBudgetPort.Scenario.STANDARD, ExecutionBudgetPort.Role.PRIMARY,
                        standard, resources(128, 128, 512, 128, 6_400_000), 7_200, 7_200),
                new Expected(ExecutionBudgetPort.Scenario.STANDARD, ExecutionBudgetPort.Role.MEMBER,
                        standard, resources(128, 128, 1_024, 256, 3_200_000), 7_200, 7_200),
                new Expected(ExecutionBudgetPort.Scenario.DELEGATION, ExecutionBudgetPort.Role.PRIMARY,
                        delegation, resources(512, 512, 8_192, 1_024, 25_600_000), 14_400, 14_400),
                new Expected(ExecutionBudgetPort.Scenario.DELEGATION, ExecutionBudgetPort.Role.MEMBER,
                        delegation, resources(256, 256, 2_048, 256, 6_400_000), 14_400, 7_200),
                new Expected(ExecutionBudgetPort.Scenario.TEAM, ExecutionBudgetPort.Role.PRIMARY,
                        team, resources(512, 512, 8_192, 1_024, 25_600_000), 28_800, 28_800),
                new Expected(ExecutionBudgetPort.Scenario.TEAM, ExecutionBudgetPort.Role.MEMBER,
                        team, resources(256, 256, 2_048, 256, 6_400_000), 28_800, 9_600))) {
            var actual = ChatBudgetSettings.freeze(snapshot, expected.scenario(), expected.role(),
                    AgentExecutionConfig.inherited());
            assertEquals(expected.root(), actual.root(), expected.toString());
            assertEquals(expected.run(), actual.run(), expected.toString());
            assertEquals(expected.rootSeconds(), actual.rootActiveSeconds());
            assertEquals(expected.runSeconds(), actual.runActiveSeconds());
            assertTrue(actual.stage().fits(actual.run()));
            assertEquals("budget-policy.v4:task_budget:7", actual.sourceVersion());
        }
    }

    @Test
    void maintenanceRemainsIndependentOfTheOperationalRootProfiles() {
        var snapshot = new OperationalSettingsService.Snapshot(REVISION, java.util.Map.of());
        for (var role : ExecutionBudgetPort.Role.values()) {
            var limits = ChatBudgetSettings.freeze(snapshot, ExecutionBudgetPort.Scenario.MAINTENANCE,
                    role, AgentExecutionConfig.inherited());
            assertEquals(resources(0, 1_024, 0, 0, 20_000_000), limits.root());
            assertEquals(limits.root(), limits.run());
            assertEquals(43_200, limits.rootActiveSeconds());
            assertEquals(43_200, limits.runActiveSeconds());
            assertEquals("budget-policy.v4:maintenance", limits.sourceVersion());
        }
    }

    @Test
    void customPublishedRootValuesKeepTheSameSharesWithoutChangingTheirSnapshot() {
        var values = new LinkedHashMap<>(REGISTRY.require("task_budget").defaults());
        values.put("team_root_business_models", 80L);
        values.put("team_root_tools", 800L);
        values.put("team_root_knowledge", 80L);
        values.put("team_root_total_tokens", 1_000_000L);
        values.put("team_root_active_seconds", 300L);
        var snapshot = new OperationalSettingsService.Snapshot(19, values);
        var primary = ChatBudgetSettings.freeze(snapshot, ExecutionBudgetPort.Scenario.TEAM,
                ExecutionBudgetPort.Role.PRIMARY, AgentExecutionConfig.inherited());
        var member = ChatBudgetSettings.freezeMember(primary, AgentExecutionConfig.inherited());
        assertEquals(resources(80, 40, 800, 80, 1_000_000), primary.root());
        assertEquals(resources(20, 20, 400, 40, 400_000), primary.run());
        assertEquals(resources(10, 10, 100, 10, 100_000), member.run());
        assertEquals(100, member.runActiveSeconds());
        assertEquals(snapshot.values(), values);
        assertEquals("budget-policy.v4:task_budget:19", member.sourceVersion());
    }

    @Test
    void onlyFifteenFieldsAndSevenDayQuotaAreAccepted() {
        var budget = REGISTRY.require("task_budget");
        assertEquals(15, budget.defaults().size());
        var configured = new LinkedHashMap<>(budget.defaults());
        configured.put("standard_root_active_seconds", ChatBudgetSettings.MAX_ACTIVE_SECONDS);
        assertDoesNotThrow(() -> OperationalSettingsRegistry.normalize(budget, configured, null));
        configured.put("standard_root_active_seconds", ChatBudgetSettings.MAX_ACTIVE_SECONDS + 1);
        assertThrows(OperationalSettingsRegistry.InvalidSettingsException.class,
                () -> OperationalSettingsRegistry.normalize(budget, configured, null));
    }

    @Test
    void scalesSmallRootsAndRejectsExplicitOvershootWithoutMultiplicationOverflow() {
        var values = new LinkedHashMap<>(REGISTRY.require("task_budget").defaults());
        for (var p : ChatBudgetSettings.Profile.values()) {
            for (var d : ChatBudgetSettings.editableDimensions()) values.put(p.field(d), 1L);
            values.put(p.activeKey(), 1L);
        }
        var snapshot = new OperationalSettingsService.Snapshot(7, values);
        var limit = ChatBudgetSettings.freeze(snapshot, ExecutionBudgetPort.Scenario.TEAM,
                ExecutionBudgetPort.Role.PRIMARY, AgentExecutionConfig.inherited());
        assertEquals(1, limit.run().businessModels());
        assertEquals(1, limit.run().totalTokens());
        assertEquals(limit.run(), limit.stage());
        assertThrows(IllegalArgumentException.class, () -> ChatBudgetSettings.freezeMember(limit,
                new AgentExecutionConfig(2, null, null, null, null)));
        values.put("team_root_total_tokens", Long.MAX_VALUE);
        var huge = ChatBudgetSettings.freeze(new OperationalSettingsService.Snapshot(8, values),
                ExecutionBudgetPort.Scenario.TEAM, ExecutionBudgetPort.Role.PRIMARY, AgentExecutionConfig.inherited());
        assertTrue(huge.run().totalTokens() > Long.MAX_VALUE / 4);
        assertTrue(huge.run().fits(huge.root()));
    }

    @Test
    void membersUseFrozenParentAndInputOutputEachMayUseTheWholeTokenQuota() {
        var values = new LinkedHashMap<>(REGISTRY.require("task_budget").defaults());
        values.put("delegation_root_total_tokens", 400_000_000L);
        var root = ChatBudgetSettings.freeze(new OperationalSettingsService.Snapshot(7, values),
                ExecutionBudgetPort.Scenario.DELEGATION, ExecutionBudgetPort.Role.PRIMARY, AgentExecutionConfig.inherited());
        var member = ChatBudgetSettings.freezeMember(root, AgentExecutionConfig.inherited());
        assertEquals(400_000_000, member.root().totalTokens());
        assertEquals(80_000_000, member.run().totalTokens());
        assertEquals(member.run().totalTokens(), member.run().inputTokens());
        assertEquals(member.run().totalTokens(), member.run().outputTokens());
        assertTrue(new ExecutionBudgetPort.Resources(1, 0, 0, 0, 1_000_000, 60_000_000, 61_000_000).fits(member.run()));
        assertTrue(new ExecutionBudgetPort.Resources(1, 0, 0, 0, 70_000_000, 1_000_000, 71_000_000).fits(member.run()));
    }

    @Test
    void deletedAgentFieldsAreRejectedEvenWithPermissiveJackson() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.readValue("{\"max_input_tokens\":100}", AgentExecutionConfig.class));
        for(String old:java.util.List.of("{\"max_iterations\":1}","{\"max_iterations\":null}","{\"max_iterations\":1,\"max_business_model_calls\":2}"))
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue(old,AgentExecutionConfig.class));
        assertEquals(new AgentExecutionConfig(1, null, null, 5L, null),
                json.readValue("{\"max_business_model_calls\":1,\"max_total_tokens\":5}", AgentExecutionConfig.class));
        assertEquals(AgentExecutionConfig.inherited(),json.readValue("{}",AgentExecutionConfig.class));
        assertEquals(AgentExecutionConfig.inherited(),json.readValue("{\"max_business_model_calls\":null}",AgentExecutionConfig.class));
        var encoded=json.readTree(json.writeValueAsString(new AgentExecutionConfig(3,null,null,null,null)));
        assertEquals(3,encoded.path("max_business_model_calls").asInt());assertFalse(encoded.has("max_iterations"));
        assertEquals(java.util.Map.of("max_business_model_calls",3),new AgentExecutionConfig(3,null,null,null,null).overrides());
    }

    /** 辅助调用不推进业务模型循环，不能用两种维度的合计制造本地int接口溢出。 */
    @Test void independentBusinessAndAuxiliaryQuotasDoNotShareLocalLoopCapacity() {
        var values=new LinkedHashMap<>(REGISTRY.require("task_budget").defaults());
        values.put("standard_root_business_models",Integer.MAX_VALUE);
        var limits=ChatBudgetSettings.freeze(new OperationalSettingsService.Snapshot(9,values),
                ExecutionBudgetPort.Scenario.STANDARD,ExecutionBudgetPort.Role.PRIMARY,
                new AgentExecutionConfig(Integer.MAX_VALUE,null,null,null,null));
        assertEquals(Integer.MAX_VALUE,limits.run().businessModels());
        assertTrue(limits.run().auxiliaryModels()>0);
        assertTrue(limits.run().businessModels()+limits.run().auxiliaryModels()>Integer.MAX_VALUE);
    }

    @Test
    void explicitAgentLimitsRemainExplicitAfterThePlatformDefaultsChange() {
        var snapshot = new OperationalSettingsService.Snapshot(REVISION,
                REGISTRY.require(ChatBudgetSettings.GROUP).defaults());
        var explicit = new AgentExecutionConfig(3, 4, 2, 1_500L, 90L);
        var frozen = ChatBudgetSettings.freeze(snapshot, ExecutionBudgetPort.Scenario.STANDARD,
                ExecutionBudgetPort.Role.PRIMARY, explicit);
        assertEquals(3, frozen.run().businessModels());
        assertEquals(4, frozen.run().tools());
        assertEquals(1_500, frozen.run().totalTokens());
        assertEquals(90, frozen.runActiveSeconds());
        assertEquals(8_000_000, frozen.root().totalTokens());
    }

    private record Expected(ExecutionBudgetPort.Scenario scenario, ExecutionBudgetPort.Role role,
            ExecutionBudgetPort.Resources root, ExecutionBudgetPort.Resources run, long rootSeconds, long runSeconds) { }

    private static ExecutionBudgetPort.Resources resources(long business, long auxiliary, long tools, long knowledge, long total) {
        return new ExecutionBudgetPort.Resources(business, auxiliary, tools, knowledge, total, total, total);
    }
}
