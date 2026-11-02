package com.skillforge.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.model.media.ModelMediaJob.Owner;
import com.skillforge.core.model.media.realtime.ModelRealtimeService;
import com.skillforge.core.model.media.realtime.ModelRealtimeSession;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** 真实MVC序列化与流式通道，不替代平台鉴权/RLS的集成验证。 */
class PlatformModelRealtimeControllerTest {
    @Test void acceptsSnakeCaseAsrAndBoundsBinaryFrameBeforeApplicationDispatch() throws Exception {
        var service = mock(ModelRealtimeService.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformModelRealtimeController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(service.openAsr(any(), any())).thenReturn(snapshot());
        when(service.audio(any(), anyString(), anyLong(), any())).thenReturn(snapshot());
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsString).thenReturn("7");
            mvc.perform(post("/api/v1/admin/models/media/realtime/asr/open").contentType(MediaType.APPLICATION_JSON).content("""
                    {"operation_id":"00000000-0000-0000-0000-000000000001","id":"71","sample_rate_hz":16000,"language":"zh"}
                    """)).andExpect(status().isOk());
            var command = ArgumentCaptor.forClass(ModelRealtimeSession.OpenAsr.class);
            var actor = ArgumentCaptor.forClass(Owner.class);
            verify(service).openAsr(actor.capture(), command.capture());
            assertEquals("7", actor.getValue().actorId()); assertNull(actor.getValue().tenantId());
            assertEquals(16000, command.getValue().sampleRateHz()); assertEquals("71", command.getValue().id());
            assertNull(command.getValue().payload());
            mvc.perform(post("/api/v1/admin/models/media/realtime/asr/audio?id=17&sequence=3")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[1048600])).andExpect(status().isOk());
            var frame = ArgumentCaptor.forClass(byte[].class);
            verify(service).audio(any(), eq("17"), eq(3L), frame.capture());
            assertEquals(1048577, frame.getValue().length);
        }
    }

    @Test void speechProducesTypedSseAudioAndSessionEvents() throws Exception {
        var service = mock(ModelRealtimeService.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformModelRealtimeController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(service.streamSpeech(any(), any(), any())).thenAnswer(call -> {
            ModelRealtimeService.Listener listener = call.getArgument(2);
            listener.snapshot(snapshot());
            listener.audio(new ModelRealtimeSession.Audio("AA==", "pcm_s16le", 24000, 1));
            listener.complete();
            return snapshot();
        });
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsString).thenReturn("7");
            var result = mvc.perform(post("/api/v1/admin/models/media/realtime/tts/stream")
                    .contentType(MediaType.APPLICATION_JSON).content("""
                    {"operation_id":"00000000-0000-0000-0000-000000000001","id":"71","text":"测试","voice":"voice","language":"Chinese"}
                    """)).andExpect(request().asyncStarted()).andReturn();
            var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse();
            assertTrue(response.getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
            String body = response.getContentAsString();
            assertTrue(body.contains("event:session")); assertTrue(body.contains("event:audio"));
            assertTrue(body.contains("\"encoding\":\"pcm_s16le\"")); assertTrue(body.contains("\"sample_rate_hz\":24000"));
        }
    }

    private static ModelRealtimeSession snapshot() {
        var at = Instant.parse("2026-10-25T00:00:00Z");
        return new ModelRealtimeSession("17", "00000000-0000-0000-0000-000000000001", "SPEECH_RECOGNITION",
                "fixture-model", 1, "task", Status.RUNNING, true, false, 0, null, null, null,
                at, at.plusSeconds(60), at, null, null);
    }
}
