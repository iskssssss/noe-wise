package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.dto.request.CreateAgentRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.HostTraceIdFilter;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import com.skillforge.institution.service.AgentCreationService;
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

class AgentCreationControllerTest {
    private static final String PATH = "/api/v1/agents/create-with-receipt";
    private static final UUID REQUEST = UUID.fromString("342de035-78c8-49a8-bb6a-80b302e95c45");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final AgentCreationService service = mock(AgentCreationService.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private MockMvc mvc;

    @BeforeEach void setup() {
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AgentCreationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addFilters(new HostTraceIdFilter((request, response, handler, failure) ->
                        mvc.getDispatcherServlet().getWebApplicationContext()
                                .getBean("handlerExceptionResolver", HandlerExceptionResolver.class)
                                .resolveException(request, response, handler, failure)))
                .setValidator(validator).build();
    }

    @AfterEach void close() { validator.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void oversizedNameIsDefinitelyRejectedWithNestedFieldBeforeCreationService(String unit) throws Exception {
        mvc.perform(post(PATH).header("X-Trace-Id", "agent-create-name-validation")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "request_id", REQUEST, "payload", Map.of("name", unit.repeat(129))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_CREATE_REJECTED"))
                .andExpect(jsonPath("$.field_errors['payload.name']").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").value("agent-create-name-validation"))
                .andExpect(header().string("X-Trace-Id", "agent-create-name-validation"));

        verifyNoInteractions(service);
    }

    @Test void creationAccepts128EmojiAndPassesTheFullNameToTheService() throws Exception {
        String name = "😀".repeat(128);
        when(service.create(any())).thenReturn(new AgentCreationService.Result(REQUEST, "1"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "request_id", REQUEST, "payload", Map.of("name", name)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request_id").value(REQUEST.toString()))
                .andExpect(jsonPath("$.data.agent_id").value("1"));

        var command = ArgumentCaptor.forClass(AgentCreationService.Command.class);
        verify(service).create(command.capture());
        assertEquals(name, command.getValue().payload().name());
        verifyNoMoreInteractions(service);
    }

    @Test void missingIdentityPayloadOrNameRejectsAtTheRequestBoundary() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_CREATE_REJECTED"))
                .andExpect(jsonPath("$.field_errors.request_id").isNotEmpty())
                .andExpect(jsonPath("$.field_errors.payload").isNotEmpty());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "request_id", REQUEST, "payload", Map.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_CREATE_REJECTED"))
                .andExpect(jsonPath("$.field_errors['payload.name']").isNotEmpty());

        verifyNoInteractions(service);
    }

    @Test void distinguishesTransactionalRejectionFromRequestIdentityConflict() {
        var controller = new AgentCreationController(service);
        var command = new AgentCreationService.Command(REQUEST, new CreateAgentRequest(null, "助手", null, null, null));
        when(service.create(command)).thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "无效"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "AGENT_CREATE_IDENTITY_CONFLICT", "冲突", false));
        assertEquals("AGENT_CREATE_REJECTED", assertThrows(ApiException.class, () -> controller.create(command)).getCode());
        assertEquals("AGENT_CREATE_IDENTITY_CONFLICT", assertThrows(ApiException.class, () -> controller.create(command)).getCode());
    }
}
