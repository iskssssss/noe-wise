package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.management.AgentManagementApplicationService;
import com.skillforge.core.agent.management.AgentManagementContracts;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationPermissionInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.HostTraceIdFilter;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import com.skillforge.institution.service.AgentManagementService;
import com.skillforge.institution.service.AgentTemplateInstallationService;
import com.skillforge.institution.service.InstitutionResourceAccess;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 真实宿主映射、JSON 和权限拦截；应用服务为边界桩，不冒称事务验收。 */
class AgentSaveAndPublishControllerTest {
    private static final String PATH = "/api/v1/agents/save-and-publish";
    private static final String AGENT = "9007199254740993";
    private static final UUID REQUEST = UUID.fromString("342de035-78c8-49a8-bb6a-80b302e95c45");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final AgentManagementApplicationService core = mock(AgentManagementApplicationService.class);
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final InstitutionResourceAccess resources = mock(InstitutionResourceAccess.class);
    private final AgentTemplateInstallationService installations = mock(AgentTemplateInstallationService.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private MockMvc mvc;

    @BeforeEach void setup() {
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(new AgentManagementService(core, resources),
                        installations))
                .addInterceptors(new FoundationPermissionInterceptor(access))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addFilters(new HostTraceIdFilter((request, response, handler, failure) ->
                        mvc.getDispatcherServlet().getWebApplicationContext()
                                .getBean("handlerExceptionResolver", HandlerExceptionResolver.class)
                                .resolveException(request, response, handler, failure)),
                        (request, response, chain) -> {
                            // 固定认证身份在每次请求的 trace 生命周期内装配。
                            TenantContext.setTenantId("7");
                            chain.doFilter(request, response);
                        })
                .setValidator(validator).build();
    }
    @AfterEach void clear() { TenantContext.clear(); validator.close(); }

    @Test void postMapsExactInputOnceAndReturnsFiveFieldReceiptWithStringIdentities() throws Exception {
        var receipt = receipt();
        when(core.saveAndPublish(any(), eq(AGENT), eq(REQUEST), any())).thenReturn(receipt);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "agent_id", AGENT, "request_id", REQUEST, "payload", Map.of("name", "新名称",
                                "system_prompt", "完整提示词", "model_config_id", "42", "config", Map.of("flag", true))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.request_id").value(REQUEST.toString()))
                .andExpect(jsonPath("$.data.agent_id").value(AGENT))
                .andExpect(jsonPath("$.data.publication_id").value("9007199254740995"))
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.published_at").exists());
        var payload = ArgumentCaptor.forClass(AgentManagementContracts.Update.class);
        verify(core).saveAndPublish(eq(new AgentManagementContracts.Caller(7L, false)), eq(AGENT), eq(REQUEST), payload.capture());
        assertEquals("新名称", payload.getValue().name());
        assertEquals("完整提示词", payload.getValue().systemPrompt());
        assertEquals("42", payload.getValue().modelConfigId());
        assertEquals(Map.of("flag", true), payload.getValue().config());
        assertNull(payload.getValue().status());
        verifyNoMoreInteractions(core);
        verify(access).require("foundation.agents.update");
    }

    @Test void resultUsesReadPermissionAndOriginalIdentityWithoutReplayingWrite() throws Exception {
        when(core.saveAndPublishResult(any(), eq(AGENT), eq(REQUEST))).thenReturn(receipt(), null);
        mvc.perform(get(PATH + "/result").param("agentId", AGENT).param("requestId", REQUEST.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.request_id").value(REQUEST.toString()))
                .andExpect(jsonPath("$.data.agent_id").value(AGENT));
        mvc.perform(get(PATH + "/result").param("agentId", AGENT).param("requestId", REQUEST.toString()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(core, times(2)).saveAndPublishResult(new AgentManagementContracts.Caller(7L, false), AGENT, REQUEST);
        verifyNoMoreInteractions(core);
        verify(access, times(2)).require("foundation.agents.read");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void oversizedPublicationNameRejectsWithNestedFieldBeforeApplicationService(String unit) throws Exception {
        mvc.perform(post(PATH).header("X-Trace-Id", "agent-publish-name-validation")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "agent_id", AGENT, "request_id", REQUEST, "payload", Map.of("name", unit.repeat(129))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors['payload.name']").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").value("agent-publish-name-validation"))
                .andExpect(header().string("X-Trace-Id", "agent-publish-name-validation"));

        verifyNoInteractions(core, installations, resources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void oversizedInstallationNameIsDefinitelyRejectedBeforeInstallationService(String unit) throws Exception {
        mvc.perform(post("/api/v1/agents/install-template").header("X-Trace-Id", "agent-install-name-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("template_id", 1L,
                                "request_id", "name-validation", "name", unit.repeat(129)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_INSTALL_REJECTED"))
                .andExpect(jsonPath("$.field_errors.name").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").value("agent-install-name-validation"))
                .andExpect(header().string("X-Trace-Id", "agent-install-name-validation"));

        verifyNoInteractions(core, installations, resources);
    }

    @Test void missingOrMalformedRequestIdentityAndPayloadRejectBeforeApplicationService() throws Exception {
        for (String body : new String[]{"{}", "{\"agent_id\":\"1\",\"payload\":{}}",
                "{\"agent_id\":\"1\",\"request_id\":\"bad\",\"payload\":{}}",
                "{\"agent_id\":\"1\",\"request_id\":\"" + REQUEST + "\"}"}) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH + "/result").param("agentId", AGENT).param("requestId", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/result").param("agentId", AGENT)).andExpect(status().isBadRequest());
        verifyNoInteractions(core);
    }

    @Test void updateAndReadPermissionRejectionsNeverCallTheApplicationService() throws Exception {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "不允许更新")).when(access).require("foundation.agents.update");
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "不允许读取")).when(access).require("foundation.agents.read");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "agent_id", AGENT, "request_id", REQUEST, "payload", Map.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/result").param("agentId", AGENT).param("requestId", REQUEST.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(core);
    }

    private static AgentManagementContracts.SaveAndPublishReceipt receipt() {
        return new AgentManagementContracts.SaveAndPublishReceipt(REQUEST, Long.parseLong(AGENT),
                9007199254740995L, 3, Instant.parse("2026-10-30T00:00:00Z"));
    }
}
