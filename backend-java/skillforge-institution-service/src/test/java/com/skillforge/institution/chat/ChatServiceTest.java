package com.skillforge.institution.chat;
import com.skillforge.core.shared.config.SnowflakeIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.chat.ChatConversationDirector;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.ChatService;
import com.skillforge.core.chat.ChatSessionService;
import com.skillforge.core.chat.ChatTeamGateway;
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
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.profile.UserProfileSummarizer;
import com.skillforge.core.shared.tracing.TraceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 宿主侧对话编排测试。
 *
 * <p>验证 Chat 入口把执行权交给 Runtime gateway，并冻结用户消息与可见 Runtime 输出。</p>
 */
class ChatServiceTest {

    private QuotaService quotaService;
    private CostRecorder costRecorder;
    private MemoryService memoryService;
    private SessionService sessionService;
    private AgentMapper agentMapper;
    private ChatRuntimeGateway runtimeGateway;
    private ChatRuntimeHistory runtimeHistory;
    private ChatService chatService;
    private SessionMailboxService sessionMailboxService;
    private String tenantId;
    private Long endUserId;

    /** 为宿主侧每个用例组装 Chat 门面及其职责组件。 */
    @BeforeEach
    void setUp() {
        quotaService = mock(QuotaService.class);
        costRecorder = mock(CostRecorder.class);
        memoryService = mock(MemoryService.class);
        sessionService = mock(SessionService.class);
        agentMapper = mock(AgentMapper.class);
        runtimeGateway = mock(ChatRuntimeGateway.class);
        runtimeHistory = mock(ChatRuntimeHistory.class);
        sessionMailboxService = mock(SessionMailboxService.class);

        ChatTeamGateway teamGateway = mock(ChatTeamGateway.class);
        ChatSessionService chatSessions = new ChatSessionService(
                new com.skillforge.core.agent.AgentQueryService(agentMapper), memoryService, sessionService,
                sessionMailboxService, runtimeHistory, teamGateway,
                new com.skillforge.core.chat.integration.publicread.PublicOutputGuard("./data/artifacts"));
        ChatConversationDirector director = new ChatConversationDirector(
                quotaService, costRecorder, memoryService, sessionService,
                mock(UserProfileSummarizer.class), chatSessions, sessionMailboxService, runtimeGateway,
                teamGateway);
        chatService = new ChatService(director, chatSessions);

        tenantId = String.valueOf(SnowflakeIds.nextId());
        endUserId = SnowflakeIds.nextId();
        TenantContext.setTenantId(tenantId);
        RequestContext.setTraceId("request-trace");
        RequestContext.setUserId("member-1");

        SessionView session = SessionView.builder().sessionId("sess_1").tenantId(Long.parseLong(tenantId))
                .endUserId(endUserId).build();
        when(sessionService.getOrCreate(
                anyString(), any(), any(), any(), any(), any())).thenReturn(session);
        when(sessionService.require(anyString(), anyString())).thenReturn(session);
        when(runtimeHistory.read(any())).thenAnswer(invocation -> {
            SessionView requested = invocation.getArgument(0);
            return new ChatHistoryResponse(
                    requested.getSessionId(), List.of(), null, List.of(), List.of(),
                    ChatHistoryResponse.Collaboration.empty());
        });

        when(memoryService.append(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ChatMessageRecord(
                        "msg_stub", "assistant", "stub", Instant.now(), Map.of()));
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatRuntimeGateway.Submission submission = invocation.getArgument(0);
            return new ChatResult(ChatConstants.STATUS_COMPLETED, submission.session().getSessionId(), "gexec_1", null, null, null);
        }).when(runtimeGateway).submit(any());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        TenantContext.clear();
    }

    @Test
    void chatWithoutTargetIsRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> chatService.runChat(
                new ChatRequest("你好", null, null, null, null, null, null, null,
                        "client-1", null, Map.of("end_user_id", endUserId)),
                null));
        assertTrue(ex.getMessage().contains("请先选择 Agent"));
        verify(runtimeGateway, never()).submit(any());
    }

    @Test
    void explicitSelectedAgentSubmitsToRuntimeGateway() {
        Long agentId = SnowflakeIds.nextId();

        ChatResult result = chatService.runChat(
                new ChatRequest("你好", null, String.valueOf(agentId), null, null, null, null, null,
                        "client-3", null, Map.of("end_user_id", endUserId)),
                null);

        assertEquals(ChatConstants.STATUS_COMPLETED, result.status());
        ArgumentCaptor<ChatRuntimeGateway.Submission> submission =
                ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify(runtimeGateway).submit(submission.capture());
        assertEquals(String.valueOf(agentId), submission.getValue().agentId());
    }

    @Test
    void normalAgentOutputIsFrozenInOutboxWithoutAnotherOnlineDeliveryPath() throws Exception {
        Long agentId = SnowflakeIds.nextId();
        ChatEventSink live = mock(ChatEventSink.class);
        var outputBuilder = SessionOutboxEntry.builder();
        outputBuilder.tenantId(Long.parseLong(tenantId));
        outputBuilder.sessionId("sess_1");
        outputBuilder.runtimeRunId("gexec_1");
        outputBuilder.sessionSequence(1L);
        outputBuilder.eventType("message.delta");
        outputBuilder.createdAt(Instant.parse("2026-09-30T00:00:00Z"));
        when(sessionMailboxService.appendOutput(eq(tenantId), eq("sess_1"), anyString(),
                eq("message.delta"), any(), any(), eq("gexec_1"))).thenAnswer(invocation -> {
                    outputBuilder.eventId(invocation.getArgument(2));
                    outputBuilder.payload(invocation.getArgument(4));
                    TraceContext trace = invocation.getArgument(5);
                    outputBuilder.inputId(trace.inputId());
                    outputBuilder.rootInputId(trace.rootInputId());
                    outputBuilder.traceId(trace.traceId());
                    outputBuilder.sourceEventId(trace.eventId());
                    return outputBuilder.build();
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatRuntimeGateway.Submission submission = invocation.getArgument(0);
            submission.sink().sendRuntime("message.delta", Map.of(
                    "content", "完成", "runtime_event_id", "runtime-event-1", "message_id", "runtime-message-1"),
                    "gexec_1");
            return new ChatResult(ChatConstants.STATUS_COMPLETED, submission.session().getSessionId(), "gexec_1", null, null, null);
        }).when(runtimeGateway).submit(any());

        chatService.runChat(new ChatRequest("你好", "sess_1", String.valueOf(agentId), null, null,
                null, null, null, "client-outbox", null,
                Map.of("end_user_id", endUserId)), live);

        verify(runtimeGateway).submit(any());
        var order = inOrder(sessionMailboxService, live);
        order.verify(live).send(eq(ChatConstants.EVENT_CONNECTED), any());
        order.verify(sessionMailboxService).appendOutput(eq(tenantId), eq("sess_1"),
                org.mockito.ArgumentMatchers.startsWith("output:"), eq("message.delta"), any(),
                eq(new TraceContext("client-outbox", "client-outbox", "request-trace",
                        "span:request-trace", "client-outbox", null)), eq("gexec_1"));
        verify(live,never()).send(eq("message.delta"),any());
        verify(sessionMailboxService, never()).appendOutput(any(), any(), any(),
                eq(ChatConstants.EVENT_CONNECTED), any(), any());
    }

    @Test
    void workflowPathIsClosedWithoutCallingRuntime() {
        ApiException error = assertThrows(ApiException.class, () -> chatService.runChat(
                new ChatRequest("查询申请", null, null, null, "workflow-example",
                        null, null, null, "client-3", null,
                        Map.of("subject_id", "S1001", "location_id", "L2002",
                                "end_user_id", endUserId)),
                null));
        assertEquals(422, error.getStatus().value());
        assertTrue(error.getMessage().contains("直连 Workflow 入口已关闭"));
        verify(runtimeGateway, never()).submit(any());
    }

    @Test
    void chatDoesNotPreloadKnowledgeAndPassesFrozenCausalityToRuntime() {
        Long agentId = SnowflakeIds.nextId();

        chatService.runChat(new ChatRequest("请查询", null, String.valueOf(agentId), null, null, null, null, null,
                "client-evidence", null, Map.of("end_user_id", endUserId)), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(memoryService).append(eq(tenantId), eq("sess_1"), eq(endUserId),
                eq(ChatConstants.ROLE_USER), eq("请查询"), metadata.capture(), eq("client-evidence"));
        assertEquals(null, metadata.getValue().get("knowledge_observation"));
        ArgumentCaptor<ChatRuntimeGateway.Submission> submission =
                ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify(runtimeGateway).submit(submission.capture());
        assertEquals("client-evidence", submission.getValue().request().context().get("input_id"));
        assertEquals("client-evidence", submission.getValue().request().context().get("root_input_id"));
        assertEquals("request-trace", submission.getValue().request().context().get("trace_id"));
        assertEquals("member-1", submission.getValue().request().context().get("delegation_requester_id"));
        assertEquals(endUserId, submission.getValue().endUserId());
    }

    @Test
    void queuedRuntimeReceiptDoesNotClaimLegacyInbox() {
        org.mockito.Mockito.doReturn(
                new ChatResult("queued", "sess_1", null, null, null, null))
                .when(runtimeGateway).submit(any());

        ChatResult result = chatService.runChat(new ChatRequest("请稍后处理", "sess_1", "123", null, null,
                null, null, null, "queued-1", null,
                Map.of("end_user_id", endUserId)), null);

        assertEquals("queued", result.status());
        assertEquals("sess_1", result.sessionId());
    }

    @Test
    void sendToSessionQueuesDeltaInDurableOutbox() {
        SessionView session = SessionView.builder().sessionId("sess_1").endUserId(endUserId).build();
        when(sessionService.require(tenantId, "sess_1")).thenReturn(session);
        when(memoryService.append(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ChatMessageRecord("m_active_1", "assistant", "文本", Instant.now(), Map.of()));
        ChatMessageRecord record = chatService.sendToSession(tenantId, "sess_1", "文本", null, null, null);
        assertEquals("m_active_1", record.messageId());
        verify(memoryService).append(anyString(), anyString(), any(), eq("assistant"), eq("文本"), any(), isNull());
        verify(sessionMailboxService).appendOutput(eq(tenantId), eq("sess_1"),
                eq("proactive-message:m_active_1"), eq("message.delta"),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "message.delta".equals(payload.get("event"))
                                && payload.get("data") instanceof Map<?, ?>));
    }

    @Test
    void sendToSessionMergesCardUpdate() {
        SessionView session = SessionView.builder().sessionId("sess_1").endUserId(endUserId).build();
        when(sessionService.require(tenantId, "sess_1")).thenReturn(session);
        when(memoryService.upsert(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new ChatMessageRecord("m_merge_1", "assistant", "更新", Instant.now(), Map.of()));
        chatService.sendToSession(tenantId, "sess_1", "更新",
                Map.of("card_id", "progress", "type", "info", "title", "进度"), null, "m_merge_1");
        verify(memoryService).upsert(anyString(), anyString(), any(),
                eq("assistant"), eq("更新"), any(), eq("m_merge_1"));
        verify(sessionMailboxService).appendOutput(eq(tenantId), eq("sess_1"),
                org.mockito.ArgumentMatchers.startsWith("proactive-update:m_merge_1:"),
                eq("message.card.update"),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "message.card.update".equals(payload.get("event"))));
    }

    @Test
    void historyReturnsPersistedMessages() {
        ChatMessageRecord record = new ChatMessageRecord(
                "msg-1", "assistant", "已为您办理资料查询", Instant.now(), Map.of());
        when(memoryService.listAll(anyString(), anyString())).thenReturn(List.of(record));

        ChatHistoryResponse history = chatService.history(tenantId, "sess_1");

        assertEquals("sess_1", history.sessionId());
        assertEquals(1, history.messages().size());
        assertEquals("已为您办理资料查询", history.messages().get(0).content());
    }

    @Test
    void createSessionWithNumericAgentIdCreatesSession() {
        Map<String, Object> result = chatService.createSession(
                new CreateSessionRequest("123456", null, null, endUserId), tenantId);

        assertEquals("sess_1", result.get("session_id"));
        verify(sessionService).getOrCreate(
                eq(tenantId), eq(endUserId), isNull(), eq(123456L), isNull(), isNull());
    }

    @Test
    void createSessionWithAgentKeyResolvesAgent() {
        AgentDO agent = new AgentDO();
        agent.setId(888L);
        agent.setTenantId(Long.parseLong(tenantId));
        agent.setAgentKey("test-service-agent");
        agent.setStatus("published");
        when(agentMapper.selectOne(any())).thenReturn(agent);

        Map<String, Object> result = chatService.createSession(
                new CreateSessionRequest("test-service-agent", null, null, endUserId), tenantId);

        assertEquals("sess_1", result.get("session_id"));
        verify(sessionService).getOrCreate(
                eq(tenantId), eq(endUserId), isNull(), eq(888L), isNull(), isNull());
    }

    @Test
    void createSessionWithUnknownAgentRejected() {
        when(agentMapper.selectOne(any())).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> chatService.createSession(
                new CreateSessionRequest("no-such-agent", null, null, endUserId), tenantId));

        assertEquals(404, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("Agent 不存在"));
        verify(sessionService, never()).getOrCreate(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createSessionWithoutTargetRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> chatService.createSession(
                new CreateSessionRequest(null, null, null, endUserId), tenantId));

        assertEquals(422, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("必须且只能指定一个 agent_id 或 team_id"));
        verify(sessionService, never()).getOrCreate(anyString(), any(), any(), any(), any(), any());
    }
}
