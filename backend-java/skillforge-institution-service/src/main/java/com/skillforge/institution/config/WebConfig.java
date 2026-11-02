package com.skillforge.institution.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Web MVC 全局配置。
 *
 * <p>负责注册角色校验、限流、幂等等业务拦截器，并配置跨域（CORS）策略，
 * 保证前端 Vue 应用可以直接访问后端 API。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 新接入面的文档scope只投影实际授权动作，不能将文档元数据当作认证放行依据。 */
    @Bean
    public org.springdoc.core.customizers.OpenApiCustomizer institutionIntegrationScopes() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> {
                String route = path.startsWith("/api/v1/") ? path.substring(7) : path;
                item.readOperationsMap().forEach((method, operation) -> {
                    String scope = null;
                    if (java.util.Set.of("/open-platform/groups/ensure", "/open-platform/groups/close").contains(route)) {
                        scope = "groups.write";
                    } else if (java.util.Set.of("/open-platform/groups/detail","/open-platform/groups/members","/open-platform/groups/agents").contains(route)) {
                        scope = "groups.read";
                    } else if (route.equals("/open-platform/groups/members/update")) {
                        scope = "groups.members.write";
                    } else if (route.equals("/open-platform/groups/agents/update")) {
                        scope = "groups.agents.write";
                    } else if (route.equals("/open-platform/groups/messages")) {
                        scope = "groups.messages.write";
                    } else if (route.equals("/open-platform/groups/triggers")) {
                        scope = "groups.triggers.write";
                    } else if (route.equals("/open-platform/groups/triggers/detail")) {
                        scope = "groups.triggers.read";
                    } else if (route.equals("/open-platform/groups/runs/respond")) {
                        scope = "groups.runs.respond";
                    } else if (route.equals("/open-platform/groups/runs/commands")) {
                        scope = "groups.runs.control";
                    } else if (route.equals("/open-platform/groups/runs/detail")) {
                        scope = "groups.runs.read";
                    } else if (java.util.Set.of("/open-platform/groups/history", "/open-platform/groups/events",
                            "/open-platform/groups/stream").contains(route)) {
                        scope = "groups.history.read";
                    } else if (route.equals("/open-platform/groups/memories/extraction-policy")) {
                        scope = method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET ? "groups.memory.read" : "groups.memory.write";
                    } else if (route.equals("/open-platform/groups/memories")) {
                        scope = "groups.memory.read";
                    } else if (java.util.Set.of("/open-platform/groups/memories/create", "/open-platform/groups/memories/review",
                            "/open-platform/groups/memories/correct", "/open-platform/groups/memories/forget",
                            "/open-platform/groups/memories/restore").contains(route)) {
                        scope = "groups.memory.write";
                    } else if (route.equals("/open-platform/groups/controls")) {
                        scope = "groups.controls.write";
                    } else if (java.util.Set.of("/open-platform/groups/controls/detail", "/open-platform/groups/controls/current",
                            "/open-platform/groups/controls/authorize").contains(route)) {
                        scope = "groups.controls.read";
                    } else if (route.equals("/open-platform/groups/controls/terminalize")) {
                        scope = "groups.controls.reconcile";
                    } else if (route.equals("/open-platform/groups/controls/release")) {
                        scope = "groups.controls.release";
                    } else if (java.util.Set.of("/open-platform/groups/sender-endpoints/register",
                            "/open-platform/groups/sender-endpoints/retire").contains(route)) {
                        scope = "groups.senders.manage";
                    } else if (java.util.Set.of("/open-platform/groups/sender-endpoints/ack",
                            "/open-platform/groups/deliveries/qualify").contains(route)) {
                        scope = "groups.controls.ack";
                    } else if (java.util.Set.of("/open-platform/business-scopes/ensure", "/open-platform/business-scopes/status").contains(route)) {
                        scope = "business.scopes.write";
                    } else if (java.util.Set.of("/open-platform/agent-deployments", "/open-platform/agent-deployments/detail",
                            "/open-platform/agent-deployments/definitions", "/open-platform/agent-deployments/grants",
                            "/open-platform/agent-deployments/bindings", "/open-platform/agent-deployments/authorization").contains(route)) {
                        scope = method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET ? "resources.read" : "resources.write";
                    } else if (java.util.Set.of("/open-platform/usage/events", "/open-platform/usage/summary", "/open-platform/usage/details").contains(route)) {
                        scope = "usage.read";
                    } else if (route.startsWith("/open-platform/resources")
                            || java.util.Set.of("/open-platform/deployments/update", "/open-platform/channels/binding").contains(route)) {
                        scope = method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET ? "resources.read" : "resources.write";
                    } else if (java.util.Set.of("/open-platform/control-operations", "/open-platform/control-operations/detail").contains(route)) {
                        scope = method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET ? "control.operations.read" : "control.operations.write";
                    } else if (route.equals("/open-platform/end-users/ensure")) {
                        scope = "end_users.write";
                    } else if (java.util.Set.of("/open-platform/sessions/events", "/open-platform/sessions/stream").contains(route)) {
                        scope = "sessions.events.read";
                    } else if (java.util.Set.of("/open-platform/sessions/deliverables", "/open-platform/sessions/deliverables/file").contains(route)) {
                        scope = "artifacts.read";
                    } else if (route.startsWith("/open-platform/sessions")) {
                        scope = method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET ? "sessions.read" : "sessions.write";
                    }
                    if (scope != null) operation.addExtension("x-required-scope", scope);
                    // 这些控制器在方法内拒绝缺失幂等键；文档必填不能由Spring参数的兼容绑定标记推断。
                    if (method != io.swagger.v3.oas.models.PathItem.HttpMethod.GET && java.util.Set.of(
                            "/open-platform/control-operations", "/open-platform/sessions/messages", "/open-platform/sessions/actions",
                            "/open-platform/sessions/callbacks", "/open-platform/sessions/commands").contains(route)) {
                        if (operation.getParameters() == null) operation.setParameters(new java.util.ArrayList<>());
                        var header = operation.getParameters().stream().filter(parameter -> "header".equals(parameter.getIn())
                                && "Idempotency-Key".equals(parameter.getName())).findFirst().orElse(null);
                        if (header == null) {
                            header = new io.swagger.v3.oas.models.parameters.HeaderParameter().name("Idempotency-Key")
                                    .schema(new io.swagger.v3.oas.models.media.StringSchema());
                            operation.addParametersItem(header);
                        }
                        header.setRequired(true);
                    }
                    // 必需multipart字段缺失时请求无法进入方法，正文自身也必须标为必需。
                    if (method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST
                            && java.util.Set.of("/open-platform/sessions/media", "/open-platform/sessions/attachments").contains(route)
                            && operation.getRequestBody() != null) operation.getRequestBody().setRequired(true);
                });
            });
        };
    }

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
    private final String[] allowedOrigins;

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
            IdempotencyInterceptor idempotencyInterceptor,
            @Value("${skillforge.web.allowed-origins:http://localhost:3000,http://localhost:3100,http://localhost:3200}") String allowedOrigins,
            @Value("${skillforge.code-execution.preview-origin:}") String previewOrigin) {
        this.permissionCheckInterceptor = permissionCheckInterceptor;
        this.securityInterceptor = securityInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.idempotencyInterceptor = idempotencyInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).toArray(String[]::new);
        for (String origin : this.allowedOrigins) {
            if (origin.isBlank() || origin.contains("*") || origin.equals("null") || origin.equals(previewOrigin)) {
                throw new IllegalArgumentException("平台凭证CORS只能允许明确的平台页面来源");
            }
        }
    }

    /**
     * 注册业务拦截器，仅作用于 API 路径。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                try { return securityInterceptor.preHandle(request, response, handler); }
                catch (RuntimeException failure) { throw PublishPreDispatchRejection.map(request, handler, failure); }
            }
        }).addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/chat/artifact-content/detail", "/api/v1/chat/deliverable-content/detail",
                        "/api/v1/agent/ws", "/api/v1/callbacks/*/*", "/api/v1/runtime/callbacks/deliveries").order(15);
        registry.addInterceptor(permissionCheckInterceptor).addPathPatterns("/api/v1/**").order(20);
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                try { return rateLimitInterceptor.preHandle(request, response, handler); }
                catch (RuntimeException failure) { throw PublishPreDispatchRejection.rateLimit(request, handler, failure); }
            }
        }).addPathPatterns("/api/v1/**").order(30);
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/v1/**").order(40);
    }

    /** CORS 在业务拦截器之前完成预检；真实请求继续走完整认证与限流。 */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        var policy = new CorsConfiguration();
        policy.setAllowedOrigins(List.of(allowedOrigins));
        policy.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        policy.setAllowedHeaders(List.of("*"));
        policy.setExposedHeaders(List.of("X-Trace-Id", "ETag", "Retry-After", "Content-Disposition", "X-Resource-Revision"));
        policy.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", policy);
        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** 未声明 Accept 的事件读取默认 JSON；SSE 客户端显式协商。 */
    @Override
    public void configureContentNegotiation(
            org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    }
}
