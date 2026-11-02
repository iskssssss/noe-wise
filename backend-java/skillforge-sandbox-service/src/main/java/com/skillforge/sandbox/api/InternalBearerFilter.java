package com.skillforge.sandbox.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.sandbox.config.SandboxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 仅接受部署配置中的服务间 Bearer 令牌，令牌不会进入日志或任务容器。 */
@Component
public class InternalBearerFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final byte[] expected;
    private final ObjectMapper objectMapper;

    public InternalBearerFilter(SandboxProperties properties, ObjectMapper objectMapper) {
        this.expected = properties.serviceToken().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] supplied = authorization != null && authorization.startsWith(PREFIX)
                ? authorization.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(expected, supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), new ApiError("UNAUTHORIZED", "服务间认证失败"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
