package com.skillforge.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.systemagent.PlatformAgentResources;
import com.skillforge.core.agent.systemagent.PlatformAgentResources.DeploymentView;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationPermissionInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.platform.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformAgentToolFilterTest {
    @Test void validatesFiltersBeforeReadingAndKeepsOptionsUnderDeploymentReadPermission() throws Exception {
        var resources = mock(PlatformAgentResources.class);
        var access = mock(FoundationAccess.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformAgentToolController(resources))
                .addInterceptors(new FoundationPermissionInterceptor(access))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        String endpoint = "/api/v1/admin/system-agent-tools";
        var options = json.readTree(mvc.perform(get(endpoint + "/options")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals("TEST", options.path("environments").get(0).path("value").textValue());
        assertEquals("PRODUCTION", options.path("environments").get(1).path("value").textValue());
        assertEquals("false", options.path("statuses").get(1).path("value").textValue());
        verify(access).require("foundation.system_agent_tools.read");
        for (var pair : List.of(new String[]{"environment", "STAGING"}, new String[]{"active", "yes"})) {
            var response = mvc.perform(get(endpoint).param(pair[0], pair[1]))
                    .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
            assertEquals("system_tool_filter_invalid", json.readTree(response).path("code").textValue());
        }
        verifyNoInteractions(resources);
        when(resources.deployments()).thenReturn(List.of(
                new DeploymentView("1", "测试草稿", null, "PLATFORM", "calculate", "TEST", false, 3, "11", null, true),
                new DeploymentView("2", "生产部署", "22", "PLATFORM", "execute", "PRODUCTION", true, 1, "12", 1L, false)));
        var filtered = json.readTree(mvc.perform(get(endpoint).param("environment", "TEST").param("active", "false"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(1, filtered.size());
        assertEquals("1", filtered.get(0).path("id").textValue());
        var all = json.readTree(mvc.perform(get(endpoint)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(2, all.size());
        clearInvocations(resources);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "禁止访问")).when(access).require("foundation.system_agent_tools.read");
        mvc.perform(get(endpoint)).andExpect(status().isForbidden());
        mvc.perform(get(endpoint + "/options")).andExpect(status().isForbidden());
        verifyNoInteractions(resources);
    }
}
