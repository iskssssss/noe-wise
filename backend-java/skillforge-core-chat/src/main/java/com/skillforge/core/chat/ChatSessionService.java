package com.skillforge.core.chat;

import com.skillforge.core.agent.AgentQueryService;
import com.skillforge.core.chat.dto.request.CreateSessionRequest;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.response.SessionItemResponse;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.constant.ChatConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.identity.EndUserIdentity;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 会话命令、查询与主动消息边界。 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final AgentQueryService agentQuery;
    private final MemoryService memoryService;
    private final SessionService sessionService;
    private final SessionMailboxService sessionMailboxService;
    private final ChatRuntimeHistory runtimeHistory;
    private final ChatTeamGateway teamGateway;
    private final PublicOutputGuard outputGuard;

    /**
     * 注入会话、记忆、运行时任务和 Outbox 协作者。
     *
     * @param agentQuery Agent key 到数字 ID 的解析器
     * @param memoryService 消息历史与主动消息存储
     * @param sessionService 会话归属服务
     * @param sessionMailboxService 主动输出邮箱
     * @param runtimeHistory Runtime 已提交会话事实的宿主投影
     * @param teamGateway 工作室可用性校验边界
     * @param outputGuard 持久助手消息的当前公开检查
     */
    public ChatSessionService(
            AgentQueryService agentQuery,
            MemoryService memoryService,
            SessionService sessionService,
            SessionMailboxService sessionMailboxService,
            ChatRuntimeHistory runtimeHistory,
            ChatTeamGateway teamGateway,
            PublicOutputGuard outputGuard) {
        // 会话服务集中持有会话、消息、活动和主动输出依赖。
        this.agentQuery = agentQuery;
        this.memoryService = memoryService;
        this.sessionService = sessionService;
        this.sessionMailboxService = sessionMailboxService;
        this.runtimeHistory = runtimeHistory;
        this.teamGateway = teamGateway;
        this.outputGuard = java.util.Objects.requireNonNull(outputGuard);
    }

    /**
     * 写入主动助手消息及可重放的 Outbox 事件。
     *
     * @return 新增或覆盖后的消息记录
     */
    public ChatMessageRecord sendToSession(
            String tenantId,
            String sessionId,
            String content,
            Map<String, Object> card,
            Map<String, Object> render,
            String mergeMessageId) {
        SessionView session = sessionService.require(tenantId, sessionId);
        boolean merge = mergeMessageId != null && !mergeMessageId.isBlank();
        Map<String,Object> publicMetadata=new LinkedHashMap<>();
        if(card!=null&&!card.isEmpty())publicMetadata.put("card",card);
        if(render!=null)publicMetadata.put("render",render);
        // merge 使用 upsert 原位覆盖；普通消息使用 append 生成新记录。
        ChatMessageRecord record = merge
                ? memoryService.upsert(tenantId, sessionId, session.getEndUserId(),
                        ChatConstants.ROLE_ASSISTANT, content, publicMetadata, mergeMessageId)
                : memoryService.append(tenantId, sessionId, session.getEndUserId(),
                        ChatConstants.ROLE_ASSISTANT, content, publicMetadata, null);
        // 事件载荷引用刚写入的 message_id，保证历史重放与在线卡片更新使用同一身份。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ChatConstants.SESSION_ID, sessionId);
        data.put(ChatConstants.CONTENT, content);
        data.put(ChatConstants.MESSAGE_ID, record.messageId());
        data.put("message_version", record.messageVersion());
        data.put("references", record.references());
        if (card != null && !card.isEmpty()) {
            // 卡片为空时发送文本事件，避免客户端收到没有内容的交互卡。
            data.put(ChatConstants.CARD, card);
        }
        if (render != null) {
            // render 是客户端自定义渲染数据，保持原对象结构但不写入日志。
            data.put("render", render);
        }
        String event = merge ? ChatConstants.EVENT_MESSAGE_CARD_UPDATE
                : card != null && !card.isEmpty()
                        ? ChatConstants.EVENT_MESSAGE_CARD : ChatConstants.EVENT_MESSAGE_DELTA;
        if (merge) {
            // merge 事件必须携带更新标识，客户端按 message_id 原位刷新。
            data.put("update_flag", true);
        }
        String eventId = merge
                ? "proactive-update:" + record.messageId() + ":" + SnowflakeIds.nextId()
                : "proactive-message:" + record.messageId();
        // Web 订阅直接读取已提交 Outbox；Webhook 使用独立 delivery 账本投递与重试。
        sessionMailboxService.appendOutput(tenantId, sessionId, eventId, event,
                Map.of("event", event, "data", data));
        log.info("会话主动消息已写入 Outbox tenant={} sessionTag={} messageTag={} event={} merge={}",
                tenantId, ChatLogIds.tag(sessionId), ChatLogIds.tag(record.messageId()), event, merge);
        return record;
    }

    /** 根据显式 Agent 或 Team 和终端用户身份创建空会话。 */
    public Map<String, Object> createSession(CreateSessionRequest request, String tenantId) {
        return createSession(request, tenantId, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> createSessionWithReceipt(CreateSessionRequest request, String tenantId, String requestId) {
        return createSession(request, tenantId, requestId);
    }

    private Map<String, Object> createSession(CreateSessionRequest request, String tenantId, String requestId) {
        boolean hasAgent = request.agentId() != null && !request.agentId().isBlank();
        boolean hasTeam = request.teamId() != null && !request.teamId().isBlank();
        if (hasAgent == hasTeam) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "会话必须且只能指定一个 agent_id 或 team_id");
        }
        Long endUserId = requireEndUserId(request.endUserId(), "创建会话入口");
        if (hasTeam) {
            teamGateway.requireAvailable(tenantId, request.teamId());
            SessionView session = requestId == null ? sessionService.getOrCreateTeam(
                    tenantId, endUserId, null, request.teamId(), request.scopeId(), request.scopeType())
                    : sessionService.createWithReceipt(tenantId, endUserId, null, request.teamId(), request.scopeId(), request.scopeType(), requestId);
            log.info("团队会话创建完成 tenant={} sessionTag={} teamTag={}",
                    tenantId, ChatLogIds.tag(session.getSessionId()), ChatLogIds.tag(request.teamId()));
            return Map.of(ChatConstants.SESSION_ID, session.getSessionId());
        }
        Long agentId = resolveAgentId(tenantId, request.agentId());
        if (agentId == null) {
            // key 解析失败在创建 Session 前拒绝，避免产生无法归属的空会话。
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在: " + request.agentId());
        }
        SessionView session = requestId == null ? sessionService.getOrCreate(
                tenantId, endUserId, null, agentId, request.scopeId(), request.scopeType())
                : sessionService.createWithReceipt(tenantId, endUserId, agentId, null, request.scopeId(), request.scopeType(), requestId);
        log.info("会话创建完成 tenant={} sessionTag={} agentTag={}",
                tenantId, ChatLogIds.tag(session.getSessionId()), ChatLogIds.tag(agentId));
        return Map.of(ChatConstants.SESSION_ID, session.getSessionId());
    }

    /** 默认恢复最近 30 条消息；过程元数据独立保留，避免分页丢失工具活动与待办。 */
    public ChatHistoryResponse history(String tenantId, String sessionId) {
        return history(tenantId,sessionId,30,null);
    }

    /** 游标是本会话已存在消息的 ID，严格排除游标本身；新消息不会使更早分页错位。 */
    public ChatHistoryResponse history(String tenantId,String sessionId,int limit,String beforeMessageId) {
        if(limit<1||limit>100)throw new ApiException(HttpStatus.BAD_REQUEST,"limit 须为 1 至 100");
        SessionView session=sessionService.require(tenantId,sessionId);
        // 必须先捕获已提交起点再读历史；读取期间新增输出可继续补齐，稳定消息身份与版本防止重复展示。
        String eventCursor=sessionMailboxService.committedCursor(tenantId,sessionId);
        ChatHistoryResponse runtime=runtimeHistory.read(session);
        Map<String,ChatMessageRecord> messages=new LinkedHashMap<>();
        // 两来源同 ID 以 Runtime 已提交事实为准，先归并再分页，不能单独截断 Memory 后遗漏消息。
        java.util.Set<String> visibleTeamAnswers=session.getTeamId()==null?java.util.Set.of():runtime.messages().stream()
                .map(ChatMessageRecord::messageId).collect(java.util.stream.Collectors.toSet());
        var publicScope=outputGuard.capture();
        memoryService.listAll(tenantId,sessionId).stream()
                .filter(message->session.getTeamId()==null||message.metadata()==null
                        ||!message.metadata().containsKey("team_answer_ref")
                        ||visibleTeamAnswers.contains(message.messageId()))
                .map(publicScope::protect)
                .forEach(message->messages.put(message.messageId(),message));
        runtime.messages().forEach(message->messages.put(message.messageId(),message));
        var order=java.util.Comparator.comparing(ChatMessageRecord::createdAt)
                .thenComparingLong(message -> message.metadata()!=null && message.metadata().get("source_sequence") instanceof Number sequence
                        ? sequence.longValue() : 0L).thenComparing(ChatMessageRecord::messageId);
        ChatMessageRecord cursor=null;
        if(beforeMessageId!=null&&!beforeMessageId.isBlank()) {
            cursor=messages.get(beforeMessageId);
            if(cursor==null)throw new ApiException(HttpStatus.BAD_REQUEST,"历史消息游标无效，请重新加载会话");
        }
        final ChatMessageRecord boundary=cursor;
        var ordered=messages.values().stream().filter(message->boundary==null||order.compare(message,boundary)<0)
                .sorted(order).toList();
        boolean hasMore=ordered.size()>limit;
        var page=List.copyOf(ordered.subList(Math.max(0,ordered.size()-limit),ordered.size()));
        return new ChatHistoryResponse(sessionId,page,runtime.pendingInteractions(),runtime.activities(),runtime.processes(),
                runtime.collaboration(),hasMore,hasMore?page.getFirst().messageId():null,eventCursor,runtime.historyRetention()).withWorkPlans(runtimeHistory.workPlans(session,page.stream()
                        .flatMap(message -> java.util.stream.Stream.of(message.messageId(),message.metadata()==null?null:message.metadata().get("root_input_id"),message.metadata()==null?null:message.metadata().get("input_id")))
                        .filter(java.util.Objects::nonNull).map(Object::toString).collect(java.util.stream.Collectors.toSet())));
    }
    /** 按租户及可选终端用户、Agent 查询会话列表。 */
    public List<SessionItemResponse> sessions(String tenantId, Long agentId, Long endUserId) {
        return sessions(tenantId, agentId, endUserId, null);
    }

    public List<SessionItemResponse> sessions(String tenantId, Long agentId, Long endUserId, String teamId) {
        Long filteredEndUserId = endUserId == null ? null : requireEndUserId(endUserId, "会话列表筛选");
        List<SessionItemResponse> result = sessionService.list(
                        tenantId, filteredEndUserId, agentId, teamId).stream()
                .map(session -> new SessionItemResponse(
                        session.sessionId(), session.summaryText(), session.messageCount(),
                        session.updatedAt(), session.agentId(), session.teamId(), session.title()))
                .toList();
        return result;
    }

    /** 将数字 Agent ID 或租户内 Agent key 解析为持久化数字 ID。 */
    Long resolveAgentId(String tenantId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            // 数字标识无需查询；非数字值才在当前租户内按 agent_key 查询。
            return Long.parseLong(agentId);
        } catch (NumberFormatException ignored) {
            var agent = agentQuery.findPublishedByKey(Long.parseLong(tenantId), agentId);
            return agent == null ? null : agent.id();
        }
    }

    /** 校验会话命令携带的正整数终端用户身份。 */
    private Long requireEndUserId(Long endUserId, String source) {
        try {
            return EndUserIdentity.require(endUserId, source);
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }
}
