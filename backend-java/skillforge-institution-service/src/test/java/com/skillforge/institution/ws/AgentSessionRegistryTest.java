package com.skillforge.institution.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 服务端通知按认证租户隔离，且只携带刷新信号。 */
class AgentSessionRegistryTest {

    @Test
    void notifiesOnlyTargetTenantWithoutPublishingTicketSnapshot() throws Exception {
        AgentSessionRegistry registry = new AgentSessionRegistry(new ObjectMapper(),mock(AgentWsAuthenticator.class));
        WebSocketSession tenantA = session("a","7");
        WebSocketSession tenantB = session("b","8");
        registry.add("7", tenantA);
        registry.add("8", tenantB);

        registry.notifyTicketChanged("7", "ticket-1");

        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(tenantA).sendMessage(message.capture());
        verify(tenantB, never()).sendMessage(org.mockito.ArgumentMatchers.any());
        assertThat(message.getValue().getPayload()).contains("ticket.changed", "ticket-1");
        assertThat(message.getValue().getPayload()).doesNotContain("items", "summary", "session_id");
    }

    @Test
    void currentWithdrawalClosesConnectionBeforeAnyNotification() throws Exception {
        var authenticator=mock(AgentWsAuthenticator.class);var registry=new AgentSessionRegistry(new ObjectMapper(),authenticator);
        var session=session("revoked","7");registry.add("7",session);
        org.mockito.Mockito.doThrow(new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"revoked")).when(authenticator).revalidate(org.mockito.ArgumentMatchers.any());
        registry.notifyTicketChanged("7","secret-ticket");
        verify(session,never()).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(session).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
    }

    private static WebSocketSession session(String id,String tenant) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(java.util.Map.of(AgentWsHandshakeInterceptor.ATTR_IDENTITY,new AgentWsIdentity(tenant,"42","alice",71,java.time.Instant.now().plusSeconds(300))));
        return session;
    }
}
