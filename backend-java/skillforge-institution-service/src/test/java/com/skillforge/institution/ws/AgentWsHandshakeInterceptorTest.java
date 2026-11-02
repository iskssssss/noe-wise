package com.skillforge.institution.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

/** WebSocket 握手只接受认证器确认的可信身份。 */
class AgentWsHandshakeInterceptorTest {

    @Test
    void storesTrustedIdentityInsteadOfQueryIdentity() {
        AgentWsAuthenticator authenticator = mock(AgentWsAuthenticator.class);
        when(authenticator.authenticate("signed-token"))
                .thenReturn(new AgentWsIdentity("7", "42", "alice",71,java.time.Instant.now().plusSeconds(300)));
        AgentWsHandshakeInterceptor interceptor = new AgentWsHandshakeInterceptor(authenticator);
        var attributes = new HashMap<String, Object>();
        var servletRequest = new MockHttpServletRequest("GET", "/api/v1/agent/ws");
        servletRequest.setQueryString("token=signed-token&tenant_id=999");
        var request = new ServletServerHttpRequest(servletRequest);
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(AgentWsHandshakeInterceptor.ATTR_TENANT_ID)).isEqualTo("7");
        assertThat(attributes.get(AgentWsHandshakeInterceptor.ATTR_IDENTITY)).isInstanceOf(AgentWsIdentity.class);
        assertThat(((AgentWsIdentity)attributes.get(AgentWsHandshakeInterceptor.ATTR_IDENTITY)).securitySessionId()).isEqualTo(71);
    }

    @Test
    void rejectsInvalidTokenBeforeUpgrade() {
        AgentWsAuthenticator authenticator = mock(AgentWsAuthenticator.class);
        when(authenticator.authenticate("bad"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "invalid"));
        AgentWsHandshakeInterceptor interceptor = new AgentWsHandshakeInterceptor(authenticator);
        var servletRequest = new MockHttpServletRequest("GET", "/api/v1/agent/ws");
        servletRequest.setQueryString("token=bad");
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                response,
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
