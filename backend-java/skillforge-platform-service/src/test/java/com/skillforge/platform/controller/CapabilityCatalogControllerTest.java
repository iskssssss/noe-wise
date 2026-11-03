package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityRequirementOption;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillCatalogSummary;
import com.skillforge.core.capability.SkillPackageCatalogService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationPermissionInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.platform.dto.capability.CreateCapabilityDefinitionRequest;
import com.skillforge.platform.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 平台 Catalog API 的公共 scope、类型与 If-Match 契约。 */
class CapabilityCatalogControllerTest {

    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final CapabilityCatalogController controller = new CapabilityCatalogController(
            catalog, mock(SkillPackageCatalogService.class));

    @Test
    void versionSummariesUseAuthenticatedScopeAndReturnOnlyFiveMetadataFields() throws Exception {
        when(catalog.listVersionSummaries(CapabilityCatalogScope.platform(), 11L)).thenReturn(List.of(
                new com.skillforge.core.capability.CapabilityVersionSummary(
                        9007199254740993L, "v1", "历史名称", "历史说明", "ARCHIVED")));
        var access = mock(FoundationAccess.class);
        skillMvc(access).perform(get("/api/v1/admin/capabilities/versions/summaries")
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
        verify(catalog).listVersionSummaries(CapabilityCatalogScope.platform(), 11L);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void versionSummariesReturnAnEmptyArrayWithoutReadingFullVersions() throws Exception {
        when(catalog.listVersionSummaries(CapabilityCatalogScope.platform(), 11L)).thenReturn(List.of());
        skillMvc(mock(FoundationAccess.class))
                .perform(get("/api/v1/admin/capabilities/versions/summaries").param("definition_id", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(catalog).listVersionSummaries(CapabilityCatalogScope.platform(), 11L);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void versionSummariesRejectMissingIdentityAndDeniedReadBeforeCatalog() throws Exception {
        skillMvc(mock(FoundationAccess.class)).perform(get("/api/v1/admin/capabilities/versions/summaries"))
                .andExpect(status().isBadRequest());
        var access = mock(FoundationAccess.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无权读取能力目录"))
                .when(access).require("foundation.capabilities.read");
        skillMvc(access).perform(get("/api/v1/admin/capabilities/versions/summaries").param("definition_id", "11"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test
    void requirementOptionsReturnExactIdentitiesAndSkillEstimateUsingAuthenticatedScope() throws Exception {
        when(catalog.listRequirementOptions(CapabilityCatalogScope.platform(), CapabilityType.SKILL))
                .thenReturn(List.of(new CapabilityRequirementOption(9007199254740993L,
                        "可选技能", 9007199254740995L, "release-2", 31L)));
        var access = mock(FoundationAccess.class);
        skillMvc(access).perform(get("/api/v1/admin/capabilities/requirements/options")
                        .param("capability_type", " skill ").param("tenant_id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].length()").value(5))
                .andExpect(jsonPath("$.data[0].definition_id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].version_id").value("9007199254740995"))
                .andExpect(jsonPath("$.data[0].name").value("可选技能"))
                .andExpect(jsonPath("$.data[0].version_key").value("release-2"))
                .andExpect(jsonPath("$.data[0].skill_estimated_tokens").value(31))
                .andExpect(jsonPath("$.data[0].descriptor").doesNotExist())
                .andExpect(jsonPath("$.data[0].files").doesNotExist());
        verify(access).require("foundation.capabilities.read");
        verify(catalog).listRequirementOptions(CapabilityCatalogScope.platform(), CapabilityType.SKILL);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void requirementOptionsReturnEmptyArrayWithoutManufacturingNullVersions() throws Exception {
        when(catalog.listRequirementOptions(CapabilityCatalogScope.platform(), CapabilityType.TOOL)).thenReturn(List.of());
        skillMvc(mock(FoundationAccess.class)).perform(get("/api/v1/admin/capabilities/requirements/options")
                        .param("capability_type", "TOOL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(catalog).listRequirementOptions(CapabilityCatalogScope.platform(), CapabilityType.TOOL);
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void requirementOptionsRequireATypeAndRejectInvalidTypesBeforeCatalogRead() throws Exception {
        var mvc = skillMvc(mock(FoundationAccess.class));
        mvc.perform(get("/api/v1/admin/capabilities/requirements/options")).andExpect(status().isBadRequest());
        for (String type : List.of("", "invalid", "SKILL,TOOL")) {
            mvc.perform(get("/api/v1/admin/capabilities/requirements/options").param("capability_type", type))
                    .andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void requirementOptionsWithoutCapabilityReadPermissionNeverReadCatalog() throws Exception {
        var access = mock(FoundationAccess.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无权读取能力目录"))
                .when(access).require("foundation.capabilities.read");
        skillMvc(access).perform(get("/api/v1/admin/capabilities/requirements/options").param("capability_type", "SKILL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test
    void skillCatalogUsesOnlyThePlatformSummaryServiceAndReturnsDescription() throws Exception {
        var summary = new SkillCatalogSummary(
                9007199254740993L, "public-skill", "公共技能", "处理订单", "PUBLISHED", 9007199254740995L, "v1");
        when(catalog.listPlatformSkills()).thenReturn(List.of(summary));
        var access = mock(FoundationAccess.class);
        skillMvc(access).perform(get("/api/v1/admin/capabilities/skills/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].length()").value(7))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].capability_key").value("public-skill"))
                .andExpect(jsonPath("$.data[0].description").value("处理订单"))
                .andExpect(jsonPath("$.data[0].default_version_id").value("9007199254740995"))
                .andExpect(jsonPath("$.data[0].descriptor").doesNotExist())
                .andExpect(jsonPath("$.data[0].files").doesNotExist());
        verify(access).require("foundation.capabilities.read");
        verify(catalog).listPlatformSkills();
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void skillCatalogReadPermissionDenialNeverReadsTheCatalog() throws Exception {
        var access = mock(FoundationAccess.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无权读取能力目录"))
                .when(access).require("foundation.capabilities.read");
        skillMvc(access).perform(get("/api/v1/admin/capabilities/skills/catalog"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(catalog);
    }

    private MockMvc skillMvc(FoundationAccess access) {
        var json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new FoundationPermissionInterceptor(access))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    void createDefinitionAlwaysUsesPlatformScope() {
        CapabilityDefinitionView view = new CapabilityDefinitionView(
                10L, null, CapabilityType.TOOL, "calendar", "Calendar", "",
                "PLATFORM", "DRAFT", null, 1L, null, null);
        when(catalog.createDefinition(any(), eq(CapabilityType.TOOL), eq("calendar"),
                eq("Calendar"), eq(""))).thenReturn(view);

        controller.createDefinition(new CreateCapabilityDefinitionRequest(
                "tool", "calendar", "Calendar", ""));

        verify(catalog).createDefinition(
                eq(CapabilityCatalogScope.platform()), eq(CapabilityType.TOOL),
                eq("calendar"), eq("Calendar"), eq(""));
    }

    @Test
    void staleHeaderFormatsAreRejectedBeforeServiceCall() {
        ApiException exception = assertThrows(ApiException.class, () ->
                controller.publishVersion(1L, 2L, "not-a-revision"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }
}
