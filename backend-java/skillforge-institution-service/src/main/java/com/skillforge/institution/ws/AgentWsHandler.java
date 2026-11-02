package com.skillforge.institution.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.time.BusinessTime;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 坐席 WebSocket 处理器。
 *
 * <p>客户端只可发送心跳。工单列表和状态迁移均通过受认证 REST 接口完成，
 * WebSocket 仅承载服务端变更通知，客户端消息不能触发跨会话广播。
 */
@Component
public class AgentWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWsHandler.class);

    private final AgentSessionRegistry sessions;
    private final ObjectMapper objectMapper;

    public AgentWsHandler(AgentSessionRegistry sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String tenantId = attribute(session, AgentWsHandshakeInterceptor.ATTR_TENANT_ID);
        String memberId = attribute(session, AgentWsHandshakeInterceptor.ATTR_MEMBER_ID);
        if (tenantId == null || memberId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.add(tenantId, session);
        sessions.send(tenantId, session.getId(), Map.of(
                "type", "connected",
                "ts", BusinessTime.format(Instant.now())));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String tenantId = attribute(session, AgentWsHandshakeInterceptor.ATTR_TENANT_ID);
        if (tenantId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        if(!sessions.authorized(tenantId,session))return;
        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException | RuntimeException exception) {
            log.debug("忽略无效坐席 WebSocket 消息 sessionId={}", session.getId());
            return;
        }
        if ("ping".equals(payload.path("type").asText())) {
            sessions.send(tenantId, session.getId(), Map.of(
                    "type", "pong",
                    "ts", BusinessTime.format(Instant.now())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String tenantId = attribute(session, AgentWsHandshakeInterceptor.ATTR_TENANT_ID);
        if (tenantId != null) {
            sessions.remove(tenantId, session.getId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("坐席 WebSocket 传输中断 sessionId={}", session.getId(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    private static String attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value == null ? null : value.toString();
    }
}
