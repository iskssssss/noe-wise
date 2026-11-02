package com.skillforge.core.foundation.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 宿主 WebConfig 注册此拦截器后，按当前数据库授权执行入口权限声明。 */
public class FoundationPermissionInterceptor implements HandlerInterceptor {
    private final FoundationAccess access;
    private final java.util.function.Predicate<HttpServletRequest> externallyAuthorized;
    /** 注入当前授权服务。 */
    public FoundationPermissionInterceptor(FoundationAccess access) { this(access, request -> false); }
    /** 由宿主显式传入已经由独立认证链验证的外部请求策略，不能凭未登录状态跳过。 */
    public FoundationPermissionInterceptor(FoundationAccess access, java.util.function.Predicate<HttpServletRequest> externallyAuthorized) {
        this.access = access;
        this.externallyAuthorized = externallyAuthorized;
    }
    /** 未声明时交由所属入口负责；声明时不可由 JWT 的旧角色绕过。 */
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            if (externallyAuthorized.test(request)) return true;
            RequirePermission annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequirePermission.class);
            if (annotation == null) annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequirePermission.class);
            if (annotation != null) {
                if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
                    Object value = request.getAttribute(com.skillforge.core.foundation.security.FoundationSecurityInterceptor.SUBJECT_ATTRIBUTE);
                    if (!(value instanceof FoundationAccess.Subject subject))
                        throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "可信异步身份缺失");
                    access.require(subject, annotation.value());
                } else access.require(annotation.value());
            }
        }
        return true;
    }
}
