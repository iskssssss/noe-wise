package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.chat.ChatService;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 断线重连只能读取已冻结的 Outbox，不能再次进入 Agent/Workflow 决策。 */
class ChatControllerTest {

    @Test
    void clientMessageIdLengthIsCheckedBeforeChatSubmission() throws Exception {
        var chat = mock(ChatService.class);
        var sessions = mock(SessionService.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller(chat, mock(SessionMailboxService.class), sessions))
                .setControllerAdvice(new com.skillforge.institution.exception.GlobalExceptionHandler(json))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json))
                .build();
        TenantContext.setTenantId("7");
        String acceptedId = "x".repeat(64);
        when(chat.runChat(any(), any())).thenReturn(new ChatResult(
                "queued", "session-1", "run:" + acceptedId, null, null, null, acceptedId, false));

        for (String path : List.of("/api/v1/chat", "/api/v1/chat/sync")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("session_id", "session-1", "agent_id", "agent-1",
                            "client_message_id", "x".repeat(65), "content", List.of(Map.of("type", "text", "text", "你好"))))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        org.mockito.Mockito.verifyNoInteractions(chat, sessions);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chat")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("session_id", "session-1", "agent_id", "agent-1",
                        "client_message_id", acceptedId, "content", List.of(Map.of("type", "text", "text", "你好"))))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        verify(chat).runChat(org.mockito.ArgumentMatchers.argThat(request -> acceptedId.equals(request.clientMessageId())), any());
    }

    @Test
    void limitsRequireCurrentSessionAndExposeUnifiedInputCapacities() {
        var chat = mock(ChatService.class);
        var sessions = mock(SessionService.class);
        TenantContext.setTenantId("7");
        var limits = controller(chat, mock(SessionMailboxService.class), sessions).limits("session-1").data();
        verify(sessions).require("7", "session-1");
        assertEquals(50L * 1024 * 1024, limits.attachments().maxFileBytes());
        assertEquals(16, limits.attachments().maxFiles());
        assertEquals(200L * 1024 * 1024, limits.attachments().maxTotalBytes());
        assertEquals(64, limits.references().maxItems());
        assertEquals(2048, limits.references().maxFileSelectors());
        org.mockito.Mockito.verifyNoInteractions(chat);
    }

    @Test
    void channelQuotaCardDoesNotPretendInputWasAcceptedAndOtherSourcesKeep429() {
        var chat = mock(ChatService.class);
        var sessions = mock(SessionService.class);
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        when(sessions.requireWritable("7", "session-1")).thenReturn(session);
        var request = new ChatRequest("你好", "session-1", "agent-1", null, null,
                null, null, null, "input-1", null, null);
        var quota = new com.skillforge.core.billing.quota.QuotaExceededException(
                java.time.Instant.parse("2026-09-30T15:59:59Z"), java.time.Instant.parse("2026-09-30T16:00:00Z"));
        when(chat.runChat(request, null)).thenThrow(quota);
        var controller = controller(chat, mock(SessionMailboxService.class), sessions);
        TenantContext.setTenantId("7");

        var response = controller.chat(request, com.skillforge.institution.config.ExternalAuthPaths.SOURCE_CHANNEL);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("quota_exhausted", response.getBody().data().status());
        org.junit.jupiter.api.Assertions.assertNull(response.getBody().data().executionId());
        org.junit.jupiter.api.Assertions.assertNull(response.getBody().data().inputId());
        assertEquals("MONTHLY_QUOTA_EXHAUSTED", response.getBody().data().card().get("code"));
        assertEquals(1L, response.getBody().data().card().get("retry_after_seconds"));
        for (String source : new String[]{null, "jwt", com.skillforge.institution.config.ExternalAuthPaths.SOURCE_OPENAPI}) {
            org.junit.jupiter.api.Assertions.assertSame(quota, org.junit.jupiter.api.Assertions.assertThrows(
                    com.skillforge.core.billing.quota.QuotaExceededException.class, () -> controller.chat(request, source)));
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ChannelContext.clear();
        ServicePrincipalContext.clear();
        RequestContext.clear();
    }

    @Test
    void cursorPullReplaysOutboxWithoutExecutingChatInput() {
        ChatService chatService = mock(ChatService.class);
        AgentRuntimeExecutionProjection projection = mock(AgentRuntimeExecutionProjection.class);
        SessionMailboxService mailbox = mock(SessionMailboxService.class);
        SessionService sessions = mock(SessionService.class);
        SessionView session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        when(sessions.require("7", "session-1")).thenReturn(session);
        var outputBuilder = SessionOutboxEntry.builder();
        outputBuilder.createdAt(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        outputBuilder.sessionId("session-1");
        outputBuilder.eventId("output-1");
        outputBuilder.sessionSequence(1L);
        outputBuilder.payload(Map.of("event", "message.delta", "data", Map.of("content", "已完成")));
        var output = outputBuilder.build();
        when(mailbox.listOutputsAfter("7", "session-1", "output-0", 500))
                .thenReturn(List.of(output));
        ChatController controller = new ChatController(
                chatService,
                new ObjectMapper(),
                projection,
                mailbox,
                sessions,
                mock(AgentMapper.class),
                mock(com.skillforge.core.foundation.access.FoundationAccess.class));
        TenantContext.setTenantId("7");

        ApiResponse<SessionOutboxCursorResponse> response =
                controller.outboxEvents("session-1", "output-0", 500);

        verify(mailbox).listOutputsAfter("7", "session-1", "output-0", 500);
        verify(chatService, never()).runChat(any(), any());
        verify(projection).reconcile(session);
        assertEquals("output-1", response.data().nextCursor());
    }

    @Test
    void replayValidatesOpaqueCursorOnceAndAdvancesOnlyTheConfirmedSequence() {
        var mailbox = mock(SessionMailboxService.class);
        var sessions = mock(SessionService.class);
        TenantContext.setTenantId("7");
        when(mailbox.resolveOutputSequence("7", "session-1", "opaque-start")).thenReturn(17L);
        var firstPage = java.util.stream.LongStream.rangeClosed(18, 517).mapToObj(sequence -> {
            var outputBuilder = SessionOutboxEntry.builder();
            outputBuilder.createdAt(java.time.Instant.parse("2026-09-29T00:00:00Z"));
            outputBuilder.sessionId("session-1"); outputBuilder.eventId("opaque-" + sequence);
            outputBuilder.sessionSequence(sequence);
            outputBuilder.payload(Map.of("event", "message.delta", "data", Map.of("content", "公开内容")));
            var output = outputBuilder.build();
            return output;
        }).toList();
        when(mailbox.listOutputsAfterSequence("7", "session-1", 17, 500)).thenReturn(firstPage);
        when(mailbox.listOutputsAfterSequence("7", "session-1", 517, 500)).thenReturn(List.of());
        var response = controller(mock(ChatService.class), mailbox, sessions).replay("session-1", "opaque-start");
        assertEquals("no-store, no-cache, no-transform", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals(org.springframework.http.MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        verify(sessions).require("7", "session-1");
        verify(mailbox).resolveOutputSequence("7", "session-1", "opaque-start");
        verify(mailbox).listOutputsAfterSequence("7", "session-1", 17, 500);
        verify(mailbox).listOutputsAfterSequence("7", "session-1", 517, 500);
        verify(mailbox, never()).listOutputsAfter(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void subscribeDisablesBufferingAndKeepsCursorValidationBeforeStreamingResponse() {
        var mailbox = mock(SessionMailboxService.class);
        var sessions = mock(SessionService.class);
        var session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        TenantContext.setTenantId("7");
        when(sessions.require("7", "session-1")).thenReturn(session)
                .thenThrow(new IllegalStateException("session access revoked"));
        when(mailbox.resolveOutputSequence("7", "session-1", "last-confirmed")).thenReturn(42L);
        var response = controller(mock(ChatService.class), mailbox, sessions)
                .subscribe("session-1", "older-query-cursor", "last-confirmed");
        assertEquals("no-store, no-cache, no-transform", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals(org.springframework.http.MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        verify(mailbox).resolveOutputSequence("7", "session-1", "last-confirmed");
        verify(mailbox, never()).resolveOutputSequence("7", "session-1", "older-query-cursor");
    }

    @Test
    void chatReturnsAcceptedReceiptInApiResponse() {
        ChatService chatService = mock(ChatService.class);
        SessionMailboxService mailbox = mock(SessionMailboxService.class);
        SessionService sessions = mock(SessionService.class);
        SessionView session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        ChatRequest request = new ChatRequest("你好", "session-1", "agent-1", null, null,
                null, null, null, "input-1", null, null);
        ChatResult result = new ChatResult("accepted", "session-1", "run-1", null, null, null, "input-1", false);
        when(sessions.requireWritable("7", "session-1")).thenReturn(session);
        when(chatService.runChat(request, null)).thenReturn(result);
        ChatController controller = controller(chatService, mailbox, sessions);
        TenantContext.setTenantId("7");

        var response = controller.chat(request, null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(result, response.getBody().data());
        var receipt = new ObjectMapper().valueToTree(response.getBody().data());
        assertEquals("input-1", receipt.path("inputId").asText());
        assertEquals(false, receipt.path("replayed").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(receipt.has("messages"));
        org.junit.jupiter.api.Assertions.assertFalse(receipt.has("pending"));
        verify(sessions).requireWritable("7", "session-1");
        verify(chatService).runChat(request, null);
    }

    @Test
    void cursorPullReturnsTheSameFrozenOutboxProjectionWithoutExecutingChat() {
        ChatService chatService = mock(ChatService.class);
        SessionMailboxService mailbox = mock(SessionMailboxService.class);
        SessionService sessions = mock(SessionService.class);
        SessionView session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        when(sessions.require("7", "session-1")).thenReturn(session);
        var outputBuilder = SessionOutboxEntry.builder();
        outputBuilder.createdAt(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        outputBuilder.sessionId("session-1");
        outputBuilder.eventId("output-2");
        outputBuilder.sessionSequence(2L);
        outputBuilder.inputId("input-1");
        outputBuilder.rootInputId("root-1");
        outputBuilder.payload(Map.of("event", "message.completed", "data", Map.of("content", "完成")));
        var output = outputBuilder.build();
        when(mailbox.listOutputsAfter("7", "session-1", "output-1", 2))
                .thenReturn(List.of(output));
        ChatController controller = controller(chatService, mailbox, sessions);
        TenantContext.setTenantId("7");

        ApiResponse<SessionOutboxCursorResponse> response = controller.outboxEvents("session-1", "output-1", 2);

        assertEquals("output-2", response.data().nextCursor());
        assertEquals(1, response.data().events().size());
        assertEquals("message.completed", response.data().events().get(0).event());
        assertEquals("root-1", response.data().events().get(0).data().get("root_input_id"));
        verify(chatService, never()).runChat(any(), any());
    }

    @Test
    void cursorPullKeepsCursorForAnEmptyPageAndClampsTheLimit() {
        SessionMailboxService mailbox = mock(SessionMailboxService.class);
        SessionService sessions = mock(SessionService.class);
        SessionView session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        when(sessions.require("7", "session-1")).thenReturn(session);
        when(mailbox.listOutputsAfter("7", "session-1", "output-2", 1))
                .thenReturn(List.of());
        ChatController controller = controller(mock(ChatService.class), mailbox, sessions);
        TenantContext.setTenantId("7");

        ApiResponse<SessionOutboxCursorResponse> response = controller.outboxEvents("session-1", "output-2", 0);

        assertEquals("output-2", response.data().nextCursor());
        assertEquals(List.of(), response.data().events());
    }

    @Test
    void sessionDetailIncludesTheTeamTarget() {
        SessionService sessions = mock(SessionService.class);
        SessionView session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        org.mockito.Mockito.when(session.getTeamId()).thenReturn("team-1");
        when(sessions.require("7", "session-1")).thenReturn(session);
        ChatController controller = controller(mock(ChatService.class), mock(SessionMailboxService.class), sessions);
        TenantContext.setTenantId("7");

        var response = controller.sessionDetail("session-1");

        assertEquals("team-1", response.data().session().teamId());
    }

    @Test
    void subscriptionRechecksTrustedMemberAndStopsBeforeReadingAfterRevocation() throws Exception {
        var mailbox=mock(SessionMailboxService.class);var sessions=mock(SessionService.class);
        notifications(mailbox);
        var foundation=mock(com.skillforge.core.foundation.access.FoundationAccess.class);
        var subject=new com.skillforge.core.foundation.access.FoundationAccess.Subject(false,"7","9");
        when(foundation.currentSubject()).thenReturn(subject);
        var session=org.mockito.Mockito.mock(SessionView.class);org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");when(sessions.require("7","session-1")).thenReturn(session);
        var observed=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{observed.countDown();throw new com.skillforge.core.shared.exception.ApiException(HttpStatus.FORBIDDEN,"权限已撤销");})
                .when(sessions).requireCurrentRead("7","session-1",subject);
        var controller=new ChatController(mock(ChatService.class),new ObjectMapper(),mock(AgentRuntimeExecutionProjection.class),mailbox,sessions,mock(AgentMapper.class),foundation);
        TenantContext.setTenantId("7");controller.subscribe("session-1",null,null);
        org.junit.jupiter.api.Assertions.assertTrue(observed.await(2,java.util.concurrent.TimeUnit.SECONDS));
        verify(sessions).requireCurrentRead("7","session-1",subject);
        verify(foundation,never()).withSubject(any(),any());
        verify(mailbox,never()).listOutputsAfterSequence(any(),any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt());
        assertEquals("7",TenantContext.getTenantId());
    }

    @Test
    void sessionDetailReturnsAuthoritativeBindingsAndClosedStateWhenAgentWasDeleted() throws Exception {
        var sessions = mock(SessionService.class);
        var session = SessionView.builder().sessionId("session-1").tenantId(7L)
                .endUserId(9223372036854775806L).agentId(9223372036854775807L)
                .metadata(Map.of("closed_at", "2026-10-29T00:00:00+08:00")).build();
        when(sessions.require("7", "session-1")).thenReturn(session);
        when(sessions.isOpenPlatformClosed(session)).thenCallRealMethod();
        TenantContext.setTenantId("7");
        var controller = controller(mock(ChatService.class), mock(SessionMailboxService.class), sessions);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json))
                .build();
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/chat/sessions/detail").param("sessionId", "session-1")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        var body = json.readTree(result.getResponse().getContentAsString());
        assertEquals("9223372036854775806", body.path("data").path("end_user_id").textValue());
        assertEquals("9223372036854775807", body.path("data").path("target_agent_id").textValue());
        assertEquals(true, body.path("data").path("closed").booleanValue());
        assertEquals(true, body.path("data").path("agent").isNull());
        verify(sessions).require("7", "session-1");
    }

    @Test
    void sessionDetailPreservesTeamBindingForOpenSession() {
        var sessions = mock(SessionService.class);
        var session = SessionView.builder().sessionId("team-session").tenantId(7L)
                .endUserId(42L).teamId("team-B").build();
        when(sessions.require("7", "team-session")).thenReturn(session);
        when(sessions.isOpenPlatformClosed(session)).thenCallRealMethod();
        TenantContext.setTenantId("7");
        var response = controller(mock(ChatService.class), mock(SessionMailboxService.class), sessions)
                .sessionDetail("team-session");
        assertEquals("team-B", response.data().session().teamId());
        assertEquals("42", response.data().endUserId());
        assertEquals(false, response.data().closed());
    }

    private ChatController controller(
            ChatService chatService, SessionMailboxService mailbox, SessionService sessions) {
        notifications(mailbox);
        return new ChatController(
                chatService,
                new ObjectMapper(),
                mock(AgentRuntimeExecutionProjection.class),
                mailbox,
                sessions,
                mock(AgentMapper.class),
                mock(com.skillforge.core.foundation.access.FoundationAccess.class));
    }

    /** 测试连接使用真实有界通知回执，使异常退出同样释放等待资源。 */
    private static void notifications(SessionMailboxService mailbox) {
        var notifications = new com.skillforge.core.chat.session.SessionOutputNotifications();
        when(mailbox.observeOutputs(any(), any())).thenAnswer(call -> notifications.observe(call.getArgument(0), call.getArgument(1)));
    }

}
