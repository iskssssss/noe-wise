package com.skillforge.core.foundation.integration;

import com.skillforge.common.context.ManagementContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/** 独立平台机器认证入口；正文缓存与签名使用同一原始字节，不创建 Tenant 执行身份。 */
public final class ManagementAuthenticationInterceptor implements HandlerInterceptor {
    public static final String SOURCE = "MANAGEMENT";
    private final PlatformManagementService management;

    public ManagementAuthenticationInterceptor(PlatformManagementService management) { this.management = management; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getHeader("X-Management-Key");
        if (key == null) return true;
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || ManagementActions.action(request.getMethod(), request.getRequestURI()) == null
                || !(request instanceof CachedBodyHttpServletRequest cached)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "管理凭证不能进入此认证域或请求正文未绑定", false);
        }
        String target = request.getRequestURI();
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) target += "?" + request.getQueryString();
        var identity = management.authenticate(key, request.getHeader("X-Timestamp"), request.getHeader("X-Signature"),
                request.getMethod(), target, new String(cached.getCachedBody(), StandardCharsets.UTF_8));
        ManagementContext.set(identity);
        request.setAttribute("AUTH_SOURCE", SOURCE);
        return true;
    }

    /** 只有正式认证链建立的上下文和请求属性同时存在才可跳过人员登录入口。 */
    public static boolean verified(HttpServletRequest request) {
        return ManagementContext.get() != null && SOURCE.equals(request.getAttribute("AUTH_SOURCE"))
                && ManagementActions.action(request.getMethod(), request.getRequestURI()) != null;
    }
}
