package com.skillforge.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.platform.models.PlatformModelProbeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModelControllerTest {
    @Test void summaryHttpProjectsEightFieldsAndEnforcesReadPermission() throws Exception {
        var models = mock(PlatformModelCatalogService.class);
        var access = mock(com.skillforge.core.foundation.access.FoundationAccess.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformModelController(models, mock(PlatformModelProbeService.class)))
                .addInterceptors(new com.skillforge.core.foundation.access.FoundationPermissionInterceptor(access))
                .setControllerAdvice(new com.skillforge.platform.exception.GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(models.summaries()).thenReturn(java.util.List.of(new com.skillforge.core.model.PlatformModelDirectorySummary(
                "9007199254740993", "目录模型", "LLM", "deepseek", "draft-model", false, null, true)));
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/models/summaries"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var row = json.readTree(result).path("data").get(0);
        assertEquals(8, row.size());
        assertEquals("9007199254740993", row.path("id").textValue());
        assertEquals("draft-model", row.path("model_id").textValue());
        assertTrue(row.path("published_revision").isNull());
        assertTrue(row.path("has_unpublished_changes").booleanValue());
        verify(access).require("foundation.models.read");
        verify(models).summaries();
        clearInvocations(models);
        doThrow(new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "禁止访问"))
                .when(access).require("foundation.models.read");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/models/summaries"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(models);
    }

    @Test void acceptsUnsavedSnakeCasePayloadAndStoredCredentialDiscoveryReference() throws Exception {
        var models = mock(PlatformModelCatalogService.class);
        var probes = mock(PlatformModelProbeService.class);
        var json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformModelController(models, probes))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(probes.test(isNull(), any())).thenReturn(new PlatformModelProbeService.ProbeResult(true, 1, null, null));
        var response = mvc.perform(post("/api/v1/admin/models/test").contentType(MediaType.APPLICATION_JSON).content("""
                {"payload":{"name":"临时模型","kind":"LLM","provider_id":"deepseek",
                "model_id":"deepseek-flash","api_key":"synthetic-secret","max_output_tokens":512}}
                """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var payload = ArgumentCaptor.forClass(ModelWrite.class);
        verify(probes).test(isNull(), payload.capture());
        assertEquals("deepseek", payload.getValue().providerId());
        assertEquals("synthetic-secret", payload.getValue().apiKey());
        assertEquals(512, payload.getValue().maxOutputTokens());
        assertFalse(response.contains("synthetic-secret"));

        mvc.perform(post("/api/v1/admin/models/discover").contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"7001","expected_revision":3,"provider_id":"deepseek","service_id":"deepseek-api",
                "profile_version":"2026-09-13","protocol":"anthropic-messages",
                "authentication_scheme":"anthropic-api-key","endpoint":"https://api.deepseek.com"}
                """)).andExpect(status().isOk());
        var discovery = ArgumentCaptor.forClass(PlatformModelProbeService.Discovery.class);
        verify(probes).discover(discovery.capture());
        assertEquals("7001", discovery.getValue().id());
        assertEquals(3L, discovery.getValue().expectedRevision());
        assertNull(discovery.getValue().apiKey());
    }
}
