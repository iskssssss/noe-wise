package com.skillforge.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 全局配置。
 *
 * <p>负责注册角色校验、限流、幂等等业务拦截器，并配置跨域（CORS）策略，
 * 保证前端 Vue 应用可以直接访问后端 API。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** OpenAPI 使用宿主 HTTP 的 Jackson 命名规则，避免文档与线上 snake_case 漂移。 */
    @org.springframework.context.annotation.Bean
    public io.swagger.v3.core.jackson.ModelResolver httpSchemaResolver(
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new io.swagger.v3.core.jackson.ModelResolver(mapper.copy()).openapi31(true);
    }


    /** Swagger 不保留泛型引用的 nullable；在 HTTP 文档投影中补齐，不改变 DTO 序列化。 */
    @org.springframework.context.annotation.Bean
    public org.springdoc.core.customizers.OpenApiCustomizer envelopeSchemaNullability() {
        return api -> api.getComponents().getSchemas().forEach((name, schema) -> {
            if (!name.startsWith("ApiResponse") || schema.getProperties() == null) return;
            if (name.equals("ApiResponse")) {
                schema.getProperties().put("data", new io.swagger.v3.oas.models.media.Schema<>());
            } else if (name.equals("ApiResponseVoid")) {
                schema.getProperties().put("data", new io.swagger.v3.oas.models.media.JsonSchema().types(java.util.Set.of("null")));
            } else {
                var data = (io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("data");
                if (data != null) schema.getProperties().put("data", new io.swagger.v3.oas.models.media.ComposedSchema()
                        .addOneOfItem(data).addOneOfItem(new io.swagger.v3.oas.models.media.JsonSchema().types(java.util.Set.of("null"))));
            }
        });
    }

    /** 角色权限校验拦截器。 */
    private final PermissionCheckInterceptor permissionCheckInterceptor;
    private final com.skillforge.core.foundation.security.FoundationSecurityInterceptor securityInterceptor;

    /** 基于 Redis 的请求限流拦截器。 */
    private final RateLimitInterceptor rateLimitInterceptor;

    /** 基于 Redis 的幂等拦截器。 */
    private final IdempotencyInterceptor idempotencyInterceptor;

    /**
     * 构造配置类。
     *
     * @param permissionCheckInterceptor      角色权限校验拦截器
     * @param rateLimitInterceptor      请求限流拦截器
     * @param idempotencyInterceptor    幂等拦截器
     */
    public WebConfig(
            PermissionCheckInterceptor permissionCheckInterceptor,
            com.skillforge.core.foundation.security.FoundationSecurityInterceptor securityInterceptor,
            RateLimitInterceptor rateLimitInterceptor,
            IdempotencyInterceptor idempotencyInterceptor) {
        this.permissionCheckInterceptor = permissionCheckInterceptor;
        this.securityInterceptor = securityInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.idempotencyInterceptor = idempotencyInterceptor;
    }

    /**
     * 注册业务拦截器，仅作用于 API 路径。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(preDispatch(securityInterceptor)).addPathPatterns("/api/v1/**").order(15);
        registry.addInterceptor(preDispatch(permissionCheckInterceptor)).addPathPatterns("/api/v1/**").order(20);
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/v1/**").order(30);
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/v1/**").order(40);
    }

    private org.springframework.web.servlet.HandlerInterceptor preDispatch(org.springframework.web.servlet.HandlerInterceptor delegate) {
        return new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                try { return delegate.preHandle(request, response, handler); }
                catch (RuntimeException failure) { throw SystemAgentToolWriteRejection.beforeDispatch(request, handler, failure); }
            }
            @Override public void postHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler,
                    org.springframework.web.servlet.ModelAndView view) throws Exception {
                delegate.postHandle(request, response, handler, view);
            }
            @Override public void afterCompletion(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler, Exception failure) throws Exception {
                delegate.afterCompletion(request, response, handler, failure);
            }
        };
    }

    /**
     * 配置全局跨域策略。
     *
     * <p>开发环境允许任意 Origin，并开放全部方法与请求头；生产环境应由网关或
     * 反向代理收紧允许来源。
     *
     * @param registry CORS 注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "ETag", "Retry-After", "Content-Disposition", "X-Resource-Revision")
                .allowCredentials(true);
    }
}
