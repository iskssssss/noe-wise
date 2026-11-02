package com.skillforge.core.chat;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.outbox.SessionOutboxChatEventSink;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.quota.QuotaService;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.constant.ChatConstants;
import com.skillforge.core.shared.constant.Messages;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.identity.EndUserIdentity;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.profile.UserProfileSummarizer;
import com.skillforge.core.shared.tracing.TraceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 对话入口编排器。
 *
 * <p>只负责冻结入口事实、产品 Session/消息与计量，然后把执行权交给
 * {@link ChatRuntimeGateway}，工作室目标先解析主助手再进入同一 Runtime，产品层只负责 Session、消息和输出投影。</p>
 */
@Service
public class ChatConversationDirector {

    private static final Logger log = LoggerFactory.getLogger(ChatConversationDirector.class);

    private final QuotaService quotaService;
    private final CostRecorder costRecorder;
    private final MemoryService memoryService;
    private final SessionService sessionService;
    private final UserProfileSummarizer userProfileSummarizer;
    private final ChatSessionService chatSessionService;
    private final SessionMailboxService mailbox;
    private final ChatRuntimeGateway runtimeGateway;
    private final ChatTeamGateway teamGateway;

    /**
     * 组装对话编排所需的应用协作者。
     *
     * @param quotaService 配额检查器
     * @param costRecorder 请求根用量记录器
     * @param memoryService 消息记忆服务
     * @param sessionService 会话归属与计数服务
     * @param userProfileSummarizer 闲聊画像总结任务
     * @param chatSessionService 会话应用服务
     * @param mailbox 会话输出邮箱
     * @param runtimeGateway Agent Runtime 提交边界
     * @param teamGateway 工作室主助手查询边界
     */
    public ChatConversationDirector(
            QuotaService quotaService,
            CostRecorder costRecorder,
            MemoryService memoryService,
            SessionService sessionService,
            UserProfileSummarizer userProfileSummarizer,
            ChatSessionService chatSessionService,
            SessionMailboxService mailbox,
            ChatRuntimeGateway runtimeGateway,
            ChatTeamGateway teamGateway) {
        this.quotaService = quotaService;
        this.costRecorder = costRecorder;
        this.memoryService = memoryService;
        this.sessionService = sessionService;
        this.userProfileSummarizer = userProfileSummarizer;
        this.chatSessionService = chatSessionService;
        this.mailbox = mailbox;
        this.runtimeGateway = runtimeGateway;
        this.teamGateway = teamGateway;
    }

    /**
     * 接收一次对话输入并提交给目标 Agent Runtime。
     *
     * @param request 外部对话请求，必须携带正整数 end_user_id
     * @param sink 可选的在线事件出口
     * @return 已完成结果或 queued 状态
     */
    public ChatResult runChat(ChatRequest request, ChatEventSink sink) {
        return runChatInternal(withTraceContext(request), sink);
    }

    /**
     * 冻结身份后创建产品 Session、写入用户消息，并把执行提交给目标入口。
     */
    private ChatResult runChatInternal(ChatRequest request, ChatEventSink sink) {
        long chatStart = System.currentTimeMillis();
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
        }
        quotaService.check(tenantId);
        Long endUserId = requireEntryEndUserId(request);
        String requestId = contextText(request.context(), "input_id",
                "input:" + SnowflakeIds.nextId());
        String rootInputId = contextText(request.context(), "root_input_id", requestId);
        TraceContext trace = new TraceContext(
                requestId, rootInputId,
                contextText(request.context(), "trace_id", requestId),
                contextText(request.context(), "span_id", "span:" + requestId),
                contextText(request.context(), "event_id", requestId),
                string(request.context().get("caused_by_event_id")));
        MeterScope rootScope = MeterScope.open(CostContext.builder()
                .requestId(requestId).endUserId(endUserId).channelId(channelId(request))
                .scopeId(request.scopeId()).scopeType(request.scopeType())
                .callPoint("chat.request").traceContext(trace).build());
        try {
            boolean explicitAgent = request.agentId() != null && !request.agentId().isBlank();
            boolean explicitTeam = request.teamId() != null && !request.teamId().isBlank();
            boolean explicitWorkflow = request.workflowId() != null
                    && !request.workflowId().isBlank();
            if (explicitTeam && (explicitAgent || explicitWorkflow)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "团队对话不可同时指定 agent_id 或 workflow_id");
            }
            SessionView session;
            ChatResult result;
            if (explicitTeam) {
                String primaryAgentId = teamGateway.primaryAgentId(tenantId, request.teamId());
                log.info("工作室主助手对话编排开始 tenant={} teamTag={} agentTag={} hitl={} inputTag={}",
                        tenantId, ChatLogIds.tag(request.teamId()), ChatLogIds.tag(primaryAgentId),
                        request.hitlResponse() != null, ChatLogIds.tag(requestId));
                session = sessionService.getOrCreateTeam(
                        tenantId, endUserId, request.sessionId(), request.teamId(),
                        request.scopeId(), request.scopeType());
                sink = durableSink(tenantId, session.getSessionId(), trace, sink);
                sink.send(ChatConstants.EVENT_CONNECTED,
                        Map.of(ChatConstants.SESSION_ID, session.getSessionId()));
                request = withDelegationRequester(request, session);
                result = runtimeGateway.submit(new ChatRuntimeGateway.Submission(
                        request, session, primaryAgentId, endUserId, sink));
            } else {
                if (!explicitAgent && !explicitWorkflow) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Messages.CHAT_NO_TARGET);
                }
                String selectedAgentId = request.agentId();
                if (selectedAgentId == null || selectedAgentId.isBlank()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "直连 Workflow 入口已关闭，请选择 Agent");
                }
                log.info("对话编排开始 tenant={} agentTag={} hitl={} inputTag={}",
                        tenantId, ChatLogIds.tag(selectedAgentId),
                        request.hitlResponse() != null, ChatLogIds.tag(requestId));
                session = sessionService.getOrCreate(
                        tenantId, endUserId, request.sessionId(),
                        chatSessionService.resolveAgentId(tenantId, selectedAgentId),
                        request.scopeId(), request.scopeType());
                sink = durableSink(tenantId, session.getSessionId(), trace, sink);
                sink.send(ChatConstants.EVENT_CONNECTED,
                        Map.of(ChatConstants.SESSION_ID, session.getSessionId()));
                request = withDelegationRequester(request, session);
                result = runtimeGateway.submit(new ChatRuntimeGateway.Submission(
                        request, session, selectedAgentId, endUserId, sink));
            }
            String sessionId = session.getSessionId();

            boolean hasUserMessage = !request.content().isEmpty() || !attachmentIds(request).isEmpty();
            String inputMessageId = hasUserMessage ? request.clientMessageId() : null;
            // Runtime 或 Team receipt 是入口的权威可重放事实；拒绝时不得先写产品消息或计数。
            boolean collaborationOnly = result != null
                    && "collaboration_updated".equals(result.status())
                    && result.executionId() == null;
            boolean hostReplay = !collaborationOnly && hasUserMessage
                    && replayOrPersistUserMessage(
                            tenantId, sessionId, endUserId, request, trace, inputMessageId);
            if (!collaborationOnly && !hostReplay) {
                sessionService.incrementMessageCount(tenantId, sessionId);
                sessionService.touch(tenantId, sessionId);
                userProfileSummarizer.submitCasualSummary(tenantId, sessionId);
            }
            if (!collaborationOnly && !hostReplay) {
                recordRequest(request, tenantId, sessionId, endUserId, requestId, trace, chatStart);
            }
            log.info("对话编排完成 tenant={} sessionTag={} inputTag={} status={} elapsedMs={}",
                    tenantId, ChatLogIds.tag(sessionId), ChatLogIds.tag(requestId),
                    result == null ? null : result.status(),
                    System.currentTimeMillis() - chatStart);
            return result;
        } finally {
            rootScope.close();
        }
    }

    /**
     * 同 {@code client_message_id} 合法重放跳过插入；绑定不同会话、角色或正文时 409。
     *
     * @return {@code true} 表示已有相同用户消息，本次是宿主侧重放
     */
    private boolean replayOrPersistUserMessage(
            String tenantId, String sessionId, Long endUserId, ChatRequest request,
            TraceContext trace, String inputMessageId) {
        MemoryService.ArchivedChatMessage existing = memoryService.findByMessageId(tenantId, inputMessageId);
        if (existing != null) {
            requireSameUserMessage(existing, sessionId, request);
            return true;
        }
        try {
            memoryService.append(tenantId, sessionId, endUserId, ChatConstants.ROLE_USER,
                    request.message(), userMessageMetadata(request, trace), inputMessageId);
            return false;
        } catch (DuplicateKeyException duplicate) {
            MemoryService.ArchivedChatMessage raced = memoryService.findByMessageId(tenantId, inputMessageId);
            if (raced == null) {
                throw duplicate;
            }
            requireSameUserMessage(raced, sessionId, request);
            return true;
        }
    }

    /** 已占用的 client_message_id 必须绑定同一会话、用户角色和正文。 */
    private void requireSameUserMessage(
            MemoryService.ArchivedChatMessage existing, String sessionId, ChatRequest request) {
        if (!sessionId.equals(existing.sessionId())
                || !ChatConstants.ROLE_USER.equals(existing.record().role())
                || !request.message().equals(existing.record().content())
                || existing.record().metadata() == null
                || !com.skillforge.core.chat.contract.ChatContent.fingerprint(request.content())
                    .equals(existing.record().metadata().get("content_fingerprint"))
                || !attachmentIds(request).equals(existing.record().metadata().getOrDefault("attachment_ids", List.of()))) {
            throw new ApiException(HttpStatus.CONFLICT, "client_message_id 已绑定不同消息");
        }
    }

    /** 记录一次用户请求根用量；细粒度 Provider 用量由 Runtime 负责。 */
    private void recordRequest(
            ChatRequest request, String tenantId, String sessionId, Long endUserId,
            String requestId, TraceContext trace, long chatStart) {
        costRecorder.recordRequest(
                tenantId, requestId + ":" + CostType.REQUEST.value(), requestId,
                CostContext.builder()
                        .sessionId(sessionId).endUserId(endUserId).channelId(channelId(request))
                        .agentId(request.teamId() == null || request.teamId().isBlank()
                                ? chatSessionService.resolveAgentId(tenantId, request.agentId()) : null)
                        .scopeId(request.scopeId()).scopeType(request.scopeType())
                        .callPoint("chat.request").traceContext(trace).build(),
                Instant.ofEpochMilli(chatStart));
    }

    /** 构造用户消息的最小因果元数据，不复制完整请求正文。 */
    private Map<String, Object> userMessageMetadata(ChatRequest request, TraceContext trace) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("client_message_id", empty(request.clientMessageId()));
        metadata.put("input_id", trace.inputId());
        metadata.put("root_input_id", trace.rootInputId());
        metadata.put("trace_id", trace.traceId());
        metadata.put("span_id", trace.spanId());
        metadata.put("content_parts", request.content());
        metadata.put("content_fingerprint", com.skillforge.core.chat.contract.ChatContent.fingerprint(request.content()));
        var attachmentIds = attachmentIds(request);
        if (!attachmentIds.isEmpty()) metadata.put("attachment_ids", attachmentIds);
        return metadata;
    }

    private List<?> attachmentIds(ChatRequest request) {
        return request.context() != null && request.context().get("attachment_ids") instanceof List<?> ids
                ? List.copyOf(ids) : List.of();
    }

    /** 在路由前冻结本次输入的幂等键与追踪因果键。 */
    private ChatRequest withTraceContext(ChatRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "对话请求不能为空");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.context() != null) {
            context.putAll(request.context());
        }
        boolean generatedMessageId = request.clientMessageId() == null || request.clientMessageId().isBlank();
        String messageId = generatedMessageId ? String.valueOf(SnowflakeIds.nextId()) : request.clientMessageId();
        String inputId = generatedMessageId ? "input:" + messageId : messageId;
        String rootInputId = contextText(context, "root_input_id", inputId);
        context.put("input_id", inputId);
        context.put("root_input_id", rootInputId);
        String requestTrace = com.skillforge.common.context.RequestContext.getTraceId();
        if (requestTrace == null || requestTrace.isBlank()) {
            throw new IllegalStateException("Chat admission requires a request trace context");
        }
        context.put("trace_id", requestTrace);
        context.put("span_id", "span:" + requestTrace);
        context.put("event_id", inputId);
        return new ChatRequest(
                request.content(), request.sessionId(), request.agentId(), request.teamId(), request.workflowId(),
                request.scopeId(), request.scopeType(), request.locale(), messageId,
                request.hitlResponse(), context, request.references());
    }

    /** 校验普通对话入口的终端用户身份，并把领域异常映射为稳定的 422。 */
    private Long requireEntryEndUserId(ChatRequest request) {
        try {
            return EndUserIdentity.require(request == null ? null : request.context(), "对话入口");
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }

    /** 当前聊天入口只接收已选目标；事件冻结到 Outbox，订阅通过已提交游标读取。 */
    private ChatEventSink durableSink(String tenantId, String sessionId, TraceContext trace, ChatEventSink liveSink) {
        return new SessionOutboxChatEventSink(mailbox, tenantId, sessionId, trace, liveSink);
    }

    /** 渠道身份只来自认证边界，不接受请求体声明。 */
    private String channelId(ChatRequest request) {
        return ChannelContext.getChannelId();
    }

    /** 委派后台任务只复用入口冻结的认证主体，禁止从模型参数或调度线程补身份。 */
    private ChatRequest withDelegationRequester(ChatRequest request, SessionView session) {
        Map<String, Object> context = new LinkedHashMap<>(request.context());
        context.remove("delegation_requester_id");
        context.remove("delegation_channel_id");
        if (session.getChannelId() != null) {
            context.put("delegation_channel_id", session.getChannelId().toString());
        } else {
            String requester = RequestContext.getUserId();
            if (requester == null || requester.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "对话缺少已认证请求者");
            }
            context.put("delegation_requester_id", requester);
        }
        return new ChatRequest(request.content(), request.sessionId(), request.agentId(), request.teamId(),
                request.workflowId(), request.scopeId(), request.scopeType(), request.locale(),
                request.clientMessageId(), request.hitlResponse(), context, request.references());
    }

    /** 从上下文读取非空文本；缺失或空白时使用调用方指定的稳定回退值。 */
    private String contextText(Map<String, Object> context, String key, String fallback) {
        String value = context == null ? null : string(context.get(key));
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 将可选请求文本规范化为非 null 字段。 */
    private String empty(String value) {
        return value == null ? "" : value;
    }

    /** 安全转换对象为字符串，null 保持 null。 */
    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
