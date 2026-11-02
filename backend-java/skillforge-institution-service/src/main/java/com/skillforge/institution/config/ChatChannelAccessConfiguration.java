package com.skillforge.institution.config;

import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.ChatChannelAccess;
import com.skillforge.institution.chat.ChatEndUserTokens;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 在控制器（包括 SSE 异步调度）之前冻结渠道目标授权。 */
@Configuration
public class ChatChannelAccessConfiguration implements WebMvcConfigurer {
    private final ChatChannelAccess access;
    private final ObjectMapper mapper;
    private final ChatEndUserTokens identities;
    public ChatChannelAccessConfiguration(ChatChannelAccess access, ObjectMapper mapper, ChatEndUserTokens identities) {
        this.access = access;
        this.mapper = mapper;
        this.identities = identities;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) return true;
                Object source = request.getAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE);
                if (!ExternalAuthPaths.SOURCE_CHANNEL.equals(source) && !ExternalAuthPaths.SOURCE_OPENAPI.equals(source)) return true;
                String path = request.getRequestURI();
                if (path.equals("/api/v1/chat/agents") || path.equals("/api/v1/chat/client-profile")
                        || path.equals("/api/v1/chat/end-users")) return true;
                if (ExternalAuthPaths.SOURCE_CHANNEL.equals(source)) identities.authenticate(request);
                String clientRef = request.getParameter("client_ref");
                if (clientRef != null) ChatEndUserTokens.requireClientRef(clientRef);
                if (request.getMethod().equals("GET")) {
                    if (path.equals("/api/v1/chat/sessions")) {
                        ChatEndUserTokens.requireEndUser(request.getParameter("end_user_id"));
                        access.requireTarget(request.getParameter("agentId"), request.getParameter("team_id"), request.getParameter("end_user_id"));
                    }
                    else {
                        String session = request.getParameter("session_id");
                        if (session == null) session = request.getParameter("sessionId");
                        if (session != null) access.requireSession(session);
                    }
                } else if (request instanceof CachedBodyHttpServletRequest cached) {
                    JsonNode body = mapper.readTree(cached.getCachedBody());
                    if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "请求体不能为空");
                    if (body.hasNonNull("client_ref")) ChatEndUserTokens.requireClientRef(body.path("client_ref").asText());
                    String session = body.path("session_id").asText("");
                    String agent = body.path("agent_id").asText("");
                    String team = body.path("team_id").asText("");
                    if (!session.isBlank() && !session.equals("new") && !session.equals("pending")) {
                        var existing = access.requireSession(session);
                        boolean chatInput = path.equals("/api/v1/chat") || path.equals("/api/v1/chat/sync") || path.equals("/api/v1/chat/sessions");
                        // 控制接口中的 agent_id 是成员选择，不能当作会话所有者覆盖。
                        if (chatInput && (!agent.isBlank() && !java.util.Objects.equals(access.requireAgent(agent), existing.getAgentId())
                                || !team.isBlank() && !team.equals(existing.getTeamId())))
                            throw new ApiException(HttpStatus.CONFLICT, "会话目标不可变更");
                        String user = body.path("context").path("end_user_id").asText(body.path("end_user_id").asText(""));
                        if (!user.isBlank() && !user.equals(existing.getEndUserId().toString()))
                            throw new ApiException(HttpStatus.CONFLICT, "会话用户不可变更");
                    } else if (path.equals("/api/v1/chat") || path.equals("/api/v1/chat/sync") || path.equals("/api/v1/chat/sessions")) {
                        ChatEndUserTokens.requireEndUser(body.path("end_user_id").asText(body.path("context").path("end_user_id").asText("")));
                        access.requireTarget(agent, team, body.path("end_user_id").asText(body.path("context").path("end_user_id").asText("")));
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/v1/chat", "/api/v1/chat/**", "/api/v1/messages").order(1);
    }
}
