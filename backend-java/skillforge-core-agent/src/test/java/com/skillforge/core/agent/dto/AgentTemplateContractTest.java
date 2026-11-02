package com.skillforge.core.agent.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.dto.request.SkillMountConfigRequest;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.agent.model.AgentTemplateDefinitionJsonbTypeHandler;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.model.PlatformModelCatalogService;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Agent 模板完整定义的序列化与发布校验契约。 */
class AgentTemplateContractTest {

    /** 精确策略枚举进入真实 JSON 边界，未知值和显式清除均不能降级成开放默认。 */
    @Test
    void distributionPolicyRequestRejectsUnknownAndExplicitNullValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var valid = mapper.readValue("{\"name\":\"定义\",\"distribution_policy\":\"AUTHORIZED_DEPLOY\"}",
                com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest.class);
        assertThat(valid.distributionPolicy())
                .isEqualTo(com.skillforge.core.agent.model.AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        assertThatThrownBy(() -> mapper.readValue("{\"name\":\"定义\",\"distribution_policy\":\"auto\"}",
                com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"distribution_policy\":null}",
                com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void typedDefinitionUsesSnakeCaseAndRoundTrips() throws Exception {
        AgentTemplateDefinition definition = definition(List.of(), List.of(), List.of());
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(definition);

        assertThat(json).contains("\"system_prompt\"", "\"role_description\"", "\"model_config_id\"",
                "\"execution_config\"", "\"resource_slots\"", "\"delegation_slots\"");
        assertThat(mapper.readValue(json, AgentTemplateDefinition.class)).isEqualTo(definition);
    }

    @Test
    void typedDefinitionJsonbHandlerRejectsRemovedArbitraryFields() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("definition")).thenReturn(
                "{\"system_prompt\":\"prompt\",\"strategy\":\"react\"}");

        assertThatThrownBy(() -> new AgentTemplateDefinitionJsonbTypeHandler()
                .getNullableResult(resultSet, "definition"))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("模板定义格式无效");
    }

    /** 模板发布正文与实例更新复用同一预算契约；旧key即使值为null也不能跨持久边界被忽略。 */
    @Test void businessModelBudgetNameIsSharedByUpdateDtoAndPublishedTemplateSnapshots() throws Exception {
        var mapper=new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var request=mapper.readValue("{\"execution_config\":{\"max_business_model_calls\":7}}",
                com.skillforge.core.agent.dto.request.UpdateAgentRequest.class);
        assertThat(request.executionConfig().maxBusinessModelCalls()).isEqualTo(7);
        var entity=new com.skillforge.core.agent.model.AgentDO();entity.setExecutionConfig(request.executionConfig());
        assertThat(entity.getMaxBusinessModelCalls()).isEqualTo(7);assertThat(entity.getExecutionConfig()).isEqualTo(request.executionConfig());
        var definition=new AgentTemplateDefinition("prompt","role","1",request.executionConfig(),List.of(),List.of(),List.of());
        String serialized=mapper.writeValueAsString(definition);
        assertThat(serialized).contains("\"max_business_model_calls\":7").doesNotContain("max_iterations");
        var rows=mock(ResultSet.class);when(rows.getString("definition")).thenReturn(serialized);
        assertThat(new AgentTemplateDefinitionJsonbTypeHandler().getNullableResult(rows,"definition")).isEqualTo(definition);
        for(String old:List.of("{\"max_iterations\":7}","{\"max_iterations\":null}")) {
            assertThatThrownBy(()->mapper.readValue("{\"execution_config\":"+old+"}",com.skillforge.core.agent.dto.request.UpdateAgentRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
            when(rows.getString("definition")).thenReturn("{\"system_prompt\":\"prompt\",\"execution_config\":"+old+"}");
            assertThatThrownBy(()->new AgentTemplateDefinitionJsonbTypeHandler().getNullableResult(rows,"definition"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("模板定义格式无效");
        }
        when(rows.getString("definition")).thenReturn("{\"system_prompt\":\"prompt\",\"execution_config\":{}}");
        assertThat(new AgentTemplateDefinitionJsonbTypeHandler().getNullableResult(rows,"definition").executionConfig())
                .isEqualTo(com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited());
    }

    @Test
    void validationReportsFieldIssueWhenDefaultModelIsAbsent() {
        AgentTemplateService service = service(mock(CapabilityCatalogService.class));

        var result = service.validateDefinition(new AgentTemplateDefinition(
                "prompt", "role", null, null, List.of(), List.of(), List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.path()).isEqualTo("definition.model_config_id");
            assertThat(issue.code()).isEqualTo("required");
        });
    }

    @Test
    void validationRejectsSkillWhoseDeclaredDependencyIsNotMounted() {
        CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
        long skillVersionId = 101L;
        when(catalog.currentPublishedVersions(eq(CapabilityCatalogScope.platform()), eq(List.of(11L))))
                .thenReturn(List.of(version(skillVersionId, 11L, CapabilityType.SKILL,
                        Map.of("activation_mode", "ALWAYS", "required_capabilities", List.of(Map.of(
                                "type", "TOOL", "capability_id", 12L, "version_id", 102L))),
                        "skill-hash")));
        AgentTemplateDefinition.BaseCapability skill = new AgentTemplateDefinition.BaseCapability(
                CapabilityType.SKILL, 11L, skillVersionId,
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1_000L), null, 1);

        var result = service(catalog).validateDefinition(definition(List.of(skill), List.of(), List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.path()).isEqualTo("definition.capabilities[0]");
            assertThat(issue.code()).isEqualTo("skill_dependency_missing");
        });
    }

    @Test
    void validationAcceptsToolOnlyWhenItsPublishedContractMatchesCatalogEntry() {
        CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
        PlatformToolCatalog.Entry entry = platformTools().entries().stream()
                .filter(value -> value.name().equals("get_goal"))
                .findFirst()
                .orElseThrow();
        long versionId = 201L;
        when(catalog.currentPublishedVersions(eq(CapabilityCatalogScope.platform()), eq(List.of(21L))))
                .thenReturn(List.of(version(versionId, 21L, CapabilityType.TOOL,
                        entry.descriptor(), entry.contentHash())));
        AgentTemplateDefinition.BaseCapability tool = new AgentTemplateDefinition.BaseCapability(
                CapabilityType.TOOL, 21L, versionId, null, entry.name(), 1);

        var result = service(catalog).validateDefinition(definition(List.of(tool), List.of(), List.of()));

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void draftResolvesPublishedCapabilityMetadataAndPreservesUserBudgetAndIncompleteSelections() {
        CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
        when(catalog.currentPublishedVersions(eq(CapabilityCatalogScope.platform()), eq(List.of(11L, 21L, 99L))))
                .thenReturn(List.of(version(102L, 11L, CapabilityType.SKILL, Map.of("activation_mode", "ALWAYS"), "skill"),
                        version(202L, 21L, CapabilityType.TOOL, Map.of("tool_name", "current_tool"), "tool")));
        var skill = new AgentTemplateDefinition.BaseCapability(CapabilityType.SKILL, 11L, 101L,
                new SkillMountConfigRequest(SkillActivationMode.ON_DEMAND, 1234L), "old_tool", 1);
        var tool = new AgentTemplateDefinition.BaseCapability(CapabilityType.TOOL, 21L, 201L, null, "old_tool", 2);
        var incomplete = new AgentTemplateDefinition.BaseCapability(CapabilityType.TOOL, 99L, null, null, null, 3);
        var result = service(catalog).resolveDraftDefinition(definition(List.of(skill, tool, incomplete), List.of(), List.of()));
        assertThat(result.capabilities().get(0).capabilityVersionId()).isEqualTo(102L);
        assertThat(result.capabilities().get(0).skillConfig()).isEqualTo(new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1234L));
        assertThat(result.capabilities().get(0).platformToolName()).isNull();
        assertThat(result.capabilities().get(1).capabilityVersionId()).isEqualTo(202L);
        assertThat(result.capabilities().get(1).platformToolName()).isEqualTo("current_tool");
        assertThat(result.capabilities().get(2)).isEqualTo(incomplete);
    }

    private PlatformToolCatalog platformTools() {
        var schemas = mock(com.skillforge.core.capability.connector.ToolSchemaContributor.class);
        when(schemas.codePublicPresentationSchema()).thenReturn(Map.of("type", "object"));
        var context = mock(PlatformToolCatalog.Entry.class);
        when(context.name()).thenReturn("read_context_reference");
        when(schemas.contextReferenceEntry()).thenReturn(context);
        return new PlatformToolCatalog(schemas);
    }

    private AgentTemplateService service(CapabilityCatalogService catalog) {
        return new AgentTemplateService(mock(PlatformModelCatalogService.class), catalog,
                platformTools(), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    }

    private AgentTemplateDefinition definition(
            List<AgentTemplateDefinition.BaseCapability> capabilities,
            List<AgentTemplateDefinition.ResourceSlot> resourceSlots,
            List<AgentTemplateDefinition.DelegationSlot> delegationSlots) {
        return new AgentTemplateDefinition("prompt", "role", "1", null,
                capabilities, resourceSlots, delegationSlots);
    }

    private CapabilityVersionView version(
            long id, long definitionId, CapabilityType type, Map<String, Object> descriptor, String contentHash) {
        return new CapabilityVersionView(id, definitionId, null, type, "v1", "发布版本", "测试版本",
                "PLATFORM", descriptor, contentHash, "PUBLISHED", Instant.EPOCH, Instant.EPOCH);
    }
}
