package com.skillforge.core.chat.profile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.prompts.PromptDefaults;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.chat.persistence.UserProfileMapper;
import com.skillforge.core.chat.persistence.UserProfileDO;
import com.skillforge.core.chat.persistence.ProfileSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 原子追加返回快照后，模型合并只通过 revision CAS 写回。 */
class UserProfileServiceTest {
    private UserProfileMapper mapper;
    private LlmService llm;
    private UserProfileService service;
    private UserProfileDO snapshot;
    @BeforeEach
    void setUp() {
        mapper = mock(UserProfileMapper.class);
        llm = mock(LlmService.class);
        var settings = mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("max_raw_summaries", 3L, "incremental_merge", true)));
        when(settings.read("prompts")).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("profile_merge_prompt", PromptDefaults.PROFILE_MERGE_PROMPT)));
        snapshot = new UserProfileDO();
        snapshot.setRevision(4L);
        snapshot.setSummary("{\"用户目标\":\"\",\"关键偏好\":{},\"历史结论\":[\"旧画像\"]}");
        snapshot.setSummaries(List.of(new ProfileSummary("第一条总结", "time")));
        when(mapper.appendRawSummary(anyLong(), eq(1L), eq(100L), anyString(), anyList(), eq(3), any()))
                .thenReturn(snapshot);
        service = new UserProfileService(mapper, llm, new ObjectMapper(), settings);
    }
    @Test
    void appendInitialProfileUsesAtomicMapperAndTypedWindow() throws Exception {
        service.appendSummary("1", 100L, "希望办理资料查询");
        var initial = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") var entries = (ArgumentCaptor<List<ProfileSummary>>) (Object)
                ArgumentCaptor.forClass(List.class);
        verify(mapper).appendRawSummary(anyLong(), eq(1L), eq(100L), initial.capture(), entries.capture(), eq(3), any());
        assertEquals("希望办理资料查询", new ObjectMapper().readTree(initial.getValue()).path("历史结论").get(0).asText());
        assertEquals("希望办理资料查询", entries.getValue().getFirst().content());
        assertNotNull(entries.getValue().getFirst().createdAt());
        verify(mapper, never()).updateById(any(UserProfileDO.class));
        verify(llm, never()).generate(anyString(), anyString());
    }
    @Test
    void fullSnapshotMergesThroughCas() throws Exception {
        fullWindow();
        when(llm.generate(anyString(), anyString())).thenReturn(new LlmResult(
                "{\"用户目标\":\"资料查询\",\"关键偏好\":{},\"历史结论\":[\"查阅资料\"]}", "test", LlmUsage.empty()));
        when(mapper.mergeSummaryIfRevision(eq(1L), eq(100L), eq(4L), anyString())).thenReturn(1);
        service.appendSummary("1", 100L, "第三条总结");
        var input = ArgumentCaptor.forClass(String.class);
        verify(llm).generate(eq(PromptDefaults.PROFILE_MERGE_PROMPT), input.capture());
        var data = new ObjectMapper().readTree(input.getValue());
        assertEquals(snapshot.getSummary(), data.path("old_profile").asText());
        assertEquals(3, data.path("summaries").size());
        assertEquals("第三条总结", data.path("summaries").get(2).path("content").asText());
        verify(mapper).mergeSummaryIfRevision(eq(1L), eq(100L), eq(4L), contains("资料查询"));
    }
    @Test
    void conflictingMergeDoesNotRetryPaidModelOrOverwriteWindow() {
        fullWindow();
        when(llm.generate(anyString(), anyString())).thenReturn(new LlmResult(snapshot.getSummary(), "test", LlmUsage.empty()));
        when(mapper.mergeSummaryIfRevision(eq(1L), eq(100L), eq(4L), anyString())).thenReturn(0);
        service.appendSummary("1", 100L, "第三条总结");
        verify(llm, times(1)).generate(anyString(), anyString());
        verify(mapper, times(1)).mergeSummaryIfRevision(eq(1L), eq(100L), eq(4L), anyString());
        verify(mapper, never()).updateById(any(UserProfileDO.class));
    }
    @Test
    void malformedMergeDoesNotReplaceProfile() {
        fullWindow();
        when(llm.generate(anyString(), anyString())).thenReturn(new LlmResult("plain text", "test", LlmUsage.empty()));
        assertThrows(IllegalStateException.class, () -> service.appendSummary("1", 100L, "第三条总结"));
        verify(mapper, never()).mergeSummaryIfRevision(anyLong(), anyLong(), anyLong(), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"用户目标\":1,\"关键偏好\":{},\"历史结论\":[]}",
        "{\"用户目标\":false,\"关键偏好\":{},\"历史结论\":[]}",
        "{\"用户目标\":\"\",\"关键偏好\":[],\"历史结论\":[]}",
        "{\"用户目标\":\"\",\"关键偏好\":{},\"历史结论\":[1]}",
        "{\"用户目标\":\"\",\"关键偏好\":{},\"历史结论\":[null]}",
        "{\"用户目标\":\"\",\"关键偏好\":{}}",
        "{\"用户目标\":\"\",\"关键偏好\":{},\"历史结论\":[],\"unknown\":1}"
    })
    void fixedProfileFieldsRejectMissingWrongTypesAndCoercion(String raw) {
        fullWindow();
        when(llm.generate(anyString(), anyString())).thenReturn(new LlmResult(raw, "test", LlmUsage.empty()));
        assertThrows(IllegalStateException.class, () -> service.appendSummary("1", 100L, "第三条总结"));
        verify(mapper, never()).mergeSummaryIfRevision(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void validProfilePreservesDynamicPreferenceObjects() {
        fullWindow();
        String raw = "{\"用户目标\":\"资料管理\",\"关键偏好\":{\"requests\":{\"weekday\":true},\"rating\":4},\"历史结论\":[\"查阅资料\"]}";
        when(llm.generate(anyString(), anyString())).thenReturn(new LlmResult(raw, "test", LlmUsage.empty()));
        service.appendSummary("1", 100L, "第三条总结");
        verify(mapper).mergeSummaryIfRevision(eq(1L), eq(100L), eq(4L), eq(raw));
    }
    private void fullWindow() {
        snapshot.setSummaries(List.of(new ProfileSummary("第一条总结", "time"),
                new ProfileSummary("第二条总结", "time"), new ProfileSummary("第三条总结", "time")));
    }
}
