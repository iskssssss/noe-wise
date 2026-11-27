package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.request.CreateChannelRequest;
import com.skillforge.core.channel.dto.request.PatchChannelRequest;
import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.institution.channel.ChannelOnboardingController;
import com.skillforge.institution.channel.ChannelOnboardingService;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChannelOptionsHttpTest {
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final ChannelService channels = mock(ChannelService.class);

    @Test void optionsExposeCurrentTypesAndConsistentStatusLabels() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelController(channels))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        mvc.perform(get("/api/v1/channels/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.types[0].value").value("web_widget"))
                .andExpect(jsonPath("$.data.types[0].label").value("网页组件"))
                .andExpect(jsonPath("$.data.types[1].value").value("openapi"))
                .andExpect(jsonPath("$.data.types[1].label").value("开放 API"))
                .andExpect(jsonPath("$.data.statuses[0].value").value("active"))
                .andExpect(jsonPath("$.data.statuses[0].label").value("启用"))
                .andExpect(jsonPath("$.data.statuses[1].value").value("disabled"))
                .andExpect(jsonPath("$.data.statuses[1].label").value("停用"));
        var options = new ChannelController(channels).options().data();
        assertThat(options.types()).hasSize(ChannelType.values().length);
        assertThat(options.statuses()).hasSize(ChannelStatus.values().length);
        verifyNoInteractions(channels);
    }

    @Test void unknownTypeAndStatusAreRejectedBeforeAnyChannelOperation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelController(channels))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler(json)).build();
        for (String value : new String[]{"invalid", "", "ACTIVE"}) {
            mvc.perform(post("/api/v1/channels").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of("channel_type", value))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            mvc.perform(patch("/api/v1/channels/update").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of("channel_id", 31,
                                    "payload", java.util.Map.of("status", value, "name", "must not save")))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(channels);
    }

    @Test void onboardingInvalidTypeReturnsDefiniteRejectionWithoutIssuingCredentials() throws Exception {
        var onboarding = mock(ChannelOnboardingService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelOnboardingController(onboarding))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        mvc.perform(post("/api/v1/channels/onboarding").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"fd9477a7-8bf8-4e29-9db7-a24bdc270d12",
                                 "channel":{"name":"invalid","channel_type":"invalid"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_ONBOARDING_REJECTED"));
        verifyNoInteractions(onboarding);
    }

    @Test void validAndOmittedValuesKeepCreateDefaultAndPartialUpdateSemantics() throws Exception {
        assertThat(json.readValue("{}", CreateChannelRequest.class).channelType()).isNull();
        assertThat(json.readValue("{\"status\":null}", PatchChannelRequest.class).status()).isNull();
        assertThat(json.readValue("{\"channel_type\":\"openapi\"}", CreateChannelRequest.class).channelType())
                .isEqualTo(ChannelType.OPENAPI);
        assertThat(json.readValue("{\"status\":\"disabled\"}", PatchChannelRequest.class).status())
                .isEqualTo(ChannelStatus.DISABLED);
    }
}
