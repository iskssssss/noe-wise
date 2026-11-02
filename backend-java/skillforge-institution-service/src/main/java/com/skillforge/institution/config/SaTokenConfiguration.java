package com.skillforge.institution.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Stream;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 认证与租户上下文拦截器配置。
 *
 * <p>对 {@code /api/v1/**} 下的受保护接口执行登录校验，并在所有请求处理前将
 * JWT 中的租户 ID 写入 {@link TenantContext}，为 MyBatis RLS 拦截器提供上下文。
 *
 * <p>外部调用开放路径（{@link ExternalAuthPaths#OPEN_PATHS}）从登录校验中放行，
 * 改由 {@link ExternalAuthInterceptor}（order(-10)）做三路认证分流：B 端 JWT /
 * 渠道令牌 / API Key+HMAC。
 */
@Configuration
@org.springframework.context.annotation.Import(com.skillforge.core.foundation.security.HostJwtConfiguration.class)
public class SaTokenConfiguration implements WebMvcConfigurer {

    /** 租户上下文拦截器，负责写入当前请求已认证的租户 ID。 */
    private final TenantContextInterceptor tenantContextInterceptor;

    /** 外部认证拦截器，开放路径专用（三路分流 + channel_id 一致性校验）。 */
    private final ExternalAuthInterceptor externalAuthInterceptor;

    /**
     * 构造配置类。
     *
     * @param tenantContextInterceptor 租户上下文拦截器
     * @param externalAuthInterceptor  外部认证拦截器
     */
    public SaTokenConfiguration(
            TenantContextInterceptor tenantContextInterceptor,
            ExternalAuthInterceptor externalAuthInterceptor) {
        this.tenantContextInterceptor = tenantContextInterceptor;
        this.externalAuthInterceptor = externalAuthInterceptor;
    }

    /**
     * 注册登录校验拦截器、外部认证拦截器与租户上下文拦截器。
     *
     * <p>登录、刷新令牌、健康检查、OpenAPI 文档接口与外部调用开放路径为公开路径；
     * 开放路径由外部认证拦截器（先于其他拦截器执行）按渠道/API Key 鉴权，其余
     * {@code /api/v1/**} 接口仍要求携带有效 JWT。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            /**
             * 登录校验。异步 dispatch（SseEmitter 等）跳过：Sa-Token 上下文绑定在
             * 请求线程，异步线程无上下文，重复 checkLogin 会抛「上下文尚未初始化」，
             * 且主请求线程已完成鉴权（AsyncDispatch 不再重复校验）。
             */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;
                }
                if (com.skillforge.core.foundation.integration.ManagementAuthenticationInterceptor.verified(request)) return true;
                try { StpUtil.checkLogin(); }
                catch (RuntimeException failure) { throw PublishPreDispatchRejection.map(request, handler, failure); }
                return true;
            }
        })
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(Stream.concat(
                        Stream.of(
                                "/api/v1/chat/artifact-content/detail",
                                "/api/v1/chat/deliverable-content/detail",
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/refresh",
                        "/api/v1/auth/recovery/request",
                        "/api/v1/auth/recovery/complete",
                        "/api/v1/auth/invite/activate",
                                "/api/v1/agent/ws",
                                "/api/v1/callbacks/*/*",
                                // Runtime 回调由入口按原注册与完整正文验证 HMAC，不要求用户 JWT。
                                "/api/v1/runtime/callbacks/deliveries",
                                "/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**"
                        ),
                        ExternalAuthPaths.OPEN_PATHS.stream()).toArray(String[]::new));
        // 外部认证先行：先于角色/限流/幂等拦截器（默认 order 0）
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                try { return externalAuthInterceptor.preHandle(request, response, handler); }
                catch (RuntimeException failure) { throw PublishPreDispatchRejection.map(request, handler, failure); }
            }
        })
                .addPathPatterns(ExternalAuthPaths.OPEN_PATHS.toArray(String[]::new))
                .order(-10);
        registry.addInterceptor(tenantContextInterceptor).addPathPatterns("/**").order(10);
    }
}
