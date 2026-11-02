package com.skillforge.institution.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.time.BusinessTime;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 坐席 WebSocket 会话注册表。
 *
 * <p>会话仅按握手阶段验证出的租户身份登记。WebSocket 不承载工单权威数据，
 * 只发送服务端生成的变更通知，由客户端重新读取 REST 列表。
 */
@Component
public class AgentSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionRegistry.class);
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    /** 租户 ID -> 会话 ID -> 可并发发送的会话包装。 */
    private final ConcurrentMap<String, ConcurrentMap<String, WebSocketSession>> sessions =
            new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final AgentWsAuthenticator authenticator;

    public AgentSessionRegistry(ObjectMapper objectMapper,AgentWsAuthenticator authenticator) {
        this.objectMapper = objectMapper;this.authenticator=authenticator;
    }

    /** 将已认证连接登记到可信租户名下。 */
    public void add(String tenantId, WebSocketSession session) {
        if(!authorized(tenantId,session))return;
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES);
        sessions.computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>())
                .put(session.getId(), safeSession);
    }

    /** 移除已关闭连接，并清理空租户集合。 */
    public void remove(String tenantId, String sessionId) {
        ConcurrentMap<String, WebSocketSession> tenantSessions = sessions.get(tenantId);
        if (tenantSessions == null) {
            return;
        }
        tenantSessions.remove(sessionId);
        if (tenantSessions.isEmpty()) {
            sessions.remove(tenantId, tenantSessions);
        }
    }

    /** 向单个连接发送协议消息。 */
    public void send(String tenantId, String sessionId, Map<String, Object> payload) throws IOException {
        ConcurrentMap<String, WebSocketSession> tenantSessions = sessions.get(tenantId);
        WebSocketSession session = tenantSessions == null ? null : tenantSessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        if(!authorized(tenantId,session))return;
        session.sendMessage(new TextMessage(toJson(payload)));
    }

    /**
     * 通知指定租户工单事实已经变化。
     *
     * <p>通知不携带工单快照，避免 WebSocket 成为第二套权威状态。
     */
    public void notifyTicketChanged(String tenantId, String ticketId) {
        ConcurrentMap<String, WebSocketSession> tenantSessions = sessions.get(tenantId);
        if (tenantSessions == null) {
            return;
        }
        TextMessage message;
        try {
            message = new TextMessage(toJson(Map.of(
                    "type", "ticket.changed",
                    "ticket_id", ticketId,
                    "ts", BusinessTime.format(Instant.now()))));
        } catch (RuntimeException exception) {
            log.error("坐席通知序列化失败 tenantId={} ticketId={}", tenantId, ticketId, exception);
            return;
        }
        tenantSessions.forEach((sessionId, session) -> sendNotification(
                tenantId, sessionId, session, message));
    }

    private void sendNotification(
            String tenantId,
            String sessionId,
            WebSocketSession session,
            TextMessage message) {
        if (!session.isOpen()) {
            remove(tenantId, sessionId);
            return;
        }
        try {
            if(!authorized(tenantId,session))return;
            session.sendMessage(message);
        } catch (IOException | RuntimeException exception) {
            remove(tenantId, sessionId);
            log.warn("坐席 WebSocket 通知失败 tenantId={} sessionId={}",
                    tenantId, sessionId, exception);
        }
    }

    /** 心跳、通知与注册共用当前会话验证；失败先移除并关闭，不发送任何业务正文。 */
    public boolean authorized(String tenantId,WebSocketSession session) {
        try {
            Object attribute=session.getAttributes().get(AgentWsHandshakeInterceptor.ATTR_IDENTITY);
            if(!(attribute instanceof AgentWsIdentity identity)||!tenantId.equals(identity.tenantId()))
                throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"坐席可信身份缺失");
            authenticator.revalidate(identity);return true;
        } catch(RuntimeException revoked) {
            remove(tenantId,session.getId());
            try {session.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);}
            catch(IOException closeFailure){log.debug("坐席撤权关闭失败 sessionId={}",session.getId(),closeFailure);}
            return false;
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("坐席 WebSocket 消息序列化失败", exception);
        }
    }
}
