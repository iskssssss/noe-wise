package com.skillforge.core.shared.web;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求线程上下文与追踪 ID 的生命周期边界。
 *
 * <p>每次 Servlet 派发进入时清除线程残留，生成或恢复同一请求的追踪 ID；
 * 返回线程池前统一清理身份和日志上下文。SSE 原请求交接异步处理后也立即清理，
 * 不依赖只在最终完成时触发的 MVC afterCompletion。业务异步线程仍须显式传播和清理。
 */
public class HostTraceIdFilter extends OncePerRequestFilter {
    private final org.springframework.web.servlet.HandlerExceptionResolver errors;
    public HostTraceIdFilter(@org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
            org.springframework.web.servlet.HandlerExceptionResolver errors) { this.errors = errors; }


    private static final String TRACE_ID_ATTRIBUTE = HostTraceIdFilter.class.getName() + ".traceId";

    /**
     * 执行请求过滤。
     *
     * @param request      HTTP 请求
     * @param response     HTTP 响应
     * @param filterChain  过滤链
     * @throws ServletException 过滤链异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        clearThreadContext();
        try {
            String traceId = (String) request.getAttribute(TRACE_ID_ATTRIBUTE);
            if (traceId == null) {
                traceId = request.getHeader("X-Trace-Id");
                if (traceId == null || !traceId.matches("[A-Za-z0-9._:-]{1,128}")) {
                    traceId = String.valueOf(SnowflakeIds.nextId()).replace("-", "");
                }
                request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
            }
            RequestContext.setTraceId(traceId);
            response.setHeader("X-Trace-Id", traceId);
            RequestObservability.syncMdc();
            try { filterChain.doFilter(request, response); }
            catch (Exception failure) {
                if (response.isCommitted() || errors.resolveException(request, response, null, failure) == null) {
                    if (failure instanceof IOException io) throw io;
                    if (failure instanceof ServletException servlet) throw servlet;
                    throw new ServletException(failure);
                }
            }
        } finally {
            clearThreadContext();
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private static void clearThreadContext() {
        TenantContext.clear();
        ChannelContext.clear();
        ServicePrincipalContext.clear();
        ManagementContext.clear();
        RequestObservability.clear();
    }
}
