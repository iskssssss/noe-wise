package com.skillforge.core.shared.web;

import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import org.slf4j.MDC;

/**
 * 可观测性工具。
 *
 * <p>将请求上下文同步到 SLF4J MDC，使结构化日志始终携带
 * trace_id / tenant_id / user_id / module / action 五个核心字段。
 */
public final class RequestObservability {

    private RequestObservability() {
    }

    /**
     * 同步当前线程上下文到 MDC。
     */
    public static void syncMdc() {
        MDC.put("trace_id", orEmpty(RequestContext.getTraceId()));
        MDC.put("tenant_id", orEmpty(TenantContext.getTenantId()));
        MDC.put("user_id", orEmpty(RequestContext.getUserId()));
        MDC.put("module", orEmpty(RequestContext.getModule()));
        MDC.put("action", "");
    }

    /**
     * 清理 MDC 与请求上下文。
     */
    public static void clear() {
        MDC.clear();
        RequestContext.clear();
    }

    /**
     * 将空值转换为空字符串。
     *
     * @param value 原始值
     * @return 空字符串或原始值
     */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
