package com.skillforge.platform.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.platform.controller.PlatformAgentToolController;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

/** 仅在认证、授权拦截器本身将同步写请求拒绝时标识确定结果。 */
final class SystemAgentToolWriteRejection {
    private SystemAgentToolWriteRejection() { }
    static RuntimeException beforeDispatch(HttpServletRequest request, Object handler, RuntimeException failure) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod method)
                || method.getBeanType() != PlatformAgentToolController.class
                || !Set.of("create", "update", "publish").contains(method.getMethod().getName())) return failure;
        if (failure instanceof ApiException api && (api.getStatus() == HttpStatus.UNAUTHORIZED || api.getStatus() == HttpStatus.FORBIDDEN))
            return new ApiException(api.getStatus(), PlatformAgentToolController.WRITE_REJECTED, String.valueOf(api.getDetail()), false);
        if (failure instanceof NotLoginException)
            return new ApiException(HttpStatus.UNAUTHORIZED, PlatformAgentToolController.WRITE_REJECTED, "未登录或登录已过期", false);
        if (failure instanceof NotPermissionException)
            return new ApiException(HttpStatus.FORBIDDEN, PlatformAgentToolController.WRITE_REJECTED, "无权访问该资源", false);
        return failure;
    }
}
