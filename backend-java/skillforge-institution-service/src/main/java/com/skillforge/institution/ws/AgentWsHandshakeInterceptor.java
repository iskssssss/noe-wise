package com.skillforge.institution.ws;

import com.skillforge.core.shared.exception.ApiException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** 在 WebSocket 升级前完成机构成员认证并写入不可由客户端覆盖的会话属性。 */
@Component
public class AgentWsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AgentWsHandshakeInterceptor.class);

    public static final String ATTR_TENANT_ID = "agent.tenant_id";
    public static final String ATTR_MEMBER_ID = "agent.member_id";
    public static final String ATTR_USERNAME = "agent.username";
    public static final String ATTR_IDENTITY = "agent.identity";

    private final AgentWsAuthenticator authenticator;

    public AgentWsHandshakeInterceptor(AgentWsAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
        try {
            AgentWsIdentity identity = authenticator.authenticate(token);
            attributes.put(ATTR_TENANT_ID, identity.tenantId());
            attributes.put(ATTR_MEMBER_ID, identity.memberId());
            attributes.put(ATTR_USERNAME, identity.username());
            attributes.put(ATTR_IDENTITY, identity);
            return true;
        } catch (ApiException exception) {
            response.setStatusCode(exception.getStatus());
            return false;
        } catch (RuntimeException exception) {
            log.error("坐席 WebSocket 握手处理失败 path={}", request.getURI().getPath(), exception);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // 无需保留请求线程资源。
    }
}
