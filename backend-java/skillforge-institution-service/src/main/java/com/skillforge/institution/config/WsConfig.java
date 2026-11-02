package com.skillforge.institution.config;

import com.skillforge.institution.ws.AgentWsHandler;
import com.skillforge.institution.ws.AgentWsHandshakeInterceptor;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 坐席 WebSocket 配置。
 *
 * <p>由 Spring 完成握手拦截、身份属性传递与处理器注入，避免容器端点绕过
 * 应用认证组件。只允许后台配置中的可信页面来源发起连接。
 */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private final AgentWsHandler handler;
    private final AgentWsHandshakeInterceptor handshakeInterceptor;
    private final String[] allowedOrigins;

    public WsConfig(
            AgentWsHandler handler,
            AgentWsHandshakeInterceptor handshakeInterceptor,
            @Value("${skillforge.web.allowed-origins:http://localhost:3000,http://localhost:3100,http://localhost:3200}")
            String allowedOrigins) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/agent/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
