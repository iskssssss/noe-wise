package com.skillforge.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.model.media.ModelMediaInput;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModelMediaControllerTest {
    @Test void bindsTypedInputAndUsesAuthenticatedOwnerWithoutExposingCredentials() throws Exception {
        var jobs = mock(ModelMediaJobService.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformModelMediaController(jobs))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(jobs.create(any(), any())).thenReturn(new ModelMediaJob("2001", "543480cc-ccee-4aa5-9071-71bb5dc8f4ef", null,
                "qwen-image-fixture", 0, "IMAGE_GENERATION", Status.PENDING, false, "supplier-task", null,
                Instant.parse("2026-10-25T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"), null, List.of()));
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsString).thenReturn("7");
            var response = mvc.perform(post("/api/v1/admin/models/media/jobs/create").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"operation_id":"543480cc-ccee-4aa5-9071-71bb5dc8f4ef",
                        "payload":{"name":"未保存图片模型","kind":"IMAGE_GENERATION","api_key":"synthetic-private-key"},
                        "input":{"type":"image","prompt":"画一片叶子","reference_images":[],"count":1,"watermark":false}}
                        """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var command = ArgumentCaptor.forClass(ModelMediaJob.Create.class);
            verify(jobs).create(eq(new ModelMediaJob.Owner(null, "7")), command.capture());
            assertInstanceOf(ModelMediaInput.Image.class, command.getValue().input());
            assertEquals("synthetic-private-key", command.getValue().payload().apiKey());
            assertFalse(response.contains("synthetic-private-key"));
            assertFalse(response.contains("endpoint"));
            assertFalse(response.contains("credential"));
            assertTrue(response.contains("supplier-task"));
        }
    }
}
