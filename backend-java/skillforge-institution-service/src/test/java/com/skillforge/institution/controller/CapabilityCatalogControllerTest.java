package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.capability.CapabilityRequirementOption;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillPackageCatalogService;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationPermissionInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.dto.capability.CreateCapabilityDefinitionRequest;
import com.skillforge.institution.dto.capability.CreateCapabilityDeploymentRequest;
import com.skillforge.institution.dto.capability.PlatformToolResponse;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 机构 Catalog API 的租户 scope、类型与 If-Match 契约。 */
class CapabilityCatalogControllerTest {

    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final TenantToolDeploymentProviderValidator providerValidator =
            mock(TenantToolDeploymentProviderValidator.class);
    private final CapabilityCatalogController controller =
            new CapabilityCatalogController(catalog, providerValidator,
                    mock(SkillPackageCatalogService.class), new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()));

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId("7");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private MockMvc requirementMvc(FoundationAccess access) {
        var json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new FoundationPermissionInterceptor(access))
                .setControllerAdvice(new com.skillforge.institution.exception.GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    @Test
    void versionSummariesUseAuthenticatedScopeAndReturnOnlyFiveMetadataFields() throws Exception {
        when(catalog.listVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L)).thenReturn(List.of(
                new com.skillforge.core.capability.CapabilityVersionSummary(
                        9007199254740993L, "v1", "历史名称", "历史说明", "ARCHIVED")));
        var access = mock(FoundationAccess.class);
        requirementMvc(access).perform(get("/api/v1/capabilities/versions/summaries")
                        .param("definition_id", "11").param("tenant_id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].length()").value(5))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].version_key").value("v1"))
                .andExpect(jsonPath("$.data[0].name").value("历史名称"))
                .andExpect(jsonPath("$.data[0].description").value("历史说明"))
                .andExpect(jsonPath("$.data[0].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data[0].descriptor").doesNotExist())
                .andExpect(jsonPath("$.data[0].files").doesNotExist());
        verify(access).require("foundation.capabilities.read");
        verify(catalog).listVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void versionSummariesReturnAnEmptyArrayWithoutReadingFullVersions() throws Exception {
        when(catalog.listVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L)).thenReturn(List.of());
        requirementMvc(mock(FoundationAccess.class))
                .perform(get("/api/v1/capabilities/versions/summaries").param("definition_id", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(catalog).listVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void versionSummariesRejectMissingIdentityAndDeniedReadBeforeCatalog() throws Exception {
        requirementMvc(mock(FoundationAccess.class)).perform(get("/api/v1/capabilities/versions/summaries"))
                .andExpect(status().isBadRequest());
        var access = mock(FoundationAccess.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无权读取能力目录"))
                .when(access).require("foundation.capabilities.read");
        requirementMvc(access).perform(get("/api/v1/capabilities/versions/summaries").param("definition_id", "11"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test
    void skillVersionSummariesUseTenantScopeAndReturnOnlyThreeFields() throws Exception {
        when(catalog.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L))
                .thenReturn(List.of(new com.skillforge.core.capability.SkillVersionSummary(
                        9007199254740993L, "v1", "ARCHIVED")));
        var access = mock(FoundationAccess.class);
        requirementMvc(access).perform(get("/api/v1/capabilities/skills/versions/summaries")
                        .param("definition_id", "11").param("tenant_id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].version_key").value("v1"))
                .andExpect(jsonPath("$.data[0].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data[0].descriptor").doesNotExist())
                .andExpect(jsonPath("$.data[0].files").doesNotExist());
        verify(access).require("foundation.capabilities.read");
        verify(catalog).listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void skillVersionSummariesReturnAnEmptyListForNoVisibleHistory() throws Exception {
        when(catalog.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L)).thenReturn(List.of());
        requirementMvc(mock(FoundationAccess.class))
                .perform(get("/api/v1/capabilities/skills/versions/summaries").param("definition_id", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(catalog).listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 11L);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void skillVersionSummariesRejectMissingIdentityOrDeniedReadBeforeCatalog() throws Exception {
        requirementMvc(mock(FoundationAccess.class))
                .perform(get("/api/v1/capabilities/skills/versions/summaries"))
                .andExpect(status().isBadRequest());
        var access = mock(FoundationAccess.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无权读取能力目录"))
                .when(access).require("foundation.capabilities.read");
        requirementMvc(access)
                .perform(get("/api/v1/capabilities/skills/versions/summaries").param("definition_id", "11"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test
    void requirementOptionsReturnExactIdentitiesAndSkillEstimateUsingAuthenticatedScope() throws Exception {
        when(catalog.listRequirementOptions(CapabilityCatalogScope.tenant(7L), CapabilityType.SKILL))
                .thenReturn(List.of(new CapabilityRequirementOption(9007199254740993L,
                        "可选技能", 9007199254740995L, "release-2", 31L, "test-key", "test-description", "published-version-name")));
        var access = mock(FoundationAccess.class);
        requirementMvc(access).perform(get("/api/v1/capabilities/requirements/options")
                        .param("capability_type", " skill ").param("tenant_id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].length()").value(8))
                .andExpect(jsonPath("$.data[0].definition_id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].version_id").value("9007199254740995"))
                .andExpect(jsonPath("$.data[0].name").value("可选技能"))
                .andExpect(jsonPath("$.data[0].version_name").value("published-version-name"))
                .andExpect(jsonPath("$.data[0].version_key").value("release-2"))
                .andExpect(jsonPath("$.data[0].capability_key").value("test-key"))
                .andExpect(jsonPath("$.data[0].description").value("test-description"))
                .andExpect(jsonPath("$.data[0].skill_estimated_tokens").value(31))
                .andExpect(jsonPath("$.data[0].descriptor").doesNotExist())
                .andExpect(jsonPath("$.data[0].files").doesNotExist());
        verify(access).require("foundation.capabilities.read");
        verify(catalog).listRequirementOptions(CapabilityCatalogScope.tenant(7L), CapabilityType.SKILL);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void requirementOptionsReturnEmptyArrayWithoutManufacturingNullVersions() throws Exception {
        when(catalog.listRequirementOptions(CapabilityCatalogScope.tenant(7L), CapabilityType.TOOL)).thenReturn(List.of());
        requirementMvc(mock(FoundationAccess.class)).perform(get("/api/v1/capabilities/requirements/options")
                        .param("capability_type", "TOOL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(catalog).listRequirementOptions(CapabilityCatalogScope.tenant(7L), CapabilityType.TOOL);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void requirementOptionsRequireATypeAndRejectInvalidTypesBeforeCatalogRead() throws Exception {
        var mvc = requirementMvc(mock(FoundationAccess.class));
        mvc.perform(get("/api/v1/capabilities/requirements/options")).andExpect(status().isBadRequest());
        for (String type : List.of("", "invalid", "SKILL,TOOL")) {
            mvc.perform(get("/api/v1/capabilities/requirements/options").param("capability_type", type))
                    .andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void requirementOptionsWithoutCapabilityReadPermissionNeverReadCatalog() throws Exception {
        var access = mock(FoundationAccess.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无权读取能力目录"))
                .when(access).require("foundation.capabilities.read");
        requirementMvc(access).perform(get("/api/v1/capabilities/requirements/options").param("capability_type", "SKILL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test
    void createDefinitionAlwaysUsesAuthenticatedTenantScope() {
        CapabilityDefinitionView view = new CapabilityDefinitionView(
                10L, 7L, CapabilityType.TOOL, "calendar", "Calendar", "",
                "TENANT", "DRAFT", null, 1L, null, null);
        when(catalog.createDefinition(any(), eq(CapabilityType.TOOL), eq("calendar"),
                eq("Calendar"), eq(""))).thenReturn(view);

        controller.createDefinition(new CreateCapabilityDefinitionRequest(
                "tool", "calendar", "Calendar", ""));

        verify(catalog).createDefinition(
                eq(CapabilityCatalogScope.tenant(7L)), eq(CapabilityType.TOOL),
                eq("calendar"), eq("Calendar"), eq(""));
    }

    @Test
    void staleHeaderFormatsAreRejectedBeforeServiceCall() {
        ApiException exception = assertThrows(ApiException.class, () ->
                controller.publishVersion(1L, 2L, "0"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void createDeploymentValidatesProviderBeforeCatalogCreate() {
        CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(7L);
        ToolDeployment.ProviderType providerType = ToolDeployment.ProviderType.CONNECTOR;
        ToolDeployment.Environment environment = ToolDeployment.Environment.TEST;
        CapabilityDeploymentView view = new CapabilityDeploymentView(
                11L, 7L, "deployment-1", 9L, "工具名称", "v1", providerType, "inst-1", environment,
                "ACTIVE", 1L, Instant.now(), Instant.now(), null);
        when(catalog.createDeployment(eq(scope), eq("deployment-1"), eq(9L), eq(providerType),
                eq("inst-1"), eq(environment), eq("ACTIVE"))).thenReturn(view);

        var response = controller.createDeployment(new CreateCapabilityDeploymentRequest(
                "deployment-1", 9L, "connector", "inst-1", "test", "ACTIVE"));
        assertEquals("工具名称", response.data().toolName());
        assertEquals("v1", response.data().toolVersionKey());

        InOrder order = inOrder(providerValidator, catalog);
        order.verify(providerValidator).validate(7L, providerType, "inst-1", environment);
        order.verify(catalog).createDeployment(
                eq(scope), eq("deployment-1"), eq(9L), eq(providerType), eq("inst-1"),
                eq(environment), eq("ACTIVE"));
    }

    @Test
    void rejectedProviderStopsCatalogInteraction() {
        ApiException rejection = CapabilityCatalogErrors.exception(
                HttpStatus.UNPROCESSABLE_ENTITY,
                CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                "ToolDeployment provider 无效或不可用");
        doThrow(rejection).when(providerValidator).validate(
                eq(7L), eq(ToolDeployment.ProviderType.CONNECTOR), eq("missing"),
                eq(ToolDeployment.Environment.TEST));

        ApiException exception = assertThrows(ApiException.class, () ->
                controller.createDeployment(new CreateCapabilityDeploymentRequest(
                        "deployment-1", 9L, "connector", "missing", "test", null)));

        assertEquals(CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                exception.getCode());
        verifyNoInteractions(catalog);
    }

    @Test
    void credentialsCannotBeReturnedByDeploymentDto() {
        // The deployment DTO intentionally has no credential property. This shape
        // assertion prevents accidental reintroduction of a secret response field.
        assertEquals(false, java.util.Arrays.stream(
                CreateCapabilityDeploymentRequest.class.getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("credential")));
    }

    @Test
    void platformToolsAreProjectedDirectlyFromTheCanonicalCatalog() {
        List<PlatformToolResponse> result = controller.listPlatformTools().data();

        assertEquals(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().size(), result.size());
        PlatformToolCatalog.Entry entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().get(0);
        assertEquals(new PlatformToolResponse(
                entry.name(), entry.description(), entry.contentHash(), entry.descriptor()), result.get(0));
        assertEquals(false, java.util.Arrays.stream(PlatformToolResponse.class.getRecordComponents())
                .anyMatch(component -> {
                    String name = component.getName().toLowerCase();
                    return name.contains("credential") || name.contains("executor")
                            || name.contains("handler") || name.contains("url");
                }));
        verifyNoInteractions(catalog, providerValidator);
    }
}
