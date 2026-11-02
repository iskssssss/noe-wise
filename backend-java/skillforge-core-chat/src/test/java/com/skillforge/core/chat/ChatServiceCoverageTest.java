package com.skillforge.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentQueryService;
import com.skillforge.core.agent.AgentView;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.request.CreateSessionRequest;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.quota.QuotaService;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.constant.ChatConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.profile.UserProfileSummarizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** Chat 编排核心路径的模块内覆盖测试；机构层控制器测试不能替代 core-chat 覆盖率。 */
class ChatServiceCoverageTest {

    private final AgentQueryService agents = mock(AgentQueryService.class);
    private final QuotaService quota = mock(QuotaService.class);
    private final CostRecorder costs = mock(CostRecorder.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final UserProfileSummarizer profileSummarizer = mock(UserProfileSummarizer.class);
    private final SessionMailboxService mailbox = mock(SessionMailboxService.class);
    private final ChatRuntimeGateway runtimeGateway = mock(ChatRuntimeGateway.class);
    private final ChatTeamGateway teamGateway = mock(ChatTeamGateway.class);
    private final ChatRuntimeHistory runtimeHistory = mock(ChatRuntimeHistory.class);

    private ChatService service;
    private String tenantId;
    private Long endUserId;
    private SessionView session;

    /** 为每个用例建立与生产装配一致的组件图和冻结身份夹具。 */
    @BeforeEach
    void setUp() {
        ChatSessionService chatSessions = new ChatSessionService(
                agents, memory, sessions, mailbox, runtimeHistory, teamGateway,
                new com.skillforge.core.chat.integration.publicread.PublicOutputGuard("./data/artifacts"));
        ChatConversationDirector director = new ChatConversationDirector(
                quota, costs, memory, sessions, profileSummarizer,
                chatSessions, mailbox, runtimeGateway, teamGateway);
        service = new ChatService(director, chatSessions);
        tenantId = "7001";
        endUserId = 7002L;
        TenantContext.setTenantId(tenantId);
        RequestContext.setTraceId("request-trace");
        RequestContext.setUserId("7003");

        session = mock(SessionView.class);
        when(session.getSessionId()).thenReturn("sess-1");
        when(session.getTenantId()).thenReturn(Long.parseLong(tenantId));
        when(session.getEndUserId()).thenReturn(endUserId);
        when(sessions.getOrCreate(anyString(), any(), any(), any(), any(), any())).thenReturn(session);
        when(sessions.getOrCreateTeam(anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(session);
        when(sessions.require(anyString(), anyString())).thenReturn(session);
        when(runtimeHistory.read(any())).thenAnswer(invocation -> {
            SessionView requested = invocation.getArgument(0);
            return new ChatHistoryResponse(
                    requested.getSessionId(), List.of(), null, List.of(), List.of(),
                    ChatHistoryResponse.Collaboration.empty());
        });
        when(memory.append(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ChatMessageRecord("m-1", "assistant", "完成", Instant.now(), Map.of()));
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatRuntimeGateway.Submission submission = invocation.getArgument(0);
            return new ChatResult(ChatConstants.STATUS_COMPLETED, submission.session().getSessionId(), "run-1", null, null, null);
        }).when(runtimeGateway).submit(any());
    }

    @AfterEach
    void tearDown() {
        com.skillforge.common.context.RequestContext.clear();
        TenantContext.clear();
    }

    @Test
    void missingClientMessageIdIsGeneratedOnceAndSharedByRuntimeAndArchive() {
        var request = new ChatRequest("确认", "sess-1", "7", null, null, null, null, null,
                null, null, Map.of("end_user_id", endUserId));
        service.runChat(request, null);
        var submitted = org.mockito.ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify(runtimeGateway).submit(submitted.capture());
        var actual = submitted.getValue().request();
        assertNotNull(actual.clientMessageId()); assertFalse(actual.clientMessageId().isBlank());
        assertEquals("input:" + actual.clientMessageId(), actual.context().get("input_id"));
        verify(memory).append(eq(tenantId), eq("sess-1"), eq(endUserId), eq("user"), eq("确认"), anyMap(), eq(actual.clientMessageId()));
    }

    @Test
    void rejectsChatWithoutExplicitAgentOrWorkflowTarget() {
        ApiException error = assertThrows(ApiException.class, () -> service.runChat(request(null, null), null));
        assertTrue(error.getMessage().contains("请先选择 Agent"));
        verify(runtimeGateway, never()).submit(any());
    }

    @Test
    void routesAgentMessageWithFrozenContextAndPersistsUserMessage() {
        Long agentId = 7100L;

        ChatResult result = service.runChat(request(String.valueOf(agentId), null), null);

        assertEquals(ChatConstants.STATUS_COMPLETED, result.status());
        var order = inOrder(runtimeGateway, memory);
        ArgumentCaptor<ChatRuntimeGateway.Submission> submission =
                ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        order.verify(runtimeGateway).submit(submission.capture());
        order.verify(memory).append(eq(tenantId), eq("sess-1"), eq(endUserId), eq(ChatConstants.ROLE_USER),
                eq("你好"), any(), any());
        assertEquals("input-1", submission.getValue().request().context().get("input_id"));
        assertEquals("request-trace", submission.getValue().request().context().get("trace_id"));
    }

    @Test
    void identicalClientMessageReplaySkipsHostWritesAndResubmitsRuntime() {
        when(memory.findByMessageId(tenantId, "input-1")).thenReturn(
                new MemoryService.ArchivedChatMessage("sess-1",
                        new ChatMessageRecord("input-1", ChatConstants.ROLE_USER, "你好", Instant.now(), Map.of("content_fingerprint", com.skillforge.core.chat.contract.ChatContent.fingerprint(com.skillforge.core.chat.contract.ChatContent.text("你好"))))));

        ChatResult result = service.runChat(request("7100", null), null);

        assertEquals(ChatConstants.STATUS_COMPLETED, result.status());
        verify(memory, never()).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verify(sessions, never()).incrementMessageCount(anyString(), anyString());
        verify(profileSummarizer, never()).submitCasualSummary(anyString(), anyString());
        verify(costs, never()).recordRequest(any(), any(), any(), any(), any());
        verify(runtimeGateway).submit(any());
    }

    @Test
    void imageOnlyMessagePersistsSelectorsAndRejectsChangedImageOnReplay() throws Exception {
        var image = new com.skillforge.core.chat.contract.ChatContent.Image("67d0c2cf-a7a0-4b4c-b283-78e2c12cf711");
        var request = new ChatRequest(List.of(image), "sess-1", "7100", null, null, null, null, null,
                "input-image", null, Map.of("end_user_id", endUserId), List.of());
        service.runChat(request, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String,Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(memory).append(eq(tenantId), eq("sess-1"), eq(endUserId), eq(ChatConstants.ROLE_USER),
                eq(""), metadata.capture(), eq("input-image"));
        assertEquals(List.of(image), metadata.getValue().get("content_parts"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var archived = mapper.readTree(mapper.writeValueAsString(metadata.getValue())).path("content_parts");
        assertEquals("image", archived.get(0).path("type").asText());
        assertEquals(image, mapper.treeToValue(archived.get(0), com.skillforge.core.chat.contract.ChatContent.class));
        when(memory.findByMessageId(tenantId, "input-image")).thenReturn(new MemoryService.ArchivedChatMessage(
                "sess-1", new ChatMessageRecord("input-image", "user", "", Instant.now(), metadata.getValue())));
        var changed = new ChatRequest(List.of(new com.skillforge.core.chat.contract.ChatContent.Image(
                "fb4002b0-783f-4b26-b64c-3ab039ec330b")), "sess-1", "7100", null, null, null, null, null,
                "input-image", null, Map.of("end_user_id", endUserId), List.of());
        assertEquals(409, assertThrows(ApiException.class, () -> service.runChat(changed, null)).getStatus().value());
    }

    @Test
    void attachmentOnlyMessagePersistsSelectorsAndKeepsReplayIdempotent() {
        var attachmentIds = List.of("67d0c2cf-a7a0-4b4c-b283-78e2c12cf711");
        var request = new ChatRequest(List.of(), "sess-1", "7100", null, null, null, null, null,
                "input-attachment", null, Map.of("end_user_id", endUserId, "attachment_ids", attachmentIds), List.of());

        service.runChat(request, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String,Object>> metadata = ArgumentCaptor.forClass(Map.class);
        var order = inOrder(runtimeGateway, memory);
        order.verify(runtimeGateway).submit(any());
        order.verify(memory).append(eq(tenantId), eq("sess-1"), eq(endUserId), eq(ChatConstants.ROLE_USER),
                eq(""), metadata.capture(), eq("input-attachment"));
        assertEquals(List.of(), metadata.getValue().get("content_parts"));
        assertEquals(attachmentIds, metadata.getValue().get("attachment_ids"));
        when(memory.findByMessageId(tenantId, "input-attachment")).thenReturn(new MemoryService.ArchivedChatMessage(
                "sess-1", new ChatMessageRecord("input-attachment", "user", "", Instant.now(), metadata.getValue())));

        service.runChat(request, null);

        verify(runtimeGateway, times(2)).submit(any());
        verify(memory).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verify(sessions).incrementMessageCount(tenantId, "sess-1");
        verify(sessions).touch(tenantId, "sess-1");
        verify(profileSummarizer).submitCasualSummary(tenantId, "sess-1");
        verify(costs).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void changedAttachmentSelectionOnReplayIsRejectedWithoutHostWrite() {
        var attachmentIds = List.of("67d0c2cf-a7a0-4b4c-b283-78e2c12cf711");
        var content = com.skillforge.core.chat.contract.ChatContent.text("依据附件回答");
        when(memory.findByMessageId(tenantId, "input-attachment")).thenReturn(new MemoryService.ArchivedChatMessage(
                "sess-1", new ChatMessageRecord("input-attachment", "user", "依据附件回答", Instant.now(), Map.of(
                        "content_fingerprint", com.skillforge.core.chat.contract.ChatContent.fingerprint(content),
                        "attachment_ids", attachmentIds))));
        var changed = new ChatRequest(content, "sess-1", "7100", null, null, null, null, null,
                "input-attachment", null, Map.of("end_user_id", endUserId, "attachment_ids",
                        List.of("fb4002b0-783f-4b26-b64c-3ab039ec330b")), List.of());

        assertEquals(409, assertThrows(ApiException.class, () -> service.runChat(changed, null)).getStatus().value());

        verify(runtimeGateway).submit(any());
        verify(memory, never()).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verify(sessions, never()).incrementMessageCount(anyString(), anyString());
        verify(sessions, never()).touch(anyString(), anyString());
        verify(costs, never()).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void rejectedAttachmentOnlyInputDoesNotPersistProductMessage() {
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.FORBIDDEN, "Runtime 拒绝附件访问"))
                .when(runtimeGateway).submit(any());
        var request = new ChatRequest(List.of(), "sess-1", "7100", null, null, null, null, null,
                "input-attachment", null, Map.of("end_user_id", endUserId, "attachment_ids",
                        List.of("67d0c2cf-a7a0-4b4c-b283-78e2c12cf711")), List.of());

        assertEquals(403, assertThrows(ApiException.class, () -> service.runChat(request, null)).getStatus().value());

        verify(memory, never()).findByMessageId(anyString(), anyString());
        verify(memory, never()).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verify(sessions, never()).incrementMessageCount(anyString(), anyString());
        verify(sessions, never()).touch(anyString(), anyString());
        verify(costs, never()).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void conflictingProductMessageIsRejectedAfterRuntimeReplayWithoutHostWrite() {
        when(memory.findByMessageId(tenantId, "input-1")).thenReturn(
                new MemoryService.ArchivedChatMessage("sess-1",
                        new ChatMessageRecord("input-1", ChatConstants.ROLE_USER, "原消息", Instant.now(), Map.of())));

        ApiException error = assertThrows(ApiException.class, () -> service.runChat(request("7100", null), null));

        assertEquals(409, error.getStatus().value());
        verify(runtimeGateway).submit(any());
        verify(memory, never()).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void runtimeRejectionDoesNotPersistProductMessageOrAdvanceSessionCounters() {
        org.mockito.Mockito.doThrow(
                new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "Runtime 拒绝访问"))
                .when(runtimeGateway).submit(any());

        ApiException error = assertThrows(ApiException.class,
                () -> service.runChat(request("7100", null), null));

        assertEquals(403, error.getStatus().value());
        verify(memory, never()).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verify(sessions, never()).incrementMessageCount(anyString(), anyString());
        verify(sessions, never()).touch(anyString(), anyString());
        verify(profileSummarizer, never()).submitCasualSummary(anyString(), anyString());
        verify(costs, never()).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void submitsStudioChatToPublishedPrimaryAgentWithoutChannelRouting() {
        com.skillforge.common.context.RequestContext.setUserId("admin-1");
        when(session.getAgentId()).thenReturn(null);
        when(session.getTeamId()).thenReturn("team-1");
        when(teamGateway.primaryAgentId(tenantId, "team-1")).thenReturn("11");
        org.mockito.Mockito.doReturn(new ChatResult("queued", "sess-1",
                null, null, null, null)).when(runtimeGateway).submit(any());
        ChatRequest request = new ChatRequest("团队任务", "sess-1", null, "team-1", null,
                null, null, null, "team-input-1", null,
                Map.of("input_id", "team-input-1", "end_user_id", endUserId));

        ChatResult result = service.runChat(request, null);

        assertEquals("queued", result.status());
        verify(teamGateway).primaryAgentId(tenantId, "team-1");
        ArgumentCaptor<ChatRuntimeGateway.Submission> submission =
                ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify(runtimeGateway).submit(submission.capture());
        assertEquals("team-1", submission.getValue().request().teamId());
        assertEquals("11", submission.getValue().agentId());
        verify(sessions).getOrCreateTeam(eq(tenantId), eq(endUserId), eq("sess-1"),
                eq("team-1"), isNull(), isNull());
        verifyNoMoreInteractions(teamGateway);
        verifyNoInteractions(agents);
        verify(memory).append(eq(tenantId), eq("sess-1"), eq(endUserId),
                eq(ChatConstants.ROLE_USER), eq("团队任务"), any(), any());
        verify(costs).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void collaborationOnlyCommandDoesNotPersistBusinessMessageOrAdvanceSessionCounters() {
        org.mockito.Mockito.doReturn(new ChatResult(
                "collaboration_updated", "sess-1", null, null, null, ChatHistoryResponse.Collaboration.empty()))
                .when(runtimeGateway).submit(any());
        ChatRequest request = new ChatRequest(
                "/plan", "sess-1", "7100", null, null,
                null, null, null, "plan-command-1", null,
                Map.of("input_id", "plan-command-1", "end_user_id", endUserId));

        ChatResult result = service.runChat(request, null);

        assertEquals("collaboration_updated", result.status());
        verify(runtimeGateway).submit(any());
        verify(memory, never()).findByMessageId(anyString(), anyString());
        verify(memory, never()).append(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verify(sessions, never()).incrementMessageCount(anyString(), anyString());
        verify(sessions, never()).touch(anyString(), anyString());
        verify(profileSummarizer, never()).submitCasualSummary(anyString(), anyString());
        verify(costs, never()).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void acceptedRuntimeInputConvergesProductMessageAfterFirstWriteFailure() {
        when(memory.append(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("product-message-write-failed"))
                .thenReturn(new ChatMessageRecord(
                        "input-1", ChatConstants.ROLE_USER, "你好", Instant.now(), Map.of()));
        ChatRequest request = request("7100", null);

        assertThrows(IllegalStateException.class, () -> service.runChat(request, null));
        ChatResult replay = service.runChat(request, null);

        assertEquals(ChatConstants.STATUS_COMPLETED, replay.status());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ChatRuntimeGateway.Submission> submissions =
                ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify(runtimeGateway, times(2)).submit(submissions.capture());
        assertEquals("input-1", submissions.getAllValues().get(0).request().context().get("input_id"));
        assertEquals("input-1", submissions.getAllValues().get(1).request().context().get("input_id"));
        verify(memory, times(2)).append(anyString(), anyString(), any(), anyString(), anyString(), any(),
                eq("input-1"));
        verify(sessions).incrementMessageCount(tenantId, "sess-1");
        verify(costs).recordRequest(any(), any(), any(), any(), any());
    }

    @Test
    void queuedRuntimeReceiptIsReturnedWithoutClaimingLegacyInbox() {
        org.mockito.Mockito.doReturn(
                new ChatResult("queued", "sess-1", null, null, null, null))
                .when(runtimeGateway).submit(any());

        ChatResult result = service.runChat(new ChatRequest("稍后处理", "sess-1", "7100", null, null,
                null, null, null, "queued-1", null,
                Map.of("input_id", "queued-1", "end_user_id", endUserId)), null);

        assertEquals("queued", result.status());
        assertEquals("sess-1", result.sessionId());
        verify(runtimeGateway).submit(any());
    }

    @Test
    void normalAgentOutputForwardsConnectedWithoutPersistingIt() {
        Long agentId = 7500L;
        ChatEventSink sink = mock(ChatEventSink.class);

        ChatResult result = service.runChat(new ChatRequest("问题", "sess-1", String.valueOf(agentId), null, null,
                null, null, null, "online-1", null,
                Map.of("input_id", "online-1", "end_user_id", endUserId)), sink);

        assertEquals(ChatConstants.STATUS_COMPLETED, result.status());
        verify(runtimeGateway).submit(any());
        verify(sink).send(eq(ChatConstants.EVENT_CONNECTED), any());
        verifyNoInteractions(mailbox);
    }

    @Test
    void directWorkflowEntryIsClosed() {
        ApiException error = assertThrows(ApiException.class, () -> service.runChat(request(null, "wf-1"), null));

        assertEquals(422, error.getStatus().value());
        assertTrue(error.getMessage().contains("直连 Workflow 入口已关闭"));
        verify(runtimeGateway, never()).submit(any());
    }

    @Test
    void proactiveTextAndCardUpdatesUseDurableMailbox() {
        when(memory.append(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ChatMessageRecord("m-text", "assistant", "文本", Instant.now(), Map.of()));
        service.sendToSession(tenantId, "sess-1", "文本", null, null, null);
        verify(mailbox).appendOutput(eq(tenantId), eq("sess-1"), eq("proactive-message:m-text"),
                eq(ChatConstants.EVENT_MESSAGE_DELTA), any());

        when(memory.upsert(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new ChatMessageRecord("m-card", "assistant", "更新", Instant.now(), Map.of()));
        service.sendToSession(tenantId, "sess-1", "更新", Map.of("type", "info"), Map.of("tone", "quiet"), "m-card");
        verify(mailbox).appendOutput(eq(tenantId), eq("sess-1"), anyString(),
                eq(ChatConstants.EVENT_MESSAGE_CARD_UPDATE), any());
    }

    @Test
    void historyAndSessionCreationValidateTenantAndAgentKey() {
        when(memory.listAll(tenantId, "sess-1"))
                .thenReturn(List.of(new ChatMessageRecord("m-h", "assistant", "历史", Instant.now(), Map.of())));
        ChatHistoryResponse history = service.history(tenantId, "sess-1");
        assertEquals("历史", history.messages().getFirst().content());

        Map<String, Object> created = service.createSession(
                new CreateSessionRequest("7100", null, null, endUserId), tenantId);
        assertEquals("sess-1", created.get("session_id"));
        verify(sessions).getOrCreate(eq(tenantId), eq(endUserId), isNull(), eq(7100L), isNull(), isNull());

        AgentView agent = new AgentView(7300L, Long.parseLong(tenantId), "agent", "published", null, "agent-key");
        when(agents.findPublishedByKey(org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn(agent);
        service.createSession(new CreateSessionRequest("agent-key", null, null, endUserId), tenantId);
        verify(sessions).getOrCreate(eq(tenantId), eq(endUserId), isNull(), eq(7300L), isNull(), isNull());
    }

    @Test
    void teamHistoryHidesMemoryAnswerWhenRuntimeRevokesItsCurrentVisibility() {
        when(session.getAgentId()).thenReturn(null);
        when(session.getTeamId()).thenReturn("team-1");
        ChatMessageRecord user = new ChatMessageRecord("user-1", "user", "问题", Instant.EPOCH, Map.of());
        ChatMessageRecord visible = new ChatMessageRecord("answer-1", "assistant", "可见答复",
                Instant.EPOCH.plusSeconds(1), Map.of("team_answer_ref", "team-answer:1"));
        ChatMessageRecord revoked = new ChatMessageRecord("answer-2", "assistant", "已撤权答复",
                Instant.EPOCH.plusSeconds(2), Map.of("team_answer_ref", "team-answer:2"));
        when(memory.listAll(tenantId, "sess-1")).thenReturn(List.of(user, visible, revoked));
        when(runtimeHistory.read(session)).thenReturn(new ChatHistoryResponse(
                "sess-1", List.of(visible), null, List.of(), List.of(),
                ChatHistoryResponse.Collaboration.empty()));

        ChatHistoryResponse history = service.history(tenantId, "sess-1");

        assertEquals(List.of("user-1", "answer-1"),
                history.messages().stream().map(ChatMessageRecord::messageId).toList());
    }

    @Test
    void unknownAgentKeyIsRejectedBeforeSessionCreation() {
        when(agents.findPublishedByKey(org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.createSession(
                new CreateSessionRequest("missing-agent", null, null, endUserId), tenantId));

        assertTrue(error.getMessage().contains("Agent 不存在"));
        verify(sessions, never()).getOrCreate(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsNullRequestAndMissingTenantContext() {
        assertThrows(ApiException.class, () -> service.runChat(null, null));

        TenantContext.clear();
        assertThrows(ApiException.class, () -> service.runChat(request("7100", null), null));
    }

    @Test
    void createSessionRejectsMissingTarget() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.createSession(new CreateSessionRequest("", null, null, null), tenantId));
        assertTrue(error.getMessage().contains("agent_id"));
    }

    @Test
    void historyProjectsPendingTaskAndActivities() {
        Map<String, Object> pendingHitl = Map.of(
                ChatConstants.EXECUTION_ID, "exec-2", "task_type", "human_input");
        ChatHistoryResponse.ActivityItem activity = new ChatHistoryResponse.ActivityItem(
                "confirm", "human_input", "waiting", Map.of("title", "确认"),
                null, null, Instant.EPOCH);
        ChatHistoryResponse.ProcessItem process = new ChatHistoryResponse.ProcessItem(
                "process-1", "run-1", "input-1", "action-1", "tool", 1,
                "succeeded", com.skillforge.core.chat.contract.ProcessPresentation.of("查询天气", "天气已返回"),
                Instant.EPOCH, Instant.EPOCH, "run-1", "input-1", 0, 1, "7", null, null);
        when(runtimeHistory.read(session)).thenReturn(new ChatHistoryResponse(
                "sess-1", List.of(), List.of(pendingHitl), List.of(activity), List.of(process),
                ChatHistoryResponse.Collaboration.empty()));

        ChatHistoryResponse result = service.history(tenantId, "sess-1");

        assertEquals("exec-2", result.pendingInteractions().getFirst().get(ChatConstants.EXECUTION_ID));
        assertEquals("human_input", result.pendingInteractions().getFirst().get("task_type"));
        assertEquals("confirm", result.activities().getFirst().nodeId());
        assertEquals("process-1", result.processes().getFirst().processId());
    }

    @Test
    void sessionsProjectsDefaultUserAndAgentFilter() {
        var directory = new com.skillforge.core.chat.session.SessionDirectoryItem(
                "sess-1", "最近一次对话", 3, java.time.Instant.parse("2026-10-30T01:00:00Z"), 7100L, null, "第一条消息");
        when(sessions.list(tenantId, endUserId, 7100L, null)).thenReturn(List.of(directory));

        var result = service.sessions(tenantId, 7100L, endUserId);

        assertEquals(1, result.size());
        assertEquals(new com.skillforge.core.chat.dto.response.SessionItemResponse(
                directory.sessionId(), directory.summaryText(), directory.messageCount(), directory.updatedAt(),
                directory.agentId(), directory.teamId(), directory.title()), result.getFirst());
        verify(sessions).list(tenantId, endUserId, 7100L, null);
    }

    @Test
    void directWorkflowHitlEntryIsClosed() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.runChat(request(null, "wf-hitl"), mock(ChatEventSink.class)));

        assertEquals(422, error.getStatus().value());
        assertTrue(error.getMessage().contains("直连 Workflow 入口已关闭"));
        verify(runtimeGateway, never()).submit(any());
    }

    /** 构造携带显式终端用户身份的最小对话请求，供 Agent/Workflow 用例复用。 */
    private ChatRequest request(String agentId, String workflowId) {
        return new ChatRequest("你好", "sess-1", agentId, null, workflowId, null, null, null,
                "input-1", null, Map.of("end_user_id", endUserId));
    }
}
