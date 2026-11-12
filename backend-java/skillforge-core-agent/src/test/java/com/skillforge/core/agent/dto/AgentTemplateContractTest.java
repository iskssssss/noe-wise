package com.skillforge.core.agent.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.exception.ApiException;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

/** Agent 模板完整定义的序列化与发布校验契约。 */
class AgentTemplateContractTest {
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final PlatformModelConfig model = mock(PlatformModelConfig.class);
    private final OperationalSettingsService settings = mock(OperationalSettingsService.class);

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
    void publicTemplateSummaryOmitsPromptAndRetainsPublishedFields() throws Exception {
        var capability = new AgentTemplateDefinition.BaseCapability(CapabilityType.TOOL, 10L, 11L, null, "clock", 1);
        var resource = new AgentTemplateDefinition.ResourceSlot("knowledge", "知识", "可选", CapabilityType.KNOWLEDGE_SOURCE);
        var delegation = new AgentTemplateDefinition.DelegationSlot("review", "审核", "审核角色");
        var definition = new AgentTemplateDefinition("PRIVATE_PROMPT", "公开角色", "81",
                new AgentExecutionConfig(3, 4, 5, 600L, 20L), List.of(capability), List.of(resource), List.of(delegation));
        var item = new com.skillforge.core.agent.dto.response.AgentTemplateItemResponse(
                1L, "key", "名称", "1", "说明", null,
                com.skillforge.core.agent.dto.response.AgentTemplateItemResponse.TemplateSummary.from(definition));
        var json = new ObjectMapper().valueToTree(item).path("definition");

        assertThat(json.has("system_prompt")).isFalse();
        assertThat(json.toString()).doesNotContain("PRIVATE_PROMPT");
        assertThat(json.path("role_description").asText()).isEqualTo("公开角色");
        assertThat(json.path("model_config_id").asText()).isEqualTo("81");
        assertThat(json.path("execution_config").path("max_business_model_calls").asInt()).isEqualTo(3);
        assertThat(json.path("capabilities").get(0).path("platform_tool_name").asText()).isEqualTo("clock");
        assertThat(json.path("resource_slots").get(0).path("key").asText()).isEqualTo("knowledge");
        assertThat(json.path("delegation_slots").get(0).path("key").asText()).isEqualTo("review");
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
        verifyNoInteractions(models, settings);
    }

    @ParameterizedTest
    @CsvSource({"2048,false", "2049,true"})
    void explicitTokenBudgetMustLeaveRoomBeyondModelOutput(long tokens, boolean valid) {
        var service = service(mock(CapabilityCatalogService.class));
        var definition = new AgentTemplateDefinition("prompt", "role", "1",
                new AgentExecutionConfig(null, null, null, tokens, null), List.of(), List.of(), List.of());

        var result = service.validateDefinition(definition);

        assertThat(result.valid()).isEqualTo(valid);
        if (valid) assertThat(result.issues()).isEmpty();
        else assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.path()).isEqualTo("definition.execution_config");
            assertThat(issue.code()).isEqualTo("budget_invalid");
            assertThat(issue.message()).contains("agent_budget_cannot_fit_text_closing:max_total_tokens");
        });
        verify(models).requireEnabled("1", "LLM");
        verifyNoMoreInteractions(models);
    }

    @ParameterizedTest
    @CsvSource({"600,60,60,false", "600,60,61,true", "30,60,30,false", "30,60,31,true"})
    void explicitActiveTimeUsesSmallerModelAndOperationalTimeout(
            int modelTimeout, int callTimeout, long activeSeconds, boolean valid) {
        var service = service(mock(CapabilityCatalogService.class));
        when(model.timeoutSeconds()).thenReturn(modelTimeout);
        when(settings.read("calls")).thenReturn(new OperationalSettingsService.Snapshot(
                7, Map.of("chat_timeout_seconds", callTimeout)));
        var definition = new AgentTemplateDefinition("prompt", "role", "1",
                new AgentExecutionConfig(null, null, null, null, activeSeconds), List.of(), List.of(), List.of());

        var result = service.validateDefinition(definition);

        assertThat(result.valid()).isEqualTo(valid);
        if (valid) assertThat(result.issues()).isEmpty();
        else assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.path()).isEqualTo("definition.execution_config");
            assertThat(issue.code()).isEqualTo("budget_invalid");
            assertThat(issue.message()).contains("agent_budget_cannot_fit_text_closing:max_wall_time_seconds");
        });
        verify(settings).read("calls");
        verifyNoMoreInteractions(settings);
    }

    @Test
    void inheritedBudgetKeepsDefaultExecutionPolicy() {
        var result = service(mock(CapabilityCatalogService.class))
                .validateDefinition(definition(List.of(), List.of(), List.of()));

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void unavailableModelDoesNotHideInvalidBasicBudget() {
        var service = service(mock(CapabilityCatalogService.class));
        when(models.requireEnabled("1", "LLM"))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "model_disabled"));
        var definition = new AgentTemplateDefinition("prompt", "role", "1",
                new AgentExecutionConfig(null, null, null, 0L, null), List.of(), List.of(), List.of());

        var result = service.validateDefinition(definition);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(issue -> issue.path() + ":" + issue.code())
                .containsExactly("definition.model_config_id:model_unavailable", "definition.execution_config:budget_invalid");
        verifyNoInteractions(settings);
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
        when(model.maxOutputTokens()).thenReturn(2048);
        when(model.timeoutSeconds()).thenReturn(120);
        when(models.requireEnabled("1", "LLM")).thenReturn(model);
        when(settings.read("calls")).thenReturn(new OperationalSettingsService.Snapshot(
                1, Map.of("chat_timeout_seconds", 120)));
        return new AgentTemplateService(models, catalog,
                platformTools(), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), settings);
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
