package com.skillforge.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.model.TimePricingPresetService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 请求边界验证；真实发布与权限存储由关系持久化测试覆盖。 */
class TimePricingPresetControllerTest {
    @Test void preservesStructuredConfigurationAndSeparatesDraftFromPublishedHistory() throws Exception {
        var presets = mock(TimePricingPresetService.class);
        var policy = new TimePricingPolicy("UTC", LocalDate.parse("2032-01-01"), LocalDate.parse("2032-12-31"),
                List.of(), List.of(), List.of(), List.of(), BigDecimal.ONE, new BigDecimal("0.125"), List.of(), null, "administrator-v2", null);
        var draft = new TimePricingPresetService.View("81000001", "管理规则", 3L, 1L, true, policy);
        var published = new TimePricingPresetService.View("81000001", "旧规则", 1L, 1L, false, policy);
        when(presets.list()).thenReturn(List.of(draft));
        when(presets.detail("81000001")).thenReturn(draft);
        when(presets.history("81000001")).thenReturn(List.of(published));
        when(presets.create(any(), eq("platform-test"))).thenReturn(draft);
        when(presets.update(eq("81000001"), any(), eq("platform-test"))).thenReturn(draft);
        when(presets.publish("81000001", 3L, "platform-test")).thenReturn(draft);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc = MockMvcBuilders.standaloneSetup(new TimePricingPresetController(presets))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        String base = "/api/v1/admin/models/time-pricing-presets";
        String response = mvc.perform(get(base)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("\"off_peak_multiplier\":\"0.125\"", "\"valid_from\":\"2032-01-01\"", "\"published_revision\":1");
        mvc.perform(get(base + "/detail").param("id", "81000001")).andExpect(status().isOk());
        mvc.perform(get(base + "/history").param("id", "81000001")).andExpect(status().isOk());
        var write = new TimePricingPresetService.Write(3L, "管理规则", policy);
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsString).thenReturn("platform-test");
            mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(write))).andExpect(status().isCreated());
            mvc.perform(put(base + "/update").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new TimePricingPresetController.Update("81000001", write)))).andExpect(status().isOk());
            mvc.perform(post(base + "/publish").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"81000001\",\"expected_revision\":3}")).andExpect(status().isOk());
        }
        var received = ArgumentCaptor.forClass(TimePricingPresetService.Write.class);
        verify(presets).update(eq("81000001"), received.capture(), eq("platform-test"));
        assertThat(received.getValue()).isEqualTo(write);
        verify(presets).publish("81000001", 3L, "platform-test");
    }
}
