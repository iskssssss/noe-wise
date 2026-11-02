package com.skillforge.platform.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.cors.CorsUtils;

/**
 * Sa-Token 认证与租户上下文拦截器配置。
 *
 * <p>对 {@code /api/v1/**} 下的受保护接口执行登录校验；平台 JWT 不含租户 ID，
 * 平台服务通过独立数据库角色跨租户读取，写租户表前由业务服务绑定目标租户上下文。
 */
@Configuration
@org.springframework.context.annotation.Import(com.skillforge.core.foundation.security.HostJwtConfiguration.class)
public class SaTokenConfiguration implements WebMvcConfigurer {

    /** 租户上下文拦截器，负责写入与清理当前线程的租户 ID。 */
    private final TenantContextInterceptor tenantContextInterceptor;

    /**
     * 构造配置类。
     *
     * @param tenantContextInterceptor 租户上下文拦截器
     */
    public SaTokenConfiguration(TenantContextInterceptor tenantContextInterceptor) {
        this.tenantContextInterceptor = tenantContextInterceptor;
    }

    /**
     * 注册登录校验拦截器与租户上下文拦截器。
     *
     * <p>平台登录、刷新令牌、健康检查与 OpenAPI 文档接口为公开路径，其余
     * {@code /api/v1/**} 接口均要求携带有效 JWT。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()) {
            /** 标准预检由前置 Spring CORS 处理器核准并写响应，不能进入业务认证与拦截链。 */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (CorsUtils.isPreFlightRequest(request)) return false;
                if (com.skillforge.core.foundation.integration.ManagementAuthenticationInterceptor.verified(request)) return true;
                try { return super.preHandle(request, response, handler); }
                catch (RuntimeException failure) { throw SystemAgentToolWriteRejection.beforeDispatch(request, handler, failure); }
            }
        })
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/platform/auth/login",
                        "/api/v1/platform/auth/refresh",
                        "/api/v1/platform/auth/recovery/request",
                        "/api/v1/platform/auth/recovery/complete",
                        "/api/v1/platform/auth/invite/activate",
                        "/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**"
                );
        registry.addInterceptor(tenantContextInterceptor).addPathPatterns("/**").order(10);
    }
}
