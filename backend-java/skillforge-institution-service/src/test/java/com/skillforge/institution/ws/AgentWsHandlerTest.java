package com.skillforge.institution.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 客户端 WebSocket 消息不能发布工单或回切事件。 */
class AgentWsHandlerTest {

    @Test
    void ignoresClientBroadcastCommandsAndOnlyRepliesToPing() throws Exception {
        AgentSessionRegistry sessions = mock(AgentSessionRegistry.class);
        AgentWsHandler handler = new AgentWsHandler(sessions, new ObjectMapper());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");
        when(session.getAttributes()).thenReturn(Map.of(
                AgentWsHandshakeInterceptor.ATTR_TENANT_ID, "7",
                AgentWsHandshakeInterceptor.ATTR_MEMBER_ID, "42"));

        when(sessions.authorized("7",session)).thenReturn(true);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.message\",\"content\":\"forged\"}"));
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"ai.resume\",\"ticket_id\":\"ticket-1\"}"));

        verify(sessions, never()).notifyTicketChanged(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(sessions, never()).send(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        verify(sessions).send(org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.eq("ws-1"), org.mockito.ArgumentMatchers.any());
    }    @Test
    void revokedConnectionCannotRenewWithPing() throws Exception {
        var sessions=mock(AgentSessionRegistry.class);var handler=new AgentWsHandler(sessions,new ObjectMapper());
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("revoked");when(session.getAttributes()).thenReturn(Map.of(AgentWsHandshakeInterceptor.ATTR_TENANT_ID,"7"));
        when(sessions.authorized("7",session)).thenReturn(false);handler.handleTextMessage(session,new TextMessage("{\"type\":\"ping\"}"));
        verify(sessions,never()).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

}
