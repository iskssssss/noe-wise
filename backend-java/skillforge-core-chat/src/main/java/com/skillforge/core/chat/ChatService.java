package com.skillforge.core.chat;

import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.request.CreateSessionRequest;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.dto.response.SessionItemResponse;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.message.ChatMessageRecord;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Chat 应用薄门面。
 *
 * <p>稳定承接 Controller 的现有调用契约；入口编排与会话读写分别委派给独立组件，
 * 本类不持有 Mapper 或运行时基础设施。</p>
 */
@Service
public class ChatService {

    private final ChatConversationDirector conversationDirector;
    private final ChatSessionService sessionService;

    /** 通过构造器注入应用职责组件。 */
    public ChatService(
            ChatConversationDirector conversationDirector,
            ChatSessionService sessionService) {
        // 门面只保存应用边界，底层 Mapper 和运行时组件由各自实现持有。
        this.conversationDirector = conversationDirector;
        this.sessionService = sessionService;
    }

    /**
     * 执行一次普通对话或 HITL 点选输入。
     *
     * @param request 对话请求
     * @param sink 可选实时事件出口
     * @return 对话状态和消息结果
     */
    public ChatResult runChat(ChatRequest request, ChatEventSink sink) {
        // 普通消息统一进入 Director，保持 Runtime 受理、计量和输出顺序集中管理。
        return conversationDirector.runChat(request, sink);
    }

    /**
     * 向会话写入主动消息并创建持久 Outbox 事件。
     *
     * @return 新增或 merge 后的消息记录
     */
    public ChatMessageRecord sendToSession(
            String tenantId,
            String sessionId,
            String content,
            Map<String, Object> card,
            Map<String, Object> render,
            String mergeMessageId) {
        // 主动消息由会话边界处理持久化与 Outbox 事件。
        return sessionService.sendToSession(
                tenantId, sessionId, content, card, render, mergeMessageId);
    }

    /** 创建显式绑定 Agent 的空会话，并返回持久 session_id。 */
    public Map<String, Object> createSession(CreateSessionRequest request, String tenantId) {
        // 会话目标解析、身份校验和建会话副作用均收敛在会话服务。
        return sessionService.createSession(request, tenantId);
    }

    public Map<String, Object> createSessionWithReceipt(CreateSessionRequest request, String tenantId, String requestId) {
        return sessionService.createSessionWithReceipt(request, tenantId, requestId);
    }

    /** 查询会话消息、挂起交互与执行活动的组合快照。 */
    public ChatHistoryResponse history(String tenantId, String sessionId) {
        // 消息历史查询不在门面内拼装运行时状态或消息 DTO。
        return sessionService.history(tenantId, sessionId);
    }

    /** 分页查询授权会话的消息，活动与交互状态继续从完整投影恢复。 */
    public ChatHistoryResponse history(String tenantId,String sessionId,int limit,String beforeMessageId) {
        return sessionService.history(tenantId,sessionId,limit,beforeMessageId);
    }

    /** 按当前租户及可选终端用户、Agent 过滤会话列表。 */
    public List<SessionItemResponse> sessions(String tenantId, Long agentId, Long endUserId) {
        return sessionService.sessions(tenantId, agentId, endUserId);
    }

    public List<SessionItemResponse> sessions(String tenantId, Long agentId, Long endUserId, String teamId) {
        return sessionService.sessions(tenantId, agentId, endUserId, teamId);
    }

}
