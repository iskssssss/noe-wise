package com.skillforge.institution.controller;

import jakarta.validation.constraints.NotNull;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.skillforge.institution.common.TenantIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.quota.QuotaExceededException;
import com.skillforge.institution.config.ExternalAuthPaths;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.constant.ChatConstants;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.request.CreateSessionRequest;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.dto.response.SessionItemResponse;
import com.skillforge.core.chat.dto.response.SessionDetailResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.ChatService;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.chat.dto.chat.SendMessageRequest;
import com.skillforge.core.chat.dto.chat.SendMessageResponse;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口控制器。
 *
 * <p>POST 仅返回持久输入受理回执，GET 订阅与回放仅消费 Outbox；断线不会取消 Run。
 */
@Tag(name = "对话", description = "输入受理 / SSE 订阅 / 历史恢复 / 会话列表（B 端与 C 端共用）")
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 对话编排服务。 */
    private final ChatService chatService;

    /** Jackson 对象映射器，用于 SSE 数据序列化。 */
    private final ObjectMapper objectMapper;

    /** 已提交 Runtime 事实的宿主投影恢复。 */
    private final com.skillforge.institution.runtime.AgentRuntimeEventProjection projection;

    /** Session Inbox/Outbox 门面；SSE 重连只读取冻结输出。 */
    private final SessionMailboxService sessionMailboxService;

    /** 会话服务（会话本体查询）。 */
    private final SessionService sessionService;

    /** Agent 表映射器（会话关联 Agent）。 */
    private final AgentMapper agentMapper;

    /** SSE 业务页发送前与空闲有界检查复验真实成员，不依赖工作线程 JWT。 */
    private final FoundationAccess foundation;


    /**
     * 构造对话控制器。
     *
     * @param chatService             对话编排服务
     * @param objectMapper            Jackson 对象映射器
     * @param projection              持久事实投影恢复
     */
    public ChatController(
            ChatService chatService,
            ObjectMapper objectMapper,
            com.skillforge.institution.runtime.AgentRuntimeEventProjection projection,
            SessionMailboxService sessionMailboxService,
            SessionService sessionService,
            AgentMapper agentMapper,
            FoundationAccess foundation) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.projection = projection;
        this.sessionMailboxService = sessionMailboxService;
        this.sessionService = sessionService;
        this.agentMapper = agentMapper;
        this.foundation = foundation;
    }

    /**
     * 对话输入受理接口；返回回执后通过 GET 订阅执行事实。
     *
     * @param request 对话请求
     * @return 输入回执；有 Run 为 202，纯协作命令为 200
     */
    @RequirePermission("foundation.sessions.run")
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ChatResult>> chat(@RequestBody ChatRequest request,
            @RequestAttribute(value = ExternalAuthPaths.ATTR_AUTH_SOURCE, required = false) String authSource) {
        requireSubmission(request);
        try {
            ChatResult result = chatService.runChat(request, null);
            return ResponseEntity.status(result.executionId() == null ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(result));
        } catch (QuotaExceededException quota) {
            return ResponseEntity.ok(ApiResponse.success(quotaCard(request, authSource, quota)));
        } finally {
            MeterScope.clear();
        }
    }

    /** 仅认证器确认的 C 端渠道返回卡片；API Key 和管理端不能以请求正文伪造渠道身份。 */
    private ChatResult quotaCard(ChatRequest request, String authSource, QuotaExceededException quota) {
        if (!ExternalAuthPaths.SOURCE_CHANNEL.equals(authSource)) throw quota;
        return new ChatResult("quota_exhausted", request.sessionId(), null,
                Map.of("type", "info", "code", QuotaExceededException.CODE, "title", "本月配额已用尽",
                        "body", quota.getMessage(), "resets_at", quota.resetsAt(),
                        "retry_after_seconds", quota.retryAfterSeconds()), quota.getMessage(), null);
    }

    /** 首次提交和重试都必须带稳定输入身份；订阅不会经过此入口。 */
    private void requireSubmission(ChatRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()
                || ChatConstants.NEW_SESSION.equals(request.sessionId())
                || request.clientMessageId() == null || request.clientMessageId().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "session_id 和 client_message_id 必填，请先创建会话");
        }
        sessionService.requireWritable(TenantIds.requireTenantId(), request.sessionId());
    }

    /** 经当前会话授权后返回两端共用的输入容量，后端受理与传输仍执行最终校验。 */
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/chat/limits")
    @Operation(summary = "查询当前会话的附件、引用和文件选择容量")
    public ApiResponse<com.skillforge.core.chat.contract.ChatLimitsResponse> limits(
            @RequestParam("session_id") String sessionId) {
        sessionService.require(TenantIds.requireTenantId(), sessionId);
        return ApiResponse.success(com.skillforge.core.chat.contract.ChatLimitsResponse.defaults());
    }

    /** 仅订阅持久输出。按同一 Outbox 游标连续读取，避免注册与回放之间的事件丢失或乱序。 */
    @RequirePermission("foundation.sessions.read")
    @GetMapping(value = "/chat/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(
            @RequestParam("session_id") String sessionId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        String tenantId = TenantIds.requireTenantId();
        SessionView subscriptionSession = sessionService.require(tenantId, sessionId);
        projection.reconcile(subscriptionSession);
        var historyRetention = projection.historyRetentionState(subscriptionSession);
        String after = lastEventId == null || lastEventId.isBlank() ? cursor : lastEventId;
        // 在响应提交前验证游标，401/403/409 仍为普通 JSON 错误。
        long afterSequence = sessionMailboxService.resolveOutputSequence(tenantId, sessionId, after);
        String traceId = RequestContext.getTraceId();
        String userId = RequestContext.getUserId();
        String channelId = ChannelContext.getChannelId();
        var principal = ServicePrincipalContext.get();
        FoundationAccess.Subject subject = channelId == null && principal == null ? foundation.currentSubject() : null;
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        SseEventSink sink = new SseEventSink(emitter, objectMapper);
        var changes = sessionMailboxService.observeOutputs(tenantId, sessionId);
        emitter.onCompletion(changes::close);
        emitter.onError(ignored -> changes.close());
        emitter.onTimeout(changes::close);
        Thread.ofVirtual().name("chat-event-subscription").start(() -> {
            TenantContext.setTenantId(tenantId);
            ChannelContext.setChannelId(channelId);
            if(principal != null) ServicePrincipalContext.set(principal);
            RequestContext.setTraceId(traceId);
            RequestContext.setUserId(userId);
            long nextSequence = afterSequence;
            long heartbeat = System.nanoTime();
            try {
                var polling = new ChatSubscriptionPolling(tenantId, sessionId, subject,
                        sessionService, sessionMailboxService, System::nanoTime);
                polling.authorized(() -> historyRetention.ifPresent(value -> sink.send("history.retired", value)));
                while (!sink.isClosed()) {
                    // 捕获通知版本后才读持久页，提交发生在读页与阻塞之间也不会丢失唤醒。
                    long observedVersion = changes.version();
                    long readingAfter = nextSequence;
                    nextSequence = polling.poll(readingAfter, output -> {
                        SessionOutboxEventProjector.Event event = projection.publicEvent(output);
                        sink.send(event.event(), event.data());
                    });
                    if (System.nanoTime() - heartbeat >= 15_000_000_000L) {
                        sink.heartbeat();
                        heartbeat = System.nanoTime();
                    }
                    if (nextSequence == readingAfter)
                        changes.awaitChange(observedVersion, java.time.Duration.ofSeconds(5));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ApiException rejected) {
                boolean denied = rejected.getStatus() == HttpStatus.UNAUTHORIZED || rejected.getStatus() == HttpStatus.FORBIDDEN;
                sink.error(denied ? "会话访问权限已失效，请刷新权限" : "事件订阅暂不可用，请重新读取会话状态",
                        denied ? "SESSION_ACCESS_DENIED" : "EVENT_STREAM_UNAVAILABLE", !denied);
            } catch (RuntimeException unavailable) {
                sink.error("事件订阅暂不可用，请重新读取会话状态", "EVENT_STREAM_UNAVAILABLE", true);
            } finally {
                changes.close();
                sink.complete();
                TenantContext.clear();
                ChannelContext.clear();
                ServicePrincipalContext.clear();
                RequestContext.clear();
            }
        });
        return streamingResponse(emitter);
    }

    /**
     * 既有集成使用的 JSON 受理入口；返回不表示 Run 已完成。
     *
     * @param request 对话请求
     * @return 对话结果
     */
    @RequirePermission("foundation.sessions.run")
    @PostMapping("/chat/sync")
    public ApiResponse<ChatResult> syncChat(@RequestBody ChatRequest request,
            @RequestAttribute(value = ExternalAuthPaths.ATTR_AUTH_SOURCE, required = false) String authSource) {
        requireSubmission(request);
        try {
            return ApiResponse.success(chatService.runChat(request, null));
        } catch (QuotaExceededException quota) {
            return ApiResponse.success(quotaCard(request, authSource, quota));
        } finally {
            // 计量作用域清理兜底（异常路径防线程池串号）
            MeterScope.clear();
        }
    }

    /**
     * 主动消息发送（超管抄送 / 外部事件回推）。
     *
     * <p>向指定会话受理助手消息：消息和冻结事件写入持久 Outbox，订阅按
     * sessionSequence 读取。{@code merge_message_id} 非空时同 id 覆盖
     * （message.card.update），消息表不追加。
     *
     * @param request 消息请求
     * @return 发送结果
     */
    @RequirePermission("foundation.sessions.run")
    @PostMapping("/messages")
    public ApiResponse<SendMessageResponse> sendMessage(@Valid @RequestBody SendMessageRequest request) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
        }
        sessionService.requireWritable(tenantId, request.sessionId());
        ChatMessageRecord record = chatService.sendToSession(
                tenantId,
                request.sessionId(),
                request.content(),
                request.card(),
                request.render(),
                request.mergeMessageId());
        return ApiResponses.wrap(new SendMessageResponse("queued", record.messageId(), false));
    }

    /**
     * 会话历史恢复接口。
     *
     * @param sessionId 会话 ID
     * @return 历史消息
     */
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/chat/history")
    public ApiResponse<ChatHistoryResponse> history(@RequestParam("session_id") String sessionId,
            @RequestParam(value="limit",defaultValue="30") int limit,
            @RequestParam(value="before_message_id",required=false) String beforeMessageId) {
        return ApiResponses.wrap(chatService.history(TenantIds.requireTenantId(), sessionId, limit, beforeMessageId));
    }

    /**
     * 历史会话列表（按最新消息时间倒序）。
     *
     * @param agentId Agent 归属过滤（管理端对话记录），可空
     * @return 当前租户内符合筛选条件的会话列表
     */
    @Operation(summary = "历史会话列表",
            description = "end_user_id 和 agentId 可选；未选择用户时返回当前租户全部会话，按最新消息时间倒序")
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/chat/sessions")
    public ApiResponse<List<SessionItemResponse>> sessions(
            @RequestParam(required = false) Long agentId,
            @RequestParam(value = "team_id", required = false) String teamId,
            @RequestParam(value = "end_user_id", required = false) Long endUserId) {
        return ApiResponses.wrap(chatService.sessions(TenantIds.requireTenantId(), agentId, endUserId, teamId));
    }

    /**
     * 创建空会话（外部集成方主动建会话）。
     *
     * <p>供外部业务系统桥接"先建会话再推送主动消息"：工作流必须经 Agent 调用，
     * 会话目标为当前机构已发布 Agent 的数字 ID 或 agent_key；不存在、跨机构或未发布
     * 返回 404，缺失返回 422。end_user_id 必须由入口显式提供；服务端不创建或选择默认用户。
     *
     * @param request 建会话请求
     * @return 会话结果（仅含明确命名的 session_id）
     */
    @Operation(summary = "创建空会话",
            description = "主动为终端用户创建会话（agent_id 目标）；返回真实 session_id 供后续推送消息/对话使用")
    @RequirePermission("foundation.sessions.create")
    @PostMapping("/chat/sessions")
    public ApiResponse<Map<String, Object>> createSession(@RequestBody CreateSessionRequest request) {
        return ApiResponses.wrap(chatService.createSession(request, TenantIds.requireTenantId()));
    }

    public record SessionCreateCommand(@JsonProperty("request_id") java.util.UUID requestId,
            CreateSessionRequest payload) { }

    @RequirePermission("foundation.sessions.create")
    @PostMapping("/chat/sessions/create-with-receipt")
    public ApiResponse<Map<String, Object>> createSessionWithReceipt(@RequestBody SessionCreateCommand command) {
        if (command == null || command.requestId() == null || command.payload() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "会话创建请求无效");
        try {
            var result = chatService.createSessionWithReceipt(command.payload(), TenantIds.requireTenantId(), command.requestId().toString());
            return ApiResponses.wrap(Map.of("request_id", command.requestId().toString(), "session_id", result.get("session_id")));
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "SESSION_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }

    @RequirePermission("foundation.sessions.read")
    @GetMapping("/chat/sessions/create-with-receipt/result")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sessionCreationResult(@RequestParam("request_id") java.util.UUID requestId) {
        String session = sessionService.creationResult(TenantIds.requireTenantId(), requestId.toString());
        return session == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ApiResponses.wrap(Map.of("request_id", requestId.toString(), "session_id", session)));
    }

    /**
     * 关闭机构端会话。关闭保留消息与运行时审计记录，后续续会话请求返回冲突。
     *
     * @param sessionId 会话 ID
     * @param request   可选关闭原因
     * @return 关闭结果
     */
    @Operation(summary = "关闭会话",
            description = "关闭指定机构端会话并保留消息、Inbox、Outbox 与审计记录；重复调用幂等")
    @RequirePermission("foundation.sessions.cancel")
    @PostMapping("/chat/sessions/close")
    public ApiResponse<Map<String, Object>> closeSessionEndpoint(
            @Valid @RequestBody CloseSessionCommand command) {
        return ApiResponses.wrap(closeSession(command.sessionId(), command.payload()));
    }

    public Map<String, Object> closeSession(String sessionId, Map<String, Object> request) {
        String reason = request == null || request.get("reason") == null
                ? null : String.valueOf(request.get("reason"));
        sessionService.closeForTenant(TenantIds.requireTenantId(), sessionId, reason);
        return Map.of("session_id", sessionId, "status", "closed");
    }

    /**
     * 会话事件回放接口（SSE，从 Session Outbox 读取冻结事件）。
     *
     * @param sessionId 会话 ID
     * @return 禁止缓存和代理缓冲的 SSE 响应
     */
    @RequirePermission("foundation.sessions.read")
    @GetMapping(value = "/chat/replay", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> replay(
            @RequestParam("session_id") String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        String tenantId = TenantIds.requireTenantId();
        sessionService.require(tenantId, sessionId);
        long afterSequence = sessionMailboxService.resolveOutputSequence(tenantId, sessionId, lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        SseEventSink sink = new SseEventSink(emitter, objectMapper);
        ReplayResult replay = replayOutbox(tenantId, sessionId, lastEventId, afterSequence, sink);
        sink.send(ChatConstants.EVENT_REPLAY_COMPLETED, replayCompleted(sessionId, replay));
        emitter.complete();
        return streamingResponse(emitter);
    }

    /** 传输层不合并已提交的正文快照；缓存和代理缓冲会破坏真正的增量可见性。 */
    private ResponseEntity<SseEmitter> streamingResponse(SseEmitter emitter) {
        log.debug("构造聊天 SSE 响应");
        var response = ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
        log.debug("聊天 SSE 响应已构造");
        return response;
    }

    /**
     * 按持久 Outbox cursor 拉取冻结事件。
     *
     * <p>这是只读传输面：先校验当前租户对 Session 的归属，再返回与 SSE 相同的事件投影；
     * 不领取、确认或重新执行业务输入。</p>
     */
    @Operation(summary = "按 cursor 读取会话事件",
            description = "从持久 Session Outbox 返回 SSE 同形事件；读取不改变发布状态或执行会话输入")
    @RequirePermission("foundation.sessions.read")
    @GetMapping(value = "/chat/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SessionOutboxCursorResponse> outboxEvents(
            @RequestParam("session_id") String sessionId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        String tenantId = TenantIds.requireTenantId();
        projection.reconcile(sessionService.require(tenantId, sessionId));
        int effectiveLimit = Math.max(1, Math.min(limit, 500));
        List<SessionOutboxEntry> outputs = sessionMailboxService
                .listOutputsAfter(tenantId, sessionId, cursor, effectiveLimit);
        List<SessionOutboxCursorResponse.Event> events = outputs.stream()
                .map(this::cursorEvent)
                .toList();
        String nextCursor = outputs.isEmpty() ? cursor : outputs.getLast().getEventId();
        return ApiResponses.wrap(new SessionOutboxCursorResponse(sessionId, nextCursor, events));
    }

    /** 从持久 Outbox 回放冻结事件，不从消息历史重建事件形状。 */
    private ReplayResult replayOutbox(
            String tenantId, String sessionId, String lastEventId, long afterSequence, ChatEventSink sink) {
        String cursor = lastEventId;
        long sequence = afterSequence;
        int count = 0;
        while (true) {
            List<SessionOutboxEntry> outputs = sessionMailboxService
                    .listOutputsAfterSequence(tenantId, sessionId, sequence, 500);
            for (SessionOutboxEntry output : outputs) {
                SessionOutboxEventProjector.Event event = projection.publicEvent(output);
                sink.send(event.event(), event.data());
                cursor = event.eventId();
                sequence = event.sessionSequence();
                count++;
            }
            if (outputs.size() < 500) return new ReplayResult(count, cursor);
        }
    }

    private record ReplayResult(int visibleCount, String cursor) { }

    private Map<String, Object> replayCompleted(String sessionId, ReplayResult replay) {
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put(ChatConstants.SESSION_ID, sessionId);
        completed.put(ChatConstants.REPLAYED_COUNT, replay.visibleCount());
        if (replay.cursor() != null && !replay.cursor().isBlank()) {
            // 控制事件只返回 cursor，不伪造持久事件 ID。
            completed.put("cursor", replay.cursor());
        }
        return Map.copyOf(completed);
    }

    private SessionOutboxCursorResponse.Event cursorEvent(SessionOutboxEntry output) {
        SessionOutboxEventProjector.Event event = projection.publicEvent(output);
        return new SessionOutboxCursorResponse.Event(
                event.event(), event.eventId(), event.sessionId(), event.sessionSequence(),
                event.inputId(), event.rootInputId(), event.causedByEventId(), event.eventVersion(),
                event.occurredAt(), event.data());
    }

    /**
     * 获取当前租户 ID，缺失时抛 401。
     *
     * @return 租户 ID
     */    /**
     * 会话详情聚合（右侧会话详情面板）。
     *
     * <p>返回选中会话的关联信息：会话本体、绑定 Agent、工作流执行记录、
     * 运行时任务（挂起/完成）、按执行关联的回调注册。跨租户会话 404。
     *
     * @param sessionId 会话 ID
     * @return 会话详情聚合
     */
    @Operation(summary = "会话详情聚合",
            description = "返回会话本体/绑定 Agent/工作流执行记录/运行时任务/回调注册；跨租户会话 404")
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/chat/sessions/detail")
    public ApiResponse<SessionDetailResponse> sessionDetail(@RequestParam("sessionId") String sessionId) {
        String tenantId = TenantIds.requireTenantId();
        SessionView session = sessionService.require(tenantId, sessionId);
        SessionDetailResponse.AgentInfo agent = null;
        if (session.getAgentId() != null) {
            AgentDO agentDO = agentMapper.selectById(session.getAgentId());
            if (agentDO != null) {
                agent = new SessionDetailResponse.AgentInfo(
                        agentDO.getId(), agentDO.getAgentKey(), agentDO.getName(), agentDO.getStatus());
            }
        }
        List<SessionDetailResponse.ExecutionItem> executionItems = List.of();
        List<SessionDetailResponse.TaskItem> taskItems = List.of();
        List<SessionDetailResponse.CallbackItem> callbackItems = List.of();
        SessionDetailResponse.Session sessionItem = new SessionDetailResponse.Session(
                session.getSessionId(), session.getSummaryText(), session.getMessageCount(),
                session.getUpdatedAt(), session.getAgentId(), session.getTeamId());
        log.info("会话详情聚合 tenant={} session={} executions={} tasks={} callbacks={}",
                tenantId, sessionId, executionItems.size(), taskItems.size(), callbackItems.size());
        return ApiResponses.wrap(new SessionDetailResponse(sessionItem,
                session.getEndUserId() == null ? null : session.getEndUserId().toString(),
                session.getAgentId() == null ? null : session.getAgentId().toString(),
                sessionService.isOpenPlatformClosed(session), agent, executionItems, taskItems, callbackItems));
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CloseSessionCommand(
            @JsonProperty("session_id") @NotNull String sessionId,
            @JsonProperty("payload") @Valid Map<String, Object> payload) {}
}
