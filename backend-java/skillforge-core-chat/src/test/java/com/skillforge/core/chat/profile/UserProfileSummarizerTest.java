package com.skillforge.core.chat.profile;

import com.skillforge.core.chat.session.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.prompts.PromptDefaults;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.application.TenantDirectoryService;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.chat.persistence.SessionDO;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 闲聊画像摘要的租户扫描、阈值触发和严格输出消费测试。 */
class UserProfileSummarizerTest {
    private final java.util.List<UserProfileSummarizer> instances = new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach
    void closeInstances() { instances.forEach(UserProfileSummarizer::close); }


    @BeforeAll
    static void initTableMetadata() {
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SessionDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ChatMessageDO.class);
    }

    @Test
    void idleScanSummarizesOnlyMeaningfulNonWorkflowMessages() throws Exception {
        TenantDirectoryService tenants = mock(TenantDirectoryService.class);
        SessionMapper sessions = mock(SessionMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        UserProfileService profiles = mock(UserProfileService.class);
        SessionService sessionService = mock(SessionService.class);
        LlmService llm = mock(LlmService.class);
        OperationalSettingsService settings = settings(30);
        SessionDO session = session("s-1", 42L);
        when(tenants.tenantIds()).thenReturn(List.of("11"));
        when(sessions.selectList(any())).thenReturn(List.of(session));
        when(messages.selectList(any())).thenReturn(List.of(
                message("user", "我喜欢周末徒步", null),
                message("assistant", "已记录", null),
                message("user", "workflow", java.util.Map.of("execution_id", "e-1"))));
        when(llm.generate(anyString(), any(), any())).thenReturn(
                new LlmResult("{\"profile\":\"喜欢徒步\",\"facts\":[]}", "test", null));
        UserProfileSummarizer service = service(tenants, sessions, messages, profiles,
                sessionService, llm, settings, mock(StringRedisTemplate.class));

        service.summarizeIdleSessions();

        verify(profiles).appendSummary("11", 42L, "喜欢徒步");
        verify(sessionService).updateSummary("11", "s-1", "喜欢徒步");
        var system = ArgumentCaptor.forClass(String.class);
        var input = ArgumentCaptor.forClass(String.class);
        verify(llm).generate(system.capture(), input.capture(), eq(List.of()));
        assertThat(system.getValue()).isEqualTo(PromptDefaults.CHAT_SUMMARY_PROMPT)
                .doesNotContain("我喜欢周末徒步");
        var data = new ObjectMapper().readTree(input.getValue()).path("messages");
        assertThat(data.size()).isEqualTo(2);
        assertThat(data.get(0).path("role").asText()).isEqualTo("user");
        assertThat(data.get(0).path("content").asText()).isEqualTo("我喜欢周末徒步");
    }

    @Test
    void idleScanHandlesEmptySessionsAndTenantDirectoryServiceFailure() {
        TenantDirectoryService tenants = mock(TenantDirectoryService.class);
        SessionMapper sessions = mock(SessionMapper.class);
        when(tenants.tenantIds()).thenReturn(List.of());
        UserProfileSummarizer service = service(tenants, sessions, mock(ChatMessageMapper.class),
                mock(UserProfileService.class), mock(SessionService.class), mock(LlmService.class),
                settings(30), mock(StringRedisTemplate.class));

        service.summarizeIdleSessions();
        when(tenants.tenantIds()).thenThrow(new IllegalStateException("db down"));
        service.summarizeIdleSessions();
        verify(sessions, never()).selectList(any());
    }

    @Test
    void idleScanAdvancesPastSkippedTwentyAndWrapsOnlyOnceWithoutFillingModelQuota() {
        var tenants=mock(TenantDirectoryService.class);var sessions=mock(SessionMapper.class);var messages=mock(ChatMessageMapper.class);
        var profiles=mock(UserProfileService.class);var sessionService=mock(SessionService.class);var llm=mock(LlmService.class);
        when(tenants.tenantIds()).thenReturn(List.of("11"));
        Instant time=Instant.parse("2026-01-01T00:00:00.123456Z");
        var first=new java.util.ArrayList<SessionDO>();for(int index=0;index<20;index++){var row=session("s-%02d".formatted(index),42L);row.setUpdatedAt(time);first.add(row);}
        var last=session("s-20",42L);last.setUpdatedAt(time);
        when(sessions.selectList(any())).thenReturn(first,List.of(last),List.of(),first);
        var user=message("user","我喜欢摄影和晨跑",null);user.setSessionId("s-20");
        var assistant=message("assistant","已记录你的偏好",null);assistant.setSessionId("s-20");
        when(messages.selectList(any())).thenReturn(List.of(),List.of(user,assistant),List.of());
        when(llm.generate(anyString(),any(),any())).thenReturn(new LlmResult("{\"profile\":\"摄影晨跑\"}","test",null));
        var service=service(tenants,sessions,messages,profiles,sessionService,llm,settings(30),mock(StringRedisTemplate.class));
        service.summarizeIdleSessions();verify(llm,never()).generate(anyString(),any(),any());
        service.summarizeIdleSessions();service.summarizeIdleSessions();
        verify(llm).generate(anyString(),any(),any());verify(sessionService).updateSummary("11","s-20","摄影晨跑");
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SessionDO>> queries=ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(sessions,org.mockito.Mockito.times(4)).selectList(queries.capture());
        for(var query:queries.getAllValues()) {
            assertThat(query.getSqlSegment()).contains("summary_text IS NULL","updated_at <","ORDER BY updated_at ASC,session_id ASC","LIMIT 20");
            assertThat(query.getParamNameValuePairs()).containsValue(11L);
        }
        assertThat(queries.getAllValues().get(1).getSqlSegment()).contains("updated_at >","updated_at =","session_id >");
        assertThat(queries.getAllValues().get(1).getParamNameValuePairs()).containsValue(time).containsValue("s-19");
        assertThat(queries.getAllValues().get(2).getParamNameValuePairs()).containsValue("s-20");
        assertThat(queries.getAllValues().get(3).getSqlSegment()).doesNotContain("session_id >");
    }

    @Test
    void idleCursorsArePerTenantAndClosedInstanceDoesNotReadAgain() {
        var tenants=mock(TenantDirectoryService.class);var sessions=mock(SessionMapper.class);var messages=mock(ChatMessageMapper.class);
        when(tenants.tenantIds()).thenReturn(List.of("11","22"));
        var a=session("tenant-a-last",42L);var b=session("tenant-b-last",43L);
        when(sessions.selectList(any())).thenReturn(List.of(a),List.of(b),List.of(a),List.of(b));
        when(messages.selectList(any())).thenReturn(List.of());
        var service=service(tenants,sessions,messages,mock(UserProfileService.class),mock(SessionService.class),mock(LlmService.class),settings(30),mock(StringRedisTemplate.class));
        service.summarizeIdleSessions();service.summarizeIdleSessions();
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SessionDO>> queries=ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(sessions,org.mockito.Mockito.times(4)).selectList(queries.capture());
        queries.getAllValues().forEach(query->query.getSqlSegment());
        assertThat(queries.getAllValues().get(2).getParamNameValuePairs()).containsValue(11L).containsValue("tenant-a-last").doesNotContainValue("tenant-b-last");
        assertThat(queries.getAllValues().get(3).getParamNameValuePairs()).containsValue(22L).containsValue("tenant-b-last").doesNotContainValue("tenant-a-last");
        service.close();service.summarizeIdleSessions();verify(sessions,org.mockito.Mockito.times(4)).selectList(any());
    }

    @Test
    void casualCounterAddsExpiryAndClaimsOnlyAtThreshold() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("1"), anyString())).thenReturn(1L, 2L);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("2"))).thenReturn(1L);
        OperationalSettingsService settings = settings(2);
        UserProfileSummarizer service = service(mock(TenantDirectoryService.class), mock(SessionMapper.class),
                mock(ChatMessageMapper.class), mock(UserProfileService.class), mock(SessionService.class),
                mock(LlmService.class), settings, redis);

        service.submitCasualSummary("7", "s-7");
        service.submitCasualSummary("7", "s-7");

        verify(redis, org.mockito.Mockito.times(2)).execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                eq(List.of("7:session:s-7:non_workflow_count")), eq("1"), eq("604800000"));
        verify(redis).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                eq(List.of("7:session:s-7:non_workflow_count")), eq("2"));
        verify(redis, never()).delete(anyString());
    }

    /** 满队列不在请求线程调用模型，并保留计数以供后续触发。 */
    @Test
    void boundedQueueRejectsWithoutDeletingTriggerAndClosesPerInstance() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("1"), anyString())).thenReturn(2L);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("2"))).thenReturn(1L);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("2"), anyString())).thenReturn(2L);
        var sessionService = mock(SessionService.class);
        var service = service(mock(TenantDirectoryService.class), mock(SessionMapper.class), mock(ChatMessageMapper.class),
                mock(UserProfileService.class), sessionService, mock(LlmService.class), settings(2), redis);
        var executorField = UserProfileSummarizer.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        var executor = (java.util.concurrent.ThreadPoolExecutor) executorField.get(service);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            int capacity = executor.getQueue().remainingCapacity();
            assertThat(capacity).isPositive().isLessThan(1000);
            for (int index = 0; index < capacity; index++) executor.execute(() -> { });
            service.submitCasualSummary("7", "s-7");
            verify(redis, never()).delete(anyString());
            verify(redis).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                    eq(List.of("7:session:s-7:non_workflow_count")), eq("2"), eq("604800000"));
            verify(sessionService, never()).requireForSystem(anyString(), anyString());
        } finally {
            release.countDown();
            service.close();
        }
        assertThat(executor.isTerminated()).isTrue();
        service.submitCasualSummary("7", "after-close");
        verify(redis, never()).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                eq(List.of("7:session:after-close:non_workflow_count")), anyString(), anyString());
        var another = service(mock(TenantDirectoryService.class), mock(SessionMapper.class), mock(ChatMessageMapper.class),
                mock(UserProfileService.class), mock(SessionService.class), mock(LlmService.class), settings(2), redis);
        assertThat(executorField.get(another)).isNotSameAs(executor);
    }

    /** 归还故障不能省略最终静止等待；中断故障保持原原因与线程标记。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedQuotaRestoreStillWaitsAndPreservesShutdownFailure(boolean interrupted) throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("1"), anyString())).thenReturn(2L);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), eq("2"))).thenReturn(1L);
        var service = service(mock(TenantDirectoryService.class), mock(SessionMapper.class), mock(ChatMessageMapper.class),
                mock(UserProfileService.class), mock(SessionService.class), mock(LlmService.class), settings(2), redis);
        var executor = mock(java.util.concurrent.ThreadPoolExecutor.class);
        var field = UserProfileSummarizer.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(service, executor);
        service.submitCasualSummary("7", "s-7");
        var queued = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(queued.capture());
        when(executor.shutdownNow()).thenReturn(List.of(queued.getValue()));
        if (interrupted) {
            when(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                    .thenThrow(new InterruptedException("controlled-interrupt")).thenReturn(true);
        } else {
            when(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false, true);
            when(executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        }
        try {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::close);
            if (interrupted) {
                assertThat(failure.getMessage()).isEqualTo("profile_summary_shutdown_interrupted");
                assertThat(failure.getCause()).isInstanceOf(InterruptedException.class);
                assertThat(failure.getSuppressed()).hasSize(1);
                assertThat(failure.getSuppressed()[0].getMessage()).isEqualTo("profile_summary_quota_restore_failed");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } else {
                assertThat(failure.getMessage()).isEqualTo("profile_summary_quota_restore_failed");
                verify(executor).awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally { Thread.interrupted(); }
    }

    @Test
    void summarizeSessionAndLlmFailuresAreSafe() throws Exception {
        UserProfileService profiles = mock(UserProfileService.class);
        SessionService sessions = mock(SessionService.class);
        LlmService llm = mock(LlmService.class);
        UserProfileSummarizer service = service(mock(TenantDirectoryService.class), mock(SessionMapper.class),
                mock(ChatMessageMapper.class), profiles, sessions, llm, settings(30),
                mock(StringRedisTemplate.class));
        SessionDO session = session("s-7", 42L);
        List<ChatMessageDO> messages = List.of(message("user", "我喜欢摄影和晨跑", null),
                message("assistant", "已记录你的偏好", null));

        when(llm.generate(anyString(), any(), any())).thenReturn(
                new LlmResult("{\"profile\":\"喜欢摄影\",\"facts\":[{\"content\":\"喜欢摄影\"}]}", "test", null));
        invoke(service, "summarizeSession",
                new Class<?>[] {String.class, String.class, Long.class, List.class, String.class},
                "7", session.getSessionId(), session.getEndUserId(), messages, PromptDefaults.CHAT_SUMMARY_PROMPT);
        verify(profiles).appendSummary("7", 42L, "喜欢摄影");

        when(llm.generate(anyString(), any(), any())).thenReturn(new LlmResult("{\"profile\":\"\"}", "test", null));
        assertThat(invoke(service, "summarize",
                new Class<?>[] {String.class, String.class, Long.class, List.class, String.class},
                "7", "s-7", 42L, messages, PromptDefaults.CHAT_SUMMARY_PROMPT)).isEqualTo("");

        when(llm.generate(anyString(), any(), any())).thenReturn(new LlmResult("plain text", "test", null));
        assertThat(invoke(service, "summarize",
                new Class<?>[] {String.class, String.class, Long.class, List.class, String.class},
                "7", "s-7", 42L, messages, PromptDefaults.CHAT_SUMMARY_PROMPT))
                .isNull();
        when(llm.generate(anyString(), any(), any())).thenThrow(new IllegalStateException("offline"));
        assertThat(invoke(service, "summarize",
                new Class<?>[] {String.class, String.class, Long.class, List.class, String.class},
                "7", "s-7", 42L, messages, PromptDefaults.CHAT_SUMMARY_PROMPT)).isNull();
        assertThat(invoke(service, "parseProfile", new Class<?>[] {String.class}, (Object) null)).isNull();
        assertThat(invoke(service, "parseProfile", new Class<?>[] {String.class}, "{bad")).isNull();
    }

    /** 空画像与格式违约均不能更新用户画像或会话摘要。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "", "plain text", "{bad}", "null", "[]", "{}", "{\"profile\":null}",
            "{\"profile\":[]}", "{\"profile\":{}}", "{\"profile\":42}", "{\"profile\":false}",
            "{\"profile\":\"\"}", "{\"profile\":\"   \"}",
            "前缀 {\"profile\":\"不应写入\"}", "{\"profile\":\"不应写入\"} trailing", "{\"profile\":\"不应写入\"} {}",
            "```json\n{\"profile\":\"不应写入\"}\n```", "[{\"profile\":\"不应写入\"}]"
    })
    void invalidOrEmptyProfileNeverWrites(String output) throws Exception {
        var profiles = mock(UserProfileService.class);
        var sessions = mock(SessionService.class);
        var llm = mock(LlmService.class);
        var service = service(mock(TenantDirectoryService.class), mock(SessionMapper.class), mock(ChatMessageMapper.class),
                profiles, sessions, llm, settings(30), mock(StringRedisTemplate.class));
        when(llm.generate(anyString(), any(), any())).thenReturn(new LlmResult(output, "test", null));
        invoke(service, "summarizeSession",
                new Class<?>[] {String.class, String.class, Long.class, List.class, String.class},
                "7", "s-7", 42L, List.of(message("user", "我喜欢摄影和晨跑", null),
                        message("assistant", "已记录你的偏好", null)), PromptDefaults.CHAT_SUMMARY_PROMPT);
        verify(llm).generate(anyString(), any(), any());
        verify(profiles, never()).appendSummary(anyString(), any(), anyString());
        verify(sessions, never()).updateSummary(anyString(), anyString(), anyString());
    }

    private UserProfileSummarizer service(
            TenantDirectoryService tenants, SessionMapper sessions, ChatMessageMapper messages,
            UserProfileService profiles, SessionService sessionService, LlmService llm,
            OperationalSettingsService settings, StringRedisTemplate redis) {
        var instance = new UserProfileSummarizer(tenants, sessions, messages, profiles,
                sessionService, llm, new ObjectMapper(), settings, redis);
        instances.add(instance);
        return instance;
    }

    private OperationalSettingsService settings(int casualThreshold) {
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new Snapshot(1, java.util.Map.of(
                "profile_summary_idle_minutes", 60L,
                "casual_counter_ttl_days", 7L,
                "casual_threshold", (long) casualThreshold)));
        when(settings.read("prompts")).thenReturn(new Snapshot(1, java.util.Map.of(
                "chat_summary_prompt", PromptDefaults.CHAT_SUMMARY_PROMPT)));
        return settings;
    }

    private SessionDO session(String id, Long userId) {
        SessionDO value = new SessionDO();
        value.setSessionId(id);
        value.setEndUserId(userId);
        value.setUpdatedAt(Instant.now().minusSeconds(3600));
        return value;
    }

    private ChatMessageDO message(String role, String content, java.util.Map<String, Object> metadata) {
        ChatMessageDO value = new ChatMessageDO();
        value.setSessionId("s-1");
        value.setRole(role);
        value.setContent(content);
        value.setMetadata(metadata);
        return value;
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
