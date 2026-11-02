package com.skillforge.core.shared.web;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

/**
 * 租户上下文拦截器。
 *
 * <p>请求处理前从 JWT 扩展字段中读取 {@code tenant_id}，保留宿主外部认证已验证的租户身份；
 * 未认证请求头不能创建或覆盖租户上下文。
 * 请求线程上下文的清理由外层 {@link HostTraceIdFilter} 负责，覆盖异步交接及认证失败。
 */
public class HostTenantContextInterceptor implements HandlerInterceptor {

    /**
     * 请求进入控制器前设置租户上下文。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  目标处理器对象
     * @return 始终返回 true，不阻断请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 已登录用户优先从 JWT 扩展字段读取租户 ID
        boolean loggedIn = false;
        try {
            loggedIn = StpUtil.isLogin();
        } catch (SaTokenException ignored) {
            // 错误页派发阶段可能没有 Sa-Token 上下文，按未登录处理
        }
        if (loggedIn && com.skillforge.common.context.ManagementContext.get() == null) {
            Object tenantId = StpUtil.getExtra("tenant_id");
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId.toString());
            }
            RequestContext.setUserId(StpUtil.getLoginIdAsString());
        }
        RequestContext.setModule(handler instanceof HandlerMethod method
                ? method.getBeanType().getSimpleName() : "");
        RequestObservability.syncMdc();
        return true;
    }

}
